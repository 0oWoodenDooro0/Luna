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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import luna.core.ai.ChatImageAttachment
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.GroundingCitation
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeminiSummarizerMultimodalGroundingTest {
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
    fun `summarize handles static short video via sync interactions with background false and no polling`() =
        runTest {
            var requestCount = 0
            var recordedBody: String? = null
            var recordedUrl: String? = null

            val responseJson =
                """
                {
                  "id": "int-static-vid",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "TL;DR: Short video summary\n\n核心重點:\n• Point 1"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    requestCount++
                    recordedUrl = request.url.toString()
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
                    content = "Quick demo video",
                    videoUri = "https://www.youtube.com/watch?v=shortVid",
                    videoDurationSeconds = 180L,
                )

            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("TL;DR: Short video summary"))
            assertEquals(1, requestCount)
            assertTrue(checkNotNull(recordedUrl).contains("/v1beta/interactions"))

            val payload = Json.parseToJsonElement(checkNotNull(recordedBody)) as JsonObject
            assertEquals("false", payload["background"].toString())
            val inputBlocks = payload["input"] as JsonArray
            val videoBlock = inputBlocks[0] as JsonObject
            assertEquals("video", (videoBlock["type"] as JsonPrimitive).content)
            assertEquals("https://www.youtube.com/watch?v=shortVid", (videoBlock["uri"] as JsonPrimitive).content)
            assertEquals("static", (videoBlock["processing"] as JsonPrimitive).content)
            assertFalse(videoBlock.containsKey("mime_type"), "YouTube video block must omit mime_type")
        }

    @Test
    fun `summarize sends chat image attachments as image input blocks and succeeds`() =
        runTest {
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "id": "int-img-1",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "TL;DR:\n對話與截圖中的架構圖已分析完成。\n\n核心重點:\n• API Gateway 轉發至後端"
                        }
                      ]
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
                    content = "[Alice]: 請看這張系統架構圖",
                    imageAttachments =
                        listOf(
                            ChatImageAttachment(
                                url = "https://cdn.discordapp.com/attachments/100/200/arch.png",
                                mimeType = "image/png",
                                filename = "arch.png",
                            ),
                        ),
                )

            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)
            assertTrue(result.text.contains("架構圖已分析完成"))

            val body = checkNotNull(recordedBody)
            assertTrue(body.contains("\"type\":\"image\""))
            assertTrue(body.contains("https://cdn.discordapp.com/attachments/100/200/arch.png"))
            assertTrue(body.contains("\"mime_type\":\"image/png\""))
        }

    @Test
    fun `summarize fails fast with 1 request and logs error when image attachment causes Gemini API 400 error`() =
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
                            content = """{"error": {"code": 400, "message": "Unable to fetch image from URI", "status": "INVALID_ARGUMENT"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = false),
                        httpClient = createTestClient(mockEngine),
                    )

                val input =
                    SummaryInput(
                        content = "[Alice]: 看看這張圖片討論的部署方案",
                        imageAttachments =
                            listOf(
                                ChatImageAttachment(
                                    url = "https://cdn.discordapp.com/attachments/100/200/expired.png",
                                    mimeType = "image/png",
                                    filename = "expired.png",
                                ),
                            ),
                    )

                val result = summarizer.summarize(input)
                assertIs<SummaryResult.Error.ApiError>(result)
                assertEquals(400, result.statusCode)
                assertEquals(1, requestCount, "Image failure must NOT retry without images")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `summarize combines url_context google_search and fetch_earlier_messages in tools array`() =
        runTest {
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "id": "int-tools-combo",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{ "type": "text", "text": "TL;DR:\n結合三種工具的摘要結果。" }]
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
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true, urlContextEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "[Alice]: Check https://kotlinlang.org/docs/k2.html",
                        webUrls = listOf("https://kotlinlang.org/docs/k2.html"),
                    ),
                    lookbackProvider = { "Earlier conversation" },
                )

            assertIs<SummaryResult.Success>(result)
            val bodyJson = Json.parseToJsonElement(checkNotNull(recordedBody)) as JsonObject
            val toolsArray = bodyJson["tools"] as JsonArray
            assertEquals(3, toolsArray.size)
            val toolTypes = toolsArray.map { ((it as JsonObject)["type"] as JsonPrimitive).content }
            assertEquals(listOf("url_context", "google_search", "function"), toolTypes)
            val fnTool = toolsArray[2] as JsonObject
            assertEquals(GeminiInteractionTools.FETCH_EARLIER_MESSAGES.name, (fnTool["name"] as JsonPrimitive).content)
        }

    @Test
    fun `summarize enables google_search by default when searchGroundingEnabled is true and omits it when disabled`() =
        runTest {
            val recordedBodies = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    recordedBodies.add(request.body.toByteArray().decodeToString())
                    respond(
                        content =
                            """{"id": "int-gs", "status": "completed", """ +
                                """"steps": [{"type": "model_output", "content": [{"type": "text", "text": "TL;DR:\nSummary."}]}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val defaultSummarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )
            val defaultResult = defaultSummarizer.summarize("Discuss recent AI breakthroughs")
            assertIs<SummaryResult.Success>(defaultResult)
            assertTrue(recordedBodies[0].contains("\"type\":\"google_search\""))

            val disabledSummarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = false),
                    httpClient = createTestClient(mockEngine),
                )
            val disabledResult = disabledSummarizer.summarize("Discuss recent AI breakthroughs")
            assertIs<SummaryResult.Success>(disabledResult)
            assertFalse(recordedBodies[1].contains("google_search"))
        }

    @Test
    fun `summarize does not retry without tools when google_search or url_context fails with HTTP error`() =
        runTest {
            var requestCount = 0

            val mockEngine =
                MockEngine {
                    requestCount++
                    respond(
                        content = """{"error": {"code": 400, "message": "Search grounding tool error", "status": "INVALID_ARGUMENT"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true, urlContextEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Discuss https://example.com/article",
                        webUrls = listOf("https://example.com/article"),
                    ),
                )
            assertIs<SummaryResult.Error.ApiError>(result)
            assertEquals(400, result.statusCode)
            assertEquals(1, requestCount, "Interactions API must fail fast with 1 HTTP call and zero tool-stripping retries")
        }

    @Test
    fun `summarize filters url_citation annotations excluding original slice URLs and url_context_result capping at 3`() =
        runTest {
            val responseWithCitations =
                """
                {
                  "id": "int-citations",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "url_context_result",
                      "call_id": "uc-1",
                      "result": [
                        { "retrieved_url": "https://article.example.com/post/", "status": "success" },
                        { "retrieved_url": "https://redirect.example.com/final", "status": "success" }
                      ]
                    },
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "TL;DR:\n詳細的技術與外部查證摘要。",
                          "annotations": [
                            { "type": "url_citation", "url": "http://article.example.com/post", "title": "Original Article" },
                            { "type": "url_citation", "url": "https://redirect.example.com/final/", "title": "Redirected URL" },
                            { "type": "url_citation", "url": "https://kotlinlang.org/docs/k2.html", "title": "Kotlin K2 Guide" },
                            { "type": "url_citation", "url": "https://kotlinlang.org/docs/k2.html/", "title": "Kotlin K2 Guide Duplicate" },
                            { "type": "url_citation", "url": "https://developer.android.com/kotlin", "title": "Android Kotlin" },
                            { "type": "url_citation", "url": "https://ktor.io/docs", "title": "Ktor Docs" },
                            { "type": "url_citation", "url": "https://example.org/ignored-fourth", "title": "Fourth Source" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = responseWithCitations,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true, urlContextEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Read https://article.example.com/post",
                        webUrls = listOf("https://article.example.com/post"),
                    ),
                )
            assertIs<SummaryResult.Success>(result)
            assertEquals(3, result.citations.size)
            assertEquals(
                listOf(
                    GroundingCitation("Kotlin K2 Guide", "https://kotlinlang.org/docs/k2.html"),
                    GroundingCitation("Android Kotlin", "https://developer.android.com/kotlin"),
                    GroundingCitation("Ktor Docs", "https://ktor.io/docs"),
                ),
                result.citations,
            )
        }

    @Test
    fun `summarize fails fast with 1 request and logs error when multimodal payload with images and tools fails`() =
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
                            content = """{"error": {"code": 400, "message": "Cannot download image URL"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true, urlContextEnabled = true),
                        httpClient = createTestClient(mockEngine),
                    )

                val input =
                    SummaryInput(
                        content = "Check this broken image link and https://example.com/spec",
                        webUrls = listOf("https://example.com/spec"),
                        imageAttachments =
                            listOf(
                                ChatImageAttachment(
                                    url = "https://cdn.discordapp.com/attachments/1/2/broken.png",
                                    mimeType = "image/png",
                                    filename = "broken.png",
                                ),
                            ),
                    )

                val result = summarizer.summarize(input)
                assertIs<SummaryResult.Error.ApiError>(result)
                assertEquals(400, result.statusCode)
                assertEquals(1, requestCount, "Must fail fast with 1 upstream attempt and zero no-images/no-search retries")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `summarize preserves valid model_output when url_context_result contains unsafe status logs error and excludes retrieved URLs`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val responseJson =
                    """
                    {
                      "id": "int-partial-uc",
                      "status": "completed",
                      "steps": [
                        {
                          "type": "url_context_result",
                          "call_id": "uc-1",
                          "result": [
                            { "retrieved_url": "https://safe.example.com/guide", "status": "success" },
                            { "retrieved_url": "https://blocked.example.com/unsafe", "status": "unsafe" }
                          ]
                        },
                        {
                          "type": "model_output",
                          "content": [
                            {
                              "type": "text",
                              "text": "TL;DR:\n已依據安全網頁完成摘要。",
                              "annotations": [
                                { "type": "url_citation", "url": "https://blocked.example.com/unsafe", "title": "Blocked" },
                                { "type": "url_citation", "url": "https://external.example.org/ref", "title": "External Ref" }
                              ]
                            }
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
                        config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = true, urlContextEnabled = true),
                        httpClient = createTestClient(mockEngine),
                    )

                val result =
                    summarizer.summarize(
                        SummaryInput(
                            content = "Compare https://safe.example.com/guide and https://blocked.example.com/unsafe",
                            webUrls = listOf("https://safe.example.com/guide", "https://blocked.example.com/unsafe"),
                        ),
                    )

                assertIs<SummaryResult.Success>(result)
                assertEquals("TL;DR:\n已依據安全網頁完成摘要。", result.text)
                assertEquals(listOf(GroundingCitation("External Ref", "https://external.example.org/ref")), result.citations)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.ERROR &&
                            it.formattedMessage.contains("https://blocked.example.com/unsafe") &&
                            it.formattedMessage.contains("unsafe")
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
