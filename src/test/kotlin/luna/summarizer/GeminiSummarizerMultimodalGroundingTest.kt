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

class GeminiSummarizerMultimodalGroundingTest {
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
    fun `short video summarization routes to generateContent with clean file_data and sanitized model path`() =
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
                            "text": "TL;DR:\n這是短影片摘要。\n\n核心重點:\n• 重點一\n• 重點二"
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
                    apiKey = "secret-key",
                    model = "models/gemini-3.5-flash-lite",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
                )
            val summarizer = GeminiSummarizer(config = config, httpClient = createTestClient(mockEngine))

            val input =
                SummaryInput(
                    content = "Short video description",
                    videoUri = "https://www.youtube.com/watch?v=short123",
                    videoDurationSeconds = 179L,
                )

            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("這是短影片摘要"))

            // Verify endpoint does not have double 'models/' or double 'v1beta'
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=secret-key",
                recordedUrl,
            )

            // Verify file_data does NOT contain invalid 'processing' field
            val body = checkNotNull(recordedBody)
            assertTrue(body.contains("\"file_uri\":\"https://www.youtube.com/watch?v=short123\""))
            assertFalse(body.contains("\"processing\""), "REST payload must not contain 'processing' in file_data")
        }
}
