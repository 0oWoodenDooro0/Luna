package luna.chatter

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.core.ai.ChatImageAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterGeneratorTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    // --- ChatterGenerator Integration & Error Handling Tests ---

    @Test
    fun `returns null and fails closed when apiKey is not configured`() =
        runTest {
            val config = ChatterConfig(apiKey = "")
            val generator = ChatterGenerator(config = config)

            val reply = generator.generateReply("任何對話紀錄")
            assertNull(reply)
        }

    @Test
    fun `returns null and fails closed when transcript is blank`() =
        runTest {
            val config = ChatterConfig(apiKey = "valid-api-key")
            val generator = ChatterGenerator(config = config)

            val reply = generator.generateReply("   \n\t  ")
            assertNull(reply)

            val emptyListReply = generator.generateReply(emptyList())
            assertNull(emptyListReply)
        }

    @Test
    fun `successfully generates organic reply via sync interactions endpoint and verifies payload parameters`() =
        runTest {
            var recordedUrl: String? = null
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "id": "int-sync-1",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "哈哈真的假的 咖啡團我也要加一 等等直接約在樓下大廳見"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    recordedUrl = request.url.toString()
                    recordedBody = request.body.toByteArray().decodeToString()

                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config =
                ChatterConfig(
                    apiKey = "chatter-test-secret-key",
                    model = "gemini-3.5-flash-lite",
                    baseUrl = "https://generativelanguage.googleapis.com",
                )

            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val transcript =
                """
                [Alice]: 各位午餐想吃什麼？
                [Bob]: 想去喝樓下的手沖咖啡！
                """.trimIndent()

            val reply = generator.generateReply(transcript)

            assertEquals("哈哈真的假的 咖啡團我也要加一 等等直接約在樓下大廳見", reply)
            val url = assertNotNull(recordedUrl)
            val body = assertNotNull(recordedBody)
            assertTrue(url.contains("/v1beta/interactions?key=chatter-test-secret-key"))
            assertFalse(url.contains(":generateContent"))

            val reqObj = json.parseToJsonElement(body).jsonObject
            assertEquals("gemini-3.5-flash-lite", reqObj["model"]?.jsonPrimitive?.content)
            assertEquals(false, reqObj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            assertFalse(body.contains("\"temperature\""))
            assertEquals(
                512,
                reqObj["generation_config"]
                    ?.jsonObject
                    ?.get("max_output_tokens")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )
            assertTrue(body.contains("Alice"))
            assertTrue(body.contains("Bob"))
            assertTrue(body.contains("Luna"))
            val toolTypes = reqObj["tools"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content }
            assertEquals(listOf("google_search"), toolTypes)
        }

    @Test
    fun `returns null gracefully without retry when Interactions API returns 429 rate limit exceeded`() =
        runTest {
            var requestCount = 0
            val mockEngine =
                MockEngine {
                    requestCount++
                    respond(
                        content = """{"error": {"code": 429, "message": "Resource has been exhausted"}}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config = ChatterConfig(apiKey = "valid-key")
            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val reply = generator.generateReply("[User]: rate limit test")
            assertNull(reply)
            assertEquals(1, requestCount)
        }

    @Test
    fun `returns null gracefully without retry when Interactions API request throws timeout exception`() =
        runTest {
            var requestCount = 0
            val mockEngine =
                MockEngine {
                    requestCount++
                    throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30000L)
                }

            val config = ChatterConfig(apiKey = "valid-key")
            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val reply = generator.generateReply("[User]: timeout test")
            assertNull(reply)
            assertEquals(1, requestCount)
        }

    @Test
    fun `returns null gracefully when Interactions API response has safety block or empty output`() =
        runTest {
            val blockedJson =
                """
                {
                  "id": "int-safety-1",
                  "status": "completed",
                  "finish_reason": "SAFETY",
                  "steps": []
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = blockedJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config = ChatterConfig(apiKey = "valid-key")
            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val reply = generator.generateReply("[User]: blocked content")
            assertNull(reply)
        }

    // --- Multimodal, Tiered Video Latency Policy, url_context & google_search, No-Fallback Tests ---

    @Test
    fun `generateReply sends multimodal image inputs and both url_context and google_search when webUrls present without appending citations`() =
        runTest {
            var capturedBody: String? = null
            val responseJson =
                """
                {
                  "id": "int-mm-1",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "url_context_result",
                      "result": [
                        {"retrieved_url": "https://example.com/port-fix", "status": "success"}
                      ]
                    },
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "這張截圖的錯誤是因為 port 8080 被佔用了 😂",
                          "annotations": [
                            {"type": "url_citation", "url": "https://docs.example.org/ports", "title": "Port Fix Guide"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true, urlContextEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val input =
                ChatterInput(
                    transcript = "[Alice]: 幫我看一下這個錯 https://example.com/port-fix [連結預覽: Port Fix] [附帶圖片 1: err.png]",
                    imageAttachments =
                        listOf(
                            ChatImageAttachment(
                                url = "https://cdn.discordapp.com/attachments/1/2/err.png",
                                mimeType = "image/png",
                                filename = "err.png",
                            ),
                        ),
                    webUrls = listOf("https://example.com/port-fix"),
                )

            val reply = generator.generateReply(input)

            assertEquals("這張截圖的錯誤是因為 port 8080 被佔用了", reply)
            val body = checkNotNull(capturedBody)
            val reqObj = json.parseToJsonElement(body).jsonObject
            assertEquals(false, reqObj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            val inputBlocks = checkNotNull(reqObj["input"]?.jsonArray)
            assertEquals("image", inputBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("https://cdn.discordapp.com/attachments/1/2/err.png", inputBlocks[0].jsonObject["uri"]?.jsonPrimitive?.content)
            assertEquals("image/png", inputBlocks[0].jsonObject["mime_type"]?.jsonPrimitive?.content)
            assertEquals("text", inputBlocks[1].jsonObject["type"]?.jsonPrimitive?.content)

            val toolTypes = reqObj["tools"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content }
            assertEquals(listOf("url_context", "google_search"), toolTypes)
            assertEquals(
                512,
                reqObj["generation_config"]
                    ?.jsonObject
                    ?.get("max_output_tokens")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )
            assertFalse(reply.orEmpty().contains("https://docs.example.org/ports"))
            assertFalse(reply.orEmpty().contains("名詞與事實查核:"))
        }

    @Test
    fun `negative boundary omits url_context when webUrls is empty or urlContextEnabled is false, and omits google_search when disabled`() =
        runTest {
            val capturedBodies = mutableListOf<String>()
            val responseJson =
                """{"id": "int-nb-1", "status": "completed", "output_text": "好的收到"}"""

            val mockEngine =
                MockEngine { request ->
                    capturedBodies.add(request.body.toByteArray().decodeToString())
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            // 1. Both flags enabled, but webUrls is empty -> only google_search (no url_context)
            val onlySearchGen =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true, urlContextEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )
            assertEquals("好的收到", onlySearchGen.generateReply(ChatterInput(transcript = "[Alice]: 早安")))

            // 2. webUrls non-empty, but urlContextEnabled = false -> only google_search
            val urlCtxDisabledGen =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true, urlContextEnabled = false),
                    httpClient = createTestClient(mockEngine),
                )
            assertEquals(
                "好的收到",
                urlCtxDisabledGen.generateReply(
                    ChatterInput(transcript = "[Alice]: https://example.com", webUrls = listOf("https://example.com")),
                ),
            )

            // 3. Both flags disabled -> no tools key
            val noToolsGen =
                ChatterGenerator(
                    config =
                        ChatterConfig.fromEnv(
                            mapOf(
                                "GEMINI_API_KEY" to "valid-key",
                                "GEMINI_SEARCH_GROUNDING_ENABLED" to "false",
                                "GEMINI_URL_CONTEXT_ENABLED" to "false",
                            ),
                        ),
                    httpClient = createTestClient(mockEngine),
                )
            assertEquals(
                "好的收到",
                noToolsGen.generateReply(
                    ChatterInput(transcript = "[Alice]: https://example.com", webUrls = listOf("https://example.com")),
                ),
            )

            assertEquals(3, capturedBodies.size)
            val obj1 = json.parseToJsonElement(capturedBodies[0]).jsonObject
            assertEquals(listOf("google_search"), obj1["tools"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content })
            val obj2 = json.parseToJsonElement(capturedBodies[1]).jsonObject
            assertEquals(listOf("google_search"), obj2["tools"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content })
            val obj3 = json.parseToJsonElement(capturedBodies[2]).jsonObject
            assertFalse(obj3.containsKey("tools"))
        }
}
