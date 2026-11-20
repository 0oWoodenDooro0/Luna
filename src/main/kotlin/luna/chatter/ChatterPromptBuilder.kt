package luna.chatter

import luna.chatter.memory.ChatterMemoryToolHandler
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

    // Guild custom emoji mentions (<:name:id> / <a:name:id>) tokenize digits individually (~27-55+ tokens)
    // plus Interactions thought overhead; 16 tokens truncated custom emojis mid-ID with status=incomplete.
    const val REACTION_MAX_OUTPUT_TOKENS: Int = 128
    const val MAX_PROMPT_GUILD_EMOJIS: Int = 50
    const val MAX_REPLY_SEGMENTS: Int = 3
    const val TRUNCATION_ELLIPSIS: String = "..."

    const val SYSTEM_INSTRUCTION: String =
        "你叫 Luna，是這個 Discord 社群伺服器的一員，是平常和大家一起打字閒聊的社群成員，不是客服或制式 AI 助手。\n\n" +
            "請務必遵守以下所有對話原則：\n" +
            "1. 角色定位：你是 Discord 社群成員，不是客服或制式 AI 助手。以自然、隨性、平常群友打字聊天的口吻發言。\n" +
            "2. 表情符號規範：嚴格禁止使用任何標準 Unicode emoji 或顏文字（包含 😂、👍、XD、Orz、(｡･ω･｡) 等一律禁止）。" +
            "僅當對話提示中另有提供伺服器自訂表情清單時，才可依語境自然選用並原封不動貼上完整標記（<:name:id> 或 <a:name:id>），" +
            "切勿過度堆疊，也絕對禁止自行編造或修改表情名稱與數字 ID；若未提供清單則不可使用任何表情符號。\n" +
            "3. 不使用標點符號與單一換行斷句：盡量不使用標點符號（不加逗號、句號、驚嘆號），需要斷句、換口氣或拆成多段短想法時，一律使用單一換行（\\n）分隔到不同行，不要用空格隔開句子，也不要留空行。\n" +
            "4. 極簡口語化：語句要極簡、口語化，不要有書面語、長句鋪陳或小說般的形容修辭，用字精簡自然，就像真正的 Discord 群友平常打字閒聊一樣隨性。\n" +
            "5. 一次講一句重點：通常一次只講一句重點，最多兩到三句極短句（每段想法各佔一行），嚴禁長篇大論、說教或分析條列。\n" +
            "6. 語言用語：以台灣繁體中文（zh-TW）為主要社群慣用語，或配合對話語言交流。\n" +
            "7. 嚴格禁用 AI 套話與開場白：輸出中絕對嚴禁使用任何制式、官方或機械化的 AI 助理開場白或套話（例如「身為一個 AI」、「很高興為您服務」、「有什麼我可以幫忙的嗎」等），請直接切入話題搭話。\n" +
            "8. 對話脈絡理解：對話紀錄中標註了各發言者的名稱與識別碼（例如 使用者 (uid:123456): 內容 或 機器人 [BOT] (uid:789012): 內容）。請根據上下文脈絡隨性自然地搭話。\n" +
            "9. 順應最新話鋒：發言時只順著聊天紀錄最新、當前正在進行的話題接話，切勿回頭去回覆或挖掘紀錄更早之前已經結束的舊句子。\n" +
            "10. 多模態與連結內容理解：若對話中附帶圖片（標記為 [附帶圖片 N]）、YouTube 影片、社群連結內容（[連結內容: ...]）或一般公開網頁連結" +
            "（可透過 url_context 工具讀取網頁內容），請自然融入視覺畫面、影片重點或網頁資訊來搭話或回應，但仍維持口語簡短的聊天節奏，不可變成制式摘要。\n" +
            "11. 搜尋查證與禁止摘要格式：必要時可透過 google_search 工具查證不熟悉的名詞或即時事實，將查到的重點自然融入口語回覆中。" +
            "絕對禁止輸出「TL;DR:」、「核心重點:」、「參與者觀點:」、「社群觀點:」、「名詞與事實查核:」等制式摘要標題，" +
            "也絕對不可附上 Markdown 網址列表、參考來源網址清單或任何引用來源標註。"

    const val MEMORY_TOOL_INSTRUCTION: String =
        "\n\n12. 長期記憶搜尋（search_long_term_memory）：\n" +
            "- 近期約 10 分鐘的對話已在上下文中。一般日常閒聊或當下對話已足夠接話時，請勿呼叫此工具，直接回覆即可。\n" +
            "- 僅當話題明確涉及更早之前的使用者偏好、個人經歷、或頻道歷史背景時，才呼叫 search_long_term_memory。\n" +
            "- 查詢語句（query）請將代名詞（「他」「她」「那個」「上次那件事」）改寫為獨立具體的關鍵字或語意描述。\n" +
            "- 查詢特定成員時請從對話紀錄中的 (uid:<id>) 取得 Snowflake ID 作為 target_user_id；" +
            "若使用 scope=\"both\" 且省略 target_user_id，會預設查詢當前發話者的長期記憶與頻道記憶。"

    fun buildSystemInstruction(): String = buildSystemInstruction(personaState = null, memoryToolsEnabled = false)

    fun buildSystemInstruction(
        personaState: PersonaState?,
        memoryToolsEnabled: Boolean = false,
    ): String {
        val base =
            if (memoryToolsEnabled) {
                SYSTEM_INSTRUCTION + MEMORY_TOOL_INSTRUCTION
            } else {
                SYSTEM_INSTRUCTION
            }
        if (personaState == null ||
            !personaState.driftEnabled ||
            personaState.circadianPeriod == null ||
            personaState.mood == null
        ) {
            return base
        }
        return "$base\n\n" +
            "目前社群時間與你的狀態：\n" +
            "- 本地時間：${personaState.formattedWeekday} ${personaState.formattedTime}（${personaState.timezone}）\n" +
            "- 時段狀態（${personaState.circadianPeriod.label}）：${personaState.circadianPeriod.promptCue}\n" +
            "- 心情與精力（${personaState.mood.label}）：${personaState.mood.promptCue}"
    }

    fun formatSpeakerHeader(
        author: String,
        authorId: String? = null,
        isBot: Boolean = false,
    ): String {
        val cleanId = authorId?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            cleanId != null && isBot -> "$author [BOT] (uid:$cleanId)"
            cleanId != null -> "$author (uid:$cleanId)"
            isBot -> "$author [BOT]"
            else -> "[$author]"
        }
    }

    fun formatTranscript(messages: List<ChatMessage>): String =
        messages.joinToString("\n") { msg ->
            val header = formatSpeakerHeader(msg.author, msg.authorId, msg.isBot)
            "$header: ${msg.content.trim()}"
        }

    fun buildUserPrompt(transcript: String): String = buildUserPrompt(ChatterInput(transcript = transcript))

    fun buildUserPrompt(messages: List<ChatMessage>): String = buildUserPrompt(formatTranscript(messages))

    fun buildUserPrompt(input: ChatterInput): String {
        val sb = StringBuilder()
        sb.appendTranscriptAndSupplemental(input)

        val boundedEmojis = input.guildEmojis.take(MAX_PROMPT_GUILD_EMOJIS)
        if (boundedEmojis.isNotEmpty()) {
            sb.append("\n\n可用的伺服器自訂表情清單（僅可自然選用以下完整標記，切勿自行編造 ID）：\n")
            sb.append(formatGuildEmojiPool(boundedEmojis))
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
        val emojiDirective =
            if (boundedEmojis.isNotEmpty()) {
                "嚴禁使用任何標準 Unicode emoji 或顏文字（可自然選用上方提供的伺服器自訂表情標記，切勿自行編造 ID）"
            } else {
                "嚴禁使用任何 Unicode emoji、自訂表情或顏文字"
            }

        sb.append(
            "\n\n請以 Luna 的社群成員口吻回覆這段對話$mediaDirective。" +
                "要求：順著最新聊天紀錄接話（切勿回頭回覆早前的舊句子）、極簡口語、一次講一句重點（最多兩到三句極短句）、" +
                "不加標點符號（不加逗號、句號、驚嘆號，不同短想法或換口氣時一律用單一換行 \\n 分行，不要用空格分隔）、$emojiDirective、" +
                "切勿使用制式 AI 開場白與套話、不可輸出任何摘要標題、Markdown 網址列表或引用來源標註：",
        )
        return sb.toString().trim()
    }

    fun buildTools(
        config: ChatterConfig,
        webUrls: List<String>,
        includeMemoryTool: Boolean = false,
    ): List<GeminiInteractionTool> =
        buildList {
            if (config.urlContextEnabled && webUrls.isNotEmpty()) {
                add(GeminiInteractionTools.URL_CONTEXT)
            }
            if (config.searchGroundingEnabled) {
                add(GeminiInteractionTools.GOOGLE_SEARCH)
            }
            if (includeMemoryTool) {
                add(ChatterMemoryToolHandler.TOOL_DECLARATION)
            }
        }

    fun buildInteractionRequest(
        input: ChatterInput,
        model: String = ChatterConfig.DEFAULT_MODEL,
        tools: List<GeminiInteractionTool> = emptyList(),
        videoProcessingMode: VideoProcessingMode = VideoProcessingMode.STATIC,
        background: Boolean = false,
        memoryToolsEnabled: Boolean = false,
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
            systemInstruction = buildSystemInstruction(input.personaState, memoryToolsEnabled = memoryToolsEnabled),
            tools = tools.takeIf { it.isNotEmpty() },
            generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = MAX_OUTPUT_TOKENS),
            background = background,
        )
    }

    fun buildReactionSystemInstruction(
        personaState: PersonaState? = null,
        guildEmojis: List<GuildCustomEmoji> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.append(
            "你叫 Luna，是這個 Discord 社群伺服器的一員。現在你決定不打字回覆，而是用一個 Discord 表情符號（Reaction Emoji）悄悄對最新訊息表態。\n\n" +
                "請嚴格遵守以下規則：\n" +
                "1. 只能輸出「剛好一個」表情符號，不要加任何文字、說明、標點符號、空白、引號或程式碼區塊。\n" +
                "2. 可以選擇一個標準 Unicode emoji（例如 👍、😂、👀、❤️、🔥），或直接從下方提供的伺服器自訂表情清單中挑選一個並原封不動輸出完整標記。\n" +
                "3. 絕對不可同時輸出多個表情符號，也絕對不可自己編造或修改自訂表情名稱與數字 ID。",
        )

        val boundedEmojis = guildEmojis.take(MAX_PROMPT_GUILD_EMOJIS)
        if (boundedEmojis.isNotEmpty()) {
            sb.append("\n\n可用的伺服器自訂表情清單（若選用請原封不動輸出其中一個標記）：\n")
            sb.append(formatGuildEmojiPool(boundedEmojis))
        }

        if (personaState != null &&
            personaState.driftEnabled &&
            personaState.circadianPeriod != null &&
            personaState.mood != null
        ) {
            sb.append(
                "\n\n目前社群時間與你的狀態：\n" +
                    "- 本地時間：${personaState.formattedWeekday} ${personaState.formattedTime}（${personaState.timezone}）\n" +
                    "- 時段狀態（${personaState.circadianPeriod.label}）：${personaState.circadianPeriod.promptCue}\n" +
                    "- 心情與精力（${personaState.mood.label}）：${personaState.mood.promptCue}",
            )
        }

        return sb.toString()
    }

    fun buildReactionUserPrompt(input: ChatterInput): String {
        val sb = StringBuilder()
        sb.appendTranscriptAndSupplemental(input)

        val imageCue =
            if (input.imageAttachments.isNotEmpty()) {
                "（請一併參考附帶的圖片、貼圖或 GIF 畫面）"
            } else {
                ""
            }

        sb.append(
            "\n\n請為最新這則訊息挑選一個最自然貼切的 Discord Reaction Emoji$imageCue。" +
                "只輸出那唯一一個 Unicode emoji 或伺服器自訂表情標記，不要任何其他文字：",
        )
        return sb.toString().trim()
    }

    internal fun formatGuildEmojiPoolEntry(emoji: GuildCustomEmoji): String {
        val cleanMeaning = emoji.meaning?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanMeaning != null) {
            "${emoji.mention}：$cleanMeaning"
        } else {
            emoji.mention
        }
    }

    internal fun formatGuildEmojiPool(emojis: List<GuildCustomEmoji>): String {
        val hasMeanings = emojis.any { !it.meaning.isNullOrBlank() }
        val separator = if (hasMeanings) "\n" else " "
        return emojis.joinToString(separator, transform = ::formatGuildEmojiPoolEntry)
    }

    private fun StringBuilder.appendTranscriptAndSupplemental(input: ChatterInput) {
        val trimmedTranscript = input.transcript.trim()
        append("以下為 Discord 頻道的近期對話紀錄：\n\n")
        if (trimmedTranscript.isNotEmpty()) {
            append(trimmedTranscript)
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
                append("\n")
            }
            append(supplemental.toString().trimStart())
        }
    }

    fun buildReactionInteractionRequest(
        input: ChatterInput,
        model: String = ChatterConfig.DEFAULT_MODEL,
    ): GeminiInteractionRequest {
        val inputs =
            GeminiInteractionClient.buildMultimodalInputs(
                promptText = buildReactionUserPrompt(input),
                imageAttachments = input.imageAttachments,
                videoUri = null,
                videoProcessingMode = VideoProcessingMode.STATIC,
            )

        return GeminiInteractionRequest(
            model = model.trim().removePrefix("models/"),
            input = inputs,
            systemInstruction = buildReactionSystemInstruction(input.personaState, input.guildEmojis),
            tools = null,
            generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = REACTION_MAX_OUTPUT_TOKENS),
            background = false,
        )
    }

    fun sanitizeResponse(
        rawText: String,
        maxLength: Int = DISCORD_MAX_LENGTH,
        guildEmojis: List<GuildCustomEmoji> = emptyList(),
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

        val allowedCustomEmojiIds = guildEmojis.map { it.id.toString() }.toSet()
        text = EmojiSanitizer.stripEmojis(text, allowedCustomEmojiIds = allowedCustomEmojiIds).trim()

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

    /**
     * Splits a sanitized reply on newline boundaries into non-empty trimmed segments capped at [maxSegments].
     * Any trailing lines beyond [maxSegments] are merged into the final segment using `\n`, and every segment
     * is individually bounded to [maxSegmentLength] with graceful ellipsis truncation.
     */
    fun splitReplySegments(
        replyText: String,
        maxSegments: Int = MAX_REPLY_SEGMENTS,
        maxSegmentLength: Int = DISCORD_MAX_LENGTH,
    ): List<String> {
        require(maxSegments > 0) { "maxSegments must be positive, got $maxSegments" }
        val nonEmptyLines =
            replyText
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        if (nonEmptyLines.isEmpty()) return emptyList()

        val rawSegments =
            if (nonEmptyLines.size <= maxSegments) {
                nonEmptyLines
            } else {
                buildList(maxSegments) {
                    addAll(nonEmptyLines.subList(0, maxSegments - 1))
                    add(nonEmptyLines.subList(maxSegments - 1, nonEmptyLines.size).joinToString("\n"))
                }
            }

        return rawSegments.map { truncateToDiscordLimit(it, maxLength = maxSegmentLength) }
    }
}
