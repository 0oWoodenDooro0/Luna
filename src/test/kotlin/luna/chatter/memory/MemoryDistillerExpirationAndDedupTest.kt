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
import kotlinx.serialization.json.jsonObject
import luna.chatter.ChatterNoiseFilter
import luna.core.LunaHost
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryDistillerExpirationAndDedupTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }

    private fun createStore(clock: Clock): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("distill-exp-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    private fun mockMessage(
        id: Long,
        authorId: Long = 111L,
        username: String = "Alice",
        content: String,
    ): Message {
        val user =
            mockk<User>(relaxed = true) {
                every { this@mockk.id } returns Snowflake(authorId)
                every { this@mockk.username } returns username
                every { this@mockk.isBot } returns false
            }
        return mockk<Message>(relaxed = true) {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.channelId } returns Snowflake(1000L)
            every { this@mockk.author } returns user
            every { this@mockk.content } returns content
            every { this@mockk.type } returns MessageType.Default
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
        val escaped = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(text))
        return """
            {
              "id": "$id",
              "status": "completed",
              "output_text": $escaped
            }
            """.trimIndent()
    }

    private fun unitEmbedding(seed: Float): List<Float> = List(768) { idx -> if (idx == 0) seed else 0.0f }

    private fun createMockEmbeddingHttp(): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    if (url.contains(":batchEmbedContents")) {
                        val reqJson = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                        val count = reqJson["requests"]?.let { (it as JsonArray).size } ?: 0
                        val embeddingsJson =
                            (0 until count).joinToString(",") { idx ->
                                """{"values": ${unitEmbedding((idx + 1).toFloat())}}"""
                            }
                        respond(
                            content = """{"embeddings": [$embeddingsJson]}""",
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    } else {
                        respond(
                            content = """{"embedding": {"values": ${unitEmbedding(1.0f)}}}""",
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    }
                }
            }
        }

    @Test
    fun `extraction injects current date and day of week, clamps expires_at to 1-365 days, and warns on malformed dates`() =
        runTest {
            val now = Instant.parse("2026-11-23T10:00:00Z") // Monday
            val nowMs = now.toEpochMilli()
            val clock = MutableClock(now)
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")

            val existing =
                store.insertMemory(
                    guildId = "2000",
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    content = "Alice is preparing for a job interview",
                )

            val processorLogger = LoggerFactory.getLogger(MemoryCandidateProcessor::class.java) as Logger
            val logAppender = ListAppender<ILoggingEvent>().apply { start() }
            processorLogger.addAppender(logAppender)

            try {
                val interactionPayloads = mutableListOf<String>()
                var callCount = 0
                val interactionHttp =
                    HttpClient(MockEngine) {
                        install(ContentNegotiation) {
                            json(
                                Json {
                                    ignoreUnknownKeys = true
                                    encodeDefaults = false
                                },
                            )
                        }
                        engine {
                            addHandler { request ->
                                interactionPayloads.add((request.body as io.ktor.http.content.TextContent).text)
                                callCount++
                                val responseText =
                                    if (callCount == 1) {
                                        """
                                        [
                                          {"scope": "user", "target_user_id": "111", "content": "Alice has a past-due appointment", "expires_at": "2026-11-20"},
                                          {"scope": "user", "target_user_id": "111", "content": "Alice plans a decade-long sabbatical", "expires_at": "2030-05-01"},
                                          {"scope": "user", "target_user_id": "111", "content": "Alice has a job interview on Dec 1", "expires_at": "2026-12-01"},
                                          {"scope": "user", "target_user_id": "111", "content": "Alice likes jazz music", "expires_at": "not-a-date"}
                                        ]
                                        """.trimIndent()
                                    } else {
                                        """
                                        [
                                          {"action": "INSERT", "candidate_index": 0, "content": "Alice has a past-due appointment"},
                                          {"action": "INSERT", "candidate_index": 1, "content": "Alice plans a decade-long sabbatical"},
                                          {"action": "UPDATE", "memory_id": ${existing.id}, "candidate_index": 2, "content": "Alice has a job interview on Dec 1", "expires_at": "2026-12-01"},
                                          {"action": "INSERT", "candidate_index": 3, "content": "Alice likes jazz music"}
                                        ]
                                        """.trimIndent()
                                    }
                                respond(
                                    content = interactionJsonResponse("int-$callCount", responseText),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                )
                            }
                        }
                    }

                val distiller =
                    MemoryDistiller(
                        memoryStore = store,
                        embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                        interactionClient = GeminiInteractionClient(httpClient = interactionHttp),
                        noiseFilter = ChatterNoiseFilter(),
                        config = ChatterMemoryConfig(),
                        apiKey = "test-key",
                        baseUrl = "https://generativelanguage.googleapis.com",
                        clock = clock,
                        messagesAfterFetcher = { _, _, _ ->
                            listOf(mockMessage(id = 105L, content = "I have a job interview on Dec 1 and I like jazz"))
                        },
                    )

                val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")
                assertTrue(outcome is DistillOutcome.Completed, "Expected Completed but got $outcome")

                // Current ISO-8601 date + day of week injected into extraction prompt
                val extractPayload = interactionPayloads.first()
                assertTrue(
                    extractPayload.contains("2026-11-23") && extractPayload.contains("Monday"),
                    "Extraction payload must include current ISO date and day of week: $extractPayload",
                )

                val memories = store.listUserMemories(guildId = "2000", userId = "111").associateBy { it.content }

                val minClamped = memories["Alice has a past-due appointment"]?.expiresAt
                assertNotNull(minClamped)
                assertEquals(nowMs + TimeUnit.DAYS.toMillis(1), minClamped, "Past/too-short expires_at must clamp to now + 1 day")

                val maxClamped = memories["Alice plans a decade-long sabbatical"]?.expiresAt
                assertNotNull(maxClamped)
                assertEquals(nowMs + TimeUnit.DAYS.toMillis(365), maxClamped, "Far-future expires_at must clamp to now + 365 days")

                val validDec1 = memories["Alice has a job interview on Dec 1"]?.expiresAt
                val expectedDec1 =
                    LocalDate
                        .parse("2026-12-01")
                        .atTime(23, 59, 59, 999_000_000)
                        .atZone(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                assertEquals(expectedDec1, validDec1, "Valid in-range expires_at should resolve to end-of-day UTC")

                assertTrue(memories.containsKey("Alice likes jazz music"), "Valid durable fact must be persisted")
                assertNull(memories["Alice likes jazz music"]?.expiresAt, "Malformed expires_at must degrade to null")

                assertTrue(
                    logAppender.list.any { event ->
                        event.level == Level.WARN && event.formattedMessage.contains("not-a-date")
                    },
                    "Expected observable WARN log for malformed expires_at date",
                )
            } finally {
                processorLogger.detachAppender(logAppender)
            }
        }

    @Test
    fun `reconciliation queries Top-6 non-expired memories and deduplicates near-identical candidates within same batch`() =
        runTest {
            val now = Instant.parse("2026-11-23T10:00:00Z")
            val nowMs = now.toEpochMilli()
            val clock = MutableClock(now)
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")

            // Insert 6 active memories + 1 expired memory matching "coffee"
            repeat(6) { idx ->
                store.insertMemory(
                    guildId = "2000",
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    content = "Alice coffee preference note #$idx",
                )
            }
            val expiredMem =
                store.insertMemory(
                    guildId = "2000",
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    content = "Alice coffee event expired",
                    expiresAt = nowMs - 1_000L,
                )

            val interactionPayloads = mutableListOf<String>()
            var embeddedCount = 0
            var callCount = 0

            val interactionHttp =
                HttpClient(MockEngine) {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                encodeDefaults = false
                            },
                        )
                    }
                    engine {
                        addHandler { request ->
                            interactionPayloads.add((request.body as io.ktor.http.content.TextContent).text)
                            callCount++
                            val responseText =
                                if (callCount == 1) {
                                    // Near-identical duplicates for user 111 + distinct guild/user222 candidates
                                    """
                                    [
                                      {"scope": "user", "target_user_id": "111", "content": "Alice drinks iced coffee"},
                                      {"scope": "user", "target_user_id": "111", "content": "Alice drinks iced coffee!"},
                                      {"scope": "user", "target_user_id": "111", "content": "Alice drinks iced coffee every morning without sugar"},
                                      {"scope": "user", "target_user_id": "222", "content": "Bob drinks iced coffee"}
                                    ]
                                    """.trimIndent()
                                } else {
                                    """
                                    [
                                      {"action": "INSERT", "candidate_index": 0},
                                      {"action": "INSERT", "candidate_index": 1}
                                    ]
                                    """.trimIndent()
                                }
                            respond(
                                content = interactionJsonResponse("int-$callCount", responseText),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }

            val embeddingHttp =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            val url = request.url.toString()
                            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            if (url.contains(":batchEmbedContents")) {
                                val reqJson = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                                val count = reqJson["requests"]?.let { (it as JsonArray).size } ?: 0
                                embeddedCount += count
                                val embeddingsJson =
                                    (0 until count).joinToString(",") { idx ->
                                        """{"values": ${unitEmbedding((idx + 1).toFloat())}}"""
                                    }
                                respond(
                                    content = """{"embeddings": [$embeddingsJson]}""",
                                    status = HttpStatusCode.OK,
                                    headers = jsonHeaders,
                                )
                            } else {
                                respond(
                                    content = """{"embedding": {"values": ${unitEmbedding(1.0f)}}}""",
                                    status = HttpStatusCode.OK,
                                    headers = jsonHeaders,
                                )
                            }
                        }
                    }
                }

            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = embeddingHttp, apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = interactionHttp),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ ->
                        listOf(mockMessage(id = 105L, content = "I drink iced coffee every morning without sugar"))
                    },
                )

            val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")
            assertTrue(outcome is DistillOutcome.Completed, "Expected Completed distillation outcome but got $outcome")

            // 3 near-identical user-111 candidates collapsed to 1 richer candidate + 1 user-222 candidate = 2 embedded
            assertEquals(2, embeddedCount, "Intra-batch dedup should collapse near-identical candidates before embedding")

            val reconcilePayload = interactionPayloads[1]
            // Top-6 existing active memories included for candidate 0, expired memory excluded
            repeat(6) { idx ->
                assertTrue(
                    reconcilePayload.contains("Alice coffee preference note #$idx"),
                    "Reconciliation prompt should include Top-6 similar memory #$idx",
                )
            }
            assertTrue(
                !reconcilePayload.contains("id=${expiredMem.id},"),
                "Expired memory must be excluded from reconciliation lookup",
            )
            // Richer candidate preserved
            val aliceMemories = store.listUserMemories("2000", "111").map { it.content }
            assertTrue(
                aliceMemories.contains("Alice drinks iced coffee every morning without sugar"),
                "Richer deduplicated candidate should be preserved: $aliceMemories",
            )
            assertEquals(7, aliceMemories.size)
        }
}
