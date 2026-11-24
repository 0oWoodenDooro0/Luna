package luna.chatter.schedule

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import luna.core.ai.GeminiInteractionClient
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeferredCatchUpTriageClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private val currentBlock =
        ScheduleBlock(
            startMinute = 900,
            endMinute = 960,
            activityTitle = "下午茶休息",
            activityDetail = "泡杯紅茶滑一下手機看看群組",
            statusText = "🍵 休息滑手機中",
            availability = AvailabilityState.BREAK,
        )

    private fun interactionSuccessResponse(text: String): String =
        buildJsonObject {
            put("id", "int-triage-1")
            put("status", "completed")
            put(
                "steps",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "model_output")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", text)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()

    private fun createClient(
        engine: MockEngine,
        apiKey: String = "test-api-key",
    ): DeferredCatchUpTriageClient {
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(json)
                }
            }
        val interactionClient = GeminiInteractionClient(httpClient = httpClient)
        return DeferredCatchUpTriageClient(
            interactionClient = interactionClient,
            model = "gemini-3.5-flash-lite",
            apiKey = apiKey,
            baseUrl = "https://generativelanguage.googleapis.com",
        )
    }

    @Test
    fun `parses valid fenced JSON, prioritizes direct mentions, and enforces maxAmbient cap`() =
        runTest {
            var capturedBody = ""
            val payloadJson =
                """
                ```json
                {
                  "selections": [
                    { "messageId": "101", "action": "REACT", "reason": "ambient meme" },
                    { "messageId": "102", "action": "REPLY", "reason": "direct question to Luna" },
                    { "messageId": "103", "action": "REPLY", "reason": "interesting anime news" },
                    { "messageId": "104", "action": "REACT", "reason": "third ambient should be capped" }
                  ]
                }
                ```
                """.trimIndent()

            val engine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = interactionSuccessResponse(payloadJson),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val candidates =
                listOf(
                    CatchUpCandidateMessage("101", "Alice", "這個梗圖超好笑", 1_000L, isDirectMention = false, isReplyToLuna = false),
                    CatchUpCandidateMessage("102", "Bob", "@Luna 晚點要打遊戲嗎", 2_000L, isDirectMention = true, isReplyToLuna = false),
                    CatchUpCandidateMessage("103", "Carol", "新番剛出預告了！", 3_000L, isDirectMention = false, isReplyToLuna = false),
                    CatchUpCandidateMessage("104", "Dave", "剛買了珍奶", 4_000L, isDirectMention = false, isReplyToLuna = false),
                )

            val client = createClient(engine)
            val result =
                client.triage(
                    candidates = candidates,
                    previousAvailability = AvailabilityState.BUSY,
                    currentBlock = currentBlock,
                    maxAmbient = 2,
                )

            assertTrue(result.isSuccess, "Expected triage success but got ${result.exceptionOrNull()}")
            val selections = result.getOrThrow()
            assertEquals(3, selections.size)
            // Direct mention prioritized first
            assertEquals("102", selections[0].messageId)
            assertEquals(CatchUpAction.REPLY, selections[0].action)
            // Ambient capped at 2 (101 and 103 kept, 104 dropped)
            assertEquals(listOf("101", "103"), selections.drop(1).map { it.messageId })
            assertEquals(CatchUpAction.REACT, selections[1].action)
            assertEquals(CatchUpAction.REPLY, selections[2].action)
            assertTrue(capturedBody.contains("下午茶休息"), "Request should include current schedule block context")
        }

    @Test
    fun `discards invalid, out-of-range, duplicate messageIds and unknown actions safely`() =
        runTest {
            val payloadJson =
                """
                {
                  "selections": [
                    { "messageId": "999", "action": "REPLY", "reason": "hallucinated id" },
                    { "messageId": "101", "action": "INVALID_ACTION", "reason": "bad action" },
                    { "messageId": "102", "action": "REPLY", "reason": "valid mention" },
                    { "messageId": "102", "action": "REACT", "reason": "duplicate id" }
                  ]
                }
                """.trimIndent()

            val engine =
                MockEngine {
                    respond(
                        content = interactionSuccessResponse(payloadJson),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val candidates =
                listOf(
                    CatchUpCandidateMessage("101", "Alice", "hello", 1_000L, isDirectMention = false, isReplyToLuna = false),
                    CatchUpCandidateMessage("102", "Bob", "@Luna ping", 2_000L, isDirectMention = true, isReplyToLuna = false),
                )

            val result =
                createClient(engine).triage(
                    candidates = candidates,
                    previousAvailability = AvailabilityState.SLEEPING,
                    currentBlock = currentBlock,
                    maxAmbient = 2,
                )

            assertTrue(result.isSuccess, "Expected triage to succeed after discarding invalid targets")
            val selections = result.getOrThrow()
            assertEquals(1, selections.size)
            assertEquals("102", selections.single().messageId)
            assertEquals(CatchUpAction.REPLY, selections.single().action)
        }

    @Test
    fun `returns failure and logs WARN on malformed JSON or HTTP error`() =
        runTest {
            val logger = LoggerFactory.getLogger(DeferredCatchUpTriageClient::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            try {
                val engine =
                    MockEngine {
                        respond(
                            content = """{"error":{"message":"Service Unavailable"}}""",
                            status = HttpStatusCode.ServiceUnavailable,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val candidates =
                    listOf(
                        CatchUpCandidateMessage("101", "Alice", "hello", 1_000L, isDirectMention = false, isReplyToLuna = false),
                    )

                val result =
                    createClient(engine).triage(
                        candidates = candidates,
                        previousAvailability = AvailabilityState.BUSY,
                        currentBlock = currentBlock,
                        maxAmbient = 2,
                    )

                assertTrue(result.isFailure, "Expected triage failure on HTTP 503 response")
                assertTrue(
                    appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("DeferredCatchUpTriageClient") },
                    "Expected observable WARN log on triage failure",
                )
            } finally {
                logger.detachAppender(appender)
            }
        }
}
