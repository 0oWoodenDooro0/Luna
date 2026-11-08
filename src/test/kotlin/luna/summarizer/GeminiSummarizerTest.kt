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
import kotlin.test.assertNotNull
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
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = false),
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
    fun `summarize coordinates lookbackProvider automatically when model requests fetch_earlier_messages`() =
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
                                "reason": "Requires earlier context"
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
                            "text": "TL;DR:\nLookback succeeded\n\n核心重點:\n• 成功擴充對話背景"
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

            var lookbackCalled = false
            val input = SummaryInput(content = "Baseline conversation")
            val result =
                summarizer.summarize(input) { reason ->
                    lookbackCalled = true
                    assertEquals("Requires earlier context", reason)
                    "Expanded earlier history"
                }

            assertIs<SummaryResult.Success>(result)
            assertTrue(lookbackCalled)
            assertTrue(result.text.contains("Lookback succeeded"))
            assertEquals(2, requestCount)
        }

    @Test
    fun `summarize falls back to baseline when lookback provider fails`() =
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
                                "reason": "Requires earlier context"
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val baselineResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "TL;DR:\nBaseline summary fallback\n\n核心重點:\n• 摘要降級成功"
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
                    val content = if (requestCount == 1) toolCallResponse else baselineResponse
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

            val input = SummaryInput(content = "Baseline conversation")
            val result =
                summarizer.summarize(input) { _ ->
                    throw RuntimeException("Database timeout on history fetch")
                }

            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("Baseline summary fallback"))
        }

    @Test
    fun `enforces single lookback limit by falling back to baseline summary when model attempts second tool call`() =
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
                              "args": { "reason": "Round 1" }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val secondToolCallResponse =
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
                              "args": { "reason": "Round 2 attempt" }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val baselineResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "TL;DR:\nBaseline summary after round limit" }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    requestCount++
                    val content =
                        when (requestCount) {
                            1 -> toolCallResponse
                            2 -> secondToolCallResponse
                            else -> baselineResponse
                        }
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

            val input = SummaryInput(content = "Target conversation")
            val result =
                summarizer.summarize(input) { reason ->
                    "Lookback response for $reason"
                }

            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("Baseline summary after round limit"))
            assertEquals(3, requestCount)
        }

    @Test
    fun `falls back gracefully to baseline summary when tool continuation throws exception`() =
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
                              "args": { "reason": "Need context" }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val baselineResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "TL;DR:\nBaseline summary after continuation exception" }
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
                    } else if (requestCount == 2) {
                        throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30000L)
                    } else {
                        respond(
                            content = baselineResponse,
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

            val input = SummaryInput(content = "Target conversation")
            val result =
                summarizer.summarize(input) { _ ->
                    "Context data"
                }

            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("Baseline summary after continuation exception"))
        }

    @Test
    fun `retries with text-only fallback when multimodal summarization with images fails with error`() =
        runTest {
            var requestCount = 0
            var fallbackHadImages = true

            val textFallbackResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "TL;DR:\nText-only fallback summary succeeded" }
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
                            content = """{"error": {"code": 400, "message": "Failed to download image"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        val body = request.body.toByteArray().decodeToString()
                        fallbackHadImages = body.contains("inlineData") || body.contains("fileData")
                        respond(
                            content = textFallbackResponse,
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
                    content = "Chat with image",
                    imageAttachments =
                        listOf(
                            ChatImageAttachment(
                                url = "https://example.com/test.png",
                                mimeType = "image/png",
                                filename = "test.png",
                            ),
                        ),
                )

            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("Text-only fallback summary succeeded"))
            assertEquals(2, requestCount)
            assertFalse(fallbackHadImages)
        }

    @Test
    fun `summarize includes standalone googleSearch tool when searchGroundingEnabled is true`() =
        runTest {
            var capturedBody: String? = null
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [{ "text": "TL;DR:\nSummary with search grounding." }]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("Topic with specialized terms")
            assertIs<SummaryResult.Success>(result)
            val body = checkNotNull(capturedBody)
            assertTrue(body.contains("\"googleSearch\":{}"))
            assertFalse(body.contains("\"functionDeclarations\""))
        }

    @Test
    fun `summarize combines googleSearch and functionDeclarations as separate objects in tools array`() =
        runTest {
            var capturedBody: String? = null
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [{ "text": "TL;DR:\nSummary succeeded." }]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    input = SummaryInput(content = "Multi-tool request"),
                    lookbackProvider = { "earlier history" },
                )

            assertIs<SummaryResult.Success>(result)
            val body = checkNotNull(capturedBody)
            assertTrue(body.contains("\"googleSearch\":{}"))
            assertTrue(body.contains("fetch_earlier_messages"))
        }

    @Test
    fun `summarize extracts and deduplicates grounding citations from candidate metadata`() =
        runTest {
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [{ "text": "TL;DR:\nSummary with citations." }]
                      },
                      "groundingMetadata": {
                        "webSearchQueries": ["Gemini Search Grounding"],
                        "groundingChunks": [
                          { "web": { "uri": "https://example.com/source1", "title": "Source 1" } },
                          { "web": { "uri": "https://example.com/source2", "title": "Source 2" } },
                          { "web": { "uri": "https://example.com/source1", "title": "Duplicate Source" } },
                          { "web": { "uri": "https://example.com/source3", "title": "Source 3" } },
                          { "web": { "uri": "https://example.com/source4", "title": "Source 4" } }
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
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("Topic requiring citations")
            assertIs<SummaryResult.Success>(result)
            assertEquals(3, result.citations.size)
            assertEquals("https://example.com/source1", result.citations[0].url)
            assertEquals("Source 1", result.citations[0].title)
            assertEquals("https://example.com/source2", result.citations[1].url)
            assertEquals("https://example.com/source3", result.citations[2].url)
        }

    @Test
    fun `summarize gracefully falls back to baseline when search grounding fails`() =
        runTest {
            var requestCount = 0
            val fallbackResponse =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [{ "text": "TL;DR:\nBaseline fallback summary without search." }]
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
                            content = """{"error": {"code": 400, "message": "Search grounding tool not supported on this model"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        val body = request.body.toByteArray().decodeToString()
                        assertFalse(body.contains("googleSearch"))
                        respond(
                            content = fallbackResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize("Should fall back to baseline")
            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("Baseline fallback summary without search"))
            assertEquals(2, requestCount)
        }

    @Test
    fun `GeminiConfig parses GEMINI_SEARCH_GROUNDING_ENABLED from environment`() {
        val defaultCfg = GeminiConfig.fromEnv()
        assertTrue(defaultCfg.searchGroundingEnabled)

        val disabledCfg = GeminiConfig.fromEnv(mapOf("GEMINI_SEARCH_GROUNDING_ENABLED" to "false"))
        assertFalse(disabledCfg.searchGroundingEnabled)

        val enabledCfg = GeminiConfig.fromEnv(mapOf("GEMINI_SEARCH_GROUNDING_ENABLED" to "true"))
        assertTrue(enabledCfg.searchGroundingEnabled)
    }
}
