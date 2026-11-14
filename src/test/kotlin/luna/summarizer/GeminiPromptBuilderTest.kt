package luna.summarizer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import luna.core.ai.ChatImageAttachment
import luna.core.ai.EmojiSanitizer
import luna.core.ai.GroundingCitation
import luna.core.ai.VideoProcessingMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiPromptBuilderTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
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
    fun `buildRequest creates valid serializable payload for Gemini Interactions API`() {
        val input =
            SummaryInput(
                content = "測試內容",
                title = "測試標題",
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        assertEquals(1, request.input.size)
        assertEquals("text", request.input.first().type)
        assertNotNull(request.systemInstruction)
        assertEquals(1024, request.generationConfig?.maxOutputTokens)

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"input\"")
        assertContains(serialized, "\"system_instruction\"")
        assertContains(serialized, "\"generation_config\"")
        assertContains(serialized, "\"max_output_tokens\":1024")
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
    fun `buildRequest includes video input block for Interactions API omitting mime_type for YouTube`() {
        val input =
            SummaryInput(
                title = "Short YouTube Clip",
                content = "A brief 2-minute tutorial",
                videoUri = "https://www.youtube.com/watch?v=short123",
                videoDurationSeconds = 120L,
                comments = listOf("Great explanation!", "Thanks!"),
            )
        val request = GeminiPromptBuilder.buildRequest(input)

        val parts = request.input
        assertEquals(2, parts.size)
        val videoPart = parts[0]
        assertEquals("video", videoPart.type)
        assertEquals("https://www.youtube.com/watch?v=short123", videoPart.uri)
        assertNull(videoPart.mimeType)
        assertEquals("static", videoPart.processing)

        val textPart = parts[1]
        val text = textPart.text
        assertNotNull(text)
        assertContains(text, "標題：Short YouTube Clip")
        assertContains(text, "社群留言與討論：")
        assertContains(text, "1. Great explanation!")

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"uri\":\"https://www.youtube.com/watch?v=short123\"")
        assertFalse(serialized.contains("\"mime_type\""), "YouTube video block must omit mime_type")
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
    fun `buildRequest includes image input blocks for chat image attachments with direct HTTPS URI and MIME type`() {
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

        val userParts = request.input
        assertEquals(3, userParts.size) // 2 image blocks + 1 text block

        val part1 = userParts[0]
        assertEquals("image", part1.type)
        assertEquals("https://cdn.discordapp.com/attachments/123/456/screenshot.png", part1.uri)
        assertEquals("image/png", part1.mimeType)

        val part2 = userParts[1]
        assertEquals("image", part2.type)
        assertEquals("https://cdn.discordapp.com/attachments/123/789/diagram.jpeg", part2.uri)
        assertEquals("image/jpeg", part2.mimeType)

        val textPart = userParts[2]
        val text = textPart.text
        assertNotNull(text)
        assertContains(text, "Please analyze these architecture diagrams")
        assertContains(text, "Visual Focus Prompting")

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"uri\":\"https://cdn.discordapp.com/attachments/123/456/screenshot.png\"")
        assertContains(serialized, "\"mime_type\":\"image/png\"")
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
    fun `buildRequest includes both video and chat image input blocks in multimodal order when both are present`() {
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

        val parts = request.input
        // 1 video block + 2 image blocks + 1 text block = 4 blocks
        assertEquals(4, parts.size)

        val videoPart = parts[0]
        assertEquals("video", videoPart.type)
        assertEquals("https://www.youtube.com/watch?v=fullStackDemo", videoPart.uri)
        assertNull(videoPart.mimeType)

        val imagePart1 = parts[1]
        assertEquals("image", imagePart1.type)
        assertEquals("https://cdn.discordapp.com/attachments/10/20/diag1.png", imagePart1.uri)
        assertEquals("image/png", imagePart1.mimeType)

        val imagePart2 = parts[2]
        assertEquals("image", imagePart2.type)
        assertEquals("https://cdn.discordapp.com/attachments/10/30/diag2.jpeg", imagePart2.uri)
        assertEquals("image/jpeg", imagePart2.mimeType)

        val textPart = parts[3]
        val text = textPart.text
        assertNotNull(text)
        assertContains(text, "Combined Multimodal Analysis")
        assertContains(text, "Visual Focus Prompting")

        val sysText = request.systemInstruction
        assertNotNull(sysText)
        assertEquals(GeminiPromptBuilder.VIDEO_AND_IMAGE_SYSTEM_INSTRUCTION, sysText)

        val serialized = json.encodeToString(request)
        assertContains(serialized, "\"uri\":\"https://www.youtube.com/watch?v=fullStackDemo\"")
        assertContains(serialized, "\"uri\":\"https://cdn.discordapp.com/attachments/10/20/diag1.png\"")
        assertContains(serialized, "\"uri\":\"https://cdn.discordapp.com/attachments/10/30/diag2.jpeg\"")
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

    @Test
    fun `system instructions include search grounding and terminology fact-checking section with strict source isolation`() {
        val instructions =
            listOf(
                GeminiPromptBuilder.SYSTEM_INSTRUCTION,
                GeminiPromptBuilder.VIDEO_SYSTEM_INSTRUCTION,
                GeminiPromptBuilder.CHAT_IMAGE_SYSTEM_INSTRUCTION,
                GeminiPromptBuilder.VIDEO_AND_IMAGE_SYSTEM_INSTRUCTION,
            )

        for (inst in instructions) {
            assertContains(inst, "台灣繁體中文")
            assertContains(inst, "zh-TW")
            assertContains(inst, "TL;DR:")
            assertContains(inst, "核心重點:")
            assertContains(inst, "名詞與事實查核:")
            assertContains(inst, "Google")
            assertContains(inst, "搜尋")
            assertFalse(EmojiSanitizer.containsEmoji(inst), "Instruction must not contain emojis")
        }

        // Standard chat instructions specify strict source isolation for participant discussions
        assertContains(GeminiPromptBuilder.SYSTEM_INSTRUCTION, "嚴格來源隔離")
        assertContains(GeminiPromptBuilder.SYSTEM_INSTRUCTION, "參與者觀點")
        assertContains(GeminiPromptBuilder.CHAT_IMAGE_SYSTEM_INSTRUCTION, "嚴格來源隔離")
        assertContains(GeminiPromptBuilder.CHAT_IMAGE_SYSTEM_INSTRUCTION, "Visual Focus Prompting")
        assertContains(GeminiPromptBuilder.CHAT_IMAGE_SYSTEM_INSTRUCTION, "OCR")
        assertContains(GeminiPromptBuilder.VIDEO_SYSTEM_INSTRUCTION, "[mm:ss]")
        assertContains(GeminiPromptBuilder.VIDEO_SYSTEM_INSTRUCTION, "1500")
    }

    @Test
    fun `formatGroundingCitations formats citations with angle-bracketed URLs capped at top 3 unique sources`() {
        val citations =
            listOf(
                GroundingCitation(title = "Kotlin Documentation", url = "https://kotlinlang.org/docs"),
                GroundingCitation(title = "Google Search", url = "https://www.google.com"),
                GroundingCitation(title = "Kotlin Documentation Duplicate", url = "https://kotlinlang.org/docs"),
                GroundingCitation(title = "Android Developers", url = "https://developer.android.com"),
                GroundingCitation(title = "GitHub", url = "https://github.com"),
            )

        val formatted = GeminiPromptBuilder.formatGroundingCitations(citations)
        val expected =
            """
            [Kotlin Documentation](<https://kotlinlang.org/docs>)
            [Google Search](<https://www.google.com>)
            [Android Developers](<https://developer.android.com>)
            """.trimIndent()

        assertEquals(expected, formatted)
    }

    @Test
    fun `formatGroundingCitations falls back to URL when citation title is blank`() {
        val citations =
            listOf(
                GroundingCitation(title = "", url = "https://example.com/untitled"),
                GroundingCitation(title = "   ", url = "https://example.com/whitespace"),
            )

        val formatted = GeminiPromptBuilder.formatGroundingCitations(citations)
        val expected =
            """
            [https://example.com/untitled](<https://example.com/untitled>)
            [https://example.com/whitespace](<https://example.com/whitespace>)
            """.trimIndent()

        assertEquals(expected, formatted)
    }

    @Test
    fun `formatGroundingCitations returns empty string when citations list is empty or invalid`() {
        assertEquals("", GeminiPromptBuilder.formatGroundingCitations(emptyList()))
        assertEquals(
            "",
            GeminiPromptBuilder.formatGroundingCitations(
                listOf(GroundingCitation(title = "No URL", url = "")),
            ),
        )
    }

    @Test
    fun `buildUserPrompt injects url_context guidance when webUrls is non-empty and omits when empty`() {
        val withWebUrls =
            SummaryInput(
                content = "[alice]: Read https://kotlinlang.org/docs",
                webUrls = listOf("https://kotlinlang.org/docs"),
            )
        val promptWithUrls = GeminiPromptBuilder.buildUserPrompt(withWebUrls)
        assertContains(promptWithUrls, "url_context")
        assertContains(promptWithUrls, "1 個公開網頁連結")

        val withoutWebUrls =
            SummaryInput(
                content = "[alice]: Plain chat message",
            )
        val promptWithoutUrls = GeminiPromptBuilder.buildUserPrompt(withoutWebUrls)
        assertFalse(promptWithoutUrls.contains("url_context"))
    }
}
