package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.socialpeek.SocialPeek
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import luna.core.ai.ChatImageAttachment
import luna.core.ai.ChatImageAttachmentExtractor
import org.slf4j.LoggerFactory

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
        )
}

/**
 * Collaborator seam responsible for the Lightweight-Heavy Multimodal Split in the Chatter Pipeline:
 * - Lightweight decision context: last [decisionMessageCount] filtered turns, Discord embed previews,
 *   and up to [maxDecisionImages] newest valid [ChatImageAttachment]s (never invokes SocialPeek).
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
    val peekResolver: suspend (String) -> PeekPost? = { SocialPeek.peekOrNull(it) },
    val referenceFetcher: suspend (message: Message) -> Message? = { msg ->
        runCatching {
            msg.referencedMessage
                ?: msg.messageReference?.data?.id?.value?.let { refId ->
                    msg.channel.getMessageOrNull(refId)
                }
        }.getOrNull()
    },
) {
    private val logger = LoggerFactory.getLogger(ChatterContextCollector::class.java)

    /**
     * Assembles lightweight decision context over the last [decisionMessageCount] filtered messages.
     * Never invokes [peekResolver] or YouTube ingestion.
     */
    open fun collectDecisionContext(
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

        val window = filtered.takeLast(decisionMessageCount.coerceAtLeast(1))
        val imagesByMessage =
            ChatImageAttachmentExtractor.selectChronologicalImages(
                messages = window,
                maxImages = maxDecisionImages.coerceAtLeast(0),
                maxImageBytes = maxImageBytes,
            )
        val decisionImages = imagesByMessage.values.flatten()
        val transcript =
            formatDialogueMessages(
                messages = window,
                imagesByMessage = imagesByMessage,
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

        val window = filtered.takeLast(historyMessageCount.coerceAtLeast(1))
        val imagesByMessage =
            ChatImageAttachmentExtractor.selectChronologicalImages(
                messages = window,
                maxImages = maxImages.coerceAtLeast(0),
                maxImageBytes = maxImageBytes,
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

        val primaryUrl =
            window
                .asReversed()
                .firstNotNullOfOrNull { msg -> ChatterNoiseFilter.URL_REGEX.find(msg.content)?.value }

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
                )
            } else if (postOutcome.isSuccess) {
                logger.warn(
                    "SocialPeek returned null for primaryUrl '{}' (unsupported, blocked, or empty resolution); degrading to Discord message/embed transcript",
                    primaryUrl,
                )
            }
        }

        return PreparedChatterContext(
            transcript = dialogueTranscript,
            imageAttachments = replyImages,
            primaryUrl = primaryUrl,
        )
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

    internal fun formatDialogueMessages(
        messages: List<Message>,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        replyReferencesByMessage: Map<Snowflake, Message> = emptyMap(),
        kordSelfId: Snowflake? = null,
    ): String {
        var imageIndex = 1
        return messages.joinToString("\n") { msg ->
            val baseAuthor = resolveAuthorName(msg, kordSelfId)
            val refMsg = replyReferencesByMessage[msg.id]
            val author =
                if (refMsg != null) {
                    val refAuthor = resolveAuthorName(refMsg, kordSelfId)
                    val rawContent = refMsg.content.trim().replace("\n", " ")
                    val excerpt =
                        if (rawContent.length > MAX_REPLY_EXCERPT_LENGTH) {
                            "${rawContent.take(MAX_REPLY_EXCERPT_LENGTH - 3)}..."
                        } else {
                            rawContent
                        }
                    "$baseAuthor 回覆 $refAuthor「$excerpt」"
                } else {
                    baseAuthor
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

            val strippedText = noiseFilter.stripLunaMentions(msg.content, kordSelfId)
            val hasAttachments = runCatching { msg.attachments.isNotEmpty() }.getOrDefault(false)
            val isDirectMention = noiseFilter.isDirectBotMention(msg, kordSelfId)

            val parts =
                listOfNotNull(
                    strippedText.takeIf { it.isNotEmpty() },
                    embedText.takeIf { it.isNotEmpty() },
                    markersText.takeIf { it.isNotEmpty() },
                )

            val body =
                when {
                    parts.isNotEmpty() -> parts.joinToString(" ")
                    isDirectMention -> ChatterNoiseFilter.CALLOUT_GREETING
                    hasAttachments -> "[Attachment]"
                    msg.content.isNotBlank() -> ChatterNoiseFilter.CALLOUT_GREETING
                    else -> ""
                }

            "[$author]: $body".trimEnd()
        }
    }

    internal fun formatEmbedPreviews(message: Message): String {
        val embeds = runCatching { message.embeds }.getOrNull() ?: return ""
        if (embeds.isEmpty()) return ""

        val previews =
            embeds.mapNotNull { embed ->
                val title = embed.title?.trim()?.takeIf { it.isNotBlank() }
                val desc = embed.description?.trim()?.takeIf { it.isNotBlank() }
                when {
                    title != null && desc != null -> "[連結預覽: $title - $desc]"
                    title != null -> "[連結預覽: $title]"
                    desc != null -> "[連結預覽: $desc]"
                    else -> null
                }
            }
        return previews.joinToString(" ")
    }

    internal fun resolveAuthorName(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): String {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        if (resolvedSelfId != null) {
            val authorId =
                runCatching { message.author?.id }.getOrNull()
                    ?: runCatching { message.data.author.id }.getOrNull()
            if (authorId == resolvedSelfId) {
                return "Luna"
            }
        }
        return noiseFilter.extractSpeakerName(message)
    }

    companion object {
        const val DEFAULT_DECISION_MESSAGE_COUNT = 3
        const val DEFAULT_HISTORY_MESSAGE_COUNT = 15
        const val DEFAULT_MAX_DECISION_IMAGES = 3
        const val DEFAULT_MAX_IMAGES = 10
        const val DEFAULT_MAX_EXTERNAL_REFERENCES = 3
        const val MAX_REPLY_EXCERPT_LENGTH = 40
    }
}
