package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import luna.chatter.emoji.ChatterEmojiService
import luna.chatter.emoji.ChatterEmojiVisionResolver
import luna.chatter.emoji.DefaultChatterEmojiService
import luna.chatter.memory.ChatterMemoryConfig
import luna.chatter.memory.ChatterMemoryService
import luna.config.ConfigValidationException
import luna.core.ai.GeminiHttpClientFactory
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

    var emojiService: ChatterEmojiService? = null
        internal set

    var memoryService: ChatterMemoryService? = null
        internal set

    var sharedHttpClient: HttpClient? = null
        internal set

    var maxImages: Int = ChatterContextCollector.DEFAULT_MAX_IMAGES
        internal set

    var isChatterEnabled: Boolean = false
        internal set

    private var registrationJob: Job? = null

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
        val httpClient = GeminiHttpClientFactory.create().also { this.sharedHttpClient = it }

        val client =
            decisionClientFactory?.invoke(decisionConfig)
                ?: GeminiDecisionClient(
                    config = decisionConfig,
                    httpClient = httpClient,
                    traceRecorder = traceRecorder,
                )
        this.decisionClient = client

        val chatterConfig = ChatterConfig.from(context.env)
        val memoryConfig = ChatterMemoryConfig.from(context.env)

        val cooldown = BoundedChannelCooldownCache(defaultCooldownDurationMs = cooldownDurationMs)
        this.cooldownCache = cooldown

        val coordinator =
            BoundedChannelBurstCoordinator(
                debounceMs = chatterConfig.debounceMillis,
                mentionDebounceMs = chatterConfig.mentionDebounceMillis,
                maxDebounceMs = chatterConfig.debounceMaxMillis,
                maxInterrupts = chatterConfig.maxInterrupts,
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

        val initializedMemoryService =
            if (memoryConfig.enabled) {
                runCatching {
                    ChatterMemoryService.create(
                        database = context.database,
                        sharedHttpClient = httpClient,
                        chatterConfig = chatterConfig,
                        memoryConfig = memoryConfig,
                        noiseFilter = noiseFilter,
                        allowedChannels = allowedChannels,
                        chatterEnabled = chatterEnabled,
                        kord = context.kord,
                        traceRecorder = traceRecorder,
                    )
                }.onFailure { e ->
                    logger.warn(
                        "Failed to initialize ChatterMemoryService; degrading without long-term memory: {}",
                        e.message,
                    )
                }.getOrNull()
            } else {
                null
            }
        this.memoryService = initializedMemoryService

        val generator =
            generatorFactory?.invoke(chatterConfig)
                ?: ChatterGenerator(
                    config = chatterConfig,
                    httpClient = httpClient,
                    traceRecorder = traceRecorder,
                    memoryConfig = memoryConfig,
                    memoryToolHandler = initializedMemoryService?.toolHandler,
                )
        this.chatterGenerator = generator

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

        val emojiServiceInstance: ChatterEmojiService =
            runCatching {
                val visionModel = chatterConfig.cleanEmojiVisionModel?.takeIf { chatterConfig.isConfigured }
                val visionInteractionClient =
                    visionModel?.let {
                        GeminiInteractionClient(
                            httpClient = httpClient,
                            traceRecorder = traceRecorder,
                            requestTimeoutMs = ChatterEmojiVisionResolver.DEFAULT_HTTP_REQUEST_TIMEOUT_MS,
                            connectTimeoutMs = ChatterEmojiVisionResolver.DEFAULT_HTTP_CONNECT_TIMEOUT_MS,
                            socketTimeoutMs = ChatterEmojiVisionResolver.DEFAULT_HTTP_SOCKET_TIMEOUT_MS,
                        )
                    }
                DefaultChatterEmojiService(
                    database = context.database,
                    visionModel = visionModel,
                    interactionClient = visionInteractionClient,
                    baseUrl = chatterConfig.cleanBaseUrl,
                    apiKey = chatterConfig.apiKey,
                )
            }.onFailure { e ->
                logger.warn(
                    "Failed to initialize ChatterEmojiService with SQLite; degrading to in-memory emoji service: {}",
                    e.message,
                )
            }.getOrElse { DefaultChatterEmojiService() }
        this.emojiService = emojiServiceInstance

        val chatterService =
            ChatterService(
                decisionClient = client,
                chatterGenerator = generator,
                filter = noiseFilter,
                contextCollector = collector,
                traceRecorder = traceRecorder,
                burstCoordinator = coordinator,
                personaStateProvider = drift,
                emojiService = emojiServiceInstance,
            )
        val finalService = serviceFactory?.invoke(chatterService) ?: chatterService
        this.service = finalService

        val scope = safeScope(context.kord)
        initializedMemoryService?.start(kord = context.kord, scope = scope, chatterEnabled = chatterEnabled)

        if (geminiApiKey.isBlank()) {
            logger.info("GEMINI_API_KEY is not configured; ChatterPlugin running in disabled mode")
            return
        }

        if (!chatterEnabled) {
            logger.info("ChatterPlugin is explicitly disabled via configuration (CHATTER_ENABLED=false)")
            return
        }

        try {
            this.registrationJob = finalService.register(context.kord, scope = scope)
            logger.info("ChatterPlugin initialized successfully and registered message listener")
        } catch (e: Exception) {
            logger.warn("Could not register ChatterPlugin message listener to Kord: {}", e.message)
        }
    }

    override suspend fun shutdown() {
        registrationJob?.cancel()
        memoryService?.close()
        memoryService = null
        decisionClient?.close()
        chatterGenerator?.close()
        sharedHttpClient?.close()
        sharedHttpClient = null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ChatterPlugin::class.java)
    }
}
