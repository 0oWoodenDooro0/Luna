package luna.chatter

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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.core.ai.ChatImageAttachment
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterGeneratorVideoAndBoundsTest {
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
    fun `tiered video policy uses sync static interaction without mime_type for videos at most 300 seconds or unknown duration`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            val recordedBodies = mutableListOf<String>()
            val responseJson =
                """{"id": "int-vid-static", "status": "completed", "output_text": "這段展示超順的"}"""

            val mockEngine =
                MockEngine { request ->
                    recordedUrls.add(request.url.toString())
                    recordedBodies.add(request.body.toByteArray().decodeToString())
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val ambientShort =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 看這支短片",
                        videoUri = "https://www.youtube.com/watch?v=short300",
                        videoDurationSeconds = 300L,
                        isDirectMention = false,
                    ),
                )
            val directUnknown =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Bob]: 這支片講什麼",
                        videoUri = "https://www.youtube.com/watch?v=unknownDur",
                        videoDurationSeconds = null,
                        isDirectMention = true,
                    ),
                )

            assertEquals("這段展示超順的", ambientShort)
            assertEquals("這段展示超順的", directUnknown)
            assertEquals(2, recordedUrls.size)
            assertTrue(recordedUrls.all { it.contains("/v1beta/interactions") })

            for (body in recordedBodies) {
                val obj = json.parseToJsonElement(body).jsonObject
                assertEquals(false, obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
                val videoBlock = obj["input"]!!.jsonArray[0].jsonObject
                assertEquals("video", videoBlock["type"]?.jsonPrimitive?.content)
                assertEquals("static", videoBlock["processing"]?.jsonPrimitive?.content)
                assertFalse(videoBlock.containsKey("mime_type"))
            }
        }

    @Test
    fun `tiered video policy omits videoUri and uses sync interaction for ambient replies over 300 seconds`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            var capturedBody: String? = null
            val responseJson =
                """{"id": "int-vid-amb", "status": "completed", "output_text": "看留言大家都推 12 分鐘那段實測"}"""

            val mockEngine =
                MockEngine { request ->
                    recordedUrls.add(request.url.toString())
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 這支教學推嗎\n[連結內容: https://www.youtube.com/watch?v=long600]\n標題：Kotlin Deep Dive\n留言：\n1. 12:00 實測很讚",
                        videoUri = "https://www.youtube.com/watch?v=long600",
                        videoDurationSeconds = 600L,
                        isDirectMention = false,
                        title = "Kotlin Deep Dive",
                        comments = listOf("12:00 實測很讚"),
                    ),
                )

            assertEquals("看留言大家都推 12 分鐘那段實測", reply)
            assertEquals(1, recordedUrls.size)
            assertTrue(recordedUrls.single().contains("/v1beta/interactions"))
            val obj = json.parseToJsonElement(checkNotNull(capturedBody)).jsonObject
            assertEquals(false, obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            val inputBlocks = obj["input"]!!.jsonArray
            assertEquals(1, inputBlocks.size)
            assertEquals("text", inputBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertTrue(capturedBody.orEmpty().contains("Kotlin Deep Dive"))
            assertTrue(capturedBody.orEmpty().contains("12:00 實測很讚"))
        }

    @Test
    fun `tiered video policy invokes agentic interactions polling for direct mentions over 300 seconds and at most 7200 seconds`() =
        runTest {
            val recordedMethods = mutableListOf<HttpMethod>()
            val recordedUrls = mutableListOf<String>()
            var interactionBody: String? = null
            var pollAttempt = 0

            val mockEngine =
                MockEngine { request ->
                    recordedMethods.add(request.method)
                    recordedUrls.add(request.url.toString())
                    if (request.method == HttpMethod.Post && request.url.toString().contains("/v1beta/interactions")) {
                        interactionBody = request.body.toByteArray().decodeToString()
                        respond(
                            content = """{"id": "interaction-chatter-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        pollAttempt++
                        val statusJson =
                            if (pollAttempt == 1) {
                                """{"id": "interaction-chatter-1", "status": "in_progress"}"""
                            } else {
                                """{"id": "interaction-chatter-1", "status": "completed", "output_text": "這支影片主要在示範協程結構化並發 👍"}"""
                            }
                        respond(
                            content = statusJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                    pollingDelayMs = 1L,
                    maxPollingAttempts = 5,
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 幫我看這支片在講什麼 https://kotlinlang.org/docs/coroutines.html",
                        videoUri = "https://www.youtube.com/watch?v=med7200",
                        videoDurationSeconds = 7200L,
                        isDirectMention = true,
                        webUrls = listOf("https://kotlinlang.org/docs/coroutines.html"),
                    ),
                )

            assertEquals("這支影片主要在示範協程結構化並發", reply)
            assertTrue(recordedUrls.first().contains("/v1beta/interactions?key=valid-key"))
            assertTrue(recordedUrls.drop(1).all { it.contains("/v1beta/interactions/interaction-chatter-1") })
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Get), recordedMethods)
            val submitted = checkNotNull(interactionBody)
            val obj = json.parseToJsonElement(submitted).jsonObject
            assertEquals(true, obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            val videoBlock = obj["input"]!!.jsonArray[0].jsonObject
            assertEquals("agentic", videoBlock["processing"]?.jsonPrimitive?.content)
            assertEquals("https://www.youtube.com/watch?v=med7200", videoBlock["uri"]?.jsonPrimitive?.content)
            assertFalse(videoBlock.containsKey("mime_type"))
            assertEquals(
                listOf("url_context", "google_search"),
                obj["tools"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content },
            )
            assertTrue(submitted.contains("幫我看這支片在講什麼"))
        }

    @Test
    fun `negative boundary omits videoUri and never invokes agentic polling when video duration exceeds 7200 seconds`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            var capturedBody: String? = null
            val responseJson =
                """{"id": "int-vid-cap", "status": "completed", "output_text": "這場直播兩小時多太長了 不過留言說重點在後半段"}"""

            val mockEngine =
                MockEngine { request ->
                    recordedUrls.add(request.url.toString())
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 這場直播在講啥\n標題：3 Hour Conference\n留言：\n1. 重點在後半段",
                        videoUri = "https://www.youtube.com/watch?v=tooLong",
                        videoDurationSeconds = 7201L,
                        isDirectMention = true,
                    ),
                )

            assertEquals("這場直播兩小時多太長了 不過留言說重點在後半段", reply)
            assertEquals(1, recordedUrls.size)
            assertTrue(recordedUrls.single().contains("/v1beta/interactions"))
            val obj = json.parseToJsonElement(checkNotNull(capturedBody)).jsonObject
            assertEquals(false, obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            assertFalse(capturedBody.orEmpty().contains("\"type\":\"video\""))
            assertTrue(capturedBody.orEmpty().contains("3 Hour Conference"))
        }

    @Test
    fun `negative boundary performs zero layered retries and logs error when sync interaction fails with images and tools`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = """{"error":{"code":400,"message":"Failed to fetch CDN image"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val generator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true, urlContextEnabled = true),
                        httpClient = createTestClient(mockEngine),
                    )

                val reply =
                    generator.generateReply(
                        ChatterInput(
                            transcript = "[Alice]: 看看這張圖 https://example.com/a",
                            imageAttachments =
                                listOf(
                                    ChatImageAttachment(
                                        url = "https://cdn.discordapp.com/attachments/1/2/expired.png",
                                        mimeType = "image/png",
                                        filename = "expired.png",
                                    ),
                                ),
                            webUrls = listOf("https://example.com/a"),
                        ),
                    )

                assertNull(reply)
                assertEquals(1, requestCount, "ChatterGenerator must NOT perform layered no-search or no-media retries")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `negative boundary performs zero retries and logs error when direct-mention agentic polling fails`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val recordedUrls = mutableListOf<String>()
                val mockEngine =
                    MockEngine { request ->
                        recordedUrls.add(request.url.toString())
                        respond(
                            content = """{"error":{"code":429,"message":"Rate limit on interactions"}}""",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val generator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true),
                        httpClient = createTestClient(mockEngine),
                        pollingDelayMs = 1L,
                        maxPollingAttempts = 2,
                    )

                val reply =
                    generator.generateReply(
                        ChatterInput(
                            transcript = "[Alice]: 影片跟截圖都在這",
                            videoUri = "https://www.youtube.com/watch?v=longVid",
                            videoDurationSeconds = 900L,
                            isDirectMention = true,
                        ),
                    )

                assertNull(reply)
                assertEquals(1, recordedUrls.size, "Agentic interaction failure must fail cleanly with zero fallback retries")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `logs error on unsafe url_context_result while preserving valid sanitized model reply`() =
        runTest {
            val logger = LoggerFactory.getLogger(luna.core.ai.GeminiInteractionClient::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val responseJson =
                    """
                    {
                      "id": "int-unsafe-1",
                      "status": "completed",
                      "steps": [
                        {
                          "type": "url_context_result",
                          "result": [
                            {"retrieved_url": "https://unsafe.example.com/bad", "status": "unsafe"}
                          ]
                        },
                        {
                          "type": "model_output",
                          "content": [
                            {"type": "text", "text": "這個網址好像打不開 不過剛剛看話題蠻有趣的 😂"}
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

                val generator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key", urlContextEnabled = true),
                        httpClient = createTestClient(mockEngine),
                    )

                val reply =
                    generator.generateReply(
                        ChatterInput(
                            transcript = "[Alice]: 看看 https://unsafe.example.com/bad",
                            webUrls = listOf("https://unsafe.example.com/bad"),
                        ),
                    )

                assertEquals("這個網址好像打不開 不過剛剛看話題蠻有趣的", reply)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.ERROR &&
                            it.formattedMessage.contains("https://unsafe.example.com/bad") &&
                            it.formattedMessage.contains("unsafe")
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `enforces 2000-char Discord bound and handles sanitized-to-blank responses cleanly`() =
        runTest {
            val longText = "a".repeat(2500)
            val longGen =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient =
                        createTestClient(
                            MockEngine {
                                respond(
                                    content = """{"id":"int-long","status":"completed","output_text":"$longText"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                        ),
                )

            val boundedReply = assertNotNull(longGen.generateReply("[Alice]: tell me everything"))
            assertEquals(2000, boundedReply.length)
            assertTrue(boundedReply.endsWith("..."))

            val logger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)
            try {
                val blankGen =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key"),
                        httpClient =
                            createTestClient(
                                MockEngine {
                                    respond(
                                        content = """{"id":"int-boilerplate","status":"completed","output_text":"身為一個AI語言模型，"}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                },
                            ),
                    )
                assertNull(blankGen.generateReply("[Alice]: hi"))
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
