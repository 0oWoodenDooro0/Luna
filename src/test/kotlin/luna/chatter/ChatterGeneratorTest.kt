package luna.chatter

import dev.kord.core.Kord
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
import io.ktor.server.routing.Routing
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
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

    // --- Configuration Tests ---

    @Test
    fun `ChatterConfig defaults to gemini-3_5-flash-lite and official baseUrl`() {
        val config = ChatterConfig()
        assertEquals("gemini-3.5-flash-lite", config.model)
        assertEquals("gemini-3.5-flash-lite", config.cleanModel)
        assertEquals("https://generativelanguage.googleapis.com", config.baseUrl)
        assertEquals("https://generativelanguage.googleapis.com", config.cleanBaseUrl)
        assertFalse(config.isConfigured)
    }

    @Test
    fun `ChatterConfig resolves from environment prioritizing CHATTER_GEMINI_MODEL`() {
        val env =
            mapOf(
                "GEMINI_API_KEY" to "fallback-key",
                "CHATTER_GEMINI_MODEL" to "models/gemini-custom-chatter",
                "GEMINI_MODEL" to "gemini-default",
                "GEMINI_BASE_URL" to "https://custom.gemini.api/v1beta/",
            )
        val config = ChatterConfig.fromEnv(env)
        assertEquals("fallback-key", config.apiKey)
        assertTrue(config.isConfigured)
        assertEquals("gemini-custom-chatter", config.cleanModel)
        assertEquals("https://custom.gemini.api", config.cleanBaseUrl)
    }

    @Test
    fun `ChatterConfig falls back to generic GEMINI variables when chatter variables are absent`() {
        val env =
            mapOf(
                "GEMINI_API_KEY" to "generic-gemini-key",
                "GEMINI_MODEL" to "gemini-1.5-pro",
                "GEMINI_BASE_URL" to "https://generativelanguage.googleapis.com/",
            )
        val config = ChatterConfig.fromEnv(env)
        assertEquals("generic-gemini-key", config.apiKey)
        assertTrue(config.isConfigured)
        assertEquals("gemini-1.5-pro", config.cleanModel)
        assertEquals("https://generativelanguage.googleapis.com", config.cleanBaseUrl)
    }

    @Test
    fun `ChatterConfig fromPluginContext preserves default official baseUrl when only global BASE_URL is present`() {
        val context =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap =
                    mapOf(
                        "BASE_URL" to "http://localhost:8080/s/",
                        "GEMINI_API_KEY" to "test-gemini-key",
                    ),
            )
        val config = ChatterConfig.fromPluginContext(context)
        assertEquals("https://generativelanguage.googleapis.com", config.baseUrl)
        assertEquals("https://generativelanguage.googleapis.com", config.cleanBaseUrl)
    }

    // --- Prompt Builder & Persona Tests ---

    @Test
    fun `ChatterPromptBuilder formats dialogue transcript with speaker attribution`() {
        val messages =
            listOf(
                ChatMessage(author = "Alice", content = "大家早安！今天天氣真好。"),
                ChatMessage(author = "Bob", content = "早安 Alice，等下要一起喝咖啡嗎？"),
                ChatMessage(author = "Charlie", content = "算我一份！我也要去。"),
            )

        val formatted = ChatterPromptBuilder.formatTranscript(messages)
        assertTrue(formatted.contains("[Alice]: 大家早安！今天天氣真好。"))
        assertTrue(formatted.contains("[Bob]: 早安 Alice，等下要一起喝咖啡嗎？"))
        assertTrue(formatted.contains("[Charlie]: 算我一份！我也要去。"))
    }

    @Test
    fun `ChatterPromptBuilder system instruction defines Luna friendly community peer persona`() {
        val systemPrompt = ChatterPromptBuilder.buildSystemInstruction()
        assertTrue(systemPrompt.contains("Luna"))
        assertTrue(systemPrompt.contains("社群夥伴") || systemPrompt.contains("社群") || systemPrompt.contains("同儕"))
        assertTrue(systemPrompt.contains("繁體中文") || systemPrompt.contains("zh-TW"))
        assertTrue(systemPrompt.contains("AI") && (systemPrompt.contains("開場白") || systemPrompt.contains("套話") || systemPrompt.contains("禁止")))
    }

    @Test
    fun `ChatterPromptBuilder sanitizes common AI boilerplate prefixes and wrapping code blocks`() {
        val rawWithBoilerplate = "身為一個AI語言模型，我覺得今天去喝拿鐵是個很棒的提議！"
        val sanitized = ChatterPromptBuilder.sanitizeResponse(rawWithBoilerplate)
        assertEquals("我覺得今天去喝拿鐵是個很棒的提議！", sanitized)

        val rawWithEnglishBoilerplate = "As an AI, I really love this community discussion!"
        val sanitizedEnglish = ChatterPromptBuilder.sanitizeResponse(rawWithEnglishBoilerplate)
        assertEquals("I really love this community discussion!", sanitizedEnglish)

        val rawWithMarkdownFence = "```markdown\n這是一段自然對話回覆。\n```"
        val sanitizedFence = ChatterPromptBuilder.sanitizeResponse(rawWithMarkdownFence)
        assertEquals("這是一段自然對話回覆。", sanitizedFence)
    }

    // --- 2,000-Character Truncation & Negative Boundary Coverage ---

    @Test
    fun `ChatterPromptBuilder preserves response intact when length is at most 2000 characters`() {
        val textExactly2000 = "x".repeat(2000)
        val result = ChatterPromptBuilder.truncateToDiscordLimit(textExactly2000)
        assertEquals(2000, result.length)
        assertEquals(textExactly2000, result)

        val shortText = "哈囉大家！我是 Luna，今天一起加油吧！"
        val shortResult = ChatterPromptBuilder.truncateToDiscordLimit(shortText)
        assertEquals(shortText, shortResult)
    }

    @Test
    fun `ChatterPromptBuilder truncates gracefully with trailing ellipsis when length exceeds 2000 characters`() {
        val text2001 = "a".repeat(2001)
        val result2001 = ChatterPromptBuilder.truncateToDiscordLimit(text2001)
        assertEquals(2000, result2001.length)
        assertTrue(result2001.endsWith("..."))
        assertEquals("a".repeat(1997) + "...", result2001)

        val text3500 = "b".repeat(3500)
        val result3500 = ChatterPromptBuilder.truncateToDiscordLimit(text3500)
        assertEquals(2000, result3500.length)
        assertTrue(result3500.endsWith("..."))
        assertEquals("b".repeat(1997) + "...", result3500)
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
    fun `successfully generates organic reply from transcript and verifies request parameters`() =
        runTest {
            var recordedUrl: String? = null
            var recordedBody: String? = null

            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "text": "哈哈真的假的！咖啡團我也要加一，等等直接約在樓下大廳見！"
                          }
                        ]
                      },
                      "finishReason": "STOP"
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

            assertEquals("哈哈真的假的！咖啡團我也要加一，等等直接約在樓下大廳見！", reply)
            val url = recordedUrl
            val body = recordedBody
            assertNotNull(url)
            assertNotNull(body)
            assertTrue(url.contains("models/gemini-3.5-flash-lite:generateContent"))
            assertTrue(url.contains("key=chatter-test-secret-key"))
            assertTrue(body.contains("Alice"))
            assertTrue(body.contains("Bob"))
            assertTrue(body.contains("Luna"))
        }

    @Test
    fun `successfully generates reply when given structured ChatMessage list`() =
        runTest {
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "text": "大家今天辛苦啦！週末愉快～"
                          }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config = ChatterConfig(apiKey = "valid-key")
            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val messages =
                listOf(
                    ChatMessage(author = "KordUser1", content = "今天下班要去哪？"),
                    ChatMessage(author = "KordUser2", content = "回家打電動！"),
                )

            val reply = generator.generateReply(messages)
            assertEquals("大家今天辛苦啦！週末愉快～", reply)
        }

    @Test
    fun `truncates generated reply exceeding 2000 characters to Discord limit with ellipsis`() =
        runTest {
            val oversizedResponseText = "長度過長的社群閒聊內容" + "x".repeat(3000)

            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          {
                            "text": "$oversizedResponseText"
                          }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ]
                }
                """.trimIndent()

            val mockEngine =
                MockEngine {
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config = ChatterConfig(apiKey = "valid-key")
            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val reply = generator.generateReply("[User]: 超長測試")

            assertTrue(reply != null)
            assertEquals(2000, reply.length)
            assertTrue(reply.endsWith("..."))
        }

    @Test
    fun `returns null gracefully when Gemini API returns 429 rate limit exceeded`() =
        runTest {
            val mockEngine =
                MockEngine {
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
        }

    @Test
    fun `returns null gracefully when Gemini API returns 500 internal server error`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content = """{"error": {"code": 500, "message": "Internal error occurred"}}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val config = ChatterConfig(apiKey = "valid-key")
            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val reply = generator.generateReply("[User]: 500 test")
            assertNull(reply)
        }

    @Test
    fun `returns null gracefully when Gemini API request throws timeout exception`() =
        runTest {
            val mockEngine =
                MockEngine {
                    throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30000L)
                }

            val config = ChatterConfig(apiKey = "valid-key")
            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val reply = generator.generateReply("[User]: timeout test")
            assertNull(reply)
        }

    @Test
    fun `returns null gracefully when response has safety block or empty candidates`() =
        runTest {
            val blockedJson =
                """
                {
                  "promptFeedback": {
                    "blockReason": "SAFETY"
                  },
                  "candidates": []
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
}
