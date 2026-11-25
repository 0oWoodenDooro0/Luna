package luna.chatter

import dev.kord.core.Kord
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import luna.chatter.emoji.ChatterEmojiService
import luna.chatter.emoji.ChatterEmojiVisionResolver
import luna.chatter.emoji.DefaultChatterEmojiService
import luna.chatter.memory.ChatterMemoryService
import luna.chatter.schedule.ChatterScheduleToolHandler
import luna.chatter.schedule.DailySchedule
import luna.chatter.schedule.DailyScheduleCoordinator
import luna.chatter.schedule.DailyScheduleGenerator
import luna.chatter.schedule.DailyScheduleProvider
import luna.chatter.schedule.DeferredCatchUpService
import luna.chatter.schedule.DeferredCatchUpTriageClient
import luna.chatter.schedule.KordCatchUpChannelAdapter
import luna.chatter.schedule.KordSchedulePresencePort
import luna.chatter.schedule.ScheduleBlock
import luna.chatter.schedule.ScheduleCommand
import luna.chatter.schedule.SqliteDailyScheduleStore
import luna.core.JsonLogger
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

    private var presenceReadyJob: Job? = null

    var deferredCatchUpService: DeferredCatchUpService? = null
        internal set

    var scheduleCommand: ScheduleCommand? = null
        internal set

    var sharedHttpClient: HttpClient? = null
        internal set

    var maxImages: Int = ChatterContextCollector.DEFAULT_MAX_IMAGES
        internal set

    var isChatterEnabled: Boolean = false
        internal set

    private var registrationJob: Job? = null
    private var scheduleCommandJob: Job? = null

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

        var initializedScheduleStore: SqliteDailyScheduleStore? = null
        var initializedScheduleCoordinator: DailyScheduleCoordinator? = null
        var initializedPresencePort: KordSchedulePresencePort? = null
        if (chatterConfig.isConfigured && chatterEnabled && chatterConfig.schedule.enabled) {
            runCatching {
                val scheduleStore =
                    SqliteDailyScheduleStore(
                        database = context.database,
                        timezone = ZoneId.of(chatterConfig.timezone),
                    )
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
                initializedPresencePort = presencePort
                this.scheduleCoordinator = scheduleCoordinatorInstance
            }.onFailure { e ->
                logger.warn(
                    "Failed to initialize DailyScheduleCoordinator; degrading without daily schedule: {}",
                    e.message,
                )
            }
        }

        val scheduleToolHandler = initializedScheduleCoordinator?.let { ChatterScheduleToolHandler(it) }
        val generator =
            generatorFactory?.invoke(chatterConfig)
                ?: ChatterGenerator(
                    config = chatterConfig,
                    httpClient = httpClient,
                    traceRecorder = traceRecorder,
                    memoryConfig = memoryConfig,
                    memoryToolHandler = initializedMemoryService?.toolHandler,
                    understandingPreInjector =
                        initializedMemoryService?.let {
                            luna.chatter.memory.UnderstandingPreInjector(
                                memoryStore = it.memoryStore,
                                maxChars = memoryConfig.understandingPreInjectMaxChars,
                            )
                        },
                    scheduleToolHandler = scheduleToolHandler,
                    kordSelfIdProvider = { context.kord.selfId.toString() },
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

        val scheduleCoord = initializedScheduleCoordinator
        val scheduleSt = initializedScheduleStore
        if (scheduleCoord != null && scheduleSt != null) {
            val triageClient =
                DeferredCatchUpTriageClient(
                    interactionClient =
                        GeminiInteractionClient(
                            httpClient = httpClient,
                            traceRecorder = traceRecorder,
                        ),
                    model = chatterConfig.cleanModel,
                    apiKey = chatterConfig.apiKey,
                    baseUrl = chatterConfig.cleanBaseUrl,
                )
            val catchUpService =
                DeferredCatchUpService(
                    scheduleStore = scheduleSt,
                    scheduleProvider = scheduleCoord,
                    triageClient = triageClient,
                    chatterGenerator = generator,
                    filter = noiseFilter,
                    contextCollector = collector,
                    channelPort = KordChatterChannelAdapter,
                    catchUpChannelPort = KordCatchUpChannelAdapter(context.kord),
                    burstCoordinator = coordinator,
                    personaStateProvider = drift,
                    emojiService = emojiServiceInstance,
                    traceRecorder = traceRecorder,
                    config = chatterConfig,
                )
            this.deferredCatchUpService = catchUpService
            scheduleCoord.setOnBecomeAvailableListener { previous, current ->
                catchUpService.onBecomeAvailable(previous, current)
            }
        }

        val scope = safeScope(context.kord)
        initializedMemoryService?.start(kord = context.kord, scope = scope, chatterEnabled = chatterEnabled)
        registerScheduleCommand(kord = context.kord, scope = scope)

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

        this.presenceReadyJob = initializedPresencePort?.registerReadyListener(scope)
        initializedScheduleCoordinator?.start(scope)
    }

    override suspend fun shutdown() {
        registrationJob?.cancel()
        presenceReadyJob?.cancel()
        presenceReadyJob = null
        scheduleCommandJob?.cancel()
        scheduleCommandJob = null
        scheduleCommand = null
        scheduleCoordinator?.shutdown()
        scheduleCoordinator = null
        deferredCatchUpService = null
        memoryService?.close()
        memoryService = null
        decisionClient?.close()
        chatterGenerator?.close()
        sharedHttpClient?.close()
        sharedHttpClient = null
    }

    private suspend fun registerScheduleCommand(
        kord: Kord,
        scope: CoroutineScope,
    ) {
        val scheduleProvider =
            object : DailyScheduleProvider {
                override fun isEnabled(): Boolean = scheduleCoordinator?.isEnabled() == true

                override fun currentSchedule(): DailySchedule? = scheduleCoordinator?.currentSchedule()

                override fun currentBlock(): ScheduleBlock? = scheduleCoordinator?.currentBlock()
            }
        val command = ScheduleCommand(scheduleProvider = scheduleProvider)
        this.scheduleCommand = command
        try {
            command.register(kord)
            scheduleCommandJob =
                kord.on<ChatInputCommandInteractionCreateEvent>(scope = scope) {
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
            logger.warn("Could not register /schedule command to Kord: {}", e.message)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ChatterPlugin::class.java)
    }
}
