package luna.core.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeminiInteractionWithToolsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    private fun createClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    private fun testTool(name: String = "lookup_context"): GeminiInteractionTool =
        GeminiInteractionTool.function(
            name = name,
            description = "Test tool",
            parameters = buildJsonObject { put("type", "object") },
        )

    private fun baseRequest(tools: List<GeminiInteractionTool> = listOf(testTool())): GeminiInteractionRequest =
        GeminiInteractionRequest(
            model = "gemini-3.5-flash-lite",
            systemInstruction = "System rules",
            input = listOf(GeminiInteractionInput.text("Initial user prompt")),
            tools = tools,
            generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = 512),
            background = false,
        )

    @Test
    fun `interactWithTools chains continuations re-supplying config and recording AI Trace attempts`() =
        runTest {
            val tempDir = Files.createTempDirectory("ai-trace-tools-test")
            val fixedClock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC)
            val recorder =
                JsonlAiTraceRecorder(
                    traceDir = tempDir,
                    clock = fixedClock,
                )

            val recordedBodies = mutableListOf<JsonObject>()
            var callIndex = 0
            val engine =
                MockEngine { request ->
                    recordedBodies.add(json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
                    callIndex++
                    val body =
                        when (callIndex) {
                            1 -> {
                                """
                                {
                                  "id": "int-1",
                                  "status": "completed",
                                  "steps": [
                                    {"type": "function_call", "call_id": "c-1", "name": "lookup_context", "arguments": {"q": "first"}},
                                    {"type": "function_call", "call_id": "c-2", "name": "lookup_context", "arguments": {"q": "second"}}
                                  ]
                                }
                                """.trimIndent()
                            }

                            else -> {
                                """{"id": "int-2", "status": "completed", "output_text": "Final answer"}"""
                            }
                        }
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                GeminiInteractionClient(
                    httpClient = createClient(engine),
                    pollingDelayMs = 1L,
                    traceRecorder = recorder,
                )

            val seenQueries = mutableListOf<String>()
            val result =
                client.interactWithTools(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request = baseRequest(),
                    traceContext =
                        InteractionTraceContext(
                            stage = AiAttemptStage.CHATTER_GENERATE,
                            pipeline = AiTracePipeline.CHATTER,
                            trigger = AiTraceTrigger.MENTION,
                        ),
                    maxTurns = 4,
                    continuationStage = AiAttemptStage.CHATTER_TOOL_CONTINUATION,
                ) { call ->
                    val q = call.arguments["q"]?.jsonPrimitive?.content ?: ""
                    seenQueries.add(q)
                    ToolExecutionOutcome.Result(buildJsonObject { put("answer", "for-$q") })
                }

            assertIs<InteractionExecutionResult.Success>(result)
            assertEquals("Final answer", result.text)
            assertEquals(listOf("first", "second"), seenQueries)
            assertEquals(2, recordedBodies.size)

            val contBody = recordedBodies[1]
            assertEquals("int-1", contBody["previous_interaction_id"]?.jsonPrimitive?.content)
            assertEquals("System rules", contBody["system_instruction"]?.jsonPrimitive?.content)
            assertEquals(false, contBody["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            assertEquals(
                512,
                contBody["generation_config"]
                    ?.jsonObject
                    ?.get("max_output_tokens")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )
            assertNotNull(contBody["tools"], "Continuation request must re-supply tools")

            val contInputs = contBody["input"]?.jsonArray ?: JsonArray(emptyList())
            assertEquals(2, contInputs.size)
            assertEquals("c-1", contInputs[0].jsonObject["call_id"]?.jsonPrimitive?.content)
            assertEquals("c-2", contInputs[1].jsonObject["call_id"]?.jsonPrimitive?.content)

            recorder.close()
            val traceFiles = Files.list(tempDir).use { it.toList() }
            assertEquals(1, traceFiles.size, "Expected one trace JSONL file")
            val lines = Files.readAllLines(traceFiles.first())
            assertEquals(1, lines.size, "Expected a single trace session for the multi-turn loop")
            val sessionJson = json.parseToJsonElement(lines.first()).jsonObject
            val attempts = sessionJson["attempts"]?.jsonArray ?: JsonArray(emptyList())
            assertEquals(2, attempts.size)
            assertEquals("CHATTER_GENERATE", attempts[0].jsonObject["stage"]?.jsonPrimitive?.content)
            assertEquals("CHATTER_TOOL_CONTINUATION", attempts[1].jsonObject["stage"]?.jsonPrimitive?.content)
            val fallbackPath = sessionJson["fallbackPath"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertEquals(listOf("CHATTER_GENERATE", "CHATTER_TOOL_CONTINUATION:lookup_context"), fallbackPath)
        }

    @Test
    fun `interactWithTools enforces maxTurns and logs warning as PARSE_ERROR`() =
        runTest {
            val clientLogger = LoggerFactory.getLogger(GeminiInteractionToolLoop::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            clientLogger.addAppender(appender)

            try {
                var callCount = 0
                val engine =
                    MockEngine {
                        callCount++
                        respond(
                            content =
                                """
                                {
                                  "id": "int-$callCount",
                                  "status": "completed",
                                  "steps": [
                                    {"type": "function_call", "call_id": "c-$callCount", "name": "lookup_context", "arguments": {}}
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)
                val result =
                    client.interactWithTools(
                        baseUrl = "https://generativelanguage.googleapis.com",
                        apiKey = "test-key",
                        request = baseRequest(),
                        maxTurns = 2,
                    ) {
                        ToolExecutionOutcome.Result(buildJsonObject { put("ok", true) })
                    }

                assertEquals(2, callCount)
                assertIs<InteractionExecutionResult.Error.Generic>(result)
                assertEquals(AiTraceFailureCategory.PARSE_ERROR, result.failureCategory)
                assertTrue(result.message.contains("maxTurns (2)"), "Expected maxTurns in error message: ${result.message}")
                assertTrue(
                    appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("maxTurns (2)") },
                    "Expected WARN log when maxTurns is exceeded",
                )
            } finally {
                clientLogger.detachAppender(appender)
            }
        }

    @Test
    fun `interactWithTools rejects malformed function calls and undeclared tools as PARSE_ERROR`() =
        runTest {
            val invalidPayloads =
                listOf(
                    """{"id": "", "status": "completed", "steps": [{"type": "function_call", "call_id": "c-1", "name": "lookup_context", "arguments": {}}]}""",
                    """{"id": "int-1", "status": "completed", "steps": [{"type": "function_call", "call_id": "", "name": "lookup_context", "arguments": {}}]}""",
                    """{"id": "int-1", "status": "completed", "steps": [{"type": "function_call", "call_id": "c-1", "name": "", "arguments": {}}]}""",
                    """{"id": "int-1", "status": "requires_action", "steps": []}""",
                    """{"id": "int-1", "status": "completed", "steps": [{"type": "function_call", "call_id": "c-1", "name": "undeclared_tool", "arguments": {}}]}""",
                )

            for (payload in invalidPayloads) {
                val engine =
                    MockEngine {
                        respond(
                            content = payload,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)
                val result =
                    client.interactWithTools(
                        baseUrl = "https://generativelanguage.googleapis.com",
                        apiKey = "test-key",
                        request = baseRequest(),
                        maxTurns = 3,
                    ) {
                        ToolExecutionOutcome.Result(buildJsonObject { put("ok", true) })
                    }

                assertIs<InteractionExecutionResult.Error.Generic>(result, "Expected Generic error for payload: $payload")
                assertEquals(AiTraceFailureCategory.PARSE_ERROR, result.failureCategory, "Expected PARSE_ERROR for payload: $payload")
            }
        }

    @Test
    fun `interactWithTools catches toolExecutor exceptions as CONTEXT_ERROR and rethrows CancellationException`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "id": "int-1",
                              "status": "completed",
                              "steps": [
                                {"type": "function_call", "call_id": "c-1", "name": "lookup_context", "arguments": {}}
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)

            val exceptionResult =
                client.interactWithTools(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request = baseRequest(),
                    maxTurns = 2,
                ) {
                    error("boom")
                }
            assertIs<InteractionExecutionResult.Error.Generic>(exceptionResult)
            assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, exceptionResult.failureCategory)
            assertTrue(exceptionResult.message.contains("boom"), "Expected exception message in error: ${exceptionResult.message}")

            assertFailsWith<CancellationException> {
                client.interactWithTools(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request = baseRequest(),
                    maxTurns = 2,
                ) {
                    throw CancellationException("cancelled")
                }
            }
        }

    @Test
    fun `interactWithTools propagates ToolExecutionOutcome Abort with deterministic failureCategory`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "id": "int-1",
                              "status": "completed",
                              "steps": [
                                {"type": "function_call", "call_id": "c-1", "name": "lookup_context", "arguments": {}}
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)

            val result =
                client.interactWithTools(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request = baseRequest(),
                    maxTurns = 2,
                ) {
                    ToolExecutionOutcome.Abort(
                        message = "Missing required reason parameter",
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    )
                }

            assertIs<InteractionExecutionResult.Error.Generic>(result)
            assertEquals(AiTraceFailureCategory.PARSE_ERROR, result.failureCategory)
            assertEquals("Missing required reason parameter", result.message)
        }
}
