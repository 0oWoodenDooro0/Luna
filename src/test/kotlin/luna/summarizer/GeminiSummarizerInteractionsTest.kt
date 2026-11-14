package luna.summarizer

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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import luna.core.ai.ChatImageAttachment
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeminiSummarizerInteractionsTest {
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

    @Test
    fun `negative boundary performs zero layered retries and logs error when interaction fails with images and tools`() =
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
                            content = """{"error": {"code": 400, "message": "Unable to fetch image"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config =
                            GeminiConfig(
                                apiKey = "test-key",
                                searchGroundingEnabled = true,
                                urlContextEnabled = true,
                            ),
                        httpClient = createTestClient(mockEngine),
                    )

                val result =
                    summarizer.summarize(
                        SummaryInput(
                            content = "Check https://example.com/a",
                            webUrls = listOf("https://example.com/a"),
                            imageAttachments =
                                listOf(
                                    ChatImageAttachment(
                                        url = "https://cdn.discordapp.com/attachments/1/2/bad.png",
                                        mimeType = "image/png",
                                        filename = "bad.png",
                                    ),
                                ),
                        ),
                        lookbackProvider = { "Earlier history" },
                    )

                assertIs<SummaryResult.Error.ApiError>(result)
                assertEquals(400, result.statusCode)
                assertEquals(1, requestCount, "GeminiSummarizer must fail fast with 1 upstream request and zero retries")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `logs error on non-success url_context_result while preserving valid generated summary`() =
        runTest {
            val logger = LoggerFactory.getLogger(luna.core.ai.GeminiInteractionClient::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val responseJson =
                    """
                    {
                      "id": "int-uc-err",
                      "status": "completed",
                      "steps": [
                        {
                          "type": "url_context_result",
                          "call_id": "uc-1",
                          "result": [
                            { "retrieved_url": "https://blocked.example.com/bad", "status": "error" }
                          ]
                        },
                        {
                          "type": "model_output",
                          "content": [
                            { "type": "text", "text": "TL;DR:\n已依據對話完成摘要。" }
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
                        config = GeminiConfig(apiKey = "test-key", urlContextEnabled = true),
                        httpClient = createTestClient(mockEngine),
                    )

                val result =
                    summarizer.summarize(
                        SummaryInput(
                            content = "Read https://blocked.example.com/bad",
                            webUrls = listOf("https://blocked.example.com/bad"),
                        ),
                    )

                assertIs<SummaryResult.Success>(result)
                assertEquals("TL;DR:\n已依據對話完成摘要。", result.text)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.ERROR &&
                            it.formattedMessage.contains("https://blocked.example.com/bad") &&
                            it.formattedMessage.contains("error")
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
