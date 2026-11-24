package luna.chatter

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import luna.config.ConfigValidationException
import luna.config.EnvReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeminiDecisionConfigTest {
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
    fun `loads ChatterConfig decision section correctly from EnvReader`() {
        val env =
            EnvReader.of(
                "GEMINI_API_KEY" to "  gemini-env-key  ",
                "GEMINI_BASE_URL" to "https://custom.gemini.local/v1beta/",
                "CHATTER_DECISION_MODEL" to "models/gemini-decision-2",
                "CHATTER_DECISION_THRESHOLD" to "0.85",
                "CHATTER_DECISION_TIMEOUT_SECONDS" to "15",
            )

        val config = ChatterConfig.from(env)
        assertEquals("gemini-env-key", config.apiKey)
        assertEquals("https://custom.gemini.local", config.cleanBaseUrl)
        assertEquals("https://custom.gemini.local/v1beta/decisions", config.decisionEndpoint)
        assertEquals("gemini-decision-2", config.decision.cleanModel)
        assertEquals(0.85, config.decision.threshold)
        assertEquals(15_000L, config.decision.timeoutMillis)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `ChatterConfig decision section treats unset and blank values as defaults and ignores legacy or chat model keys`() {
        val defaultConfig = ChatterConfig.from(EnvReader.of())
        assertEquals("", defaultConfig.apiKey)
        assertEquals(ChatterConfig.DEFAULT_DECISION_MODEL, defaultConfig.decision.model)
        assertEquals(ChatterConfig.DEFAULT_BASE_URL, defaultConfig.baseUrl)
        assertEquals(0.7, defaultConfig.decision.threshold)
        assertEquals(15_000L, defaultConfig.decision.timeoutMillis)
        assertEquals(15, defaultConfig.decision.decisionMessageCount)
        assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, defaultConfig.decision.instructions)

        val blankAndLegacy =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_DECISION_MODEL" to "   ",
                    "CHATTER_GEMINI_MODEL" to "gemini-chat-model",
                    "GEMINI_MODEL" to "gemini-default-model",
                    "CHATTER_DECISION_INSTRUCTIONS" to "   ",
                    "CHATTER_DECISION_MESSAGE_COUNT" to "   ",
                    "CHATTER_DECISION_THRESHOLD" to "",
                    "CHATTER_DECISION_TIMEOUT_SECONDS" to "  ",
                    "chatter.decisionModel" to "legacy-model",
                    "chatter.decision.threshold" to "0.2",
                    "gemini.baseUrl" to "https://legacy.example.com",
                ),
            )
        assertEquals(ChatterConfig.DEFAULT_DECISION_MODEL, blankAndLegacy.decision.model)
        assertEquals(ChatterConfig.DEFAULT_BASE_URL, blankAndLegacy.baseUrl)
        assertEquals(0.7, blankAndLegacy.decision.threshold)
        assertEquals(15_000L, blankAndLegacy.decision.timeoutMillis)
        assertEquals(15, blankAndLegacy.decision.decisionMessageCount)
        assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, blankAndLegacy.decision.instructions)
    }

    @Test
    fun `loads decision instructions and bounded message count at valid boundaries`() {
        val customConfig =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_DECISION_INSTRUCTIONS" to "  Evaluate strictly for Luna tech Q&A  ",
                    "CHATTER_DECISION_MESSAGE_COUNT" to "42",
                ),
            )
        assertEquals("Evaluate strictly for Luna tech Q&A", customConfig.decision.instructions)
        assertEquals(42, customConfig.decision.decisionMessageCount)

        assertEquals(1, ChatterConfig.from(EnvReader.of("CHATTER_DECISION_MESSAGE_COUNT" to "1")).decision.decisionMessageCount)
        assertEquals(100, ChatterConfig.from(EnvReader.of("CHATTER_DECISION_MESSAGE_COUNT" to "100")).decision.decisionMessageCount)
        assertEquals(0.0, ChatterConfig.from(EnvReader.of("CHATTER_DECISION_THRESHOLD" to "0.0")).decision.threshold)
        assertEquals(1.0, ChatterConfig.from(EnvReader.of("CHATTER_DECISION_THRESHOLD" to "1.0")).decision.threshold)
    }

    @Test
    fun `ChatterConfig decision section fails fast with ConfigValidationException on invalid or out-of-range values`() {
        val invalidCases =
            listOf(
                "CHATTER_DECISION_THRESHOLD" to "1.5",
                "CHATTER_DECISION_THRESHOLD" to "-0.2",
                "CHATTER_DECISION_THRESHOLD" to "not-a-number",
                "CHATTER_DECISION_THRESHOLD" to "NaN",
                "CHATTER_DECISION_TIMEOUT_SECONDS" to "0",
                "CHATTER_DECISION_TIMEOUT_SECONDS" to "-5",
                "CHATTER_DECISION_TIMEOUT_SECONDS" to "abc",
                "CHATTER_DECISION_MESSAGE_COUNT" to "0",
                "CHATTER_DECISION_MESSAGE_COUNT" to "-5",
                "CHATTER_DECISION_MESSAGE_COUNT" to "150",
                "CHATTER_DECISION_MESSAGE_COUNT" to "9999999999",
                "CHATTER_DECISION_MESSAGE_COUNT" to "abc",
            )
        for ((key, value) in invalidCases) {
            val ex =
                assertFailsWith<ConfigValidationException>("Expected ConfigValidationException for $key=$value") {
                    ChatterConfig.from(EnvReader.of(key to value))
                }
            assertTrue(ex.message?.contains(key) == true, "Expected error message to mention $key: ${ex.message}")
        }
    }

    @Test
    fun `GeminiDecisionClient transmits configured CHATTER_DECISION_INSTRUCTIONS override in POST v1beta decisions`() =
        runTest {
            val capturedInstructions = mutableListOf<String?>()
            val mockEngine =
                MockEngine { request ->
                    val bodyJson = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    capturedInstructions.add(
                        bodyJson["questions"]
                            ?.jsonObject
                            ?.get("should_participate")
                            ?.jsonObject
                            ?.get("instructions")
                            ?.jsonPrimitive
                            ?.content,
                    )
                    respond(
                        content = """{"answers":{"should_participate":{"type":"noul","noul":0.85,"confidence":0.85}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val customConfig =
                ChatterConfig.from(
                    EnvReader.of(
                        "GEMINI_API_KEY" to "valid-key",
                        "CHATTER_DECISION_INSTRUCTIONS" to "Only reply when Luna is asked about Rust or Kotlin.",
                    ),
                )
            val customClient =
                GeminiDecisionClient(
                    config = customConfig,
                    httpClient = createTestClient(mockEngine),
                )
            assertTrue(customClient.shouldParticipate("[Bot Identity: Luna]\n\n[Latest Message]\n[Alice]: Kotlin question"))

            // Blank instructions on direct config construction safely fall back to DEFAULT_INSTRUCTIONS
            val blankConfigClient =
                GeminiDecisionClient(
                    config = ChatterConfig(apiKey = "valid-key", decision = ChatterConfig.Decision(instructions = "   ")),
                    httpClient = createTestClient(mockEngine),
                )
            assertTrue(blankConfigClient.shouldParticipate("[Bot Identity: Luna]\n\n[Latest Message]\n[Bob]: Hello"))

            assertEquals(2, capturedInstructions.size)
            assertEquals("Only reply when Luna is asked about Rust or Kotlin.", capturedInstructions[0])
            assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, capturedInstructions[1])
        }
}
