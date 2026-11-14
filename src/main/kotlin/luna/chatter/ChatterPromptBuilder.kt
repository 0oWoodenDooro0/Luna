package luna.chatter

import luna.core.ai.EmojiSanitizer
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionGenerationConfig
import luna.core.ai.GeminiInteractionRequest
import luna.core.ai.GeminiInteractionTool
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.VideoProcessingMode

/**
 * Builds system persona instructions, user prompts, and Interactions API request payloads for [ChatterGenerator],
 * while providing output sanitization and Discord 2,000-character payload bound enforcement.
 */
object ChatterPromptBuilder {
    const val DISCORD_MAX_LENGTH: Int = 2000
    const val MAX_OUTPUT_TOKENS: Int = 512
    const val TRUNCATION_ELLIPSIS: String = "..."

    const val SYSTEM_INSTRUCTION: String =
        "你叫 Luna，是這個 Discord 社群伺服器的一員，是平常和大家一起打字閒聊的社群成員，不是客服或制式 AI 助手。\n\n" +
            "請務必遵守以下所有對話原則：\n" +
            "1. 角色定位：你是 Discord 社群成員，不是客服或制式 AI 助手。以自然、隨性、平常群友打字聊天的口吻發言。\n" +
            "2. 禁用表情符號：嚴格禁止使用任何 emoji、表情符號或顏文字（包含 XD、Orz、(｡･ω･｡) 等一律禁止）。\n" +
            "3. 不使用標點符號：盡量不使用標點符號（不加逗號、句號、驚嘆號），需要斷句或換口氣時一律用空格或分行隔開句子。\n" +
            "4. 極簡口語化：語句要極簡、口語化，不要有書面語、長句鋪陳或小說般的形容修辭，用字精簡自然，就像真正的 Discord 群友平常打字閒聊一樣隨性。\n" +
            "5. 一次講一句重點：通常一次只講一句重點，最多兩到三句極短句，嚴禁長篇大論、說教或分析條列。\n" +
            "6. 語言用語：以台灣繁體中文（zh-TW）為主要社群慣用語，或配合對話語言交流。\n" +
            "7. 嚴格禁用 AI 套話與開場白：輸出中絕對嚴禁使用任何制式、官方或機械化的 AI 助理開場白或套話（例如「身為一個 AI」、「很高興為您服務」、「有什麼我可以幫忙的嗎」等），請直接切入話題搭話。\n" +
            "8. 對話脈絡理解：對話紀錄中標註了各發言者的名稱（例如 [使用者]: 內容）。請根據上下文脈絡隨性自然地搭話。\n" +
            "9. 順應最新話鋒：發言時只順著聊天紀錄最新、當前正在進行的話題接話，切勿回頭去回覆或挖掘紀錄更早之前已經結束的舊句子。\n" +
            "10. 多模態與連結內容理解：若對話中附帶圖片（標記為 [附帶圖片 N]）、YouTube 影片、社群連結內容（[連結內容: ...]）或一般公開網頁連結" +
            "（可透過 url_context 工具讀取網頁內容），請自然融入視覺畫面、影片重點或網頁資訊來搭話或回應，但仍維持口語簡短的聊天節奏，不可變成制式摘要。\n" +
            "11. 搜尋查證與禁止摘要格式：必要時可透過 google_search 工具查證不熟悉的名詞或即時事實，將查到的重點自然融入口語回覆中。" +
            "絕對禁止輸出「TL;DR:」、「核心重點:」、「參與者觀點:」、「社群觀點:」、「名詞與事實查核:」等制式摘要標題，" +
            "也絕對不可附上 Markdown 網址列表、參考來源網址清單或任何引用來源標註。"

    fun buildSystemInstruction(): String = SYSTEM_INSTRUCTION

    fun formatTranscript(messages: List<ChatMessage>): String =
        messages.joinToString("\n") { msg ->
            "[${msg.author}]: ${msg.content.trim()}"
        }

    fun buildUserPrompt(transcript: String): String = buildUserPrompt(ChatterInput(transcript = transcript))

    fun buildUserPrompt(messages: List<ChatMessage>): String = buildUserPrompt(formatTranscript(messages))

    fun buildUserPrompt(input: ChatterInput): String {
        val sb = StringBuilder()
        val trimmedTranscript = input.transcript.trim()
        sb.append("以下為 Discord 頻道的近期對話紀錄：\n\n")
        if (trimmedTranscript.isNotEmpty()) {
            sb.append(trimmedTranscript)
        }

        val supplemental = StringBuilder()
        val cleanTitle = input.title?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTitle != null && !trimmedTranscript.contains(cleanTitle)) {
            supplemental.append("\n標題：").append(cleanTitle)
        }
        val cleanUrl = input.url?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanUrl != null && !trimmedTranscript.contains(cleanUrl)) {
            supplemental.append("\n來源網址：").append(cleanUrl)
        }
        val missingComments =
            input.comments
                .map { it.trim() }
                .filter { it.isNotEmpty() && !trimmedTranscript.contains(it) }
        if (missingComments.isNotEmpty()) {
            supplemental.append("\n社群留言：")
            missingComments.forEachIndexed { idx, comment ->
                supplemental.append("\n${idx + 1}. ").append(comment)
            }
        }
        if (supplemental.isNotEmpty()) {
            if (trimmedTranscript.isNotEmpty()) {
                sb.append("\n")
            }
            sb.append(supplemental.toString().trimStart())
        }

        val mediaHints = mutableListOf<String>()
        if (!input.videoUri.isNullOrBlank()) {
            mediaHints.add("附帶 YouTube 影片請結合畫面與語音自然搭話")
        }
        if (input.imageAttachments.isNotEmpty()) {
            mediaHints.add("對話中附帶圖片（[附帶圖片 N]）請自然結合視覺內容回應")
        }
        if (input.webUrls.isNotEmpty()) {
            mediaHints.add("對話中的一般網頁連結可透過 url_context 讀取內容並自然融入回應")
        }
        val mediaDirective =
            if (mediaHints.isNotEmpty()) {
                "（${mediaHints.joinToString("，")}）"
            } else {
                ""
            }

        sb.append(
            "\n\n請以 Luna 的社群成員口吻回覆這段對話$mediaDirective。" +
                "要求：順著最新聊天紀錄接話（切勿回頭回覆早前的舊句子）、極簡口語、一次講一句重點（最多兩到三句極短句）、" +
                "不加標點符號（不加逗號、句號、驚嘆號，斷句用空格或分行）、嚴禁使用任何 emoji 或表情符號、" +
                "切勿使用制式 AI 開場白與套話、不可輸出任何摘要標題、Markdown 網址列表或引用來源標註：",
        )
        return sb.toString().trim()
    }

    fun buildTools(
        config: ChatterConfig,
        webUrls: List<String>,
    ): List<GeminiInteractionTool> =
        buildList {
            if (config.urlContextEnabled && webUrls.isNotEmpty()) {
                add(GeminiInteractionTools.URL_CONTEXT)
            }
            if (config.searchGroundingEnabled) {
                add(GeminiInteractionTools.GOOGLE_SEARCH)
            }
        }

    fun buildInteractionRequest(
        input: ChatterInput,
        model: String = ChatterConfig.DEFAULT_MODEL,
        tools: List<GeminiInteractionTool> = emptyList(),
        videoProcessingMode: VideoProcessingMode = VideoProcessingMode.STATIC,
        background: Boolean = false,
    ): GeminiInteractionRequest {
        val inputs =
            GeminiInteractionClient.buildMultimodalInputs(
                promptText = buildUserPrompt(input),
                imageAttachments = input.imageAttachments,
                videoUri = input.videoUri,
                videoProcessingMode = videoProcessingMode,
            )

        return GeminiInteractionRequest(
            model = model.trim().removePrefix("models/"),
            input = inputs,
            systemInstruction = buildSystemInstruction(),
            tools = tools.takeIf { it.isNotEmpty() },
            generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = MAX_OUTPUT_TOKENS),
            background = background,
        )
    }

    fun sanitizeResponse(
        rawText: String,
        maxLength: Int = DISCORD_MAX_LENGTH,
    ): String {
        var text = rawText.trim()

        // Strip wrapping code block if model wrapped reply in markdown fences
        if (text.startsWith("```") && text.endsWith("```") && text.length >= 6) {
            val lines = text.lines()
            if (lines.size >= 2 && lines.first().startsWith("```") && lines.last().trim() == "```") {
                text = lines.subList(1, lines.size - 1).joinToString("\n").trim()
            }
        }

        // Strip common formal AI boilerplate prefixes
        val boilerplatePatterns =
            listOf(
                Regex("""^(身為一個?AI(?:語言模型|助手)?|作為一個?AI(?:語言模型|助手)?|我是由\s*Google\s*訓練的(?:大型)?語言模型)[，,:\s\n]+""", RegexOption.IGNORE_CASE),
                Regex("""^(As an AI(?: language model)?)[,:\s\n]+""", RegexOption.IGNORE_CASE),
            )
        for (pattern in boilerplatePatterns) {
            text = text.replace(pattern, "").trim()
        }

        text = EmojiSanitizer.stripEmojis(text).trim()

        return truncateToDiscordLimit(text, maxLength = maxLength)
    }

    /**
     * Strictly bounds text output to Discord's 2,000-character payload limit.
     * Truncates gracefully and appends trailing ellipsis (`...`) when boundary is exceeded.
     */
    fun truncateToDiscordLimit(
        text: String,
        maxLength: Int = DISCORD_MAX_LENGTH,
    ): String {
        if (text.length <= maxLength) return text
        val prefixLength = (maxLength - TRUNCATION_ELLIPSIS.length).coerceAtLeast(0)
        return text.take(prefixLength) + TRUNCATION_ELLIPSIS
    }
}
