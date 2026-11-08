package luna.summarizer

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.socialpeek.SocialPeek
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import kotlinx.coroutines.flow.toList
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
    val maxHistoryMessages: Int = resolveMaxHistoryMessages(),
    val inactivityGap: Duration = resolveInactivityGap(),
    val maxCharacterBudget: Int = resolveMaxCharacterBudget(),
    val maxImages: Int = resolveMaxImages(),
    val maxLookbackMessages: Int = resolveMaxLookbackMessages(),
    val maxImageBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
    val noiseFilter: DialogueNoiseFilter = DialogueNoiseFilter(),
    val peekResolver: suspend (String) -> PeekPost? = { SocialPeek.peekOrNull(it) },
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
        runCatching {
            msg.referencedMessage
                ?: msg.messageReference?.data?.id?.value?.let { refId ->
                    msg.channel.getMessageOrNull(refId)
                }
        }.getOrNull()
    },
) {
    private val logger = LoggerFactory.getLogger(ConversationSliceCollector::class.java)

    fun clearCache() {
        noiseFilter.clearWebhookCache()
    }

    internal suspend fun isNoiseMessage(message: Message): Boolean = noiseFilter.isNoiseMessage(message)

    internal suspend fun isBotSummaryMessage(message: Message): Boolean = noiseFilter.isBotSummaryMessage(message)

    internal fun extractImageAttachments(message: Message): List<ChatImageAttachment> {
        val attachments = runCatching { message.attachments }.getOrElse { emptySet() }
        if (attachments.isEmpty()) return emptyList()

        return attachments.mapNotNull { attachment ->
            val size = runCatching { attachment.size }.getOrNull()
            if (size != null && size > maxImageBytes) {
                return@mapNotNull null
            }
            val contentType = runCatching { attachment.contentType }.getOrNull()
            val filename = runCatching { attachment.filename }.getOrNull()
            val resolvedMimeType = resolveImageMimeType(contentType, filename)
            if (resolvedMimeType != null) {
                val url = runCatching { attachment.url }.getOrNull() ?: return@mapNotNull null
                ChatImageAttachment(
                    url = url,
                    mimeType = resolvedMimeType,
                    filename = filename,
                )
            } else {
                null
            }
        }
    }

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
        val primaryUrl = allMessages.asReversed().firstNotNullOfOrNull { msg -> URL_REGEX.find(msg.content)?.value }

        val targetImages = extractImageAttachments(message)
        val retainedTargetImages = targetImages.take(maxImages)
        val remainingCapacity = kotlin.math.max(0, maxImages - retainedTargetImages.size)

        val otherMessages = precedingMessages + subsequentMessages
        val otherMessagesWithImages = otherMessages.map { msg -> msg to extractImageAttachments(msg) }
        val retainedOtherByMessage = mutableMapOf<Snowflake, List<ChatImageAttachment>>()
        var capacityLeft = remainingCapacity

        for ((msg, images) in otherMessagesWithImages.asReversed()) {
            if (capacityLeft <= 0 || images.isEmpty()) {
                retainedOtherByMessage[msg.id] = emptyList()
                continue
            }
            if (images.size <= capacityLeft) {
                retainedOtherByMessage[msg.id] = images
                capacityLeft -= images.size
            } else {
                retainedOtherByMessage[msg.id] = images.takeLast(capacityLeft)
                capacityLeft = 0
            }
        }

        val messagesWithImages =
            allMessages.map { msg ->
                val retained =
                    if (msg.id == message.id) {
                        retainedTargetImages
                    } else {
                        retainedOtherByMessage[msg.id].orEmpty()
                    }
                msg to retained
            }
        val sliceImageAttachments = messagesWithImages.flatMap { it.second }

        val replyReferencesByMessage = resolveExternalReplyReferences(allMessages)

        val formattedContent =
            if (allMessages.size > 1) {
                val imagesMap = messagesWithImages.toMap().mapKeys { it.key.id }
                val (formatted, _) =
                    formatDialogueMessages(
                        messages = allMessages,
                        imagesByMessage = imagesMap,
                        replyReferencesByMessage = replyReferencesByMessage,
                    )
                formatted
            } else {
                val embedText = formatEmbedPreviews(message)
                if (sliceImageAttachments.isNotEmpty() || embedText.isNotEmpty() || replyReferencesByMessage.isNotEmpty()) {
                    formatDialogueMessages(
                        messages = listOf(message),
                        imagesByMessage = mapOf(message.id to sliceImageAttachments),
                        replyReferencesByMessage = replyReferencesByMessage,
                    ).first
                } else {
                    message.content
                }
            }

        if (primaryUrl != null) {
            val post =
                runCatching { peekResolver(primaryUrl) }
                    .onFailure { e ->
                        logger.warn("Failed to resolve URL $primaryUrl via peekResolver, falling back to message context", e)
                    }.getOrNull()

            if (post != null) {
                val durationSeconds = post.rawData["durationSeconds"]?.toLongOrNull()
                if (post.platform == Platform.YOUTUBE && durationSeconds != null && durationSeconds > 7200L) {
                    return PreparedConversationSlice(
                        summaryInput = SummaryInput(content = ""),
                        primaryUrl = primaryUrl,
                        durationExceeded = true,
                    )
                }

                val postContent = post.content.ifBlank { "" }.trim()
                val combinedContent =
                    if (allMessages.size == 1 && sliceImageAttachments.isEmpty()) {
                        val commentary = message.content.replace(primaryUrl, "").trim()
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
                            title = post.title,
                            comments = post.comments.map { it.content },
                            url = primaryUrl,
                            videoUri = if (post.platform == Platform.YOUTUBE) (post.cleanUrl.ifBlank { primaryUrl }) else null,
                            videoDurationSeconds = durationSeconds,
                            imageAttachments = sliceImageAttachments,
                        ),
                    primaryUrl = primaryUrl,
                    durationExceeded = false,
                )
            } else {
                logger.debug("No post resolved for primary URL {}, summarizing message context directly", primaryUrl)
            }
        }

        val lookbackProvider: suspend (reason: String) -> String? = { reason ->
            val earliestBaseline = allMessages.first()
            val earlierMessages =
                retrieveEarlierMessages(
                    earliestBaselineMessage = earliestBaseline,
                    fallbackHistory = unconsumedHistory,
                )

            if (earlierMessages.isNotEmpty()) {
                val earlierReferences = resolveExternalReplyReferences(earlierMessages)
                val (earlierTranscript, _) =
                    formatDialogueMessages(
                        messages = earlierMessages,
                        startingImageIndex = 1,
                        replyReferencesByMessage = earlierReferences,
                    )
                val baselineTranscript =
                    if (allMessages.size > 1) {
                        formattedContent
                    } else {
                        formatDialogueMessages(
                            messages = allMessages,
                            startingImageIndex = 1,
                            replyReferencesByMessage = replyReferencesByMessage,
                        ).first
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
                    url = primaryUrl,
                    imageAttachments = sliceImageAttachments,
                ),
            primaryUrl = primaryUrl,
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

    internal suspend fun resolveExternalReplyReferences(
        messages: List<Message>,
        maxExternalReferences: Int = 3,
    ): Map<Snowflake, Message> {
        val windowMessageIds = messages.map { it.id }.toSet()
        val result = mutableMapOf<Snowflake, Message>()
        val resolvedExternalIds = mutableSetOf<Snowflake>()

        for (msg in messages) {
            val ref = runCatching { referenceFetcher(msg) }.getOrNull() ?: continue
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

    internal fun formatDialogueMessages(
        messages: List<Message>,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        startingImageIndex: Int = 1,
        replyReferencesByMessage: Map<Snowflake, Message> = emptyMap(),
    ): Pair<String, Int> {
        var imageIndex = startingImageIndex
        val formatted =
            messages.joinToString("\n") { msg ->
                val baseAuthor = resolveAuthorName(msg)
                val refMsg = replyReferencesByMessage[msg.id]
                val author =
                    if (refMsg != null) {
                        val refAuthor = resolveAuthorName(refMsg)
                        val rawContent = refMsg.content.trim().replace("\n", " ")
                        val excerpt =
                            if (rawContent.length > 40) {
                                "${rawContent.take(37)}..."
                            } else {
                                rawContent
                            }
                        "$baseAuthor 回覆 $refAuthor「$excerpt」"
                    } else {
                        baseAuthor
                    }
                val embedText = formatEmbedPreviews(msg)
                val images = imagesByMessage[msg.id] ?: extractImageAttachments(msg)
                if (images.isNotEmpty()) {
                    val markers =
                        images.map { img ->
                            val idx = imageIndex++
                            if (!img.filename.isNullOrBlank()) {
                                "[附帶圖片 $idx: ${img.filename}]"
                            } else {
                                "[附帶圖片 $idx]"
                            }
                        }
                    val markersText = markers.joinToString(" ")
                    val trimmedContent = msg.content.trim()
                    val parts =
                        listOfNotNull(
                            trimmedContent.takeIf { it.isNotEmpty() },
                            embedText.takeIf { it.isNotEmpty() },
                            markersText.takeIf { it.isNotEmpty() },
                        )
                    if (parts.isNotEmpty()) {
                        "[$author]: ${parts.joinToString(" ")}"
                    } else {
                        "[$author]:"
                    }
                } else if (embedText.isNotEmpty()) {
                    val trimmedContent = msg.content.trim()
                    if (trimmedContent.isNotEmpty()) {
                        "[$author]: $trimmedContent $embedText"
                    } else {
                        "[$author]: $embedText"
                    }
                } else {
                    "[$author]: ${msg.content}"
                }
            }
        return formatted to imageIndex
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

    internal fun resolveAuthorName(message: Message): String =
        message.author?.username
            ?: runCatching { message.data.author.username }.getOrNull()
            ?: "Unknown"

    companion object {
        const val DEFAULT_MAX_HISTORY_MESSAGES = 50
        val DEFAULT_INACTIVITY_GAP = 15.minutes
        const val DEFAULT_MAX_CHARACTER_BUDGET = 20000
        const val DEFAULT_MAX_IMAGE_BYTES = 10 * 1024 * 1024
        const val DEFAULT_MAX_IMAGES = 10
        const val DEFAULT_MAX_LOOKBACK_MESSAGES = 20
        const val NO_FURTHER_HISTORY_MESSAGE = "查無更早的歷史訊息。"

        val SUPPORTED_IMAGE_MIME_TYPES =
            setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
            )

        fun resolveImageMimeType(
            contentType: String?,
            filename: String? = null,
        ): String? {
            val normalizedContentType =
                contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()

            if (normalizedContentType != null && normalizedContentType in SUPPORTED_IMAGE_MIME_TYPES) {
                return normalizedContentType
            }

            return null
        }

        fun resolveMaxHistoryMessages(env: Map<String, String> = emptyMap()): Int =
            env["SUMMARY_MAX_HISTORY_MESSAGES"]
                ?.toIntOrNull()
                ?.coerceIn(1, 100)
                ?: DEFAULT_MAX_HISTORY_MESSAGES

        fun resolveInactivityGap(env: Map<String, String> = emptyMap()): Duration =
            env["SUMMARY_INACTIVITY_GAP_MINUTES"]
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.minutes
                ?: DEFAULT_INACTIVITY_GAP

        fun resolveMaxCharacterBudget(env: Map<String, String> = emptyMap()): Int =
            env["SUMMARY_MAX_CHARACTER_BUDGET"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_MAX_CHARACTER_BUDGET

        fun resolveMaxLookbackMessages(env: Map<String, String> = emptyMap()): Int =
            env["SUMMARY_MAX_LOOKBACK_MESSAGES"]
                ?.toIntOrNull()
                ?.coerceIn(1, DEFAULT_MAX_LOOKBACK_MESSAGES)
                ?: DEFAULT_MAX_LOOKBACK_MESSAGES

        fun resolveMaxImages(env: Map<String, String> = emptyMap()): Int =
            env["SUMMARY_MAX_IMAGES"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_MAX_IMAGES

        val URL_REGEX = Regex("""https?://[^\s<>"']+""")
    }
}
