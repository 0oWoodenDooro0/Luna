package luna.core.ai

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import luna.core.SummaryRegistry
import luna.core.WebhookRegistry
import org.slf4j.LoggerFactory

/**
 * Policy-driven shared Dialogue Context Engine in `luna.core.ai` providing unified Discord
 * noise evaluation, external Reply Reference Chaining, embed/GIF preview formatting,
 * primary social/YouTube link enrichment, and image marker formatting for both
 * `Chatter Pipeline` and `Summary Pipeline`.
 */
open class DiscordDialogueContextEngine(
    val webhookRegistry: WebhookRegistry = LunaWebhookRegistry,
    val summaryRegistry: SummaryRegistry = SummaryMessageRegistry,
    val mentionResolver: DiscordMentionResolver = DiscordMentionResolver(),
) {
    private val logger = LoggerFactory.getLogger(DiscordDialogueContextEngine::class.java)

    /**
     * Synchronously evaluates whether [message] is dialogue noise under [policy].
     *
     * @param isVerifiedWebhook Optional pre-resolved webhook verification result (e.g. from a
     *   suspending REST fallback cache). When `null`, checks [webhookRegistry] synchronously.
     */
    fun evaluateNoise(
        message: Message,
        policy: DialogueContextPolicy,
        kordSelfId: Snowflake? = null,
        isVerifiedWebhook: Boolean? = null,
    ): DialogueNoiseEvaluation {
        val speaker = resolveSpeaker(message, kordSelfId)
        val webhookId = runCatching { message.webhookId }.getOrNull()
        val isLunaWebhook =
            if (webhookId != null) {
                isVerifiedWebhook ?: webhookRegistry.contains(webhookId)
            } else {
                false
            }

        if (webhookId != null && !isLunaWebhook) {
            return DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.UNVERIFIED_WEBHOOK, speaker = speaker)
        }

        if (speaker.isLunaBot) {
            if (isBotSummaryMessage(message, isVerifiedWebhook = isLunaWebhook)) {
                return DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.BOT_SUMMARY, speaker = speaker)
            }
        } else if (!isLunaWebhook) {
            val author = runCatching { message.author }.getOrNull()
            if (author == null) {
                return DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.MISSING_AUTHOR, speaker = speaker)
            }
            if (speaker.isThirdPartyBot && !policy.allowThirdPartyBots) {
                return DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.THIRD_PARTY_BOT, speaker = speaker)
            }
        }

        val type = runCatching { message.type }.getOrNull() ?: MessageType.Default
        if (isCommand(message.content, type, policy.commandPrefixRule)) {
            return DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.COMMAND, speaker = speaker)
        }

        if (isSystemMessageType(type)) {
            return DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.SYSTEM_MESSAGE, speaker = speaker)
        }

        val hasAttachments = runCatching { message.attachments }.getOrNull()?.isNotEmpty() == true
        if (hasAttachments) {
            val placeholder = if (message.content.isBlank()) ATTACHMENT_PLACEHOLDER else null
            return DialogueNoiseEvaluation(isNoise = false, blankPlaceholder = placeholder, speaker = speaker)
        }

        val hasStickers = runCatching { message.stickers }.getOrNull()?.isNotEmpty() == true
        val blankOrCustomEmoji = isBlankOrCustomEmojiOnly(message.content)

        if (hasStickers && blankOrCustomEmoji) {
            return if (!policy.allowStickerOnlyMessages) {
                DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.STICKER_ONLY, speaker = speaker)
            } else {
                val placeholder =
                    if (message.content.isBlank()) {
                        DiscordMediaEmbedExtractor.formatStickerAnnotations(message).ifEmpty { null }
                    } else {
                        null
                    }
                DialogueNoiseEvaluation(isNoise = false, blankPlaceholder = placeholder, speaker = speaker)
            }
        }

        if (message.content.isBlank()) {
            val embeds = runCatching { message.embeds }.getOrDefault(emptyList())
            if (embeds.isNotEmpty() && policy.allowEmbedOnlyMessages) {
                return DialogueNoiseEvaluation(
                    isNoise = false,
                    blankPlaceholder = resolveEmbedPlaceholder(embeds),
                    speaker = speaker,
                )
            }
            return DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.EMPTY_OR_EMOJI_ONLY, speaker = speaker)
        }

        if (blankOrCustomEmoji && !policy.allowCustomEmojiOnlyMessages) {
            return DialogueNoiseEvaluation(isNoise = true, reason = DialogueNoiseReason.EMPTY_OR_EMOJI_ONLY, speaker = speaker)
        }

        return DialogueNoiseEvaluation(isNoise = false, speaker = speaker)
    }

    fun isNoiseMessage(
        message: Message,
        policy: DialogueContextPolicy,
        kordSelfId: Snowflake? = null,
        isVerifiedWebhook: Boolean? = null,
    ): Boolean = evaluateNoise(message, policy, kordSelfId, isVerifiedWebhook).isNoise

    fun isBotSummaryMessage(
        message: Message,
        isVerifiedWebhook: Boolean? = null,
    ): Boolean {
        val webhookId = runCatching { message.webhookId }.getOrNull()
        val isLunaWebhook = webhookId != null && (isVerifiedWebhook ?: webhookRegistry.contains(webhookId))
        if (isLunaWebhook) {
            return false
        }
        if (summaryRegistry.contains(message.id)) {
            return true
        }
        return runCatching { message.author?.isBot }.getOrNull() == true &&
            SummaryRegistry.isSummaryContent(message.content)
    }

    fun resolveSpeaker(
        message: Message,
        kordSelfId: Snowflake? = null,
        resolvedDisplayName: String? = null,
    ): DialogueSpeaker {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        val authorId =
            runCatching { message.author?.id }.getOrNull()
                ?: runCatching { message.data.author.id }.getOrNull()
        val isLunaBot = resolvedSelfId != null && authorId == resolvedSelfId
        val name = if (isLunaBot) "Luna" else (resolvedDisplayName ?: extractSpeakerName(message))
        val isThirdPartyBot = !isLunaBot && (runCatching { message.author?.isBot }.getOrNull() == true)
        return DialogueSpeaker(
            name = name,
            authorId = authorId?.toString(),
            isLunaBot = isLunaBot,
            isThirdPartyBot = isThirdPartyBot,
        )
    }

    fun extractSpeakerName(message: Message): String = DiscordMentionResolver.extractSpeakerName(message)

    fun selectImages(
        messages: List<Message>,
        policy: DialogueContextPolicy,
        maxImages: Int,
        maxImageBytes: Int = ChatImageAttachmentExtractor.DEFAULT_MAX_IMAGE_BYTES,
        prioritizedMessageId: Snowflake? = null,
    ): Map<Snowflake, List<ChatImageAttachment>> =
        ChatImageAttachmentExtractor.selectChronologicalImages(
            messages = messages,
            maxImages = maxImages.coerceAtLeast(0),
            maxImageBytes = maxImageBytes,
            prioritizedMessageId = prioritizedMessageId,
            includeStickersAndGifEmbeds = policy.includeStickersAndGifEmbeds,
        )

    /**
     * Resolves out-of-slice replied-to messages via [referenceFetcher], enforcing
     * [DialogueContextPolicy.maxExternalReferences] and emitting observable logs on
     * fetch failures and cap hits.
     */
    suspend fun resolveExternalReplyReferences(
        messages: List<Message>,
        policy: DialogueContextPolicy,
        referenceFetcher: suspend (Message) -> Message?,
    ): Map<Snowflake, Message> {
        val maxExternalReferences = policy.maxExternalReferences
        if (maxExternalReferences <= 0 || messages.isEmpty()) {
            return emptyMap()
        }

        val windowMessageIds = messages.map { it.id }.toSet()
        val result = mutableMapOf<Snowflake, Message>()
        val resolvedExternalIds = mutableSetOf<Snowflake>()

        for (msg in messages) {
            val ref =
                runCatching { referenceFetcher(msg) }
                    .onFailure { e ->
                        logger.warn(
                            "Failed to resolve external reply reference for message {}: {}",
                            msg.id,
                            e.message ?: e::class.simpleName,
                        )
                    }.getOrNull() ?: continue
            if (ref.id in windowMessageIds) {
                continue
            }
            if (ref.id in resolvedExternalIds) {
                result[msg.id] = ref
                continue
            }
            if (resolvedExternalIds.size < maxExternalReferences) {
                resolvedExternalIds.add(ref.id)
                result[msg.id] = ref
            } else {
                logger.debug(
                    "Skipping external reply reference {} for message {}: reached maxExternalReferences={}",
                    ref.id,
                    msg.id,
                    maxExternalReferences,
                )
            }
        }
        return result
    }

    /**
     * Resolves Discord user, role, and channel mentions in [text] using [contextMessages]
     * and [policy] without mutating raw [Message.content].
     */
    suspend fun resolveTextMentions(
        text: String,
        contextMessages: List<Message>,
        policy: DialogueContextPolicy,
        kordSelfId: Snowflake? = null,
    ): String {
        if (text.isBlank() || !text.contains('<')) return text
        val resolvedSelfId =
            kordSelfId ?: contextMessages.firstNotNullOfOrNull { runCatching { it.kord.selfId }.getOrNull() }
        val resolved =
            mentionResolver.resolveSlice(
                messages = contextMessages,
                extraTexts = listOf(text),
                policy = policy,
                kordSelfId = resolvedSelfId,
            )
        return resolved.formatText(text)
    }

    /**
     * Formats [messages] into structured dialogue turns with inline mention resolution,
     * reply attribution, Tenor/Giphy `[GIF: ...]` and `[連結預覽: ...]` embed previews,
     * policy-gated sticker annotations, and sequential `[附帶圖片 N: filename]` markers.
     */
    suspend fun formatDialogueMessages(
        messages: List<Message>,
        policy: DialogueContextPolicy,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        replyReferencesByMessage: Map<Snowflake, Message> = emptyMap(),
        startingImageIndex: Int = 1,
        kordSelfId: Snowflake? = null,
        formatSpeakerHeader: (DialogueSpeaker) -> String = { it.name },
    ): FormattedDialogueResult {
        val resolvedSelfId =
            kordSelfId ?: messages.firstNotNullOfOrNull { runCatching { it.kord.selfId }.getOrNull() }
        val allSliceMessages = (messages + replyReferencesByMessage.values).distinctBy { it.id }
        val resolvedMentions =
            mentionResolver.resolveSlice(
                messages = allSliceMessages,
                policy = policy,
                kordSelfId = resolvedSelfId,
            )

        fun resolveMessageSpeaker(msg: Message): DialogueSpeaker {
            val authorId =
                runCatching { msg.author?.id }.getOrNull()
                    ?: runCatching { msg.data.author.id }.getOrNull()
            val cachedMemberName = authorId?.let { resolvedMentions.speakerDisplayName(it) }
            return resolveSpeaker(msg, resolvedSelfId, resolvedDisplayName = cachedMemberName)
        }

        var imageIndex = startingImageIndex
        val turns =
            messages.map { msg ->
                val speaker = resolveMessageSpeaker(msg)
                val baseHeader = formatSpeakerHeader(speaker)
                val refMsg = replyReferencesByMessage[msg.id]
                val rawTurnHeader =
                    if (refMsg != null) {
                        val refSpeaker = resolveMessageSpeaker(refMsg)
                        val refHeader = formatSpeakerHeader(refSpeaker)
                        val resolvedRefContent = resolvedMentions.formatText(refMsg.content)
                        val excerpt = truncateReplyExcerpt(resolvedRefContent, policy.maxReplyExcerptLength)
                        "$baseHeader 回覆 $refHeader「$excerpt」"
                    } else {
                        baseHeader
                    }
                val turnHeader = if (policy.wrapTurnHeaderInBrackets) "[$rawTurnHeader]" else rawTurnHeader

                val stickerText =
                    if (policy.includeStickerAnnotations) {
                        DiscordMediaEmbedExtractor.formatStickerAnnotations(msg)
                    } else {
                        ""
                    }
                val embedText = formatEmbedPreviews(msg)
                val images = imagesByMessage[msg.id].orEmpty()
                val markersText =
                    if (images.isNotEmpty()) {
                        images.joinToString(" ") { img ->
                            val idx = imageIndex++
                            if (!img.filename.isNullOrBlank()) {
                                "[附帶圖片 $idx: ${img.filename}]"
                            } else {
                                "[附帶圖片 $idx]"
                            }
                        }
                    } else {
                        ""
                    }

                val rawText = msg.content.takeIf { it.isNotBlank() }?.let { resolvedMentions.formatText(it) }
                val parts =
                    listOfNotNull(
                        rawText,
                        stickerText.takeIf { it.isNotEmpty() },
                        embedText.takeIf { it.isNotEmpty() },
                        markersText.takeIf { it.isNotEmpty() },
                    )

                val body =
                    if (parts.isNotEmpty()) {
                        parts.joinToString(" ")
                    } else if (policy.allowEmbedOnlyMessages || policy.allowStickerOnlyMessages) {
                        resolveBlankMessagePlaceholder(msg).orEmpty()
                    } else {
                        ""
                    }

                if (body.isEmpty()) "$turnHeader:" else "$turnHeader: $body"
            }

        return FormattedDialogueResult(
            turns = turns,
            transcript = turns.joinToString("\n"),
            nextImageIndex = imageIndex,
        )
    }

    fun formatEmbedPreviews(message: Message): String {
        val embeds = runCatching { message.embeds }.getOrNull() ?: return ""
        if (embeds.isEmpty()) return ""

        val previews =
            embeds.mapNotNull { embed ->
                val title = runCatching { embed.title }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
                val desc = runCatching { embed.description }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
                val isGifEmbed = DiscordMediaEmbedExtractor.isTenorOrGiphyEmbed(embed)
                when {
                    isGifEmbed && title != null && desc != null -> "[GIF: $title - $desc]"
                    isGifEmbed && title != null -> "[GIF: $title]"
                    isGifEmbed && desc != null -> "[GIF: $desc]"
                    isGifEmbed -> GIF_PLACEHOLDER
                    title != null && desc != null -> "[連結預覽: $title - $desc]"
                    title != null -> "[連結預覽: $title]"
                    desc != null -> "[連結預覽: $desc]"
                    else -> null
                }
            }
        return previews.joinToString(" ")
    }

    private fun truncateReplyExcerpt(
        content: String,
        maxReplyExcerptLength: Int,
    ): String {
        val rawContent = content.trim().replace("\n", " ")
        val cap = maxReplyExcerptLength.coerceAtLeast(0)
        if (rawContent.length <= cap) {
            return rawContent
        }
        logger.debug(
            "Truncating external reply excerpt from {} chars to maxReplyExcerptLength={}",
            rawContent.length,
            cap,
        )
        return if (cap > 3) {
            "${rawContent.take(cap - 3)}..."
        } else {
            rawContent.take(cap)
        }
    }

    /**
     * Classifies URLs across [messages] in reverse chronological order, selects the latest
     * `SOCIAL`/`YOUTUBE` candidate (or falls back to `webUrls` when
     * [DialogueContextPolicy.fallbackPrimaryUrlToWeb] is `true`), resolves via [peekResolver],
     * enforces [DialogueContextPolicy.maxYouTubeDurationSeconds], and falls back to regex-detected
     * `videoUri` when YouTube resolution fails or returns `null`.
     */
    suspend fun enrichLinks(
        messages: List<Message>,
        policy: DialogueContextPolicy,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        peekResolver: suspend (String) -> PeekPost? = SocialPeekFactory.buildPeekResolver(),
    ): DialogueLinkEnrichmentResult {
        if (messages.isEmpty()) {
            return DialogueLinkEnrichmentResult()
        }

        val imageUrls = imagesByMessage.values.flatten().map { it.url }
        val capturedGifEmbedPageUrls =
            if (policy.includeStickersAndGifEmbeds) {
                messages.flatMap { msg ->
                    if (imagesByMessage[msg.id].isNullOrEmpty()) {
                        emptyList()
                    } else {
                        DiscordMediaEmbedExtractor.extractCapturedGifEmbedPageUrls(msg)
                    }
                }
            } else {
                emptyList()
            }
        val capturedAttachmentUrls = (imageUrls + capturedGifEmbedPageUrls).distinct()

        val classifiedUrls =
            ContextUrlClassifier.classify(
                messages = messages.map { it.content },
                capturedAttachmentUrls = capturedAttachmentUrls,
            )

        var peekTargetUrl: String? = null
        var peekTargetCategory: ContextUrlCategory? = null
        for (msg in messages.asReversed()) {
            for (url in ContextUrlClassifier.extractUrls(msg.content).asReversed()) {
                val category = ContextUrlClassifier.classifyUrl(url, capturedAttachmentUrls)
                if (category == ContextUrlCategory.SOCIAL || category == ContextUrlCategory.YOUTUBE) {
                    peekTargetUrl = url
                    peekTargetCategory = category
                    break
                }
            }
            if (peekTargetUrl != null) break
        }

        val primaryUrl =
            peekTargetUrl ?: if (policy.fallbackPrimaryUrlToWeb) classifiedUrls.webUrls.lastOrNull() else null

        if (peekTargetUrl == null) {
            return DialogueLinkEnrichmentResult(
                primaryUrl = primaryUrl,
                resolvedUrl = primaryUrl,
                classifiedUrls = classifiedUrls,
            )
        }

        val postOutcome = runCatching { peekResolver(peekTargetUrl) }
        val post =
            postOutcome
                .onFailure { e ->
                    logger.warn(
                        "Failed to resolve primaryUrl '{}' via SocialPeek ({}); degrading to Discord message/embed transcript",
                        peekTargetUrl,
                        e.message ?: e::class.simpleName,
                        e,
                    )
                }.getOrNull()

        if (post != null) {
            val resolvedUrl = post.cleanUrl.ifBlank { peekTargetUrl }
            val title = post.title?.trim()?.takeIf { it.isNotBlank() }
            val postContent = post.content.trim().takeIf { it.isNotBlank() }
            val comments = post.comments.mapNotNull { it.content.trim().takeIf { c -> c.isNotBlank() } }
            val videoDurationSeconds = post.rawData["durationSeconds"]?.toLongOrNull()
            val isYouTube = post.platform == Platform.YOUTUBE || peekTargetCategory == ContextUrlCategory.YOUTUBE
            val durationExceeded =
                isYouTube && videoDurationSeconds != null && videoDurationSeconds > policy.maxYouTubeDurationSeconds

            val videoUri =
                if (durationExceeded) {
                    logger.warn(
                        "YouTube video '{}' duration {}s exceeds maxYouTubeDurationSeconds={}s; omitting videoUri and setting durationExceeded=true",
                        resolvedUrl,
                        videoDurationSeconds,
                        policy.maxYouTubeDurationSeconds,
                    )
                    null
                } else if (post.platform == Platform.YOUTUBE) {
                    resolvedUrl
                } else {
                    null
                }

            return DialogueLinkEnrichmentResult(
                primaryUrl = primaryUrl,
                peekTargetUrl = peekTargetUrl,
                resolvedUrl = resolvedUrl,
                title = title,
                postContent = postContent,
                comments = comments,
                videoUri = videoUri,
                videoDurationSeconds = videoDurationSeconds,
                durationExceeded = durationExceeded,
                classifiedUrls = classifiedUrls,
                resolvedPost = post,
            )
        }

        if (postOutcome.isSuccess) {
            logger.warn(
                "SocialPeek returned null for primaryUrl '{}' (unsupported, blocked, or empty resolution); degrading to Discord message/embed transcript",
                peekTargetUrl,
            )
        }

        if (peekTargetCategory == ContextUrlCategory.YOUTUBE) {
            logger.warn(
                "YouTubeResolver failed or returned null for '{}'; falling back to regex-detected videoUri for static Video Understanding",
                peekTargetUrl,
            )
            return DialogueLinkEnrichmentResult(
                primaryUrl = primaryUrl,
                peekTargetUrl = peekTargetUrl,
                resolvedUrl = peekTargetUrl,
                videoUri = peekTargetUrl,
                classifiedUrls = classifiedUrls,
            )
        }

        return DialogueLinkEnrichmentResult(
            primaryUrl = primaryUrl,
            peekTargetUrl = peekTargetUrl,
            resolvedUrl = primaryUrl,
            classifiedUrls = classifiedUrls,
        )
    }

    fun resolveBlankMessagePlaceholder(message: Message): String? {
        val hasAttachments = runCatching { message.attachments.isNotEmpty() }.getOrDefault(false)
        if (hasAttachments) return ATTACHMENT_PLACEHOLDER
        val stickerPlaceholder = DiscordMediaEmbedExtractor.formatStickerAnnotations(message)
        if (stickerPlaceholder.isNotEmpty()) return stickerPlaceholder
        val embeds = runCatching { message.embeds }.getOrDefault(emptyList())
        if (embeds.isNotEmpty()) {
            return resolveEmbedPlaceholder(embeds)
        }
        return null
    }

    private fun resolveEmbedPlaceholder(embeds: List<dev.kord.core.entity.Embed>): String =
        if (embeds.any { DiscordMediaEmbedExtractor.isTenorOrGiphyEmbed(it) }) {
            GIF_PLACEHOLDER
        } else {
            EMBED_PLACEHOLDER
        }

    private fun isSystemMessageType(type: MessageType): Boolean =
        when (type) {
            MessageType.Default,
            MessageType.Reply,
            MessageType.ThreadStarterMessage,
            -> false

            else -> true
        }

    private fun isCommand(
        content: String,
        type: MessageType,
        commandPrefixRule: CommandPrefixRule,
    ): Boolean {
        if (type == MessageType.ChatInputCommand || type == MessageType.ContextMenuCommand) {
            return true
        }
        return commandPrefixRule.matches(content)
    }

    private fun isBlankOrCustomEmojiOnly(content: String): Boolean {
        if (content.isBlank()) return true
        return CUSTOM_EMOJI_REGEX.replace(content, "").isBlank()
    }

    companion object {
        const val ATTACHMENT_PLACEHOLDER = "[Attachment]"
        const val EMBED_PLACEHOLDER = "[Embed]"
        const val GIF_PLACEHOLDER = DiscordMediaEmbedExtractor.GIF_PLACEHOLDER
        const val STICKER_PLACEHOLDER = DiscordMediaEmbedExtractor.STICKER_PLACEHOLDER
        val CUSTOM_EMOJI_REGEX = Regex("""<a?:[a-zA-Z0-9_]+:\d+>""")
    }
}
