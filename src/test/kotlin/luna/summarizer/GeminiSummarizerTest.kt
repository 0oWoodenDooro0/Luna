package luna.summarizer

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeminiSummarizerTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    @Test
    fun `returns MissingApiKey when apiKey is blank or not configured`() =
        runTest {
            val config = GeminiConfig(apiKey = "")
            val summarizer = GeminiSummarizer(config = config)

            val result = summarizer.summarize("測試文章內容")
            assertIs<SummaryResult.Error.MissingApiKey>(result)
            assertEquals("Gemini API key is not configured", result.message)
        }

    @Test
    fun `returns Generic error when content is blank`() =
        runTest {
            val config = GeminiConfig(apiKey = "valid-key")
            val summarizer = GeminiSummarizer(config = config)

            val result = summarizer.summarize("   ")
            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("blank", ignoreCase = true))
        }

    @Test
    fun `successfully summarizes content with correct endpoint, model, and payload`() =
        runTest {
            var recordedUrl: String? = null
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "text": "TL;DR:\n這是一段繁體中文摘要。\n\n重點摘要:\n• 重點一\n• 重點二\n• 重點三"
                          }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    recordedUrl = request.url.toString()
                    recordedBody = request.body.toByteArray().decodeToString()

                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config =
                GeminiConfig(
                    apiKey = "secret-gemini-key",
                    model = "gemini-1.5-flash",
                    baseUrl = "https://generativelanguage.googleapis.com",
                )
            val summarizer = GeminiSummarizer(config = config, httpClient = createTestClient(mockEngine))

            val input =
                SummaryInput(
                    title = "測試標題",
                    content = "這是一篇關於人工智慧發展的詳細介紹。",
                    comments = listOf("討論很熱烈", "值得期待"),
                    url = "https://example.com/ai",
                )
            val result = summarizer.summarize(input)

            assertIs<SummaryResult.Success>(result)
            assertEquals(
                "TL;DR:\n這是一段繁體中文摘要。\n\n重點摘要:\n• 重點一\n• 重點二\n• 重點三",
                result.text,
            )

            // Verify URL contains model and API key query param
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=secret-gemini-key",
                recordedUrl,
            )

            // Verify request payload was serialized and contains instruction and content
            val body = recordedBody
            kotlin.test.assertNotNull(body)
            assertTrue(body.contains("台灣繁體中文"))
            assertTrue(body.contains("社群留言與討論："))
        }

    @Test
    fun `strips rogue emojis from Gemini candidate response text defensively`() =
        runTest {
            val responseWithEmojis =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "text": "TL;DR:\n🚀 台灣團隊研發出新技術 👍！\n\n重點摘要:\n• 💡 重點一\n• ⚡ 重點二"
                          }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = responseWithEmojis,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("測試內容")
            assertIs<SummaryResult.Success>(result)
            assertFalse(EmojiSanitizer.containsEmoji(result.text))
            assertEquals(
                "TL;DR:\n 台灣團隊研發出新技術 ！\n\n重點摘要:\n•  重點一\n•  重點二",
                result.text,
            )
        }

    @Test
    fun `handles 429 Too Many Requests and returns RateLimitExceeded`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content = """{"error": {"code": 429, "message": "Resource has been exhausted", "status": "RESOURCE_EXHAUSTED"}}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("測試內容")
            assertIs<SummaryResult.Error.RateLimitExceeded>(result)
            assertTrue(result.message.contains("rate limit", ignoreCase = true))
        }

    @Test
    fun `handles 400 Bad Request and returns ApiError`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content = """{"error": {"code": 400, "message": "Invalid argument provided", "status": "INVALID_ARGUMENT"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("測試內容")
            assertIs<SummaryResult.Error.ApiError>(result)
            assertEquals(400, result.statusCode)
            assertTrue(result.message.contains("Invalid argument provided"))
        }

    @Test
    fun `handles 500 Internal Server Error and returns ApiError`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content = "Internal server error",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("測試內容")
            assertIs<SummaryResult.Error.ApiError>(result)
            assertEquals(500, result.statusCode)
        }

    @Test
    fun `handles timeout and returns Timeout error`() =
        runTest {
            val mockEngine =
                MockEngine {
                    throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30_000L)
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("測試內容")
            assertIs<SummaryResult.Error.Timeout>(result)
            assertTrue(result.message.contains("timed out", ignoreCase = true))
        }

    @Test
    fun `handles safety blocked prompt feedback and returns Generic error`() =
        runTest {
            val blockedResponse =
                """
                {
                  "promptFeedback": {
                    "blockReason": "SAFETY"
                  }
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = blockedResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("測試內容")
            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("SAFETY"))
        }

    @Test
    fun `handles empty candidates in OK response and returns Generic error`() =
        runTest {
            val emptyResponse = """{"candidates": []}"""

            val mockEngine =
                MockEngine {
                    respond(
                        content = emptyResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("測試內容")
            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("candidates", ignoreCase = true))
        }

    @Test
    fun `invokes interactions endpoint and polls until completion when agentic mode is selected`() =
        runTest {
            var callCount = 0
            val mockEngine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/interactions") && request.method.value == "POST" -> {
                            assertEquals("test-key", request.url.parameters["key"])
                            val body = request.body.toByteArray().decodeToString()
                            assertTrue(body.contains("zh-TW"))
                            assertTrue(body.contains("TL;DR:"))
                            respond(
                                content = """{"id": "int-123", "status": "in_progress"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        request.url.encodedPath.contains("/interactions/int-123") && request.method.value == "GET" -> {
                            callCount++
                            if (callCount == 1) {
                                respond(
                                    content = """{"id": "int-123", "status": "in_progress"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            } else {
                                respond(
                                    content = """{"id": "int-123", "status": "completed", "output_text": "TL;DR: Summary of long video\n\n核心重點:\n• Arg 1"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        }

                        else -> {
                            respond("Not found", status = HttpStatusCode.NotFound)
                        }
                    }
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                    pollingDelayMs = 1L,
                )

            val input =
                SummaryInput(
                    content = "Detailed lecture",
                    videoUri = "https://www.youtube.com/watch?v=longTalk",
                    videoDurationSeconds = 1800L,
                )

            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("TL;DR: Summary of long video"))
            assertTrue(result.text.contains("核心重點:"))
            assertEquals(2, callCount)
        }

    @Test
    fun `returns Timeout error when interactions polling exceeds max attempts`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-timeout", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"id": "int-timeout", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                    pollingDelayMs = 1L,
                    maxPollingAttempts = 3,
                )

            val input =
                SummaryInput(
                    content = "Stuck video",
                    videoUri = "https://www.youtube.com/watch?v=stuckVideo",
                    videoDurationSeconds = 1200L,
                )

            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Error.Timeout>(result)
            assertTrue(result.message.contains("timed out", ignoreCase = true))
        }

    @Test
    fun `returns Error when static Gemini response contains only emojis or whitespaces`() =
        runTest {
            val responseJson =
                """
                {
                    "candidates": [
                        {
                            "content": {
                                "parts": [
                                    { "text": "  📝👍🎉  " }
                                ]
                            }
                        }
                    ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
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

            val result = summarizer.summarize("Some text")
            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("empty or sanitized to blank", ignoreCase = true))
        }

    @Test
    fun `returns Error when agentic interaction response outputText contains only emojis or whitespaces`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-emoji-only", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"id": "int-emoji-only", "status": "completed", "outputText": "   📝🎉   "}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                    pollingDelayMs = 1L,
                )

            val input =
                SummaryInput(
                    content = "Video",
                    videoUri = "https://www.youtube.com/watch?v=emojiVideo",
                    videoDurationSeconds = 600L,
                )

            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("empty or sanitized to blank", ignoreCase = true))
        }

    @Test
    fun `extracts summary text from interactions steps model_output structure`() =
        runTest {
            val responseJson =
                """
                {
                    "id": "v1_test_steps",
                    "status": "completed",
                    "steps": [
                        {
                            "type": "thought",
                            "signature": "thought-sig"
                        },
                        {
                            "type": "model_output",
                            "content": [
                                {
                                    "type": "text",
                                    "text": "TL;DR: Extract from steps succeeded\n\n核心重點:\n• Point 1"
                                }
                            ]
                        }
                    ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "v1_test_steps", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = responseJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                    pollingDelayMs = 1L,
                )

            val input =
                SummaryInput(
                    content = "Video",
                    videoUri = "https://www.youtube.com/watch?v=stepsVideo",
                    videoDurationSeconds = 600L,
                )

            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("TL;DR: Extract from steps succeeded"))
            assertTrue(result.text.contains("核心重點:"))
        }

    @Test
    fun `serializes tool declaration schema with fetch_earlier_messages and reason parameter`() =
        runTest {
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "TL;DR:\n摘要完成\n\n核心重點:\n• 重點" }
                        ]
                      }
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
                    content = "Discussion needing context",
                    tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                )
            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)

            val body = recordedBody
            kotlin.test.assertNotNull(body)
            assertTrue(body.contains("tools"))
            assertTrue(body.contains("fetch_earlier_messages"))
            assertTrue(body.contains("functionDeclarations"))
            assertTrue(body.contains("reason"))
            assertTrue(body.contains("OBJECT"))
        }

    @Test
    fun `omits tools field from serialized payload when no tools are configured`() =
        runTest {
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "TL;DR:\n一般摘要\n\n核心重點:\n• 重點" }
                        ]
                      }
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

            val input = SummaryInput(content = "Plain text summarization")
            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)

            val body = recordedBody
            kotlin.test.assertNotNull(body)
            assertFalse(body.contains("\"tools\""))
        }

    @Test
    fun `parses functionCall from Gemini response candidate and returns ToolCall with arguments`() =
        runTest {
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "fetch_earlier_messages",
                              "args": {
                                "reason": "Need earlier conversation to determine disagreement origin"
                              }
                            }
                          }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
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
                    content = "Recent message slice",
                    tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                )
            val result = summarizer.summarize(input)

            assertIs<SummaryResult.ToolCall>(result)
            assertEquals("fetch_earlier_messages", result.name)
            assertEquals("Need earlier conversation to determine disagreement origin", result.args["reason"])
            kotlin.test.assertNotNull(result.continuation)
        }

    @Test
    fun `multi-turn tool execution succeeds when continuation is invoked with earlier messages`() =
        runTest {
            var requestCount = 0
            var continuationBody: String? = null

            val toolCallResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "fetch_earlier_messages",
                              "args": {
                                "reason": "Missing context"
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val finalSummaryResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "text": "TL;DR:\n完整脈絡繁體中文摘要\n\n核心重點:\n• 早期討論要點\n• 後續共識"
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    requestCount++
                    if (requestCount == 1) {
                        respond(
                            content = toolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        continuationBody = request.body.toByteArray().decodeToString()
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
                    content = "Later part of discussion",
                    tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                )

            val initialResult = summarizer.summarize(input)
            assertIs<SummaryResult.ToolCall>(initialResult)

            val continuation = initialResult.continuation
            kotlin.test.assertNotNull(continuation)

            val earlierHistory = "User A: We should adopt Kotlin 2.0. User B: Agreed."
            val finalResult = continuation.invoke(earlierHistory)

            assertIs<SummaryResult.Success>(finalResult)
            assertTrue(finalResult.text.contains("TL;DR:\n完整脈絡繁體中文摘要"))
            assertEquals(2, requestCount)

            val contBody = continuationBody
            kotlin.test.assertNotNull(contBody)
            assertTrue(contBody.contains("functionResponse"))
            assertTrue(contBody.contains("fetch_earlier_messages"))
            assertTrue(contBody.contains("User A: We should adopt Kotlin 2.0"))
        }

    @Test
    fun `summarizeWithTools coordinates multi-turn tool interaction with executor automatically`() =
        runTest {
            var requestCount = 0
            val toolCallResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "fetch_earlier_messages",
                              "args": {
                                "reason": "Requires earlier history"
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val finalSummaryResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "TL;DR:\n自動化多輪工具摘要成功\n\n核心重點:\n• 要點一\n• 要點二"
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    requestCount++
                    val content = if (requestCount == 1) toolCallResponse else finalSummaryResponse
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val input = SummaryInput(content = "Initial chat slice")
            var executorCalled = false

            val result =
                summarizer.summarizeWithTools(input) { name, args ->
                    executorCalled = true
                    assertEquals("fetch_earlier_messages", name)
                    assertEquals("Requires earlier history", args["reason"])
                    "Fetched earlier messages from channel"
                }

            assertIs<SummaryResult.Success>(result)
            assertTrue(executorCalled)
            assertTrue(result.text.contains("自動化多輪工具摘要成功"))
            assertEquals(2, requestCount)
        }

    @Test
    fun `summarizeWithTools returns Success directly when model produces text without tool calls`() =
        runTest {
            val directTextResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "TL;DR:\n直接產出摘要\n\n核心重點:\n• 重點一"
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = directTextResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            var executorCalled = false
            val result =
                summarizer.summarizeWithTools(SummaryInput("Sufficient context")) { _, _ ->
                    executorCalled = true
                    "Unreached"
                }

            assertIs<SummaryResult.Success>(result)
            assertFalse(executorCalled)
            assertTrue(result.text.contains("直接產出摘要"))
        }

    @Test
    fun `handles malformed tool response with blank function name gracefully`() =
        runTest {
            val malformedResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "   ",
                              "args": {}
                            }
                          }
                        ]
                      }
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
                        content = "Chat",
                        tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                    ),
                )
            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("missing function name", ignoreCase = true))
        }

    @Test
    fun `handles malformed tool response when required reason argument is missing`() =
        runTest {
            val missingReasonResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "fetch_earlier_messages",
                              "args": {}
                            }
                          }
                        ]
                      }
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
                        content = "Chat",
                        tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                    ),
                )
            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("reason", ignoreCase = true))
        }

    @Test
    fun `handles unknown tool call gracefully when model requests undeclared function`() =
        runTest {
            val unknownToolResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "unsupported_shell_execute",
                              "args": {
                                "cmd": "ls"
                              }
                            }
                          }
                        ]
                      }
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
                        content = "Chat",
                        tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                    ),
                )
            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("Unknown tool call", ignoreCase = true))
        }

    @Test
    fun `handles tool executor failure gracefully without throwing unhandled exceptions`() =
        runTest {
            val toolCallResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "fetch_earlier_messages",
                              "args": {
                                "reason": "Need context"
                              }
                            }
                          }
                        ]
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

            val result =
                summarizer.summarizeWithTools(SummaryInput("Chat")) { _, _ ->
                    throw IllegalStateException("Discord gateway disconnected")
                }

            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("Discord gateway disconnected"))
        }

    @Test
    fun `handles HTTP error during tool response continuation gracefully`() =
        runTest {
            var requestCount = 0
            val toolCallResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "fetch_earlier_messages",
                              "args": {
                                "reason": "Need context"
                              }
                            }
                          }
                        ]
                      }
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
                        respond(
                            content = "Internal Gemini error",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    }
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val initialResult =
                summarizer.summarize(
                    SummaryInput(
                        content = "Chat",
                        tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                    ),
                )
            assertIs<SummaryResult.ToolCall>(initialResult)

            val continuationResult = initialResult.continuation?.invoke("Earlier messages")
            assertIs<SummaryResult.Error.ApiError>(continuationResult)
            assertEquals(500, continuationResult.statusCode)
        }

    @Test
    fun `handles maximum turn limit exceeded gracefully in summarizeWithTools`() =
        runTest {
            val endlessToolCallResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "functionCall": {
                              "name": "fetch_earlier_messages",
                              "args": {
                                "reason": "Need even more context"
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = endlessToolCallResponse,
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
                summarizer.summarizeWithTools(
                    input = SummaryInput("Chat"),
                    maxTurns = 2,
                ) { _, _ -> "History batch" }

            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("maximum tool call turns", ignoreCase = true))
        }
}
