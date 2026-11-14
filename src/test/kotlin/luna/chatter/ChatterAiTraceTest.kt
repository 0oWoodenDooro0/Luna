package luna.chatter

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ChatImageAttachment
import luna.core.ai.GroundingCitation
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterAiTraceTest {
    @Test
    fun `standalone GeminiDecisionClient records SKIPPED trace with DECISION attempt noul score and input blocks`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "model": "gemini-decision-1",
                          "answers": {
                            "should_participate": {
                              "type": "noul",
                              "noul": 0.23,
                              "confidence": 0.77
                            }
                          }
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val client =
            GeminiDecisionClient(
                config = ChatterDecisionConfig(apiKey = "secret-decision-key", threshold = 0.7),
                httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine),
                traceRecorder = recorder,
            )

        val decision = client.decide("[Alice]: Anyone want coffee later?")
        recorder.flushAndClose()

        assertFalse(decision.shouldParticipate)
        assertEquals(0.23, decision.noul)

        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.AMBIENT, trace.trigger)
        assertEquals(AiTraceOutcome.SKIPPED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("DECISION"), trace.fallbackPath)
        assertTrue(trace.errorSummary?.contains("0.23") == true)
        assertTrue(trace.errorSummary?.contains("0.7") == true)

        val attempt = trace.attempts.single()
        assertEquals(AiAttemptStage.DECISION, attempt.stage)
        assertEquals("gemini-decision-1", attempt.model)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", attempt.endpoint)
        assertEquals(200, attempt.httpStatus)
        assertEquals(AiAttemptOutcome.SKIPPED, attempt.parsedOutcome)
        assertTrue(attempt.responseRaw?.contains("\"noul\": 0.23") == true)
        assertTrue(attempt.responseRaw?.contains("\"confidence\": 0.77") == true)

        val payloadObj = attempt.request?.payload?.jsonObject
        assertNotNull(payloadObj)
        val inputBlocks = payloadObj["input"]?.jsonArray
        assertNotNull(inputBlocks)
        assertEquals(1, inputBlocks.size)
        assertEquals("text", inputBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("[Alice]: Anyone want coffee later?", inputBlocks[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `standalone GeminiDecisionClient multimodal failure retries text-only and records degraded true with both DECISION attempts`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine { request ->
                val bodyJson =
                    ChatterAiTraceTestSupport.clientJson
                        .parseToJsonElement(request.body.toByteArray().decodeToString())
                        .jsonObject
                val inputSize = bodyJson["input"]!!.jsonArray.size
                if (inputSize > 1) {
                    respond(
                        content = """{"error":{"code":400,"message":"Invalid image URI"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content =
                            """
                            {
                              "model": "gemini-decision-1",
                              "answers": {
                                "should_participate": {
                                  "type": "noul",
                                  "noul": 0.84,
                                  "confidence": 0.84
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val client =
            GeminiDecisionClient(
                config = ChatterDecisionConfig(apiKey = "secret-decision-key", threshold = 0.7),
                httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine),
                traceRecorder = recorder,
            )

        val images =
            listOf(
                ChatImageAttachment(
                    url = "https://cdn.discordapp.com/attachments/1/2/img.png",
                    mimeType = "image/png",
                ),
            )
        val decision = client.decide("[Alice]: look at this", images)
        recorder.flushAndClose()

        assertTrue(decision.shouldParticipate)
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTraceOutcome.DEGRADED_SUCCESS, trace.outcome)
        assertTrue(trace.degraded)
        assertEquals(2, trace.attemptCount)
        assertEquals(listOf("DECISION:multimodal", "DECISION:text-only"), trace.fallbackPath)

        val first = trace.attempts[0]
        assertEquals(1, first.attemptIndex)
        assertEquals(AiAttemptStage.DECISION, first.stage)
        assertEquals(400, first.httpStatus)
        assertEquals(AiAttemptOutcome.ERROR, first.parsedOutcome)
        assertTrue(first.request?.hasImages == true)
        assertEquals(1, first.request?.imageCount)

        val second = trace.attempts[1]
        assertEquals(2, second.attemptIndex)
        assertEquals(AiAttemptStage.DECISION, second.stage)
        assertEquals(200, second.httpStatus)
        assertEquals(AiAttemptOutcome.SUCCESS, second.parsedOutcome)
        assertFalse(second.request?.hasImages ?: true)
        assertEquals(0, second.request?.imageCount)
    }

    @Test
    fun `standalone ChatterGenerator records single CHATTER_GENERATE FAILED attempt without retries on interaction error`(
        @TempDir tempDir: Path,
    ) = runTest {
        var callCount = 0
        val mockEngine =
            MockEngine {
                callCount++
                respond(
                    content = """{"error":{"code":400,"message":"Cannot fetch image URI"}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-chatter-key", searchGroundingEnabled = true, urlContextEnabled = true),
                httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine),
                traceRecorder = recorder,
            )

        val reply =
            generator.generateReply(
                ChatterInput(
                    transcript = "[Alice]: 看看這張圖 https://example.com/guide",
                    imageAttachments =
                        listOf(
                            ChatImageAttachment(
                                url = "https://cdn.discordapp.com/attachments/1/2/pic.png",
                                mimeType = "image/png",
                            ),
                        ),
                    webUrls = listOf("https://example.com/guide"),
                    isDirectMention = true,
                ),
            )
        recorder.flushAndClose()

        assertNull(reply)
        assertEquals(1, callCount)
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.HTTP_ERROR, trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("CHATTER_GENERATE"), trace.fallbackPath)

        val attempt = trace.attempts.single()
        assertEquals(400, attempt.httpStatus)
        assertEquals(AiAttemptOutcome.ERROR, attempt.parsedOutcome)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/interactions?key=******",
            attempt.endpoint,
        )
        assertTrue(attempt.request?.hasImages == true)
        assertEquals(1, attempt.request?.webUrlCount)
        assertEquals(listOf("url_context", "google_search"), attempt.request?.enabledTools)
    }

    @Test
    fun `standalone ChatterGenerator records webUrlCount enabledTools urlContextResults googleSearchQueries and filtered citations in AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "id": "int-chatter-trace-1",
                          "status": "completed",
                          "usage": {
                            "input_tokens": 120,
                            "output_tokens": 25,
                            "total_tokens": 145
                          },
                          "steps": [
                            {
                              "type": "url_context_result",
                              "call_id": "uc-1",
                              "result": [
                                {"retrieved_url": "https://kotlinlang.org/docs/coroutines.html", "status": "success"},
                                {"retrieved_url": "https://unsafe.example.com/bad", "status": "unsafe"}
                              ]
                            },
                            {
                              "type": "google_search_call",
                              "id": "gs-1",
                              "arguments": {"queries": ["kotlin coroutines structured concurrency"]}
                            },
                            {
                              "type": "model_output",
                              "content": [
                                {
                                  "type": "text",
                                  "text": "結構化並發用起來超乾淨",
                                  "annotations": [
                                    {"type": "url_citation", "url": "https://kotlinlang.org/docs/coroutines.html", "title": "Kotlin Docs"},
                                    {"type": "url_citation", "url": "https://docs.oracle.com/concurrency", "title": "Concurrency Reference"}
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

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-chatter-key", searchGroundingEnabled = true, urlContextEnabled = true),
                httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine),
                traceRecorder = recorder,
            )

        val reply =
            generator.generateReply(
                ChatterInput(
                    transcript = "[Alice]: 看這篇 https://kotlinlang.org/docs/coroutines.html 跟 https://unsafe.example.com/bad",
                    webUrls = listOf("https://kotlinlang.org/docs/coroutines.html", "https://unsafe.example.com/bad"),
                    isDirectMention = true,
                ),
            )
        recorder.flushAndClose()

        assertEquals("結構化並發用起來超乾淨", reply)
        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertTrue(trace.citations.isEmpty(), "Chatter must not append citations to Discord output")

        val attempt = trace.attempts.single()
        assertEquals(2, attempt.request?.webUrlCount)
        assertEquals(listOf("url_context", "google_search"), attempt.request?.enabledTools)
        assertEquals(2, attempt.urlContextResults.size)
        assertEquals("success", attempt.urlContextResults[0].status)
        assertEquals("unsafe", attempt.urlContextResults[1].status)
        assertEquals(listOf("kotlin coroutines structured concurrency"), attempt.googleSearchQueries)
        assertEquals(2, attempt.rawCitations.size)
        assertEquals(
            listOf(GroundingCitation(title = "Concurrency Reference", url = "https://docs.oracle.com/concurrency")),
            attempt.filteredCitations,
        )
        assertEquals(145, attempt.usageMetadata?.totalTokenCount)
    }
}
