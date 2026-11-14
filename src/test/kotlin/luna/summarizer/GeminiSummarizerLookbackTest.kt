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
import luna.core.ai.ChatImageAttachment
import luna.core.ai.EmojiSanitizer
import luna.core.ai.GeminiTools
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
        }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
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
}
