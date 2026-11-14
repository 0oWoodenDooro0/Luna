package luna.core.ai

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
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeminiInteractionClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    private fun createClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    private fun agenticVideoRequest(
        model: String,
        videoUri: String,
        promptText: String,
    ): GeminiInteractionRequest =
        GeminiInteractionRequest(
            model = model,
            input =
                GeminiInteractionClient.buildMultimodalInputs(
                    promptText = promptText,
                    videoUri = videoUri,
                    videoMimeType = "video/mp4",
                    videoProcessingMode = VideoProcessingMode.AGENTIC,
                ),
            background = true,
        )

    @Test
    fun `submits agentic video interaction and polls until completion returning sanitized output`() =
        runTest {
            var pollCount = 0
            var recordedPostBody: String? = null

            val mockEngine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/v1beta/interactions") && request.method.value == "POST" -> {
                            assertEquals("test-api-key", request.url.parameters["key"])
                            recordedPostBody = request.body.toByteArray().decodeToString()
                            respond(
                                content = """{"id": "int-abc", "status": "in_progress"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        request.url.encodedPath.endsWith("/v1beta/interactions/int-abc") && request.method.value == "GET" -> {
                            pollCount++
                            if (pollCount == 1) {
                                respond(
                                    content = """{"id": "int-abc", "status": "in_progress"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            } else {
                                respond(
                                    content = """{"id": "int-abc", "status": "completed", "output_text": "  Agentic video result 🚀  "}""",
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

            val client =
                GeminiInteractionClient(
                    httpClient = createClient(mockEngine),
                    pollingDelayMs = 1L,
                    maxPollingAttempts = 5,
                )

            val result =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
                    apiKey = "test-api-key",
                    request =
                        agenticVideoRequest(
                            model = "models/gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=testVideo",
                            promptText = "Summarize this video",
                        ),
                    traceContext = InteractionTraceContext(stage = AiAttemptStage.AGENTIC_VIDEO),
                )

            assertIs<InteractionExecutionResult.Success>(result)
            assertEquals("Agentic video result", result.text)
            assertEquals(2, pollCount)

            val postBody = kotlin.test.assertNotNull(recordedPostBody)
            assertTrue(postBody.contains("\"model\":\"gemini-3.5-flash-lite\""))
            assertTrue(postBody.contains("\"processing\":\"agentic\""))
            assertTrue(postBody.contains("https://www.youtube.com/watch?v=testVideo"))
            val decoded = json.decodeFromString<GeminiInteractionRequest>(postBody)
            assertTrue(decoded.background)
            assertEquals(2, decoded.input.size)
            assertEquals("Summarize this video", decoded.input[1].text)
        }

    @Test
    fun `extracts output text from model_output steps when output_text is absent`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-steps", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content =
                                """
                                {
                                  "id": "int-steps",
                                  "status": "completed",
                                  "steps": [
                                    {"type": "thought", "content": [{"type": "text", "text": "internal thought"}]},
                                    {"type": "model_output", "content": [{"type": "text", "text": "Extracted step output"}]}
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val client = GeminiInteractionClient(httpClient = createClient(mockEngine), pollingDelayMs = 1L)
            val result =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=steps",
                            promptText = "Prompt",
                        ),
                )

            assertIs<InteractionExecutionResult.Success>(result)
            assertEquals("Extracted step output", result.text)
        }

    @Test
    fun `returns RateLimitExceeded with Retry-After on HTTP 429 during submission and polling`() =
        runTest {
            val submit429Engine =
                MockEngine {
                    respond(
                        content = """{"error": {"code": 429, "message": "Rate limited"}}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.RetryAfter, "45"),
                    )
                }

            val submitClient = GeminiInteractionClient(httpClient = createClient(submit429Engine), pollingDelayMs = 1L)
            val submitResult =
                submitClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=v1",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.RateLimitExceeded>(submitResult)
            assertEquals(45L, submitResult.retryAfterSeconds)

            val poll429Engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-429", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = "Rate limited",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.RetryAfter, "12"),
                        )
                    }
                }

            val pollClient = GeminiInteractionClient(httpClient = createClient(poll429Engine), pollingDelayMs = 1L)
            val pollResult =
                pollClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=v2",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.RateLimitExceeded>(pollResult)
            assertEquals(12L, pollResult.retryAfterSeconds)
        }

    @Test
    fun `returns ApiError on non-2xx HTTP status during submission and polling`() =
        runTest {
            val submitErrorEngine =
                MockEngine {
                    respond(
                        content = """{"error": "Bad request"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val submitClient = GeminiInteractionClient(httpClient = createClient(submitErrorEngine), pollingDelayMs = 1L)
            val submitResult =
                submitClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=v1",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.ApiError>(submitResult)
            assertEquals(400, submitResult.statusCode)

            val pollErrorEngine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-500", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = "Server error",
                            status = HttpStatusCode.InternalServerError,
                        )
                    }
                }

            val pollClient = GeminiInteractionClient(httpClient = createClient(pollErrorEngine), pollingDelayMs = 1L)
            val pollResult =
                pollClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=v2",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.ApiError>(pollResult)
            assertEquals(500, pollResult.statusCode)
        }

    @Test
    fun `returns Timeout on network timeout exception and when polling exceeds max attempts`() =
        runTest {
            val timeoutExceptionEngine =
                MockEngine {
                    throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30_000L)
                }
            val timeoutClient = GeminiInteractionClient(httpClient = createClient(timeoutExceptionEngine), pollingDelayMs = 1L)
            val exceptionResult =
                timeoutClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=v1",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.Timeout>(exceptionResult)

            val stuckEngine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-stuck", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"id": "int-stuck", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            val stuckClient =
                GeminiInteractionClient(
                    httpClient = createClient(stuckEngine),
                    pollingDelayMs = 1L,
                    maxPollingAttempts = 2,
                )
            val exhaustedResult =
                stuckClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=v2",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.Timeout>(exhaustedResult)
            assertTrue(exhaustedResult.message.contains("2 attempts"))
        }

    @Test
    fun `returns EmptyOutput when completed interaction has blank or emoji-only output`() =
        runTest {
            val blankEngine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-empty", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"id": "int-empty", "status": "completed", "output_text": "   📝🎉  "}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val client = GeminiInteractionClient(httpClient = createClient(blankEngine), pollingDelayMs = 1L)
            val result =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=empty",
                            promptText = "Prompt",
                        ),
                )

            assertIs<InteractionExecutionResult.Error.EmptyOutput>(result)
            assertTrue(result.message.contains("empty or sanitized to blank", ignoreCase = true))
        }

    @Test
    fun `returns Generic error when interaction fails or returns no interaction id or blank inputs`() =
        runTest {
            val failedEngine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-fail", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"id": "int-fail", "status": "failed", "error": {"message": "Video inaccessible"}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            val failClient = GeminiInteractionClient(httpClient = createClient(failedEngine), pollingDelayMs = 1L)
            val failedResult =
                failClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=fail",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.Generic>(failedResult)
            assertEquals("Video inaccessible", failedResult.message)

            val noIdEngine =
                MockEngine {
                    respond(
                        content = """{"status": "in_progress"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val noIdClient = GeminiInteractionClient(httpClient = createClient(noIdEngine), pollingDelayMs = 1L)
            val noIdResult =
                noIdClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=noid",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.Generic>(noIdResult)

            val blankKeyResult =
                noIdClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "   ",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=noid",
                            promptText = "Prompt",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.Generic>(blankKeyResult)
            assertEquals(AiTraceFailureCategory.NOT_CONFIGURED, blankKeyResult.failureCategory)

            val blankInputResult =
                noIdClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = " ",
                            promptText = " ",
                        ),
                )
            assertIs<InteractionExecutionResult.Error.Generic>(blankInputResult)
            assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, blankInputResult.failureCategory)
        }

    @Test
    fun `agentic video interact omits mime_type for YouTube video URIs and sends no Api-Revision header`() =
        runTest {
            var recordedPostBody: String? = null
            var hasApiRevision = false

            val engine =
                MockEngine { request ->
                    if (request.headers.contains("Api-Revision")) {
                        hasApiRevision = true
                    }
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        recordedPostBody = request.body.toByteArray().decodeToString()
                        respond(
                            content = """{"id": "int-yt", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"id": "int-yt", "status": "completed", "output_text": "Video summary"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)
            val result =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        agenticVideoRequest(
                            model = "gemini-3.5-flash-lite",
                            videoUri = "https://youtu.be/dQw4w9WgXcQ",
                            promptText = "Summarize",
                        ),
                )

            assertIs<InteractionExecutionResult.Success>(result)
            assertFalse(hasApiRevision, "Must never send removed Api-Revision header")
            val bodyJson = Json.parseToJsonElement(assertNotNull(recordedPostBody)) as JsonObject
            val inputs = bodyJson["input"] as JsonArray
            val videoBlock = inputs[0] as JsonObject
            assertEquals("video", (videoBlock["type"] as JsonPrimitive).content)
            assertFalse(videoBlock.containsKey("mime_type"), "YouTube video input must omit mime_type")
        }

    @Test
    fun `interact executes sync request with multimodal ordering tools and filtered url_citations`() =
        runTest {
            var recordedBody: String? = null
            var requestCount = 0

            val engine =
                MockEngine { request ->
                    requestCount++
                    assertFalse(request.headers.contains("Api-Revision"))
                    assertEquals("POST", request.method.value)
                    assertTrue(request.url.encodedPath.endsWith("/v1beta/interactions"))
                    recordedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content =
                            """
                            {
                              "id": "int-sync-1",
                              "status": "completed",
                              "usage": {
                                "input_tokens": 210,
                                "output_tokens": 55,
                                "total_tokens": 280,
                                "thoughts_tokens": 10,
                                "tool_use_input_tokens": 5
                              },
                              "steps": [
                                {"type": "url_context_call", "id": "uc-1", "arguments": {"urls": ["https://example.com/article"]}},
                                {
                                  "type": "url_context_result",
                                  "call_id": "uc-1",
                                  "result": [{"retrieved_url": "https://example.com/article/", "status": "success"}]
                                },
                                {"type": "google_search_call", "id": "gs-1", "arguments": {"queries": ["search query 1"]}},
                                {"type": "google_search_result", "call_id": "gs-1", "result": [{"title": "Doc", "url": "https://docs.example.org/guide"}]},
                                {
                                  "type": "model_output",
                                  "content": [
                                    {
                                      "type": "text",
                                      "text": "Clean summary 🚀",
                                      "annotations": [
                                        {"type": "url_citation", "url": "http://example.com/article", "title": "Original URL"},
                                        {"type": "url_citation", "url": "https://docs.example.org/guide", "title": "Guide"},
                                        {"type": "url_citation", "url": "https://docs.example.org/guide/", "title": "Guide Dup"},
                                        {"type": "url_citation", "url": "https://news.example.org/1", "title": "News 1"},
                                        {"type": "url_citation", "url": "https://news.example.org/2", "title": "News 2"},
                                        {"type": "url_citation", "url": "https://news.example.org/3", "title": "News 3"}
                                      ]
                                    }
                                  ]
                                }
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)
            val result =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        GeminiInteractionRequest(
                            model = "gemini-3.5-flash-lite",
                            systemInstruction = "System prompt",
                            input =
                                GeminiInteractionClient.buildMultimodalInputs(
                                    promptText = "User prompt",
                                    imageAttachments =
                                        listOf(
                                            ChatImageAttachment(
                                                url = "https://cdn.discordapp.com/1.png",
                                                mimeType = "image/png",
                                            ),
                                        ),
                                    videoUri = "https://www.youtube.com/watch?v=sync123",
                                    videoProcessingMode = VideoProcessingMode.STATIC,
                                ),
                            tools = listOf(GeminiInteractionTools.URL_CONTEXT, GeminiInteractionTools.GOOGLE_SEARCH),
                            generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = 1024),
                            background = false,
                        ),
                    traceContext =
                        InteractionTraceContext(
                            webUrlCount = 1,
                            inputUrls = listOf("https://example.com/article"),
                        ),
                )

            assertEquals(1, requestCount)
            assertIs<InteractionExecutionResult.Success>(result)
            assertEquals("int-sync-1", result.interactionId)
            assertEquals("Clean summary", result.text)
            assertEquals(listOf("search query 1"), result.googleSearchQueries)
            assertEquals(
                listOf(UrlContextResultEntry(retrievedUrl = "https://example.com/article/", status = "success")),
                result.urlContextResults,
            )
            assertEquals(
                listOf(
                    GroundingCitation(title = "Guide", url = "https://docs.example.org/guide"),
                    GroundingCitation(title = "News 1", url = "https://news.example.org/1"),
                    GroundingCitation(title = "News 2", url = "https://news.example.org/2"),
                ),
                result.citations,
            )
            assertEquals(280, result.usageMetadata?.totalTokenCount)
            assertEquals(10, result.usageMetadata?.thoughtsTokenCount)
            assertEquals(5, result.usageMetadata?.toolUseInputTokens)

            val payload = Json.parseToJsonElement(assertNotNull(recordedBody)) as JsonObject
            assertEquals("false", payload["background"].toString())
            val inputs = payload["input"] as JsonArray
            assertEquals(3, inputs.size)
            assertEquals("video", ((inputs[0] as JsonObject)["type"] as JsonPrimitive).content)
            assertFalse((inputs[0] as JsonObject).containsKey("mime_type"))
            assertEquals("image", ((inputs[1] as JsonObject)["type"] as JsonPrimitive).content)
            assertEquals("text", ((inputs[2] as JsonObject)["type"] as JsonPrimitive).content)
        }

    @Test
    fun `supports stateful previous_interaction_id and function_result continuation`() =
        runTest {
            var turn = 0
            var secondRequestBody: String? = null

            val engine =
                MockEngine { request ->
                    turn++
                    val body = request.body.toByteArray().decodeToString()
                    if (turn == 1) {
                        respond(
                            content =
                                """
                                {
                                  "id": "int-turn-1",
                                  "status": "requires_action",
                                  "steps": [
                                    {"type": "function_call", "id": "call_abc", "name": "fetch_earlier_messages", "arguments": {"reason": "need context"}}
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        secondRequestBody = body
                        respond(
                            content =
                                """
                                {
                                  "id": "int-turn-2",
                                  "status": "completed",
                                  "steps": [
                                    {"type": "model_output", "content": [{"type": "text", "text": "Final answer with earlier context"}]}
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)
            val tools = listOf(GeminiInteractionTools.URL_CONTEXT, GeminiInteractionTools.GOOGLE_SEARCH, GeminiInteractionTools.FETCH_EARLIER_MESSAGES)

            val firstResult =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        GeminiInteractionRequest(
                            model = "gemini-3.5-flash-lite",
                            systemInstruction = "Sys",
                            input = listOf(GeminiInteractionInput.text("Check earlier messages")),
                            tools = tools,
                            generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = 1024),
                            background = false,
                        ),
                )

            assertIs<InteractionExecutionResult.FunctionCall>(firstResult)
            assertEquals("int-turn-1", firstResult.interactionId)
            assertEquals(1, firstResult.functionCalls.size)
            assertEquals("call_abc", firstResult.functionCalls[0].id)
            assertEquals("fetch_earlier_messages", firstResult.functionCalls[0].name)

            val secondResult =
                client.continueInteraction(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    model = "gemini-3.5-flash-lite",
                    previousInteractionId = firstResult.interactionId,
                    functionResults =
                        listOf(
                            GeminiInteractionInput.functionResult(
                                name = "fetch_earlier_messages",
                                callId = firstResult.functionCalls[0].id,
                                result = "Alice: Context message",
                            ),
                        ),
                    systemInstruction = "Sys",
                    tools = tools,
                    maxOutputTokens = 1024,
                )

            assertIs<InteractionExecutionResult.Success>(secondResult)
            assertEquals("Final answer with earlier context", secondResult.text)

            val contJson = Json.parseToJsonElement(assertNotNull(secondRequestBody)) as JsonObject
            assertEquals("int-turn-1", (contJson["previous_interaction_id"] as JsonPrimitive).content)
            assertEquals(3, (contJson["tools"] as JsonArray).size)
            val contInput = (contJson["input"] as JsonArray)[0] as JsonObject
            assertEquals("function_result", (contInput["type"] as JsonPrimitive).content)
            assertEquals("call_abc", (contInput["call_id"] as JsonPrimitive).content)
            assertEquals("fetch_earlier_messages", (contInput["name"] as JsonPrimitive).content)
        }

    @Test
    fun `logs error on non-success or unsafe url_context_result while preserving valid model_output`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "id": "int-unsafe-1",
                              "status": "completed",
                              "steps": [
                                {
                                  "type": "url_context_result",
                                  "call_id": "uc-1",
                                  "result": [
                                    {"retrieved_url": "https://ok.example.com", "status": "success"},
                                    {"retrieved_url": "https://evil.example.com/phish", "status": "unsafe"}
                                  ]
                                },
                                {
                                  "type": "model_output",
                                  "content": [{"type": "text", "text": "Partial summary from safe sources"}]
                                }
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val interactionLogger = LoggerFactory.getLogger(GeminiInteractionClient::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            interactionLogger.addAppender(appender)

            try {
                val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)
                val result =
                    client.interact(
                        baseUrl = "https://generativelanguage.googleapis.com",
                        apiKey = "test-key",
                        request =
                            GeminiInteractionRequest(
                                model = "gemini-3.5-flash-lite",
                                input = listOf(GeminiInteractionInput.text("Summarize links")),
                                tools = listOf(GeminiInteractionTools.URL_CONTEXT),
                                background = false,
                            ),
                    )

                assertIs<InteractionExecutionResult.Success>(result)
                assertEquals("Partial summary from safe sources", result.text)
                assertEquals(2, result.urlContextResults.size)

                val errorLogs = appender.list.filter { it.level == Level.ERROR }
                assertTrue(errorLogs.isNotEmpty(), "Non-success url_context_result must emit logger.error")
                val msg = errorLogs.joinToString("\n") { it.formattedMessage }
                assertTrue(msg.contains("https://evil.example.com/phish"))
                assertTrue(msg.contains("unsafe"))
                assertTrue(msg.contains("int-unsafe-1"))
            } finally {
                interactionLogger.detachAppender(appender)
            }
        }

    @Test
    fun `does not retry on HTTP error during sync interaction`() =
        runTest {
            var requestCount = 0
            val engine =
                MockEngine {
                    requestCount++
                    respond(
                        content = """{"error": {"code": 500, "message": "Internal error"}}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)
            val result =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        GeminiInteractionRequest(
                            model = "gemini-3.5-flash-lite",
                            input = listOf(GeminiInteractionInput.text("Hello")),
                            tools = listOf(GeminiInteractionTools.URL_CONTEXT, GeminiInteractionTools.GOOGLE_SEARCH),
                            background = false,
                        ),
                )

            assertIs<InteractionExecutionResult.Error.ApiError>(result)
            assertEquals(500, result.statusCode)
            assertEquals(1, requestCount, "Must execute strictly 1 HTTP call with zero fallback retries")
        }

    @Test
    fun `interact retains mime_type for non-YouTube video and polls background tasks when background is true`() =
        runTest {
            var recordedPostBody: String? = null
            var pollCount = 0
            var hasApiRevision = false

            val engine =
                MockEngine { request ->
                    if (request.headers.contains("Api-Revision")) {
                        hasApiRevision = true
                    }
                    if (request.method.value == "POST") {
                        recordedPostBody = request.body.toByteArray().decodeToString()
                        respond(
                            content = """{"id": "int-bg-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        pollCount++
                        respond(
                            content =
                                """
                                {
                                  "id": "int-bg-1",
                                  "status": "completed",
                                  "steps": [
                                    {"type": "model_output", "content": [{"type": "text", "text": "Async non-YouTube summary"}]}
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L)
            val result =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        GeminiInteractionRequest(
                            model = "gemini-3.5-flash-lite",
                            input =
                                GeminiInteractionClient.buildMultimodalInputs(
                                    promptText = "Describe this clip",
                                    videoUri = "https://cdn.example.com/media/clip.mp4",
                                    videoMimeType = "video/mp4",
                                    videoProcessingMode = VideoProcessingMode.AGENTIC,
                                ),
                            background = true,
                        ),
                )

            assertIs<InteractionExecutionResult.Success>(result)
            assertEquals("Async non-YouTube summary", result.text)
            assertEquals(1, pollCount)
            assertFalse(hasApiRevision)

            val payload = Json.parseToJsonElement(assertNotNull(recordedPostBody)) as JsonObject
            assertEquals("true", payload["background"].toString())
            val videoBlock = (payload["input"] as JsonArray)[0] as JsonObject
            assertEquals("video/mp4", (videoBlock["mime_type"] as JsonPrimitive).content)
            assertEquals("agentic", (videoBlock["processing"] as JsonPrimitive).content)
        }

    @Test
    fun `returns SafetyBlock and PARSE_ERROR on safety finish_reason and malformed JSON`() =
        runTest {
            val safetyEngine =
                MockEngine {
                    respond(
                        content = """{"id": "int-safe", "status": "completed", "finish_reason": "SAFETY", "steps": []}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val safetyClient = GeminiInteractionClient(httpClient = createClient(safetyEngine), pollingDelayMs = 1L)
            val safetyResult =
                safetyClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        GeminiInteractionRequest(
                            model = "gemini-3.5-flash-lite",
                            input = listOf(GeminiInteractionInput.text("Unsafe prompt")),
                            background = false,
                        ),
                )
            assertIs<InteractionExecutionResult.Error.SafetyBlock>(safetyResult)
            assertEquals(AiTraceFailureCategory.SAFETY_BLOCK, safetyResult.failureCategory)

            val malformedEngine =
                MockEngine {
                    respond(
                        content = """{not valid json""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val parseClient = GeminiInteractionClient(httpClient = createClient(malformedEngine), pollingDelayMs = 1L)
            val parseResult =
                parseClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        GeminiInteractionRequest(
                            model = "gemini-3.5-flash-lite",
                            input = listOf(GeminiInteractionInput.text("Hello")),
                            background = false,
                        ),
                )
            assertIs<InteractionExecutionResult.Error.Generic>(parseResult)
            assertEquals(AiTraceFailureCategory.PARSE_ERROR, parseResult.failureCategory)
        }
}
