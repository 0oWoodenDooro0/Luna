package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import luna.chatter.emoji.BoundedChannelEmojiCadenceTracker
import luna.chatter.emoji.ChatterEmojiCatalog
import luna.chatter.emoji.CustomEmojiVisionResolver
import luna.chatter.emoji.DefaultChatterEmojiCatalog
import luna.chatter.emoji.ExposedCustomEmojiMeaningRepository
import luna.chatter.emoji.ExposedEmojiUsageRankingRepository
import luna.chatter.emoji.NoOpChatterEmojiCatalog
import luna.chatter.memory.ChatterMemoryConfig
import luna.chatter.memory.ChatterMemoryStore
import luna.chatter.memory.ChatterMemoryToolHandler
import luna.chatter.memory.MemoryCommand
import luna.chatter.memory.MemoryDistillationCoordinator
import luna.chatter.memory.MemoryDistiller
import luna.chatter.memory.SqliteChatterMemoryStore
import luna.config.ConfigValidationException
import luna.core.JsonLogger
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.SocialPeekFactory
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import org.slf4j.LoggerFactory

internal fun safeScope(kord: Kord): CoroutineScope {
    val hasValidContext =
        runCatching {
            kord.coroutineContext.fold(true) { acc, _ -> acc }
        }.getOrDefault(false)

    return if (hasValidContext) kord else CoroutineScope(Dispatchers.Default)
}

/**
 * Luna Plugin encapsulating autonomous conversational participation in Discord channels (Chatter).
 *
 * Uses Google Gemini Decision 1 for low-latency participation evaluation (`POST /v1beta/decisions`)
 * and Google Gemini for persona-grounded conversational dialogue generation.
 */
class ChatterPlugin(
    private val decisionClientFactory: ((ChatterDecisionConfig) -> GeminiDecisionClient)? = null,
    private val generatorFactory: ((ChatterConfig) -> ChatterGenerator)? = null,
    private val serviceFactory: ((ChatterService) -> ChatterService)? = null,
) : LunaPlugin {
    constructor() : this(null, null, null)

    override val name: String = "chatter"

    var decisionClient: GeminiDecisionClient? = null
        internal set

    var chatterGenerator: ChatterGenerator? = null
        internal set

    var service: ChatterService? = null
        internal set

    var filter: ChatterNoiseFilter? = null
        internal set

    var cooldownCache: BoundedChannelCooldownCache? = null
        internal set

    var burstCoordinator: BoundedChannelBurstCoordinator? = null
        internal set

    var contextCollector: ChatterContextCollector? = null
        internal set

    var personaStateProvider: PersonaStateProvider? = null
        internal set

    var emojiCatalog: ChatterEmojiCatalog? = null
        internal set

    var emojiCadenceTracker: BoundedChannelEmojiCadenceTracker? = null
        internal set

    var emojiVisionResolver: CustomEmojiVisionResolver? = null
        internal set

    var memoryStore: ChatterMemoryStore? = null
        internal set

    var memoryCommand: MemoryCommand? = null
        internal set

    var memoryToolHandler: ChatterMemoryToolHandler? = null
        internal set

    var memoryDistillationCoordinator: MemoryDistillationCoordinator? = null
        internal set

    private var embeddingClient: GeminiEmbeddingClient? = null
    private var visionHttpClient: HttpClient? = null
    private var distillationHttpClient: HttpClient? = null

    var maxImages: Int = ChatterContextCollector.DEFAULT_MAX_IMAGES
        internal set

    var isChatterEnabled: Boolean = false
        internal set

    private var registrationJob: Job? = null
    private var memoryCommandJob: Job? = null
    private var distillationObserverJob: Job? = null
    private var distillationTickerJob: Job? = null

    override suspend fun initialize(context: PluginContext) {
        val decisionConfig = ChatterDecisionConfig.from(context.env)
        val geminiApiKey = decisionConfig.apiKey

        val chatterEnabledConfig = context.env.boolean("CHATTER_ENABLED", true)
        val chatterEnabled = chatterEnabledConfig && geminiApiKey.isNotBlank()
        this.isChatterEnabled = chatterEnabled

        val allowedChannels: Set<Snowflake> =
            context.env
                .csv("CHATTER_ALLOWED_CHANNELS")
                .map { rawId ->
                    val idValue =
                        rawId.toULongOrNull()
                            ?: throw ConfigValidationException(
                                "Invalid configuration for CHATTER_ALLOWED_CHANNELS: " +
                                    "expected comma-separated Discord Snowflake IDs, but got '$rawId'",
                            )
                    Snowflake(idValue)
                }.toSet()

        val cooldownSeconds = context.env.long("CHATTER_COOLDOWN_SECONDS", 5L, 0L..Long.MAX_VALUE)
        val cooldownDurationMs = cooldownSeconds * 1000L

        val youtubeApiKey = context.env.string("YOUTUBE_API_KEY")

        val imagesLimit =
            context.env.int(
                "SUMMARY_MAX_IMAGES",
                ChatterContextCollector.DEFAULT_MAX_IMAGES,
                1..Int.MAX_VALUE,
            )
        this.maxImages = imagesLimit

        val traceRecorder = context.aiTraceRecorder

        val client =
            decisionClientFactory?.invoke(decisionConfig)
                ?: GeminiDecisionClient(
                    config = decisionConfig,
                    traceRecorder = traceRecorder,
                )
        this.decisionClient = client

        val chatterConfig = ChatterConfig.from(context.env)
        val memoryConfig = ChatterMemoryConfig.from(context.env)
        val initializedStore =
            if (memoryConfig.enabled) {
                runCatching {
                    SqliteChatterMemoryStore(
                        database = context.database,
                        defaultRecencyExcludeMinutes = memoryConfig.recencyExcludeMinutes.toInt(),
                        defaultScoreCutoff = memoryConfig.scoreCutoff,
                        maxPerUser = memoryConfig.maxPerUser,
                        maxPerChannel = memoryConfig.maxPerChannel,
                    )
                }.onFailure { e ->
                    logger.warn(
                        "Failed to initialize SqliteChatterMemoryStore; degrading without long-term memory: {}",
                        e.message,
                    )
                }.getOrNull()
            } else {
                null
            }
        this.memoryStore = initializedStore

        val initializedEmbeddingClient =
            if (memoryConfig.enabled && initializedStore != null && chatterConfig.isConfigured) {
                GeminiEmbeddingClient(
                    apiKey = chatterConfig.apiKey,
                    baseUrl = chatterConfig.cleanBaseUrl,
                    model = memoryConfig.cleanEmbeddingModel,
                )
            } else {
                null
            }
        this.embeddingClient = initializedEmbeddingClient

        val toolHandler =
            if (initializedStore != null && initializedEmbeddingClient != null) {
                ChatterMemoryToolHandler(
                    embeddingClient = initializedEmbeddingClient,
                    memoryStore = initializedStore,
                    config = memoryConfig,
                )
            } else {
                null
            }
        this.memoryToolHandler = toolHandler

        val generator =
            generatorFactory?.invoke(chatterConfig)
                ?: ChatterGenerator(
                    config = chatterConfig,
                    traceRecorder = traceRecorder,
                    memoryConfig = memoryConfig,
                    memoryToolHandler = toolHandler,
                )
        this.chatterGenerator = generator

        val cooldown = BoundedChannelCooldownCache(defaultCooldownDurationMs = cooldownDurationMs)
        this.cooldownCache = cooldown

        val coordinator =
            BoundedChannelBurstCoordinator(
                debounceMs = chatterConfig.debounceMillis,
                mentionDebounceMs = chatterConfig.mentionDebounceMillis,
                maxDebounceMs = chatterConfig.debounceMaxMillis,
                maxInterrupts = chatterConfig.normalizedMaxInterrupts,
            )
        this.burstCoordinator = coordinator

        val noiseFilter =
            ChatterNoiseFilter(
                cooldownCache = cooldown,
                enabled = chatterEnabled,
                allowedChannels = allowedChannels,
                cooldownDurationMs = cooldownDurationMs,
            )
        this.filter = noiseFilter

        val peekResolver = SocialPeekFactory.buildPeekResolver(youtubeApiKey)
        val collector =
            ChatterContextCollector(
                noiseFilter = noiseFilter,
                decisionMessageCount = decisionConfig.decisionMessageCount,
                historyMessageCount = memoryConfig.historyMessageCount,
                maxImages = imagesLimit,
                peekResolver = peekResolver,
                historyWindowMinutes = memoryConfig.historyWindowMinutes,
            )
        this.contextCollector = collector

        val drift =
            CircadianPersonaDrift(
                timezone = chatterConfig.timezone,
                enabled = chatterConfig.driftEnabled,
            )
        this.personaStateProvider = drift

        val catalog =
            runCatching {
                val meaningRepository = ExposedCustomEmojiMeaningRepository(context.database)
                val usageRepository = ExposedEmojiUsageRankingRepository(context.database)
                val resolver =
                    chatterConfig.cleanEmojiVisionModel?.takeIf { chatterConfig.isConfigured }?.let { visionModel ->
                        val httpClient = CustomEmojiVisionResolver.createDefaultHttpClient()
                        this.visionHttpClient = httpClient
                        CustomEmojiVisionResolver(
                            meaningRepository = meaningRepository,
                            visionModel = visionModel,
                            interactionClient =
                                GeminiInteractionClient(
                                    httpClient = httpClient,
                                    traceRecorder = traceRecorder,
                                ),
                            baseUrl = chatterConfig.cleanBaseUrl,
                            apiKey = chatterConfig.apiKey,
                        )
                    }
                this.emojiVisionResolver = resolver
                DefaultChatterEmojiCatalog(
                    meaningRepository = meaningRepository,
                    usageRepository = usageRepository,
                    visionResolver = resolver,
                )
            }.onFailure { e ->
                logger.warn(
                    "Failed to initialize Custom Emoji Meaning repository; degrading to no-op emoji catalog: {}",
                    e.message,
                )
            }.getOrElse { NoOpChatterEmojiCatalog }
        this.emojiCatalog = catalog

        val cadenceTracker = BoundedChannelEmojiCadenceTracker()
        this.emojiCadenceTracker = cadenceTracker

        val chatterService =
            ChatterService(
                filter = noiseFilter,
                decisionClient = client,
                chatterGenerator = generator,
                cooldownCache = cooldown,
                decisionMessageCount = decisionConfig.decisionMessageCount,
                historyMessageCount = memoryConfig.historyMessageCount,
                contextCollector = collector,
                traceRecorder = traceRecorder,
                burstCoordinator = coordinator,
                afterMessagesFetcher = ChatterService.defaultAfterMessagesFetcher,
                personaStateProvider = drift,
                guildEmojisProvider = ChatterService.defaultGuildEmojisProvider,
                emojiCatalog = catalog,
                emojiCadenceTracker = cadenceTracker,
            )
        val finalService = serviceFactory?.invoke(chatterService) ?: chatterService
        this.service = finalService

        if (initializedStore != null) {
            val command = MemoryCommand(initializedStore)
            this.memoryCommand = command
            try {
                val scope = safeScope(context.kord)
                command.register(context.kord)
                this.memoryCommandJob =
                    context.kord.on<ChatInputCommandInteractionCreateEvent>(scope = scope) {
                        if (interaction.command.rootName == command.name) {
                            try {
                                command.handle(interaction)
                                JsonLogger.log(
                                    layer = "COMMAND",
                                    component = command.javaClass.simpleName,
                                    operation = "handle",
                                    data =
                                        mapOf(
                                            "userId" to interaction.user.id.toString(),
                                            "command" to command.name,
                                        ),
                                )
                            } catch (e: Exception) {
                                JsonLogger.error(
                                    layer = "COMMAND",
                                    component = command.javaClass.simpleName,
                                    operation = "handle",
                                    data =
                                        mapOf(
                                            "userId" to interaction.user.id.toString(),
                                            "command" to command.name,
                                        ),
                                    errorMessage = e.message,
                                )
                            }
                        }
                    }
            } catch (e: Exception) {
                logger.warn("Could not register /memory command to Kord: {}", e.message)
            }
        }

        if (geminiApiKey.isBlank()) {
            logger.info("GEMINI_API_KEY is not configured; ChatterPlugin running in disabled mode")
            return
        }

        if (!chatterEnabled) {
            logger.info("ChatterPlugin is explicitly disabled via configuration (CHATTER_ENABLED=false)")
            return
        }

        try {
            val scope = safeScope(context.kord)
            this.registrationJob = finalService.register(context.kord, scope = scope)
            initializeMemoryDistillation(
                context = context,
                chatterConfig = chatterConfig,
                memoryConfig = memoryConfig,
                memoryStore = initializedStore,
                embeddingClient = initializedEmbeddingClient,
                noiseFilter = noiseFilter,
                allowedChannels = allowedChannels,
                scope = scope,
            )
            logger.info("ChatterPlugin initialized successfully and registered message listener")
        } catch (e: Exception) {
            logger.warn("Could not register ChatterPlugin message listener to Kord: {}", e.message)
        }
    }

    private fun initializeMemoryDistillation(
        context: PluginContext,
        chatterConfig: ChatterConfig,
        memoryConfig: ChatterMemoryConfig,
        memoryStore: ChatterMemoryStore?,
        embeddingClient: GeminiEmbeddingClient?,
        noiseFilter: ChatterNoiseFilter,
        allowedChannels: Set<Snowflake>,
        scope: CoroutineScope,
    ) {
        if (!memoryConfig.enabled || memoryStore == null || embeddingClient == null) return

        runCatching {
            val distillHttp = MemoryDistiller.createDefaultHttpClient().also { this.distillationHttpClient = it }
            val distiller =
                MemoryDistiller(
                    memoryStore = memoryStore,
                    embeddingClient = embeddingClient,
                    interactionClient =
                        GeminiInteractionClient(
                            httpClient = distillHttp,
                            traceRecorder = context.aiTraceRecorder,
                        ),
                    noiseFilter = noiseFilter,
                    config = memoryConfig,
                    apiKey = chatterConfig.apiKey,
                    baseUrl = chatterConfig.cleanBaseUrl,
                    traceRecorder = context.aiTraceRecorder,
                    messagesAfterFetcher = { channelId, afterMessageId, limit ->
                        runCatching { context.kord.getChannelOf<MessageChannel>(channelId) }
                            .getOrNull()
                            ?.getMessagesAfter(afterMessageId, limit = limit)
                            ?.toList()
                            ?: emptyList()
                    },
                    kordSelfIdProvider = { runCatching { context.kord.selfId }.getOrNull() },
                )
            val distCoordinator =
                MemoryDistillationCoordinator(
                    memoryStore = memoryStore,
                    noiseFilter = noiseFilter,
                    config = memoryConfig,
                    allowedChannels = allowedChannels,
                    chatterEnabled = isChatterEnabled,
                    kordSelfIdProvider = { runCatching { context.kord.selfId }.getOrNull() },
                    distillExecutor = distiller::distillChannel,
                )
            this.memoryDistillationCoordinator = distCoordinator
            this.distillationObserverJob = distCoordinator.registerMessageObserver(context.kord, scope = scope)
            this.distillationTickerJob = distCoordinator.startTicker(scope = scope)
        }.onFailure { e ->
            logger.warn("Failed to initialize Memory Distillation; continuing without background distillation: {}", e.message)
        }
    }

    override suspend fun shutdown() {
        registrationJob?.cancel()
        memoryCommandJob?.cancel()
        memoryCommandJob = null
        distillationObserverJob?.cancel()
        distillationObserverJob = null
        distillationTickerJob?.cancel()
        distillationTickerJob = null
        decisionClient?.close()
        chatterGenerator?.close()
        embeddingClient?.close()
        embeddingClient = null
        visionHttpClient?.close()
        visionHttpClient = null
        distillationHttpClient?.close()
        distillationHttpClient = null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ChatterPlugin::class.java)
    }
}
