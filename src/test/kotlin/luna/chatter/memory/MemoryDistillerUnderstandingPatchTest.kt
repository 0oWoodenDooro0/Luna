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
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRecorder
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryDistillerUnderstandingPatchTest {
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

    private fun createStore(clock: Clock): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("distill-3stage-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    private fun mockMessage(
        id: Long,
        authorId: Long,
        username: String,
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
        return """{"id": "$id", "status": "completed", "output_text": $escaped}"""
    }

    private fun createMockEmbeddingHttp(capturedTexts: MutableList<String>? = null): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    val bodyText = (request.body as io.ktor.http.content.TextContent).text
                    capturedTexts?.add(bodyText)
                    if (url.contains(":batchEmbedContents")) {
                        val reqJson = Json.parseToJsonElement(bodyText).jsonObject
                        val count = (reqJson["requests"] as? JsonArray)?.size ?: 0
                        val embeddingsJson =
                            (0 until count).joinToString(",") { idx ->
                                """{"values": ${List(768) { if (it == 0) (idx + 1).toFloat() else 0.0f }}}"""
                            }
                        respond(
                            content = """{"embeddings": [$embeddingsJson]}""",
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    } else {
                        respond(
                            content = """{"embedding": {"values": ${List(768) { if (it == 0) 1.0f else 0.0f }}}}""",
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    }
                }
            }
        }

    @Test
    fun `stage 3 UnderstandingPatch passes u1 u2 aliases and applies ADD UPDATE REAFFIRM DELETE`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")
            val uReaffirm =
                store.insertUnderstanding(
                    guildId = "2000",
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    subject = "humor",
                    content = "Enjoys dry sarcasm",
                )
            clock.advanceMinutes(1)
            val uUpdate =
                store.insertUnderstanding(
                    guildId = "2000",
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    subject = "tone",
                    content = "Usually formal",
                )
            val uDelete =
                store.insertUnderstanding(
                    guildId = "2000",
                    scope = MemoryScope.GUILD,
                    targetUserId = null,
                    subject = "outdated_meme",
                    content = "Old inside joke",
                )
            clock.advanceMinutes(10)

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
                            val body = (request.body as io.ktor.http.content.TextContent).text
                            interactionPayloads.add(body)
                            callCount++
                            val responseText =
                                when (callCount) {
                                    1 -> {
                                        """[{"scope": "user", "target_user_id": "111", "content": "Alice likes espresso", "importance": 4, "keywords": "espresso coffee"}]"""
                                    }

                                    2 -> {
                                        """[{"action": "INSERT", "candidate_index": 0}]"""
                                    }

                                    else -> {
                                        """
                                        [
                                          {"action": "UPDATE", "id": "u1", "subject": "tone", "content": "Casual and playful"},
                                          {"action": "REAFFIRM", "id": "[u2]"},
                                          {"action": "DELETE", "id": "u3"},
                                          {"action": "ADD", "scope": "guild", "target_user_id": null, "subject": "banter", "content": "Loves late-night coffee banter"}
                                        ]
                                        """.trimIndent()
                                    }
                                }
                            respond(
                                content = interactionJsonResponse("int-$callCount", responseText),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }

            val embeddedPayloads = mutableListOf<String>()
            val traceRecorder = CapturingTraceRecorder()
            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(embeddedPayloads), apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = interactionHttp, traceRecorder = traceRecorder),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    traceRecorder = traceRecorder,
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ ->
                        listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "Espresso time, no formal talk!"))
                    },
                    kordSelfIdProvider = { Snowflake(999L) },
                )

            val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")
            assertTrue(outcome is DistillOutcome.Completed, "Expected Completed outcome, got $outcome")
            assertEquals(1, outcome.mutationsCount)
            assertEquals("105", store.getCursor("1000")?.lastSummarizedMessageId)

            assertTrue(
                embeddedPayloads.any { it.contains("espresso coffee") },
                "Expected candidate embedding payload to include keywords",
            )

            assertEquals(3, interactionPayloads.size)
            val stage3Prompt = interactionPayloads[2]
            assertTrue(stage3Prompt.contains("[u1]"), "Expected [u1] alias in Stage 3 prompt")
            assertTrue(stage3Prompt.contains("[u2]"), "Expected [u2] alias in Stage 3 prompt")
            assertTrue(stage3Prompt.contains("[u3]"), "Expected [u3] alias in Stage 3 prompt")

            val userUnderstandings = store.listUserUnderstandings("2000", "111").associateBy { it.id }
            assertEquals("Casual and playful", userUnderstandings[uUpdate.id]?.content)
            assertEquals("Enjoys dry sarcasm", userUnderstandings[uReaffirm.id]?.content)
            assertEquals(clock.instant().toEpochMilli(), userUnderstandings[uReaffirm.id]?.updatedAt)

            val guildUnderstandings = store.listGuildUnderstandings("2000")
            assertTrue(guildUnderstandings.none { it.id == uDelete.id }, "Expected deleted guild understanding to be removed")
            assertEquals(1, guildUnderstandings.size)
            assertEquals("banter", guildUnderstandings.single().subject)

            val trace = traceRecorder.records.single()
            assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
            assertEquals(
                listOf(
                    AiAttemptStage.MEMORY_DISTILL_EXTRACT,
                    AiAttemptStage.MEMORY_DISTILL_RECONCILE,
                    AiAttemptStage.MEMORY_DISTILL_UNDERSTANDING,
                ),
                trace.attempts.map { it.stage },
            )
        }

    @Test
    fun `stage 3 failure fails open with warning log and DEGRADED_SUCCESS trace without rolling back facts or cursor`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")

            val stageLogger = LoggerFactory.getLogger(UnderstandingPatchPrompt::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>().apply { start() }
            stageLogger.addAppender(listAppender)
            try {
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
                            addHandler {
                                callCount++
                                when (callCount) {
                                    1 -> {
                                        respond(
                                            content =
                                                interactionJsonResponse(
                                                    "int-1",
                                                    """[{"scope": "user", "target_user_id": "111", "content": "Alice likes matcha", "importance": 3, "keywords": "matcha tea"}]""",
                                                ),
                                            status = HttpStatusCode.OK,
                                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                        )
                                    }

                                    2 -> {
                                        respond(
                                            content = interactionJsonResponse("int-2", """[{"action": "INSERT", "candidate_index": 0}]"""),
                                            status = HttpStatusCode.OK,
                                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                        )
                                    }

                                    else -> {
                                        respond(
                                            content = """{"error": {"message": "Stage 3 transient failure"}}""",
                                            status = HttpStatusCode.ServiceUnavailable,
                                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                        )
                                    }
                                }
                            }
                        }
                    }

                val traceRecorder = CapturingTraceRecorder()
                val distiller =
                    MemoryDistiller(
                        memoryStore = store,
                        embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                        interactionClient = GeminiInteractionClient(httpClient = interactionHttp, traceRecorder = traceRecorder),
                        noiseFilter = ChatterNoiseFilter(),
                        config = ChatterMemoryConfig(),
                        apiKey = "test-key",
                        baseUrl = "https://generativelanguage.googleapis.com",
                        traceRecorder = traceRecorder,
                        clock = clock,
                        messagesAfterFetcher = { _, _, _ ->
                            listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "I love matcha"))
                        },
                        kordSelfIdProvider = { Snowflake(999L) },
                    )

                val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")
                assertTrue(outcome is DistillOutcome.Completed, "Stage 3 failure must fail open as Completed, got $outcome")
                assertEquals(1, outcome.mutationsCount)
                assertEquals("105", store.getCursor("1000")?.lastSummarizedMessageId)
                assertEquals(1, store.listUserMemories("2000", "111").size)

                val warnLogs = listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnLogs.any { it.contains("UnderstandingPatch", ignoreCase = true) },
                    "Expected observable WARN log for Stage 3 failure, got: $warnLogs",
                )

                val trace = traceRecorder.records.single()
                assertEquals(AiTraceOutcome.DEGRADED_SUCCESS, trace.outcome)
                assertEquals(AiAttemptStage.MEMORY_DISTILL_UNDERSTANDING, trace.attempts.last().stage)
            } finally {
                stageLogger.detachAppender(listAppender)
            }
        }

    @Test
    fun `stage 3 runs on casual banter with no extracted atomic facts and supports empty noop`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")

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
                        addHandler {
                            callCount++
                            respond(
                                content = interactionJsonResponse("int-$callCount", "[]"),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }

            val traceRecorder = CapturingTraceRecorder()
            val distiller =
                MemoryDistiller(
                    memoryStore = store,
                    embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                    interactionClient = GeminiInteractionClient(httpClient = interactionHttp, traceRecorder = traceRecorder),
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    apiKey = "test-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                    traceRecorder = traceRecorder,
                    clock = clock,
                    messagesAfterFetcher = { _, _, _ ->
                        listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "haha lol"))
                    },
                    kordSelfIdProvider = { Snowflake(999L) },
                )

            val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")
            assertTrue(outcome is DistillOutcome.Completed, "Expected Completed outcome on empty noop, got $outcome")
            assertEquals(0, outcome.mutationsCount)
            assertEquals(2, callCount)
            assertEquals("105", store.getCursor("1000")?.lastSummarizedMessageId)
            assertTrue(store.listUserUnderstandings("2000", "111").isEmpty(), "Expected no understandings on empty noop")
            assertEquals(
                listOf(AiAttemptStage.MEMORY_DISTILL_EXTRACT, AiAttemptStage.MEMORY_DISTILL_UNDERSTANDING),
                traceRecorder.records
                    .single()
                    .attempts
                    .map { it.stage },
            )
        }
}
