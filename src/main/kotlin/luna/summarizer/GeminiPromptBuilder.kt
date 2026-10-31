package luna.summarizer

/**
 * Builds system instructions, prompts, and REST request payloads for Google Gemini API.
 */
object GeminiPromptBuilder {
    const val SYSTEM_INSTRUCTION: String =
        "你是一個專業的內容摘要助理。請根據使用者提供的內容進行結構化摘要。\n" +
            "請務必嚴格遵守以下所有規則：\n" +
            "1. 語言與用語：一律使用台灣繁體中文（zh-TW）與台灣慣用語彙，絕對不可使用簡體中文或中國大陸用語。\n" +
            "2. 嚴格禁用 Emoji：輸出中絕對不可包含任何 Emoji、表情符號或繪文字圖示。\n" +
            "3. 輸出結構：必須依照下列結構分段輸出，標題格式請保持一致：\n" +
            "TL;DR:\n" +
            "（1 至 2 句話扼要總結最核心重點）\n\n" +
            "重點摘要:\n" +
            "• （3 至 5 點列點摘要，說明核心要點與關鍵資訊）\n\n" +
            "社群討論風向:\n" +
            "（僅在使用者提供的內容中包含留言、評論或社群討論時才輸出此區塊；若無留言或討論內容，請完全省略此區塊。若有，請以 2 至 3 點客觀總結網友主要看法或爭議焦點）\n\n" +
            "4. 長度限制：內容必須精簡扼要，整體篇幅不可超過 Discord 訊息上限（請控制在 1500 字元以內）。"

    fun buildUserPrompt(input: SummaryInput): String {
        val sb = StringBuilder()
        if (!input.title.isNullOrBlank()) {
            sb.append("標題：").append(input.title.trim()).append("\n")
        }
        if (!input.url.isNullOrBlank()) {
            sb.append("來源網址：").append(input.url.trim()).append("\n")
        }
        sb.append("內文：\n").append(input.content.trim())
        if (input.comments.isNotEmpty()) {
            sb.append("\n\n社群留言與討論：\n")
            input.comments.forEachIndexed { index, comment ->
                sb.append("${index + 1}. ").append(comment.trim()).append("\n")
            }
        }
        return sb.toString().trim()
    }

    fun buildRequest(input: SummaryInput): GeminiGenerateContentRequest =
        GeminiGenerateContentRequest(
            systemInstruction =
                GeminiContent(
                    parts = listOf(GeminiPart(text = SYSTEM_INSTRUCTION)),
                ),
            contents =
                listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(text = buildUserPrompt(input))),
                    ),
                ),
            generationConfig =
                GeminiGenerationConfig(
                    temperature = 0.2,
                    maxOutputTokens = 1024,
                ),
        )
}
