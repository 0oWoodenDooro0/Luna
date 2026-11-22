package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.socialpeek.model.PeekPost
import luna.core.ai.ChatImageAttachment
import luna.core.ai.ChatImageAttachmentExtractor
import luna.core.ai.DialogueContextPolicy
import luna.core.ai.DialogueSpeaker
import luna.core.ai.DiscordDialogueContextEngine
import luna.core.ai.FormattedDialogueResult
import luna.core.ai.SocialPeekFactory
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
    val oldestWindowTimestampMs: Long? = null,
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
            workingMemoryStartMs = oldestWindowTimestampMs,
        )
}

/**
 * Collaborator seam responsible for the Lightweight-Heavy Multimodal Split in the Chatter Pipeline:
 * - Lightweight decision context: last [decisionMessageCount] filtered turns within the Working Memory Window,
 *   structural section framing, and up to [maxDecisionImages] newest valid [ChatImageAttachment]s
 *   (never invokes SocialPeek or YouTube ingestion).
 * - Heavy reply context: last [historyMessageCount] filtered turns within the Working Memory Window,
 *   up to [maxImages] valid [ChatImageAttachment]s, and SocialPeek/YouTube enrichment via [contextEngine].
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
    val contextEngine: DiscordDialogueContextEngine = noiseFilter.contextEngine,
) {
    private fun chatterPolicy(): DialogueContextPolicy =
        DialogueContextPolicy.chatter(
            botPrefixes = noiseFilter.botPrefixes,
            maxExternalReferences = maxExternalReferences.coerceAtLeast(0),
            maxReplyExcerptLength = MAX_REPLY_EXCERPT_LENGTH,
        )

    private fun formatChatterSpeakerHeader(speaker: DialogueSpeaker): String =
        ChatterPromptBuilder.formatSpeakerHeader(speaker.name, speaker.authorId, speaker.isThirdPartyBot)

    private data class FormattedChatterWindow(
        val policy: DialogueContextPolicy,
        val imagesByMessage: Map<Snowflake, List<ChatImageAttachment>>,
        val images: List<ChatImageAttachment>,
        val formatted: FormattedDialogueResult,
    )

    private suspend fun formatChatterWindow(
        window: List<Message>,
        maxWindowImages: Int,
        kordSelfId: Snowflake?,
    ): FormattedChatterWindow {
        val policy = chatterPolicy()
        val imagesByMessage =
            contextEngine.selectImages(
                messages = window,
                policy = policy,
                maxImages = maxWindowImages.coerceAtLeast(0),
                maxImageBytes = maxImageBytes,
            )
        val replyReferencesByMessage =
            contextEngine.resolveExternalReplyReferences(
                messages = window,
                policy = policy,
                referenceFetcher = referenceFetcher,
            )
        val formatted =
            contextEngine.formatDialogueMessages(
                messages = window,
                policy = policy,
                imagesByMessage = imagesByMessage,
                replyReferencesByMessage = replyReferencesByMessage,
                kordSelfId = kordSelfId,
                formatSpeakerHeader = ::formatChatterSpeakerHeader,
            )
        return FormattedChatterWindow(
            policy = policy,
            imagesByMessage = imagesByMessage,
            images = imagesByMessage.values.flatten(),
            formatted = formatted,
        )
    }

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
        val windowContext =
            formatChatterWindow(
                window = window,
                maxWindowImages = maxDecisionImages,
                kordSelfId = resolvedSelfId,
            )
        val transcript =
            formatDecisionTranscript(
                formattedTurns = windowContext.formatted.turns,
                kordSelfId = resolvedSelfId,
            )

        return ChatterDecisionContext(
            transcript = transcript,
            images = windowContext.images,
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
        val oldestWindowTimestampMs =
            window.firstNotNullOfOrNull { msg ->
                runCatching { msg.timestamp.toEpochMilliseconds() }.getOrNull()
            }
        val windowContext =
            formatChatterWindow(
                window = window,
                maxWindowImages = maxImages,
                kordSelfId = resolvedSelfId,
            )
        val dialogueTranscript = windowContext.formatted.transcript

        val enrichment =
            contextEngine.enrichLinks(
                messages = window,
                policy = windowContext.policy,
                imagesByMessage = windowContext.imagesByMessage,
                peekResolver = peekResolver,
            )

        val transcript =
            if (enrichment.resolvedPost != null) {
                buildEnrichedTranscript(
                    dialogueTranscript = dialogueTranscript,
                    postTitle = enrichment.title,
                    postUrl = enrichment.resolvedUrl ?: enrichment.primaryUrl.orEmpty(),
                    postContent = enrichment.postContent,
                    comments = enrichment.comments,
                )
            } else {
                dialogueTranscript
            }

        return PreparedChatterContext(
            transcript = transcript,
            imageAttachments = windowContext.images,
            primaryUrl = enrichment.primaryUrl,
            title = enrichment.title,
            url = enrichment.resolvedUrl,
            postContent = enrichment.postContent,
            comments = enrichment.comments,
            videoUri = enrichment.videoUri,
            videoDurationSeconds = enrichment.videoDurationSeconds,
            webUrls = enrichment.classifiedUrls.webUrls,
            oldestWindowTimestampMs = oldestWindowTimestampMs,
        )
    }

    internal fun filterByRecencyWindow(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return emptyList()
        val effectiveGapMinutes = historyWindowMinutes.coerceAtLeast(MIN_HISTORY_WINDOW_MINUTES)
        val anchorTimestamp = runCatching { messages.last().timestamp }.getOrNull() ?: return messages
        val maxLookbackMinutes = maxOf(effectiveGapMinutes * MAX_LOOKBACK_GAP_MULTIPLIER, DEFAULT_MAX_HISTORY_LOOKBACK_MINUTES)
        val oldestAllowedTimestamp = anchorTimestamp - maxLookbackMinutes.minutes
        val maxGap = effectiveGapMinutes.minutes

        val keptReversed = mutableListOf(messages.last())
        var nextTimestamp = anchorTimestamp

        for (index in messages.size - 2 downTo 0) {
            val msg = messages[index]
            val msgTimestamp = runCatching { msg.timestamp }.getOrNull()
            if (msgTimestamp == null) {
                keptReversed.add(msg)
                continue
            }
            if (msgTimestamp < oldestAllowedTimestamp) {
                break
            }
            if (nextTimestamp - msgTimestamp > maxGap) {
                break
            }
            keptReversed.add(msg)
            nextTimestamp = msgTimestamp
        }

        return keptReversed.asReversed()
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

    companion object {
        const val DEFAULT_DECISION_MESSAGE_COUNT = 15
        const val DEFAULT_HISTORY_MESSAGE_COUNT = 30
        const val DEFAULT_HISTORY_WINDOW_MINUTES = 10L
        const val MIN_HISTORY_WINDOW_MINUTES = 1L
        const val DEFAULT_MAX_HISTORY_LOOKBACK_MINUTES = 60L
        const val MAX_LOOKBACK_GAP_MULTIPLIER = 6L
        const val DEFAULT_MAX_DECISION_IMAGES = 3
        const val DEFAULT_MAX_IMAGES = 10
        const val DEFAULT_MAX_EXTERNAL_REFERENCES = DialogueContextPolicy.DEFAULT_MAX_EXTERNAL_REFERENCES
        const val MAX_REPLY_EXCERPT_LENGTH = DialogueContextPolicy.DEFAULT_MAX_REPLY_EXCERPT_LENGTH
    }
}
