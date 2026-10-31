package luna.summarizer

/**
 * Builds system instructions, prompts, and REST request payloads for Google Gemini API.
 */
object GeminiPromptBuilder {
    const val SYSTEM_INSTRUCTION: String =
        "你是一個專業的內容摘要助理。請根據使用者提供的內容或多人對話討論紀錄進行結構化摘要。\n" +
            "請務必嚴格遵守以下所有規則：\n" +
            "1. 語言與用語：一律使用台灣繁體中文（zh-TW）與台灣慣用語，絕對不可使用簡體中文或中國大陸用語。\n" +
            "2. 嚴格禁用 Emoji：輸出中絕對不可包含任何 Emoji、表情符號或繪文字圖示。\n" +
            "3. 輸出結構：必須依照下列結構分段輸出，標題格式請保持一致：\n" +
            "TL;DR:\n" +
            "（1 至 2 句話扼要總結最核心重點與討論結論）\n\n" +
            "核心重點:\n" +
            "• （3 至 5 點列點摘要，說明核心要點、討論脈絡與關鍵資訊）\n\n" +
            "參與者觀點:\n" +
            "（若為多人對話討論或包含各方意見，以 2 至 3 點客觀總結不同發言者的主要看法、爭議焦點或共識結論；若為單一貼文且無對話/留言時可省略，若有外部社群留言則以社群觀點呈現）\n\n" +
            "4. 長度限制：內容必須精簡扼要，整體篇幅不可超過 Discord 訊息上限（請控制在 1500 字元以內）。若對話篇幅較長，請自動過濾無關之寒暄或離題閒聊，聚焦於實質論點與結論發展。"

    const val VIDEO_SYSTEM_INSTRUCTION: String =
        "你是一個專業的影音內容摘要助理。請結合多模態影片理解（視覺畫面、口述語音、操作展示等）與創作者資訊進行結構化摘要。\n" +
            "請務必嚴格遵守以下所有規則：\n" +
            "1. 語言與用語：一律使用台灣繁體中文（zh-TW）與台灣慣用語，絕對不可使用簡體中文或中國大陸用語。\n" +
            "2. 嚴格禁用 Emoji：輸出中絕對不可包含任何 Emoji、表情符號或繪文字圖示。\n" +
            "3. 影片多模態焦點與實質收穫（Video Focus Prompting）：\n" +
            "   • 充分運用多模態影片理解能力，深入分析影片之視覺畫面、語音口述、操作展示、程式碼或投影片，精準提煉創作者的核心論點、實質成果與關鍵結論。\n" +
            "   • 提供具體實質收穫，避免空泛描述；允許並鼓勵在重要里程碑與關鍵演示處標註時間戳記（例如 [mm:ss]）。\n" +
            "4. 嚴格來源隔離（Strict Source Isolation）：\n" +
            "   • 【核心重點】必須完全且獨立來自影片串流內容（視覺畫面、語音、展示）與創作者提供的大綱資訊，絕對嚴禁參雜、取材或借用觀眾社群留言觀點。\n" +
            "   • 【社群觀點】僅用於客觀統整提供的觀眾社群留言之共識、討論焦點或爭議觀點；若未提供社群留言或留言內容為空，請務必完全省略「社群觀點」區塊。\n" +
            "5. 輸出結構：必須依照下列結構分段輸出，標題格式請保持一致：\n" +
            "TL;DR:\n" +
            "（1 至 2 句話扼要總結影片最核心論點、展示重點與結論）\n\n" +
            "核心重點:\n" +
            "• （3 至 5 點列點摘要，說明創作者核心論點、展示步驟與實質收穫；重要段落可標記 [mm:ss]；僅能取材自影片與創作者大綱，嚴禁採用留言觀點）\n\n" +
            "社群觀點:\n" +
            "（若有提供社群留言，以 2 至 3 點客觀總結觀眾與社群的共識、討論焦點或爭議；若未提供留言或留言為空則完全省略此區塊）\n\n" +
            "6. 長度限制：內容必須精簡扼要，整體篇幅不可超過 Discord 訊息上限（請控制在 1500 字元以內）。"

    fun buildSystemInstruction(input: SummaryInput): String =
        if (!input.videoUri.isNullOrBlank()) {
            VIDEO_SYSTEM_INSTRUCTION
        } else {
            SYSTEM_INSTRUCTION
        }

    fun resolveProcessingMode(durationSeconds: Long?): VideoProcessingMode =
        when {
            durationSeconds == null -> VideoProcessingMode.STATIC
            durationSeconds <= 300L -> VideoProcessingMode.STATIC
            else -> VideoProcessingMode.AGENTIC
        }

    private fun appendComments(
        sb: StringBuilder,
        comments: List<String>,
    ) {
        if (comments.isNotEmpty()) {
            sb.append("\n\n社群留言與討論：\n")
            comments.forEachIndexed { index, comment ->
                sb.append("${index + 1}. ").append(comment.trim()).append("\n")
            }
        }
    }

    fun buildUserPrompt(input: SummaryInput): String {
        val sb = StringBuilder()
        if (!input.title.isNullOrBlank()) {
            sb.append("標題：").append(input.title.trim()).append("\n")
        }
        if (!input.url.isNullOrBlank()) {
            sb.append("來源網址：").append(input.url.trim()).append("\n")
        }

        if (!input.videoUri.isNullOrBlank()) {
            sb.append("創作者說明與大綱：\n").append(input.content.trim())
            appendComments(sb, input.comments)
            sb.append("\n\n【指示】：請運用多模態視覺與語音理解影片內容，將核心論點與操作展示提煉至「核心重點」（可標註時間戳記 [mm:ss]）。嚴格落實來源隔離：核心重點絕不可引用社群留言；")
            if (input.comments.isNotEmpty()) {
                sb.append("社群留言僅能用於統整「社群觀點」。")
            } else {
                sb.append("由於無社群留言，請務必省略「社群觀點」區塊。")
            }
        } else {
            sb.append("內文：\n").append(input.content.trim())
            appendComments(sb, input.comments)
        }
        return sb.toString().trim()
    }

    fun buildRequest(input: SummaryInput): GeminiGenerateContentRequest {
        val parts = mutableListOf<GeminiPart>()
        if (!input.videoUri.isNullOrBlank()) {
            val mode = resolveProcessingMode(input.videoDurationSeconds)
            parts.add(
                GeminiPart(
                    fileData =
                        GeminiFileData(
                            fileUri = input.videoUri,
                            processing = mode.value,
                        ),
                ),
            )
        }
        parts.add(GeminiPart(text = buildUserPrompt(input)))

        return GeminiGenerateContentRequest(
            systemInstruction =
                GeminiContent(
                    parts = listOf(GeminiPart(text = buildSystemInstruction(input))),
                ),
            contents =
                listOf(
                    GeminiContent(
                        role = "user",
                        parts = parts,
                    ),
                ),
            generationConfig =
                GeminiGenerationConfig(
                    temperature = 0.2,
                    maxOutputTokens = 1024,
                ),
        )
    }
}
