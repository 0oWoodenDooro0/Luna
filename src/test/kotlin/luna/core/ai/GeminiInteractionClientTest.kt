package luna.core.ai

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
import kotlin.test.assertIs
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
}
