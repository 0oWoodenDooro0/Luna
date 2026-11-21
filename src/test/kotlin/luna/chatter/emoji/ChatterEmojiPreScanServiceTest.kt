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

class ChatterEmojiPreScanServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun openDatabase(name: String = "chatter-emoji-prescan.db"): Database =
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
        clock: Clock = Clock.fixed(Instant.parse("2026-04-12T10:00:00Z"), ZoneOffset.UTC),
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
            channelPort = FakeChatterChannelPort(),
            contextCollector = ChatterContextCollector(noiseFilter = filter),
            burstCoordinator = BoundedChannelBurstCoordinator(),
            randomProvider = { _, _ -> 0L },
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
    fun `startup pre-scan asynchronously populates SQLite Custom Emoji Meaning for uncached available guild emojis`() =
        runTest {
            val db = openDatabase("startup-prescan.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db)
            val requestedUrls = CopyOnWriteArrayList<String>()

            val engine =
                MockEngine { request ->
                    requestedUrls += request.url.toString()
                    val bodyText = request.body.toByteArray().decodeToString()
                    val meaning =
                        when {
                            bodyText.contains("1001.png") -> "開心歡呼"
                            bodyText.contains("1002.gif") -> "點頭同意"
                            else -> "未知表情"
                        }
                    respond(
                        content = """{"id":"vis-1","status":"completed","finish_reason":"STOP","output_text":"$meaning"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = createService(engine, meaningRepo)
            val guild1 =
                mockk<Guild>(relaxed = true) {
                    every { id } returns Snowflake(9001L)
                    every { emojis } returns
                        flowOf(
                            mockGuildEmoji(id = 1001L, name = "cheer", isAvailable = true, isAnimated = false),
                            mockGuildEmoji(id = 1002L, name = "nod", isAvailable = true, isAnimated = true),
                        )
                }
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns MutableSharedFlow<Event>()
                    every { guilds } returns flowOf(guild1)
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)

                // Poll briefly on real wall clock since startup pre-scan runs asynchronously on scope
                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline) {
                    val meanings = meaningRepo.findMeaningMap(listOf("1001", "1002"))
                    if (meanings.size == 2) break
                    delay(20L)
                }

                val meanings = meaningRepo.findMeaningMap(listOf("1001", "1002"))
                assertEquals(
                    mapOf("1001" to "開心歡呼", "1002" to "點頭同意"),
                    meanings,
                    "Expected startup pre-scan to populate SQLite Custom Emoji Meaning for available guild emojis",
                )
                assertTrue(
                    requestedUrls.size == 2,
                    "Expected 2 vision API calls during startup pre-scan, got ${requestedUrls.size}",
                )
                job.cancel()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `EmojisUpdateEvent asynchronously populates SQLite Custom Emoji Meaning without blocking event dispatch`() =
        runTest {
            val db = openDatabase("emojis-update-prescan.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db)
            val gate = CompletableDeferred<Unit>()

            val engine =
                MockEngine { request ->
                    gate.await()
                    val bodyText = request.body.toByteArray().decodeToString()
                    val meaning =
                        when {
                            bodyText.contains("2001.png") -> "加油打氣"
                            bodyText.contains("2002.gif") -> "興奮轉圈"
                            else -> "未知表情"
                        }
                    respond(
                        content = """{"id":"vis-2","status":"completed","finish_reason":"STOP","output_text":"$meaning"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
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
                val subscribeDeadline = System.currentTimeMillis() + 5_000L
                while (eventsFlow.subscriptionCount.value < 3 && System.currentTimeMillis() < subscribeDeadline) {
                    withContext(Dispatchers.Default) { delay(10L) }
                }

                val updateEvent =
                    mockk<EmojisUpdateEvent>(relaxed = true) {
                        every { guildId } returns Snowflake(9002L)
                        every { emojis } returns
                            setOf(
                                mockGuildEmoji(id = 2001L, name = "cheerup", isAvailable = true, isAnimated = false),
                                mockGuildEmoji(id = 2002L, name = "spin", isAvailable = true, isAnimated = true),
                            )
                    }

                // Must return immediately without waiting for MockEngine gate
                eventsFlow.emit(updateEvent)
                assertTrue(
                    meaningRepo.findMeaningMap(listOf("2001", "2002")).isEmpty(),
                    "Expected EmojisUpdateEvent dispatch to be non-blocking before vision completes",
                )

                gate.complete(Unit)

                val deadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadline) {
                    val meanings = meaningRepo.findMeaningMap(listOf("2001", "2002"))
                    if (meanings.size == 2) break
                    delay(20L)
                }

                assertEquals(
                    mapOf("2001" to "加油打氣", "2002" to "興奮轉圈"),
                    meaningRepo.findMeaningMap(listOf("2001", "2002")),
                    "Expected EmojisUpdateEvent pre-scan to populate SQLite Custom Emoji Meaning",
                )
                job.cancel()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `cancelling registration job cancels in-flight pre-scan tasks cleanly`() =
        runTest {
            val db = openDatabase("cancel-prescan.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(db)
            val requestStarted = CompletableDeferred<Unit>()
            val neverEndingGate = CompletableDeferred<Unit>()

            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    neverEndingGate.await()
                    respond(
                        content = """{"id":"vis-cancel","status":"completed","finish_reason":"STOP","output_text":"不應寫入"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service = createService(engine, meaningRepo)
            val guild1 =
                mockk<Guild>(relaxed = true) {
                    every { id } returns Snowflake(9003L)
                    every { emojis } returns flowOf(mockGuildEmoji(id = 3001L, name = "slow", isAvailable = true))
                }
            val mockKord =
                mockk<Kord>(relaxed = true) {
                    every { events } returns MutableSharedFlow<Event>()
                    every { guilds } returns flowOf(guild1)
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = service.register(mockKord, scope = scope)
                requestStarted.await()
                job.cancel()
                job.join()

                assertTrue(job.isCancelled, "Expected registrationJob to be cancelled on shutdown")
                assertTrue(
                    meaningRepo.findMeaningMap(listOf("3001")).isEmpty(),
                    "Expected cancelled pre-scan not to persist partial meaning",
                )
            } finally {
                neverEndingGate.cancel()
                scope.cancel()
            }
        }
}
