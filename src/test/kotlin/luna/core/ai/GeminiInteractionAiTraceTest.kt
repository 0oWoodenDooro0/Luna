package luna.core.ai

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiInteractionAiTraceTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-14T16:00:00Z"), ZoneOffset.UTC)

    private val clientJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    private fun createClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(clientJson)
            }
        }

    private fun createRecorder(
        tempDir: Path,
        apiKey: String = "secret-interaction-key",
    ): Pair<JsonlAiTraceRecorder, Path> {
        val traceDir = tempDir.resolve("ai-traces")
        val recorder =
            JsonlAiTraceRecorder(
                traceDir = traceDir,
                redactor = AiTraceRedactor(listOf(apiKey)),
                clock = fixedClock,
            )
        return recorder to traceDir.resolve("ai-2026-11-14.jsonl")
    }

    private fun readTraces(traceFile: Path): List<AiTraceRecord> {
        if (!Files.exists(traceFile)) return emptyList()
        return Files
            .readAllLines(traceFile)
            .filter { it.isNotBlank() }
            .map { AiTraceJson.decodeFromString(AiTraceRecord.serializer(), it) }
    }

    private fun createInteractionClient(
        engine: MockEngine,
        recorder: JsonlAiTraceRecorder,
        maxPollingAttempts: Int = 5,
    ): GeminiInteractionClient =
        GeminiInteractionClient(
            httpClient = createClient(engine),
            pollingDelayMs = 1L,
            maxPollingAttempts = maxPollingAttempts,
            traceRecorder = recorder,
        )

    private fun agenticVideoRequest(
        videoUri: String,
        model: String = "gemini-3.5-flash-lite",
        promptText: String = "Prompt",
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
    fun `standalone submission and multi-poll completion records single collapsed AGENTIC_VIDEO attempt with redacted endpoint and no per-poll log spam`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "super-secret-interaction-key-999"
        val (recorder, traceFile) = createRecorder(tempDir, apiKey)
        var pollCount = 0
        val completedJson =
            """{"id": "int-multi-1", "status": "completed", "output_text": "Detailed long video breakdown 🚀"}"""

        val mockEngine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/v1beta/interactions") && request.method.value == "POST" -> {
                        respond(
                            content = """{"id": "int-multi-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    request.url.encodedPath.endsWith("/v1beta/interactions/int-multi-1") && request.method.value == "GET" -> {
                        pollCount++
                        val body =
                            if (pollCount < 3) {
                                """{"id": "int-multi-1", "status": "in_progress"}"""
                            } else {
                                completedJson
                            }
                        respond(
                            content = body,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        respond("Not found", status = HttpStatusCode.NotFound)
                    }
                }
            }

        val interactionLogger = LoggerFactory.getLogger(GeminiInteractionClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        interactionLogger.addAppender(appender)

        try {
            val client = createInteractionClient(mockEngine, recorder)

            val result =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
                    apiKey = apiKey,
                    request =
                        agenticVideoRequest(
                            model = "models/gemini-3.5-flash-lite",
                            videoUri = "https://www.youtube.com/watch?v=longVid123",
                            promptText = "Summarize this 20 minute talk",
                        ),
                    traceContext =
                        InteractionTraceContext(
                            videoDurationSeconds = 1200L,
                            stage = AiAttemptStage.AGENTIC_VIDEO,
                        ),
                )
            recorder.flushAndClose()

            assertIs<InteractionExecutionResult.Success>(result)
            assertEquals("Detailed long video breakdown", result.text)
            assertEquals(3, pollCount)
            assertTrue(
                appender.list.isEmpty(),
                "Intermediate pending/in_progress poll iterations must not emit log spam",
            )

            val traces = readTraces(traceFile)
            assertEquals(1, traces.size)
            val trace = traces.single()
            assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
            assertFalse(trace.degraded)
            assertNull(trace.failureCategory)
            assertEquals("Detailed long video breakdown", trace.finalOutput)
            assertEquals(1, trace.attemptCount)
            assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)

            val attempt = trace.attempts.single()
            assertEquals(1, attempt.attemptIndex)
            assertEquals(AiAttemptStage.AGENTIC_VIDEO, attempt.stage)
            assertEquals("gemini-3.5-flash-lite", attempt.model)
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/interactions?key=******",
                attempt.endpoint,
            )
            assertEquals(3, attempt.pollingAttempts)
            assertEquals(200, attempt.httpStatus)
            assertEquals(completedJson, attempt.responseRaw)
            assertEquals(AiAttemptOutcome.SUCCESS, attempt.parsedOutcome)
            assertNull(attempt.errorMessage)
            assertTrue(attempt.latencyMs >= 0L)

            val req = assertNotNull(attempt.request)
            assertTrue(req.hasVideo)
            assertEquals(1200L, req.videoDurationSeconds)
            assertEquals("Summarize this 20 minute talk".length, req.transcriptLength)
            assertFalse(req.hasImages)
            assertEquals(0, req.imageCount)
            val payloadObj = assertNotNull(req.payload?.jsonObject)
            assertEquals("gemini-3.5-flash-lite", payloadObj["model"]?.jsonPrimitive?.content)
            assertEquals(2, payloadObj["input"]?.jsonArray?.size)

            val rawJsonLine = Files.readAllLines(traceFile).single()
            assertFalse(rawJsonLine.contains(apiKey), "API key must never appear in persisted JSONL")
        } finally {
            interactionLogger.detachAppender(appender)
        }
    }

    @Test
    fun `failed interaction status on poll records collapsed ERROR attempt with pollingAttempts and raw body`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var pollCount = 0
        val failedJson = """{"id": "int-fail-1", "status": "failed", "error": {"message": "Video inaccessible"}}"""

        val mockEngine =
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/interactions")) {
                    respond(
                        content = """{"id": "int-fail-1", "status": "in_progress"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    pollCount++
                    val body =
                        if (pollCount == 1) {
                            """{"id": "int-fail-1", "status": "in_progress"}"""
                        } else {
                            failedJson
                        }
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }

        val client = createInteractionClient(mockEngine, recorder)

        val result =
            client.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "secret-interaction-key",
                request = agenticVideoRequest(videoUri = "https://www.youtube.com/watch?v=failVid", promptText = "Analyze video"),
                traceContext =
                    InteractionTraceContext(
                        videoDurationSeconds = 900L,
                        stage = AiAttemptStage.AGENTIC_VIDEO,
                    ),
            )
        recorder.flushAndClose()

        assertIs<InteractionExecutionResult.Error.Generic>(result)
        val trace = readTraces(traceFile).single()
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertEquals(AiTraceFailureCategory.UNEXPECTED_ERROR, trace.failureCategory)
        assertEquals(1, trace.attemptCount)

        val attempt = trace.attempts.single()
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, attempt.stage)
        assertEquals(2, attempt.pollingAttempts)
        assertEquals(200, attempt.httpStatus)
        assertEquals(failedJson, attempt.responseRaw)
        assertEquals(AiAttemptOutcome.ERROR, attempt.parsedOutcome)
        assertEquals("Video inaccessible", attempt.errorMessage)
    }

    @Test
    fun `HTTP 429 on submission and HTTP 500 on polling record accurate httpStatus pollingAttempts and failureCategory`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        val submit429Body = """{"error": {"code": 429, "message": "Quota exceeded"}}"""
        val poll500Body = "Upstream interaction poll crash"

        val submit429Client =
            createInteractionClient(
                MockEngine {
                    respond(
                        content = submit429Body,
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                recorder,
            )
        val res429 =
            submit429Client.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "secret-interaction-key",
                request = agenticVideoRequest(videoUri = "https://www.youtube.com/watch?v=v429"),
            )
        assertIs<InteractionExecutionResult.Error.RateLimitExceeded>(res429)

        var pollCall = 0
        val poll500Client =
            createInteractionClient(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-500", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        pollCall++
                        if (pollCall == 1) {
                            respond(
                                content = """{"id": "int-500", "status": "in_progress"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond(
                                content = poll500Body,
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                            )
                        }
                    }
                },
                recorder,
            )
        val res500 =
            poll500Client.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "secret-interaction-key",
                request = agenticVideoRequest(videoUri = "https://www.youtube.com/watch?v=v500"),
            )
        assertIs<InteractionExecutionResult.Error.ApiError>(res500)
        recorder.flushAndClose()

        val traces = readTraces(traceFile)
        assertEquals(2, traces.size)

        val trace429 = traces[0]
        assertEquals(AiTraceOutcome.FAILED, trace429.outcome)
        assertEquals(AiTraceFailureCategory.RATE_LIMIT, trace429.failureCategory)
        assertEquals(0, trace429.attempts.single().pollingAttempts)
        assertEquals(429, trace429.attempts.single().httpStatus)
        assertEquals(submit429Body, trace429.attempts.single().responseRaw)
        assertEquals(AiAttemptOutcome.ERROR, trace429.attempts.single().parsedOutcome)

        val trace500 = traces[1]
        assertEquals(AiTraceOutcome.FAILED, trace500.outcome)
        assertEquals(AiTraceFailureCategory.HTTP_ERROR, trace500.failureCategory)
        assertEquals(2, trace500.attempts.single().pollingAttempts)
        assertEquals(500, trace500.attempts.single().httpStatus)
        assertEquals(poll500Body, trace500.attempts.single().responseRaw)
        assertEquals(AiAttemptOutcome.ERROR, trace500.attempts.single().parsedOutcome)
    }

    @Test
    fun `polling exhaustion and timeout exception record TIMEOUT with exact pollingAttempts and redacted error`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-timeout-key-456"
        val (recorder, traceFile) = createRecorder(tempDir, apiKey)
        val pendingJson = """{"id": "int-stuck", "status": "in_progress"}"""

        val exhaustedClient =
            createInteractionClient(
                MockEngine {
                    respond(
                        content = pendingJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                recorder,
                maxPollingAttempts = 3,
            )
        val exhaustedRes =
            exhaustedClient.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = apiKey,
                request = agenticVideoRequest(videoUri = "https://www.youtube.com/watch?v=stuck"),
            )
        assertIs<InteractionExecutionResult.Error.Timeout>(exhaustedRes)

        var pollNum = 0
        val networkTimeoutClient =
            createInteractionClient(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/interactions")) {
                        respond(
                            content = """{"id": "int-net-to", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        pollNum++
                        if (pollNum == 1) {
                            respond(
                                content = """{"id": "int-net-to", "status": "in_progress"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            throw io.ktor.client.plugins.HttpRequestTimeoutException(
                                "https://generativelanguage.googleapis.com/v1beta/interactions/int-net-to?key=$apiKey",
                                30_000L,
                            )
                        }
                    }
                },
                recorder,
            )
        val networkTimeoutRes =
            networkTimeoutClient.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = apiKey,
                request = agenticVideoRequest(videoUri = "https://www.youtube.com/watch?v=netto"),
            )
        assertIs<InteractionExecutionResult.Error.Timeout>(networkTimeoutRes)
        recorder.flushAndClose()

        val traces = readTraces(traceFile)
        assertEquals(2, traces.size)

        val exhaustedTrace = traces[0]
        assertEquals(AiTraceOutcome.FAILED, exhaustedTrace.outcome)
        assertEquals(AiTraceFailureCategory.TIMEOUT, exhaustedTrace.failureCategory)
        assertEquals(3, exhaustedTrace.attempts.single().pollingAttempts)
        assertEquals(200, exhaustedTrace.attempts.single().httpStatus)
        assertEquals(pendingJson, exhaustedTrace.attempts.single().responseRaw)

        val netTrace = traces[1]
        assertEquals(AiTraceOutcome.FAILED, netTrace.outcome)
        assertEquals(AiTraceFailureCategory.TIMEOUT, netTrace.failureCategory)
        assertEquals(2, netTrace.attempts.single().pollingAttempts)
        assertNull(netTrace.attempts.single().httpStatus)
        assertNull(netTrace.attempts.single().responseRaw)
        assertFalse(
            netTrace.attempts
                .single()
                .errorMessage
                ?.contains(apiKey) == true,
        )

        val serialized = Files.readString(traceFile)
        assertFalse(serialized.contains(apiKey))
    }

    @Test
    fun `pre-HTTP aborts record NOT_CONFIGURED and CONTEXT_ERROR cleanly on standalone and outer sessions`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        val client = createInteractionClient(MockEngine { error("HTTP must not be called on pre-HTTP abort") }, recorder)

        // 1. Standalone blank apiKey -> NOT_CONFIGURED
        val blankKeyRes =
            client.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = " ",
                request = agenticVideoRequest(videoUri = "https://www.youtube.com/watch?v=abc"),
            )
        assertIs<InteractionExecutionResult.Error.Generic>(blankKeyRes)
        assertEquals(AiTraceFailureCategory.NOT_CONFIGURED, blankKeyRes.failureCategory)

        // 2. Standalone blank inputs -> CONTEXT_ERROR
        val blankInputRes =
            client.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "secret-interaction-key",
                request = agenticVideoRequest(videoUri = "", promptText = ""),
                traceContext = InteractionTraceContext(videoDurationSeconds = 500L),
            )
        assertIs<InteractionExecutionResult.Error.Generic>(blankInputRes)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, blankInputRes.failureCategory)

        // 3. Outer session active with blank inputs -> appends attempt to outer session
        val outerSession =
            recorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.MENTION,
                discord = AiTraceDiscordContext(channelId = "10", messageId = "20", isDirectMention = true),
            )
        withAiTraceSession(outerSession) {
            client.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "secret-interaction-key",
                request = agenticVideoRequest(videoUri = " ", promptText = " "),
                traceContext =
                    InteractionTraceContext(
                        stage = AiAttemptStage.AGENTIC_VIDEO,
                        pipeline = AiTracePipeline.CHATTER,
                        trigger = AiTraceTrigger.MENTION,
                    ),
            )
            outerSession.fail(AiTraceFailureCategory.CONTEXT_ERROR, "blank input")
        }
        outerSession.finish()
        recorder.flushAndClose()

        val traces = readTraces(traceFile)
        assertEquals(3, traces.size)

        assertEquals(AiTraceOutcome.FAILED, traces[0].outcome)
        assertEquals(AiTraceFailureCategory.NOT_CONFIGURED, traces[0].failureCategory)
        assertEquals(0, traces[0].attempts.single().pollingAttempts)
        assertNull(traces[0].attempts.single().httpStatus)
        assertNull(traces[0].attempts.single().responseRaw)

        assertEquals(AiTraceOutcome.FAILED, traces[1].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[1].failureCategory)
        assertEquals(0, traces[1].attempts.single().pollingAttempts)
        assertFalse(
            traces[1]
                .attempts
                .single()
                .request
                ?.hasVideo ?: true,
        )

        assertEquals(AiTracePipeline.CHATTER, traces[2].pipeline)
        assertEquals(AiTraceTrigger.MENTION, traces[2].trigger)
        assertEquals(1, traces[2].attemptCount)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, traces[2].attempts.single().stage)
        assertEquals(0, traces[2].attempts.single().pollingAttempts)
    }
}
