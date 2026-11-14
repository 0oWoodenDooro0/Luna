package luna.summarizer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.GeminiTools
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeminiSummarizerLookbackTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    @Test
    fun `serializes tools array with fetch_earlier_messages function declaration when tools are provided`() =
        runTest {
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "id": "int-lb-1",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{ "type": "text", "text": "TL;DR: Summary without lookback." }]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    recordedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val input =
                SummaryInput(
                    content = "[Alice]: Let's wrap up.",
                    tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                )
            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)

            val body = checkNotNull(recordedBody)
            assertTrue(body.contains("\"tools\""))
            assertTrue(body.contains("\"type\":\"function\""))
            assertTrue(body.contains(GeminiTools.FETCH_EARLIER_MESSAGES))
            assertTrue(body.contains("reason"))
        }

    @Test
    fun `omits tools field from serialized payload when no tools are configured`() =
        runTest {
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "id": "int-no-tools",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{ "type": "text", "text": "TL;DR: Normal summary." }]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    recordedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = false, urlContextEnabled = false),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize(SummaryInput(content = "[Alice]: Normal discussion"))
            assertIs<SummaryResult.Success>(result)

            val body = checkNotNull(recordedBody)
            assertFalse(body.contains("\"tools\""))
        }

    @Test
    fun `parses function_call step from Gemini and returns ToolCall result with continuation`() =
        runTest {
            val toolCallResponse =
                """
                {
                  "id": "int-turn-1",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-101",
                      "name": "fetch_earlier_messages",
                      "arguments": {
                        "reason": "Need earlier context to identify what 'it' refers to"
                      }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = toolCallResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val input =
                SummaryInput(
                    content = "[Bob]: Let's proceed with it.",
                    tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                )
            val result = summarizer.summarize(input)

            assertIs<SummaryResult.ToolCall>(result)
            assertEquals("fetch_earlier_messages", result.name)
            assertEquals("Need earlier context to identify what 'it' refers to", result.args["reason"])
            assertNotNull(result.continuation)
        }

    @Test
    fun `continues multi-turn summarization via previous_interaction_id and function_result`() =
        runTest {
            var requestCount = 0
            var secondRequestBody: String? = null

            val toolCallResponse =
                """
                {
                  "id": "int-turn-abc",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-abc",
                      "name": "fetch_earlier_messages",
                      "arguments": {
                        "reason": "Missing initial topic"
                      }
                    }
                  ]
                }
                """.trimIndent()

            val finalSummaryResponse =
                """
                {
                  "id": "int-turn-final",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "TL;DR:\n團隊決定採用方案 B 進行系統重構。\n\n核心重點:\n• 方案 B 支援向後相容"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    requestCount++
                    val body = request.body.toByteArray().decodeToString()
                    if (requestCount == 1) {
                        respond(
                            content = toolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        secondRequestBody = body
                        respond(
                            content = finalSummaryResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val input =
                SummaryInput(
                    content = "[Bob]: 我同意這個決定。",
                    tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                )
            val initialResult = summarizer.summarize(input)
            assertIs<SummaryResult.ToolCall>(initialResult)

            val continuation = checkNotNull(initialResult.continuation)
            val finalResult = continuation("[Alice]: 我們要選方案 A 還是方案 B？我建議方案 B。")

            assertIs<SummaryResult.Success>(finalResult)
            assertTrue(finalResult.text.contains("團隊決定採用方案 B 進行系統重構"))
            assertEquals(2, requestCount)

            val contPayload = Json.parseToJsonElement(checkNotNull(secondRequestBody)) as JsonObject
            assertEquals("int-turn-abc", (contPayload["previous_interaction_id"] as JsonPrimitive).content)
            val contInputs = contPayload["input"] as JsonArray
            val fnResultBlock = contInputs[0] as JsonObject
            assertEquals("function_result", (fnResultBlock["type"] as JsonPrimitive).content)
            assertEquals("call-abc", (fnResultBlock["call_id"] as JsonPrimitive).content)
            assertEquals("fetch_earlier_messages", (fnResultBlock["name"] as JsonPrimitive).content)
            assertTrue(fnResultBlock.toString().contains("方案 B"))
        }

    @Test
    fun `returns Error when Gemini returns requires_action with malformed function_call missing name`() =
        runTest {
            val malformedResponse =
                """
                {
                  "id": "int-malformed",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-bad",
                      "name": "   ",
                      "arguments": {}
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = malformedResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Test",
                        tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                    ),
                )

            assertIs<SummaryResult.Error.Generic>(result)
            assertEquals(AiTraceFailureCategory.PARSE_ERROR, classifySummaryError(result))
        }

    @Test
    fun `returns Error when Gemini returns unknown tool name not declared in tools`() =
        runTest {
            val unknownToolResponse =
                """
                {
                  "id": "int-unknown-tool",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-unk",
                      "name": "unsupported_shell_execute",
                      "arguments": { "cmd": "ls" }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = unknownToolResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Test",
                        tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                    ),
                )

            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("Unknown tool call", ignoreCase = true))
            assertTrue(result.message.contains("unsupported_shell_execute"))
        }

    @Test
    fun `returns Error when fetch_earlier_messages function_call is missing required reason argument`() =
        runTest {
            val missingReasonResponse =
                """
                {
                  "id": "int-no-reason",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-nr",
                      "name": "fetch_earlier_messages",
                      "arguments": {}
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = missingReasonResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Test",
                        tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                    ),
                )

            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("reason", ignoreCase = true))
        }

    @Test
    fun `returns Error when fetch_earlier_messages function_call has blank reason argument`() =
        runTest {
            val blankReasonResponse =
                """
                {
                  "id": "int-blank-reason",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-br",
                      "name": "fetch_earlier_messages",
                      "arguments": { "reason": "   " }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = blankReasonResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Test",
                        tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                    ),
                )

            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("reason", ignoreCase = true))
        }

    @Test
    fun `executes multi-turn lookback when lookbackProvider is passed and model invokes fetch_earlier_messages`() =
        runTest {
            var requestCount = 0
            val recordedPayloads = mutableListOf<String>()

            val toolCallResponse =
                """
                {
                  "id": "int-multi-1",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-m1",
                      "name": "fetch_earlier_messages",
                      "arguments": { "reason": "Reference to earlier discussion is unclear" }
                    }
                  ]
                }
                """.trimIndent()

            val finalResponse =
                """
                {
                  "id": "int-multi-2",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "TL;DR:\n團隊針對資料庫與架構完成深入討論並達成共識。\n\n核心重點:\n• 確認採用 PostgreSQL"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    requestCount++
                    recordedPayloads.add(request.body.toByteArray().decodeToString())
                    val body = if (requestCount == 1) toolCallResponse else finalResponse
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val input = SummaryInput(content = "[Charlie]: I agree with that database choice.")
            var capturedReason: String? = null
            val result =
                summarizer.summarize(input) { reason ->
                    capturedReason = reason
                    "[前置歷史背景]\n[Alice]: Let's use PostgreSQL.\n\n[當前核心討論]\n[Charlie]: I agree with that database choice."
                }

            assertIs<SummaryResult.Success>(result)
            assertEquals("Reference to earlier discussion is unclear", capturedReason)
            assertTrue(result.text.contains("團隊針對資料庫與架構完成深入討論並達成共識"))
            assertEquals(2, requestCount)
            assertTrue(recordedPayloads[0].contains("fetch_earlier_messages"))
            assertTrue(recordedPayloads[1].contains("\"previous_interaction_id\":\"int-multi-1\""))
            assertTrue(recordedPayloads[1].contains("function_result"))
            assertTrue(recordedPayloads[1].contains("Let's use PostgreSQL"))
        }

    @Test
    fun `fails fast without baseline retry and logs error when tool-enabled call fails`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = """{"error": {"code": 500, "message": "Tool call failed"}}""",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "[Alice]: Discussion content")
                val result =
                    summarizer.summarize(input) { _ ->
                        "Earlier messages"
                    }

                assertIs<SummaryResult.Error.ApiError>(result)
                assertEquals(500, result.statusCode)
                assertEquals(1, requestCount, "Must fail fast with 1 upstream attempt and no baseline retry")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `fails without baseline retry and logs error when model attempts more than one lookback turn`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val toolCallResponse =
                    """
                    {
                      "id": "int-loop-1",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "function_call",
                          "id": "call-loop",
                          "name": "fetch_earlier_messages",
                          "arguments": { "reason": "Need even more history" }
                        }
                      ]
                    }
                    """.trimIndent()

                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = toolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "Target conversation")
                val result =
                    summarizer.summarize(input) { _ ->
                        "Earlier context chunk"
                    }

                assertIs<SummaryResult.Error.Generic>(result)
                assertEquals(AiTraceFailureCategory.PARSE_ERROR, classifySummaryError(result))
                assertEquals(2, requestCount, "Must stop after round-2 continuation without a 3rd baseline retry")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `fails without baseline retry and logs error when lookbackProvider throws an exception`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val toolCallResponse =
                    """
                    {
                      "id": "int-prov-err",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "function_call",
                          "id": "call-pe",
                          "name": "fetch_earlier_messages",
                          "arguments": { "reason": "Need history" }
                        }
                      ]
                    }
                    """.trimIndent()

                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = toolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "Target conversation")
                val result =
                    summarizer.summarize(input) { _ ->
                        throw IllegalStateException("Discord API fetch failed")
                    }

                assertIs<SummaryResult.Error.Generic>(result)
                assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, classifySummaryError(result))
                assertEquals(1, requestCount, "Lookback provider failure must not trigger baseline retry")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `fails without baseline retry and logs error when tool continuation throws network exception`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val toolCallResponse =
                    """
                    {
                      "id": "int-cont-exc",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "function_call",
                          "id": "call-ce",
                          "name": "fetch_earlier_messages",
                          "arguments": { "reason": "Need history" }
                        }
                      ]
                    }
                    """.trimIndent()

                val mockEngine =
                    MockEngine {
                        requestCount++
                        if (requestCount == 1) {
                            respond(
                                content = toolCallResponse,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30000L)
                        }
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "Target conversation")
                val result =
                    summarizer.summarize(input) { _ ->
                        "Context data"
                    }

                assertIs<SummaryResult.Error.Timeout>(result)
                assertEquals(2, requestCount, "Continuation failure must stop after round 2 without baseline retry")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `rejects fetch_earlier_messages tool call when call_id is missing without invoking lookbackProvider`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                var providerInvoked = false
                val malformedToolCallResponse =
                    """
                    {
                      "id": "int-missing-call-id",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "function_call",
                          "name": "fetch_earlier_messages",
                          "arguments": { "reason": "Need earlier context" }
                        }
                      ]
                    }
                    """.trimIndent()

                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = malformedToolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "Target conversation")
                val result =
                    summarizer.summarize(input) { _ ->
                        providerInvoked = true
                        "Should never be fetched"
                    }

                assertFalse(providerInvoked, "lookbackProvider must not run when call_id is missing")
                assertEquals(1, requestCount, "Must fail closed after round 1 without continuation")
                assertIs<SummaryResult.Error.Generic>(result)
                assertEquals(AiTraceFailureCategory.PARSE_ERROR, classifySummaryError(result))
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.ERROR && it.formattedMessage.contains("call_id")
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
