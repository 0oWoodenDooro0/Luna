package luna.summarizer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
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
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryAiTraceTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
    }

    @Test
    fun `happy-path Summary Reaction records complete AI Trace with citations usageMetadata and JsonLogger correlation`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "super-secret-gemini-key-123"
        val (recorder, traceFile) = createRecorder(tempDir, apiKey)

        val responseJson =
            """
            {
              "id": "int-happy-1",
              "status": "completed",
              "finish_reason": "STOP",
              "usage": {
                "input_tokens": 140,
                "output_tokens": 42,
                "total_tokens": 182
              },
              "steps": [
                {
                  "type": "google_search_call",
                  "id": "gs-1",
                  "arguments": { "queries": ["Kotlin K2 compiler"] }
                },
                {
                  "type": "model_output",
                  "content": [
                    {
                      "type": "text",
                      "text": "TL;DR:\n🚀 Kotlin K2 編譯器顯著提升建置速度。\n\n核心重點:\n• 編譯效能提升\n• 支援跨平台",
                      "annotations": [
                        { "type": "url_citation", "url": "https://kotlinlang.org/k2", "title": "Kotlin K2" }
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

        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        jsonLogger.addAppender(listAppender)

        try {
            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = apiKey, model = "models/gemini-3.5-flash-lite", searchGroundingEnabled = true),
                    httpClient = createTestClient(mockEngine),
                    traceRecorder = recorder,
                )

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> emptyList() },
                    forwardFetcher = { _, _ -> emptyList() },
                    peekResolver = { null },
                )

            var sentReply: String? = null
            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector = collector,
                    replySender = { _, text -> sentReply = text },
                    traceRecorder = recorder,
                )

            val targetMessage =
                SummaryTestFixtures.createMockMessage(
                    id = 1001L,
                    username = "alice",
                    content = "SUPER_SECRET_USER_PROMPT_MARKER Let us discuss Kotlin K2 compiler rollout.",
                    authorId =
                        dev.kord.common.entity
                            .Snowflake(4001L),
                )
            val event =
                SummaryTestFixtures.createMockReactionEvent(
                    targetMessage = targetMessage,
                    userId = 5001L,
                    channelId = 3001L,
                    guildId = 2001L,
                )

            service.handleReaction(event)
            recorder.flushAndClose()

            assertNotNull(sentReply)
            assertTrue(sentReply!!.contains("Kotlin K2 編譯器顯著提升建置速度"))
            assertTrue(sentReply!!.contains("[Kotlin K2](<https://kotlinlang.org/k2>)"))

            val traces = readTraces(traceFile)
            assertEquals(1, traces.size)
            val trace = traces.first()

            assertEquals(AiTracePipeline.SUMMARY, trace.pipeline)
            assertEquals(AiTraceTrigger.REACTION, trace.trigger)
            assertEquals("2001", trace.discord.guildId)
            assertEquals("3001", trace.discord.channelId)
            assertEquals("1001", trace.discord.messageId)
            assertEquals("4001", trace.discord.authorId)
            assertEquals("5001", trace.discord.reactingUserId)
            assertFalse(trace.discord.isDirectMention)

            assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
            assertFalse(trace.degraded)
            assertNull(trace.failureCategory)
            assertNull(trace.errorSummary)
            assertEquals(sentReply, trace.finalOutput)
            assertEquals(1, trace.citations.size)
            assertEquals("https://kotlinlang.org/k2", trace.citations.first().url)
            assertEquals(1, trace.attemptCount)
            assertEquals(1, trace.attempts.size)
            assertEquals(listOf("SUMMARY_GENERATE:grounded+lookback"), trace.fallbackPath)

            val attempt = trace.attempts.first()
            assertEquals(1, attempt.attemptIndex)
            assertEquals(AiAttemptStage.SUMMARY_GENERATE, attempt.stage)
            assertEquals("gemini-3.5-flash-lite", attempt.model)
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/interactions?key=******",
                attempt.endpoint,
            )
            assertEquals(200, attempt.httpStatus)
            assertEquals("STOP", attempt.finishReason)
            assertNull(attempt.blockReason)
            assertEquals(140, attempt.usageMetadata?.promptTokenCount)
            assertEquals(42, attempt.usageMetadata?.candidatesTokenCount)
            assertEquals(182, attempt.usageMetadata?.totalTokenCount)
            assertEquals(listOf("Kotlin K2 compiler"), attempt.googleSearchQueries)
            assertEquals(AiAttemptOutcome.SUCCESS, attempt.parsedOutcome)
            assertNull(attempt.errorMessage)
            assertEquals(responseJson, attempt.responseRaw)

            val request = assertNotNull(attempt.request)
            assertFalse(request.hasImages)
            assertEquals(0, request.imageCount)
            assertFalse(request.hasVideo)
            assertTrue(request.transcriptLength > 0)
            val payloadObj = request.payload?.jsonObject
            assertNotNull(payloadObj)
            assertTrue(payloadObj.toString().contains("SUPER_SECRET_USER_PROMPT_MARKER"))

            val serializedLine = Files.readAllLines(traceFile).first()
            assertFalse(serializedLine.contains(apiKey), "API key must never appear in persisted trace JSONL")

            val correlationLogs =
                listAppender.list.filter { logEvent ->
                    logEvent.formattedMessage.contains("traceRecorded") &&
                        (logEvent.argumentArray?.any { it.toString().contains(trace.traceId) } == true)
                }
            assertEquals(1, correlationLogs.size, "Exactly one JsonLogger correlation entry must be emitted")
            val logText =
                correlationLogs.first().formattedMessage +
                    " " +
                    correlationLogs.first().argumentArray?.joinToString(" ")
            assertFalse(logText.contains("SUPER_SECRET_USER_PROMPT_MARKER"), "JsonLogger must not duplicate raw prompts")
            assertTrue(logText.contains("SUCCESS"))
        } finally {
            jsonLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `standalone GeminiSummarizer multi-turn fetch_earlier_messages records SUMMARY_GENERATE and SUMMARY_TOOL_CONTINUATION in order`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var callIndex = 0

        val toolCallResponse =
            """
            {
              "id": "int-lb-turn-1",
              "status": "requires_action",
              "usage": { "input_tokens": 80, "output_tokens": 15, "total_tokens": 95 },
              "steps": [
                {
                  "type": "function_call",
                  "id": "call-1",
                  "name": "fetch_earlier_messages",
                  "arguments": { "reason": "Need earlier context" }
                }
              ]
            }
            """.trimIndent()

        val finalResponse =
            """
            {
              "id": "int-lb-turn-2",
              "status": "completed",
              "finish_reason": "STOP",
              "usage": { "input_tokens": 160, "output_tokens": 30, "total_tokens": 190 },
              "steps": [
                {
                  "type": "model_output",
                  "content": [{ "type": "text", "text": "TL;DR:\n完整對話脈絡摘要。" }]
                }
              ]
            }
            """.trimIndent()

        val mockEngine =
            MockEngine {
                callIndex++
                val body = if (callIndex == 1) toolCallResponse else finalResponse
                respond(
                    content = body,
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
            summarizer.summarize(SummaryInput(content = "Later messages only")) { reason ->
                assertEquals("Need earlier context", reason)
                "Earlier history context"
            }
        recorder.flushAndClose()

        assertTrue(result is SummaryResult.Success)
        val traces = readTraces(traceFile)
        assertEquals(1, traces.size)
        val trace = traces.first()

        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(2, trace.attemptCount)
        assertEquals(
            listOf("SUMMARY_GENERATE:grounded+lookback", "SUMMARY_TOOL_CONTINUATION:fetch_earlier_messages"),
            trace.fallbackPath,
        )

        assertEquals(1, trace.attempts[0].attemptIndex)
        assertEquals(AiAttemptStage.SUMMARY_GENERATE, trace.attempts[0].stage)
        assertEquals(AiAttemptOutcome.TOOL_CALL, trace.attempts[0].parsedOutcome)
        assertEquals(95, trace.attempts[0].usageMetadata?.totalTokenCount)

        assertEquals(2, trace.attempts[1].attemptIndex)
        assertEquals(AiAttemptStage.SUMMARY_TOOL_CONTINUATION, trace.attempts[1].stage)
        assertEquals(AiAttemptOutcome.SUCCESS, trace.attempts[1].parsedOutcome)
        assertEquals(190, trace.attempts[1].usageMetadata?.totalTokenCount)
        val continuationPayload =
            trace.attempts[1]
                .request
                ?.payload
                ?.toString()
                .orEmpty()
        assertTrue(continuationPayload.contains("function_result"))
        assertTrue(continuationPayload.contains("int-lb-turn-1"))
        assertTrue(continuationPayload.contains("Earlier history context"))
    }

    @Test
    fun `ignored reactions non-summary emoji bot user and duplicate message do not create AI Trace records`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        val mockEngine =
            MockEngine {
                respond(
                    content =
                        """{"id": "int-ignored", "status": "completed", """ +
                            """"steps": [{"type": "model_output", "content": [{"type": "text", "text": "TL;DR:\nSummary."}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "secret-gemini-api-key", searchGroundingEnabled = false),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )
        val service =
            SummaryService(
                summarizer = summarizer,
                collector =
                    ConversationSliceCollector(
                        historyFetcher = { _, _ -> emptyList() },
                        forwardFetcher = { _, _ -> emptyList() },
                        peekResolver = { null },
                    ),
                replySender = { _, _ -> },
                traceRecorder = recorder,
            )

        val targetMessage = SummaryTestFixtures.createMockMessage(id = 7001L, username = "alice", content = "Some content")

        // 1. Non-summary emoji
        service.handleReaction(
            SummaryTestFixtures.createMockReactionEvent(
                targetMessage = targetMessage,
                emoji =
                    dev.kord.core.entity.ReactionEmoji
                        .Unicode("👍"),
            ),
        )
        // 2. Bot reacting user
        service.handleReaction(
            SummaryTestFixtures.createMockReactionEvent(
                targetMessage = targetMessage,
                userIsBot = true,
            ),
        )
        // 3. First valid reaction (records 1 trace)
        val validEvent = SummaryTestFixtures.createMockReactionEvent(targetMessage = targetMessage, userId = 55L)
        service.handleReaction(validEvent)
        // 4. Duplicate reaction on same message (skipped before trace creation)
        service.handleReaction(
            SummaryTestFixtures.createMockReactionEvent(targetMessage = targetMessage, userId = 56L),
        )

        recorder.flushAndClose()

        val traces = readTraces(traceFile)
        assertEquals(1, traces.size, "Only the single non-duplicate human summary reaction should emit a trace")
        assertEquals("7001", traces.first().discord.messageId)
    }

    @Test
    fun `unsafe url_context_result logs error and records retrieval statuses in AI Trace while preserving valid summary`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        val logger = LoggerFactory.getLogger(luna.core.ai.GeminiInteractionClient::class.java) as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        logger.addAppender(listAppender)

        try {
            val responseJson =
                """
                {
                  "id": "int-uc-unsafe-trace",
                  "status": "completed",
                  "finish_reason": "STOP",
                  "steps": [
                    {
                      "type": "url_context_result",
                      "call_id": "uc-1",
                      "result": [
                        { "retrieved_url": "https://safe.example.com/ok", "status": "success" },
                        { "retrieved_url": "https://unsafe.example.com/bad", "status": "unsafe" }
                      ]
                    },
                    {
                      "type": "model_output",
                      "content": [
                        { "type": "text", "text": "TL;DR:\n已根據安全內容完成摘要。" }
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
                    config = GeminiConfig(apiKey = "secret-gemini-api-key", urlContextEnabled = true),
                    httpClient = createTestClient(mockEngine),
                    traceRecorder = recorder,
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Compare https://safe.example.com/ok and https://unsafe.example.com/bad",
                        webUrls = listOf("https://safe.example.com/ok", "https://unsafe.example.com/bad"),
                    ),
                )
            recorder.flushAndClose()

            assertTrue(result is SummaryResult.Success)
            assertEquals("TL;DR:\n已根據安全內容完成摘要。", result.text)
            assertTrue(
                listAppender.list.any {
                    it.level == Level.ERROR &&
                        it.formattedMessage.contains("https://unsafe.example.com/bad") &&
                        it.formattedMessage.contains("unsafe")
                },
            )

            val trace = readTraces(traceFile).single()
            assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
            assertFalse(trace.degraded)
            val attempt = trace.attempts.single()
            assertEquals(2, attempt.urlContextResults.size)
            assertEquals("success", attempt.urlContextResults[0].status)
            assertEquals("unsafe", attempt.urlContextResults[1].status)
            assertEquals("https://unsafe.example.com/bad", attempt.urlContextResults[1].retrievedUrl)
        } finally {
            logger.detachAppender(listAppender)
            listAppender.stop()
        }
    }
}
