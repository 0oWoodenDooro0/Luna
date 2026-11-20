package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import luna.chatter.memory.SqliteChatterMemoryStore
import luna.core.JsonLogger
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.SocialPeekFactory
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import luna.plugin.config
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

    var emojiVisionResolver: CustomEmojiVisionResolver? = null
        internal set

    var memoryStore: ChatterMemoryStore? = null
        internal set

    var memoryCommand: MemoryCommand? = null
        internal set

    var memoryToolHandler: ChatterMemoryToolHandler? = null
        internal set

    private var embeddingClient: GeminiEmbeddingClient? = null
    private var visionHttpClient: HttpClient? = null

    var maxImages: Int = ChatterContextCollector.DEFAULT_MAX_IMAGES
        internal set

    var isChatterEnabled: Boolean = false
        internal set

    private var registrationJob: Job? = null
    private var memoryCommandJob: Job? = null

    override suspend fun initialize(context: PluginContext) {
        val decisionConfig = ChatterDecisionConfig.fromPluginContext(context)
        val geminiApiKey = decisionConfig.apiKey

        val chatterEnabledConfig =
            context.config<Boolean>("CHATTER_ENABLED")
                ?: context.config<String>("CHATTER_ENABLED")?.toBooleanStrictOrNull()
                ?: context.config<Boolean>("chatter.enabled")
                ?: context.config<String>("chatter.enabled")?.toBooleanStrictOrNull()

        val chatterEnabled = (chatterEnabledConfig ?: true) && geminiApiKey.isNotBlank()
        this.isChatterEnabled = chatterEnabled

        val allowedChannelsRaw =
            context.config<String>("CHATTER_ALLOWED_CHANNELS")
                ?: context.config<String>("chatter.allowedChannels")
                ?: context.config<String>("chatter.allowed_channels")
                ?: ""

        val allowedChannels: Set<Snowflake> =
            if (allowedChannelsRaw.isBlank()) {
                emptySet()
            } else {
                allowedChannelsRaw
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .mapNotNull { runCatching { Snowflake(it) }.getOrNull() }
                    .toSet()
            }

        val cooldownSeconds =
            context.config<Long>("CHATTER_COOLDOWN_SECONDS")
                ?: context.config<Int>("CHATTER_COOLDOWN_SECONDS")?.toLong()
                ?: context.config<String>("CHATTER_COOLDOWN_SECONDS")?.toLongOrNull()
                ?: context.config<Long>("chatter.cooldownSeconds")
                ?: context.config<Int>("chatter.cooldownSeconds")?.toLong()
                ?: context.config<String>("chatter.cooldownSeconds")?.toLongOrNull()
                ?: 5L
        val cooldownDurationMs = cooldownSeconds * 1000L

        val youtubeApiKey =
            context.config<String>("YOUTUBE_API_KEY")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("youtube.apiKey")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("youtube.api_key")?.takeIf { it.isNotBlank() }

        val imagesLimit =
            context.config<Int>("SUMMARY_MAX_IMAGES")?.takeIf { it > 0 }
                ?: context.config<String>("SUMMARY_MAX_IMAGES")?.toIntOrNull()?.takeIf { it > 0 }
                ?: context.config<Int>("summary.maxImages")?.takeIf { it > 0 }
                ?: context.config<String>("summary.maxImages")?.toIntOrNull()?.takeIf { it > 0 }
                ?: ChatterContextCollector.DEFAULT_MAX_IMAGES
        this.maxImages = imagesLimit

        val traceRecorder = context.aiTraceRecorder

        val client =
            decisionClientFactory?.invoke(decisionConfig)
                ?: GeminiDecisionClient(
                    config = decisionConfig,
                    traceRecorder = traceRecorder,
                )
        this.decisionClient = client

        val chatterConfig = ChatterConfig.fromPluginContext(context)
        val memoryConfig = ChatterMemoryConfig.fromPluginContext(context)
        val initializedStore =
            if (memoryConfig.enabled) {
                runCatching {
                    SqliteChatterMemoryStore(
                        database = context.database,
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
            logger.info("ChatterPlugin initialized successfully and registered message listener")
        } catch (e: Exception) {
            logger.warn("Could not register ChatterPlugin message listener to Kord: {}", e.message)
        }
    }

    override suspend fun shutdown() {
        registrationJob?.cancel()
        memoryCommandJob?.cancel()
        memoryCommandJob = null
        decisionClient?.close()
        chatterGenerator?.close()
        embeddingClient?.close()
        embeddingClient = null
        visionHttpClient?.close()
        visionHttpClient = null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ChatterPlugin::class.java)
    }
}
