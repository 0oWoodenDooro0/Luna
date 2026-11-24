package luna.summarizer

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.GeminiTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeminiSummarizerLookbackTest {
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
    fun `serializes tools array with fetch_earlier_messages function declaration when tools are provided`() =
        runTest {
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "id": "int-lb-1",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{ "type": "text", "text": "TL;DR: Summary without lookback." }]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    recordedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val input =
                SummaryInput(
                    content = "[Alice]: Let's wrap up.",
                    tools = listOf(GeminiInteractionTools.FETCH_EARLIER_MESSAGES),
                )
            val result = summarizer.summarize(input)
            assertIs<SummaryResult.Success>(result)

            val body = checkNotNull(recordedBody)
            assertTrue(body.contains("\"tools\""))
            assertTrue(body.contains("\"type\":\"function\""))
            assertTrue(body.contains(GeminiTools.FETCH_EARLIER_MESSAGES))
            assertTrue(body.contains("reason"))
        }

    @Test
    fun `omits tools field from serialized payload when no tools are configured`() =
        runTest {
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "id": "int-no-tools",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [{ "type": "text", "text": "TL;DR: Normal summary." }]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    recordedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key", searchGroundingEnabled = false, urlContextEnabled = false),
                    httpClient = createTestClient(mockEngine),
                )

            val result = summarizer.summarize(SummaryInput(content = "[Alice]: Normal discussion"))
            assertIs<SummaryResult.Success>(result)

            val body = checkNotNull(recordedBody)
            assertFalse(body.contains("\"tools\""))
        }

    @Test
    fun `executes multi-turn lookback when lookbackProvider is passed and model invokes fetch_earlier_messages`() =
        runTest {
            var requestCount = 0
            val recordedPayloads = mutableListOf<String>()

            val toolCallResponse =
                """
                {
                  "id": "int-multi-1",
                  "status": "requires_action",
                  "steps": [
                    {
                      "type": "function_call",
                      "id": "call-m1",
                      "name": "fetch_earlier_messages",
                      "arguments": { "reason": "Reference to earlier discussion is unclear" }
                    }
                  ]
                }
                """.trimIndent()

            val finalResponse =
                """
                {
                  "id": "int-multi-2",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "TL;DR:\n團隊針對資料庫與架構完成深入討論並達成共識。\n\n核心重點:\n• 確認採用 PostgreSQL"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    requestCount++
                    recordedPayloads.add(request.body.toByteArray().decodeToString())
                    val body = if (requestCount == 1) toolCallResponse else finalResponse
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val summarizer =
                GeminiSummarizer(
                    config = GeminiConfig(apiKey = "test-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val input = SummaryInput(content = "[Charlie]: I agree with that database choice.")
            var capturedReason: String? = null
            val result =
                summarizer.summarize(input) { reason ->
                    capturedReason = reason
                    "[前置歷史背景]\n[Alice]: Let's use PostgreSQL.\n\n[當前核心討論]\n[Charlie]: I agree with that database choice."
                }

            assertIs<SummaryResult.Success>(result)
            assertEquals("Reference to earlier discussion is unclear", capturedReason)
            assertTrue(result.text.contains("團隊針對資料庫與架構完成深入討論並達成共識"))
            assertEquals(2, requestCount)
            assertTrue(recordedPayloads[0].contains("fetch_earlier_messages"))
            assertTrue(recordedPayloads[1].contains("\"previous_interaction_id\":\"int-multi-1\""))
            assertTrue(recordedPayloads[1].contains("function_result"))
            assertTrue(recordedPayloads[1].contains("Let's use PostgreSQL"))
        }
}
