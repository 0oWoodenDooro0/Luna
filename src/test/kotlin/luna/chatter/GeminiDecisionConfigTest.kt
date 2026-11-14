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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.plugin.DefaultPluginContext
import luna.plugin.PluginContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `default decision instructions use Luna only, frame latest message against recent context, and balance engagement with restraint`() =
        runTest {
            var capturedInstructions: String? = null
            val mockEngine =
                MockEngine { request ->
                    val bodyJson = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    capturedInstructions =
                        bodyJson["questions"]
                            ?.jsonObject
                            ?.get("should_participate")
                            ?.jsonObject
                            ?.get("instructions")
                            ?.jsonPrimitive
                            ?.content
                    respond(
                        content = """{"answers":{"should_participate":{"type":"noul","noul":0.8,"confidence":0.8}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            assertTrue(client.shouldParticipate("[Bot Identity: Luna]\n\n[Latest Message]\n[Alice]: Luna thoughts?"))
            val sent = assertNotNull(capturedInstructions)
            assertFalse(sent.contains("露娜"), "Decision instructions must never use 露娜")
            assertTrue(sent.contains("Luna"))
            assertTrue(sent.contains("[Latest Message]"))
            assertTrue(sent.contains("[Recent Context]"))
            assertTrue(sent.contains("[Bot Identity]"))
            assertTrue(sent.contains("1-on-1"))
            assertTrue(sent.contains("bot command"))
            assertTrue(sent.contains("system notice"))
            assertTrue(sent.contains("ok") && sent.contains("lol"))
        }

    @Test
    fun `loads decision instructions and bounded message count from env with negative boundaries and clamping`() {
        val defaultConfig = ChatterDecisionConfig.fromEnv(emptyMap())
        assertEquals(0.7, defaultConfig.threshold)
        assertEquals(15, defaultConfig.decisionMessageCount)
        assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, defaultConfig.instructions)

        val customConfig =
            ChatterDecisionConfig.fromEnv(
                mapOf(
                    "CHATTER_DECISION_INSTRUCTIONS" to "  Evaluate strictly for Luna tech Q&A  ",
                    "CHATTER_DECISION_MESSAGE_COUNT" to "42",
                ),
            )
        assertEquals("Evaluate strictly for Luna tech Q&A", customConfig.instructions)
        assertEquals(42, customConfig.decisionMessageCount)
        assertEquals(0.7, customConfig.threshold)

        // Boundary min and max
        assertEquals(1, ChatterDecisionConfig.fromEnv(mapOf("CHATTER_DECISION_MESSAGE_COUNT" to "1")).decisionMessageCount)
        assertEquals(100, ChatterDecisionConfig.fromEnv(mapOf("CHATTER_DECISION_MESSAGE_COUNT" to "100")).decisionMessageCount)

        // Clamped > 100
        assertEquals(100, ChatterDecisionConfig.fromEnv(mapOf("CHATTER_DECISION_MESSAGE_COUNT" to "150")).decisionMessageCount)
        assertEquals(100, ChatterDecisionConfig.fromEnv(mapOf("CHATTER_DECISION_MESSAGE_COUNT" to "9999999999")).decisionMessageCount)

        // Negative boundaries: blank, invalid, zero, negative fallback to 15; blank instructions fallback to default
        val negativeBoundaries =
            listOf(
                mapOf("CHATTER_DECISION_INSTRUCTIONS" to "   ", "CHATTER_DECISION_MESSAGE_COUNT" to ""),
                mapOf("CHATTER_DECISION_MESSAGE_COUNT" to "   "),
                mapOf("CHATTER_DECISION_MESSAGE_COUNT" to "abc"),
                mapOf("CHATTER_DECISION_MESSAGE_COUNT" to "0"),
                mapOf("CHATTER_DECISION_MESSAGE_COUNT" to "-5"),
            )
        negativeBoundaries.forEach { env ->
            val cfg = ChatterDecisionConfig.fromEnv(env)
            assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, cfg.instructions)
            assertEquals(15, cfg.decisionMessageCount)
        }
    }

    @Test
    fun `loads decision instructions and message count across fromConfig and fromPluginContext with precedence`() {
        // fromConfig precedence: ENV > dotted > camelCase
        val fromConfigEnvWins =
            ChatterDecisionConfig.fromConfig(
                mapOf(
                    "CHATTER_DECISION_INSTRUCTIONS" to "env-wins",
                    "chatter.decision.instructions" to "dotted",
                    "chatter.decisionInstructions" to "camel",
                    "CHATTER_DECISION_MESSAGE_COUNT" to 25,
                    "chatter.decision.messageCount" to 10,
                    "chatter.decisionMessageCount" to 5,
                ),
            )
        assertEquals("env-wins", fromConfigEnvWins.instructions)
        assertEquals(25, fromConfigEnvWins.decisionMessageCount)

        val fromConfigDottedWins =
            ChatterDecisionConfig.fromConfig(
                mapOf(
                    "CHATTER_DECISION_INSTRUCTIONS" to "   ",
                    "chatter.decision.instructions" to "dotted-wins",
                    "chatter.decisionInstructions" to "camel",
                    "chatter.decision.messageCount" to "30",
                    "chatter.decisionMessageCount" to 5,
                ),
            )
        assertEquals("dotted-wins", fromConfigDottedWins.instructions)
        assertEquals(30, fromConfigDottedWins.decisionMessageCount)

        val fromConfigCamel =
            ChatterDecisionConfig.fromConfig(
                mapOf(
                    "chatter.decisionInstructions" to "camel-wins",
                    "chatter.decisionMessageCount" to 150L,
                ),
            )
        assertEquals("camel-wins", fromConfigCamel.instructions)
        assertEquals(100, fromConfigCamel.decisionMessageCount)

        val fromConfigInvalid =
            ChatterDecisionConfig.fromConfig(
                mapOf(
                    "CHATTER_DECISION_MESSAGE_COUNT" to -2,
                ),
            )
        assertEquals(15, fromConfigInvalid.decisionMessageCount)

        // fromPluginContext variants & precedence
        val ctxPrimary =
            DefaultPluginContext(
                kord = mockk(relaxed = true),
                routing = mockk(relaxed = true),
                database = mockk(relaxed = true),
                configMap =
                    mapOf(
                        "CHATTER_DECISION_INSTRUCTIONS" to "ctx-env-instructions",
                        "chatter.decision.instructions" to "ctx-dotted-instructions",
                        "CHATTER_DECISION_MESSAGE_COUNT" to "18",
                        "chatter.decision.messageCount" to 9,
                    ),
            )
        val cfgPrimary = ChatterDecisionConfig.fromPluginContext(ctxPrimary)
        assertEquals("ctx-env-instructions", cfgPrimary.instructions)
        assertEquals(18, cfgPrimary.decisionMessageCount)

        val ctxDotted =
            DefaultPluginContext(
                kord = mockk(relaxed = true),
                routing = mockk(relaxed = true),
                database = mockk(relaxed = true),
                configMap =
                    mapOf(
                        "chatter.decision.instructions" to "ctx-dotted-instructions",
                        "chatter.decisionInstructions" to "ctx-camel-instructions",
                        "chatter.decision.messageCount" to 22,
                        "chatter.decisionMessageCount" to 8,
                    ),
            )
        val cfgDotted = ChatterDecisionConfig.fromPluginContext(ctxDotted)
        assertEquals("ctx-dotted-instructions", cfgDotted.instructions)
        assertEquals(22, cfgDotted.decisionMessageCount)

        val ctxCamel =
            DefaultPluginContext(
                kord = mockk(relaxed = true),
                routing = mockk(relaxed = true),
                database = mockk(relaxed = true),
                configMap =
                    mapOf(
                        "chatter.decisionInstructions" to "ctx-camel-instructions",
                        "chatter.decisionMessageCount" to 200,
                    ),
            )
        val cfgCamel = ChatterDecisionConfig.fromPluginContext(ctxCamel)
        assertEquals("ctx-camel-instructions", cfgCamel.instructions)
        assertEquals(100, cfgCamel.decisionMessageCount)

        val ctxNegative =
            DefaultPluginContext(
                kord = mockk(relaxed = true),
                routing = mockk(relaxed = true),
                database = mockk(relaxed = true),
                configMap =
                    mapOf(
                        "CHATTER_DECISION_INSTRUCTIONS" to "   ",
                        "CHATTER_DECISION_MESSAGE_COUNT" to 0,
                    ),
            )
        val cfgNegative = ChatterDecisionConfig.fromPluginContext(ctxNegative)
        assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, cfgNegative.instructions)
        assertEquals(15, cfgNegative.decisionMessageCount)
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
                ChatterDecisionConfig.fromEnv(
                    mapOf(
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
                    config = ChatterDecisionConfig(apiKey = "valid-key", instructions = "   "),
                    httpClient = createTestClient(mockEngine),
                )
            assertTrue(blankConfigClient.shouldParticipate("[Bot Identity: Luna]\n\n[Latest Message]\n[Bob]: Hello"))

            assertEquals(2, capturedInstructions.size)
            assertEquals("Only reply when Luna is asked about Rust or Kotlin.", capturedInstructions[0])
            assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, capturedInstructions[1])
        }

    @Test
    fun `fromPluginContext resolves Long Int and String fallback chain on strict PluginContext`() {
        val rawMap: Map<String, Any?> =
            mapOf(
                "chatter.decision.timeoutSeconds" to 25,
                "chatter.decisionMessageCount" to " 40 ",
                "chatter.decisionThreshold" to " 0.82 ",
            )
        val strictContext =
            object : PluginContext {
                override val kord: dev.kord.core.Kord = mockk(relaxed = true)
                override val routing: io.ktor.server.routing.Routing = mockk(relaxed = true)
                override val database: org.jetbrains.exposed.v1.jdbc.Database = mockk(relaxed = true)

                @Suppress("UNCHECKED_CAST")
                override fun <T> config(
                    key: String,
                    clazz: Class<T>,
                ): T? {
                    val value = rawMap[key] ?: return null
                    return if (clazz.isInstance(value)) value as T else null
                }
            }

        val config = ChatterDecisionConfig.fromPluginContext(strictContext)
        assertEquals(25_000L, config.timeoutMillis)
        assertEquals(40, config.decisionMessageCount)
        assertEquals(0.82, config.threshold)
    }
}
