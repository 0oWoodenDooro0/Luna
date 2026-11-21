package luna.chatter.emoji

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.GuildCustomEmoji
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterEmojiServiceVisionAndMeaningTest {
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

    private fun createInteractionClient(engine: MockEngine): GeminiInteractionClient =
        GeminiInteractionClient(
            httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json(json)
                    }
                },
        )

    private fun jsonInteractionResponse(text: String): String =
        """
        {
          "id": "interaction-1",
          "status": "completed",
          "finish_reason": "STOP",
          "output_text": "$text"
        }
        """.trimIndent()

    private class MutableClock(
        initialEpochMs: Long,
    ) : Clock() {
        private val nowMs = AtomicLong(initialEpochMs)

        fun advanceMs(deltaMs: Long) {
            nowMs.addAndGet(deltaMs)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(nowMs.get())
    }

    @Test
    fun `resolves uncached emojis via vision caches in SQLite and annotates transcript and pools`() =
        runTest {
            val db = openDatabase("vision-basic.db")
            val requestedUris = CopyOnWriteArrayList<String>()
            val engine =
                MockEngine { request ->
                    val body = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val imageBlock = body["input"]!!.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }.jsonObject
                    val uri = imageBlock["uri"]!!.jsonPrimitive.content
                    requestedUris += uri
                    val meaning = if (uri.contains("1001")) "無奈嘆氣" else "開心比讚"
                    respond(
                        content = jsonInteractionResponse(meaning),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = db,
                    visionModel = "gemini-2.5-flash-lite",
                    interactionClient = createInteractionClient(engine),
                    apiKey = "test-key",
                )

            val guildEmoji = GuildCustomEmoji(id = Snowflake(2002L), name = "nice", isAnimated = false)
            val turn =
                service.beginTurn(
                    channelId = Snowflake(10L),
                    guildId = Snowflake(99L),
                    guildEmojisProvider = { listOf(guildEmoji) },
                )

            val annotated = turn.annotateTranscript("Alice: 今天好累 <:sigh:1001>")
            assertEquals("Alice: 今天好累 <:sigh:1001>(無奈嘆氣)", annotated)

            val prepared = turn.prepareReplyEmojis("Alice: 今天好累 <:sigh:1001>")
            assertEquals("Alice: 今天好累 <:sigh:1001>(無奈嘆氣)", prepared.transcript)
            assertEquals(1, prepared.guildEmojis.size)
            assertEquals("開心比讚", prepared.guildEmojis.single().meaning)
            assertEquals(2, requestedUris.size)
            assertTrue(requestedUris.any { it.endsWith("/1001.png") }, "Expected static PNG CDN URI for 1001, got $requestedUris")
            assertTrue(requestedUris.any { it.endsWith("/2002.png") }, "Expected static PNG CDN URI for 2002, got $requestedUris")

            // Second service instance backed by same SQLite reuses cached meanings with zero vision calls
            val callCountAfterRestart = AtomicInteger(0)
            val secondEngine =
                MockEngine {
                    callCountAfterRestart.incrementAndGet()
                    respond("", HttpStatusCode.InternalServerError)
                }
            val restartedService: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = db,
                    visionModel = "gemini-2.5-flash-lite",
                    interactionClient = createInteractionClient(secondEngine),
                    apiKey = "test-key",
                )
            val restartedTurn =
                restartedService.beginTurn(
                    channelId = Snowflake(10L),
                    guildId = Snowflake(99L),
                    guildEmojisProvider = { listOf(guildEmoji.copy(name = "renamed_nice")) },
                )
            val cachedPrepared = restartedTurn.prepareReplyEmojis("Bob: <:renamed_sigh:1001>")
            assertEquals("Bob: <:renamed_sigh:1001>(無奈嘆氣)", cachedPrepared.transcript)
            assertEquals("開心比讚", cachedPrepared.guildEmojis.single().meaning)
            assertEquals(0, callCountAfterRestart.get())
        }

    @Test
    fun `animated emoji falls back from gif to static png with warning log`() =
        runTest {
            val db = openDatabase("vision-animated.db")
            val requestedMimeTypes = CopyOnWriteArrayList<String>()
            val engine =
                MockEngine { request ->
                    val body = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val imageBlock = body["input"]!!.jsonArray.first { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }.jsonObject
                    val mime = imageBlock["mime_type"]!!.jsonPrimitive.content
                    requestedMimeTypes += mime
                    if (mime == "image/gif") {
                        respond(
                            content = """{"error":{"message":"unsupported gif"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = jsonInteractionResponse("狂喜跳舞"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val logbackLogger = LoggerFactory.getLogger("luna.chatter.emoji") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                val service: ChatterEmojiService =
                    DefaultChatterEmojiService(
                        database = db,
                        visionModel = "gemini-2.5-flash-lite",
                        interactionClient = createInteractionClient(engine),
                        apiKey = "test-key",
                    )
                val turn =
                    service.beginTurn(
                        channelId = Snowflake(10L),
                        guildId = Snowflake(99L),
                        guildEmojisProvider = { emptyList() },
                    )
                val annotated = turn.annotateTranscript("Alice: <a:dance:3003>")
                assertEquals("Alice: <a:dance:3003>(狂喜跳舞)", annotated)
                assertEquals(listOf("image/gif", "image/png"), requestedMimeTypes)
                assertTrue(
                    appender.list.any { it.level == Level.WARN && it.formattedMessage.contains(".png") },
                    "Expected WARN log for animated .gif -> .png fallback",
                )
            } finally {
                logbackLogger.detachAppender(appender)
            }
        }

    @Test
    fun `enforces one hour failure cooldown and degrades gracefully to bare token`() =
        runTest {
            val db = openDatabase("vision-cooldown.db")
            val clock = MutableClock(1_700_000_000_000L)
            val calls = AtomicInteger(0)
            val engine =
                MockEngine {
                    val attempt = calls.incrementAndGet()
                    if (attempt == 1) {
                        respond(
                            content = """{"error":{"message":"rate limited"}}""",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = jsonInteractionResponse("尷尬微笑"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val service: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = db,
                    visionModel = "gemini-2.5-flash-lite",
                    interactionClient = createInteractionClient(engine),
                    apiKey = "test-key",
                    clock = clock,
                )

            val turn1 = service.beginTurn(Snowflake(10L), Snowflake(99L), guildEmojisProvider = { emptyList() })
            assertEquals("Alice: <:awkward:4004>", turn1.annotateTranscript("Alice: <:awkward:4004>"))
            assertEquals(1, calls.get())

            // Within 1-hour cooldown: must not retry
            clock.advanceMs(30 * 60 * 1000L)
            val turn2 = service.beginTurn(Snowflake(10L), Snowflake(99L), guildEmojisProvider = { emptyList() })
            assertEquals("Alice: <:awkward:4004>", turn2.annotateTranscript("Alice: <:awkward:4004>"))
            assertEquals(1, calls.get())

            // Past 1-hour cooldown: retries and succeeds
            clock.advanceMs(31 * 60 * 1000L)
            val turn3 = service.beginTurn(Snowflake(10L), Snowflake(99L), guildEmojisProvider = { emptyList() })
            assertEquals("Alice: <:awkward:4004>(尷尬微笑)", turn3.annotateTranscript("Alice: <:awkward:4004>"))
            assertEquals(2, calls.get())
        }

    @Test
    fun `disables vision calls when vision model is blank while still reading cached meanings`() =
        runTest {
            val db = openDatabase("vision-opt-in.db")
            val calls = AtomicInteger(0)
            val engine =
                MockEngine {
                    calls.incrementAndGet()
                    respond(
                        content = jsonInteractionResponse("開心"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            // Seed one meaning with vision enabled
            val seededService =
                DefaultChatterEmojiService(
                    database = db,
                    visionModel = "gemini-2.5-flash-lite",
                    interactionClient = createInteractionClient(engine),
                    apiKey = "test-key",
                )
            seededService
                .beginTurn(Snowflake(10L), Snowflake(99L), guildEmojisProvider = { emptyList() })
                .annotateTranscript("Seed <:known:5001>")
            assertEquals(1, calls.get())

            // Opt-out service (visionModel = null) reads cached 5001, leaves 5002 bare, zero HTTP calls
            val optOutService: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = db,
                    visionModel = null,
                    interactionClient = createInteractionClient(engine),
                    apiKey = "test-key",
                )
            val annotated =
                optOutService
                    .beginTurn(Snowflake(10L), Snowflake(99L), guildEmojisProvider = { emptyList() })
                    .annotateTranscript("Mix <:known:5001> <:unknown:5002>")
            assertEquals("Mix <:known:5001>(開心) <:unknown:5002>", annotated)
            assertEquals(1, calls.get())
        }

    @Test
    fun `shares turn vision budget across stages enforces concurrency cap and degrades on timeout`() =
        runTest {
            val db = openDatabase("vision-budget.db")
            val inFlight = AtomicInteger(0)
            val maxObservedConcurrency = AtomicInteger(0)
            val totalCalls = AtomicInteger(0)
            val engine =
                MockEngine {
                    totalCalls.incrementAndGet()
                    val current = inFlight.incrementAndGet()
                    maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, current) }
                    try {
                        delay(300L)
                        respond(
                            content = jsonInteractionResponse("慢速表情"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }

            val logbackLogger = LoggerFactory.getLogger("luna.chatter.emoji") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                val service: ChatterEmojiService =
                    DefaultChatterEmojiService(
                        database = db,
                        visionModel = "gemini-2.5-flash-lite",
                        interactionClient = createInteractionClient(engine),
                        apiKey = "test-key",
                        visionMaxConcurrency = 2,
                        visionRoundTimeoutMs = 120L,
                    )

                val poolEmojis =
                    (1..4).map { idx ->
                        GuildCustomEmoji(id = Snowflake(6000L + idx), name = "slow$idx", isAnimated = false)
                    }
                val turn =
                    service.beginTurn(
                        channelId = Snowflake(10L),
                        guildId = Snowflake(99L),
                        guildEmojisProvider = { poolEmojis },
                    )

                // Stage 1 exhausts the 120ms turn round budget
                val stage1 = turn.annotateTranscript("Slow <:s1:6001> <:s2:6002> <:s3:6003>")
                assertEquals("Slow <:s1:6001> <:s2:6002> <:s3:6003>", stage1)
                val callsAfterStage1 = totalCalls.get()

                // Stage 2 in the SAME turn must not start another full timeout round
                val stage2 = turn.prepareReplyEmojis("Slow <:s1:6001> <:s4:6004>")
                assertNull(stage2.guildEmojis.first().meaning)
                assertEquals(callsAfterStage1, totalCalls.get())
                assertTrue(
                    maxObservedConcurrency.get() <= 2,
                    "Expected concurrency <= 2, observed ${maxObservedConcurrency.get()}",
                )
                val warnMessages = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(warnMessages.any { it.contains("timed out") }, "Expected timeout WARN log, got $warnMessages")
                assertTrue(warnMessages.any { it.contains("budget exhausted") }, "Expected budget-exhausted WARN log, got $warnMessages")
                assertContains(stage2.transcript, "<:s4:6004>")
            } finally {
                logbackLogger.detachAppender(appender)
            }
        }
}
