package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import luna.core.ai.ChatImageAttachment
import luna.core.ai.ChatImageAttachmentExtractor
import luna.core.ai.ContextUrlCategory
import luna.core.ai.ContextUrlClassifier
import luna.core.ai.DiscordMediaEmbedExtractor
import luna.core.ai.SocialPeekFactory
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

/**
 * Lightweight context payload passed to Gemini Decisions API (`gemini-decision-1`).
 */
data class ChatterDecisionContext(
    val transcript: String,
    val images: List<ChatImageAttachment> = emptyList(),
)

/**
 * Rich multimodal reply context assembled after participation confirmation or direct mention.
 */
data class PreparedChatterContext(
    val transcript: String,
    val imageAttachments: List<ChatImageAttachment> = emptyList(),
    val primaryUrl: String? = null,
    val title: String? = null,
    val url: String? = null,
    val postContent: String? = null,
    val comments: List<String> = emptyList(),
    val videoUri: String? = null,
    val videoDurationSeconds: Long? = null,
    val webUrls: List<String> = emptyList(),
) {
    fun toChatterInput(isDirectMention: Boolean = false): ChatterInput =
        ChatterInput(
            transcript = transcript,
            imageAttachments = imageAttachments,
            videoUri = videoUri,
            videoDurationSeconds = videoDurationSeconds,
            isDirectMention = isDirectMention,
            title = title,
            url = url,
            comments = comments,
            webUrls = webUrls,
        )
}

/**
 * Collaborator seam responsible for the Lightweight-Heavy Multimodal Split in the Chatter Pipeline:
 * - Lightweight decision context: last [decisionMessageCount] filtered turns, external Reply Reference Chaining,
 *   Discord embed previews, structural section framing, and up to [maxDecisionImages] newest valid
 *   [ChatImageAttachment]s (never invokes SocialPeek or YouTube ingestion).
 * - Heavy reply context: last [historyMessageCount] filtered turns, external Reply Reference Chaining,
 *   Discord embed previews, up to [maxImages] valid [ChatImageAttachment]s, and SocialPeek resolution
 *   for the latest `primaryUrl` in the window.
 */
open class ChatterContextCollector(
    val noiseFilter: ChatterNoiseFilter = ChatterNoiseFilter(),
    val decisionMessageCount: Int = DEFAULT_DECISION_MESSAGE_COUNT,
    val historyMessageCount: Int = DEFAULT_HISTORY_MESSAGE_COUNT,
    val maxDecisionImages: Int = DEFAULT_MAX_DECISION_IMAGES,
    val maxImages: Int = DEFAULT_MAX_IMAGES,
    val maxImageBytes: Int = ChatImageAttachmentExtractor.DEFAULT_MAX_IMAGE_BYTES,
    val maxExternalReferences: Int = DEFAULT_MAX_EXTERNAL_REFERENCES,
    val peekResolver: suspend (String) -> PeekPost? = SocialPeekFactory.buildPeekResolver(),
    val referenceFetcher: suspend (message: Message) -> Message? = { msg ->
        runCatching {
            msg.referencedMessage
                ?: msg.messageReference?.data?.id?.value?.let { refId ->
                    msg.channel.getMessageOrNull(refId)
                }
        }.getOrNull()
    },
    val historyWindowMinutes: Long = DEFAULT_HISTORY_WINDOW_MINUTES,
) {
    private val logger = LoggerFactory.getLogger(ChatterContextCollector::class.java)

    /**
     * Assembles lightweight decision context over the last [decisionMessageCount] filtered messages,
     * including external Reply Reference Chaining and structural section framing.
     * Never invokes [peekResolver] or YouTube ingestion.
     */
    open suspend fun collectDecisionContext(
        messages: List<Message>,
        kordSelfId: Snowflake? = null,
    ): ChatterDecisionContext {
        val resolvedSelfId = kordSelfId ?: messages.firstNotNullOfOrNull { runCatching { it.kord.selfId }.getOrNull() }
        val filtered =
            noiseFilter
                .filterContextMessages(messages, resolvedSelfId)
                .distinctBy { it.id }

        if (filtered.isEmpty()) {
            return ChatterDecisionContext(transcript = "", images = emptyList())
        }

        val recent = filterByRecencyWindow(filtered)
        val window = recent.takeLast(decisionMessageCount.coerceAtLeast(1))
        val imagesByMessage =
            ChatImageAttachmentExtractor.selectChronologicalImages(
                messages = window,
                maxImages = maxDecisionImages.coerceAtLeast(0),
                maxImageBytes = maxImageBytes,
                includeStickersAndGifEmbeds = true,
            )
        val decisionImages = imagesByMessage.values.flatten()
        val replyReferencesByMessage =
            resolveExternalReplyReferences(
                messages = window,
                maxExternalReferences = maxExternalReferences.coerceAtLeast(0),
            )
        val formattedTurns =
            formatDialogueTurnList(
                messages = window,
                imagesByMessage = imagesByMessage,
                replyReferencesByMessage = replyReferencesByMessage,
                kordSelfId = resolvedSelfId,
            )
        val transcript =
            formatDecisionTranscript(
                formattedTurns = formattedTurns,
                kordSelfId = resolvedSelfId,
            )

        return ChatterDecisionContext(
            transcript = transcript,
            images = decisionImages,
        )
    }

    /**
     * Assembles heavy reply context over the last [historyMessageCount] filtered messages,
     * including external Reply Reference Chaining, Discord embed previews, up to [maxImages]
     * newest valid [ChatImageAttachment]s, and SocialPeek resolution for the latest `primaryUrl`.
     */
    open suspend fun collectReplyContext(
        messages: List<Message>,
        kordSelfId: Snowflake? = null,
    ): PreparedChatterContext {
        val resolvedSelfId = kordSelfId ?: messages.firstNotNullOfOrNull { runCatching { it.kord.selfId }.getOrNull() }
        val filtered =
            noiseFilter
                .filterContextMessages(messages, resolvedSelfId)
                .distinctBy { it.id }

        if (filtered.isEmpty()) {
            return PreparedChatterContext(transcript = "")
        }

        val recent = filterByRecencyWindow(filtered)
        val window = recent.takeLast(historyMessageCount.coerceAtLeast(1))
        val imagesByMessage =
            ChatImageAttachmentExtractor.selectChronologicalImages(
                messages = window,
                maxImages = maxImages.coerceAtLeast(0),
                maxImageBytes = maxImageBytes,
                includeStickersAndGifEmbeds = true,
            )
        val replyImages = imagesByMessage.values.flatten()
        val replyReferencesByMessage =
            resolveExternalReplyReferences(
                messages = window,
                maxExternalReferences = maxExternalReferences.coerceAtLeast(0),
            )
        val dialogueTranscript =
            formatDialogueMessages(
                messages = window,
                imagesByMessage = imagesByMessage,
                replyReferencesByMessage = replyReferencesByMessage,
                kordSelfId = resolvedSelfId,
            )

        val capturedGifEmbedPageUrls =
            window.flatMap { msg ->
                if (imagesByMessage[msg.id].isNullOrEmpty()) {
                    emptyList()
                } else {
                    DiscordMediaEmbedExtractor.extractCapturedGifEmbedPageUrls(msg)
                }
            }
        val capturedAttachmentUrls = (replyImages.map { it.url } + capturedGifEmbedPageUrls).distinct()
        val classifiedUrls =
            ContextUrlClassifier.classify(
                messages = window.map { it.content },
                capturedAttachmentUrls = capturedAttachmentUrls,
            )

        var primaryUrl: String? = null
        var primaryCategory: ContextUrlCategory? = null
        for (msg in window.asReversed()) {
            for (url in ContextUrlClassifier.extractUrls(msg.content).asReversed()) {
                val category = ContextUrlClassifier.classifyUrl(url, capturedAttachmentUrls)
                if (category == ContextUrlCategory.SOCIAL || category == ContextUrlCategory.YOUTUBE) {
                    primaryUrl = url
                    primaryCategory = category
                    break
                }
            }
            if (primaryUrl != null) break
        }

        if (primaryUrl != null) {
            val postOutcome = runCatching { peekResolver(primaryUrl) }
            val post =
                postOutcome
                    .onFailure { e ->
                        logger.warn(
                            "Failed to resolve primaryUrl '{}' via SocialPeek ({}); degrading to Discord message/embed transcript",
                            primaryUrl,
                            e.message ?: e::class.simpleName,
                            e,
                        )
                    }.getOrNull()

            if (post != null) {
                val resolvedUrl = post.cleanUrl.ifBlank { primaryUrl }
                val title = post.title?.trim()?.takeIf { it.isNotBlank() }
                val postContent = post.content.trim().takeIf { it.isNotBlank() }
                val comments = post.comments.mapNotNull { it.content.trim().takeIf { c -> c.isNotBlank() } }
                val videoUri = if (post.platform == Platform.YOUTUBE) resolvedUrl else null
                val videoDurationSeconds = post.rawData["durationSeconds"]?.toLongOrNull()

                val enrichedTranscript =
                    buildEnrichedTranscript(
                        dialogueTranscript = dialogueTranscript,
                        postTitle = title,
                        postUrl = resolvedUrl,
                        postContent = postContent,
                        comments = comments,
                    )

                return PreparedChatterContext(
                    transcript = enrichedTranscript,
                    imageAttachments = replyImages,
                    primaryUrl = primaryUrl,
                    title = title,
                    url = resolvedUrl,
                    postContent = postContent,
                    comments = comments,
                    videoUri = videoUri,
                    videoDurationSeconds = videoDurationSeconds,
                    webUrls = classifiedUrls.webUrls,
                )
            } else if (postOutcome.isSuccess) {
                logger.warn(
                    "SocialPeek returned null for primaryUrl '{}' (unsupported, blocked, or empty resolution); degrading to Discord message/embed transcript",
                    primaryUrl,
                )
            }

            if (primaryCategory == ContextUrlCategory.YOUTUBE) {
                logger.warn(
                    "YouTubeResolver failed or returned null for '{}'; falling back to regex-detected videoUri for static Video Understanding",
                    primaryUrl,
                )
                return PreparedChatterContext(
                    transcript = dialogueTranscript,
                    imageAttachments = replyImages,
                    primaryUrl = primaryUrl,
                    url = primaryUrl,
                    videoUri = primaryUrl,
                    videoDurationSeconds = null,
                    webUrls = classifiedUrls.webUrls,
                )
            }
        }

        return PreparedChatterContext(
            transcript = dialogueTranscript,
            imageAttachments = replyImages,
            primaryUrl = primaryUrl,
            webUrls = classifiedUrls.webUrls,
        )
    }

    internal fun filterByRecencyWindow(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return emptyList()
        val effectiveWindowMinutes = historyWindowMinutes.coerceAtLeast(MIN_HISTORY_WINDOW_MINUTES)
        val anchorTimestamp = runCatching { messages.last().timestamp }.getOrNull() ?: return messages
        val cutoff = anchorTimestamp - effectiveWindowMinutes.minutes
        return messages.filter { msg ->
            val msgTimestamp = runCatching { msg.timestamp }.getOrNull()
            msgTimestamp == null || msgTimestamp >= cutoff
        }
    }

    internal suspend fun resolveExternalReplyReferences(
        messages: List<Message>,
        maxExternalReferences: Int = DEFAULT_MAX_EXTERNAL_REFERENCES,
    ): Map<Snowflake, Message> {
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
            }
        }
        return result
    }

    internal fun buildEnrichedTranscript(
        dialogueTranscript: String,
        postTitle: String?,
        postUrl: String,
        postContent: String?,
        comments: List<String>,
    ): String {
        val hasPostDetails = !postTitle.isNullOrBlank() || !postContent.isNullOrBlank() || comments.isNotEmpty()
        if (!hasPostDetails) {
            return dialogueTranscript
        }
        val sb = StringBuilder()
        if (dialogueTranscript.isNotBlank()) {
            sb.append(dialogueTranscript.trim()).append("\n\n")
        }
        sb.append("[連結內容: ").append(postUrl).append("]")
        if (!postTitle.isNullOrBlank()) {
            sb.append("\n標題：").append(postTitle)
        }
        if (!postContent.isNullOrBlank()) {
            sb.append("\n內容：\n").append(postContent)
        }
        if (comments.isNotEmpty()) {
            sb.append("\n留言：")
            comments.forEachIndexed { idx, comment ->
                sb.append("\n${idx + 1}. ").append(comment)
            }
        }
        return sb.toString().trim()
    }

    internal fun formatDecisionTranscript(
        formattedTurns: List<String>,
        kordSelfId: Snowflake?,
    ): String {
        if (formattedTurns.isEmpty()) {
            return ""
        }
        val identityHeader =
            if (kordSelfId != null) {
                "[Bot Identity: Luna | Discord Mention: <@$kordSelfId>]"
            } else {
                "[Bot Identity: Luna]"
            }
        val recentTurns = formattedTurns.dropLast(1)
        val latestTurn = formattedTurns.last()
        return buildString {
            append(identityHeader)
            if (recentTurns.isNotEmpty()) {
                append("\n\n[Recent Context]\n")
                append(recentTurns.joinToString("\n"))
            }
            append("\n\n[Latest Message]\n")
            append(latestTurn)
        }
    }

    internal fun formatDialogueMessages(
        messages: List<Message>,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        replyReferencesByMessage: Map<Snowflake, Message> = emptyMap(),
        kordSelfId: Snowflake? = null,
    ): String =
        formatDialogueTurnList(
            messages = messages,
            imagesByMessage = imagesByMessage,
            replyReferencesByMessage = replyReferencesByMessage,
            kordSelfId = kordSelfId,
        ).joinToString("\n")

    internal fun formatDialogueTurnList(
        messages: List<Message>,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        replyReferencesByMessage: Map<Snowflake, Message> = emptyMap(),
        kordSelfId: Snowflake? = null,
    ): List<String> {
        var imageIndex = 1
        return messages.map { msg ->
            val speaker = resolveSpeakerMetadata(msg, kordSelfId)
            val baseHeader = ChatterPromptBuilder.formatSpeakerHeader(speaker.name, speaker.authorId, speaker.isThirdPartyBot)
            val refMsg = replyReferencesByMessage[msg.id]
            val turnHeader =
                if (refMsg != null) {
                    val refSpeaker = resolveSpeakerMetadata(refMsg, kordSelfId)
                    val refHeader =
                        ChatterPromptBuilder.formatSpeakerHeader(
                            refSpeaker.name,
                            refSpeaker.authorId,
                            refSpeaker.isThirdPartyBot,
                        )
                    val rawContent = refMsg.content.trim().replace("\n", " ")
                    val excerpt =
                        if (rawContent.length > MAX_REPLY_EXCERPT_LENGTH) {
                            "${rawContent.take(MAX_REPLY_EXCERPT_LENGTH - 3)}..."
                        } else {
                            rawContent
                        }
                    "$baseHeader 回覆 $refHeader「$excerpt」"
                } else {
                    baseHeader
                }

            val stickerText = DiscordMediaEmbedExtractor.formatStickerAnnotations(msg)
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

            val rawText = msg.content.takeIf { it.isNotBlank() }

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
                } else {
                    ChatterNoiseFilter.resolveBlankMessagePlaceholder(msg).orEmpty()
                }

            if (body.isEmpty()) "$turnHeader:" else "$turnHeader: $body"
        }
    }

    internal fun formatEmbedPreviews(message: Message): String {
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
                    isGifEmbed -> DiscordMediaEmbedExtractor.GIF_PLACEHOLDER
                    title != null && desc != null -> "[連結預覽: $title - $desc]"
                    title != null -> "[連結預覽: $title]"
                    desc != null -> "[連結預覽: $desc]"
                    else -> null
                }
            }
        return previews.joinToString(" ")
    }

    private data class SpeakerMetadata(
        val name: String,
        val authorId: String?,
        val isThirdPartyBot: Boolean,
    )

    private fun resolveSpeakerMetadata(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): SpeakerMetadata {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        val authorId =
            runCatching { message.author?.id }.getOrNull()
                ?: runCatching { message.data.author.id }.getOrNull()
        val isLunaBot = resolvedSelfId != null && authorId == resolvedSelfId
        val name = if (isLunaBot) "Luna" else noiseFilter.extractSpeakerName(message)
        val isThirdPartyBot = !isLunaBot && (runCatching { message.author?.isBot }.getOrNull() == true)
        return SpeakerMetadata(
            name = name,
            authorId = authorId?.toString(),
            isThirdPartyBot = isThirdPartyBot,
        )
    }

    internal fun resolveAuthorName(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): String = resolveSpeakerMetadata(message, kordSelfId).name

    companion object {
        const val DEFAULT_DECISION_MESSAGE_COUNT = 15
        const val DEFAULT_HISTORY_MESSAGE_COUNT = 30
        const val DEFAULT_HISTORY_WINDOW_MINUTES = 10L
        const val MIN_HISTORY_WINDOW_MINUTES = 1L
        const val DEFAULT_MAX_DECISION_IMAGES = 3
        const val DEFAULT_MAX_IMAGES = 10
        const val DEFAULT_MAX_EXTERNAL_REFERENCES = 3
        const val MAX_REPLY_EXCERPT_LENGTH = 40
    }
}
