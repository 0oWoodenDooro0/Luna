package luna.core.ai

import ch.qos.logback.classic.Level
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
import luna.chatter.ChatterConfig
import luna.chatter.ChatterGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiInteractionSafetyBlockTest {
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

    private val prohibitedContentPayload =
        """{"error":{"message":"Input blocked: The prompt could not be submitted. The prompt contains sensitive words that violate Google's Generative AI Prohibited Use policy.","code":"prohibited_content"}}"""

    @Test
    fun `HTTP 400 prohibited_content returns SafetyBlock with WARN log while invalid_argument remains ApiError`() =
        runTest {
            val clientLogger = LoggerFactory.getLogger(GeminiInteractionClient::class.java) as Logger
            val events = ListAppender<ILoggingEvent>().apply { start() }
            clientLogger.addAppender(events)

            try {
                val prohibitedEngine =
                    MockEngine {
                        respond(
                            content = prohibitedContentPayload,
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val client = GeminiInteractionClient(httpClient = createClient(prohibitedEngine))
                val blockedResult =
                    client.interact(
                        baseUrl = "https://generativelanguage.googleapis.com",
                        apiKey = "test-key",
                        request =
                            GeminiInteractionRequest(
                                model = "gemini-3.5-flash-lite",
                                input = listOf(GeminiInteractionInput.text("blocked prompt")),
                            ),
                    )
                assertIs<InteractionExecutionResult.Error.SafetyBlock>(blockedResult)
                assertEquals("PROHIBITED_CONTENT", blockedResult.finishReason)
                assertEquals(AiTraceFailureCategory.SAFETY_BLOCK, blockedResult.failureCategory)
                assertTrue(
                    events.list.any { it.level == Level.WARN && it.formattedMessage.contains("prohibited_content") },
                    "HTTP safety block must log at WARN level",
                )
                assertFalse(
                    events.list.any { it.level == Level.ERROR },
                    "HTTP safety block must not log at ERROR level",
                )

                // Negative boundary: ordinary HTTP 400 invalid_argument must still classify as ApiError (HTTP_ERROR)
                val badArgEngine =
                    MockEngine {
                        respond(
                            content = """{"error":{"message":"Invalid model","code":"invalid_argument"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val badArgResult =
                    GeminiInteractionClient(httpClient = createClient(badArgEngine)).interact(
                        baseUrl = "https://generativelanguage.googleapis.com",
                        apiKey = "test-key",
                        request =
                            GeminiInteractionRequest(
                                model = "gemini-3.5-flash-lite",
                                input = listOf(GeminiInteractionInput.text("bad arg")),
                            ),
                    )
                assertIs<InteractionExecutionResult.Error.ApiError>(badArgResult)
                assertEquals(AiTraceFailureCategory.HTTP_ERROR, badArgResult.failureCategory)
            } finally {
                clientLogger.detachAppender(events)
                events.stop()
            }
        }

    @Test
    fun `ChatterGenerator records SAFETY_BLOCK trace and logs WARN instead of ERROR on HTTP 400 prohibited_content`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir)
        val genLogger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
        val clientLogger = LoggerFactory.getLogger(GeminiInteractionClient::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        genLogger.addAppender(events)
        clientLogger.addAppender(events)

        try {
            val engine =
                MockEngine {
                    respond(
                        content = prohibitedContentPayload,
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "test-key", searchGroundingEnabled = false),
                    httpClient = createClient(engine),
                    traceRecorder = recorder,
                )

            assertNull(generator.generateReply("hello"))
            recorder.flushAndClose()

            val lines =
                java.nio.file.Files
                    .readAllLines(tempDir.resolve(tempDir.toFile().list()!!.single()))
            val record = AiTraceJson.decodeFromString(AiTraceRecord.serializer(), lines.single())
            assertEquals(AiTraceOutcome.FAILED, record.outcome)
            assertEquals(AiTraceFailureCategory.SAFETY_BLOCK, record.failureCategory)
            assertEquals("PROHIBITED_CONTENT", record.attempts.single().finishReason)
            assertFalse(
                events.list.any { it.level == Level.ERROR },
                "prohibited_content safety block in ChatterGenerator must not emit ERROR logs",
            )
            assertTrue(
                events.list.any { it.level == Level.WARN && it.loggerName == ChatterGenerator::class.java.name },
                "ChatterGenerator must still emit WARN log for observability",
            )
        } finally {
            genLogger.detachAppender(events)
            clientLogger.detachAppender(events)
            events.stop()
            recorder.flushAndClose()
        }
    }
}
