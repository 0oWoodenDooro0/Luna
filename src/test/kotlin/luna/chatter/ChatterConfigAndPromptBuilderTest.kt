package luna.chatter

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import luna.core.ai.ChatImageAttachment
import luna.core.ai.EmojiSanitizer
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.VideoProcessingMode
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterConfigAndPromptBuilderTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
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
}
