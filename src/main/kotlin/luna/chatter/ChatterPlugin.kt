package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    var inFlightTracker: BoundedChannelInFlightTracker? = null
        internal set

    var contextCollector: ChatterContextCollector? = null
        internal set

    var maxImages: Int = ChatterContextCollector.DEFAULT_MAX_IMAGES
        internal set

    var isChatterEnabled: Boolean = false
        internal set

    private var registrationJob: Job? = null

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
                ?: GeminiDecisionClient(config = decisionConfig, traceRecorder = traceRecorder)
        this.decisionClient = client

        val chatterConfig = ChatterConfig.fromPluginContext(context)
        val generator =
            generatorFactory?.invoke(chatterConfig)
                ?: ChatterGenerator(config = chatterConfig, traceRecorder = traceRecorder)
        this.chatterGenerator = generator

        val cooldown = BoundedChannelCooldownCache(defaultCooldownDurationMs = cooldownDurationMs)
        this.cooldownCache = cooldown

        val tracker = BoundedChannelInFlightTracker()
        this.inFlightTracker = tracker

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
                maxImages = imagesLimit,
                peekResolver = peekResolver,
            )
        this.contextCollector = collector

        val chatterService =
            ChatterService(
                filter = noiseFilter,
                decisionClient = client,
                chatterGenerator = generator,
                cooldownCache = cooldown,
                contextCollector = collector,
                traceRecorder = traceRecorder,
                inFlightTracker = tracker,
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
            logger.info("ChatterPlugin initialized successfully and registered message listener")
        } catch (e: Exception) {
            logger.warn("Could not register ChatterPlugin message listener to Kord: {}", e.message)
        }
    }

    override suspend fun shutdown() {
        registrationJob?.cancel()
        decisionClient?.close()
        chatterGenerator?.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ChatterPlugin::class.java)
    }
}
