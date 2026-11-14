package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterAiTraceFailureTest {
    @Test
    fun `ChatterGenerator total failures flush FAILED with accurate failureCategory across error modes`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)

        // 1. RATE_LIMIT
        run {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":{"code":429,"message":"Quota exceeded"}}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = ChatterAiTraceTestSupport.createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        // 2. TIMEOUT
        run {
            val engine =
                MockEngine {
                    throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30_000L)
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = ChatterAiTraceTestSupport.createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        // 3. HTTP_ERROR
        run {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":{"code":503,"message":"Unavailable"}}""",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = ChatterAiTraceTestSupport.createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        // 4. SAFETY_BLOCK
        run {
            val engine =
                MockEngine {
                    respond(
                        content = """{"id":"int-safety","status":"completed","finish_reason":"SAFETY","steps":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = ChatterAiTraceTestSupport.createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        // 5. EMPTY_OUTPUT
        run {
            val engine =
                MockEngine {
                    respond(
                        content = """{"id":"int-empty","status":"completed","steps":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = ChatterAiTraceTestSupport.createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        recorder.flushAndClose()
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(5, traces.size)
        assertEquals(
            listOf(
                AiTraceFailureCategory.RATE_LIMIT,
                AiTraceFailureCategory.TIMEOUT,
                AiTraceFailureCategory.HTTP_ERROR,
                AiTraceFailureCategory.SAFETY_BLOCK,
                AiTraceFailureCategory.EMPTY_OUTPUT,
            ),
            traces.map { it.failureCategory },
        )
        assertTrue(traces.all { it.outcome == AiTraceOutcome.FAILED })
        assertEquals("SAFETY", traces[3].attempts.single().finishReason)
    }

    @Test
    fun `ChatterGenerator classifies RECITATION finishReason with empty text as SAFETY_BLOCK`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val engine =
            MockEngine {
                respond(
                    content = """{"id":"int-recite","status":"completed","finish_reason":"RECITATION","steps":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                httpClient = ChatterAiTraceTestSupport.createHttpClient(engine),
                traceRecorder = recorder,
            )

        assertNull(generator.generateReply("hello"))
        recorder.flushAndClose()

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertEquals(AiTraceFailureCategory.SAFETY_BLOCK, trace.failureCategory)
        assertEquals("RECITATION", trace.attempts.single().finishReason)
    }

    @Test
    fun `ChatterService post-filter preflight aborts record attempts empty or prior DECISION attempts with FAILED outcome`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val selfId = Snowflake(9999L)

        // 1. NOT_CONFIGURED on direct mention -> attempts: []
        run {
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = ""), traceRecorder = recorder),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = ""), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    traceRecorder = recorder,
                )
            val msg =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 801L,
                    content = "<@9999> hey!",
                    mentionedUserIds = setOf(selfId),
                    selfId = selfId,
                )
            assertFalse(service.processMessage(msg))
        }

        // 2. Blank reply context on direct mention (e.g. collector returns blank context) -> attempts: []
        run {
            val emptyCollector =
                object : ChatterContextCollector() {
                    override suspend fun collectReplyContext(
                        messages: List<Message>,
                        kordSelfId: Snowflake?,
                    ): PreparedChatterContext = PreparedChatterContext(transcript = "")
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = "k"), traceRecorder = recorder),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = emptyCollector,
                    traceRecorder = recorder,
                )
            val msg =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 802L,
                    content = "<@9999> hey!",
                    mentionedUserIds = setOf(selfId),
                    selfId = selfId,
                )
            assertFalse(service.processMessage(msg))
        }

        // 3. Channel resolution error after ambient DECISION succeeded -> preserves DECISION attempt
        run {
            val decisionEngine =
                MockEngine {
                    respond(
                        content = """{"answers":{"should_participate":{"type":"noul","noul":0.9,"confidence":0.9}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "k"),
                            httpClient = ChatterAiTraceTestSupport.createHttpClient(decisionEngine),
                            traceRecorder = recorder,
                        ),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    traceRecorder = recorder,
                )
            val msg =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 803L,
                    content = "Does anyone know Kotlin?",
                    channelThrows = true,
                )
            assertFalse(service.processMessage(msg))
        }

        // 4. Discord delivery failure -> UNEXPECTED_ERROR with CHATTER_GENERATE attempt recorded
        run {
            val genEngine =
                MockEngine {
                    respond(
                        content = """{"id":"int-deliv","status":"completed","finish_reason":"STOP","output_text":"Reply text"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val httpClient = ChatterAiTraceTestSupport.createHttpClient(genEngine)
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "k"),
                            httpClient = httpClient,
                            traceRecorder = recorder,
                        ),
                    chatterGenerator =
                        ChatterGenerator(
                            config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                            httpClient = httpClient,
                            traceRecorder = recorder,
                        ),
                    historyFetcher = { _, _ -> emptyList() },
                    messageSender = { _, _ -> throw RuntimeException("Discord 500") },
                    traceRecorder = recorder,
                )
            val msg =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 804L,
                    content = "<@9999> hi",
                    mentionedUserIds = setOf(selfId),
                    selfId = selfId,
                )
            assertFalse(service.processMessage(msg))
        }

        recorder.flushAndClose()
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(4, traces.size)

        assertEquals(AiTraceOutcome.FAILED, traces[0].outcome)
        assertEquals(AiTraceFailureCategory.NOT_CONFIGURED, traces[0].failureCategory)
        assertEquals(0, traces[0].attemptCount)
        assertTrue(traces[0].attempts.isEmpty())

        assertEquals(AiTraceOutcome.FAILED, traces[1].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[1].failureCategory)
        assertEquals(0, traces[1].attemptCount)
        assertTrue(traces[1].attempts.isEmpty())

        assertEquals(AiTraceOutcome.FAILED, traces[2].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[2].failureCategory)
        assertEquals(1, traces[2].attemptCount)
        assertEquals(AiAttemptStage.DECISION, traces[2].attempts.single().stage)

        assertEquals(AiTraceOutcome.FAILED, traces[3].outcome)
        assertEquals(AiTraceFailureCategory.UNEXPECTED_ERROR, traces[3].failureCategory)
        assertEquals(1, traces[3].attemptCount)
        assertEquals(AiAttemptStage.CHATTER_GENERATE, traces[3].attempts.single().stage)
    }

    @Test
    fun `ChatterService classifies collectDecisionContext and collectReplyContext exceptions as CONTEXT_ERROR`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val selfId = Snowflake(9999L)

        // 1. collectDecisionContext throws
        run {
            val failingDecisionCollector =
                object : ChatterContextCollector() {
                    override suspend fun collectDecisionContext(
                        messages: List<Message>,
                        kordSelfId: Snowflake?,
                    ): ChatterDecisionContext = throw IllegalStateException("Decision context boom")
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = "k"), traceRecorder = recorder),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = failingDecisionCollector,
                    traceRecorder = recorder,
                )
            val msg = ChatterAiTraceTestSupport.createMockMessage(id = 851L, content = "Does anyone know Kotlin?")
            assertFalse(service.processMessage(msg))
        }

        // 2. collectReplyContext throws
        run {
            val failingReplyCollector =
                object : ChatterContextCollector() {
                    override suspend fun collectReplyContext(
                        messages: List<Message>,
                        kordSelfId: Snowflake?,
                    ): PreparedChatterContext = throw IllegalStateException("Reply context boom")
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = "k"), traceRecorder = recorder),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = failingReplyCollector,
                    traceRecorder = recorder,
                )
            val msg =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 852L,
                    content = "<@9999> hi",
                    mentionedUserIds = setOf(selfId),
                    selfId = selfId,
                )
            assertFalse(service.processMessage(msg))
        }

        recorder.flushAndClose()
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(2, traces.size)

        assertEquals(AiTraceOutcome.FAILED, traces[0].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[0].failureCategory)
        assertTrue(traces[0].errorSummary?.contains("Decision context boom") == true)

        assertEquals(AiTraceOutcome.FAILED, traces[1].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[1].failureCategory)
        assertTrue(traces[1].errorSummary?.contains("Reply context boom") == true)
    }
}
