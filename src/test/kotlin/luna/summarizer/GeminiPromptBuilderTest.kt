package luna.summarizer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeminiPromptBuilderTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    @Test
    fun `system instruction specifies Taiwanese Traditional Chinese and strictly prohibits emojis`() {
        val instruction = GeminiPromptBuilder.SYSTEM_INSTRUCTION

        assertContains(instruction, "台灣繁體中文")
        assertContains(instruction, "zh-TW")
        assertContains(instruction, "TL;DR:")
        assertContains(instruction, "核心重點:")
        assertContains(instruction, "參與者觀點:")
        assertContains(instruction, "Emoji")

        assertFalse(EmojiSanitizer.containsEmoji(instruction), "System instruction should not contain emojis")
        // Also verify that actual emojis would be detected
        assertTrue(EmojiSanitizer.containsEmoji("核心重點: 🚀"))
        assertTrue(EmojiSanitizer.containsEmoji("參與者觀點 👍"))
    }

    @Test
    fun `buildUserPrompt cleanly accommodates multi-message dialogues alongside optional title and url`() {
        val dialogueContent =
            """
            [alice]: 請問大家對新架構有什麼看法？
            [bob]: 我認為效能提升非常顯著，值得採用。
            [charlie]: 但遷移成本可能需要評估一下。
            """.trimIndent()

        val input =
            SummaryInput(
                title = "Luna Architecture RFC",
                content = dialogueContent,
                url = "https://github.com/project/luna/issues/23",
                comments = listOf("外部留言 1: 贊同", "外部留言 2: 反對"),
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "標題：Luna Architecture RFC")
        assertContains(prompt, "來源網址：https://github.com/project/luna/issues/23")
        assertContains(
            prompt,
            "內文：\n[alice]: 請問大家對新架構有什麼看法？\n[bob]: 我認為效能提升非常顯著，值得採用。\n[charlie]: 但遷移成本可能需要評估一下。",
        )
        assertContains(prompt, "社群留言與討論：\n1. 外部留言 1: 贊同\n2. 外部留言 2: 反對")
    }

    @Test
    fun `buildUserPrompt cleanly accommodates multi-message dialogues without title and url`() {
        val dialogueContent =
            """
            [alice]: 今天下午三點開會討論？
            [bob]: +1 沒問題
            [charlie]: 我會準時參加
            """.trimIndent()

        val input =
            SummaryInput(
                content = dialogueContent,
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertFalse(prompt.contains("標題："))
        assertFalse(prompt.contains("來源網址："))
        assertFalse(prompt.contains("社群留言與討論："))
        assertEquals("內文：\n$dialogueContent", prompt)
    }

    @Test
    fun `buildUserPrompt formats content without comments when comments are empty`() {
        val input =
            SummaryInput(
                title = "科技新突破",
                content = "這是一篇關於量子運算的深度分析文章。",
                url = "https://example.com/quantum",
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "標題：科技新突破")
        assertContains(prompt, "來源網址：https://example.com/quantum")
        assertContains(prompt, "內文：\n這是一篇關於量子運算的深度分析文章。")
        assertFalse(prompt.contains("社群留言與討論"), "Should not include comments section when empty")
    }

    @Test
    fun `buildUserPrompt formats comments when present`() {
        val input =
            SummaryInput(
                title = "討論熱烈的話題",
                content = "某平台推出新功能。",
                comments = listOf("這功能太實用了！", "感覺有點雞肋，還要觀察。"),
                url = "https://example.com/post",
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "社群留言與討論：")
        assertContains(prompt, "1. 這功能太實用了！")
        assertContains(prompt, "2. 感覺有點雞肋，還要觀察。")
    }

    @Test
    fun `buildRequest creates valid serializable payload for Gemini REST API`() {
        val input =
            SummaryInput(
                content = "測試內容",
                title = "測試標題",
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        assertEquals("user", request.contents.first().role)
        assertEquals(1, request.contents.size)
        assertNotNull(request.systemInstruction)
        assertEquals(0.2, request.generationConfig?.temperature)

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"contents\"")
        assertContains(serialized, "\"systemInstruction\"")
        assertContains(serialized, "\"generationConfig\"")
        assertContains(serialized, "\"temperature\":0.2")
    }

    @Test
    fun `resolveProcessingMode selects STATIC for under or equal 300s and AGENTIC for over 300s`() {
        assertEquals(VideoProcessingMode.STATIC, GeminiPromptBuilder.resolveProcessingMode(null))
        assertEquals(VideoProcessingMode.STATIC, GeminiPromptBuilder.resolveProcessingMode(0L))
        assertEquals(VideoProcessingMode.STATIC, GeminiPromptBuilder.resolveProcessingMode(300L))
        assertEquals(VideoProcessingMode.AGENTIC, GeminiPromptBuilder.resolveProcessingMode(301L))
        assertEquals(VideoProcessingMode.AGENTIC, GeminiPromptBuilder.resolveProcessingMode(3600L))
    }

    @Test
    fun `buildRequest includes video file data part with static processing mode for short videos`() {
        val input =
            SummaryInput(
                title = "Short YouTube Clip",
                content = "A brief 2-minute tutorial",
                videoUri = "https://www.youtube.com/watch?v=short123",
                videoDurationSeconds = 120L,
                comments = listOf("Great explanation!", "Thanks!"),
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        val parts = request.contents.first().parts
        assertEquals(2, parts.size)
        val videoPart = parts[0]
        val fileData = videoPart.fileData
        assertNotNull(fileData)
        assertEquals("https://www.youtube.com/watch?v=short123", fileData.fileUri)
        assertEquals("static", fileData.processing)

        val textPart = parts[1]
        val text = textPart.text
        assertNotNull(text)
        assertContains(text, "標題：Short YouTube Clip")
        assertContains(text, "社群留言與討論：")
        assertContains(text, "1. Great explanation!")

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"file_uri\":\"https://www.youtube.com/watch?v=short123\"")
        assertContains(serialized, "\"processing\":\"static\"")
    }

    @Test
    fun `buildRequest includes video file data part with agentic processing mode for long videos`() {
        val input =
            SummaryInput(
                title = "Long Tech Conference Talk",
                content = "1-hour deep dive into compiler architecture",
                videoUri = "https://www.youtube.com/watch?v=longTalk456",
                videoDurationSeconds = 3600L,
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        val parts = request.contents.first().parts
        assertEquals(2, parts.size)
        val videoPart = parts[0]
        val fileData = videoPart.fileData
        assertNotNull(fileData)
        assertEquals("https://www.youtube.com/watch?v=longTalk456", fileData.fileUri)
        assertEquals("agentic", fileData.processing)

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"file_uri\":\"https://www.youtube.com/watch?v=longTalk456\"")
        assertContains(serialized, "\"processing\":\"agentic\"")
    }
}
