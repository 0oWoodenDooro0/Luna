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

class ChatterEmojiVisionFallbackTest {
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
    fun `animated emoji requests gif first, falls back to static png on gif failure, and emits observable warning log`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("animated-fallback.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)

            val resolverLogger = LoggerFactory.getLogger(CustomEmojiVisionResolver::class.java) as Logger
            val logAppender = ListAppender<ILoggingEvent>().apply { start() }
            resolverLogger.addAppender(logAppender)

            try {
                val visionImageInputs = mutableListOf<Pair<String, String>>()
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
                                val input = extractInteractionImageInputs(body).single()
                                visionImageInputs += input
                                if (input.first.endsWith(".gif")) {
                                    respond(
                                        content = """{"error":{"message":"unsupported gif"}}""",
                                        status = HttpStatusCode.BadRequest,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                } else {
                                    respond(
                                        content = """{"id":"vis-png","status":"completed","finish_reason":"STOP","output_text":"瘋狂點頭"}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                }
                            }

                            else -> {
                                respond(
                                    content = """{"id":"int-reply","status":"completed","finish_reason":"STOP","output_text":"笑死 <a:nod:1003>"}""",
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
                        guildEmojis = listOf(guildEmoji(1003L, "nod", isAnimated = true))
                    }
                val service =
                    ChatterService(
                        filter = ChatterNoiseFilter(cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })),
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

                val message =
                    ChatterAiTraceTestSupport.createMockMessage(
                        id = 2003L,
                        channelId = 3003L,
                        content = "<a:nod:1003> 確實",
                    )

                assertTrue(service.processMessage(message), "Expected ChatterService to handle message with animated emoji fallback")
                assertEquals(
                    listOf(
                        "https://cdn.discordapp.com/emojis/1003.gif" to "image/gif",
                        "https://cdn.discordapp.com/emojis/1003.png" to "image/png",
                    ),
                    visionImageInputs,
                    "Expected animated emoji to try .gif first and fall back once to .png",
                )
                assertEquals("瘋狂點頭", repo.findById("1003")?.meaning)

                val warnLogs = logAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnLogs.any { it.contains("1003") && it.contains(".gif") && it.contains(".png") },
                    "Expected observable WARN log noting emoji ID 1003 and .gif -> .png fallback, got: $warnLogs",
                )
            } finally {
                resolverLogger.detachAppender(logAppender)
            }
        }

    @Test
    fun `rename updates stored name without re-invoking vision and is_manual_override rows are never overwritten`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("rename-override.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)

            repo.upsertResolvedMeaning(emojiId = "1001", name = "old_name", isAnimated = false, meaning = "原本意思")
            transaction(db) {
                CustomEmojiMeaningsTable.insert {
                    it[emojiId] = "1002"
                    it[name] = "manual_emoji"
                    it[isAnimated] = false
                    it[meaning] = "管理員手動設定"
                    it[isManualOverride] = true
                    it[updatedAt] = clock.millis()
                    it[failedUntil] = null
                }
            }

            val visionCallCount = AtomicInteger(0)
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
                            visionCallCount.incrementAndGet()
                            respond(
                                content = """{"id":"vis-unexpected","status":"completed","finish_reason":"STOP","output_text":"不應該"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            respond(
                                content = """{"id":"int-reply","status":"completed","finish_reason":"STOP","output_text":"收到"}""",
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
                    guildEmojis = listOf(guildEmoji(1001L, "new_name"), guildEmoji(1002L, "manual_renamed"))
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })),
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

            val message =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 2004L,
                    channelId = 3004L,
                    content = "<:new_name:1001> <:manual_renamed:1002>",
                )

            assertTrue(service.processMessage(message), "Expected ChatterService to handle message with renamed and manual-override emojis")
            assertEquals(0, visionCallCount.get(), "Renames and manual overrides must not invoke vision API")

            val renamed = repo.findById("1001")
            assertNotNull(renamed)
            assertEquals("new_name", renamed.name)
            assertEquals("原本意思", renamed.meaning)

            val manual = repo.findById("1002")
            assertNotNull(manual)
            assertEquals("manual_renamed", manual.name)
            assertEquals("管理員手動設定", manual.meaning)
            assertTrue(manual.isManualOverride, "Manual override flag must be preserved after emoji rename")
        }
}
