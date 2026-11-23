package luna.chatter.memory

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.ChatterConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterInput
import luna.core.LunaHost
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceJson
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTraceRecord
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatterMemoryToolIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    private fun createMockHttpClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    private fun unitVector768(): List<Float> =
        List(GeminiEmbeddingClient.DEFAULT_OUTPUT_DIMENSIONALITY) { idx ->
            if (idx == 0) 1.0f else 0.0f
        }

    private fun mockEmbeddingResponseJson(): String {
        val values = unitVector768().joinToString(",")
        return """{"embedding":{"values":[$values]}}"""
    }

    private fun createStoreWithOldMemory(): SqliteChatterMemoryStore {
        val mutableClock =
            object : Clock() {
                var now: Instant = Instant.parse("2026-01-01T00:00:00Z")

                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId) = this

                override fun instant(): Instant = now
            }
        val dbPath = tempDir.resolve("integration-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        val store = SqliteChatterMemoryStore(database = db, clock = mutableClock)
        store.insertMemory(
            guildId = "1001",
            scope = MemoryScope.USER,
            targetUserId = "999001",
            content = "Alice prefers oat milk flat whites",
        )
        mutableClock.now = Instant.parse("2026-01-01T01:00:00Z")
        return store
    }

    @Test
    fun `casual reply incurs zero embedding queries while declaring search_long_term_memory alongside built-in tools`() =
        runBlocking {
            val embedCalls = AtomicInteger(0)
            val embeddingClient =
                GeminiEmbeddingClient(
                    httpClient =
                        createMockHttpClient(
                            MockEngine { _ ->
                                embedCalls.incrementAndGet()
                                respond(
                                    content = mockEmbeddingResponseJson(),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                        ),
                    apiKey = "test-api-key",
                )
            val store = createStoreWithOldMemory()
            val memoryConfig = ChatterMemoryConfig(enabled = true)
            val toolHandler = ChatterMemoryToolHandler(embeddingClient, store, memoryConfig)

            var capturedToolsJson = ""
            val interactionEngine =
                MockEngine { request ->
                    capturedToolsJson = (request.body as TextContent).text
                    respond(
                        content =
                            """
                            {
                              "id": "inter-casual-1",
                              "status": "completed",
                              "steps": [{"type": "model_output", "content": [{"type": "text", "text": "早安呀 今天天氣不錯"}]}]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config =
                        ChatterConfig(
                            apiKey = "test-api-key",
                            searchGroundingEnabled = true,
                            urlContextEnabled = true,
                        ),
                    httpClient = createMockHttpClient(interactionEngine),
                    memoryConfig = memoryConfig,
                    memoryToolHandler = toolHandler,
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "Alice (uid:999001): 早安",
                        webUrls = listOf("https://example.com/news"),
                        guildId = "1001",
                        channelId = "2001",
                        authorId = "999001",
                    ),
                )

            assertEquals("早安呀 今天天氣不錯", reply)
            assertEquals(0, embedCalls.get(), "Casual reply must incur zero Embedding API calls")

            val reqObj = json.parseToJsonElement(capturedToolsJson).jsonObject
            val toolNamesOrTypes =
                reqObj["tools"]
                    ?.jsonArray
                    ?.mapNotNull { elem ->
                        val obj = elem.jsonObject
                        obj["name"]?.jsonPrimitive?.content ?: obj["type"]?.jsonPrimitive?.content
                    }.orEmpty()
            assertTrue(toolNamesOrTypes.contains("url_context"), "Expected url_context tool in $toolNamesOrTypes")
            assertTrue(toolNamesOrTypes.contains("google_search"), "Expected google_search tool in $toolNamesOrTypes")
            assertTrue(toolNamesOrTypes.contains("search_long_term_memory"), "Expected search_long_term_memory tool in $toolNamesOrTypes")
        }

    @Test
    fun `executes multi-turn previous_interaction_id and function_result continuation with AI Trace capture`() =
        runBlocking {
            val embedCalls = AtomicInteger(0)
            val embeddingClient =
                GeminiEmbeddingClient(
                    httpClient =
                        createMockHttpClient(
                            MockEngine { _ ->
                                embedCalls.incrementAndGet()
                                respond(
                                    content = mockEmbeddingResponseJson(),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                        ),
                    apiKey = "test-api-key",
                )
            val store = createStoreWithOldMemory()
            val memoryConfig = ChatterMemoryConfig(enabled = true)
            val toolHandler = ChatterMemoryToolHandler(embeddingClient, store, memoryConfig)

            val traceDir = tempDir.resolve("ai-traces")
            val traceRecorder = JsonlAiTraceRecorder(traceDir = traceDir)

            val turnRequests = mutableListOf<String>()
            val turnCounter = AtomicInteger(0)
            val interactionEngine =
                MockEngine { request ->
                    val bodyText = (request.body as TextContent).text
                    turnRequests.add(bodyText)
                    val turn = turnCounter.incrementAndGet()
                    if (turn == 1) {
                        respond(
                            content =
                                """
                                {
                                  "id": "inter-mem-1",
                                  "status": "requires_action",
                                  "steps": [
                                    {
                                      "type": "function_call",
                                      "id": "call-1",
                                      "name": "search_long_term_memory",
                                      "arguments": {"query": "flat white oat milk", "scope": "both"}
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content =
                                """
                                {
                                  "id": "inter-mem-2",
                                  "status": "completed",
                                  "steps": [{"type": "model_output", "content": [{"type": "text", "text": "記得你都喝燕麥奶小白咖啡對吧"}]}]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "test-api-key"),
                    httpClient = createMockHttpClient(interactionEngine),
                    traceRecorder = traceRecorder,
                    memoryConfig = memoryConfig,
                    memoryToolHandler = toolHandler,
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "Alice (uid:999001): 我平常都點哪種咖啡來著",
                        guildId = "1001",
                        channelId = "2001",
                        authorId = "999001",
                    ),
                )

            assertEquals("記得你都喝燕麥奶小白咖啡對吧", reply)
            assertEquals(1, embedCalls.get())
            assertEquals(2, turnRequests.size)

            val secondReq = json.parseToJsonElement(turnRequests[1]).jsonObject
            assertEquals("inter-mem-1", secondReq["previous_interaction_id"]?.jsonPrimitive?.content)
            val fnResultInput = secondReq["input"]?.jsonArray?.firstOrNull()?.jsonObject
            assertNotNull(fnResultInput)
            assertEquals("function_result", fnResultInput["type"]?.jsonPrimitive?.content)
            assertEquals("search_long_term_memory", fnResultInput["name"]?.jsonPrimitive?.content)
            assertEquals("call-1", fnResultInput["call_id"]?.jsonPrimitive?.content)
            assertTrue(
                fnResultInput["result"]?.jsonPrimitive?.content?.contains("oat milk flat whites") == true,
                "Expected stored memory content in function_result: $fnResultInput",
            )

            traceRecorder.close()

            val traceFiles = Files.list(traceDir).use { stream -> stream.toList() }
            assertEquals(1, traceFiles.size)
            val traceLines = Files.readAllLines(traceFiles.single())
            assertEquals(1, traceLines.size)
            val traceRecord = AiTraceJson.decodeFromString(AiTraceRecord.serializer(), traceLines.single())
            assertEquals(AiTraceOutcome.SUCCESS, traceRecord.outcome)
            assertEquals(2, traceRecord.attempts.size)
            assertEquals(AiAttemptStage.CHATTER_GENERATE, traceRecord.attempts[0].stage)
            assertEquals(AiAttemptOutcome.TOOL_CALL, traceRecord.attempts[0].parsedOutcome)
            assertTrue(
                traceRecord.attempts[0]
                    .request
                    ?.enabledTools
                    ?.contains("search_long_term_memory") == true,
                "Expected search_long_term_memory in enabledTools: ${traceRecord.attempts[0].request?.enabledTools}",
            )
            assertEquals(AiAttemptStage.CHATTER_TOOL_CONTINUATION, traceRecord.attempts[1].stage)
            assertEquals(AiAttemptOutcome.SUCCESS, traceRecord.attempts[1].parsedOutcome)
            assertTrue(
                traceRecord.attempts[1]
                    .request
                    ?.payload
                    ?.toString()
                    ?.contains("function_result") == true,
                "Expected function_result payload in continuation attempt: ${traceRecord.attempts[1].request?.payload}",
            )
        }

    @Test
    fun `6th tool call skips DB and Embedding API returning limit_reached within maxTurns 8`() =
        runBlocking {
            val embedCalls = AtomicInteger(0)
            val embeddingClient =
                GeminiEmbeddingClient(
                    httpClient =
                        createMockHttpClient(
                            MockEngine { _ ->
                                embedCalls.incrementAndGet()
                                respond(
                                    content = mockEmbeddingResponseJson(),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                        ),
                    apiKey = "test-api-key",
                )
            val store = createStoreWithOldMemory()
            val memoryConfig = ChatterMemoryConfig(enabled = true, maxToolCalls = 5, maxTurns = 8)
            val toolHandler = ChatterMemoryToolHandler(embeddingClient, store, memoryConfig)

            val turnRequests = mutableListOf<String>()
            val turnCounter = AtomicInteger(0)
            val interactionEngine =
                MockEngine { request ->
                    turnRequests.add((request.body as TextContent).text)
                    val turn = turnCounter.incrementAndGet()
                    if (turn <= 6) {
                        respond(
                            content =
                                """
                                {
                                  "id": "inter-cap-$turn",
                                  "status": "requires_action",
                                  "steps": [
                                    {
                                      "type": "function_call",
                                      "id": "call-$turn",
                                      "name": "search_long_term_memory",
                                      "arguments": {"query": "coffee attempt $turn"}
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content =
                                """
                                {
                                  "id": "inter-cap-$turn",
                                  "status": "completed",
                                  "steps": [{"type": "model_output", "content": [{"type": "text", "text": "好啦我直接回你"}]}]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "test-api-key"),
                    httpClient = createMockHttpClient(interactionEngine),
                    memoryConfig = memoryConfig,
                    memoryToolHandler = toolHandler,
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "Alice (uid:999001): 幫我查一下",
                        guildId = "1001",
                        channelId = "2001",
                        authorId = "999001",
                    ),
                )

            assertEquals("好啦我直接回你", reply)
            assertEquals(5, embedCalls.get(), "6th+ call must skip Embedding API and DB")
            assertEquals(7, turnRequests.size)
            val seventhReq = json.parseToJsonElement(turnRequests[6]).jsonObject
            val sixthResult =
                seventhReq["input"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("result")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertTrue(sixthResult.contains("limit_reached"), "Expected limit_reached in 6th tool result: $sixthResult")
        }

    @Test
    fun `slow or failing memory tool triggers fail-open unavailable notice and still produces reply`() =
        runBlocking {
            val embeddingClient =
                GeminiEmbeddingClient(
                    httpClient =
                        createMockHttpClient(
                            MockEngine { _ ->
                                delay(300L)
                                respond(
                                    content = mockEmbeddingResponseJson(),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                        ),
                    apiKey = "test-api-key",
                )
            val store = createStoreWithOldMemory()
            val memoryConfig = ChatterMemoryConfig(enabled = true, toolTimeoutMs = 50L)
            val toolHandler = ChatterMemoryToolHandler(embeddingClient, store, memoryConfig)

            val turnRequests = mutableListOf<String>()
            val turnCounter = AtomicInteger(0)
            val interactionEngine =
                MockEngine { request ->
                    turnRequests.add((request.body as TextContent).text)
                    val turn = turnCounter.incrementAndGet()
                    if (turn == 1) {
                        respond(
                            content =
                                """
                                {
                                  "id": "inter-timeout-1",
                                  "status": "requires_action",
                                  "steps": [
                                    {
                                      "type": "function_call",
                                      "id": "call-timeout-1",
                                      "name": "search_long_term_memory",
                                      "arguments": {"query": "coffee"}
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content =
                                """
                                {
                                  "id": "inter-timeout-2",
                                  "status": "completed",
                                  "steps": [{"type": "model_output", "content": [{"type": "text", "text": "雖然想不太起來但還是聊聊吧"}]}]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "test-api-key"),
                    httpClient = createMockHttpClient(interactionEngine),
                    memoryConfig = memoryConfig,
                    memoryToolHandler = toolHandler,
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "Alice (uid:999001): 我之前說過什麼嗎",
                        guildId = "1001",
                        channelId = "2001",
                        authorId = "999001",
                    ),
                )

            assertEquals("雖然想不太起來但還是聊聊吧", reply)
            assertEquals(2, turnRequests.size)
            val secondReq = json.parseToJsonElement(turnRequests[1]).jsonObject
            val resultPayload =
                secondReq["input"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("result")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertTrue(resultPayload.contains("unavailable"), "Expected unavailable in timeout tool result: $resultPayload")
        }
}
