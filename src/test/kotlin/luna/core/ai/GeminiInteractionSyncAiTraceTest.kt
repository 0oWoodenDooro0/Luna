package luna.core.ai

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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

class GeminiInteractionSyncAiTraceTest {
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

    @Test
    fun `sync interaction records webUrlCount enabledTools urlContextResults googleSearchQueries raw and filtered citations and redacts secrets`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "super-secret-sync-key-123"
        val extraSecret = "embedded-token-xyz-999"
        val traceDir = tempDir.resolve("ai-traces")
        val recorder =
            JsonlAiTraceRecorder(
                traceDir = traceDir,
                redactor = AiTraceRedactor(listOf(apiKey, extraSecret)),
                clock = fixedClock,
            )
        val traceFile = traceDir.resolve("ai-2026-11-14.jsonl")

        val engine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "id": "int-trace-sync",
                          "status": "completed",
                          "usage": {
                            "input_tokens": 300,
                            "output_tokens": 80,
                            "total_tokens": 410,
                            "thoughts_tokens": 20,
                            "tool_use_input_tokens": 10
                          },
                          "steps": [
                            {
                              "type": "url_context_result",
                              "call_id": "uc-1",
                              "result": [
                                {"retrieved_url": "https://example.com/article?key=$apiKey", "status": "success"},
                                {"retrieved_url": "https://unsafe.example.com/$extraSecret", "status": "unsafe"}
                              ]
                            },
                            {"type": "google_search_call", "id": "gs-1", "arguments": {"queries": ["query with $extraSecret", "kotlin docs"]}},
                            {
                              "type": "model_output",
                              "content": [
                                {
                                  "type": "text",
                                  "text": "Summary output",
                                  "annotations": [
                                    {"type": "url_citation", "url": "https://example.com/article?key=$apiKey", "title": "Original"},
                                    {"type": "url_citation", "url": "https://kotlinlang.org/docs", "title": "Kotlin Docs"},
                                    {"type": "url_citation", "url": "https://kotlinlang.org/docs/", "title": "Kotlin Docs Dup"}
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

        val client =
            GeminiInteractionClient(
                httpClient = createClient(engine),
                pollingDelayMs = 1L,
                traceRecorder = recorder,
            )

        val result =
            client.interact(
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = apiKey,
                request =
                    GeminiInteractionRequest(
                        model = "gemini-3.5-flash-lite",
                        input = listOf(GeminiInteractionInput.text("Summarize https://example.com/article")),
                        tools = listOf(GeminiInteractionTools.URL_CONTEXT, GeminiInteractionTools.GOOGLE_SEARCH),
                        generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = 1024),
                        background = false,
                    ),
                traceContext =
                    InteractionTraceContext(
                        webUrlCount = 2,
                        inputUrls = listOf("https://example.com/article?key=$apiKey"),
                    ),
            )
        recorder.flushAndClose()

        assertIs<InteractionExecutionResult.Success>(result)
        val traces = readTraces(traceFile)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals(listOf(GroundingCitation("Kotlin Docs", "https://kotlinlang.org/docs")), trace.citations)

        val attempt = trace.attempts.single()
        assertNull(attempt.pollingAttempts)
        assertEquals(AiAttemptStage.SUMMARY_GENERATE, attempt.stage)
        assertEquals(AiAttemptOutcome.SUCCESS, attempt.parsedOutcome)

        val req = assertNotNull(attempt.request)
        assertEquals(2, req.webUrlCount)
        assertEquals(listOf("url_context", "google_search"), req.enabledTools)

        assertEquals(2, attempt.urlContextResults.size)
        assertEquals("https://example.com/article?key=******", attempt.urlContextResults[0].retrievedUrl)
        assertEquals("https://unsafe.example.com/******", attempt.urlContextResults[1].retrievedUrl)
        assertEquals("unsafe", attempt.urlContextResults[1].status)

        assertEquals(listOf("query with ******", "kotlin docs"), attempt.googleSearchQueries)
        assertEquals(3, attempt.rawCitations.size)
        assertEquals("https://example.com/article?key=******", attempt.rawCitations[0].url)
        assertEquals(listOf(GroundingCitation("Kotlin Docs", "https://kotlinlang.org/docs")), attempt.filteredCitations)
        assertEquals(20, attempt.usageMetadata?.thoughtsTokenCount)
        assertEquals(10, attempt.usageMetadata?.toolUseInputTokens)

        val rawJsonLine = Files.readAllLines(traceFile).single()
        assertFalse(rawJsonLine.contains(apiKey))
        assertFalse(rawJsonLine.contains(extraSecret))
    }

    @Test
    fun `multi-turn function_call and previous_interaction_id continuation records TOOL_CALL then SUCCESS attempts`(
        @TempDir tempDir: Path,
    ) = runTest {
        val (recorder, traceFile) = createRecorder(tempDir)
        var turn = 0

        val engine =
            MockEngine {
                turn++
                if (turn == 1) {
                    respond(
                        content =
                            """
                            {
                              "id": "int-turn-1",
                              "status": "requires_action",
                              "steps": [
                                {"type": "function_call", "id": "call-1", "name": "fetch_earlier_messages", "arguments": {"reason": "need context"}}
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content =
                            """
                            {
                              "id": "int-turn-2",
                              "status": "completed",
                              "steps": [
                                {"type": "model_output", "content": [{"type": "text", "text": "Continued summary"}]}
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }

        val client = GeminiInteractionClient(httpClient = createClient(engine), pollingDelayMs = 1L, traceRecorder = recorder)
        val session =
            recorder.startSession(
                pipeline = AiTracePipeline.SUMMARY,
                trigger = AiTraceTrigger.REACTION,
                discord = AiTraceDiscordContext(channelId = "c1", messageId = "m1"),
            )
        withAiTraceSession(session) {
            val first =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "secret-interaction-key",
                    request =
                        GeminiInteractionRequest(
                            model = "gemini-3.5-flash-lite",
                            input = listOf(GeminiInteractionInput.text("Summarize")),
                            tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                            background = false,
                        ),
                )
            assertIs<InteractionExecutionResult.FunctionCall>(first)
            val second =
                client.continueInteraction(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "secret-interaction-key",
                    model = "gemini-3.5-flash-lite",
                    previousInteractionId = first.interactionId,
                    functionResults =
                        listOf(
                            GeminiInteractionInput.functionResult(
                                name = "fetch_earlier_messages",
                                callId = first.functionCalls.single().id,
                                result = "Earlier chat",
                            ),
                        ),
                    tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                )
            assertIs<InteractionExecutionResult.Success>(second)
            session.complete(finalOutput = second.text, citations = second.citations)
        }
        session.finish()
        recorder.flushAndClose()

        val trace = readTraces(traceFile).single()
        assertEquals(2, trace.attemptCount)
        assertEquals(AiAttemptStage.SUMMARY_GENERATE, trace.attempts[0].stage)
        assertEquals(AiAttemptOutcome.TOOL_CALL, trace.attempts[0].parsedOutcome)
        assertEquals(listOf("fetch_earlier_messages"), trace.attempts[0].request?.enabledTools)

        assertEquals(AiAttemptStage.SUMMARY_TOOL_CONTINUATION, trace.attempts[1].stage)
        assertEquals(AiAttemptOutcome.SUCCESS, trace.attempts[1].parsedOutcome)
        val contPayload =
            assertNotNull(
                trace.attempts[1]
                    .request
                    ?.payload
                    ?.jsonObject,
            )
        assertEquals("int-turn-1", contPayload["previous_interaction_id"]?.jsonPrimitive?.content)
    }
}
