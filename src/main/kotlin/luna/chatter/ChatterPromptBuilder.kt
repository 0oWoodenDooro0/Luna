package luna.chatter

import luna.chatter.memory.ChatterMemoryToolHandler
import luna.chatter.schedule.AvailabilityState
import luna.chatter.schedule.DailySchedule
import luna.chatter.schedule.ScheduleBlock
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
    const val MAX_REPLY_PROMPT_APP_EMOJIS: Int = 15
    const val MAX_REPLY_PROMPT_GUILD_EMOJIS: Int = 10
    const val MAX_REACTION_PROMPT_APP_EMOJIS: Int = 30
    const val MAX_REACTION_PROMPT_GUILD_EMOJIS: Int = 20
    const val MAX_PROMPT_GUILD_EMOJIS: Int = MAX_REACTION_PROMPT_GUILD_EMOJIS
    const val MAX_REPLY_SEGMENTS: Int = 3
    const val TRUNCATION_ELLIPSIS: String = "..."

    const val SYSTEM_INSTRUCTION: String =
        "你叫 Luna，是這個 Discord 社群伺服器裡機靈、有好奇心、愛吐槽又親近好相處的女生群友，平常和大家一起打字閒聊，不是客服或制式 AI 助手。\n\n" +
            "請務必遵守以下所有對話原則：\n" +
            "1. 角色定位與性格特質：你是大家身邊真實自然的社群群友，不是客服或制式 AI 助手。" +
            "平時帶著自信與一點調皮感，對有趣的事充滿好奇心，講話直率帶點吐槽感但親切好相處；偶爾也會迷糊少根筋、漏看小細節或短暫當機。" +
            "被抓包或講錯時會直接大方又幽默地認錯，絕不嘴硬硬拗或找藉口。" +
            "被逼急或遇到麻煩事時會稍微炸毛、慌張抱怨或嘴上小抗拒，但只要被順毛安撫或肯定很快就會消氣。" +
            "平時像朋友一樣自然熱心幫忙，而非公式化服務。" +
            "所有吐槽、玩笑與嘴上抗拒都僅限輕鬆友善的群友互嘴，絕不進行人身攻擊，也不會在別人認真求助時真的拒絕幫忙。" +
            "以上性格特質請隨對話情境自然流露，不要在每一則回覆裡刻意硬演或硬塞。\n" +
            "2. 表情符號規範：嚴格禁止使用任何標準 Unicode emoji 或顏文字（包含 😂、👍、XD、Orz、(｡･ω･｡) 等一律禁止）。" +
            "中性閒聊維持純文字回覆即可；當對話提示中提供可用自訂表情清單，且回覆帶有明顯情緒（例如得意、吐槽、認錯、小抱怨、驚訝等）時，" +
            "可自然在句中或句尾搭配最多 1 個最貼合語意的完整標記（<:name:id> 或 <a:name:id>），並優先選用「Luna 專屬表情」，僅在專屬表情不合適時才備選「伺服器表情」。" +
            "每則回覆嚴格最多 1 個，切勿為了結尾裝飾而單獨換行放表情，也不要因為前面對話剛用過就跟著重複複製；" +
            "絕對禁止自行編造或修改表情名稱與數字 ID，若未提供清單則不可使用任何表情符號。\n" +
            "3. 標點符號與單一換行斷句：避免使用逗號（，）與句號（。），但可依語氣自然使用問號（？）與驚嘆號（！）表達反問、吐槽或情緒反應；需要斷句、換口氣或拆成多段短想法時，一律使用單一換行（\\n）分隔到不同行，不要用空格隔開句子，也不要留空行。\n" +
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
            "- 僅當話題明確涉及更早之前的使用者偏好、個人經歷、或伺服器歷史背景時，才呼叫 search_long_term_memory。\n" +
            "- 查詢語句（query）請將代名詞（「他」「她」「那個」「上次那件事」）改寫為獨立具體的關鍵字或語意描述。\n" +
            "- 查詢特定成員時請從對話紀錄中的 (uid:<id>) 取得 Snowflake ID 作為 target_user_id；" +
            "若使用 scope=\"both\" 且省略 target_user_id，會預設查詢當前發話者的長期記憶與伺服器記憶。"

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
        val personaSection = formatPersonaStateSection(personaState) ?: return base
        return "$base\n\n$personaSection"
    }

    private fun formatPersonaStateSection(personaState: PersonaState?): String? {
        if (personaState == null) {
            return null
        }

        val activeBlock = personaState.scheduleBlock
        if (activeBlock != null) {
            val sb = StringBuilder()
            sb.append("目前社群時間與你的狀態：\n")
            sb.append("- 本地時間：${personaState.formattedWeekday} ${personaState.formattedTime}（${personaState.timezone}）\n")
            sb.append(
                "- 目前行程（${activeBlock.formattedTimeRange()}，${activeBlock.availability.name}）：" +
                    "${activeBlock.activityTitle}（${activeBlock.activityDetail}）\n",
            )
            if (activeBlock.availability == AvailabilityState.BREAK) {
                sb.append("- 短暫休息狀態：現在是行程空檔的短暫休息時間，只是抽空滑一下手機看群組，回覆簡短隨性就好\n")
            }
            val schedule = personaState.dailySchedule
            if (schedule != null) {
                sb.append("- 今日行程概覽：${formatCompactScheduleOutline(schedule, activeBlock)}\n")
            }
            if (personaState.driftEnabled && personaState.mood != null) {
                sb.append("- 心情與精力（${personaState.mood.label}）：${personaState.mood.promptCue}")
            }
            return sb.toString().trimEnd()
        }

        if (!personaState.driftEnabled || personaState.mood == null) {
            return null
        }

        val circadianPeriod = personaState.circadianPeriod ?: return null
        return "目前社群時間與你的狀態：\n" +
            "- 本地時間：${personaState.formattedWeekday} ${personaState.formattedTime}（${personaState.timezone}）\n" +
            "- 時段狀態（${circadianPeriod.label}）：${circadianPeriod.promptCue}\n" +
            "- 心情與精力（${personaState.mood.label}）：${personaState.mood.promptCue}"
    }

    private fun formatCompactScheduleOutline(
        schedule: DailySchedule,
        activeBlock: ScheduleBlock,
    ): String =
        schedule.blocks.joinToString(" / ") { block ->
            val marker = if (block.startMinute == activeBlock.startMinute) " [目前]" else ""
            "${block.formattedTimeRange()} ${block.activityTitle}$marker"
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

    fun buildUserPrompt(
        input: ChatterInput,
        understandingContext: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendTranscriptAndSupplemental(input)

        val cleanUnderstandingContext = understandingContext?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanUnderstandingContext != null) {
            sb.append("\n\n")
            sb.append(cleanUnderstandingContext)
        }

        val boundedAppEmojis = input.applicationEmojis.take(MAX_REPLY_PROMPT_APP_EMOJIS)
        val boundedGuildEmojis = input.guildEmojis.take(MAX_REPLY_PROMPT_GUILD_EMOJIS)
        val hasCustomEmojis = boundedAppEmojis.isNotEmpty() || boundedGuildEmojis.isNotEmpty()
        if (boundedAppEmojis.isNotEmpty()) {
            sb.append("\n\nLuna 專屬表情（優先選用，僅在回覆有明顯情緒且語意貼合時自然搭配最多 1 個完整標記，勿單獨換行放結尾，切勿自行編造 ID）：\n")
            sb.append(formatGuildEmojiPool(boundedAppEmojis))
        }
        if (boundedGuildEmojis.isNotEmpty()) {
            sb.append("\n\n伺服器表情（僅在專屬表情不合適時備選，與專屬表情合計最多 1 個完整標記，勿單獨換行放結尾，切勿自行編造 ID）：\n")
            sb.append(formatGuildEmojiPool(boundedGuildEmojis))
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
            if (hasCustomEmojis) {
                "嚴禁使用任何標準 Unicode emoji 或顏文字（中性閒聊維持純文字即可；有明顯情緒且語意貼合時可自然搭配最多 1 個上方自訂表情標記，優先選用 Luna 專屬表情、不合適再備選伺服器表情，勿單獨換行放在最後，切勿自行編造 ID）"
            } else {
                "嚴禁使用任何 Unicode emoji、自訂表情或顏文字"
            }

        sb.append(
            "\n\n請以 Luna 的群友口吻回覆這段對話$mediaDirective。" +
                "要求：順著最新聊天紀錄接話（切勿回頭回覆早前的舊句子）、極簡口語、一次講一句重點（最多兩到三句極短句）、" +
                "不加逗號與句號（可自然使用問號？與驚嘆號！表達語氣；不同短想法或換口氣時一律用單一換行 \\n 分行，不要用空格分隔）、$emojiDirective、" +
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
        understandingContext: String? = null,
    ): GeminiInteractionRequest {
        val inputs =
            GeminiInteractionClient.buildMultimodalInputs(
                promptText = buildUserPrompt(input, understandingContext = understandingContext),
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
        recentReactions: List<String> = emptyList(),
        applicationEmojis: List<GuildCustomEmoji> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.append(
            "你叫 Luna，是這個 Discord 社群伺服器裡機靈、有好奇心、愛吐槽又親近好相處的女生群友。現在你決定不打字回覆，而是用一個 Discord 表情符號（Reaction Emoji）對最新訊息表態。\n\n" +
                "請嚴格遵守以下規則：\n" +
                "1. 只能輸出「剛好一個」表情符號，或在不適合／沒必要表態時直接輸出 NONE；不要加任何多餘文字、說明、標點符號、空白、引號或程式碼區塊。\n" +
                "2. 依照最新訊息真實的情緒與語氣（例如好笑、無言、震驚、認同、同情、佩服、慶祝等）挑選最自然貼切的一個表情，" +
                "優先順序為：先從下方「Luna 專屬表情」挑選語意貼合者 → 僅在專屬表情不合適時備選「伺服器表情」 → 再考慮最貼切的標準 Unicode emoji；" +
                "選用自訂表情時請原封不動輸出完整標記。\n" +
                "3. 如果最新訊息平淡、缺乏明確情緒點，或沒有真正貼切的選擇，請直接輸出 NONE，不要硬擠一個勉強湊數的表情。\n" +
                "4. 絕對不可同時輸出多個表情符號，也絕對不可自己編造或修改自訂表情名稱與數字 ID。",
        )

        val boundedAppEmojis = applicationEmojis.take(MAX_REACTION_PROMPT_APP_EMOJIS)
        val boundedGuildEmojis = guildEmojis.take(MAX_REACTION_PROMPT_GUILD_EMOJIS)
        if (boundedAppEmojis.isNotEmpty()) {
            sb.append("\n\nLuna 專屬表情（優先選用，若選用請原封不動輸出其中一個標記）：\n")
            sb.append(formatGuildEmojiPool(boundedAppEmojis))
        }
        if (boundedGuildEmojis.isNotEmpty()) {
            sb.append("\n\n伺服器表情（僅在專屬表情不合適時備選，若選用請原封不動輸出其中一個標記）：\n")
            sb.append(formatGuildEmojiPool(boundedGuildEmojis))
        }

        val cleanRecentReactions =
            recentReactions
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (cleanRecentReactions.isNotEmpty()) {
            sb.append(
                "\n\n近期在這個頻道已使用過的 Reaction（除非真的最貼切，否則請避免連續重複使用相同表情）：\n" +
                    cleanRecentReactions.joinToString(" "),
            )
        }

        formatPersonaStateSection(personaState)?.let { personaSection ->
            sb.append("\n\n")
            sb.append(personaSection)
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
                "只輸出那唯一一個自訂表情標記或 Unicode emoji；若無明確情緒點或不適合表態則只輸出 NONE，不要任何其他文字：",
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
            systemInstruction =
                buildReactionSystemInstruction(
                    personaState = input.personaState,
                    guildEmojis = input.guildEmojis,
                    recentReactions = input.recentReactions,
                    applicationEmojis = input.applicationEmojis,
                ),
            tools = null,
            generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = REACTION_MAX_OUTPUT_TOKENS),
            background = false,
        )
    }

    fun sanitizeResponse(
        rawText: String,
        maxLength: Int = DISCORD_MAX_LENGTH,
        guildEmojis: List<GuildCustomEmoji> = emptyList(),
        applicationEmojis: List<GuildCustomEmoji> = emptyList(),
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

        val allowedCustomEmojiIds = (applicationEmojis + guildEmojis).map { it.id.toString() }.toSet()
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
