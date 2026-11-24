package luna.chatter

import dev.kord.core.Kord
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import luna.chatter.emoji.ChatterEmojiService
import luna.chatter.emoji.ChatterEmojiVisionResolver
import luna.chatter.emoji.DefaultChatterEmojiService
import luna.chatter.memory.ChatterMemoryService
import luna.chatter.schedule.DailyScheduleCoordinator
import luna.chatter.schedule.DailyScheduleGenerator
import luna.chatter.schedule.KordSchedulePresencePort
import luna.chatter.schedule.SqliteDailyScheduleStore
import luna.core.ai.GeminiHttpClientFactory
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.SocialPeekFactory
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import org.slf4j.LoggerFactory
import java.time.ZoneId

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
    private val decisionClientFactory: ((ChatterConfig) -> GeminiDecisionClient)? = null,
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

    var scheduleCoordinator: DailyScheduleCoordinator? = null
        internal set

    var sharedHttpClient: HttpClient? = null
        internal set

    var maxImages: Int = ChatterContextCollector.DEFAULT_MAX_IMAGES
        internal set

    var isChatterEnabled: Boolean = false
        internal set

    private var registrationJob: Job? = null

    override suspend fun initialize(context: PluginContext) {
        val chatterConfig = ChatterConfig.from(context.env)
        val memoryConfig = chatterConfig.memory
        val chatterEnabled = chatterConfig.effectiveEnabled
        this.isChatterEnabled = chatterEnabled
        this.maxImages = chatterConfig.maxImages

        val traceRecorder = context.aiTraceRecorder
        val httpClient = GeminiHttpClientFactory.create().also { this.sharedHttpClient = it }

        val client =
            decisionClientFactory?.invoke(chatterConfig)
                ?: GeminiDecisionClient(
                    config = chatterConfig,
                    httpClient = httpClient,
                    traceRecorder = traceRecorder,
                )
        this.decisionClient = client

        val cooldown = BoundedChannelCooldownCache(defaultCooldownDurationMs = chatterConfig.filter.cooldownDurationMs)
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
                config = chatterConfig.filter.copy(enabled = chatterEnabled),
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
                        allowedChannels = chatterConfig.filter.allowedChannels,
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

        val peekResolver = SocialPeekFactory.buildPeekResolver(chatterConfig.youtubeApiKey)
        val collector =
            ChatterContextCollector(
                noiseFilter = noiseFilter,
                decisionMessageCount = chatterConfig.decision.decisionMessageCount,
                historyMessageCount = memoryConfig.historyMessageCount,
                maxImages = chatterConfig.maxImages,
                peekResolver = peekResolver,
                historyWindowMinutes = memoryConfig.historyWindowMinutes,
            )
        this.contextCollector = collector

        var initializedScheduleStore: SqliteDailyScheduleStore? = null
        var initializedScheduleCoordinator: DailyScheduleCoordinator? = null
        if (chatterConfig.isConfigured && chatterEnabled && chatterConfig.schedule.enabled) {
            runCatching {
                val scheduleStore = SqliteDailyScheduleStore(context.database)
                val scheduleGenerator =
                    DailyScheduleGenerator(
                        interactionClient =
                            GeminiInteractionClient(
                                httpClient = httpClient,
                                traceRecorder = traceRecorder,
                            ),
                        model = chatterConfig.cleanModel,
                        apiKey = chatterConfig.apiKey,
                        baseUrl = chatterConfig.cleanBaseUrl,
                        timezone = chatterConfig.timezone,
                    )
                val presencePort = KordSchedulePresencePort(context.kord)
                val scheduleCoordinatorInstance =
                    DailyScheduleCoordinator(
                        store = scheduleStore,
                        generator = scheduleGenerator,
                        presencePort = presencePort,
                        enabled = true,
                        timezone = ZoneId.of(chatterConfig.timezone),
                    )
                initializedScheduleStore = scheduleStore
                initializedScheduleCoordinator = scheduleCoordinatorInstance
                this.scheduleCoordinator = scheduleCoordinatorInstance
            }.onFailure { e ->
                logger.warn(
                    "Failed to initialize DailyScheduleCoordinator; degrading without daily schedule: {}",
                    e.message,
                )
            }
        }

        val drift =
            CircadianPersonaDrift(
                timezone = chatterConfig.timezone,
                enabled = chatterConfig.driftEnabled,
                scheduleProvider = initializedScheduleCoordinator,
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
                config = chatterConfig,
                scheduleProvider = initializedScheduleCoordinator,
                scheduleStore = initializedScheduleStore,
            )
        val finalService = serviceFactory?.invoke(chatterService) ?: chatterService
        this.service = finalService

        val scope = safeScope(context.kord)
        initializedMemoryService?.start(kord = context.kord, scope = scope, chatterEnabled = chatterEnabled)

        if (!chatterConfig.isConfigured) {
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

        initializedScheduleCoordinator?.start(scope)
    }

    override suspend fun shutdown() {
        registrationJob?.cancel()
        scheduleCoordinator?.shutdown()
        scheduleCoordinator = null
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
