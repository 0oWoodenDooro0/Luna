package luna.chatter.emoji

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Guild
import dev.kord.core.entity.GuildEmoji
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.BoundedChannelCooldownCache
import luna.chatter.ChatterAiTraceTestSupport
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterEmojiVisionGuardrailsTest {
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

    private fun extractInteractionPromptText(body: String): String =
        json
            .parseToJsonElement(body)
            .jsonObject["input"]
            ?.jsonArray
            ?.last()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    private fun createChatterService(
        httpClient: HttpClient,
        catalog: ChatterEmojiCatalog,
        channelPort: FakeChatterChannelPort = FakeChatterChannelPort(),
    ): ChatterService {
        val cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })
        return ChatterService(
            filter = ChatterNoiseFilter(cooldownCache = cooldownCache),
            decisionClient =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                    httpClient = httpClient,
                ),
            chatterGenerator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "secret-key", emojiVisionModel = "gemini-2.5-flash"),
                    httpClient = httpClient,
                ),
            channelPort = channelPort,
            contextCollector = ChatterContextCollector(ChatterNoiseFilter(), peekResolver = { null }),
            burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 10L),
            randomProvider = { _, _ -> 0L },
            personaStateProvider = fixedPersonaProvider(),
            emojiCatalog = catalog,
        )
    }

    private fun extractInteractionModel(body: String): String =
        json
            .parseToJsonElement(body)
            .jsonObject["model"]
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    private fun extractInteractionImageInputs(body: String): List<Pair<String, String>> =
        json
            .parseToJsonElement(body)
            .jsonObject["input"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val obj = element.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "image") {
                    val uri = obj["uri"]?.jsonPrimitive?.content.orEmpty()
                    val mime = obj["mime_type"]?.jsonPrimitive?.content.orEmpty()
                    uri to mime
                } else {
                    null
                }
            }.orEmpty()

    private fun guildEmoji(
        id: Long,
        name: String,
        isAnimated: Boolean = false,
    ): GuildEmoji =
        mockk<GuildEmoji>(relaxed = true).also { emoji ->
            every { emoji.id } returns Snowflake(id)
            every { emoji.name } returns name
            every { emoji.isAnimated } returns isAnimated
            every { emoji.isAvailable } returns true
        }

    private class MutableClock(
        private var currentInstant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = currentInstant

        fun advanceBySeconds(seconds: Long) {
            currentInstant = currentInstant.plusSeconds(seconds)
        }
    }

    @Test
    fun `failure or empty output sets 1-hour cooldown, suppresses retries within window, and resumes after 1 hour`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("failure-cooldown.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)

            val resolverLogger = LoggerFactory.getLogger(CustomEmojiVisionResolver::class.java) as Logger
            val logAppender = ListAppender<ILoggingEvent>().apply { start() }
            resolverLogger.addAppender(logAppender)

            try {
                val visionCalls = AtomicInteger(0)
                val mockEngine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        val body = request.body.toByteArray().decodeToString()
                        when {
                            url.contains("/v1beta/decisions") -> {
                                val decisionJson =
                                    """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}"""
                                respond(
                                    content = decisionJson,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            url.contains("/v1beta/interactions") && extractInteractionModel(body) == "gemini-2.5-flash" -> {
                                val callNum = visionCalls.incrementAndGet()
                                if (callNum == 1) {
                                    respond(
                                        content = """{"id":"vis-empty","status":"completed","finish_reason":"STOP","output_text":"   ...   "}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                } else {
                                    respond(
                                        content = """{"id":"vis-ok","status":"completed","finish_reason":"STOP","output_text":"無奈嘆氣"}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                }
                            }

                            else -> {
                                respond(
                                    content = """{"id":"int-reply","status":"completed","finish_reason":"STOP","output_text":"好喔"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        }
                    }

                val httpClient = createClient(mockEngine)
                val visionResolver =
                    CustomEmojiVisionResolver(
                        meaningRepository = repo,
                        visionModel = "gemini-2.5-flash",
                        interactionClient = GeminiInteractionClient(httpClient = httpClient),
                        apiKey = "secret-key",
                        clock = clock,
                    )
                val catalog = DefaultChatterEmojiCatalog(meaningRepository = repo, visionResolver = visionResolver, clock = clock)
                val channelPort =
                    FakeChatterChannelPort().apply {
                        guildEmojis = listOf(guildEmoji(1005L, "sigh"))
                    }
                val service = createChatterService(httpClient, catalog, channelPort)

                val msg1 = ChatterAiTraceTestSupport.createMockMessage(id = 2005L, channelId = 3005L, content = "<:sigh:1005> 唉")
                assertTrue(service.processMessage(msg1), "Expected first message turn to complete despite vision failure")
                assertEquals(1, visionCalls.get())
                val failedRecord = repo.findById("1005")
                assertNotNull(failedRecord)
                assertNull(failedRecord.meaning)
                assertEquals(clock.millis() + 3_600_000L, failedRecord.failedUntil)

                val warnLogs = logAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnLogs.any { it.contains("1005") && it.contains("cooldown") },
                    "Expected observable WARN log recording failure and cooldown, got: $warnLogs",
                )

                clock.advanceBySeconds(1800)
                val msg2 = ChatterAiTraceTestSupport.createMockMessage(id = 2006L, channelId = 3006L, content = "<:sigh:1005> 唉")
                assertTrue(service.processMessage(msg2), "Expected second message turn to complete during cooldown")
                assertEquals(1, visionCalls.get(), "Cooldown window must suppress vision retries")

                clock.advanceBySeconds(1860)
                val msg3 = ChatterAiTraceTestSupport.createMockMessage(id = 2007L, channelId = 3007L, content = "<:sigh:1005> 唉")
                assertTrue(service.processMessage(msg3), "Expected third message turn to complete after cooldown expiry")
                assertEquals(2, visionCalls.get(), "Vision retries must resume after 1-hour cooldown expires")
                assertEquals("無奈嘆氣", repo.findById("1005")?.meaning)
                assertNull(repo.findById("1005")?.failedUntil)
            } finally {
                resolverLogger.detachAppender(logAppender)
            }
        }

    @Test
    fun `concurrency cap bounds in-flight vision calls to 5 and round timeout degrades gracefully without aborting turn`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("concurrency-timeout.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)

            val activeCalls = AtomicInteger(0)
            val maxObservedConcurrency = AtomicInteger(0)
            val mockEngineConcurrency =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    if (url.contains("/v1beta/interactions") && extractInteractionModel(body) == "gemini-2.5-flash") {
                        val current = activeCalls.incrementAndGet()
                        maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
                        delay(50)
                        activeCalls.decrementAndGet()
                        respond(
                            content = """{"id":"vis-c","status":"completed","finish_reason":"STOP","output_text":"測試表情"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"id":"int-ok","status":"completed","finish_reason":"STOP","output_text":"ok"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            val concurrencyResolver =
                CustomEmojiVisionResolver(
                    meaningRepository = repo,
                    visionModel = "gemini-2.5-flash",
                    interactionClient = GeminiInteractionClient(httpClient = createClient(mockEngineConcurrency)),
                    apiKey = "secret-key",
                    clock = clock,
                )
            val eightTargets = (1..8).map { idx -> EmojiVisionRequest(id = "700$idx", name = "e$idx", isAnimated = false) }
            concurrencyResolver.resolveMissing(eightTargets)
            assertTrue(
                maxObservedConcurrency.get() in 2..5,
                "Expected bounded parallel concurrency in 2..5, got ${maxObservedConcurrency.get()}",
            )
            for (idx in 1..8) {
                assertEquals("測試表情", repo.findById("700$idx")?.meaning)
            }

            val resolverLogger = LoggerFactory.getLogger(CustomEmojiVisionResolver::class.java) as Logger
            val logAppender = ListAppender<ILoggingEvent>().apply { start() }
            resolverLogger.addAppender(logAppender)

            try {
                val capturedReplyPrompts = mutableListOf<String>()
                val slowEngine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        val body = request.body.toByteArray().decodeToString()
                        when {
                            url.contains("/v1beta/decisions") -> {
                                val decisionJson =
                                    """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}"""
                                respond(
                                    content = decisionJson,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            url.contains("/v1beta/interactions") && extractInteractionModel(body) == "gemini-2.5-flash" -> {
                                delay(1_000)
                                respond(
                                    content = """{"id":"vis-late","status":"completed","finish_reason":"STOP","output_text":"太慢了"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            else -> {
                                capturedReplyPrompts += extractInteractionPromptText(body)
                                respond(
                                    content = """{"id":"int-reply","status":"completed","finish_reason":"STOP","output_text":"照常回覆"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        }
                    }

                val slowClient = createClient(slowEngine)
                val timeoutResolver =
                    CustomEmojiVisionResolver(
                        meaningRepository = repo,
                        visionModel = "gemini-2.5-flash",
                        interactionClient = GeminiInteractionClient(httpClient = slowClient),
                        apiKey = "secret-key",
                        clock = clock,
                        roundTimeoutMs = 150L,
                    )
                val catalog = DefaultChatterEmojiCatalog(meaningRepository = repo, visionResolver = timeoutResolver, clock = clock)
                val channelPort =
                    FakeChatterChannelPort().apply {
                        guildEmojis = listOf(guildEmoji(8001L, "slow_emoji"))
                    }
                val service = createChatterService(slowClient, catalog, channelPort)

                val message =
                    ChatterAiTraceTestSupport.createMockMessage(
                        id = 2008L,
                        channelId = 3008L,
                        content = "等一下 <:slow_emoji:8001>",
                    )

                assertTrue(service.processMessage(message), "Round timeout must degrade gracefully without aborting ChatterService")
                val replyPrompt = capturedReplyPrompts.single()
                assertContains(replyPrompt, "<:slow_emoji:8001>")
                assertFalse(replyPrompt.contains("<:slow_emoji:8001>("), "Timed-out emoji must degrade to bare token")

                val warnLogs = logAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnLogs.any { it.contains("timed out", ignoreCase = true) },
                    "Expected observable WARN log indicating timeout/degraded resolution, got: $warnLogs",
                )
            } finally {
                resolverLogger.detachAppender(logAppender)
            }
        }

    @Test
    fun `concurrent resolveMissing calls deduplicate in-flight vision requests`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("dedup.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)

            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val mockEngine =
                MockEngine {
                    calls.incrementAndGet()
                    gate.await()
                    respond(
                        content = """{"id":"vis-dedup","status":"completed","finish_reason":"STOP","output_text":"共用語意"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val resolver =
                CustomEmojiVisionResolver(
                    meaningRepository = repo,
                    visionModel = "gemini-2.5-flash",
                    interactionClient = GeminiInteractionClient(httpClient = createClient(mockEngine)),
                    apiKey = "secret-key",
                    clock = clock,
                )

            val target = listOf(EmojiVisionRequest(id = "9901", name = "shared", isAnimated = false))
            val job1 = async { resolver.resolveMissing(target, timeoutMs = null) }
            val job2 = async { resolver.resolveMissing(target, timeoutMs = null) }
            delay(20)
            gate.complete(Unit)
            job1.await()
            job2.await()

            assertEquals(1, calls.get(), "Concurrent calls for the same emoji_id must deduplicate to 1 vision request")
            assertEquals("共用語意", repo.findById("9901")?.meaning)
        }

    @Test
    fun `single turn with uncached transcript and guild pool emojis enforces single combined round timeout without sequential retries`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("single-turn-budget.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)

            val visionCalls = AtomicInteger(0)
            val capturedReplyPrompts = mutableListOf<String>()
            val slowEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    when {
                        url.contains("/v1beta/decisions") -> {
                            respond(
                                content =
                                    """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        url.contains("/v1beta/interactions") && extractInteractionModel(body) == "gemini-2.5-flash" -> {
                            visionCalls.incrementAndGet()
                            delay(1_000)
                            respond(
                                content = """{"id":"vis-late","status":"completed","finish_reason":"STOP","output_text":"太慢了"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            capturedReplyPrompts += extractInteractionPromptText(body)
                            respond(
                                content = """{"id":"int-reply","status":"completed","finish_reason":"STOP","output_text":"照常回覆"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }

            val client = createClient(slowEngine)
            val resolver =
                CustomEmojiVisionResolver(
                    meaningRepository = repo,
                    visionModel = "gemini-2.5-flash",
                    interactionClient = GeminiInteractionClient(httpClient = client),
                    apiKey = "secret-key",
                    clock = clock,
                    roundTimeoutMs = 200L,
                )
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = repo, visionResolver = resolver, clock = clock)
            val channelPort =
                FakeChatterChannelPort().apply {
                    guildEmojis = listOf(guildEmoji(8102L, "guild_slow"))
                }
            val service = createChatterService(client, catalog, channelPort)

            val message =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 2010L,
                    channelId = 3010L,
                    content = "哈囉 <:ext_slow:8101>",
                )

            val startedAtNanos = System.nanoTime()
            assertTrue(
                service.processMessage(message),
                "Single turn with uncached transcript and guild pool emojis must degrade gracefully",
            )
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

            assertEquals(
                1,
                visionCalls.get(),
                "Expected only the decision-stage uncached emoji to invoke vision before the 200ms round budget was exhausted",
            )
            assertTrue(
                elapsedMs < 350L,
                "Expected turn to stay bounded within a single 200ms round timeout without chaining sequential timeouts, took ${elapsedMs}ms",
            )
            val replyPrompt = capturedReplyPrompts.single()
            assertContains(replyPrompt, "<:ext_slow:8101>")
            assertContains(replyPrompt, "<:guild_slow:8102>")
            assertFalse(
                replyPrompt.contains("<:ext_slow:8101>("),
                "Timed-out transcript emoji must degrade to bare token",
            )
            assertFalse(
                replyPrompt.contains("<:guild_slow:8102>："),
                "Skipped guild pool emoji after budget exhaustion must degrade to bare token",
            )
        }
}
