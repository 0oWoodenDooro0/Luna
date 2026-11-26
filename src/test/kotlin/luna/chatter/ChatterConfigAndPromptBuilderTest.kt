package luna.chatter

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import luna.config.ConfigValidationException
import luna.config.EnvReader
import luna.core.ai.ChatImageAttachment
import luna.core.ai.EmojiSanitizer
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.VideoProcessingMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `ChatterConfig resolves from EnvReader prioritizing CHATTER_GEMINI_MODEL`() {
        val env =
            EnvReader.of(
                "GEMINI_API_KEY" to "fallback-key",
                "CHATTER_GEMINI_MODEL" to "models/gemini-custom-chatter",
                "GEMINI_MODEL" to "gemini-default",
                "GEMINI_BASE_URL" to "https://custom.gemini.api/v1beta/",
            )
        val config = ChatterConfig.from(env)
        assertEquals("fallback-key", config.apiKey)
        assertTrue(config.isConfigured)
        assertEquals("gemini-custom-chatter", config.cleanModel)
        assertEquals("https://custom.gemini.api", config.cleanBaseUrl)
    }

    @Test
    fun `ChatterConfig falls back to GEMINI_MODEL when CHATTER_GEMINI_MODEL is unset or blank`() {
        val env =
            EnvReader.of(
                "GEMINI_API_KEY" to "generic-gemini-key",
                "CHATTER_GEMINI_MODEL" to "   ",
                "GEMINI_MODEL" to "gemini-1.5-pro",
                "GEMINI_BASE_URL" to "https://generativelanguage.googleapis.com/",
            )
        val config = ChatterConfig.from(env)
        assertEquals("generic-gemini-key", config.apiKey)
        assertTrue(config.isConfigured)
        assertEquals("gemini-1.5-pro", config.cleanModel)
        assertEquals("https://generativelanguage.googleapis.com", config.cleanBaseUrl)
    }

    @Test
    fun `ChatterConfig preserves default official baseUrl when only global BASE_URL is present and ignores legacy keys`() {
        val env =
            EnvReader.of(
                "BASE_URL" to "http://localhost:8080/s/",
                "GEMINI_API_KEY" to "test-gemini-key",
                "chatter.model" to "legacy-model",
                "gemini.baseUrl" to "https://legacy.example.com",
            )
        val config = ChatterConfig.from(env)
        assertEquals("https://generativelanguage.googleapis.com", config.baseUrl)
        assertEquals("https://generativelanguage.googleapis.com", config.cleanBaseUrl)
        assertEquals(ChatterConfig.DEFAULT_MODEL, config.model)
        assertTrue(config.searchGroundingEnabled)
    }

    @Test
    fun `ChatterConfig resolves searchGroundingEnabled and urlContextEnabled and fails fast on invalid booleans`() {
        assertTrue(ChatterConfig().searchGroundingEnabled)
        assertTrue(ChatterConfig().urlContextEnabled)
        assertTrue(ChatterConfig.from(EnvReader.of()).searchGroundingEnabled)
        assertTrue(ChatterConfig.from(EnvReader.of()).urlContextEnabled)

        assertFalse(ChatterConfig.from(EnvReader.of("GEMINI_SEARCH_GROUNDING_ENABLED" to "false")).searchGroundingEnabled)
        assertTrue(ChatterConfig.from(EnvReader.of("GEMINI_SEARCH_GROUNDING_ENABLED" to "true")).searchGroundingEnabled)
        assertFalse(ChatterConfig.from(EnvReader.of("GEMINI_URL_CONTEXT_ENABLED" to " false ")).urlContextEnabled)
        assertTrue(ChatterConfig.from(EnvReader.of("GEMINI_URL_CONTEXT_ENABLED" to "true")).urlContextEnabled)

        assertFailsWith<ConfigValidationException> {
            ChatterConfig.from(EnvReader.of("GEMINI_SEARCH_GROUNDING_ENABLED" to "invalid"))
        }
        assertFailsWith<ConfigValidationException> {
            ChatterConfig.from(EnvReader.of("GEMINI_URL_CONTEXT_ENABLED" to "not-a-boolean"))
        }
    }

    @Test
    fun `ChatterConfig resolves burst debounce and maxInterrupts defaults and valid overrides`() {
        val defaults = ChatterConfig()
        assertEquals(2.5, defaults.debounceSeconds)
        assertEquals(1.2, defaults.mentionDebounceSeconds)
        assertEquals(7.0, defaults.debounceMaxSeconds)
        assertEquals(2, defaults.maxInterrupts)

        val envDefaults = ChatterConfig.from(EnvReader.of())
        assertEquals(2.5, envDefaults.debounceSeconds)
        assertEquals(1.2, envDefaults.mentionDebounceSeconds)
        assertEquals(7.0, envDefaults.debounceMaxSeconds)
        assertEquals(2, envDefaults.maxInterrupts)

        val envCustom =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_DEBOUNCE_SECONDS" to "3.0",
                    "CHATTER_MENTION_DEBOUNCE_SECONDS" to "1.5",
                    "CHATTER_DEBOUNCE_MAX_SECONDS" to "6.5",
                    "CHATTER_MAX_INTERRUPTS" to "0",
                ),
            )
        assertEquals(3.0, envCustom.debounceSeconds)
        assertEquals(1.5, envCustom.mentionDebounceSeconds)
        assertEquals(6.5, envCustom.debounceMaxSeconds)
        assertEquals(0, envCustom.maxInterrupts)
    }

    @Test
    fun `ChatterConfig fails fast with ConfigValidationException on out-of-range or invalid debounce and maxInterrupts`() {
        val invalidCases =
            listOf(
                "CHATTER_DEBOUNCE_SECONDS" to "-1.0",
                "CHATTER_DEBOUNCE_SECONDS" to "0.0",
                "CHATTER_DEBOUNCE_SECONDS" to "NaN",
                "CHATTER_MENTION_DEBOUNCE_SECONDS" to "0",
                "CHATTER_MENTION_DEBOUNCE_SECONDS" to "Infinity",
                "CHATTER_DEBOUNCE_MAX_SECONDS" to "2.0",
                "CHATTER_DEBOUNCE_MAX_SECONDS" to "15.0",
                "CHATTER_DEBOUNCE_MAX_SECONDS" to "0.0",
                "CHATTER_DEBOUNCE_MAX_SECONDS" to "not-a-number",
                "CHATTER_MAX_INTERRUPTS" to "-3",
                "CHATTER_MAX_INTERRUPTS" to "abc",
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
    fun `ChatterConfig fails fast in init on invalid direct constructor arguments`() {
        assertFailsWith<ConfigValidationException> { ChatterConfig(maxOutputCharacters = 0) }
        assertFailsWith<ConfigValidationException> { ChatterConfig(debounceSeconds = 0.0) }
        assertFailsWith<ConfigValidationException> { ChatterConfig(debounceSeconds = Double.NaN) }
        assertFailsWith<ConfigValidationException> { ChatterConfig(mentionDebounceSeconds = -1.0) }
        assertFailsWith<ConfigValidationException> { ChatterConfig(debounceMaxSeconds = 5.9) }
        assertFailsWith<ConfigValidationException> { ChatterConfig(debounceMaxSeconds = 8.1) }
        assertFailsWith<ConfigValidationException> { ChatterConfig(maxInterrupts = -1) }
        assertFailsWith<ConfigValidationException> { ChatterConfig(timezone = "Invalid/Timezone") }
        assertFailsWith<ConfigValidationException> { ChatterConfig(timezone = "   ") }
        assertFailsWith<ConfigValidationException> { ChatterConfig(maxImages = 0) }
    }

    @Test
    fun `ChatterConfig loads media and YouTube API key settings from EnvReader`() {
        val defaults = ChatterConfig.from(EnvReader.of())
        assertEquals(ChatterContextCollector.DEFAULT_MAX_IMAGES, defaults.maxImages)
        assertNull(defaults.youtubeApiKey)

        val custom =
            ChatterConfig.from(
                EnvReader.of(
                    "SUMMARY_MAX_IMAGES" to "6",
                    "YOUTUBE_API_KEY" to "  yt-test-key  ",
                ),
            )
        assertEquals(6, custom.maxImages)
        assertEquals("yt-test-key", custom.youtubeApiKey)

        assertFailsWith<ConfigValidationException> {
            ChatterConfig.from(EnvReader.of("SUMMARY_MAX_IMAGES" to "0"))
        }
        assertFailsWith<ConfigValidationException> {
            ChatterConfig.from(EnvReader.of("SUMMARY_MAX_IMAGES" to "abc"))
        }
    }

    @Test
    fun `ChatterConfig Decision loads from EnvReader, validates init invariants, and allows reactionThreshold at or above decision threshold`() {
        val defaults = ChatterConfig()
        assertEquals("gemini-decision-1", defaults.decision.model)
        assertEquals(0.7, defaults.decision.threshold)
        assertEquals(15_000L, defaults.decision.timeoutMillis)
        assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, defaults.decision.instructions)
        assertEquals(15, defaults.decision.decisionMessageCount)

        val custom =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_DECISION_MODEL" to "models/gemini-decision-2",
                    "CHATTER_DECISION_THRESHOLD" to "0.6",
                    "CHATTER_DECISION_TIMEOUT_SECONDS" to "20",
                    "CHATTER_DECISION_INSTRUCTIONS" to "  Be concise  ",
                    "CHATTER_DECISION_MESSAGE_COUNT" to "42",
                    "CHATTER_REACTION_THRESHOLD" to "0.85",
                ),
            )
        assertEquals("gemini-decision-2", custom.decision.cleanModel)
        assertEquals(0.6, custom.decision.threshold)
        assertEquals(20_000L, custom.decision.timeoutMillis)
        assertEquals("Be concise", custom.decision.instructions)
        assertEquals(42, custom.decision.decisionMessageCount)
        assertEquals(0.85, custom.reactionThreshold)

        // reactionThreshold == decision.threshold is also allowed
        val equalThresholds =
            ChatterConfig(
                reactionThreshold = 0.7,
                decision = ChatterConfig.Decision(threshold = 0.7),
            )
        assertEquals(0.7, equalThresholds.reactionThreshold)

        // Direct Decision init {} invariants
        assertFailsWith<ConfigValidationException> { ChatterConfig.Decision(model = "   ") }
        assertFailsWith<ConfigValidationException> { ChatterConfig.Decision(threshold = -0.1) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.Decision(threshold = 1.1) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.Decision(threshold = Double.NaN) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.Decision(timeoutMillis = 0L) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.Decision(decisionMessageCount = 0) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.Decision(decisionMessageCount = 101) }

        // EnvReader Decision validation
        for ((k, v) in listOf(
            "CHATTER_DECISION_THRESHOLD" to "1.5",
            "CHATTER_DECISION_TIMEOUT_SECONDS" to "0",
            "CHATTER_DECISION_MESSAGE_COUNT" to "101",
        )) {
            assertFailsWith<ConfigValidationException> {
                ChatterConfig.from(EnvReader.of(k to v))
            }
        }
    }

    @Test
    fun `ChatterConfig composes filter and memory sections from EnvReader and validates Snowflake IDs and cooldown`() {
        val defaults = ChatterConfig.from(EnvReader.of())
        assertTrue(defaults.filter.enabled)
        assertFalse(defaults.effectiveEnabled)
        assertTrue(defaults.filter.allowedChannels.isEmpty())
        assertEquals(5_000L, defaults.filter.cooldownDurationMs)
        assertEquals(10L, defaults.memory.historyWindowMinutes)

        val configured =
            ChatterConfig.from(
                EnvReader.of(
                    "GEMINI_API_KEY" to "secret-gemini-key",
                    "CHATTER_ENABLED" to "true",
                    "CHATTER_ALLOWED_CHANNELS" to "111, 222",
                    "CHATTER_COOLDOWN_SECONDS" to "45",
                    "CHATTER_HISTORY_WINDOW_MINUTES" to "20",
                ),
            )
        assertTrue(configured.filter.enabled)
        assertTrue(configured.effectiveEnabled)
        assertEquals(
            setOf(
                dev.kord.common.entity
                    .Snowflake(111L),
                dev.kord.common.entity
                    .Snowflake(222L),
            ),
            configured.filter.allowedChannels,
        )
        assertEquals(45_000L, configured.filter.cooldownDurationMs)
        assertEquals(20L, configured.memory.historyWindowMinutes)

        val disabledByFlag =
            ChatterConfig.from(
                EnvReader.of(
                    "GEMINI_API_KEY" to "secret-gemini-key",
                    "CHATTER_ENABLED" to "false",
                ),
            )
        assertFalse(disabledByFlag.filter.enabled)
        assertFalse(disabledByFlag.effectiveEnabled)

        assertFailsWith<ConfigValidationException> { ChatterFilterConfig(cooldownDurationMs = -1L) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_ENABLED" to "maybe")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_COOLDOWN_SECONDS" to "-1")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_ALLOWED_CHANNELS" to "111,not-a-snowflake")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_ALLOWED_CHANNELS" to "-123")) }
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
    fun `ChatterPromptBuilder formats speaker headers with uid and BOT badge when metadata is present`() {
        val messages =
            listOf(
                ChatMessage(author = "Alice", content = "大家早安", authorId = "123456", isBot = false),
                ChatMessage(author = "NewsBot", content = "今日頭條", authorId = "789012", isBot = true),
                ChatMessage(author = "Luna", content = "早安呀", authorId = "999999", isBot = false),
            )

        val formatted = ChatterPromptBuilder.formatTranscript(messages)
        assertEquals(
            "Alice (uid:123456): 大家早安\n" +
                "NewsBot [BOT] (uid:789012): 今日頭條\n" +
                "Luna (uid:999999): 早安呀",
            formatted,
        )
        assertEquals("Alice (uid:123456)", ChatterPromptBuilder.formatSpeakerHeader("Alice", "123456", isBot = false))
        assertEquals("NewsBot [BOT] (uid:789012)", ChatterPromptBuilder.formatSpeakerHeader("NewsBot", "789012", isBot = true))
        assertEquals("Luna (uid:999999)", ChatterPromptBuilder.formatSpeakerHeader("Luna", "999999", isBot = false))
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

    @Test
    fun `ChatterPromptBuilder buildUserPrompt and buildReactionUserPrompt share identical supplemental title url and comments formatting`() {
        val input =
            ChatterInput(
                transcript = "[Alice]: 看看這個\n已有留言",
                title = "測試標題",
                url = "https://example.com/post",
                comments = listOf("已有留言", "新留言 A", "  新留言 B  ", ""),
            )

        val replyPrompt = ChatterPromptBuilder.buildUserPrompt(input)
        val reactionPrompt = ChatterPromptBuilder.buildReactionUserPrompt(input)

        val expectedPrefix =
            "以下為 Discord 頻道的近期對話紀錄：\n\n" +
                "[Alice]: 看看這個\n已有留言\n" +
                "標題：測試標題\n" +
                "來源網址：https://example.com/post\n" +
                "社群留言：\n" +
                "1. 新留言 A\n" +
                "2. 新留言 B"

        assertTrue(replyPrompt.startsWith(expectedPrefix))
        assertTrue(reactionPrompt.startsWith(expectedPrefix))
    }

    @Test
    fun `ChatterConfig validationError masks sensitive key values via SecretMasker`() {
        val rawSecret = "AIzaSySuperSecretKey1234567890"
        val error = ChatterConfig.validationError("GEMINI_API_KEY", "a valid API key", rawSecret)
        val message = assertNotNull(error.message)
        assertContains(message, "GEMINI_API_KEY")
        assertFalse(message.contains(rawSecret))
        assertContains(message, "***")
    }

    @Test
    fun `ChatterPromptBuilder system instruction explains inline mention formats, restrained ping etiquette, and includes kordSelfId when provided`() {
        val baseInstruction = ChatterPromptBuilder.buildSystemInstruction()
        assertContains(baseInstruction, "@Name (uid:<id>)")
        assertContains(baseInstruction, "@Luna [YOU] (uid:<selfId>)")
        assertContains(baseInstruction, "@Role (role)")
        assertContains(baseInstruction, "#channel")
        assertContains(baseInstruction, "<@uid>")

        val withSelfId =
            ChatterPromptBuilder.buildSystemInstruction(
                personaState = null,
                kordSelfId = "999888777",
            )
        assertContains(withSelfId, "999888777")

        val withBlankSelfId =
            ChatterPromptBuilder.buildSystemInstruction(
                personaState = null,
                kordSelfId = "   ",
            )
        assertEquals(ChatterPromptBuilder.SYSTEM_INSTRUCTION, withBlankSelfId)

        val requestWithSelfId =
            ChatterPromptBuilder.buildInteractionRequest(
                input = ChatterInput(transcript = "Alice (uid:101): @Luna [YOU] (uid:999888777) 早安"),
                kordSelfId = "999888777",
            )
        assertContains(assertNotNull(requestWithSelfId.systemInstruction), "999888777")
    }

    @Test
    fun `ChatterPromptBuilder and GeminiDecisionClient include channelContext block and untrusted-metadata isolation`() {
        val channelBlock = "[頻道脈絡]\n位置：#general\n主題：日常閒聊"
        val input =
            ChatterInput(
                transcript = "Alice: 早安",
                channelContext = channelBlock,
            )

        val replyPrompt = ChatterPromptBuilder.buildUserPrompt(input)
        val reactionPrompt = ChatterPromptBuilder.buildReactionUserPrompt(input)

        assertTrue(replyPrompt.startsWith("$channelBlock\n\n以下為 Discord 頻道的近期對話紀錄："))
        assertTrue(reactionPrompt.startsWith("$channelBlock\n\n以下為 Discord 頻道的近期對話紀錄："))

        assertContains(ChatterPromptBuilder.buildSystemInstruction(), "[頻道脈絡]")
        assertContains(ChatterPromptBuilder.buildReactionSystemInstruction(), "[頻道脈絡]")
        assertContains(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, "[頻道脈絡]")
    }
}
