package luna.chatter

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.core.ai.ChatImageAttachment
import luna.plugin.DefaultPluginContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiDecisionClientTest {
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
            install(HttpTimeout) {
                requestTimeoutMillis = 3_000
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 3_000
            }
        }

    @Test
    fun `ChatterDecisionConfig defaults to gemini-decision-1, threshold 0_7, 10s timeout, and official Gemini baseUrl`() {
        val config = ChatterDecisionConfig()
        assertEquals("", config.apiKey)
        assertEquals("gemini-decision-1", config.model)
        assertEquals("gemini-decision-1", config.cleanModel)
        assertEquals("https://generativelanguage.googleapis.com", config.baseUrl)
        assertEquals("https://generativelanguage.googleapis.com", config.cleanBaseUrl)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", config.decisionEndpoint)
        assertEquals(0.7, config.threshold)
        assertEquals(10_000L, config.timeoutMillis)
        assertFalse(config.isConfigured)
    }

    @Test
    fun `loads ChatterDecisionConfig correctly from environment map and clamps invalid bounds`() {
        val env =
            mapOf(
                "GEMINI_API_KEY" to "  gemini-env-key  ",
                "GEMINI_BASE_URL" to "https://custom.gemini.local/v1beta/",
                "CHATTER_DECISION_MODEL" to "models/gemini-decision-2",
                "CHATTER_DECISION_THRESHOLD" to "0.85",
                "CHATTER_DECISION_TIMEOUT_SECONDS" to "15",
            )

        val config = ChatterDecisionConfig.fromEnv(env)
        assertEquals("gemini-env-key", config.apiKey)
        assertEquals("https://custom.gemini.local", config.cleanBaseUrl)
        assertEquals("https://custom.gemini.local/v1beta/decisions", config.decisionEndpoint)
        assertEquals("gemini-decision-2", config.cleanModel)
        assertEquals(0.85, config.threshold)
        assertEquals(15_000L, config.timeoutMillis)
        assertTrue(config.isConfigured)

        val clampedHigh = ChatterDecisionConfig.fromEnv(mapOf("CHATTER_DECISION_THRESHOLD" to "1.5"))
        assertEquals(1.0, clampedHigh.threshold)

        val clampedLow = ChatterDecisionConfig.fromEnv(mapOf("CHATTER_DECISION_THRESHOLD" to "-0.2"))
        assertEquals(0.0, clampedLow.threshold)

        val invalidTimeout = ChatterDecisionConfig.fromEnv(mapOf("CHATTER_DECISION_TIMEOUT_SECONDS" to "0"))
        assertEquals(10_000L, invalidTimeout.timeoutMillis)
    }

    @Test
    fun `loads ChatterDecisionConfig from PluginContext supporting camelCase and dot-notation variants`() {
        val primaryContext =
            DefaultPluginContext(
                kord = mockk(relaxed = true),
                routing = mockk(relaxed = true),
                database = mockk(relaxed = true),
                configMap =
                    mapOf(
                        "GEMINI_API_KEY" to "ctx-api-key",
                        "CHATTER_DECISION_MODEL" to "gemini-decision-custom",
                        "CHATTER_DECISION_THRESHOLD" to 0.65,
                        "CHATTER_DECISION_TIMEOUT_SECONDS" to 20L,
                    ),
            )

        val primaryConfig = ChatterDecisionConfig.fromPluginContext(primaryContext)
        assertEquals("ctx-api-key", primaryConfig.apiKey)
        assertEquals("gemini-decision-custom", primaryConfig.cleanModel)
        assertEquals(0.65, primaryConfig.threshold)
        assertEquals(20_000L, primaryConfig.timeoutMillis)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", primaryConfig.decisionEndpoint)
        assertTrue(primaryConfig.isConfigured)

        val variantContext =
            DefaultPluginContext(
                kord = mockk(relaxed = true),
                routing = mockk(relaxed = true),
                database = mockk(relaxed = true),
                configMap =
                    mapOf(
                        "chatter.apiKey" to "chatter-key",
                        "gemini.baseUrl" to "https://proxy.example.com/v1beta/",
                        "chatter.decisionModel" to "gemini-decision-variant",
                        "chatter.decisionThreshold" to "0.75",
                        "chatter.decisionTimeoutSeconds" to 12,
                    ),
            )

        val variantConfig = ChatterDecisionConfig.fromPluginContext(variantContext)
        assertEquals("chatter-key", variantConfig.apiKey)
        assertEquals("https://proxy.example.com", variantConfig.cleanBaseUrl)
        assertEquals("gemini-decision-variant", variantConfig.cleanModel)
        assertEquals(0.75, variantConfig.threshold)
        assertEquals(12_000L, variantConfig.timeoutMillis)

        val dottedContext =
            DefaultPluginContext(
                kord = mockk(relaxed = true),
                routing = mockk(relaxed = true),
                database = mockk(relaxed = true),
                configMap =
                    mapOf(
                        "gemini.apiKey" to "gemini-dot-key",
                        "chatter.decision.model" to "gemini-decision-dot",
                        "chatter.decision.threshold" to 0.8,
                        "chatter.decision.timeoutSeconds" to "8",
                    ),
            )

        val dottedConfig = ChatterDecisionConfig.fromPluginContext(dottedContext)
        assertEquals("gemini-dot-key", dottedConfig.apiKey)
        assertEquals("gemini-decision-dot", dottedConfig.cleanModel)
        assertEquals(0.8, dottedConfig.threshold)
        assertEquals(8_000L, dottedConfig.timeoutMillis)
    }

    @Test
    fun `constructs valid POST v1beta decisions payload with x-goog-api-key header and text input`() =
        runTest {
            var capturedUrl: String? = null
            var capturedMethod: HttpMethod? = null
            var capturedApiKeyHeader: String? = null
            var capturedContentType: String? = null
            var capturedBody: String? = null

            val mockEngine =
                MockEngine { request ->
                    capturedUrl = request.url.toString()
                    capturedMethod = request.method
                    capturedApiKeyHeader = request.headers["x-goog-api-key"]
                    capturedContentType = request.body.contentType?.toString() ?: request.headers[HttpHeaders.ContentType]
                    capturedBody = request.body.toByteArray().decodeToString()

                    respond(
                        content =
                            """
                            {
                              "model": "gemini-decision-1",
                              "answers": {
                                "should_participate": {
                                  "type": "noul",
                                  "noul": 0.88,
                                  "confidence": 0.88
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config =
                ChatterDecisionConfig(
                    apiKey = "gemini-secret-token",
                    model = "gemini-decision-1",
                    threshold = 0.7,
                )
            val client = GeminiDecisionClient(config = config, httpClient = createTestClient(mockEngine))

            val dialogueState = "[UserA]: Has anyone tried the new release?\n[UserB]: Yes, works great!"
            val decision = client.decide(dialogueState)

            assertTrue(decision.shouldParticipate)
            assertEquals(0.88, decision.noul)
            assertEquals(0.88, decision.confidence)
            assertEquals(HttpMethod.Post, capturedMethod)
            assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", capturedUrl)
            assertEquals("gemini-secret-token", capturedApiKeyHeader)
            assertNotNull(capturedContentType)
            assertTrue(capturedContentType!!.startsWith("application/json"))

            assertNotNull(capturedBody)
            val bodyJson = json.parseToJsonElement(capturedBody).jsonObject
            assertEquals("gemini-decision-1", bodyJson["model"]?.jsonPrimitive?.content)

            val inputArray = bodyJson["input"]?.jsonArray
            assertNotNull(inputArray)
            assertEquals(1, inputArray.size)
            val textBlock = inputArray[0].jsonObject
            assertEquals("text", textBlock["type"]?.jsonPrimitive?.content)
            assertEquals(dialogueState, textBlock["text"]?.jsonPrimitive?.content)

            val questions = bodyJson["questions"]?.jsonObject
            assertNotNull(questions)
            val participateQuestion = questions["should_participate"]?.jsonObject
            assertNotNull(participateQuestion)
            assertEquals("noul", participateQuestion["type"]?.jsonPrimitive?.content)
            assertTrue(participateQuestion["instructions"]?.jsonPrimitive?.content?.isNotBlank() == true)
        }

    @Test
    fun `includes low resolution image blocks in input when ChatImageAttachments are provided`() =
        runTest {
            var capturedBody: String? = null
            val mockEngine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content =
                            """
                            {
                              "answers": {
                                "should_participate": {
                                  "type": "noul",
                                  "noul": 0.91,
                                  "confidence": 0.91
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val images =
                listOf(
                    ChatImageAttachment(
                        url = "https://cdn.discordapp.com/attachments/1/2/meme.png",
                        mimeType = "image/png",
                        filename = "meme.png",
                    ),
                    ChatImageAttachment(
                        url = "https://cdn.discordapp.com/attachments/1/3/photo.jpg",
                        mimeType = "image/jpeg",
                        filename = "photo.jpg",
                    ),
                )

            val decision = client.decide("[Alice]: look at these screenshots", images)

            assertTrue(decision.shouldParticipate)
            assertNotNull(capturedBody)
            val inputArray = json.parseToJsonElement(capturedBody).jsonObject["input"]!!.jsonArray
            assertEquals(3, inputArray.size)

            assertEquals("text", inputArray[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("[Alice]: look at these screenshots", inputArray[0].jsonObject["text"]?.jsonPrimitive?.content)

            val img1 = inputArray[1].jsonObject
            assertEquals("image", img1["type"]?.jsonPrimitive?.content)
            assertEquals("https://cdn.discordapp.com/attachments/1/2/meme.png", img1["uri"]?.jsonPrimitive?.content)
            assertEquals("image/png", img1["mime_type"]?.jsonPrimitive?.content)
            assertEquals("low", img1["resolution"]?.jsonPrimitive?.content)

            val img2 = inputArray[2].jsonObject
            assertEquals("image", img2["type"]?.jsonPrimitive?.content)
            assertEquals("https://cdn.discordapp.com/attachments/1/3/photo.jpg", img2["uri"]?.jsonPrimitive?.content)
            assertEquals("image/jpeg", img2["mime_type"]?.jsonPrimitive?.content)
            assertEquals("low", img2["resolution"]?.jsonPrimitive?.content)
        }

    @Test
    fun `participates when noul meets or equals configured threshold and rejects when noul is below threshold despite high confidence`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    val bodyText = request.body.toByteArray().decodeToString()
                    val text =
                        json
                            .parseToJsonElement(bodyText)
                            .jsonObject["input"]!!
                            .jsonArray[0]
                            .jsonObject["text"]!!
                            .jsonPrimitive.content
                    val payload =
                        when {
                            text.contains("exact") -> """{"answers":{"should_participate":{"type":"noul","noul":0.70,"confidence":0.70}}}"""
                            text.contains("high-confidence-rejection") -> """{"answers":{"should_participate":{"type":"noul","noul":0.12,"confidence":0.88}}}"""
                            else -> """{"answers":{"should_participate":{"type":"noul","noul":0.69,"confidence":0.95}}}"""
                        }
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "valid-key", threshold = 0.7),
                    httpClient = createTestClient(mockEngine),
                )

            val exactDecision = client.decide("exact threshold case")
            assertTrue(exactDecision.shouldParticipate)
            assertEquals(0.70, exactDecision.noul)

            // Negative boundary: confidence = max(noul, 1 - noul) = 0.88 >= 0.7, but noul = 0.12 < 0.7
            val strongRejection = client.decide("high-confidence-rejection case")
            assertFalse(strongRejection.shouldParticipate)
            assertEquals(0.12, strongRejection.noul)
            assertEquals(0.88, strongRejection.confidence)

            val justBelow = client.decide("just below threshold")
            assertFalse(justBelow.shouldParticipate)
            assertEquals(0.69, justBelow.noul)
        }

    @Test
    fun `retries once with text-only input when multimodal decision request fails`() =
        runTest {
            val capturedInputs = mutableListOf<Int>()
            val mockEngine =
                MockEngine { request ->
                    val bodyJson = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val inputSize = bodyJson["input"]!!.jsonArray.size
                    capturedInputs.add(inputSize)

                    if (inputSize > 1) {
                        respondError(
                            status = HttpStatusCode.BadRequest,
                            content = """{"error":{"code":400,"message":"Failed to fetch image URI"}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content =
                                """
                                {
                                  "answers": {
                                    "should_participate": {
                                      "type": "noul",
                                      "noul": 0.82,
                                      "confidence": 0.82
                                    }
                                  }
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val client =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "valid-key", threshold = 0.7),
                    httpClient = createTestClient(mockEngine),
                )

            val images = listOf(ChatImageAttachment("https://cdn.discordapp.com/expired.png", "image/png"))
            val decision = client.decide("[Alice]: what is in this image?", images)

            assertTrue(decision.shouldParticipate)
            assertEquals(0.82, decision.noul)
            assertEquals(listOf(2, 1), capturedInputs)
        }

    @Test
    fun `does not retry text-only when multimodal request succeeds with shouldParticipate false`() =
        runTest {
            var requestCount = 0
            val mockEngine =
                MockEngine {
                    requestCount++
                    respond(
                        content =
                            """
                            {
                              "answers": {
                                "should_participate": {
                                  "type": "noul",
                                  "noul": 0.2,
                                  "confidence": 0.8
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "valid-key", threshold = 0.7),
                    httpClient = createTestClient(mockEngine),
                )

            val images = listOf(ChatImageAttachment("https://cdn.discordapp.com/img.png", "image/png"))
            val decision = client.decide("[Alice]: random meme", images)

            assertFalse(decision.shouldParticipate)
            assertEquals(1, requestCount)
        }

    @Test
    fun `fails closed after single text-only retry when both multimodal and fallback requests fail`() =
        runTest {
            var requestCount = 0
            val mockEngine =
                MockEngine {
                    requestCount++
                    throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com/v1beta/decisions", 10_000L)
                }

            val client =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val images = listOf(ChatImageAttachment("https://cdn.discordapp.com/img.png", "image/png"))
            val decision = client.decide("[Alice]: check this out", images)

            assertFalse(decision.shouldParticipate)
            assertNull(decision.noul)
            assertEquals(2, requestCount)
        }

    @Test
    fun `fails closed without caching on timeouts, HTTP 429, HTTP 5xx, error payloads, and malformed JSON`() =
        runTest {
            var callIndex = 0
            val mockEngine =
                MockEngine {
                    callIndex++
                    when (callIndex) {
                        1 -> {
                            throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com/v1beta/decisions", 10_000L)
                        }

                        2 -> {
                            respond(
                                content = """{"error":{"code":429,"message":"Too many requests"}}""",
                                status = HttpStatusCode.TooManyRequests,
                                headers =
                                    headersOf(
                                        HttpHeaders.ContentType to listOf("application/json"),
                                        HttpHeaders.RetryAfter to listOf("5"),
                                    ),
                            )
                        }

                        3 -> {
                            respondError(
                                status = HttpStatusCode.InternalServerError,
                                content = """{"error":{"code":500,"message":"Internal error"}}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        4 -> {
                            respond(
                                content = """{"error":{"code":400,"message":"Bad request"}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        5 -> {
                            respond(
                                content = """{not valid json""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        6 -> {
                            respond(
                                content = """{"answers":{}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            respond(
                                content = """{"answers":{"should_participate":{"type":"noul","noul":0.9,"confidence":0.9}}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }

            val client =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("timeout state"))
            assertFalse(client.shouldParticipate("429 state"))
            assertFalse(client.shouldParticipate("500 state"))
            assertFalse(client.shouldParticipate("error payload state"))
            assertFalse(client.shouldParticipate("malformed json state"))
            assertFalse(client.shouldParticipate("missing answer state"))
            // Transient failures must never be cached: 7th call succeeds
            assertTrue(client.shouldParticipate("recovered state"))
            assertEquals(7, callIndex)
        }

    @Test
    fun `fails closed immediately without HTTP call when API key or dialogue state is blank`() =
        runTest {
            var requestExecuted = false
            val mockEngine =
                MockEngine {
                    requestExecuted = true
                    respond(content = "{}", status = HttpStatusCode.OK)
                }

            val unconfiguredClient =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = ""),
                    httpClient = createTestClient(mockEngine),
                )
            assertFalse(unconfiguredClient.shouldParticipate("[Alice]: Hello"))
            assertFalse(requestExecuted)

            val configuredClient =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )
            assertFalse(configuredClient.shouldParticipate("   "))
            assertFalse(requestExecuted)
        }
}
