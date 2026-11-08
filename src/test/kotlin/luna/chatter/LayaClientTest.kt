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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.plugin.DefaultPluginContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayaClientTest {
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
    fun `constructs valid JSON payload and headers for POST v1 systemone`() =
        runTest {
            var capturedUrl: String? = null
            var capturedAuthHeader: String? = null
            var capturedContentType: String? = null
            var capturedBody: String? = null

            val mockEngine =
                MockEngine { request ->
                    capturedUrl = request.url.toString()
                    capturedAuthHeader = request.headers[HttpHeaders.Authorization]
                    capturedContentType = request.body.contentType?.toString() ?: request.headers[HttpHeaders.ContentType]
                    capturedBody = request.body.toByteArray().decodeToString()

                    respond(
                        content =
                            """
                            {
                              "model": "convaiinnovations/laya",
                              "answers": {
                                "should_participate": {
                                  "noul": true,
                                  "probability": 0.88,
                                  "confidence": 0.88
                                }
                              },
                              "usage": { "input_tokens": 42 }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config =
                LayaConfig(
                    apiKey = "laya-secret-token",
                    threshold = 0.7,
                )
            val client = LayaClient(config = config, httpClient = createTestClient(mockEngine))

            val dialogueState = "UserA: Has anyone tried the new release?\nUserB: Yes, works great!"
            val decision = client.shouldParticipate(dialogueState)

            assertTrue(decision)
            assertEquals("https://api.laya.studio/v1/systemone", capturedUrl)
            assertEquals("Bearer laya-secret-token", capturedAuthHeader)
            assertNotNull(capturedContentType)
            assertTrue(capturedContentType!!.startsWith("application/json"))

            assertNotNull(capturedBody)
            val bodyJson = json.parseToJsonElement(capturedBody).jsonObject
            assertEquals(dialogueState, bodyJson["state"]?.jsonPrimitive?.content)

            val questions = bodyJson["questions"]?.jsonObject
            assertNotNull(questions)
            val participateQuestion = questions["should_participate"]?.jsonObject
            assertNotNull(participateQuestion)
            assertEquals("noul", participateQuestion["type"]?.jsonPrimitive?.content)
            assertTrue(participateQuestion["instructions"]?.jsonPrimitive?.content?.isNotBlank() == true)
        }

    @Test
    fun `returns true when noul is true and probability is greater than or equal to default threshold`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "model": "convaiinnovations/laya",
                              "answers": {
                                "should_participate": {
                                  "noul": true,
                                  "probability": 0.75,
                                  "confidence": 0.75
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token", threshold = 0.7),
                    httpClient = createTestClient(mockEngine),
                )

            val result = client.shouldParticipate("User: Anyone know how to fix this issue?")
            assertTrue(result)

            val decision = client.decide("User: Anyone know how to fix this issue?")
            assertTrue(decision.shouldParticipate)
            assertEquals(true, decision.noul)
            assertEquals(0.75, decision.probability)
        }

    @Test
    fun `returns true when noul is true and probability exactly matches configured threshold`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "answers": {
                                "should_participate": {
                                  "noul": true,
                                  "probability": 0.70
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token", threshold = 0.7),
                    httpClient = createTestClient(mockEngine),
                )

            assertTrue(client.shouldParticipate("Context"))
        }

    @Test
    fun `returns true when probability is missing but confidence meets threshold`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "answers": {
                                "should_participate": {
                                  "noul": true,
                                  "confidence": 0.82
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token", threshold = 0.7),
                    httpClient = createTestClient(mockEngine),
                )

            assertTrue(client.shouldParticipate("Context"))
        }

    @Test
    fun `returns false when noul is false even if probability is high`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "answers": {
                                "should_participate": {
                                  "noul": false,
                                  "probability": 0.95,
                                  "confidence": 0.95
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token", threshold = 0.7),
                    httpClient = createTestClient(mockEngine),
                )

            val result = client.shouldParticipate("User: bye")
            assertFalse(result)

            val decision = client.decide("User: bye")
            assertFalse(decision.shouldParticipate)
            assertEquals(false, decision.noul)
        }

    @Test
    fun `returns false when probability falls below configured threshold`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "answers": {
                                "should_participate": {
                                  "noul": true,
                                  "probability": 0.69
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token", threshold = 0.7),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("Context"))
        }

    @Test
    fun `respects custom threshold configured on LayaConfig`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "answers": {
                                "should_participate": {
                                  "noul": true,
                                  "probability": 0.55
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token", threshold = 0.5),
                    httpClient = createTestClient(mockEngine),
                )

            assertTrue(client.shouldParticipate("Context"))
        }

    @Test
    fun `fails closed and returns false on network timeout without throwing`() =
        runTest {
            val mockEngine =
                MockEngine {
                    throw HttpRequestTimeoutException("https://api.laya.studio/v1/systemone", 3_000L)
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token"),
                    httpClient = createTestClient(mockEngine),
                )

            val result = client.shouldParticipate("User: Anyone here?")
            assertFalse(result)

            val decision = client.decide("User: Anyone here?")
            assertFalse(decision.shouldParticipate)
            assertNull(decision.noul)
        }

    @Test
    fun `fails closed and returns false on HTTP 500 internal server error`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respondError(
                        status = HttpStatusCode.InternalServerError,
                        content = """{"error": "Internal server error"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token"),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("User: Anyone here?"))
        }

    @Test
    fun `fails closed and returns false on HTTP 503 service unavailable`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respondError(
                        status = HttpStatusCode.ServiceUnavailable,
                        content = "Service Temporarily Unavailable",
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token"),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("User: Anyone here?"))
        }

    @Test
    fun `fails closed and returns false on HTTP 429 rate limit error`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content = """{"error": "Too many requests"}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                HttpHeaders.RetryAfter to listOf("10"),
                            ),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token"),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("User: Anyone here?"))
        }

    @Test
    fun `fails closed and returns false on HTTP 401 unauthorized`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respondError(
                        status = HttpStatusCode.Unauthorized,
                        content = """{"error": "Invalid API token"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "bad-token"),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("User: Anyone here?"))
        }

    @Test
    fun `fails closed when response payload has missing question answer`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "model": "convaiinnovations/laya",
                              "answers": {}
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token"),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("User: Anyone here?"))
        }

    @Test
    fun `fails closed immediately when API key is blank`() =
        runTest {
            var requestExecuted = false
            val mockEngine =
                MockEngine {
                    requestExecuted = true
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = ""),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("User: Anyone here?"))
            assertFalse(requestExecuted)
        }

    @Test
    fun `fails closed immediately when dialogue state is blank`() =
        runTest {
            var requestExecuted = false
            val mockEngine =
                MockEngine {
                    requestExecuted = true
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                    )
                }

            val client =
                LayaClient(
                    config = LayaConfig(apiKey = "valid-token"),
                    httpClient = createTestClient(mockEngine),
                )

            assertFalse(client.shouldParticipate("   "))
            assertFalse(requestExecuted)
        }

    @Test
    fun `loads LayaConfig correctly from environment map`() {
        val env =
            mapOf(
                "LAYA_API_KEY" to "env-test-key",
                "LAYA_THRESHOLD" to "0.85",
                "LAYA_ENDPOINT" to "https://custom.laya.local/v1/systemone/",
            )

        val config = LayaConfig.fromEnv(env)
        assertEquals("env-test-key", config.apiKey)
        assertEquals(0.85, config.threshold)
        assertEquals("https://custom.laya.local/v1/systemone", config.cleanEndpoint)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `falls back to defaults in LayaConfig when env is empty`() {
        val config = LayaConfig.fromEnv(emptyMap())
        assertEquals("", config.apiKey)
        assertEquals(0.7, config.threshold)
        assertEquals("https://api.laya.studio/v1/systemone", config.endpoint)
        assertFalse(config.isConfigured)
    }

    @Test
    fun `loads LayaConfig correctly from PluginContext`() {
        val mockContext =
            DefaultPluginContext(
                kord = io.mockk.mockk(relaxed = true),
                routing = io.mockk.mockk(relaxed = true),
                database = io.mockk.mockk(relaxed = true),
                configMap =
                    mapOf(
                        "LAYA_API_KEY" to "ctx-api-key",
                        "LAYA_THRESHOLD" to 0.65,
                    ),
            )

        val config = LayaConfig.fromPluginContext(mockContext)
        assertEquals("ctx-api-key", config.apiKey)
        assertEquals(0.65, config.threshold)
        assertEquals("https://api.laya.studio/v1/systemone", config.endpoint)
        assertEquals(10_000L, config.timeoutMillis)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `loads timeoutMillis correctly from environment map and plugin context`() {
        val envSeconds =
            mapOf(
                "LAYA_API_KEY" to "key",
                "LAYA_TIMEOUT_SECONDS" to "15",
            )
        val configSec = LayaConfig.fromEnv(envSeconds)
        assertEquals(15_000L, configSec.timeoutMillis)

        val defaultConf = LayaConfig.fromEnv(emptyMap())
        assertEquals(10_000L, defaultConf.timeoutMillis)

        val mockContext =
            DefaultPluginContext(
                kord = io.mockk.mockk(relaxed = true),
                routing = io.mockk.mockk(relaxed = true),
                database = io.mockk.mockk(relaxed = true),
                configMap =
                    mapOf(
                        "LAYA_API_KEY" to "key",
                        "LAYA_TIMEOUT_SECONDS" to 20L,
                    ),
            )
        val contextConf = LayaConfig.fromPluginContext(mockContext)
        assertEquals(20_000L, contextConf.timeoutMillis)
    }
}
