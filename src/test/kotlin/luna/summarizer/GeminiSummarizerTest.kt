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
    fun `invokes interactions endpoint and polls until completion when agentic mode is selected`() =
        runTest {
            var callCount = 0
            val mockEngine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/interactions") && request.method.value == "POST" -> {
                            assertEquals("test-key", request.url.parameters["key"])
                            val body = request.body.toByteArray().decodeToString()
                            assertTrue(body.contains("longTalk"))
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
    fun `enables url_context only when urlContextEnabled is true and webUrls is non-empty`() =
        runTest {
            val recordedPayloads = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    recordedPayloads.add(request.body.toByteArray().decodeToString())
                    respond(
                        content =
                            """{"id": "int-uc", "status": "completed", """ +
                                """"steps": [{"type": "model_output", "content": [{"type": "text", "text": "TL;DR: Done"}]}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            // 1. urlContextEnabled = true & webUrls non-empty -> includes url_context + google_search
            val enabledSummarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true, urlContextEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )
            enabledSummarizer.summarize(
                SummaryInput(
                    content = "Read https://kotlinlang.org/docs",
                    webUrls = listOf("https://kotlinlang.org/docs"),
                ),
            )
            assertTrue(recordedPayloads[0].contains("\"type\":\"url_context\""))
            assertTrue(recordedPayloads[0].contains("\"type\":\"google_search\""))

            // 2. urlContextEnabled = true & webUrls empty -> omits url_context
            enabledSummarizer.summarize(SummaryInput(content = "No web URLs"))
            assertFalse(recordedPayloads[1].contains("\"type\":\"url_context\""))
            assertTrue(recordedPayloads[1].contains("\"type\":\"google_search\""))

            // 3. urlContextEnabled = false & webUrls non-empty -> omits url_context
            val disabledSummarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true, urlContextEnabled = false),
                    httpClient = createTestClient(mockEngine),
                )
            disabledSummarizer.summarize(
                SummaryInput(
                    content = "Read https://kotlinlang.org/docs",
                    webUrls = listOf("https://kotlinlang.org/docs"),
                ),
            )
            assertFalse(recordedPayloads[2].contains("\"type\":\"url_context\""))
            assertTrue(recordedPayloads[2].contains("\"type\":\"google_search\""))
        }
}
