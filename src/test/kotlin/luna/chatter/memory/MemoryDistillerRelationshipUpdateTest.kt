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
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryDistillerRelationshipUpdateTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId) = this

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
            trigger: AiTraceTrigger,
            discord: AiTraceDiscordContext,
        ) = AiTraceSession(
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
        val dbPath = tempDir.resolve("distill-rel-${System.nanoTime()}.db").toString()
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
        val escaped = Json.encodeToString(JsonPrimitive(text))
        return """{"id": "$id", "status": "completed", "output_text": $escaped}"""
    }

    private fun createMockEmbeddingHttp(): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    val bodyText = (request.body as io.ktor.http.content.TextContent).text
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
    fun `stage 3 loads existing UserRelationship into prompt and applies clamped relationshipUpdates`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")
            store.applyRelationshipUpdate(
                guildId = "2000",
                userId = "111",
                affinityDelta = 15,
                toneStyle = "friendly banter",
            )
            clock.advanceMinutes(30)

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
                                        "[]"
                                    }

                                    else -> {
                                        """
                                        {
                                          "understandingPatches": [
                                            {"action": "ADD", "scope": "user", "target_user_id": "111", "subject": "warmth", "content": "Very supportive"}
                                          ],
                                          "relationshipUpdates": [
                                            {"targetUserId": "111", "affinityDelta": 25, "toneStyle": "warm and enthusiastic"},
                                            {"targetUserId": "999999", "affinityDelta": -10, "toneStyle": "should be ignored"},
                                            {"targetUserId": "   ", "affinityDelta": 5, "toneStyle": "should be ignored"}
                                          ]
                                        }
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
                        listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "Thanks for always helping out!"))
                    },
                    kordSelfIdProvider = { Snowflake(999L) },
                )

            val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")
            assertTrue(outcome is DistillOutcome.Completed, "Expected Completed outcome, got $outcome")
            assertEquals("105", store.getCursor("1000")?.lastSummarizedMessageId)

            val stage3Prompt = interactionPayloads[1]
            assertTrue(stage3Prompt.contains("WARM"), "Expected WARM tier in Stage 3 prompt")
            assertTrue(stage3Prompt.contains("15"), "Expected score 15 in Stage 3 prompt")
            assertTrue(stage3Prompt.contains("friendly banter"), "Expected existing toneStyle in Stage 3 prompt")

            val updatedRel = store.getUserRelationship("2000", "111")
            // 15 + clamp(25 -> 15) = 30
            assertEquals(30, updatedRel.affinityScore)
            assertEquals("warm and enthusiastic", updatedRel.toneStyle)
            assertEquals(clock.instant().toEpochMilli(), updatedRel.lastInteractionAt)

            // Unknown userId 999999 was ignored
            assertEquals(0, store.getUserRelationship("2000", "999999").affinityScore)
            assertEquals(1, store.listUserUnderstandings("2000", "111").size)
            assertEquals(AiTraceOutcome.SUCCESS, traceRecorder.records.single().outcome)
        }

    @Test
    fun `stage 3 relationship store error fails open with warning log and DEGRADED_SUCCESS without rolling back facts or understandings`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val backingStore = createStore(clock)
            backingStore.upsertCursor(channelId = "1000", guildId = "2000", lastSummarizedMessageId = "100")

            val failingRelStore =
                object : ChatterMemoryStore by backingStore {
                    override fun listUserRelationships(
                        guildId: String,
                        userIds: Collection<String>,
                        now: Long?,
                    ): Map<String, UserRelationship> = throw IllegalStateException("SQLite relationship read boom")

                    override fun applyRelationshipUpdate(
                        guildId: String,
                        userId: String,
                        affinityDelta: Int,
                        toneStyle: String?,
                        now: Long?,
                    ): UserRelationship = throw IllegalStateException("SQLite relationship write boom")
                }

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
                                val responseText =
                                    when (callCount) {
                                        1 -> {
                                            """[{"scope": "user", "target_user_id": "111", "content": "Alice drinks oolong", "importance": 3, "keywords": "oolong"}]"""
                                        }

                                        2 -> {
                                            """[{"action": "INSERT", "candidate_index": 0}]"""
                                        }

                                        else -> {
                                            """
                                            {
                                              "understandingPatches": [
                                                {"action": "ADD", "scope": "user", "target_user_id": "111", "subject": "tea", "content": "Loves tea chats"}
                                              ],
                                              "relationshipUpdates": [
                                                {"targetUserId": "111", "affinityDelta": 5, "toneStyle": "warm"}
                                              ]
                                            }
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

                val traceRecorder = CapturingTraceRecorder()
                val distiller =
                    MemoryDistiller(
                        memoryStore = failingRelStore,
                        embeddingClient = GeminiEmbeddingClient(httpClient = createMockEmbeddingHttp(), apiKey = "test-key"),
                        interactionClient = GeminiInteractionClient(httpClient = interactionHttp, traceRecorder = traceRecorder),
                        noiseFilter = ChatterNoiseFilter(),
                        config = ChatterMemoryConfig(),
                        apiKey = "test-key",
                        baseUrl = "https://generativelanguage.googleapis.com",
                        traceRecorder = traceRecorder,
                        clock = clock,
                        messagesAfterFetcher = { _, _, _ ->
                            listOf(mockMessage(id = 105L, authorId = 111L, username = "Alice", content = "Oolong tea is great"))
                        },
                        kordSelfIdProvider = { Snowflake(999L) },
                    )

                val outcome = distiller.distillChannel(channelId = "1000", guildId = "2000", cursorMessageId = "100")
                assertTrue(outcome is DistillOutcome.Completed, "Expected Completed outcome on relationship error, got $outcome")
                assertEquals(1, outcome.mutationsCount)
                assertEquals("105", backingStore.getCursor("1000")?.lastSummarizedMessageId)
                assertEquals(1, backingStore.listUserMemories("2000", "111").size)
                assertEquals(1, backingStore.listUserUnderstandings("2000", "111").size)

                val warnLogs = listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnLogs.any { it.contains("UserRelationship", ignoreCase = true) },
                    "Expected observable WARN log for UserRelationship error, got: $warnLogs",
                )
                assertEquals(AiTraceOutcome.DEGRADED_SUCCESS, traceRecorder.records.single().outcome)
            } finally {
                stageLogger.detachAppender(listAppender)
            }
        }
}
