package luna.chatter.memory

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
import kotlinx.serialization.json.jsonObject
import luna.chatter.ChatterNoiseFilter
import luna.core.LunaHost
import luna.core.ai.AiTraceJson
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRecorder
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryDistillerTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = current

        fun advanceMinutes(minutes: Long) {
            current = current.plusSeconds(minutes * 60)
        }
    }

    private class CapturingTraceRecorder : AiTraceRecorder {
        val records = mutableListOf<AiTraceRecord>()

        override fun isEnabled(): Boolean = true

        override fun startSession(
            pipeline: AiTracePipeline,
            trigger: luna.core.ai.AiTraceTrigger,
            discord: luna.core.ai.AiTraceDiscordContext,
        ) = luna.core.ai.AiTraceSession(
            recorder = this,
            pipeline = pipeline,
            trigger = trigger,
            discord = discord,
            isEnabled = true,
            emitCorrelationLog = false,
        )

        override fun record(trace: AiTraceRecord) {
            records.add(trace)
        }

        override suspend fun flushAndClose(timeoutMs: Long) {}
    }

    private fun createStore(clock: Clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("distill-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    private fun mockMessage(
        id: Long,
        authorId: Long,
        username: String,
        content: String,
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
            every { this@mockk.channelId } returns Snowflake(1000L)
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

    @Test
    fun `distillChannel extracts facts reconciles mutations updates cursor omits unsupported params and emits AI Trace`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")
            val existingToUpdate =
                store.insertMemory(
                    guildId = "2000",
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    content = "Alice lives in Taipei",
                )
            val existingToDelete =
                store.insertMemory(
                    guildId = "2000",
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    content = "Alice owns a Honda Civic",
                )
            clock.advanceMinutes(1)

            val interactionPayloads = mutableListOf<String>()
            var interactionCallCount = 0
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
                            val body = (request.body as io.ktor.http.content.TextContent).text
                            interactionPayloads.add(body)
                            interactionCallCount++
                            val responseText =
                                when (interactionCallCount) {
                                    1 -> {
                                        """
                                        [
                                          {"scope": "user", "target_user_id": "111", "content": "Alice moved to Kaohsiung"},
                                          {"scope": "user", "target_user_id": "111", "content": "Alice sold her Honda Civic"},
                                          {"scope": "guild", "target_user_id": null, "content": "The server hosts weekly Friday movie nights"},
                                          {"scope": "user", "target_user_id": "222", "content": "Bob prefers dark mode"}
                                        ]
                                        """.trimIndent()
                                    }

                                    2 -> {
                                        """
                                        [
                                          {"action": "UPDATE", "memory_id": ${existingToUpdate.id}, "candidate_index": 0, "content": "Alice lives in Kaohsiung"},
                                          {"action": "DELETE", "memory_id": ${existingToDelete.id}, "candidate_index": 1},
                                          {"action": "INSERT", "candidate_index": 2, "content": "The server hosts weekly Friday movie nights"},
                                          {"action": "NOOP", "candidate_index": 3}
                                        ]
                                        """.trimIndent()
                                    }

                                    else -> {
                                        "[]"
                                    }
                                }
                            respond(
                                content = interactionJsonResponse("int-$interactionCallCount", responseText),
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
                                val count = reqJson["requests"]?.let { (it as kotlinx.serialization.json.JsonArray).size } ?: 0
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

            val traceRecorder = CapturingTraceRecorder()
            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = embeddingHttp, apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = interactionHttp, traceRecorder = traceRecorder),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    traceRecorder = traceRecorder,
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ ->
                        listOf(
                            mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "I moved to Kaohsiung and sold my Civic"),
                            mockMessage(id = 106L, authorId = 888L, username = "NewsBot", content = "Friday movie night at 9pm", isBot = true),
                            mockMessage(id = 107L, authorId = 999L, username = "Luna", content = "Nice, enjoy Kaohsiung", isBot = true),
                            mockMessage(id = 108L, authorId = 111L, username = "Alice", content = "!ignoreme"),
                        )
                    },
                    kordSelfIdProvider = { Snowflake(999L) },
                )

            val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")

            assertTrue(
                outcome is DistillOutcome.Completed,
                "Expected Completed but got $outcome; traceError=${traceRecorder.records.firstOrNull()?.errorSummary}",
            )
            assertEquals(3, outcome.mutationsCount)
            assertEquals("108", outcome.newCursorMessageId)

            // Cursor updated to newest fetched message ID
            assertEquals("108", store.getCursor("1000")?.lastSummarizedMessageId)

            // Reconciled memories: UPDATE, DELETE, INSERT applied; NOOP skipped
            val aliceMemories = store.listUserMemories(guildId = "2000", userId = "111")
            assertEquals(1, aliceMemories.size)
            assertEquals("Alice lives in Kaohsiung", aliceMemories.first().content)
            assertTrue(store.listUserMemories(guildId = "2000", userId = "222").isEmpty(), "NOOP candidate should not be inserted")

            val guildHits =
                store.search(
                    guildId = "2000",
                    queryText = "Friday movie nights",
                    scope = SearchScope.GUILD,
                    recencyExcludeMinutes = 0,
                )
            assertEquals(1, guildHits.size)
            assertEquals(MemoryScope.GUILD, guildHits.first().memory.scope)

            // Verify speaker headers preserved & unsupported params omitted
            assertEquals(3, interactionPayloads.size)
            val extractPayload = interactionPayloads[0]
            assertTrue(extractPayload.contains("Alice (uid:111)"), "Expected Alice uid header")
            assertTrue(extractPayload.contains("NewsBot [BOT] (uid:888)"), "Expected NewsBot [BOT] header")
            assertTrue(extractPayload.contains("Luna (uid:999)"), "Expected Luna uid header")
            assertFalse(extractPayload.contains("!ignoreme"), "Command should be filtered from transcript")
            for (payload in interactionPayloads) {
                assertFalse(payload.contains("\"temperature\""), "temperature must be omitted")
                assertFalse(payload.contains("\"topP\""), "topP must be omitted")
                assertFalse(payload.contains("\"top_p\""), "top_p must be omitted")
                assertFalse(payload.contains("\"topK\""), "topK must be omitted")
                assertFalse(payload.contains("\"top_k\""), "top_k must be omitted")
            }

            // Verify AI Trace emitted with pipeline = "chatter_memory_distill"
            assertEquals(1, traceRecorder.records.size)
            val trace = traceRecorder.records.single()
            assertEquals(AiTracePipeline.CHATTER_MEMORY_DISTILL, trace.pipeline)
            assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
            assertEquals(3, trace.attempts.size)
            val jsonLine = AiTraceJson.encodeToString(AiTraceRecord.serializer(), trace)
            assertTrue(jsonLine.contains("\"pipeline\":\"chatter_memory_distill\""), "Expected serialized pipeline name")
        }

    @Test
    fun `transient Gemini failure leaves cursor unchanged and emits FAILED AI Trace`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")

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
                        addHandler {
                            respond(
                                content = """{"error": {"message": "Service unavailable"}}""",
                                status = HttpStatusCode.ServiceUnavailable,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }
            val embeddingHttp =
                HttpClient(MockEngine) {
                    engine { addHandler { error("Should not be called") } }
                }

            val traceRecorder = CapturingTraceRecorder()
            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = embeddingHttp, apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = interactionHttp, traceRecorder = traceRecorder),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    traceRecorder = traceRecorder,
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ ->
                        listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "Hello world"))
                    },
                )

            val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")

            assertTrue(outcome is DistillOutcome.Failed, "Expected Failed outcome on Gemini HTTP error, got $outcome")
            assertEquals("100", store.getCursor("1000")?.lastSummarizedMessageId)
            assertEquals(1, traceRecorder.records.size)
            assertEquals(AiTraceOutcome.FAILED, traceRecorder.records.single().outcome)
        }

    @Test
    fun `transient Embedding API failure leaves cursor unchanged and emits FAILED AI Trace`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")

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
                        addHandler {
                            respond(
                                content =
                                    interactionJsonResponse(
                                        "int-1",
                                        """[{"scope": "user", "target_user_id": "111", "content": "Alice likes coffee"}]""",
                                    ),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }
            val embeddingHttp =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = """{"error": "rate limit"}""",
                                status = HttpStatusCode.TooManyRequests,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }

            val traceRecorder = CapturingTraceRecorder()
            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = embeddingHttp, apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = interactionHttp, traceRecorder = traceRecorder),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    traceRecorder = traceRecorder,
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ ->
                        listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "I love coffee"))
                    },
                )

            val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")

            assertTrue(outcome is DistillOutcome.Failed, "Expected Failed outcome on Gemini HTTP error, got $outcome")
            assertEquals("100", store.getCursor("1000")?.lastSummarizedMessageId)
            assertTrue(store.listUserMemories(guildId = "2000", userId = "111").isEmpty(), "No memories should be persisted on failure")
            assertEquals(1, traceRecorder.records.size)
            assertEquals(AiTraceOutcome.FAILED, traceRecorder.records.single().outcome)
        }

    @Test
    fun `distillChannel reconciles guild-scoped facts across channels in same guild while staying guild-isolated`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor(channelId = "1002", guildId = "2000", lastSummarizedMessageId = "100")
            val guild1Mem =
                store.insertMemory(
                    guildId = "2000",
                    scope = MemoryScope.GUILD,
                    targetUserId = null,
                    content = "The server hosts weekly Friday movie nights",
                )
            store.insertMemory(
                guildId = "3000",
                scope = MemoryScope.GUILD,
                targetUserId = null,
                content = "Other guild hosts weekly Friday movie nights",
            )
            clock.advanceMinutes(1)

            val reconciliationPrompts = mutableListOf<String>()
            var interactionCallCount = 0
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
                            val body = (request.body as io.ktor.http.content.TextContent).text
                            interactionCallCount++
                            val responseText =
                                when (interactionCallCount) {
                                    1 -> {
                                        """[{"scope": "guild", "target_user_id": null, "content": "The server moved weekly movie nights to Saturday"}]"""
                                    }

                                    2 -> {
                                        reconciliationPrompts.add(body)
                                        """[{"action": "UPDATE", "memory_id": ${guild1Mem.id}, "candidate_index": 0, "content": "The server hosts weekly Saturday movie nights"}]"""
                                    }

                                    else -> {
                                        "[]"
                                    }
                                }
                            respond(
                                content = interactionJsonResponse("int-$interactionCallCount", responseText),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }
            val embeddingHttp =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = """{"embeddings": [{"values": ${unitEmbedding(1.0f)}}]}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
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
                        listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "Our server movie nights moved to Saturday"))
                    },
                )

            val outcome = distiller.distillChannel(channelId = "1002", guildId = "2000", cursorMessageId = "100")
            assertTrue(outcome is DistillOutcome.Completed, "Expected Completed, got $outcome")
            assertEquals(1, reconciliationPrompts.size)
            assertTrue(
                reconciliationPrompts.single().contains("id=${guild1Mem.id}, scope=guild"),
                "Expected guild-1 memory in reconciliation prompt: ${reconciliationPrompts.single()}",
            )
            assertFalse(
                reconciliationPrompts.single().contains("Other guild"),
                "Other guild memory must never leak into reconciliation prompt",
            )

            val guild1Hits = store.search(guildId = "2000", queryText = "Saturday movie nights", scope = SearchScope.GUILD, recencyExcludeMinutes = 0)
            assertEquals(1, guild1Hits.size)
            assertEquals("The server hosts weekly Saturday movie nights", guild1Hits.first().memory.content)

            val guild2Hits = store.search(guildId = "3000", queryText = "Friday movie nights", scope = SearchScope.GUILD, recencyExcludeMinutes = 0)
            assertEquals(1, guild2Hits.size)
        }
}
