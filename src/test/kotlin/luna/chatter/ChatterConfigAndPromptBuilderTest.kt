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

    @Test
    fun `ChatterConfig resolves burst debounce and maxInterrupts defaults and aliases across fromEnv, fromConfig, and fromPluginContext`() {
        val defaults = ChatterConfig()
        assertEquals(2.5, defaults.debounceSeconds)
        assertEquals(1.2, defaults.mentionDebounceSeconds)
        assertEquals(7.0, defaults.debounceMaxSeconds)
        assertEquals(2, defaults.maxInterrupts)

        val envDefaults = ChatterConfig.fromEnv(emptyMap())
        assertEquals(2.5, envDefaults.debounceSeconds)
        assertEquals(1.2, envDefaults.mentionDebounceSeconds)
        assertEquals(7.0, envDefaults.debounceMaxSeconds)
        assertEquals(2, envDefaults.maxInterrupts)

        val envCustom =
            ChatterConfig.fromEnv(
                mapOf(
                    "CHATTER_DEBOUNCE_SECONDS" to "3.0",
                    "CHATTER_MENTION_DEBOUNCE_SECONDS" to "1.5",
                    "CHATTER_DEBOUNCE_MAX_SECONDS" to "6.5",
                    "CHATTER_MAX_INTERRUPTS" to "4",
                ),
            )
        assertEquals(3.0, envCustom.debounceSeconds)
        assertEquals(1.5, envCustom.mentionDebounceSeconds)
        assertEquals(6.5, envCustom.debounceMaxSeconds)
        assertEquals(4, envCustom.maxInterrupts)

        val configAliases =
            ChatterConfig.fromConfig(
                mapOf(
                    "chatter.debounceSeconds" to 2.0,
                    "chatter.mentionDebounceSeconds" to "1.0",
                    "chatter.debounceMaxSeconds" to 8,
                    "chatter.maxInterrupts" to 0,
                ),
            )
        assertEquals(2.0, configAliases.debounceSeconds)
        assertEquals(1.0, configAliases.mentionDebounceSeconds)
        assertEquals(8.0, configAliases.debounceMaxSeconds)
        assertEquals(0, configAliases.maxInterrupts)

        val contextAliases =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap =
                    mapOf(
                        "chatter.debounce_seconds" to "3.2",
                        "chatter.mention_debounce_seconds" to 1.4,
                        "chatter.debounce_max_seconds" to 6.2,
                        "chatter.max_interrupts" to "3",
                    ),
            )
        val fromCtx = ChatterConfig.fromPluginContext(contextAliases)
        assertEquals(3.2, fromCtx.debounceSeconds)
        assertEquals(1.4, fromCtx.mentionDebounceSeconds)
        assertEquals(6.2, fromCtx.debounceMaxSeconds)
        assertEquals(3, fromCtx.maxInterrupts)
    }

    @Test
    fun `ChatterConfig clamps out-of-range debounceMaxSeconds to 6-8s and falls back safely on non-positive or invalid values`() {
        val clamped =
            ChatterConfig.fromEnv(
                mapOf(
                    "CHATTER_DEBOUNCE_MAX_SECONDS" to "2.0",
                ),
            )
        assertEquals(6.0, clamped.debounceMaxSeconds)

        val clampedHigh =
            ChatterConfig.fromEnv(
                mapOf(
                    "CHATTER_DEBOUNCE_MAX_SECONDS" to "15.0",
                ),
            )
        assertEquals(8.0, clampedHigh.debounceMaxSeconds)

        val invalidEnv =
            ChatterConfig.fromEnv(
                mapOf(
                    "CHATTER_DEBOUNCE_SECONDS" to "-1.0",
                    "CHATTER_MENTION_DEBOUNCE_SECONDS" to "0",
                    "CHATTER_DEBOUNCE_MAX_SECONDS" to "0.0",
                    "CHATTER_MAX_INTERRUPTS" to "-3",
                ),
            )
        assertEquals(2.5, invalidEnv.debounceSeconds)
        assertEquals(1.2, invalidEnv.mentionDebounceSeconds)
        assertEquals(7.0, invalidEnv.debounceMaxSeconds)
        assertEquals(2, invalidEnv.maxInterrupts)

        val garbageConfig =
            ChatterConfig.fromConfig(
                mapOf(
                    "CHATTER_DEBOUNCE_SECONDS" to "NaN",
                    "CHATTER_MENTION_DEBOUNCE_SECONDS" to "Infinity",
                    "CHATTER_DEBOUNCE_MAX_SECONDS" to "not-a-number",
                    "CHATTER_MAX_INTERRUPTS" to "abc",
                ),
            )
        assertEquals(2.5, garbageConfig.debounceSeconds)
        assertEquals(1.2, garbageConfig.mentionDebounceSeconds)
        assertEquals(7.0, garbageConfig.debounceMaxSeconds)
        assertEquals(2, garbageConfig.maxInterrupts)
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

        assertEquals(ChatterPromptBuilder.SYSTEM_INSTRUCTION, request.systemInstruction)

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

    // --- Newline Prompt Directive & Segment Splitting Tests ---

    @Test
    fun `ChatterPromptBuilder system and user instructions direct single newline breaks instead of spaces`() {
        val systemInstruction = ChatterPromptBuilder.buildSystemInstruction()
        val userPrompt = ChatterPromptBuilder.buildUserPrompt("[Alice]: 晚餐要吃什麼")

        assertContains(systemInstruction, "\\n")
        assertContains(systemInstruction, "換行")
        assertFalse(systemInstruction.contains("用空格或分行"))

        assertContains(userPrompt, "\\n")
        assertContains(userPrompt, "換行")
        assertFalse(userPrompt.contains("斷句用空格或分行"))
    }

    @Test
    fun `ChatterPromptBuilder splitReplySegments handles blank inputs, 1 to 3 segments, blank-line trimming, overflow merge, and 2000-char caps`() {
        assertEquals(emptyList(), ChatterPromptBuilder.splitReplySegments(""))
        assertEquals(emptyList(), ChatterPromptBuilder.splitReplySegments("   \n\r\n\t  "))

        assertEquals(listOf("單句回覆"), ChatterPromptBuilder.splitReplySegments("  單句回覆  "))

        assertEquals(
            listOf("第一句", "第二句"),
            ChatterPromptBuilder.splitReplySegments("第一句\n\n   \n  第二句  \r\n"),
        )

        assertEquals(
            listOf("想法一", "想法二", "想法三"),
            ChatterPromptBuilder.splitReplySegments("想法一\n想法二\n想法三"),
        )

        assertEquals(
            listOf("第一段", "第二段", "第三段\n第四段\n第五段"),
            ChatterPromptBuilder.splitReplySegments("第一段\n\n第二段\n第三段\n  \n第四段\n第五段"),
        )

        val longSeg1 = "a".repeat(2005)
        val longSeg2 = "b".repeat(2000)
        val longSeg3PartA = "c".repeat(1500)
        val longSeg3PartB = "d".repeat(600)
        val bounded =
            ChatterPromptBuilder.splitReplySegments(
                "$longSeg1\n$longSeg2\n$longSeg3PartA\n$longSeg3PartB",
            )
        assertEquals(3, bounded.size)
        assertEquals(2000, bounded[0].length)
        assertTrue(bounded[0].endsWith("..."))
        assertEquals("a".repeat(1997) + "...", bounded[0])
        assertEquals(longSeg2, bounded[1])
        assertEquals(2000, bounded[2].length)
        assertTrue(bounded[2].endsWith("..."))
        assertTrue(bounded[2].startsWith(longSeg3PartA + "\n"))
    }
}
