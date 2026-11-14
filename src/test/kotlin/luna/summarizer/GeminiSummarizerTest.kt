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
import luna.core.ai.EmojiSanitizer
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
            var hasApiRevision = false

            val responseJson =
                """
                {
                  "id": "int-success-1",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "TL;DR:\n這是一段繁體中文摘要。\n\n重點摘要:\n• 重點一\n• 重點二\n• 重點三"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    if (request.headers.contains("Api-Revision")) {
                        hasApiRevision = true
                    }
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

            assertFalse(hasApiRevision, "Must never send removed Api-Revision header")
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/interactions?key=secret-gemini-key",
                recordedUrl,
            )

            val body = checkNotNull(recordedBody)
            assertTrue(body.contains("\"model\":\"gemini-1.5-flash\""))
            assertTrue(body.contains("\"background\":false"))
            assertTrue(body.contains("https://example.com/ai"))
        }

    @Test
    fun `strips rogue emojis from Gemini interaction response text defensively`() =
        runTest {
            val responseWithEmojis =
                """
                {
                  "id": "int-emoji-1",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "TL;DR:\n🚀 台灣團隊研發出新技術 👍！\n\n重點摘要:\n• 💡 重點一\n• ⚡ 重點二"
                        }
                      ]
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
    fun `handles safety blocked finish_reason and returns Generic error`() =
        runTest {
            val blockedResponse =
                """
                {
                  "id": "int-safety-1",
                  "status": "completed",
                  "finish_reason": "SAFETY",
                  "steps": []
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
    fun `handles empty steps in OK response and returns Generic error`() =
        runTest {
            val emptyResponse = """{"id": "int-empty-1", "status": "completed", "steps": []}"""

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
            assertTrue(result.message.contains("empty", ignoreCase = true))
        }

    @Test
    fun `returns Error when static Gemini response contains only emojis or whitespaces`() =
        runTest {
            val responseJson =
                """
                {
                    "id": "int-emoji-only-static",
                    "status": "completed",
                    "steps": [
                        {
                            "type": "model_output",
                            "content": [
                                { "type": "text", "text": "  📝👍🎉  " }
                            ]
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
}
