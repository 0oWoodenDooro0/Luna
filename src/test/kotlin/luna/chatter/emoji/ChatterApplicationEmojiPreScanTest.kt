package luna.chatter.emoji

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.ApplicationEmoji
import dev.kord.core.entity.Guild
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.Message
import dev.kord.core.event.Event
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.BoundedChannelCooldownCache
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterDecisionConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterNoiseFilter
import luna.chatter.ChatterService
import luna.chatter.FakeChatterChannelPort
import luna.chatter.GeminiDecisionClient
import luna.chatter.GuildCustomEmoji
import luna.chatter.PersonaState
import luna.chatter.PersonaStateProvider
import luna.core.ai.GeminiInteractionClient
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatterApplicationEmojiPreScanTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private class MutableClock(
        initial: Instant,
    ) : Clock() {
        private val current = AtomicReference(initial)

        fun advance(duration: Duration) {
            current.updateAndGet { it.plus(duration) }
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current.get()
    }

    private fun openDatabase(name: String): Database =
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve(name).toFile().absolutePath}",
            driver = "org.sqlite.JDBC",
        )

    private fun createClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    private fun fixedPersonaProvider(): PersonaStateProvider =
        object : PersonaStateProvider {
            override fun currentState(baseThreshold: Double): PersonaState =
                PersonaState(
                    driftEnabled = false,
                    timezone = "Asia/Taipei",
                    localTime = ZonedDateTime.of(2026, 11, 21, 14, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                    formattedWeekday = "星期六",
                    formattedTime = "14:00",
                    circadianPeriod = null,
                    mood = null,
                    baseThreshold = baseThreshold,
                    effectiveThreshold = baseThreshold,
                )
        }

    private fun createService(
        engine: MockEngine,
        meaningRepository: ExposedCustomEmojiMeaningRepository,
        channelPort: FakeChatterChannelPort = FakeChatterChannelPort(),
        applicationEmojiCache: ApplicationEmojiCache = ApplicationEmojiCache(),
        clock: Clock = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC),
    ): ChatterService {
        val client = createClient(engine)
        val decisionClient =
            GeminiDecisionClient(
                config =
                    ChatterDecisionConfig(
                        apiKey = "test-api-key",
                        model = "gemini-decision-test",
                        threshold = 0.7,
                        timeoutMillis = 5_000,
                    ),
                httpClient = client,
            )
        val generator =
            ChatterGenerator(
                config =
                    ChatterConfig(
                        apiKey = "test-api-key",
                        model = "gemini-chat-test",
                        emojiVisionModel = "gemini-2.5-flash-vision",
                    ),
                httpClient = client,
            )
        val visionResolver =
            CustomEmojiVisionResolver(
                meaningRepository = meaningRepository,
                visionModel = "gemini-2.5-flash-vision",
                interactionClient = GeminiInteractionClient(httpClient = client),
                apiKey = "test-api-key",
                clock = clock,
            )
        val catalog =
            DefaultChatterEmojiCatalog(
                meaningRepository = meaningRepository,
                visionResolver = visionResolver,
                clock = clock,
            )
        val filter = ChatterNoiseFilter(cooldownCache = BoundedChannelCooldownCache())
        return ChatterService(
            filter = filter,
            decisionClient = decisionClient,
            chatterGenerator = generator,
            channelPort = channelPort,
            contextCollector = ChatterContextCollector(noiseFilter = filter),
            burstCoordinator = BoundedChannelBurstCoordinator(),
            randomProvider = { _, _ -> 0L },
            personaStateProvider = fixedPersonaProvider(),
            emojiCatalog = catalog,
            applicationEmojiCache = applicationEmojiCache,
        )
    }

    private fun mockApplicationEmoji(
        id: Long,
        name: String?,
        isAvailable: Boolean = true,
        isAnimated: Boolean = false,
    ): ApplicationEmoji =
        mockk(relaxed = true) {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.name } returns name
            every { this@mockk.isAvailable } returns isAvailable
            every { this@mockk.isAnimated } returns isAnimated
        }

    private fun mockGuildEmoji(
        id: Long,
        name: String?,
        isAvailable: Boolean = true,
        isAnimated: Boolean = false,
    ): GuildEmoji =
        mockk(relaxed = true) {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.name } returns name
            every { this@mockk.isAvailable } returns isAvailable
            every { this@mockk.isAnimated } returns isAnimated
        }

    @Test
    fun `startup pre-scan populates ApplicationEmojiCache and SQLite Custom Emoji Meaning asynchronously`() =
        runTest {
            val db = openDatabase("startup-app-emoji-prescan.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db)
            val requestedUrls = CopyOnWriteArrayList<String>()

            val engine =
                MockEngine { request ->
                    requestedUrls += request.url.toString()
                    val bodyText = request.body.toByteArray().decodeToString()
                    val meaning =
                        when {
                            bodyText.contains("8001.png") -> "得意壞笑"
                            bodyText.contains("8002.gif") -> "害羞轉圈"
                            else -> "未知表情"
                        }
                    respond(
                        content = """{"id":"vis-app-1","status":"completed","finish_reason":"STOP","output_text":"$meaning"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val cache = ApplicationEmojiCache()
            val service = createService(engine, meaningRepo, applicationEmojiCache = cache)
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns MutableSharedFlow<Event>()
                    every { guilds } returns flowOf()
                    every { emojis } returns
                        flowOf(
                            mockApplicationEmoji(id = 8001L, name = "luna_smug", isAvailable = true, isAnimated = false),
                            mockApplicationEmoji(id = 8002L, name = "luna_spin", isAvailable = true, isAnimated = true),
                            mockApplicationEmoji(id = 8003L, name = "broken", isAvailable = false, isAnimated = false),
                            mockApplicationEmoji(id = 8004L, name = "  ", isAvailable = true, isAnimated = false),
                        )
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)

                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline) {
                    val meanings = meaningRepo.findMeaningMap(listOf("8001", "8002"))
                    if (meanings.size == 2) break
                    withContext(Dispatchers.Default) { delay(20L) }
                }

                assertEquals(
                    mapOf("8001" to "得意壞笑", "8002" to "害羞轉圈"),
                    meaningRepo.findMeaningMap(listOf("8001", "8002")),
                    "Expected startup pre-scan to populate SQLite Custom Emoji Meaning for available Application Emojis",
                )
                assertEquals(
                    setOf("8001", "8002"),
                    cache.knownIds(),
                    "Expected startup pre-scan to populate ApplicationEmojiCache",
                )
                assertEquals(
                    2,
                    requestedUrls.size,
                    "Expected exactly 2 vision calls for available non-blank Application Emojis",
                )
                job.cancel()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `startup Application Emoji fetch failure logs warning without aborting guild pre-scan`() =
        runTest {
            val db = openDatabase("startup-app-emoji-error.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db)

            val cacheLogger = LoggerFactory.getLogger(ApplicationEmojiCache::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            cacheLogger.addAppender(appender)

            val engine =
                MockEngine {
                    respond(
                        content = """{"id":"vis-guild-ok","status":"completed","finish_reason":"STOP","output_text":"伺服器表情"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = createService(engine, meaningRepo)
            val healthyGuild =
                mockk<Guild>(relaxed = true) {
                    every { id } returns Snowflake(9101L)
                    every { emojis } returns flowOf(mockGuildEmoji(id = 8101L, name = "guild_wave", isAvailable = true))
                }
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns MutableSharedFlow<Event>()
                    every { guilds } returns flowOf(healthyGuild)
                    every { emojis } returns flow { throw IllegalStateException("app emoji endpoint boom") }
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)

                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline) {
                    if (meaningRepo.findById("8101")?.meaning == "伺服器表情") break
                    withContext(Dispatchers.Default) { delay(20L) }
                }

                assertEquals(
                    "伺服器表情",
                    meaningRepo.findById("8101")?.meaning,
                    "Expected guild startup pre-scan to succeed despite Application Emoji fetch failure",
                )
                val warnLogs = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnLogs.any { it.contains("app emoji endpoint boom") },
                    "Expected warning log for Application Emoji fetch failure, got: $warnLogs",
                )
                job.cancel()
            } finally {
                cacheLogger.detachAppender(appender)
                scope.cancel()
            }
        }

    @Test
    fun `cancelling registration job cancels in-flight startup Application Emoji pre-scan cleanly`() =
        runTest {
            val db = openDatabase("cancel-app-emoji-prescan.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db)
            val requestStarted = CompletableDeferred<Unit>()
            val neverEndingGate = CompletableDeferred<Unit>()

            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    neverEndingGate.await()
                    respond(
                        content = """{"id":"vis-cancel-app","status":"completed","finish_reason":"STOP","output_text":"不應寫入"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = createService(engine, meaningRepo)
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns MutableSharedFlow<Event>()
                    every { guilds } returns flowOf()
                    every { emojis } returns flowOf(mockApplicationEmoji(id = 8201L, name = "slow_app", isAvailable = true))
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)
                requestStarted.await()
                job.cancel()
                job.join()

                assertTrue(job.isCancelled, "Expected registrationJob to be cancelled on shutdown")
                assertTrue(
                    meaningRepo.findMeaningMap(listOf("8201")).isEmpty(),
                    "Expected cancelled Application Emoji pre-scan not to persist partial meaning",
                )
            } finally {
                neverEndingGate.cancel()
                scope.cancel()
            }
        }

    @Test
    fun `resolveApplicationEmojis refreshes on TTL expiry and pre-scans only newly discovered IDs in background`() =
        runTest {
            val db = openDatabase("ttl-refresh-app-emoji-prescan.db")
            val clock = MutableClock(Instant.parse("2026-11-21T10:00:00Z"))
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db, clock = clock)
            meaningRepo.upsertResolvedMeaning(
                emojiId = "8301",
                name = "luna_existing",
                isAnimated = false,
                meaning = "既有表情",
            )

            val gate = CompletableDeferred<Unit>()
            val requestedBodies = CopyOnWriteArrayList<String>()
            val engine =
                MockEngine { request ->
                    gate.await()
                    val bodyText = request.body.toByteArray().decodeToString()
                    requestedBodies += bodyText
                    respond(
                        content = """{"id":"vis-app-new","status":"completed","finish_reason":"STOP","output_text":"新增得意"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val emojiExisting = GuildCustomEmoji(Snowflake(8301L), "luna_existing", false)
            val emojiNew = GuildCustomEmoji(Snowflake(8302L), "luna_new", true)
            val channelPort =
                FakeChatterChannelPort().apply {
                    applicationEmojis = listOf(emojiExisting)
                }
            val cache = ApplicationEmojiCache(clock = clock)
            val service =
                createService(
                    engine = engine,
                    meaningRepository = meaningRepo,
                    channelPort = channelPort,
                    applicationEmojiCache = cache,
                    clock = clock,
                )

            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns MutableSharedFlow<Event>()
                    every { guilds } returns flowOf()
                    every { emojis } returns flowOf(mockApplicationEmoji(id = 8301L, name = "luna_existing", isAvailable = true))
                }
            val anchor =
                mockk<Message>(relaxed = true) {
                    every { id } returns Snowflake(10L)
                    every { channelId } returns Snowflake(100L)
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)

                val startupDeadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < startupDeadline && "8301" !in cache.knownIds()) {
                    withContext(Dispatchers.Default) { delay(15L) }
                }
                assertEquals(setOf("8301"), cache.knownIds())

                val cachedResult = cache.resolveForMessage(anchor, channelPort, service.emojiCatalog, scope)
                assertEquals(listOf(emojiExisting), cachedResult)
                assertTrue(
                    channelPort.events.none { it is FakeChatterChannelPort.ChannelEvent.ListedApplicationEmojis },
                    "Expected TTL cache hit without calling channelPort.listApplicationEmojis",
                )

                channelPort.applicationEmojis = listOf(emojiExisting, emojiNew)
                clock.advance(Duration.ofMinutes(10))

                val refreshed = cache.resolveForMessage(anchor, channelPort, service.emojiCatalog, scope)
                assertEquals(listOf(emojiExisting, emojiNew), refreshed)
                assertTrue(
                    meaningRepo.findMeaningMap(listOf("8302")).isEmpty(),
                    "Expected background pre-scan not to block resolveApplicationEmojis before vision completes",
                )

                gate.complete(Unit)

                val preScanDeadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < preScanDeadline) {
                    if (meaningRepo.findById("8302")?.meaning == "新增得意") break
                    withContext(Dispatchers.Default) { delay(20L) }
                }

                assertEquals(
                    "新增得意",
                    meaningRepo.findById("8302")?.meaning,
                    "Expected newly discovered Application Emoji 8302 to be pre-scanned in background",
                )
                assertEquals(
                    1,
                    requestedBodies.size,
                    "Expected only 1 vision call for newly discovered emoji 8302, got ${requestedBodies.size}",
                )
                assertTrue(
                    requestedBodies.single().contains("8302.gif"),
                    "Expected vision call to target 8302.gif, got ${requestedBodies.single()}",
                )
                job.cancel()
            } finally {
                gate.complete(Unit)
                scope.cancel()
            }
        }
}
