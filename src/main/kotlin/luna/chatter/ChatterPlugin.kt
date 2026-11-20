package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.channel.MessageChannel
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import luna.chatter.emoji.ChatterEmojiCatalog
import luna.chatter.emoji.CustomEmojiVisionResolver
import luna.chatter.emoji.DefaultChatterEmojiCatalog
import luna.chatter.emoji.ExposedCustomEmojiMeaningRepository
import luna.chatter.emoji.ExposedEmojiUsageRankingRepository
import luna.chatter.emoji.NoOpChatterEmojiCatalog
import luna.chatter.memory.ChatterMemoryConfig
import luna.chatter.memory.MemoryDistillationCoordinator
import luna.chatter.memory.MemoryDistiller
import luna.chatter.memory.SqliteChatterMemoryStore
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

    var memoryDistillationCoordinator: MemoryDistillationCoordinator? = null
        internal set

    private var visionHttpClient: HttpClient? = null
    private var distillationHttpClient: HttpClient? = null
    private var memoryEmbeddingClient: GeminiEmbeddingClient? = null

    var maxImages: Int = ChatterContextCollector.DEFAULT_MAX_IMAGES
        internal set

    var isChatterEnabled: Boolean = false
        internal set

    private var registrationJob: Job? = null
    private var distillationObserverJob: Job? = null
    private var distillationTickerJob: Job? = null

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
        val generator =
            generatorFactory?.invoke(chatterConfig)
                ?: ChatterGenerator(config = chatterConfig, traceRecorder = traceRecorder)
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
                maxImages = imagesLimit,
                peekResolver = peekResolver,
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
        noiseFilter: ChatterNoiseFilter,
        allowedChannels: Set<Snowflake>,
        scope: CoroutineScope,
    ) {
        val memoryConfig = ChatterMemoryConfig.fromPluginContext(context)
        if (!memoryConfig.enabled) return

        runCatching {
            val memoryStore =
                SqliteChatterMemoryStore(
                    database = context.database,
                    defaultRecencyExcludeMinutes = memoryConfig.recencyExcludeMinutes.toInt(),
                    defaultScoreCutoff = memoryConfig.scoreCutoff,
                    maxPerUser = memoryConfig.maxPerUser,
                    maxPerChannel = memoryConfig.maxPerChannel,
                )
            val embeddingClient =
                GeminiEmbeddingClient(
                    apiKey = chatterConfig.apiKey,
                    baseUrl = chatterConfig.cleanBaseUrl,
                    model = memoryConfig.cleanEmbeddingModel,
                ).also { this.memoryEmbeddingClient = it }
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
        distillationObserverJob?.cancel()
        distillationTickerJob?.cancel()
        decisionClient?.close()
        chatterGenerator?.close()
        visionHttpClient?.close()
        visionHttpClient = null
        distillationHttpClient?.close()
        distillationHttpClient = null
        memoryEmbeddingClient?.close()
        memoryEmbeddingClient = null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ChatterPlugin::class.java)
    }
}
