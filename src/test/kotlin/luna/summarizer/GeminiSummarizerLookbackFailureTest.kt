package luna.summarizer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.GeminiInteractionTools
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeminiSummarizerLookbackFailureTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    @Test
    fun `returns Error when Gemini returns requires_action with malformed function_call missing name`() =
        runTest {
            val malformedResponse =
                """
                {
                  "id": "int-malformed",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-bad",
                      "name": "   ",
                      "arguments": {}
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = malformedResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Test",
                        tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                    ),
                )

            assertIs<SummaryResult.Error.Generic>(result)
            assertEquals(AiTraceFailureCategory.PARSE_ERROR, classifySummaryError(result))
        }

    @Test
    fun `returns Error when Gemini returns unknown tool name not declared in tools`() =
        runTest {
            val unknownToolResponse =
                """
                {
                  "id": "int-unknown-tool",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-unk",
                      "name": "unsupported_shell_execute",
                      "arguments": { "cmd": "ls" }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = unknownToolResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Test",
                        tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                    ),
                )

            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("Unknown tool call", ignoreCase = true))
            assertTrue(result.message.contains("unsupported_shell_execute"))
        }

    @Test
    fun `returns Error when fetch_earlier_messages function_call is missing required reason argument`() =
        runTest {
            val missingReasonResponse =
                """
                {
                  "id": "int-no-reason",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-nr",
                      "name": "fetch_earlier_messages",
                      "arguments": {}
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = missingReasonResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Test",
                        tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                    ),
                )

            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("reason", ignoreCase = true))
        }

    @Test
    fun `returns Error when fetch_earlier_messages function_call has blank reason argument`() =
        runTest {
            val blankReasonResponse =
                """
                {
                  "id": "int-blank-reason",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-br",
                      "name": "fetch_earlier_messages",
                      "arguments": { "reason": "   " }
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = blankReasonResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val result =
                summarizer.summarize(
                    SummaryInput(
                        content = "Test",
                        tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                    ),
                )

            assertIs<SummaryResult.Error.Generic>(result)
            assertTrue(result.message.contains("reason", ignoreCase = true))
        }

    @Test
    fun `fails fast without baseline retry and logs error when tool-enabled call fails`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = """{"error": {"code": 500, "message": "Tool call failed"}}""",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "[Alice]: Discussion content")
                val result =
                    summarizer.summarize(input) { _ ->
                        "Earlier messages"
                    }

                assertIs<SummaryResult.Error.ApiError>(result)
                assertEquals(500, result.statusCode)
                assertEquals(1, requestCount, "Must fail fast with 1 upstream attempt and no baseline retry")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `fails without baseline retry and logs error when model attempts more than one lookback turn`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val toolCallResponse =
                    """
                    {
                      "id": "int-loop-1",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "function_call",
                          "id": "call-loop",
                          "name": "fetch_earlier_messages",
                          "arguments": { "reason": "Need even more history" }
                        }
                      ]
                    }
                    """.trimIndent()

                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = toolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "Target conversation")
                val result =
                    summarizer.summarize(input) { _ ->
                        "Earlier context chunk"
                    }

                assertIs<SummaryResult.Error.Generic>(result)
                assertEquals(AiTraceFailureCategory.PARSE_ERROR, classifySummaryError(result))
                assertEquals(2, requestCount, "Must stop after round-2 continuation without a 3rd baseline retry")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `fails without baseline retry and logs error when lookbackProvider throws an exception`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val toolCallResponse =
                    """
                    {
                      "id": "int-prov-err",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "function_call",
                          "id": "call-pe",
                          "name": "fetch_earlier_messages",
                          "arguments": { "reason": "Need history" }
                        }
                      ]
                    }
                    """.trimIndent()

                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = toolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "Target conversation")
                val result =
                    summarizer.summarize(input) { _ ->
                        throw IllegalStateException("Discord API fetch failed")
                    }

                assertIs<SummaryResult.Error.Generic>(result)
                assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, classifySummaryError(result))
                assertEquals(1, requestCount, "Lookback provider failure must not trigger baseline retry")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `fails without baseline retry and logs error when tool continuation throws network exception`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val toolCallResponse =
                    """
                    {
                      "id": "int-cont-exc",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "function_call",
                          "id": "call-ce",
                          "name": "fetch_earlier_messages",
                          "arguments": { "reason": "Need history" }
                        }
                      ]
                    }
                    """.trimIndent()

                val mockEngine =
                    MockEngine {
                        requestCount++
                        if (requestCount == 1) {
                            respond(
                                content = toolCallResponse,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30000L)
                        }
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "Target conversation")
                val result =
                    summarizer.summarize(input) { _ ->
                        "Context data"
                    }

                assertIs<SummaryResult.Error.Timeout>(result)
                assertEquals(2, requestCount, "Continuation failure must stop after round 2 without baseline retry")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `rejects fetch_earlier_messages tool call when call_id is missing without invoking lookbackProvider`() =
        runTest {
            val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                var providerInvoked = false
                val malformedToolCallResponse =
                    """
                    {
                      "id": "int-missing-call-id",
                      "status": "requires_action",
                      "steps": [
                        {
                          "type": "function_call",
                          "name": "fetch_earlier_messages",
                          "arguments": { "reason": "Need earlier context" }
                        }
                      ]
                    }
                    """.trimIndent()

                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = malformedToolCallResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val summarizer =
                    GeminiSummarizer(
                        config = GeminiConfig(apiKey = "test-key"),
                        httpClient = createTestClient(mockEngine),
                    )

                val input = SummaryInput(content = "Target conversation")
                val result =
                    summarizer.summarize(input) { _ ->
                        providerInvoked = true
                        "Should never be fetched"
                    }

                assertFalse(providerInvoked, "lookbackProvider must not run when call_id is missing")
                assertEquals(1, requestCount, "Must fail closed after round 1 without continuation")
                assertIs<SummaryResult.Error.Generic>(result)
                assertEquals(AiTraceFailureCategory.PARSE_ERROR, classifySummaryError(result))
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.ERROR && it.formattedMessage.contains("call_id")
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
