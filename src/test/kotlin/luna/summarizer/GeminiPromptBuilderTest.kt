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

    @Test
    fun `video system instruction specifies Taiwanese Traditional Chinese, zero emojis, and structured sections`() {
        val instruction = GeminiPromptBuilder.VIDEO_SYSTEM_INSTRUCTION

        assertContains(instruction, "台灣繁體中文")
        assertContains(instruction, "zh-TW")
        assertContains(instruction, "TL;DR:")
        assertContains(instruction, "核心重點:")
        assertContains(instruction, "社群觀點:")
        assertContains(instruction, "Emoji")

        assertFalse(EmojiSanitizer.containsEmoji(instruction), "Video system instruction should not contain emojis")
    }

    @Test
    fun `video system instruction enforces multimodal video understanding, source isolation, and timestamp milestones`() {
        val instruction = GeminiPromptBuilder.VIDEO_SYSTEM_INSTRUCTION

        // Multimodal video understanding directives
        assertContains(instruction, "視覺畫面")
        assertContains(instruction, "語音")
        assertContains(instruction, "操作展示")

        // Strict source isolation directives
        assertContains(instruction, "核心重點")
        assertContains(instruction, "嚴禁")
        assertContains(instruction, "留言")
        assertContains(instruction, "社群觀點")

        // Milestones and timestamp references allowed
        assertContains(instruction, "[mm:ss]")

        // Length limit
        assertContains(instruction, "1500")
    }

    @Test
    fun `buildSystemInstruction returns VIDEO_SYSTEM_INSTRUCTION when videoUri is present and SYSTEM_INSTRUCTION otherwise`() {
        val videoInput =
            SummaryInput(
                content = "Video description",
                videoUri = "https://www.youtube.com/watch?v=sample123",
            )
        val nonVideoInput =
            SummaryInput(
                content = "Standard chat discussion",
                url = "https://example.com/article",
            )
        val blankVideoInput =
            SummaryInput(
                content = "Empty video URI discussion",
                videoUri = "   ",
            )

        assertEquals(GeminiPromptBuilder.VIDEO_SYSTEM_INSTRUCTION, GeminiPromptBuilder.buildSystemInstruction(videoInput))
        assertEquals(GeminiPromptBuilder.SYSTEM_INSTRUCTION, GeminiPromptBuilder.buildSystemInstruction(nonVideoInput))
        assertEquals(GeminiPromptBuilder.SYSTEM_INSTRUCTION, GeminiPromptBuilder.buildSystemInstruction(blankVideoInput))
    }

    @Test
    fun `buildUserPrompt formats creator description, comments, and injects video focus directives when videoUri is present`() {
        val input =
            SummaryInput(
                title = "Kotlin Multiplatform Tutorial",
                content = "00:00 Intro\n02:15 Shared Logic Demo\n08:30 Native UI Integration",
                url = "https://www.youtube.com/watch?v=kmpDemo",
                videoUri = "https://www.youtube.com/watch?v=kmpDemo",
                videoDurationSeconds = 600L,
                comments = listOf("Super helpful demo at 02:15!", "Can we use Compose on iOS?"),
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "標題：Kotlin Multiplatform Tutorial")
        assertContains(prompt, "來源網址：https://www.youtube.com/watch?v=kmpDemo")
        assertContains(prompt, "創作者說明與大綱：")
        assertContains(prompt, "02:15 Shared Logic Demo")
        assertContains(prompt, "社群留言與討論：")
        assertContains(prompt, "1. Super helpful demo at 02:15!")
        assertContains(prompt, "2. Can we use Compose on iOS?")

        // Video Focus directives and source isolation in prompt
        assertContains(prompt, "多模態")
        assertContains(prompt, "核心重點")
        assertContains(prompt, "社群觀點")
        assertContains(prompt, "[mm:ss]")
    }

    @Test
    fun `buildUserPrompt directs model to omit community perspectives when video comments are empty`() {
        val input =
            SummaryInput(
                title = "Solo Project Deep Dive",
                content = "Architectural overview and demo",
                url = "https://www.youtube.com/watch?v=soloProject",
                videoUri = "https://www.youtube.com/watch?v=soloProject",
                comments = emptyList(),
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "標題：Solo Project Deep Dive")
        assertContains(prompt, "創作者說明與大綱：")
        assertFalse(prompt.contains("社群留言與討論："), "Should not contain comments section when empty")
        assertContains(prompt, "省略「社群觀點」")
    }

    @Test
    fun `buildUserPrompt preserves non-video dialogue and web URL formatting without video directives`() {
        val input =
            SummaryInput(
                title = "Web Article",
                content = "Full article content about distributed consensus",
                url = "https://example.com/consensus",
                comments = listOf("Nice overview", "What about Raft?"),
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "標題：Web Article")
        assertContains(prompt, "來源網址：https://example.com/consensus")
        assertContains(prompt, "內文：")
        assertFalse(prompt.contains("創作者說明與大綱："))
        assertFalse(prompt.contains("多模態"))
        assertFalse(prompt.contains("[mm:ss]"))
    }

    @Test
    fun `buildRequest attaches video system instruction when videoUri is present`() {
        val input =
            SummaryInput(
                title = "Video Title",
                content = "Video Outline",
                videoUri = "https://www.youtube.com/watch?v=sampleVid",
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        val sysText =
            request.systemInstruction
                ?.parts
                ?.firstOrNull()
                ?.text
        assertNotNull(sysText)
        assertEquals(GeminiPromptBuilder.VIDEO_SYSTEM_INSTRUCTION, sysText)
        assertContains(sysText, "嚴格來源隔離")
    }

    @Test
    fun `buildRequest attaches standard system instruction when videoUri is absent`() {
        val input =
            SummaryInput(
                title = "Standard Article",
                content = "Article text",
                url = "https://example.com/article",
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        val sysText =
            request.systemInstruction
                ?.parts
                ?.firstOrNull()
                ?.text
        assertNotNull(sysText)
        assertEquals(GeminiPromptBuilder.SYSTEM_INSTRUCTION, sysText)
    }

    @Test
    fun `buildRequest includes fileData parts for chat image attachments with direct HTTPS URI and MIME type`() {
        val image1 =
            ChatImageAttachment(
                url = "https://cdn.discordapp.com/attachments/123/456/screenshot.png",
                mimeType = "image/png",
                filename = "screenshot.png",
            )
        val image2 =
            ChatImageAttachment(
                url = "https://cdn.discordapp.com/attachments/123/789/diagram.jpeg",
                mimeType = "image/jpeg",
                filename = "diagram.jpeg",
            )
        val input =
            SummaryInput(
                content = "Please analyze these architecture diagrams",
                imageAttachments = listOf(image1, image2),
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        val userParts = request.contents.first().parts
        assertEquals(3, userParts.size) // 2 image fileData parts + 1 text part

        val part1 = userParts[0].fileData
        assertNotNull(part1)
        assertEquals("https://cdn.discordapp.com/attachments/123/456/screenshot.png", part1.fileUri)
        assertEquals("image/png", part1.mimeType)

        val part2 = userParts[1].fileData
        assertNotNull(part2)
        assertEquals("https://cdn.discordapp.com/attachments/123/789/diagram.jpeg", part2.fileUri)
        assertEquals("image/jpeg", part2.mimeType)

        val textPart = userParts[2].text
        assertNotNull(textPart)
        assertContains(textPart, "Please analyze these architecture diagrams")
        assertContains(textPart, "Visual Focus Prompting")

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"file_uri\":\"https://cdn.discordapp.com/attachments/123/456/screenshot.png\"")
        assertContains(serialized, "\"mime_type\":\"image/png\"")
    }

    @Test
    fun `chat image system instruction specifies Taiwanese Traditional Chinese, zero emojis, and standard 3-section format`() {
        val instruction = GeminiPromptBuilder.CHAT_IMAGE_SYSTEM_INSTRUCTION

        assertContains(instruction, "台灣繁體中文")
        assertContains(instruction, "zh-TW")
        assertContains(instruction, "TL;DR:")
        assertContains(instruction, "核心重點:")
        assertContains(instruction, "參與者觀點:")
        assertContains(instruction, "Emoji")
        assertContains(instruction, "Visual Focus Prompting")

        assertFalse(EmojiSanitizer.containsEmoji(instruction), "Chat image system instruction should not contain emojis")
    }

    @Test
    fun `visual focus prompting instructions direct model to interpret OCR, code, error logs, diagrams, UI states, or photos into TLDR and core points`() {
        val instruction = GeminiPromptBuilder.CHAT_IMAGE_SYSTEM_INSTRUCTION

        assertContains(instruction, "視覺證據")
        assertContains(instruction, "OCR")
        assertContains(instruction, "程式碼")
        assertContains(instruction, "錯誤")
        assertContains(instruction, "架構圖")
        assertContains(instruction, "介面")
        assertContains(instruction, "照片")
        assertContains(instruction, "TL;DR")
        assertContains(instruction, "核心重點")
    }

    @Test
    fun `buildSystemInstruction returns chat image system instruction when image attachments are present and videoUri is absent`() {
        val input =
            SummaryInput(
                content = "Error log screenshot",
                imageAttachments =
                    listOf(
                        ChatImageAttachment(
                            url = "https://cdn.discordapp.com/attachments/1/2/err.webp",
                            mimeType = "image/webp",
                        ),
                    ),
            )
        val sysInstruction = GeminiPromptBuilder.buildSystemInstruction(input)
        assertEquals(GeminiPromptBuilder.CHAT_IMAGE_SYSTEM_INSTRUCTION, sysInstruction)
    }

    @Test
    fun `buildUserPrompt formats chat images with Visual Focus Prompting instructions`() {
        val input =
            SummaryInput(
                content = "[alice]: Here is the server stack trace\n[bob]: Checking logs",
                imageAttachments =
                    listOf(
                        ChatImageAttachment(
                            url = "https://cdn.discordapp.com/attachments/1/2/log.png",
                            mimeType = "image/png",
                            filename = "log.png",
                        ),
                    ),
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "[alice]: Here is the server stack trace")
        assertContains(prompt, "Visual Focus Prompting")
        assertContains(prompt, "視覺證據")
        assertContains(prompt, "OCR")
        assertContains(prompt, "程式碼")
        assertContains(prompt, "錯誤")
        assertContains(prompt, "TL;DR")
        assertContains(prompt, "核心重點")
    }

    @Test
    fun `video and image system instruction specifies Taiwanese Traditional Chinese, zero emojis, and coexistence rules`() {
        val instruction = GeminiPromptBuilder.VIDEO_AND_IMAGE_SYSTEM_INSTRUCTION

        assertContains(instruction, "台灣繁體中文")
        assertContains(instruction, "zh-TW")
        assertContains(instruction, "TL;DR:")
        assertContains(instruction, "核心重點:")
        assertContains(instruction, "社群觀點:")
        assertContains(instruction, "Emoji")
        assertContains(instruction, "Video & Visual Focus Prompting")
        assertContains(instruction, "嚴格來源隔離")

        assertFalse(EmojiSanitizer.containsEmoji(instruction), "Coexistence system instruction should not contain emojis")
    }

    @Test
    fun `buildSystemInstruction returns combined video and image system instruction when both are present`() {
        val input =
            SummaryInput(
                content = "Discussion with video and image",
                videoUri = "https://www.youtube.com/watch?v=sampleCoexist",
                imageAttachments =
                    listOf(
                        ChatImageAttachment(
                            url = "https://cdn.discordapp.com/attachments/1/2/screenshot.png",
                            mimeType = "image/png",
                        ),
                    ),
            )
        val sysInstruction = GeminiPromptBuilder.buildSystemInstruction(input)
        assertEquals(GeminiPromptBuilder.VIDEO_AND_IMAGE_SYSTEM_INSTRUCTION, sysInstruction)
    }

    @Test
    fun `buildUserPrompt formats both video outline and chat image Visual Focus Prompting when both are present`() {
        val input =
            SummaryInput(
                title = "Video and Image Discussion",
                content = "00:00 Intro\n03:00 Code Walkthrough",
                url = "https://www.youtube.com/watch?v=sampleCoexist",
                videoUri = "https://www.youtube.com/watch?v=sampleCoexist",
                videoDurationSeconds = 300L,
                comments = listOf("Great video!"),
                imageAttachments =
                    listOf(
                        ChatImageAttachment(
                            url = "https://cdn.discordapp.com/attachments/1/2/code.png",
                            mimeType = "image/png",
                            filename = "code.png",
                        ),
                    ),
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "標題：Video and Image Discussion")
        assertContains(prompt, "創作者說明與大綱：")
        assertContains(prompt, "00:00 Intro")
        assertContains(prompt, "社群留言與討論：")
        assertContains(prompt, "1. Great video!")
        assertContains(prompt, "【指示】：請運用多模態視覺與語音理解影片內容")
        assertContains(prompt, "Visual Focus Prompting")
        assertContains(prompt, "視覺證據")
        assertContains(prompt, "OCR")
    }

    @Test
    fun `buildRequest includes both video fileData and chat image fileData parts when both are present`() {
        val img1 =
            ChatImageAttachment(
                url = "https://cdn.discordapp.com/attachments/10/20/diag1.png",
                mimeType = "image/png",
                filename = "diag1.png",
            )
        val img2 =
            ChatImageAttachment(
                url = "https://cdn.discordapp.com/attachments/10/30/diag2.jpeg",
                mimeType = "image/jpeg",
                filename = "diag2.jpeg",
            )
        val input =
            SummaryInput(
                title = "Combined Multimodal Analysis",
                content = "00:00 Architecture\n05:00 Demo",
                videoUri = "https://www.youtube.com/watch?v=fullStackDemo",
                videoDurationSeconds = 180L,
                imageAttachments = listOf(img1, img2),
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        val parts = request.contents.first().parts
        // 1 video part + 2 image parts + 1 text prompt part = 4 parts
        assertEquals(4, parts.size)

        val videoPart = parts[0].fileData
        assertNotNull(videoPart)
        assertEquals("https://www.youtube.com/watch?v=fullStackDemo", videoPart.fileUri)
        assertEquals("static", videoPart.processing)

        val imagePart1 = parts[1].fileData
        assertNotNull(imagePart1)
        assertEquals("https://cdn.discordapp.com/attachments/10/20/diag1.png", imagePart1.fileUri)
        assertEquals("image/png", imagePart1.mimeType)

        val imagePart2 = parts[2].fileData
        assertNotNull(imagePart2)
        assertEquals("https://cdn.discordapp.com/attachments/10/30/diag2.jpeg", imagePart2.fileUri)
        assertEquals("image/jpeg", imagePart2.mimeType)

        val textPart = parts[3].text
        assertNotNull(textPart)
        assertContains(textPart, "Combined Multimodal Analysis")
        assertContains(textPart, "Visual Focus Prompting")

        val sysText =
            request.systemInstruction
                ?.parts
                ?.firstOrNull()
                ?.text
        assertNotNull(sysText)
        assertEquals(GeminiPromptBuilder.VIDEO_AND_IMAGE_SYSTEM_INSTRUCTION, sysText)

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"file_uri\":\"https://www.youtube.com/watch?v=fullStackDemo\"")
        assertContains(serialized, "\"file_uri\":\"https://cdn.discordapp.com/attachments/10/20/diag1.png\"")
        assertContains(serialized, "\"file_uri\":\"https://cdn.discordapp.com/attachments/10/30/diag2.jpeg\"")
    }

    @Test
    fun `formatExpandedDialogue formats sections with distinct labeled headers`() {
        val earlier = "[alice]: Previous context discussion\n[bob]: Agree with earlier points"
        val current = "[alice]: Current topic question\n[charlie]: Final consensus"

        val formatted = GeminiPromptBuilder.formatExpandedDialogue(earlier, current)

        val expected =
            """
            |[前置歷史背景]
            |[alice]: Previous context discussion
            |[bob]: Agree with earlier points
            |
            |[當前核心討論]
            |[alice]: Current topic question
            |[charlie]: Final consensus
            """.trimMargin()

        assertEquals(expected, formatted)
        assertTrue(formatted.startsWith("[前置歷史背景]"))
        assertContains(formatted, "[當前核心討論]")
    }

    @Test
    fun `buildUserPrompt formats distinct labeled sections when earlierContext is present`() {
        val input =
            SummaryInput(
                title = "Team Sync",
                content = "[alice]: What should we deploy today?\n[bob]: Let's ship ticket 60.",
                earlierContext = "[alice]: Planning ticket 60 scope\n[charlie]: Lookback and webhook summaries",
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "標題：Team Sync")
        assertContains(prompt, "內文：\n[前置歷史背景]")
        assertContains(prompt, "[alice]: Planning ticket 60 scope")
        assertContains(prompt, "[charlie]: Lookback and webhook summaries")
        assertContains(prompt, "[當前核心討論]")
        assertContains(prompt, "[alice]: What should we deploy today?")
        assertContains(prompt, "[bob]: Let's ship ticket 60.")
    }

    @Test
    fun `buildUserPrompt with video outline and earlierContext formats distinct sections in creator outline`() {
        val input =
            SummaryInput(
                title = "Video RFC",
                content = "00:00 Final conclusion",
                videoUri = "https://www.youtube.com/watch?v=vid123",
                earlierContext = "00:00 Initial discussion of architecture",
            )
        val prompt = GeminiPromptBuilder.buildUserPrompt(input)

        assertContains(prompt, "標題：Video RFC")
        assertContains(prompt, "創作者說明與大綱：\n[前置歷史背景]")
        assertContains(prompt, "00:00 Initial discussion of architecture")
        assertContains(prompt, "[當前核心討論]")
        assertContains(prompt, "00:00 Final conclusion")
    }
}
