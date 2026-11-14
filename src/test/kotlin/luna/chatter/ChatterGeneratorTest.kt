package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.core.Kord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.Routing
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.core.ai.ChatImageAttachment
import luna.core.ai.EmojiSanitizer
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.VideoProcessingMode
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertContains
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
        assertTrue(config.searchGroundingEnabled)
    }

    @Test
    fun `ChatterConfig defaults searchGroundingEnabled to true and resolves false across fromEnv, fromConfig, and fromPluginContext`() {
        assertTrue(ChatterConfig().searchGroundingEnabled)
        assertTrue(ChatterConfig.fromEnv(emptyMap()).searchGroundingEnabled)

        val envDisabled = ChatterConfig.fromEnv(mapOf("GEMINI_SEARCH_GROUNDING_ENABLED" to "false"))
        assertFalse(envDisabled.searchGroundingEnabled)

        val envEnabled = ChatterConfig.fromEnv(mapOf("GEMINI_SEARCH_GROUNDING_ENABLED" to "true"))
        assertTrue(envEnabled.searchGroundingEnabled)

        val configDisabledEnvKey = ChatterConfig.fromConfig(mapOf("GEMINI_SEARCH_GROUNDING_ENABLED" to false))
        assertFalse(configDisabledEnvKey.searchGroundingEnabled)

        val configDisabledDotKey = ChatterConfig.fromConfig(mapOf("gemini.searchGroundingEnabled" to "false"))
        assertFalse(configDisabledDotKey.searchGroundingEnabled)

        val contextDisabled =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap =
                    mapOf(
                        "GEMINI_API_KEY" to "test-key",
                        "GEMINI_SEARCH_GROUNDING_ENABLED" to false,
                    ),
            )
        assertFalse(ChatterConfig.fromPluginContext(contextDisabled).searchGroundingEnabled)

        val contextDotDisabled =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap =
                    mapOf(
                        "GEMINI_API_KEY" to "test-key",
                        "gemini.searchGroundingEnabled" to "false",
                    ),
            )
        assertFalse(ChatterConfig.fromPluginContext(contextDotDisabled).searchGroundingEnabled)
    }

    @Test
    fun `ChatterConfig defaults urlContextEnabled to true and resolves across fromEnv, fromConfig, and fromPluginContext with invalid fallback`() {
        assertTrue(ChatterConfig.DEFAULT_URL_CONTEXT_ENABLED)
        assertTrue(ChatterConfig().urlContextEnabled)
        assertTrue(ChatterConfig.fromEnv(emptyMap()).urlContextEnabled)

        assertFalse(ChatterConfig.fromEnv(mapOf("GEMINI_URL_CONTEXT_ENABLED" to "false")).urlContextEnabled)
        assertFalse(ChatterConfig.fromEnv(mapOf("GEMINI_URL_CONTEXT_ENABLED" to " false ")).urlContextEnabled)
        assertTrue(ChatterConfig.fromEnv(mapOf("GEMINI_URL_CONTEXT_ENABLED" to "true")).urlContextEnabled)
        assertTrue(ChatterConfig.fromEnv(mapOf("GEMINI_URL_CONTEXT_ENABLED" to "invalid")).urlContextEnabled)

        assertFalse(ChatterConfig.fromConfig(mapOf("GEMINI_URL_CONTEXT_ENABLED" to false)).urlContextEnabled)
        assertFalse(ChatterConfig.fromConfig(mapOf("GEMINI_URL_CONTEXT_ENABLED" to "false")).urlContextEnabled)
        assertFalse(ChatterConfig.fromConfig(mapOf("gemini.urlContextEnabled" to false)).urlContextEnabled)
        assertFalse(ChatterConfig.fromConfig(mapOf("gemini.urlContextEnabled" to " false ")).urlContextEnabled)
        assertTrue(ChatterConfig.fromConfig(mapOf("GEMINI_URL_CONTEXT_ENABLED" to "invalid")).urlContextEnabled)

        val contextDisabled =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap =
                    mapOf(
                        "GEMINI_API_KEY" to "test-key",
                        "GEMINI_URL_CONTEXT_ENABLED" to false,
                    ),
            )
        assertFalse(ChatterConfig.fromPluginContext(contextDisabled).urlContextEnabled)

        val contextDotDisabled =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap =
                    mapOf(
                        "GEMINI_API_KEY" to "test-key",
                        "gemini.urlContextEnabled" to "false",
                    ),
            )
        assertFalse(ChatterConfig.fromPluginContext(contextDotDisabled).urlContextEnabled)

        val contextInvalidFallback =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap =
                    mapOf(
                        "GEMINI_API_KEY" to "test-key",
                        "GEMINI_URL_CONTEXT_ENABLED" to "not-a-boolean",
                    ),
            )
        assertTrue(ChatterConfig.fromPluginContext(contextInvalidFallback).urlContextEnabled)
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
    fun `ChatterPromptBuilder buildInteractionRequest embeds video image text inputs, tools, max_output_tokens without temperature`() {
        val input =
            ChatterInput(
                transcript = "[Alice]: 看看這張圖跟影片 [附帶圖片 1: err.png] https://kotlinlang.org/docs/",
                imageAttachments =
                    listOf(
                        ChatImageAttachment(
                            url = "https://cdn.discordapp.com/attachments/1/2/err.png",
                            mimeType = "image/png",
                            filename = "err.png",
                        ),
                    ),
                videoUri = "https://www.youtube.com/watch?v=shortVid",
                videoDurationSeconds = 120L,
                isDirectMention = true,
                title = "Kotlin Conf Keynote",
                url = "https://www.youtube.com/watch?v=shortVid",
                comments = listOf("Awesome demo!", "Super clean"),
                webUrls = listOf("https://kotlinlang.org/docs/"),
            )

        val request =
            ChatterPromptBuilder.buildInteractionRequest(
                input = input,
                model = "gemini-3.5-flash-lite",
                tools = listOf(GeminiInteractionTools.URL_CONTEXT, GeminiInteractionTools.GOOGLE_SEARCH),
                videoProcessingMode = VideoProcessingMode.STATIC,
                background = false,
            )
        assertEquals(512, request.generationConfig?.maxOutputTokens)
        assertFalse(request.background)
        assertEquals(3, request.input.size)

        val videoInput = request.input[0]
        assertEquals("video", videoInput.type)
        assertEquals("https://www.youtube.com/watch?v=shortVid", videoInput.uri)
        assertNull(videoInput.mimeType)
        assertEquals("static", videoInput.processing)

        val imageInput = request.input[1]
        assertEquals("image", imageInput.type)
        assertEquals("https://cdn.discordapp.com/attachments/1/2/err.png", imageInput.uri)
        assertEquals("image/png", imageInput.mimeType)

        val textInput = request.input[2]
        assertEquals("text", textInput.type)
        val promptText = assertNotNull(textInput.text)
        assertContains(promptText, "[Alice]: 看看這張圖跟影片 [附帶圖片 1: err.png] https://kotlinlang.org/docs/")
        assertContains(promptText, "Kotlin Conf Keynote")
        assertContains(promptText, "Awesome demo!")
        assertContains(promptText, "url_context")

        val sys = assertNotNull(request.systemInstruction)
        assertContains(sys, "url_context")
        assertContains(sys, "google_search")
        assertContains(sys, "TL;DR:")

        assertEquals(listOf("url_context", "google_search"), request.tools?.map { it.type })

        val serialized = json.encodeToString(request)
        assertFalse(serialized.contains("\"temperature\""))
        assertContains(serialized, "\"max_output_tokens\":512")
        assertContains(serialized, "\"type\":\"url_context\"")
        assertContains(serialized, "\"type\":\"google_search\"")
        assertFalse(serialized.contains("\"mime_type\":\"video/mp4\""))
    }

    @Test
    fun `ChatterPromptBuilder sanitizes common AI boilerplate prefixes, wrapping code blocks, and emojis`() {
        val rawWithBoilerplate = "身為一個AI語言模型，我覺得今天去喝拿鐵是個很棒的提議！"
        val sanitized = ChatterPromptBuilder.sanitizeResponse(rawWithBoilerplate)
        assertEquals("我覺得今天去喝拿鐵是個很棒的提議！", sanitized)

        val rawWithEnglishBoilerplate = "As an AI, I really love this community discussion!"
        val sanitizedEnglish = ChatterPromptBuilder.sanitizeResponse(rawWithEnglishBoilerplate)
        assertEquals("I really love this community discussion!", sanitizedEnglish)

        val rawWithMarkdownFence = "```markdown\n這是一段自然對話回覆。\n```"
        val sanitizedFence = ChatterPromptBuilder.sanitizeResponse(rawWithMarkdownFence)
        assertEquals("這是一段自然對話回覆。", sanitizedFence)

        val rawWithEmojis = "真的太強了吧 😂👍"
        val sanitizedEmoji = ChatterPromptBuilder.sanitizeResponse(rawWithEmojis)
        assertFalse(EmojiSanitizer.containsEmoji(sanitizedEmoji))
        assertEquals("真的太強了吧", sanitizedEmoji)
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

    @Test
    fun `tiered video policy uses sync static interaction without mime_type for videos at most 300 seconds or unknown duration`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            val recordedBodies = mutableListOf<String>()
            val responseJson =
                """{"id": "int-vid-static", "status": "completed", "output_text": "這段展示超順的"}"""

            val mockEngine =
                MockEngine { request ->
                    recordedUrls.add(request.url.toString())
                    recordedBodies.add(request.body.toByteArray().decodeToString())
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val ambientShort =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 看這支短片",
                        videoUri = "https://www.youtube.com/watch?v=short300",
                        videoDurationSeconds = 300L,
                        isDirectMention = false,
                    ),
                )
            val directUnknown =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Bob]: 這支片講什麼",
                        videoUri = "https://www.youtube.com/watch?v=unknownDur",
                        videoDurationSeconds = null,
                        isDirectMention = true,
                    ),
                )

            assertEquals("這段展示超順的", ambientShort)
            assertEquals("這段展示超順的", directUnknown)
            assertEquals(2, recordedUrls.size)
            assertTrue(recordedUrls.all { it.contains("/v1beta/interactions") })

            for (body in recordedBodies) {
                val obj = json.parseToJsonElement(body).jsonObject
                assertEquals(false, obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
                val videoBlock = obj["input"]!!.jsonArray[0].jsonObject
                assertEquals("video", videoBlock["type"]?.jsonPrimitive?.content)
                assertEquals("static", videoBlock["processing"]?.jsonPrimitive?.content)
                assertFalse(videoBlock.containsKey("mime_type"))
            }
        }

    @Test
    fun `tiered video policy omits videoUri and uses sync interaction for ambient replies over 300 seconds`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            var capturedBody: String? = null
            val responseJson =
                """{"id": "int-vid-amb", "status": "completed", "output_text": "看留言大家都推 12 分鐘那段實測"}"""

            val mockEngine =
                MockEngine { request ->
                    recordedUrls.add(request.url.toString())
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 這支教學推嗎\n[連結內容: https://www.youtube.com/watch?v=long600]\n標題：Kotlin Deep Dive\n留言：\n1. 12:00 實測很讚",
                        videoUri = "https://www.youtube.com/watch?v=long600",
                        videoDurationSeconds = 600L,
                        isDirectMention = false,
                        title = "Kotlin Deep Dive",
                        comments = listOf("12:00 實測很讚"),
                    ),
                )

            assertEquals("看留言大家都推 12 分鐘那段實測", reply)
            assertEquals(1, recordedUrls.size)
            assertTrue(recordedUrls.single().contains("/v1beta/interactions"))
            val obj = json.parseToJsonElement(checkNotNull(capturedBody)).jsonObject
            assertEquals(false, obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            val inputBlocks = obj["input"]!!.jsonArray
            assertEquals(1, inputBlocks.size)
            assertEquals("text", inputBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertTrue(capturedBody.orEmpty().contains("Kotlin Deep Dive"))
            assertTrue(capturedBody.orEmpty().contains("12:00 實測很讚"))
        }

    @Test
    fun `tiered video policy invokes agentic interactions polling for direct mentions over 300 seconds and at most 7200 seconds`() =
        runTest {
            val recordedMethods = mutableListOf<HttpMethod>()
            val recordedUrls = mutableListOf<String>()
            var interactionBody: String? = null
            var pollAttempt = 0

            val mockEngine =
                MockEngine { request ->
                    recordedMethods.add(request.method)
                    recordedUrls.add(request.url.toString())
                    if (request.method == HttpMethod.Post && request.url.toString().contains("/v1beta/interactions")) {
                        interactionBody = request.body.toByteArray().decodeToString()
                        respond(
                            content = """{"id": "interaction-chatter-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        pollAttempt++
                        val statusJson =
                            if (pollAttempt == 1) {
                                """{"id": "interaction-chatter-1", "status": "in_progress"}"""
                            } else {
                                """{"id": "interaction-chatter-1", "status": "completed", "output_text": "這支影片主要在示範協程結構化並發 👍"}"""
                            }
                        respond(
                            content = statusJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                    pollingDelayMs = 1L,
                    maxPollingAttempts = 5,
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 幫我看這支片在講什麼 https://kotlinlang.org/docs/coroutines.html",
                        videoUri = "https://www.youtube.com/watch?v=med7200",
                        videoDurationSeconds = 7200L,
                        isDirectMention = true,
                        webUrls = listOf("https://kotlinlang.org/docs/coroutines.html"),
                    ),
                )

            assertEquals("這支影片主要在示範協程結構化並發", reply)
            assertTrue(recordedUrls.first().contains("/v1beta/interactions?key=valid-key"))
            assertTrue(recordedUrls.drop(1).all { it.contains("/v1beta/interactions/interaction-chatter-1") })
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Get), recordedMethods)
            val submitted = checkNotNull(interactionBody)
            val obj = json.parseToJsonElement(submitted).jsonObject
            assertEquals(true, obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            val videoBlock = obj["input"]!!.jsonArray[0].jsonObject
            assertEquals("agentic", videoBlock["processing"]?.jsonPrimitive?.content)
            assertEquals("https://www.youtube.com/watch?v=med7200", videoBlock["uri"]?.jsonPrimitive?.content)
            assertFalse(videoBlock.containsKey("mime_type"))
            assertEquals(
                listOf("url_context", "google_search"),
                obj["tools"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content },
            )
            assertTrue(submitted.contains("幫我看這支片在講什麼"))
        }

    @Test
    fun `negative boundary omits videoUri and never invokes agentic polling when video duration exceeds 7200 seconds`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            var capturedBody: String? = null
            val responseJson =
                """{"id": "int-vid-cap", "status": "completed", "output_text": "這場直播兩小時多太長了 不過留言說重點在後半段"}"""

            val mockEngine =
                MockEngine { request ->
                    recordedUrls.add(request.url.toString())
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 這場直播在講啥\n標題：3 Hour Conference\n留言：\n1. 重點在後半段",
                        videoUri = "https://www.youtube.com/watch?v=tooLong",
                        videoDurationSeconds = 7201L,
                        isDirectMention = true,
                    ),
                )

            assertEquals("這場直播兩小時多太長了 不過留言說重點在後半段", reply)
            assertEquals(1, recordedUrls.size)
            assertTrue(recordedUrls.single().contains("/v1beta/interactions"))
            val obj = json.parseToJsonElement(checkNotNull(capturedBody)).jsonObject
            assertEquals(false, obj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            assertFalse(capturedBody.orEmpty().contains("\"type\":\"video\""))
            assertTrue(capturedBody.orEmpty().contains("3 Hour Conference"))
        }

    @Test
    fun `negative boundary performs zero layered retries and logs error when sync interaction fails with images and tools`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                var requestCount = 0
                val mockEngine =
                    MockEngine {
                        requestCount++
                        respond(
                            content = """{"error":{"code":400,"message":"Failed to fetch CDN image"}}""",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val generator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true, urlContextEnabled = true),
                        httpClient = createTestClient(mockEngine),
                    )

                val reply =
                    generator.generateReply(
                        ChatterInput(
                            transcript = "[Alice]: 看看這張圖 https://example.com/a",
                            imageAttachments =
                                listOf(
                                    ChatImageAttachment(
                                        url = "https://cdn.discordapp.com/attachments/1/2/expired.png",
                                        mimeType = "image/png",
                                        filename = "expired.png",
                                    ),
                                ),
                            webUrls = listOf("https://example.com/a"),
                        ),
                    )

                assertNull(reply)
                assertEquals(1, requestCount, "ChatterGenerator must NOT perform layered no-search or no-media retries")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `negative boundary performs zero retries and logs error when direct-mention agentic polling fails`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val recordedUrls = mutableListOf<String>()
                val mockEngine =
                    MockEngine { request ->
                        recordedUrls.add(request.url.toString())
                        respond(
                            content = """{"error":{"code":429,"message":"Rate limit on interactions"}}""",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                val generator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true),
                        httpClient = createTestClient(mockEngine),
                        pollingDelayMs = 1L,
                        maxPollingAttempts = 2,
                    )

                val reply =
                    generator.generateReply(
                        ChatterInput(
                            transcript = "[Alice]: 影片跟截圖都在這",
                            videoUri = "https://www.youtube.com/watch?v=longVid",
                            videoDurationSeconds = 900L,
                            isDirectMention = true,
                        ),
                    )

                assertNull(reply)
                assertEquals(1, recordedUrls.size, "Agentic interaction failure must fail cleanly with zero fallback retries")
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `logs error on unsafe url_context_result while preserving valid sanitized model reply`() =
        runTest {
            val logger = LoggerFactory.getLogger(luna.core.ai.GeminiInteractionClient::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val responseJson =
                    """
                    {
                      "id": "int-unsafe-1",
                      "status": "completed",
                      "steps": [
                        {
                          "type": "url_context_result",
                          "result": [
                            {"retrieved_url": "https://unsafe.example.com/bad", "status": "unsafe"}
                          ]
                        },
                        {
                          "type": "model_output",
                          "content": [
                            {"type": "text", "text": "這個網址好像打不開 不過剛剛看話題蠻有趣的 😂"}
                          ]
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

                val generator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key", urlContextEnabled = true),
                        httpClient = createTestClient(mockEngine),
                    )

                val reply =
                    generator.generateReply(
                        ChatterInput(
                            transcript = "[Alice]: 看看 https://unsafe.example.com/bad",
                            webUrls = listOf("https://unsafe.example.com/bad"),
                        ),
                    )

                assertEquals("這個網址好像打不開 不過剛剛看話題蠻有趣的", reply)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.ERROR &&
                            it.formattedMessage.contains("https://unsafe.example.com/bad") &&
                            it.formattedMessage.contains("unsafe")
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `enforces 2000-char Discord bound and handles sanitized-to-blank responses cleanly`() =
        runTest {
            val longText = "a".repeat(2500)
            val longGen =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient =
                        createTestClient(
                            MockEngine {
                                respond(
                                    content = """{"id":"int-long","status":"completed","output_text":"$longText"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                        ),
                )

            val boundedReply = assertNotNull(longGen.generateReply("[Alice]: tell me everything"))
            assertEquals(2000, boundedReply.length)
            assertTrue(boundedReply.endsWith("..."))

            val logger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)
            try {
                val blankGen =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key"),
                        httpClient =
                            createTestClient(
                                MockEngine {
                                    respond(
                                        content = """{"id":"int-boilerplate","status":"completed","output_text":"身為一個AI語言模型，"}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                },
                            ),
                    )
                assertNull(blankGen.generateReply("[Alice]: hi"))
                assertTrue(listAppender.list.any { it.level == Level.ERROR })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
