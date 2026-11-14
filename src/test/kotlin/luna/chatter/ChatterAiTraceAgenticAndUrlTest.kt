package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.socialpeek.model.Author
import dev.socialpeek.model.Comment
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceTrigger
import luna.core.ai.GroundingCitation
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterAiTraceAgenticAndUrlTest {
    @Test
    fun `ChatterService direct-mention long-video AGENTIC_VIDEO success emits MENTION trace with single collapsed attempt`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-chatter-agentic-key"
        var pollCount = 0
        val completedJson =
            """{"id": "chat-int-1", "status": "completed", "output_text": "這支長影片重點在後半段的實測 👍"}"""

        val mockEngine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/interactions") && request.method.value == "POST" -> {
                        respond(
                            content = """{"id": "chat-int-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    request.url.encodedPath.endsWith("/interactions/chat-int-1") && request.method.value == "GET" -> {
                        pollCount++
                        val body =
                            if (pollCount == 1) {
                                """{"id": "chat-int-1", "status": "in_progress"}"""
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

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val longVideoPost =
            PeekPost(
                platform = Platform.YOUTUBE,
                id = "longChatter720",
                originalUrl = "https://www.youtube.com/watch?v=longChatter720",
                cleanUrl = "https://www.youtube.com/watch?v=longChatter720",
                author = Author(username = "Creator"),
                title = "Long Architecture Talk",
                content = "Talk summary",
                rawData = mapOf("durationSeconds" to "720"),
            )
        val filter = ChatterNoiseFilter()
        val collector =
            ChatterContextCollector(
                noiseFilter = filter,
                peekResolver = { longVideoPost },
            )
        val sentMessages = mutableListOf<String>()
        val service =
            ChatterService(
                filter = filter,
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = apiKey),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = apiKey, searchGroundingEnabled = true),
                        httpClient = httpClient,
                        pollingDelayMs = 1L,
                        maxPollingAttempts = 5,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, content ->
                    sentMessages.add(content)
                    mockk(relaxed = true)
                },
                contextCollector = collector,
                traceRecorder = recorder,
            )

        val selfId = Snowflake(9999L)
        val mentionMsg =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 901L,
                channelId = 902L,
                guildId = 903L,
                authorId = 904L,
                username = "Alice",
                content = "<@9999> 幫我看這支片 https://www.youtube.com/watch?v=longChatter720",
                mentionedUserIds = setOf(selfId),
                selfId = selfId,
            )

        val handled = service.processMessage(mentionMsg)
        recorder.flushAndClose()

        assertTrue(handled)
        assertEquals(listOf("這支長影片重點在後半段的實測"), sentMessages)

        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertFalse(trace.degraded)
        assertNull(trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)
        assertEquals("這支長影片重點在後半段的實測", trace.finalOutput)

        val attempt = trace.attempts.single()
        assertEquals(1, attempt.attemptIndex)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, attempt.stage)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions?key=******", attempt.endpoint)
        assertEquals(2, attempt.pollingAttempts)
        assertEquals(200, attempt.httpStatus)
        assertEquals(completedJson, attempt.responseRaw)
        assertEquals(AiAttemptOutcome.SUCCESS, attempt.parsedOutcome)
        assertEquals(true, attempt.request?.hasVideo)
        assertEquals(720L, attempt.request?.videoDurationSeconds)

        val jsonlFile = tempDir.resolve("ai-2026-11-14.jsonl")
        assertFalse(Files.readString(jsonlFile).contains(apiKey))
    }

    @Test
    fun `ChatterService direct-mention agentic timeout fails without retry and records FAILED AGENTIC_VIDEO trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-chatter-degrade-key"
        var pollCount = 0
        val pendingJson = """{"id": "chat-int-to", "status": "in_progress"}"""

        val mockEngine =
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/interactions") && request.method.value == "POST" -> {
                        respond(
                            content = pendingJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    path.contains("/interactions/chat-int-to") && request.method.value == "GET" -> {
                        pollCount++
                        respond(
                            content = pendingJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        error("Unexpected fallback HTTP call to $path")
                    }
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val longVideoPost =
            PeekPost(
                platform = Platform.YOUTUBE,
                id = "longTimeout900",
                originalUrl = "https://www.youtube.com/watch?v=longTimeout900",
                cleanUrl = "https://www.youtube.com/watch?v=longTimeout900",
                author = Author(username = "Creator"),
                title = "Long Stream Replay",
                content = "Stream outline",
                comments =
                    listOf(
                        Comment(author = "viewer", content = "12:00 精華必看"),
                    ),
                rawData = mapOf("durationSeconds" to "900"),
            )
        val filter = ChatterNoiseFilter()
        val service =
            ChatterService(
                filter = filter,
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = apiKey),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = apiKey, searchGroundingEnabled = true),
                        httpClient = httpClient,
                        pollingDelayMs = 1L,
                        maxPollingAttempts = 2,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, _ -> mockk(relaxed = true) },
                contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { longVideoPost }),
                traceRecorder = recorder,
            )

        val selfId = Snowflake(9999L)
        val mentionMsg =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 911L,
                channelId = 912L,
                guildId = 913L,
                authorId = 914L,
                username = "Alice",
                content = "<@9999> 幫忙看 https://www.youtube.com/watch?v=longTimeout900",
                mentionedUserIds = setOf(selfId),
                selfId = selfId,
            )

        val handled = service.processMessage(mentionMsg)
        recorder.flushAndClose()

        assertFalse(handled)
        assertEquals(2, pollCount)

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.TIMEOUT, trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)

        val agenticAttempt = trace.attempts.single()
        assertEquals(1, agenticAttempt.attemptIndex)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, agenticAttempt.stage)
        assertEquals(2, agenticAttempt.pollingAttempts)
        assertEquals(AiAttemptOutcome.ERROR, agenticAttempt.parsedOutcome)
        assertEquals(900L, agenticAttempt.request?.videoDurationSeconds)
    }

    @Test
    fun `standalone ChatterGenerator failed agentic interaction records single FAILED AGENTIC_VIDEO attempt without retry`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-standalone-agentic-key"
        val failedInteractionJson = """{"id": "chat-fail-1", "status": "failed", "error": {"message": "Video unavailable"}}"""

        val mockEngine =
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/interactions") && request.method.value == "POST" -> {
                        respond(
                            content = """{"id": "chat-fail-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    path.contains("/interactions/chat-fail-1") && request.method.value == "GET" -> {
                        respond(
                            content = failedInteractionJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        error("Unexpected fallback HTTP call to $path")
                    }
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = apiKey, searchGroundingEnabled = true),
                httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine),
                pollingDelayMs = 1L,
                maxPollingAttempts = 3,
                traceRecorder = recorder,
            )

        val reply =
            generator.generateReply(
                ChatterInput(
                    transcript = "[Alice]: 看看這支長影片\n標題：Long Video",
                    videoUri = "https://www.youtube.com/watch?v=longFail950",
                    videoDurationSeconds = 950L,
                    isDirectMention = true,
                ),
            )
        recorder.flushAndClose()

        assertNull(reply)
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.UNEXPECTED_ERROR, trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, trace.attempts[0].stage)
        assertEquals(1, trace.attempts[0].pollingAttempts)
        assertEquals(failedInteractionJson, trace.attempts[0].responseRaw)
        assertEquals(950L, trace.attempts[0].request?.videoDurationSeconds)
    }

    @Test
    fun `ChatterService end-to-end with general web URL skips SocialPeek, enables url_context, and records full AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-e2e-web-key"
        val webUrl = "https://kotlinlang.org/docs/coroutines-overview.html"
        var peekCalled = false

        val mockEngine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/decisions") -> {
                        respond(
                            content = """{"answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    request.url.encodedPath.endsWith("/interactions") -> {
                        respond(
                            content =
                                """
                                {
                                  "id": "int-e2e-web",
                                  "status": "completed",
                                  "steps": [
                                    {
                                      "type": "url_context_result",
                                      "result": [{"retrieved_url": "$webUrl", "status": "success"}]
                                    },
                                    {
                                      "type": "google_search_call",
                                      "arguments": {"queries": ["kotlinx coroutines 1.9"]}
                                    },
                                    {
                                      "type": "model_output",
                                      "content": [
                                        {
                                          "type": "text",
                                          "text": "官方文件寫得很清楚 新手照著看很快就能上手",
                                          "annotations": [
                                            {"type": "url_citation", "url": "$webUrl", "title": "Coroutines Overview"},
                                            {"type": "url_citation", "url": "https://github.com/Kotlin/kotlinx.coroutines", "title": "kotlinx.coroutines"}
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

                    else -> {
                        respond("Not found", status = HttpStatusCode.NotFound)
                    }
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val filter = ChatterNoiseFilter()
        val sentMessages = mutableListOf<String>()
        val service =
            ChatterService(
                filter = filter,
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = apiKey),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = apiKey, searchGroundingEnabled = true, urlContextEnabled = true),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, content ->
                    sentMessages.add(content)
                    mockk(relaxed = true)
                },
                contextCollector =
                    ChatterContextCollector(
                        noiseFilter = filter,
                        peekResolver = {
                            peekCalled = true
                            null
                        },
                    ),
                traceRecorder = recorder,
            )

        val msg = ChatterAiTraceTestSupport.createMockMessage(id = 990L, channelId = 991L, content = "推薦看一下 $webUrl")
        assertTrue(service.processMessage(msg))
        recorder.flushAndClose()

        assertFalse(peekCalled)
        assertEquals(listOf("官方文件寫得很清楚 新手照著看很快就能上手"), sentMessages)

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals(2, trace.attemptCount)
        assertTrue(trace.citations.isEmpty())

        val genAttempt = trace.attempts[1]
        assertEquals(AiAttemptStage.CHATTER_GENERATE, genAttempt.stage)
        assertEquals(1, genAttempt.request?.webUrlCount)
        assertEquals(listOf("url_context", "google_search"), genAttempt.request?.enabledTools)
        assertEquals(1, genAttempt.urlContextResults.size)
        assertEquals(webUrl, genAttempt.urlContextResults.single().retrievedUrl)
        assertEquals(listOf("kotlinx coroutines 1.9"), genAttempt.googleSearchQueries)
        assertEquals(2, genAttempt.rawCitations.size)
        assertEquals(
            listOf(GroundingCitation("kotlinx.coroutines", "https://github.com/Kotlin/kotlinx.coroutines")),
            genAttempt.filteredCitations,
        )
    }
}
