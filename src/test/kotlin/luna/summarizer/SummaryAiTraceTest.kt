package luna.summarizer

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceJson
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRedactor
import luna.core.ai.AiTraceTrigger
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.BeforeEach
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryAiTraceTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC)

    private val clientJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
    }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(clientJson)
            }
        }

    private fun createRecorder(
        tempDir: Path,
        apiKey: String = "secret-gemini-api-key",
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
    fun `multimodal fallback strips Chat Image Attachment blocks and records DEGRADED_SUCCESS`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var callIndex = 0

        val mockEngine =
            MockEngine {
                callIndex++
                if (callIndex == 1) {
                    respond(
                        content = """{"error": {"code": 400, "message": "Unable to fetch image"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content =
                            """{"id": "int-text-only", "status": "completed", "finish_reason": "STOP", """ +
                                """"steps": [{"type": "model_output", "content": [{"type": "text", "text": "TL;DR:\nText-only summary."}]}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
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

        assertTrue(result is SummaryResult.Success)
        val traces = readTraces(traceFile)
        assertEquals(1, traces.size)
        val trace = traces.first()

        assertEquals(AiTraceOutcome.DEGRADED_SUCCESS, trace.outcome)
        assertTrue(trace.degraded)
        assertEquals(2, trace.attemptCount)
        assertEquals(
            listOf("SUMMARY_GENERATE:grounded", "SUMMARY_GENERATE:no-images"),
            trace.fallbackPath,
        )

        assertEquals(true, trace.attempts[0].request?.hasImages)
        assertEquals(2, trace.attempts[0].request?.imageCount)
        assertEquals(false, trace.attempts[1].request?.hasImages)
        assertEquals(0, trace.attempts[1].request?.imageCount)
        assertFalse(
            trace.attempts[1]
                .request
                ?.payload
                ?.toString()
                ?.contains("cdn.discordapp.com") == true,
        )
    }

    @Test
    fun `tool continuation failure falls back to baseline and records DEGRADED_SUCCESS across all three attempts`(
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
                            content = "Upstream error during continuation",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    }

                    else -> {
                        respond(
                            content =
                                """{"id": "int-tc-base", "status": "completed", """ +
                                    """"steps": [{"type": "model_output", "content": [{"type": "text", "text": "TL;DR:\nBaseline fallback summary."}]}]}""",
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

        val result = summarizer.summarize(SummaryInput(content = "Recent slice")) { "Earlier messages" }
        recorder.flushAndClose()

        assertTrue(result is SummaryResult.Success)
        val traces = readTraces(traceFile)
        assertEquals(1, traces.size)
        val trace = traces.first()

        assertEquals(AiTraceOutcome.DEGRADED_SUCCESS, trace.outcome)
        assertTrue(trace.degraded)
        assertEquals(3, trace.attemptCount)
        assertEquals(
            listOf(
                "SUMMARY_GENERATE:grounded+lookback",
                "SUMMARY_TOOL_CONTINUATION:fetch_earlier_messages",
                "SUMMARY_GENERATE:baseline",
            ),
            trace.fallbackPath,
        )
        assertEquals(AiAttemptOutcome.TOOL_CALL, trace.attempts[0].parsedOutcome)
        assertEquals(AiAttemptOutcome.ERROR, trace.attempts[1].parsedOutcome)
        assertEquals(500, trace.attempts[1].httpStatus)
        assertEquals("Upstream error during continuation", trace.attempts[1].responseRaw)
        assertEquals(AiAttemptOutcome.SUCCESS, trace.attempts[2].parsedOutcome)
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
