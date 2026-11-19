package luna.chatter.emoji

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Guild
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.event.Event
import dev.kord.core.event.guild.EmojisUpdateEvent
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
import kotlinx.serialization.json.Json
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.BoundedChannelCooldownCache
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterDecisionConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterNoiseFilter
import luna.chatter.ChatterService
import luna.chatter.GeminiDecisionClient
import luna.chatter.PersonaState
import luna.chatter.PersonaStateProvider
import luna.core.ai.GeminiInteractionClient
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatterEmojiPreScanBoundariesTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
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
                    localTime = ZonedDateTime.of(2026, 11, 19, 14, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                    formattedWeekday = "星期四",
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
        visionModel: String? = "gemini-2.5-flash-vision",
        clock: Clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC),
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
                        emojiVisionModel = visionModel,
                    ),
                httpClient = client,
            )
        val visionResolver =
            if (!visionModel.isNullOrBlank()) {
                CustomEmojiVisionResolver(
                    meaningRepository = meaningRepository,
                    visionModel = visionModel,
                    interactionClient = GeminiInteractionClient(httpClient = client),
                    apiKey = "test-api-key",
                    clock = clock,
                )
            } else {
                null
            }
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
            contextCollector = ChatterContextCollector(noiseFilter = filter),
            burstCoordinator = BoundedChannelBurstCoordinator(),
            randomProvider = { _, _ -> 0L },
            afterMessagesFetcher = { _, _ -> emptyList() },
            personaStateProvider = fixedPersonaProvider(),
            emojiCatalog = catalog,
        )
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
    fun `when vision model is unset or blank startup and EmojisUpdateEvent trigger zero vision calls while syncing renames`() =
        runTest {
            val db = openDatabase("opt-out-prescan.db")
            val clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC)
            val meaningRepo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)
            meaningRepo.upsertResolvedMeaning(
                emojiId = "4001",
                name = "old_name",
                isAnimated = false,
                meaning = "既有意義",
            )

            val visionCalls = AtomicInteger(0)
            val engine =
                MockEngine {
                    visionCalls.incrementAndGet()
                    respond(
                        content = """{"id":"vis-nope","status":"completed","finish_reason":"STOP","output_text":"不應呼叫"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = createService(engine, meaningRepo, visionModel = "   ", clock = clock)
            val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 16)
            val guild1 =
                mockk<Guild>(relaxed = true) {
                    every { id } returns Snowflake(9004L)
                    every { emojis } returns
                        flowOf(
                            mockGuildEmoji(id = 4001L, name = "renamed_emoji", isAvailable = true),
                            mockGuildEmoji(id = 4002L, name = "uncached_emoji", isAvailable = true),
                        )
                }
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns eventsFlow
                    every { guilds } returns flowOf(guild1)
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)
                delay(150L)

                val updateEvent =
                    mockk<EmojisUpdateEvent>(relaxed = true) {
                        every { guildId } returns Snowflake(9004L)
                        every { emojis } returns
                            setOf(
                                mockGuildEmoji(id = 4001L, name = "renamed_again", isAvailable = true),
                                mockGuildEmoji(id = 4003L, name = "another_uncached", isAvailable = true),
                            )
                    }
                eventsFlow.emit(updateEvent)

                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline) {
                    if (meaningRepo.findById("4001")?.name == "renamed_again") break
                    delay(20L)
                }

                assertEquals(
                    0,
                    visionCalls.get(),
                    "Expected zero vision API calls when CHATTER_EMOJI_VISION_MODEL is blank",
                )
                val updated = meaningRepo.findById("4001")
                assertEquals(
                    "renamed_again",
                    updated?.name,
                    "Expected rename metadata update even when vision model is blank",
                )
                assertEquals(
                    "既有意義",
                    updated?.meaning,
                    "Expected existing meaning to remain intact",
                )
                job.cancel()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `pre-scan skips unavailable, already cached, and cooling-down emojis while updating renamed metadata`() =
        runTest {
            val db = openDatabase("skip-boundary-prescan.db")
            val now = Instant.parse("2026-11-19T10:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val meaningRepo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)
            meaningRepo.upsertResolvedMeaning(
                emojiId = "5001",
                name = "old_cached",
                isAnimated = false,
                meaning = "已快取",
            )
            meaningRepo.recordFailureCooldown(
                emojiId = "5002",
                name = "cooling",
                isAnimated = false,
                failedUntilEpochMs = now.toEpochMilli() + 1_800_000L,
            )

            val requestedIds = CopyOnWriteArrayList<String>()
            val engine =
                MockEngine { request ->
                    val bodyText = request.body.toByteArray().decodeToString()
                    requestedIds += bodyText
                    respond(
                        content = """{"id":"vis-5","status":"completed","finish_reason":"STOP","output_text":"新表情"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = createService(engine, meaningRepo, clock = clock)
            val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 16)
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns eventsFlow
                    every { guilds } returns flowOf()
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)
                delay(50L)

                val updateEvent =
                    mockk<EmojisUpdateEvent>(relaxed = true) {
                        every { guildId } returns Snowflake(9005L)
                        every { emojis } returns
                            setOf(
                                mockGuildEmoji(id = 5001L, name = "new_cached_name", isAvailable = true),
                                mockGuildEmoji(id = 5002L, name = "cooling", isAvailable = true),
                                mockGuildEmoji(id = 5003L, name = "unavailable", isAvailable = false),
                                mockGuildEmoji(id = 5004L, name = "fresh_one", isAvailable = true),
                            )
                    }
                eventsFlow.emit(updateEvent)

                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline) {
                    if (meaningRepo.findById("5004")?.meaning == "新表情") break
                    delay(20L)
                }

                assertEquals(1, requestedIds.size, "Expected only 1 vision call for fresh available emoji 5004")
                assertTrue(
                    requestedIds.single().contains("5004.png"),
                    "Expected vision call to target 5004.png, got ${requestedIds.single()}",
                )
                assertEquals(
                    "new_cached_name",
                    meaningRepo.findById("5001")?.name,
                    "Expected renamed cached emoji 5001 to update name metadata without vision call",
                )
                assertEquals(
                    "已快取",
                    meaningRepo.findById("5001")?.meaning,
                    "Expected cached meaning of 5001 to be preserved",
                )
                job.cancel()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `rapid back-to-back EmojisUpdateEvents bound concurrency to 5 and deduplicate in-flight emoji IDs`() =
        runTest {
            val db = openDatabase("dedup-concurrency-prescan.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db)
            val activeCalls = AtomicInteger(0)
            val maxObservedConcurrency = AtomicInteger(0)
            val totalCalls = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()

            val engine =
                MockEngine {
                    val current = activeCalls.incrementAndGet()
                    maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
                    totalCalls.incrementAndGet()
                    try {
                        gate.await()
                        delay(15L)
                        respond(
                            content = """{"id":"vis-dedup","status":"completed","finish_reason":"STOP","output_text":"並發表情"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } finally {
                        activeCalls.decrementAndGet()
                    }
                }

            val service = createService(engine, meaningRepo)
            val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 16)
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns eventsFlow
                    every { guilds } returns flowOf()
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)
                delay(50L)

                val emojis =
                    (1L..8L)
                        .map { idx ->
                            mockGuildEmoji(id = 6000L + idx, name = "emoji_$idx", isAvailable = true)
                        }.toSet()

                repeat(3) {
                    eventsFlow.emit(
                        mockk<EmojisUpdateEvent>(relaxed = true) {
                            every { guildId } returns Snowflake(9006L)
                            every { this@mockk.emojis } returns emojis
                        },
                    )
                }

                val hitDeadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < hitDeadline && totalCalls.get() < 5) {
                    delay(10L)
                }
                gate.complete(Unit)

                val expectedIds = (1L..8L).map { (6000L + it).toString() }
                val doneDeadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < doneDeadline) {
                    if (meaningRepo.findMeaningMap(expectedIds).size == 8) break
                    delay(20L)
                }

                assertEquals(8, meaningRepo.findMeaningMap(expectedIds).size, "Expected all 8 emojis cached")
                assertEquals(
                    8,
                    totalCalls.get(),
                    "Expected exactly 8 deduplicated vision calls across 3 rapid EmojisUpdateEvents, got ${totalCalls.get()}",
                )
                assertTrue(
                    maxObservedConcurrency.get() <= CustomEmojiVisionResolver.DEFAULT_MAX_CONCURRENCY,
                    "Expected max concurrency <= ${CustomEmojiVisionResolver.DEFAULT_MAX_CONCURRENCY}, got ${maxObservedConcurrency.get()}",
                )
                job.cancel()
            } finally {
                gate.complete(Unit)
                scope.cancel()
            }
        }

    @Test
    fun `pre-scan emits warning logs on guild fetch failure and individual emoji error without aborting remaining scans`() =
        runTest {
            val db = openDatabase("degraded-prescan.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db)

            val serviceLogger = LoggerFactory.getLogger(ChatterService::class.java) as Logger
            val resolverLogger = LoggerFactory.getLogger(CustomEmojiVisionResolver::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            serviceLogger.addAppender(appender)
            resolverLogger.addAppender(appender)

            val engine =
                MockEngine { request ->
                    val bodyText = request.body.toByteArray().decodeToString()
                    if (bodyText.contains("7001.png")) {
                        respond(
                            content = """{"error":{"message":"upstream failure"}}""",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"id":"vis-ok","status":"completed","finish_reason":"STOP","output_text":"正常表情"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val service = createService(engine, meaningRepo)
            val failingGuild =
                mockk<Guild>(relaxed = true) {
                    every { id } returns Snowflake(9007L)
                    every { emojis } returns flow { throw IllegalStateException("guild emoji fetch boom") }
                }
            val healthyGuild =
                mockk<Guild>(relaxed = true) {
                    every { id } returns Snowflake(9008L)
                    every { emojis } returns
                        flowOf(
                            mockGuildEmoji(id = 7001L, name = "broken_emoji", isAvailable = true),
                            mockGuildEmoji(id = 7002L, name = "healthy_emoji", isAvailable = true),
                        )
                }
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns MutableSharedFlow<Event>()
                    every { guilds } returns flowOf(failingGuild, healthyGuild)
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)

                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline) {
                    if (
                        meaningRepo.findById("7002")?.meaning == "正常表情" &&
                        meaningRepo.findById("7001")?.failedUntil != null
                    ) {
                        break
                    }
                    delay(20L)
                }

                assertEquals(
                    "正常表情",
                    meaningRepo.findById("7002")?.meaning,
                    "Expected healthy emoji 7002 in remaining guild 9008 to still be scanned and cached",
                )
                assertTrue(
                    meaningRepo.findById("7001")?.failedUntil != null,
                    "Expected failing emoji 7001 to enter 1-hour failure cooldown",
                )

                val warningMessages =
                    appender.list
                        .filter { it.level == Level.WARN }
                        .map { it.formattedMessage }

                assertTrue(
                    warningMessages.any { it.contains("9007") && it.contains("guild emoji fetch boom") },
                    "Expected warning log with failing guild ID 9007 and cause, got: $warningMessages",
                )
                assertTrue(
                    warningMessages.any { it.contains("7001") },
                    "Expected warning log with failing emoji ID 7001, got: $warningMessages",
                )
                job.cancel()
            } finally {
                serviceLogger.detachAppender(appender)
                resolverLogger.detachAppender(appender)
                scope.cancel()
            }
        }
}
