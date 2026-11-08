package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Uses Laya Studio System 1 for low-latency decision filtering (`POST /v1/systemone`) and
 * Google Gemini for persona-grounded conversational dialogue generation.
 */
class ChatterPlugin(
    private val layaClientFactory: ((LayaConfig) -> LayaClient)? = null,
    private val generatorFactory: ((ChatterConfig) -> ChatterGenerator)? = null,
    private val serviceFactory: ((ChatterService) -> ChatterService)? = null,
) : LunaPlugin {
    constructor() : this(null, null, null)

    override val name: String = "chatter"

    var layaClient: LayaClient? = null
        internal set

    var chatterGenerator: ChatterGenerator? = null
        internal set

    var service: ChatterService? = null
        internal set

    var filter: ChatterNoiseFilter? = null
        internal set

    var cooldownCache: BoundedChannelCooldownCache? = null
        internal set

    var isChatterEnabled: Boolean = false
        internal set

    private var registrationJob: Job? = null

    override suspend fun initialize(context: PluginContext) {
        val layaApiKey =
            context.config<String>("LAYA_API_KEY")?.trim()?.ifBlank { null }
                ?: context.config<String>("laya.apiKey")?.trim()?.ifBlank { null }
                ?: context.config<String>("laya.api_key")?.trim()?.ifBlank { null }
                ?: ""

        val rawThreshold =
            context.config<Double>("LAYA_THRESHOLD")
                ?: context.config<String>("LAYA_THRESHOLD")?.toDoubleOrNull()
                ?: context.config<Double>("laya.threshold")
                ?: context.config<String>("laya.threshold")?.toDoubleOrNull()
                ?: LayaConfig.DEFAULT_THRESHOLD

        val layaEndpoint =
            context.config<String>("LAYA_ENDPOINT")?.trim()?.ifBlank { null }
                ?: context.config<String>("laya.endpoint")?.trim()?.ifBlank { null }
                ?: LayaConfig.DEFAULT_ENDPOINT

        val chatterEnabledConfig =
            context.config<Boolean>("CHATTER_ENABLED")
                ?: context.config<String>("CHATTER_ENABLED")?.toBooleanStrictOrNull()
                ?: context.config<Boolean>("chatter.enabled")
                ?: context.config<String>("chatter.enabled")?.toBooleanStrictOrNull()

        val chatterEnabled = chatterEnabledConfig ?: layaApiKey.isNotBlank()
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
                ?: 30L
        val cooldownDurationMs = cooldownSeconds * 1000L

        val geminiApiKey =
            context.config<String>("GEMINI_API_KEY")?.trim()?.ifBlank { null }
                ?: context.config<String>("gemini.apiKey")?.trim()?.ifBlank { null }
                ?: context.config<String>("gemini.api_key")?.trim()?.ifBlank { null }
                ?: ""

        val rawGeminiModel =
            context.config<String>("CHATTER_GEMINI_MODEL")?.trim()?.ifBlank { null }
                ?: context.config<String>("chatter.gemini.model")?.trim()?.ifBlank { null }
                ?: context.config<String>("chatter.model")?.trim()?.ifBlank { null }
                ?: context.config<String>("GEMINI_MODEL")?.trim()?.ifBlank { null }
                ?: context.config<String>("gemini.model")?.trim()?.ifBlank { null }
                ?: ChatterConfig.DEFAULT_MODEL
        val geminiModel = rawGeminiModel.removePrefix("models/")

        val geminiBaseUrl =
            context.config<String>("GEMINI_BASE_URL")?.trim()?.ifBlank { null }
                ?: context.config<String>("gemini.baseUrl")?.trim()?.ifBlank { null }
                ?: ChatterConfig.DEFAULT_BASE_URL

        val layaConfig =
            LayaConfig(
                apiKey = layaApiKey,
                threshold = rawThreshold,
                endpoint = layaEndpoint,
            )
        val laya = layaClientFactory?.invoke(layaConfig) ?: LayaClient(config = layaConfig)
        this.layaClient = laya

        val chatterConfig =
            ChatterConfig(
                apiKey = geminiApiKey,
                model = geminiModel,
                baseUrl = geminiBaseUrl,
            )
        val generator = generatorFactory?.invoke(chatterConfig) ?: ChatterGenerator(config = chatterConfig)
        this.chatterGenerator = generator

        val cooldown = BoundedChannelCooldownCache(defaultCooldownDurationMs = cooldownDurationMs)
        this.cooldownCache = cooldown

        val noiseFilter =
            ChatterNoiseFilter(
                cooldownCache = cooldown,
                enabled = chatterEnabled,
                allowedChannels = allowedChannels,
                cooldownDurationMs = cooldownDurationMs,
            )
        this.filter = noiseFilter

        val chatterService =
            ChatterService(
                filter = noiseFilter,
                layaClient = laya,
                chatterGenerator = generator,
                cooldownCache = cooldown,
            )
        val finalService = serviceFactory?.invoke(chatterService) ?: chatterService
        this.service = finalService

        if (layaApiKey.isBlank()) {
            logger.info("LAYA_API_KEY is not configured; ChatterPlugin running in disabled mode")
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
        layaClient?.close()
        chatterGenerator?.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ChatterPlugin::class.java)
    }
}
