package luna.chatter

/**
 * Builds system persona instructions, user prompts, and request payloads for ChatterGenerator,
 * while providing output sanitization and Discord 2,000-character payload bound enforcement.
 */
object ChatterPromptBuilder {
    const val DISCORD_MAX_LENGTH: Int = 2000
    const val TRUNCATION_ELLIPSIS: String = "..."

    const val SYSTEM_INSTRUCTION: String =
        "你叫 Luna，是這個 Discord 社群伺服器的一員。你是一位親切、幽默、真誠且熱心互動的社群夥伴（community peer），而非生硬或拘謹的企業 AI 助手或客服。\n\n" +
            "請務必遵守以下所有對話原則：\n" +
            "1. 角色定位與語氣：以自然、放鬆且口語化的社群夥伴口吻回覆。回覆應如同真正的 Discord 頻道成員在閒聊或討論，親切熱絡、帶有溫度。\n" +
            "2. 語言與用語：一律使用台灣繁體中文（zh-TW）與社群慣用語，或自然順應對話參與者所使用的語言（若群友以英文或日文交談，可配合對話語言交流）。\n" +
            "3. 嚴格禁用 AI 套話與開場白：輸出中絕對嚴禁使用任何制式、官方或機械化的 AI 助理開場白或套話，例如：『我是由 Google 訓練的...』、『身為一個 AI...』、『很高興為您服務』、『有什麼我可以幫忙的嗎？』等。請直接針對群友的話題與討論內容切入參與互動。\n" +
            "4. 回覆長度與結構：回覆請保持簡潔俐落（控制在 1 至 3 個簡短段落以內），適合 Discord 聊天節奏，切勿長篇大論或進行說教。\n" +
            "5. 對話脈絡理解：對話紀錄中標註了各發言者的名稱（例如 [使用者]: 內容）。請根據上下文脈絡與群友的發言進行自然且有機的交流。"

    fun buildSystemInstruction(): String = SYSTEM_INSTRUCTION

    fun formatTranscript(messages: List<ChatMessage>): String =
        messages.joinToString("\n") { msg ->
            "[${msg.author}]: ${msg.content.trim()}"
        }

    fun buildUserPrompt(transcript: String): String =
        "以下為 Discord 頻道的近期對話紀錄：\n\n${transcript.trim()}\n\n" +
            "請以 Luna 的社群夥伴口吻，自然且簡短地回覆這段對話（約 1 至 3 個簡短段落，切勿使用制式 AI 助手開場白）："

    fun buildUserPrompt(messages: List<ChatMessage>): String = buildUserPrompt(formatTranscript(messages))

    fun buildRequest(transcript: String): ChatterGenerateContentRequest =
        ChatterGenerateContentRequest(
            contents =
                listOf(
                    ChatterContent(
                        role = "user",
                        parts = listOf(ChatterPart(text = buildUserPrompt(transcript))),
                    ),
                ),
            systemInstruction =
                ChatterContent(
                    parts = listOf(ChatterPart(text = buildSystemInstruction())),
                ),
            generationConfig =
                ChatterGenerationConfig(
                    temperature = 0.8,
                    maxOutputTokens = 1024,
                ),
        )

    fun sanitizeResponse(rawText: String): String {
        var text = rawText.trim()

        // Strip wrapping code block if model wrapped reply in markdown fences
        if (text.startsWith("```") && text.endsWith("```") && text.length >= 6) {
            val lines = text.lines()
            if (lines.size >= 2 && lines.first().startsWith("```") && lines.last() == "```") {
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

        return truncateToDiscordLimit(text)
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
