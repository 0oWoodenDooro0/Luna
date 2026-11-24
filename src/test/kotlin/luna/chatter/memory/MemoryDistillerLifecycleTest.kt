package luna.chatter.memory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import luna.chatter.ChatterNoiseFilter
import luna.core.LunaHost
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryDistillerLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceMinutes(minutes: Long) {
            current = current.plusSeconds(minutes * 60)
        }
    }

    private fun createStore(clock: Clock): Pair<SqliteChatterMemoryStore, org.jetbrains.exposed.v1.jdbc.Database> {
        val dbPath = tempDir.resolve("distiller-lifecycle-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock) to db
    }

    private fun mockMessage(
        id: Long,
        channelId: Long = 1000L,
        authorId: Long = 111L,
        username: String = "Alice",
        content: String = "hello",
        isBot: Boolean = false,
        type: MessageType = MessageType.Default,
    ): Message {
        val user =
            mockk<User>(relaxed = true) {
                every { this@mockk.id } returns Snowflake(authorId)
                every { this@mockk.username } returns username
                every { this@mockk.isBot } returns isBot
            }
        return mockk<Message>(relaxed = true) {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.channelId } returns Snowflake(channelId)
            every { this@mockk.author } returns user
            every { this@mockk.content } returns content
            every { this@mockk.type } returns type
            every { webhookId } returns null
            every { attachments } returns emptySet()
            every { embeds } returns emptyList()
            every { mentionedUserIds } returns emptySet()
        }
    }

    private fun interactionJsonResponse(
        id: String,
        text: String,
    ): String {
        val escaped = Json.encodeToString(JsonPrimitive(text))
        return """
            {
              "id": "$id",
              "status": "completed",
              "output_text": $escaped
            }
            """.trimIndent()
    }

    private fun unitEmbedding(seed: Float): List<Float> = List(768) { idx -> if (idx == 0) seed else 0.0f }

    private fun createMockInteractionHttp(handler: (callIndex: Int) -> Pair<HttpStatusCode, String>): HttpClient {
        val calls = AtomicInteger(0)
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = false
                    },
                )
            }
            engine {
                addHandler {
                    val idx = calls.incrementAndGet()
                    val (status, body) = handler(idx)
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
    }

    private fun createMockEmbeddingHttp(statusProvider: (callIndex: Int) -> HttpStatusCode = { HttpStatusCode.OK }): HttpClient {
        val calls = AtomicInteger(0)
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val idx = calls.incrementAndGet()
                    val status = statusProvider(idx)
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    if (status != HttpStatusCode.OK) {
                        respond(content = """{"error": "transient error"}""", status = status, headers = jsonHeaders)
                    } else if (request.url.toString().contains(":batchEmbedContents")) {
                        val reqJson = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                        val count = reqJson["requests"]?.let { (it as JsonArray).size } ?: 0
                        val embeddingsJson =
                            (0 until count).joinToString(",") { i ->
                                """{"values": ${unitEmbedding((i + 1).toFloat())}}"""
                            }
                        respond(content = """{"embeddings": [$embeddingsJson]}""", status = HttpStatusCode.OK, headers = jsonHeaders)
                    } else {
                        respond(content = """{"embedding": {"values": ${unitEmbedding(1.0f)}}}""", status = HttpStatusCode.OK, headers = jsonHeaders)
                    }
                }
            }
        }
    }

    @Test
    fun `cold start writes cursor at first observed message without backfilling historical messages`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val (store, _) = createStore(clock)
            var fetcherCalls = 0
            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = createMockInteractionHttp { HttpStatusCode.OK to "{}" }),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ ->
                        fetcherCalls++
                        emptyList()
                    },
                )

            distiller.observeMessage(
                message = mockMessage(id = 100L, content = "first message"),
                guildId = "2000",
            )

            val cursor = store.getCursor("1000")
            assertNotNull(cursor)
            assertEquals("100", cursor.lastSummarizedMessageId)
            assertEquals("2000", cursor.guildId)

            clock.advanceMinutes(60)
            distiller.tick()
            assertEquals(0, fetcherCalls, "Cold-start channel must not trigger backfill distillation")
        }

    @Test
    fun `ignores non-allowed channels and channels flooded only by bots`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val (store, _) = createStore(clock)
            var fetcherCalls = 0
            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = createMockInteractionHttp { HttpStatusCode.OK to "{}" }),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(quietMinutes = 5, minMessages = 5),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    clock = clock,
                    allowedChannels = setOf(Snowflake(1000L)),
                    messagesAfterFetcher = { _, _, _ ->
                        fetcherCalls++
                        emptyList()
                    },
                )

            distiller.observeMessage(mockMessage(id = 10L, channelId = 9999L), guildId = "2000")
            assertNull(store.getCursor("9999"))

            distiller.observeMessage(mockMessage(id = 100L, channelId = 1000L), guildId = "2000")
            repeat(10) { i ->
                distiller.observeMessage(
                    mockMessage(id = 101L + i, channelId = 1000L, authorId = 888L, isBot = true, content = "bot msg $i"),
                    guildId = "2000",
                )
            }
            repeat(4) { i ->
                distiller.observeMessage(
                    mockMessage(id = 200L + i, channelId = 1000L, authorId = 111L, isBot = false, content = "human msg $i"),
                    guildId = "2000",
                )
            }

            clock.advanceMinutes(40)
            distiller.tick()
            assertEquals(0, fetcherCalls, "Bot flood + <5 human messages must not trigger distillation")
        }

    @Test
    fun `end-to-end observe tick distill advances cursor and persists extracted memory`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val (store, _) = createStore(clock)
            store.upsertCursor("1000", "2000", "100")

            val messages =
                (1..5).map { i ->
                    mockMessage(id = 100L + i, authorId = 111L, username = "Alice", content = "I love matcha latte #$i")
                }

            val interactionHttp =
                createMockInteractionHttp { callIdx ->
                    val text =
                        if (callIdx == 1) {
                            """[{"scope": "user", "target_user_id": "111", "content": "Alice loves matcha lattes"}]"""
                        } else {
                            """[{"action": "INSERT", "candidate_index": 0, "content": "Alice loves matcha lattes"}]"""
                        }
                    HttpStatusCode.OK to interactionJsonResponse("int-$callIdx", text)
                }

            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = interactionHttp),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(quietMinutes = 5, minMessages = 5),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    clock = clock,
                    messagesAfterFetcher = { _, afterId, _ -> messages.filter { it.id > afterId } },
                )

            messages.forEach { distiller.observeMessage(it, guildId = "2000") }

            clock.advanceMinutes(4)
            distiller.tick()
            assertEquals("100", store.getCursor("1000")?.lastSummarizedMessageId, "Should not trigger before 5m quiet")

            clock.advanceMinutes(1)
            distiller.tick()
            assertEquals("105", store.getCursor("1000")?.lastSummarizedMessageId, "Cursor must advance after distillation")
            val stored = store.listUserMemories("2000", "111")
            assertEquals(1, stored.size)
            assertEquals("Alice loves matcha lattes", stored.single().content)

            // Next tick with no new messages must not re-distill
            clock.advanceMinutes(10)
            distiller.tick()
            assertEquals("105", store.getCursor("1000")?.lastSummarizedMessageId)
        }

    @Test
    fun `forced triggers fire at maxBatchMessages or maxBatchAgeMinutes without quiet gap`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val (store, _) = createStore(clock)
            store.upsertCursor("1000", "2000", "100")
            store.upsertCursor("1001", "2000", "200")

            val fetchedChannels = mutableListOf<String>()
            val interactionHttp =
                createMockInteractionHttp { callIdx ->
                    HttpStatusCode.OK to interactionJsonResponse("int-$callIdx", "[]")
                }

            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = interactionHttp),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(quietMinutes = 5, minMessages = 5, maxBatchMessages = 50, maxBatchAgeMinutes = 30),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    clock = clock,
                    messagesAfterFetcher = { channelId, _, _ ->
                        fetchedChannels.add(channelId.toString())
                        listOf(mockMessage(id = 999L, channelId = channelId.value.toLong(), content = "hi"))
                    },
                )

            repeat(50) { i ->
                distiller.observeMessage(mockMessage(id = 101L + i, channelId = 1000L), guildId = "2000")
            }
            repeat(4) { i ->
                distiller.observeMessage(mockMessage(id = 201L + i, channelId = 1001L), guildId = "2000")
            }
            clock.advanceMinutes(30)
            distiller.observeMessage(mockMessage(id = 205L, channelId = 1001L), guildId = "2000")

            distiller.tick()
            assertEquals(listOf("1000", "1001"), fetchedChannels)
            assertEquals("999", store.getCursor("1000")?.lastSummarizedMessageId)
            assertEquals("999", store.getCursor("1001")?.lastSummarizedMessageId)
        }

    @Test
    fun `LRU activity tracker bounds capacity to 1000 channels by default`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val (store, _) = createStore(clock)
            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = createMockInteractionHttp { HttpStatusCode.OK to "[]" }),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ -> emptyList() },
                )

            for (ch in 1L..1005L) {
                distiller.observeMessage(mockMessage(id = ch * 10, channelId = ch), guildId = "2000")
            }

            assertEquals(MemoryDistiller.DEFAULT_MAX_TRACKED_CHANNELS, distiller.trackedChannelCount())
        }

    @Test
    fun `transient LLM, embedding, and SQLite errors fail open with WARN logs and retry on next tick`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val (realStore, _) = createStore(clock)
            realStore.upsertCursor("1000", "2000", "100")

            var failCursorRead = false
            val storeWrapper =
                object : ChatterMemoryStore by realStore {
                    override fun getCursor(channelId: String): ChatterMemoryCursor? {
                        if (failCursorRead) {
                            throw IllegalStateException("SQLite busy")
                        }
                        return realStore.getCursor(channelId)
                    }
                }

            var failLlm = true
            var failEmbed = true
            var tick4Calls = 0
            val interactionHttp =
                createMockInteractionHttp { callIdx ->
                    if (failLlm) {
                        HttpStatusCode.ServiceUnavailable to """{"error": "unavailable"}"""
                    } else if (failEmbed || tick4Calls++ == 0) {
                        HttpStatusCode.OK to
                            interactionJsonResponse("int-$callIdx", """[{"scope":"user","target_user_id":"111","content":"Alice likes tea"}]""")
                    } else {
                        HttpStatusCode.OK to
                            interactionJsonResponse("int-$callIdx", """[{"action":"INSERT","candidate_index":0,"content":"Alice likes tea"}]""")
                    }
                }

            val embeddingHttp =
                createMockEmbeddingHttp {
                    if (failEmbed) HttpStatusCode.TooManyRequests else HttpStatusCode.OK
                }

            val distillerLogger = LoggerFactory.getLogger(MemoryDistiller::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            distillerLogger.addAppender(appender)

            try {
                val distiller =
                    MemoryDistiller(
                        memoryStore = storeWrapper,
                        embeddingClient = GeminiEmbeddingClient(httpClient = embeddingHttp, apiKey = "test-key"),
                        interactionClient = GeminiInteractionClient(httpClient = interactionHttp),
                        noiseFilter = ChatterNoiseFilter(),
                        config = ChatterMemoryConfig(quietMinutes = 5, minMessages = 5),
                        apiKey = "test-key",
                        baseUrl = "https://generativelanguage.googleapis.com",
                        clock = clock,
                        messagesAfterFetcher = { _, _, _ ->
                            listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "I like tea"))
                        },
                    )

                repeat(5) { i ->
                    distiller.observeMessage(mockMessage(id = 101L + i), guildId = "2000")
                }
                clock.advanceMinutes(5)

                // Tick 1: SQLite failure
                failCursorRead = true
                distiller.tick()
                assertEquals("100", realStore.getCursor("1000")?.lastSummarizedMessageId)

                // Tick 2: LLM 503 failure
                failCursorRead = false
                distiller.tick()
                assertEquals("100", realStore.getCursor("1000")?.lastSummarizedMessageId)

                // Tick 3: Embedding 429 failure
                failLlm = false
                distiller.tick()
                assertEquals("100", realStore.getCursor("1000")?.lastSummarizedMessageId)

                // Tick 4: Succeeds cleanly after transient errors cleared inFlight
                failEmbed = false
                distiller.tick()
                assertEquals("105", realStore.getCursor("1000")?.lastSummarizedMessageId)
                assertEquals(1, realStore.listUserMemories("2000", "111").size)

                val warnLogs = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(warnLogs.any { it.contains("SQLite busy") }, "Expected WARN log for SQLite error: $warnLogs")
                assertTrue(warnLogs.count { it.contains("leaving cursor unchanged") } >= 3, "Expected WARN logs for transient failures: $warnLogs")
            } finally {
                distillerLogger.detachAppender(appender)
            }
        }

    @Test
    fun `tick purges expired memories even when no distillation batches are eligible`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-20T00:00:00Z"))
            val (store, db) = createStore(clock)
            val nowMs = clock.millis()
            val oneDayMs = 86_400_000L

            val durable =
                store.insertMemory(
                    guildId = "2000",
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    content = "Alice loves ramen.",
                    expiresAt = null,
                )
            store.insertMemory(
                guildId = "2000",
                scope = MemoryScope.USER,
                targetUserId = "111",
                content = "Alice has a flight tomorrow.",
                expiresAt = nowMs + oneDayMs,
            )

            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = createMockInteractionHttp { HttpStatusCode.OK to "[]" }),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ -> emptyList() },
                )

            clock.advanceMinutes(24 * 60 + 1)
            distiller.tick()

            val remainingIds =
                transaction(db) {
                    val conn = TransactionManager.current().connection.connection as Connection
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT id FROM chatter_memories").use { rs ->
                            buildList {
                                while (rs.next()) add(rs.getLong(1))
                            }
                        }
                    }
                }
            assertEquals(listOf(durable.id), remainingIds)
        }
}
