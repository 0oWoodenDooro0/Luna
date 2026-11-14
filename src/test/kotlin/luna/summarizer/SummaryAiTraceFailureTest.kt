package luna.summarizer

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.summarizer.SummaryAiTraceTestSupport.createRecorder
import luna.summarizer.SummaryAiTraceTestSupport.createTestClient
import luna.summarizer.SummaryAiTraceTestSupport.readTraces
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryAiTraceFailureTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
    }

    @Test
    fun `search grounding and url_context HTTP error fails fast without retry and records FAILED`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var callIndex = 0

        val mockEngine =
            MockEngine {
                callIndex++
                respond(
                    content = """{"error": {"code": 400, "message": "google_search tool rejected"}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key", searchGroundingEnabled = true, urlContextEnabled = true),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        val result =
            summarizer.summarize(
                SummaryInput(
                    content = "Topic https://example.com/doc",
                    webUrls = listOf("https://example.com/doc"),
                ),
            )
        recorder.flushAndClose()

        assertTrue(result is SummaryResult.Error.ApiError)
        assertEquals(1, callIndex)
        val traces = readTraces(traceFile)
        assertEquals(1, traces.size)
        val trace = traces.first()

        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(luna.core.ai.AiTraceFailureCategory.HTTP_ERROR, trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("SUMMARY_GENERATE:url_context+grounded"), trace.fallbackPath)
        assertEquals(AiAttemptOutcome.ERROR, trace.attempts[0].parsedOutcome)
        assertEquals(400, trace.attempts[0].httpStatus)
    }

    @Test
    fun `multimodal failure with images and tools fails fast with 1 attempt and records AI Trace FAILED`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var callIndex = 0

        val mockEngine =
            MockEngine {
                callIndex++
                respond(
                    content = """{"error": {"code": 400, "message": "Unable to fetch image"}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key", searchGroundingEnabled = true),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        val input =
            SummaryInput(
                content = "Discussion with two screenshots",
                imageAttachments =
                    listOf(
                        luna.core.ai.ChatImageAttachment("https://cdn.discordapp.com/a.png", "image/png", "a.png"),
                        luna.core.ai.ChatImageAttachment("https://cdn.discordapp.com/b.jpg", "image/jpeg", "b.jpg"),
                    ),
            )
        val result = summarizer.summarize(input)
        recorder.flushAndClose()

        assertTrue(result is SummaryResult.Error.ApiError)
        assertEquals(1, callIndex, "Must fail fast with 1 upstream attempt and no no-images retry")
        val traces = readTraces(traceFile)
        assertEquals(1, traces.size)
        val trace = traces.first()

        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.HTTP_ERROR, trace.failureCategory)
        assertNotNull(trace.errorSummary)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("SUMMARY_GENERATE:grounded"), trace.fallbackPath)

        val attempt = trace.attempts.single()
        assertEquals(AiAttemptOutcome.ERROR, attempt.parsedOutcome)
        assertEquals(400, attempt.httpStatus)
        assertNotNull(attempt.errorMessage)
        assertEquals(true, attempt.request?.hasImages)
        assertEquals(2, attempt.request?.imageCount)
    }

    @Test
    fun `tool continuation failure fails fast across two attempts and records AI Trace FAILED without baseline retry`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var callIndex = 0

        val toolCallResponse =
            """
            {
              "id": "int-tc-1",
              "status": "requires_action",
              "steps": [
                { "type": "function_call", "id": "call-tc", "name": "fetch_earlier_messages", "arguments": { "reason": "More context" } }
              ]
            }
            """.trimIndent()

        val mockEngine =
            MockEngine {
                callIndex++
                if (callIndex == 1) {
                    respond(
                        content = toolCallResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = "Upstream error during continuation",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            }

        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key", searchGroundingEnabled = true),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        val result = summarizer.summarize(SummaryInput(content = "Recent slice")) { "Earlier messages" }
        recorder.flushAndClose()

        assertTrue(result is SummaryResult.Error.ApiError)
        assertEquals(2, callIndex, "Must stop after round-2 continuation failure without a 3rd baseline retry")
        val traces = readTraces(traceFile)
        assertEquals(1, traces.size)
        val trace = traces.first()

        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.HTTP_ERROR, trace.failureCategory)
        assertNotNull(trace.errorSummary)
        assertEquals(2, trace.attemptCount)
        assertEquals(
            listOf(
                "SUMMARY_GENERATE:grounded+lookback",
                "SUMMARY_TOOL_CONTINUATION:fetch_earlier_messages",
            ),
            trace.fallbackPath,
        )
        assertEquals(AiAttemptOutcome.TOOL_CALL, trace.attempts[0].parsedOutcome)
        assertEquals(AiAttemptOutcome.ERROR, trace.attempts[1].parsedOutcome)
        assertEquals(500, trace.attempts[1].httpStatus)
        assertEquals("Upstream error during continuation", trace.attempts[1].responseRaw)
        assertNotNull(trace.attempts[1].errorMessage)
    }

    @Test
    fun `lookbackProvider exception records single TOOL_CALL attempt and AI Trace FAILED without baseline retry`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var callIndex = 0

        val toolCallResponse =
            """
            {
              "id": "int-lb-fail-1",
              "status": "requires_action",
              "steps": [
                { "type": "function_call", "id": "call-lbf", "name": "fetch_earlier_messages", "arguments": { "reason": "Need history" } }
              ]
            }
            """.trimIndent()

        val mockEngine =
            MockEngine {
                callIndex++
                respond(
                    content = toolCallResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key", searchGroundingEnabled = true),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        val result =
            summarizer.summarize(SummaryInput(content = "Recent slice")) {
                throw IllegalStateException("Discord history unavailable")
            }
        recorder.flushAndClose()

        assertTrue(result is SummaryResult.Error.Generic)
        assertEquals(1, callIndex)
        val trace = readTraces(traceFile).single()
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, trace.failureCategory)
        assertTrue(trace.errorSummary?.contains("Discord history unavailable") == true)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("SUMMARY_GENERATE:grounded+lookback"), trace.fallbackPath)
        assertEquals(AiAttemptOutcome.TOOL_CALL, trace.attempts.single().parsedOutcome)
    }

    @Test
    fun `post-trigger preflight aborts record AI Trace with empty attempts`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key"),
                traceRecorder = recorder,
            )

        // Case 1: getMessageOrNull throws -> CONTEXT_ERROR
        val service1 = SummaryService(summarizer = summarizer, traceRecorder = recorder)
        val msg1 = SummaryTestFixtures.createMockMessage(id = 8001L, username = "alice", content = "Hello")
        val failingMsgEvent = SummaryTestFixtures.createMockReactionEvent(targetMessage = msg1)
        io.mockk.coEvery { failingMsgEvent.getMessageOrNull() } throws RuntimeException("Discord REST 503")
        service1.handleReaction(failingMsgEvent)

        // Case 2: collector.collectSlice throws -> CONTEXT_ERROR
        val failingCollector = io.mockk.mockk<ConversationSliceCollector>(relaxed = true)
        io.mockk.coEvery { failingCollector.collectSlice(any()) } throws IllegalStateException("Channel history unavailable")
        val service2 = SummaryService(summarizer = summarizer, collector = failingCollector, traceRecorder = recorder)
        val msg2 = SummaryTestFixtures.createMockMessage(id = 8002L, username = "bob", content = "Hello")
        service2.handleReaction(SummaryTestFixtures.createMockReactionEvent(targetMessage = msg2))

        // Case 3: collector.collectSlice returns null (noise) -> SKIPPED
        val service3 =
            SummaryService(
                summarizer = summarizer,
                collector = ConversationSliceCollector(peekResolver = { null }),
                traceRecorder = recorder,
            )
        val noiseMsg = SummaryTestFixtures.createMockMessage(id = 8003L, username = "carol", content = "/cmd arg")
        service3.handleReaction(SummaryTestFixtures.createMockReactionEvent(targetMessage = noiseMsg))

        // Case 4: YOUTUBE_DURATION_EXCEEDED -> DURATION_EXCEEDED
        val longVideoPost =
            dev.socialpeek.model.PeekPost(
                platform = dev.socialpeek.model.Platform.YOUTUBE,
                id = "longVid",
                originalUrl = "https://www.youtube.com/watch?v=longVid",
                cleanUrl = "https://www.youtube.com/watch?v=longVid",
                author = dev.socialpeek.model.Author(username = "Creator"),
                title = "Long Stream",
                content = "Long Stream",
                rawData = mapOf("durationSeconds" to "9000"),
            )
        val service4 =
            SummaryService(
                summarizer = summarizer,
                collector = ConversationSliceCollector(peekResolver = { longVideoPost }),
                replySender = { _, _ -> },
                traceRecorder = recorder,
            )
        val ytMsg =
            SummaryTestFixtures.createMockMessage(
                id = 8004L,
                username = "dave",
                content = "https://www.youtube.com/watch?v=longVid",
            )
        service4.handleReaction(SummaryTestFixtures.createMockReactionEvent(targetMessage = ytMsg))

        // Case 5: NOT_CONFIGURED when Gemini API key is blank
        val unconfiguredSummarizer = GeminiSummarizer(config = GeminiConfig(apiKey = ""), traceRecorder = recorder)
        val service5 =
            SummaryService(
                summarizer = unconfiguredSummarizer,
                collector =
                    ConversationSliceCollector(
                        historyFetcher = { _, _ -> emptyList() },
                        forwardFetcher = { _, _ -> emptyList() },
                        peekResolver = { null },
                    ),
                traceRecorder = recorder,
            )
        val plainMsg = SummaryTestFixtures.createMockMessage(id = 8005L, username = "eve", content = "Summarize me")
        service5.handleReaction(SummaryTestFixtures.createMockReactionEvent(targetMessage = plainMsg))

        recorder.flushAndClose()

        val traces = readTraces(traceFile)
        assertEquals(5, traces.size)

        assertEquals("8001", traces[0].discord.messageId)
        assertEquals(AiTraceOutcome.FAILED, traces[0].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.CONTEXT_ERROR, traces[0].failureCategory)
        assertEquals(0, traces[0].attemptCount)
        assertTrue(traces[0].attempts.isEmpty())

        assertEquals("8002", traces[1].discord.messageId)
        assertEquals(AiTraceOutcome.FAILED, traces[1].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.CONTEXT_ERROR, traces[1].failureCategory)
        assertTrue(traces[1].attempts.isEmpty())

        assertEquals("8003", traces[2].discord.messageId)
        assertEquals(AiTraceOutcome.SKIPPED, traces[2].outcome)
        assertNull(traces[2].failureCategory)
        assertTrue(traces[2].attempts.isEmpty())

        assertEquals("8004", traces[3].discord.messageId)
        assertEquals(AiTraceOutcome.FAILED, traces[3].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.DURATION_EXCEEDED, traces[3].failureCategory)
        assertTrue(traces[3].errorSummary?.contains("YOUTUBE_DURATION_EXCEEDED") == true)
        assertTrue(traces[3].attempts.isEmpty())

        assertEquals("8005", traces[4].discord.messageId)
        assertEquals(AiTraceOutcome.FAILED, traces[4].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.NOT_CONFIGURED, traces[4].failureCategory)
        assertTrue(traces[4].attempts.isEmpty())
    }

    @Test
    fun `upstream errors safety blocks timeouts empty output and parse errors record accurate failureCategory and attempt raw details`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var scenario = 0

        val mockEngine =
            MockEngine {
                when (scenario) {
                    1 -> {
                        respond(
                            content = """{"error": {"code": 429, "message": "Quota exceeded"}}""",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    2 -> {
                        respond(
                            content = "Internal Upstream Crash",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    }

                    3 -> {
                        throw io.ktor.client.plugins.HttpRequestTimeoutException(
                            "https://generativelanguage.googleapis.com",
                            30_000L,
                        )
                    }

                    4 -> {
                        respond(
                            content = """{"id": "int-safety", "status": "completed", "finish_reason": "SAFETY", "steps": []}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    5 -> {
                        respond(
                            content =
                                """{"id": "int-empty", "status": "completed", "finish_reason": "STOP", """ +
                                    """"steps": [{"type": "model_output", "content": [{"type": "text", "text": "📝🎉"}]}]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        respond(
                            content = "{not-valid-json",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }

        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key", searchGroundingEnabled = false),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        for (i in 1..6) {
            scenario = i
            summarizer.summarize(SummaryInput(content = "Test payload $i"))
        }
        recorder.flushAndClose()

        val traces = readTraces(traceFile)
        assertEquals(6, traces.size)

        // 1. Rate limit
        assertEquals(AiTraceOutcome.FAILED, traces[0].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.RATE_LIMIT, traces[0].failureCategory)
        assertEquals(429, traces[0].attempts.first().httpStatus)

        // 2. HTTP 500
        assertEquals(AiTraceOutcome.FAILED, traces[1].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.HTTP_ERROR, traces[1].failureCategory)
        assertEquals(500, traces[1].attempts.first().httpStatus)
        assertEquals("Internal Upstream Crash", traces[1].attempts.first().responseRaw)

        // 3. Timeout
        assertEquals(AiTraceOutcome.FAILED, traces[2].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.TIMEOUT, traces[2].failureCategory)
        assertNull(traces[2].attempts.first().httpStatus)
        assertEquals(AiAttemptOutcome.ERROR, traces[2].attempts.first().parsedOutcome)

        // 4. Safety block
        assertEquals(AiTraceOutcome.FAILED, traces[3].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.SAFETY_BLOCK, traces[3].failureCategory)
        assertEquals("SAFETY", traces[3].attempts.first().finishReason)

        // 5. Empty / emoji-only output
        assertEquals(AiTraceOutcome.FAILED, traces[4].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.EMPTY_OUTPUT, traces[4].failureCategory)
        assertEquals("STOP", traces[4].attempts.first().finishReason)

        // 6. Malformed JSON parse error
        assertEquals(AiTraceOutcome.FAILED, traces[5].outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.PARSE_ERROR, traces[5].failureCategory)
        assertEquals(200, traces[5].attempts.first().httpStatus)
        assertEquals("{not-valid-json", traces[5].attempts.first().responseRaw)
    }
}
