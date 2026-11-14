package luna.summarizer

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceTrigger
import luna.summarizer.SummaryAiTraceTestSupport.createRecorder
import luna.summarizer.SummaryAiTraceTestSupport.createTestClient
import luna.summarizer.SummaryAiTraceTestSupport.readTraces
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryAiTracePipelineTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
    }

    @Test
    fun `SummaryService end-to-end multi-turn fetch_earlier_messages and upstream safety block record complete traces`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var callIndex = 0

        val toolCallResponse =
            """
            {
              "id": "int-e2e-1",
              "status": "requires_action",
              "steps": [
                {
                  "type": "function_call",
                  "id": "call-e2e",
                  "name": "fetch_earlier_messages",
                  "arguments": { "reason": "Need earlier context" }
                }
              ]
            }
            """.trimIndent()

        val finalSummaryResponse =
            """
            {
              "id": "int-e2e-2",
              "status": "completed",
              "finish_reason": "STOP",
              "usage": { "input_tokens": 210, "output_tokens": 25, "total_tokens": 235 },
              "steps": [
                {
                  "type": "model_output",
                  "content": [{ "type": "text", "text": "TL;DR:\n整合歷史脈絡後的摘要。" }]
                }
              ]
            }
            """.trimIndent()

        val safetyBlockedResponse =
            """
            {
              "id": "int-e2e-safe",
              "status": "completed",
              "finish_reason": "SAFETY",
              "steps": []
            }
            """.trimIndent()

        val mockEngine =
            MockEngine {
                callIndex++
                when (callIndex) {
                    1 -> {
                        respond(
                            content = toolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    2 -> {
                        respond(
                            content = finalSummaryResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        respond(
                            content = safetyBlockedResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }

        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key", searchGroundingEnabled = true),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        val earlierMsg = SummaryTestFixtures.createMockMessage(id = 9101L, username = "alice", content = "Earlier decision")
        val targetMsg1 = SummaryTestFixtures.createMockMessage(id = 9102L, username = "bob", content = "What did we decide?")
        val targetMsg2 = SummaryTestFixtures.createMockMessage(id = 9103L, username = "carol", content = "Blocked discussion")

        var lastReply: String? = null
        val service =
            SummaryService(
                summarizer = summarizer,
                collector =
                    ConversationSliceCollector(
                        historyFetcher = { msg, _ ->
                            if (msg.id == targetMsg1.id) listOf(earlierMsg) else emptyList()
                        },
                        forwardFetcher = { _, _ -> emptyList() },
                        peekResolver = { null },
                    ),
                replySender = { _, text -> lastReply = text },
                traceRecorder = recorder,
            )

        // 1. Multi-turn lookback via SummaryService
        service.handleReaction(SummaryTestFixtures.createMockReactionEvent(targetMessage = targetMsg1))
        assertEquals("整合歷史脈絡後的摘要。".let { "TL;DR:\n$it" }, lastReply)

        // 2. Safety block via SummaryService
        service.handleReaction(SummaryTestFixtures.createMockReactionEvent(targetMessage = targetMsg2))
        assertNull(service.getStatus(targetMsg2.id))

        recorder.flushAndClose()

        val traces = readTraces(traceFile)
        assertEquals(2, traces.size)

        val lookbackTrace = traces[0]
        assertEquals("9102", lookbackTrace.discord.messageId)
        assertEquals(AiTraceOutcome.SUCCESS, lookbackTrace.outcome)
        assertFalse(lookbackTrace.degraded)
        assertEquals(2, lookbackTrace.attemptCount)
        assertEquals(AiAttemptStage.SUMMARY_GENERATE, lookbackTrace.attempts[0].stage)
        assertEquals(AiAttemptStage.SUMMARY_TOOL_CONTINUATION, lookbackTrace.attempts[1].stage)
        assertEquals("整合歷史脈絡後的摘要。".let { "TL;DR:\n$it" }, lookbackTrace.finalOutput)

        val blockedTrace = traces[1]
        assertEquals("9103", blockedTrace.discord.messageId)
        assertEquals(AiTraceOutcome.FAILED, blockedTrace.outcome)
        assertEquals(luna.core.ai.AiTraceFailureCategory.SAFETY_BLOCK, blockedTrace.failureCategory)
        assertEquals("SAFETY", blockedTrace.attempts.last().finishReason)
    }

    @Test
    fun `SummaryService long-video AGENTIC_VIDEO run records pipeline SUMMARY trigger REACTION and single collapsed attempt`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-summary-agentic-key"
        val (recorder, traceFile) = createRecorder(tempDir, apiKey)
        var pollCount = 0
        val completedJson =
            """{"id": "sum-int-1", "status": "completed", "output_text": "TL;DR:\n長影片核心摘要 🚀\n\n核心重點:\n• 第一段重點"}"""

        val mockEngine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/interactions") && request.method.value == "POST" -> {
                        respond(
                            content = """{"id": "sum-int-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    request.url.encodedPath.endsWith("/interactions/sum-int-1") && request.method.value == "GET" -> {
                        pollCount++
                        val body =
                            if (pollCount == 1) {
                                """{"id": "sum-int-1", "status": "in_progress"}"""
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
                        respond("Unexpected endpoint", status = HttpStatusCode.NotFound)
                    }
                }
            }

        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = apiKey, model = "models/gemini-3.5-flash-lite", searchGroundingEnabled = true),
                httpClient = createTestClient(mockEngine),
                pollingDelayMs = 1L,
                maxPollingAttempts = 5,
                traceRecorder = recorder,
            )

        val longVideoPost =
            dev.socialpeek.model.PeekPost(
                platform = dev.socialpeek.model.Platform.YOUTUBE,
                id = "longTalk900",
                originalUrl = "https://www.youtube.com/watch?v=longTalk900",
                cleanUrl = "https://www.youtube.com/watch?v=longTalk900",
                author = dev.socialpeek.model.Author(username = "Conference"),
                title = "Deep Dive Keynote",
                content = "Deep Dive Keynote description",
                rawData = mapOf("durationSeconds" to "900"),
            )

        var sentReply: String? = null
        val service =
            SummaryService(
                summarizer = summarizer,
                collector =
                    ConversationSliceCollector(
                        historyFetcher = { _, _ -> emptyList() },
                        forwardFetcher = { _, _ -> emptyList() },
                        peekResolver = { longVideoPost },
                    ),
                replySender = { _, text -> sentReply = text },
                traceRecorder = recorder,
            )

        val targetMsg =
            SummaryTestFixtures.createMockMessage(
                id = 9201L,
                username = "alice",
                content = "https://www.youtube.com/watch?v=longTalk900",
            )
        service.handleReaction(SummaryTestFixtures.createMockReactionEvent(targetMessage = targetMsg))
        recorder.flushAndClose()

        assertNotNull(sentReply)
        assertTrue(sentReply!!.contains("長影片核心摘要"))

        val traces = readTraces(traceFile)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.SUMMARY, trace.pipeline)
        assertEquals(AiTraceTrigger.REACTION, trace.trigger)
        assertEquals("9201", trace.discord.messageId)
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)
        assertEquals(sentReply, trace.finalOutput)

        val attempt = trace.attempts.single()
        assertEquals(1, attempt.attemptIndex)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, attempt.stage)
        assertEquals("gemini-3.5-flash-lite", attempt.model)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/interactions?key=******",
            attempt.endpoint,
        )
        assertEquals(2, attempt.pollingAttempts)
        assertEquals(200, attempt.httpStatus)
        assertEquals(completedJson, attempt.responseRaw)
        assertEquals(AiAttemptOutcome.SUCCESS, attempt.parsedOutcome)
        assertEquals(true, attempt.request?.hasVideo)
        assertEquals(900L, attempt.request?.videoDurationSeconds)
        assertTrue((attempt.request?.transcriptLength ?: 0) > 0)

        assertFalse(Files.readString(traceFile).contains(apiKey))
    }

    @Test
    fun `GeminiSummarizer long-video AGENTIC_VIDEO failure records single FAILED attempt without duplicate search-grounding retry`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var submitCount = 0
        val failedJson = """{"id": "sum-fail-1", "status": "failed", "error": {"message": "Video processing failed"}}"""

        val mockEngine =
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/interactions")) {
                    submitCount++
                    respond(
                        content = """{"id": "sum-fail-1", "status": "in_progress"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = failedJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }

        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key", searchGroundingEnabled = true),
                httpClient = createTestClient(mockEngine),
                pollingDelayMs = 1L,
                maxPollingAttempts = 3,
                traceRecorder = recorder,
            )

        val result =
            summarizer.summarize(
                SummaryInput(
                    content = "Long talk",
                    videoUri = "https://www.youtube.com/watch?v=longFail",
                    videoDurationSeconds = 600L,
                ),
            )
        recorder.flushAndClose()

        assertTrue(result is SummaryResult.Error.Generic)
        assertEquals(1, submitCount)

        val trace = readTraces(traceFile).single()
        assertEquals(AiTracePipeline.SUMMARY, trace.pipeline)
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, trace.attempts.single().stage)
        assertEquals(1, trace.attempts.single().pollingAttempts)
        assertEquals(failedJson, trace.attempts.single().responseRaw)
        assertEquals(
            600L,
            trace.attempts
                .single()
                .request
                ?.videoDurationSeconds,
        )
    }

    @Test
    fun `SummaryService end-to-end web URL summary records webUrlCount urlContextResults googleSearchQueries and filtered citations`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-web-url-trace-key"
        val (recorder, traceFile) = createRecorder(tempDir, apiKey)

        val responseJson =
            """
            {
              "id": "int-web-e2e",
              "status": "completed",
              "finish_reason": "STOP",
              "usage": { "input_tokens": 320, "output_tokens": 70, "total_tokens": 390 },
              "steps": [
                {
                  "type": "url_context_result",
                  "call_id": "uc-1",
                  "result": [
                    { "retrieved_url": "https://kotlinlang.org/docs/k2.html", "status": "success" }
                  ]
                },
                {
                  "type": "google_search_call",
                  "id": "gs-1",
                  "arguments": { "queries": ["Kotlin K2 release notes"] }
                },
                {
                  "type": "model_output",
                  "content": [
                    {
                      "type": "text",
                      "text": "TL;DR:\nK2 編譯器文章重點已讀取完成。",
                      "annotations": [
                        { "type": "url_citation", "url": "https://kotlinlang.org/docs/k2.html/", "title": "Original Doc" },
                        { "type": "url_citation", "url": "https://blog.jetbrains.com/kotlin/k2", "title": "JetBrains K2 Blog" }
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
                config =
                    GeminiConfig(
                        apiKey = apiKey,
                        model = "gemini-3.5-flash-lite",
                        searchGroundingEnabled = true,
                        urlContextEnabled = true,
                    ),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        var peekCalled = false
        val collector =
            ConversationSliceCollector(
                historyFetcher = { _, _ -> emptyList() },
                forwardFetcher = { _, _ -> emptyList() },
                peekResolver = {
                    peekCalled = true
                    null
                },
            )

        var sentReply: String? = null
        val service =
            SummaryService(
                summarizer = summarizer,
                collector = collector,
                replySender = { _, text -> sentReply = text },
                traceRecorder = recorder,
            )

        val targetMsg =
            SummaryTestFixtures.createMockMessage(
                id = 9301L,
                username = "alice",
                content = "Please check https://kotlinlang.org/docs/k2.html",
            )
        service.handleReaction(SummaryTestFixtures.createMockReactionEvent(targetMessage = targetMsg))
        recorder.flushAndClose()

        assertFalse(peekCalled)
        assertNotNull(sentReply)
        assertTrue(sentReply!!.contains("K2 編譯器文章重點已讀取完成"))
        assertTrue(sentReply!!.contains("[JetBrains K2 Blog](<https://blog.jetbrains.com/kotlin/k2>)"))
        assertFalse(sentReply!!.contains("[Original Doc]"))

        val trace = readTraces(traceFile).single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals(listOf("SUMMARY_GENERATE:url_context+grounded+lookback"), trace.fallbackPath)
        assertEquals(1, trace.citations.size)
        assertEquals("https://blog.jetbrains.com/kotlin/k2", trace.citations.single().url)

        val attempt = trace.attempts.single()
        assertEquals(1, attempt.request?.webUrlCount)
        assertEquals(
            listOf("url_context", "google_search", "fetch_earlier_messages"),
            attempt.request?.enabledTools,
        )
        assertEquals(1, attempt.urlContextResults.size)
        assertEquals("https://kotlinlang.org/docs/k2.html", attempt.urlContextResults.single().retrievedUrl)
        assertEquals(listOf("Kotlin K2 release notes"), attempt.googleSearchQueries)
        assertEquals(2, attempt.rawCitations.size)
        assertEquals(1, attempt.filteredCitations.size)
        assertEquals("https://blog.jetbrains.com/kotlin/k2", attempt.filteredCitations.single().url)
    }
}
