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
import luna.core.ai.ChatImageAttachment
import luna.core.ai.EmojiSanitizer
import luna.core.ai.GeminiTools
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
    fun `ChatterPromptBuilder buildRequest with ChatterInput embeds file_data parts, googleSearch tool, and metadata without duplicate transcript info`() {
        val input =
            ChatterInput(
                transcript = "[Alice]: 看看這張圖跟影片 [附帶圖片 1: err.png]",
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
            )

        val request = ChatterPromptBuilder.buildRequest(input, tools = listOf(GeminiTools.GOOGLE_SEARCH_TOOL))
        assertEquals(512, request.generationConfig?.maxOutputTokens)
        assertEquals(0.8, request.generationConfig?.temperature)

        val parts = request.contents.first().parts
        assertEquals(3, parts.size)

        val videoFileData = parts[0].fileData
        assertNotNull(videoFileData)
        assertEquals("https://www.youtube.com/watch?v=shortVid", videoFileData.fileUri)
        assertNull(videoFileData.mimeType)

        val imageFileData = parts[1].fileData
        assertNotNull(imageFileData)
        assertEquals("https://cdn.discordapp.com/attachments/1/2/err.png", imageFileData.fileUri)
        assertEquals("image/png", imageFileData.mimeType)

        val textPart = parts[2].text
        assertNotNull(textPart)
        assertContains(textPart, "[Alice]: 看看這張圖跟影片 [附帶圖片 1: err.png]")
        assertContains(textPart, "Kotlin Conf Keynote")
        assertContains(textPart, "Awesome demo!")

        assertEquals(1, request.tools?.size)
        assertNotNull(request.tools?.first()?.actualGoogleSearch)

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"file_uri\":\"https://www.youtube.com/watch?v=shortVid\"")
        assertContains(serialized, "\"file_uri\":\"https://cdn.discordapp.com/attachments/1/2/err.png\"")
        assertContains(serialized, "\"mime_type\":\"image/png\"")
        assertContains(serialized, "\"googleSearch\":{}")
        assertFalse(serialized.contains("\"processing\""))
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

    // --- Multimodal, Tiered Video Latency Policy, Search Grounding & Degraded Fallback Tests ---

    @Test
    fun `generateReply with ChatterInput sends file_data images and googleSearch tool without appending citation URLs`() =
        runTest {
            var capturedBody: String? = null
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          { "text": "這張截圖的錯誤是因為 port 8080 被佔用了 😂" }
                        ]
                      },
                      "groundingMetadata": {
                        "groundingChunks": [
                          { "web": { "uri": "https://example.com/port-fix", "title": "Port Fix Guide" } }
                        ]
                      },
                      "finishReason": "STOP"
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
                    config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true),
                    httpClient = createTestClient(mockEngine),
                )

            val input =
                ChatterInput(
                    transcript = "[Alice]: 幫我看一下這個錯 [附帶圖片 1: err.png]",
                    imageAttachments =
                        listOf(
                            ChatImageAttachment(
                                url = "https://cdn.discordapp.com/attachments/1/2/err.png",
                                mimeType = "image/png",
                                filename = "err.png",
                            ),
                        ),
                )

            val reply = generator.generateReply(input)

            assertEquals("這張截圖的錯誤是因為 port 8080 被佔用了", reply)
            val body = checkNotNull(capturedBody)
            assertTrue(body.contains("\"file_uri\":\"https://cdn.discordapp.com/attachments/1/2/err.png\""))
            assertTrue(body.contains("\"mime_type\":\"image/png\""))
            assertTrue(body.contains("\"googleSearch\":{}"))
            assertTrue(body.contains("\"maxOutputTokens\":512"))
            assertFalse(reply.orEmpty().contains("https://example.com/port-fix"))
            assertFalse(reply.orEmpty().contains("名詞與事實查核:"))
        }

    @Test
    fun `negative boundary omits googleSearch tool when GEMINI_SEARCH_GROUNDING_ENABLED is false`() =
        runTest {
            var capturedBody: String? = null
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [{ "text": "好的收到" }]
                      }
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

            val config = ChatterConfig.fromEnv(mapOf("GEMINI_API_KEY" to "valid-key", "GEMINI_SEARCH_GROUNDING_ENABLED" to "false"))
            val generator = ChatterGenerator(config = config, httpClient = createTestClient(mockEngine))

            val reply = generator.generateReply(ChatterInput(transcript = "[Alice]: 早安"))
            assertEquals("好的收到", reply)
            val body = checkNotNull(capturedBody)
            assertFalse(body.contains("googleSearch"))
            assertFalse(body.contains("\"tools\""))
        }

    @Test
    fun `tiered video policy uses static generateContent file_data for videos at most 300 seconds or unknown duration`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            val recordedBodies = mutableListOf<String>()
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [{ "text": "這段展示超順的" }]
                      }
                    }
                  ]
                }
                """.trimIndent()

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
            assertTrue(recordedUrls.all { it.contains(":generateContent") && !it.contains("/interactions") })
            assertTrue(recordedBodies[0].contains("\"file_uri\":\"https://www.youtube.com/watch?v=short300\""))
            assertTrue(recordedBodies[1].contains("\"file_uri\":\"https://www.youtube.com/watch?v=unknownDur\""))
            assertTrue(recordedBodies.none { it.contains("\"processing\"") })
        }

    @Test
    fun `tiered video policy omits videoUri and skips agentic polling for ambient replies over 300 seconds`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            var capturedBody: String? = null
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [{ "text": "看留言大家都推 12 分鐘那段實測" }]
                      }
                    }
                  ]
                }
                """.trimIndent()

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
            assertTrue(recordedUrls.single().contains(":generateContent"))
            assertFalse(recordedUrls.single().contains("/interactions"))
            val body = checkNotNull(capturedBody)
            assertFalse(body.contains("\"file_data\""))
            assertTrue(body.contains("Kotlin Deep Dive"))
            assertTrue(body.contains("12:00 實測很讚"))
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
                        transcript = "[Alice]: 幫我看這支片在講什麼",
                        videoUri = "https://www.youtube.com/watch?v=med7200",
                        videoDurationSeconds = 7200L,
                        isDirectMention = true,
                    ),
                )

            assertEquals("這支影片主要在示範協程結構化並發", reply)
            assertTrue(recordedUrls.first().contains("/v1beta/interactions?key=valid-key"))
            assertTrue(recordedUrls.drop(1).all { it.contains("/v1beta/interactions/interaction-chatter-1") })
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Get), recordedMethods)
            val submitted = checkNotNull(interactionBody)
            assertTrue(submitted.contains("\"processing\":\"agentic\""))
            assertTrue(submitted.contains("\"uri\":\"https://www.youtube.com/watch?v=med7200\""))
            assertTrue(submitted.contains("幫我看這支片在講什麼"))
        }

    @Test
    fun `negative boundary omits videoUri and never invokes agentic polling when video duration exceeds 7200 seconds`() =
        runTest {
            val recordedUrls = mutableListOf<String>()
            var capturedBody: String? = null
            val responseJson =
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [{ "text": "這場直播兩小時多太長了 不過留言說重點在後半段" }]
                      }
                    }
                  ]
                }
                """.trimIndent()

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
            assertTrue(recordedUrls.single().contains(":generateContent"))
            assertFalse(recordedUrls.single().contains("/interactions"))
            val body = checkNotNull(capturedBody)
            assertFalse(body.contains("\"file_data\""))
            assertTrue(body.contains("3 Hour Conference"))
        }

    @Test
    fun `observable degraded fallback strips googleSearch then file_data sequentially on errors and emits warning logs`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val requestBodies = mutableListOf<String>()
                var requestCount = 0

                val mockEngine =
                    MockEngine { request ->
                        requestCount++
                        requestBodies.add(request.body.toByteArray().decodeToString())
                        when (requestCount) {
                            1 -> {
                                respond(
                                    content = """{"error":{"code":400,"message":"Search grounding unsupported"}}""",
                                    status = HttpStatusCode.BadRequest,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            2 -> {
                                respond(
                                    content = """{"error":{"code":400,"message":"Failed to fetch CDN image"}}""",
                                    status = HttpStatusCode.BadRequest,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            else -> {
                                respond(
                                    content =
                                        """
                                        {
                                          "candidates": [
                                            {
                                              "content": {
                                                "role": "model",
                                                "parts": [{ "text": "雖然圖片讀不到 不過聽起來蠻有趣的" }]
                                              }
                                            }
                                          ]
                                        }
                                        """.trimIndent(),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        }
                    }

                val generator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "valid-key", searchGroundingEnabled = true),
                        httpClient = createTestClient(mockEngine),
                    )

                val reply =
                    generator.generateReply(
                        ChatterInput(
                            transcript = "[Alice]: 看看這張圖 [附帶圖片 1: expired.png]",
                            imageAttachments =
                                listOf(
                                    ChatImageAttachment(
                                        url = "https://cdn.discordapp.com/attachments/1/2/expired.png",
                                        mimeType = "image/png",
                                        filename = "expired.png",
                                    ),
                                ),
                        ),
                    )

                assertEquals("雖然圖片讀不到 不過聽起來蠻有趣的", reply)
                assertEquals(3, requestCount)
                // 1st request: googleSearch + file_data
                assertTrue(requestBodies[0].contains("googleSearch"))
                assertTrue(requestBodies[0].contains("file_data"))
                // 2nd request: no googleSearch, still has file_data
                assertFalse(requestBodies[1].contains("googleSearch"))
                assertTrue(requestBodies[1].contains("file_data"))
                // 3rd request: text-only baseline (no googleSearch, no file_data)
                assertFalse(requestBodies[2].contains("googleSearch"))
                assertFalse(requestBodies[2].contains("file_data"))

                val warnMessages = listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(warnMessages.any { it.contains("googleSearch", ignoreCase = true) })
                assertTrue(warnMessages.any { it.contains("file_data", ignoreCase = true) || it.contains("text-only", ignoreCase = true) })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `observable degraded fallback degrades agentic polling failure to generateContent baseline without videoUri while preserving images`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val recordedUrls = mutableListOf<String>()
                var generateContentBody: String? = null

                val mockEngine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        recordedUrls.add(url)
                        if (url.contains("/v1beta/interactions")) {
                            respond(
                                content = """{"error":{"code":429,"message":"Rate limit on interactions"}}""",
                                status = HttpStatusCode.TooManyRequests,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            generateContentBody = request.body.toByteArray().decodeToString()
                            respond(
                                content =
                                    """
                                    {
                                      "candidates": [
                                        {
                                          "content": {
                                            "role": "model",
                                            "parts": [{ "text": "影片暫時打不開 不過截圖看起來是依賴版本衝突" }]
                                          }
                                        }
                                      ]
                                    }
                                    """.trimIndent(),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
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
                            transcript = "[Alice]: 影片跟截圖都在這 [附帶圖片 1: dep.png]",
                            imageAttachments =
                                listOf(
                                    ChatImageAttachment(
                                        url = "https://cdn.discordapp.com/attachments/1/2/dep.png",
                                        mimeType = "image/png",
                                        filename = "dep.png",
                                    ),
                                ),
                            videoUri = "https://www.youtube.com/watch?v=longVid",
                            videoDurationSeconds = 900L,
                            isDirectMention = true,
                        ),
                    )

                assertEquals("影片暫時打不開 不過截圖看起來是依賴版本衝突", reply)
                assertEquals(2, recordedUrls.size)
                assertTrue(recordedUrls[0].contains("/v1beta/interactions"))
                assertTrue(recordedUrls[1].contains(":generateContent"))

                val fallbackBody = checkNotNull(generateContentBody)
                assertFalse(fallbackBody.contains("https://www.youtube.com/watch?v=longVid"))
                assertTrue(fallbackBody.contains("\"file_uri\":\"https://cdn.discordapp.com/attachments/1/2/dep.png\""))

                val warnMessages = listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(warnMessages.any { it.contains("agentic", ignoreCase = true) })
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
