package luna.summarizer

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.socialpeek.model.PeekPost
import kotlinx.coroutines.flow.toList
import luna.core.ai.ChatImageAttachment
import luna.core.ai.ChatImageAttachmentExtractor
import luna.core.ai.DialogueContextPolicy
import luna.core.ai.DiscordDialogueContextEngine
import luna.core.ai.SocialPeekFactory
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Value object representing an assembled Conversation Slice ready for AI summarization.
 *
 * @property summaryInput Formatted input payload including dialogue transcript and media attachments.
 * @property primaryUrl Extracted primary link if a web URL was detected in the slice.
 * @property durationExceeded True if a linked YouTube video exceeds the 2-hour processing limit.
 * @property lookbackProvider Optional callback to fetch earlier conversational context on demand.
 */
data class PreparedConversationSlice(
    val summaryInput: SummaryInput,
    val primaryUrl: String? = null,
    val durationExceeded: Boolean = false,
    val lookbackProvider: (suspend (reason: String) -> String?)? = null,
)

/**
 * Deep module responsible for Dual-Boundary Backtracking, Dialogue Noise Filtering,
 * Chat Image Attachment extraction, capacity budgeting, and transcript formatting.
 */
open class ConversationSliceCollector(
    val maxHistoryMessages: Int = DEFAULT_MAX_HISTORY_MESSAGES,
    val inactivityGap: Duration = DEFAULT_INACTIVITY_GAP,
    val maxCharacterBudget: Int = DEFAULT_MAX_CHARACTER_BUDGET,
    val maxImages: Int = DEFAULT_MAX_IMAGES,
    val maxLookbackMessages: Int = DEFAULT_MAX_LOOKBACK_MESSAGES,
    val maxImageBytes: Int = ChatImageAttachmentExtractor.DEFAULT_MAX_IMAGE_BYTES,
    val noiseFilter: DialogueNoiseFilter = DialogueNoiseFilter(),
    val peekResolver: suspend (String) -> PeekPost? = SocialPeekFactory.buildPeekResolver(),
    val historyFetcher: suspend (message: Message, limit: Int) -> List<Message> = { message, limit ->
        message.channel
            .getMessagesBefore(message.id, limit = limit)
            .toList()
            .reversed()
    },
    val forwardFetcher: suspend (message: Message, limit: Int) -> List<Message> = { message, limit ->
        message.channel
            .getMessagesAfter(message.id, limit = limit)
            .toList()
    },
    val referenceFetcher: suspend (message: Message) -> Message? = { msg ->
        msg.referencedMessage
            ?: msg.messageReference?.data?.id?.value?.let { refId ->
                msg.channel.getMessageOrNull(refId)
            }
    },
    val contextEngine: DiscordDialogueContextEngine = noiseFilter.contextEngine,
    val policy: DialogueContextPolicy = noiseFilter.policy,
) {
    private val logger = LoggerFactory.getLogger(ConversationSliceCollector::class.java)

    fun clearCache() {
        noiseFilter.clearWebhookCache()
    }

    internal suspend fun isNoiseMessage(message: Message): Boolean = noiseFilter.isNoiseMessage(message)

    internal suspend fun isBotSummaryMessage(message: Message): Boolean = noiseFilter.isBotSummaryMessage(message)

    private fun formatTranscript(
        messages: List<Message>,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        replyReferencesByMessage: Map<Snowflake, Message> = emptyMap(),
    ): String =
        contextEngine
            .formatDialogueMessages(
                messages = messages,
                policy = policy,
                imagesByMessage = imagesByMessage,
                replyReferencesByMessage = replyReferencesByMessage,
                kordSelfId = noiseFilter.selfId,
            ).transcript

    suspend fun collectSlice(message: Message): PreparedConversationSlice? {
        if (isNoiseMessage(message)) {
            logger.debug("Ignoring reaction on noise or bot message {}", message.id)
            return null
        }

        val (precedingMessagesRaw, unconsumedHistory) = retrieveContextMessagesWithHistory(message)
        val precedingMessages = precedingMessagesRaw.filterNot { isNoiseMessage(it) }

        val consumedCount = precedingMessagesRaw.size + 1
        val remainingBudget = (maxHistoryMessages - consumedCount).coerceAtLeast(0)
        val initialLength = message.content.length + precedingMessagesRaw.sumOf { it.content.length }

        val subsequentMessagesRaw =
            if (remainingBudget > 0) {
                retrieveForwardMessages(message, remainingBudget, initialLength)
            } else {
                emptyList()
            }
        val subsequentMessages = subsequentMessagesRaw.filterNot { isNoiseMessage(it) }

        val allMessages =
            (precedingMessages + message + subsequentMessages)
                .distinctBy { it.id }
                .sortedBy { it.id.value }

        val imagesMap =
            contextEngine.selectImages(
                messages = allMessages,
                policy = policy,
                maxImages = maxImages,
                maxImageBytes = maxImageBytes,
                prioritizedMessageId = message.id,
            )
        val sliceImageAttachments = imagesMap.values.flatten()

        val replyReferencesByMessage =
            contextEngine.resolveExternalReplyReferences(
                messages = allMessages,
                policy = policy,
                referenceFetcher = referenceFetcher,
            )

        val formattedContent =
            if (allMessages.size > 1) {
                formatTranscript(
                    messages = allMessages,
                    imagesByMessage = imagesMap,
                    replyReferencesByMessage = replyReferencesByMessage,
                )
            } else {
                val embedText = contextEngine.formatEmbedPreviews(message)
                if (sliceImageAttachments.isNotEmpty() || embedText.isNotEmpty() || replyReferencesByMessage.isNotEmpty()) {
                    formatTranscript(
                        messages = listOf(message),
                        imagesByMessage = mapOf(message.id to sliceImageAttachments),
                        replyReferencesByMessage = replyReferencesByMessage,
                    )
                } else {
                    message.content
                }
            }

        val enrichment =
            contextEngine.enrichLinks(
                messages = allMessages,
                policy = policy,
                imagesByMessage = imagesMap,
                peekResolver = peekResolver,
            )

        if (enrichment.durationExceeded) {
            return PreparedConversationSlice(
                summaryInput = SummaryInput(content = ""),
                primaryUrl = enrichment.primaryUrl,
                durationExceeded = true,
            )
        }

        if (enrichment.resolvedPost != null) {
            val postContent = enrichment.postContent.orEmpty()
            val peekTargetUrl = enrichment.peekTargetUrl
            val combinedContent =
                if (allMessages.size == 1 && sliceImageAttachments.isEmpty() && peekTargetUrl != null) {
                    val commentary = message.content.replace(peekTargetUrl, "").trim()
                    if (commentary.isNotBlank() && postContent.isNotBlank()) {
                        "使用者附註: $commentary\n\n內容:\n$postContent"
                    } else {
                        postContent.ifBlank { message.content }
                    }
                } else {
                    if (postContent.isNotBlank()) {
                        "$formattedContent\n\n內容:\n$postContent"
                    } else {
                        formattedContent
                    }
                }

            return PreparedConversationSlice(
                summaryInput =
                    SummaryInput(
                        content = combinedContent,
                        title = enrichment.title,
                        comments = enrichment.comments,
                        url = enrichment.primaryUrl,
                        webUrls = enrichment.classifiedUrls.webUrls,
                        originalUrls = enrichment.classifiedUrls.allOriginalUrls,
                        videoUri = enrichment.videoUri,
                        videoDurationSeconds = enrichment.videoDurationSeconds,
                        imageAttachments = sliceImageAttachments,
                    ),
                primaryUrl = enrichment.primaryUrl,
                durationExceeded = false,
            )
        }

        val lookbackProvider: suspend (reason: String) -> String? = { _ ->
            val earliestBaseline = allMessages.first()
            val earlierMessages =
                retrieveEarlierMessages(
                    earliestBaselineMessage = earliestBaseline,
                    fallbackHistory = unconsumedHistory,
                )

            if (earlierMessages.isNotEmpty()) {
                val earlierReferences =
                    contextEngine.resolveExternalReplyReferences(
                        messages = earlierMessages,
                        policy = policy,
                        referenceFetcher = referenceFetcher,
                    )
                val earlierTranscript =
                    formatTranscript(
                        messages = earlierMessages,
                        replyReferencesByMessage = earlierReferences,
                    )
                val baselineTranscript =
                    if (allMessages.size > 1) {
                        formattedContent
                    } else {
                        formatTranscript(
                            messages = allMessages,
                            replyReferencesByMessage = replyReferencesByMessage,
                        )
                    }
                GeminiPromptBuilder.formatExpandedDialogue(
                    earlierContent = earlierTranscript,
                    currentContent = baselineTranscript,
                )
            } else {
                NO_FURTHER_HISTORY_MESSAGE
            }
        }

        return PreparedConversationSlice(
            summaryInput =
                SummaryInput(
                    content = formattedContent,
                    url = enrichment.primaryUrl,
                    webUrls = enrichment.classifiedUrls.webUrls,
                    originalUrls = enrichment.classifiedUrls.allOriginalUrls,
                    videoUri = enrichment.videoUri,
                    videoDurationSeconds = null,
                    imageAttachments = sliceImageAttachments,
                ),
            primaryUrl = enrichment.primaryUrl,
            durationExceeded = false,
            lookbackProvider = lookbackProvider,
        )
    }

    private fun normalizeChronologicalOrder(messages: List<Message>): List<Message> {
        if (messages.size < 2) return messages
        val firstTime = runCatching { messages.first().timestamp }.getOrNull()
        val lastTime = runCatching { messages.last().timestamp }.getOrNull()
        return if (firstTime != null && lastTime != null && firstTime > lastTime) {
            messages.reversed()
        } else {
            val firstId = runCatching { messages.first().id }.getOrNull()
            val lastId = runCatching { messages.last().id }.getOrNull()
            if (firstId != null && lastId != null && firstId > lastId) {
                messages.reversed()
            } else {
                messages
            }
        }
    }

    private fun isEarlierThan(
        msg: Message,
        baseline: Message,
    ): Boolean {
        val timeOrder = runCatching { msg.timestamp < baseline.timestamp }.getOrNull()
        return timeOrder ?: (msg.id != baseline.id && msg.id.value < baseline.id.value)
    }

    private suspend fun shouldStopTraversal(
        msg: Message,
        referenceMessage: Message,
        accumulatedLength: Int,
        forward: Boolean = false,
    ): Boolean {
        if (isBotSummaryMessage(msg)) {
            val direction = if (forward) "forward traversal" else "history backtracking"
            logger.debug("Encountered bot summary message {}, stopping {}", msg.id, direction)
            return true
        }

        val elapsed =
            if (forward) {
                runCatching { msg.timestamp - referenceMessage.timestamp }.getOrNull()
            } else {
                runCatching { referenceMessage.timestamp - msg.timestamp }.getOrNull()
            }
        if (elapsed != null && elapsed > inactivityGap) {
            val direction = if (forward) "forward traversal" else "history backtracking"
            logger.debug("Inactivity gap {} exceeds {}, stopping {}", elapsed, inactivityGap, direction)
            return true
        }

        if (accumulatedLength + msg.content.length > maxCharacterBudget) {
            val direction = if (forward) "forward traversal" else "history backtracking"
            logger.debug(
                "Accumulated character budget {} exceeded with message {}, stopping {}",
                maxCharacterBudget,
                msg.id,
                direction,
            )
            return true
        }

        return false
    }

    internal suspend fun retrieveContextMessagesWithHistory(targetMessage: Message): Pair<List<Message>, List<Message>> {
        val rawHistory =
            runCatching {
                historyFetcher(targetMessage, maxHistoryMessages)
            }.getOrElse { e ->
                logger.warn("Failed to retrieve message history for {}: {}", targetMessage.id, e.message)
                emptyList()
            }

        if (rawHistory.isEmpty()) {
            return emptyList<Message>() to emptyList<Message>()
        }

        val chronological = normalizeChronologicalOrder(rawHistory).takeLast(maxHistoryMessages)

        val acceptedPreceding = ArrayDeque<Message>()
        var referenceMessage = targetMessage
        var accumulatedLength = targetMessage.content.length
        var stopIndex = -1

        for (i in chronological.lastIndex downTo 0) {
            val msg = chronological[i]

            if (shouldStopTraversal(msg, referenceMessage, accumulatedLength, forward = false)) {
                stopIndex = i
                break
            }

            acceptedPreceding.addFirst(msg)
            accumulatedLength += msg.content.length
            referenceMessage = msg
        }

        val unconsumed =
            if (stopIndex >= 0) {
                chronological.take(stopIndex + 1)
            } else {
                emptyList()
            }

        return acceptedPreceding.toList() to unconsumed
    }

    internal suspend fun retrieveForwardMessages(
        targetMessage: Message,
        remainingBudget: Int,
        initialAccumulatedLength: Int,
    ): List<Message> {
        if (remainingBudget <= 0) return emptyList()

        val rawForward =
            runCatching {
                forwardFetcher(targetMessage, remainingBudget)
            }.getOrElse { e ->
                logger.warn("Failed to retrieve forward messages for {}: {}", targetMessage.id, e.message)
                emptyList()
            }

        if (rawForward.isEmpty()) return emptyList()

        val chronological = normalizeChronologicalOrder(rawForward).take(remainingBudget)
        val acceptedSubsequent = mutableListOf<Message>()
        var referenceMessage = targetMessage
        var accumulatedLength = initialAccumulatedLength

        for (msg in chronological) {
            if (shouldStopTraversal(msg, referenceMessage, accumulatedLength, forward = true)) {
                break
            }

            acceptedSubsequent.add(msg)
            accumulatedLength += msg.content.length
            referenceMessage = msg
        }

        return acceptedSubsequent
    }

    internal suspend fun retrieveContextMessages(targetMessage: Message): List<Message> = retrieveContextMessagesWithHistory(targetMessage).first

    internal suspend fun retrievePrecedingMessages(targetMessage: Message): List<Message> =
        retrieveContextMessages(targetMessage).filterNot { isNoiseMessage(it) }

    internal suspend fun retrieveEarlierMessages(
        earliestBaselineMessage: Message,
        fallbackHistory: List<Message> = emptyList(),
    ): List<Message> {
        val fetchedHistory =
            runCatching {
                historyFetcher(earliestBaselineMessage, maxLookbackMessages)
            }.getOrElse { e ->
                logger.warn("Failed to fetch earlier message history for {}: {}", earliestBaselineMessage.id, e.message)
                emptyList()
            }

        val normalizedFetched = normalizeChronologicalOrder(fetchedHistory)
        val candidateFromFetched = normalizedFetched.filter { isEarlierThan(it, earliestBaselineMessage) }
        val rawCandidates =
            if (candidateFromFetched.isNotEmpty()) {
                candidateFromFetched
            } else {
                fallbackHistory.filter { isEarlierThan(it, earliestBaselineMessage) }
            }

        if (rawCandidates.isEmpty()) {
            return emptyList()
        }

        val acceptedEarlier = ArrayDeque<Message>()
        val latestEarlier = rawCandidates.last()

        if (isBotSummaryMessage(latestEarlier)) {
            logger.debug("Encountered bot summary message {} at start of lookback, halting", latestEarlier.id)
            return emptyList()
        }

        acceptedEarlier.addFirst(latestEarlier)
        var referenceMessage = latestEarlier
        var accumulatedLength = latestEarlier.content.length

        for (i in rawCandidates.size - 2 downTo 0) {
            if (acceptedEarlier.size >= maxLookbackMessages) {
                logger.debug("Reached maximum lookback message limit {}", maxLookbackMessages)
                break
            }

            val msg = rawCandidates[i]

            if (shouldStopTraversal(msg, referenceMessage, accumulatedLength, forward = false)) {
                break
            }

            acceptedEarlier.addFirst(msg)
            accumulatedLength += msg.content.length
            referenceMessage = msg
        }

        return acceptedEarlier.filterNot { isNoiseMessage(it) }
    }

    companion object {
        const val DEFAULT_MAX_HISTORY_MESSAGES = 50
        val DEFAULT_INACTIVITY_GAP = 15.minutes
        const val DEFAULT_MAX_CHARACTER_BUDGET = 20000
        const val DEFAULT_MAX_IMAGES = 10
        const val DEFAULT_MAX_LOOKBACK_MESSAGES = 20
        const val NO_FURTHER_HISTORY_MESSAGE = "查無更早的歷史訊息。"
    }
}
