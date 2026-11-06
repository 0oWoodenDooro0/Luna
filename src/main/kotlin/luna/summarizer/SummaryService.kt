package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.reply
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.on
import dev.kord.rest.builder.message.allowedMentions
import dev.socialpeek.SocialPeek
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import kotlinx.coroutines.flow.toList
import luna.core.JsonLogger
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

enum class SummaryStatus {
    IN_PROGRESS,
    DONE,
    FAILED,
}

/**
 * Thread-safe bounded LRU cache to prevent unbounded memory growth over long bot lifecycles.
 */
class BoundedStatusCache(
    private val maxEntries: Int = 1000,
) {
    private val map =
        Collections.synchronizedMap(
            object : LinkedHashMap<Snowflake, SummaryStatus>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, SummaryStatus>?): Boolean = size > maxEntries
            },
        )

    fun putIfAbsent(
        key: Snowflake,
        value: SummaryStatus,
    ): SummaryStatus? = map.putIfAbsent(key, value)

    operator fun get(key: Snowflake): SummaryStatus? = map[key]

    operator fun set(
        key: Snowflake,
        value: SummaryStatus,
    ) {
        map[key] = value
    }

    fun remove(key: Snowflake): SummaryStatus? = map.remove(key)

    fun clear() {
        map.clear()
    }
}

class SummaryService(
    private val summarizer: GeminiSummarizer = GeminiSummarizer(),
    private val peekResolver: suspend (String) -> PeekPost? = { SocialPeek.peekOrNull(it) },
    private val replySender: suspend (message: Message, content: String) -> Unit = { message, content ->
        message.reply {
            this.messageReference = message.id
            this.content = content
            allowedMentions {
                repliedUser = false
            }
        }
    },
    val configuredEmoji: String? = System.getenv("SUMMARY_EMOJI")?.takeIf { it.isNotBlank() },
    val maxHistoryMessages: Int = resolveMaxHistoryMessages(),
    val inactivityGap: Duration = resolveInactivityGap(),
    val maxCharacterBudget: Int = resolveMaxCharacterBudget(),
    val maxImages: Int = resolveMaxImages(),
    val maxLookbackMessages: Int = resolveMaxLookbackMessages(),
    noiseFilter: DialogueNoiseFilter? = null,
    private val imageResolver: ChatImageResolver = ChatImageResolver(),
    private val historyFetcher: suspend (message: Message, limit: Int) -> List<Message> = { message, limit ->
        message.channel
            .getMessagesBefore(message.id, limit = limit)
            .toList()
            .reversed()
    },
    val isLunaWebhook: (suspend (Snowflake) -> Boolean)? = null,
    kord: Kord? = null,
) {
    private val logger = LoggerFactory.getLogger(SummaryService::class.java)
    private val statusCache = BoundedStatusCache()
    private var kordInstance: Kord? = kord

    val noiseFilter: DialogueNoiseFilter =
        noiseFilter ?: DialogueNoiseFilter(
            isLunaWebhook = isLunaWebhook,
            kord = kord,
        )

    fun register(kord: Kord) {
        this.kordInstance = kord
        noiseFilter.setKord(kord)
        kord.on<ReactionAddEvent> {
            handleReaction(this)
        }
        logger.info(
            "SummaryService registered successfully with Summary Reaction emoji: {}, maxHistory: {}, inactivityGap: {}m",
            configuredEmoji ?: DEFAULT_EMOJI,
            maxHistoryMessages,
            inactivityGap.inWholeMinutes,
        )
    }

    fun isSummaryEmoji(emoji: ReactionEmoji): Boolean {
        val name = emoji.name
        val configured = configuredEmoji?.trim()
        if (!configured.isNullOrBlank()) {
            if (name == configured || name.equals(configured.removeSurrounding(":"), ignoreCase = true)) {
                return true
            }
        }
        return name == DEFAULT_EMOJI ||
            name.equals(":memo:", ignoreCase = true) ||
            name.equals("memo", ignoreCase = true)
    }

    fun getStatus(messageId: Snowflake): SummaryStatus? = statusCache[messageId]

    fun clearCache() {
        statusCache.clear()
        noiseFilter.clearWebhookCache()
    }

    fun truncateForDiscord(
        text: String,
        maxLength: Int = DISCORD_MAX_MESSAGE_LENGTH,
    ): String {
        if (text.length <= maxLength) return text
        val ellipsis = "..."
        return text.take(maxLength - ellipsis.length) + ellipsis
    }

    suspend fun handleReaction(event: ReactionAddEvent) {
        if (!isSummaryEmoji(event.emoji)) {
            return
        }

        val reactingUser =
            try {
                event.getUserOrNull()
            } catch (e: Exception) {
                logger.warn("Could not retrieve reacting user {}: {}", event.userId, e.message)
                null
            }

        if (reactingUser?.isBot == true) {
            logger.debug("Ignoring reaction from bot user {}", event.userId)
            return
        }

        // Deduplication: atomically claim message summarization
        val prevStatus = statusCache.putIfAbsent(event.messageId, SummaryStatus.IN_PROGRESS)
        if (prevStatus != null) {
            logger.debug(
                "Message {} is already processed or in-progress (status: {}), skipping",
                event.messageId,
                prevStatus,
            )
            return
        }

        val message =
            try {
                event.getMessageOrNull()
            } catch (e: Exception) {
                logger.warn("Failed to retrieve message {}: {}", event.messageId, e.message)
                statusCache.remove(event.messageId)
                return
            }

        if (message == null) {
            logger.warn("Message {} was null or deleted", event.messageId)
            statusCache.remove(event.messageId)
            return
        }

        if (isNoiseMessage(message)) {
            logger.debug("Ignoring reaction on noise or bot message {}", event.messageId)
            statusCache.remove(event.messageId)
            return
        }

        val (precedingMessagesRaw, unconsumedHistory) = retrieveContextMessagesWithHistory(message)
        val precedingMessages = precedingMessagesRaw.filterNot { isNoiseMessage(it) }
        val allMessages =
            if (precedingMessages.isNotEmpty()) {
                precedingMessages + message
            } else {
                listOf(message)
            }
        val primaryUrl = allMessages.asReversed().firstNotNullOfOrNull { msg -> URL_REGEX.find(msg.content)?.value }

        val targetImages = extractImageAttachments(message)
        val retainedTargetImages = targetImages.take(maxImages)
        val remainingCapacity = kotlin.math.max(0, maxImages - retainedTargetImages.size)

        val precedingMessagesWithImages = precedingMessages.map { msg -> msg to extractImageAttachments(msg) }
        val retainedPrecedingByMessage = mutableMapOf<Snowflake, List<ChatImageAttachment>>()
        var capacityLeft = remainingCapacity

        for ((msg, images) in precedingMessagesWithImages.asReversed()) {
            if (capacityLeft <= 0 || images.isEmpty()) {
                retainedPrecedingByMessage[msg.id] = emptyList()
                continue
            }
            if (images.size <= capacityLeft) {
                retainedPrecedingByMessage[msg.id] = images
                capacityLeft -= images.size
            } else {
                retainedPrecedingByMessage[msg.id] = images.takeLast(capacityLeft)
                capacityLeft = 0
            }
        }

        val messagesWithImages =
            allMessages.map { msg ->
                val retained =
                    if (msg.id == message.id) {
                        retainedTargetImages
                    } else {
                        retainedPrecedingByMessage[msg.id].orEmpty()
                    }
                msg to retained
            }
        val sliceImageAttachments = messagesWithImages.flatMap { it.second }

        val formattedContent =
            if (precedingMessages.isNotEmpty()) {
                var imageIndex = 1
                messagesWithImages.joinToString("\n") { (msg, images) ->
                    val author = msg.author?.username ?: "Unknown"
                    val embedText = formatEmbedPreviews(msg)
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
            } else {
                val embedText = formatEmbedPreviews(message)
                if (sliceImageAttachments.isNotEmpty()) {
                    var singleMsgImageIndex = 1
                    val markers =
                        sliceImageAttachments.map { img ->
                            val idx = singleMsgImageIndex++
                            if (!img.filename.isNullOrBlank()) {
                                "[附帶圖片 $idx: ${img.filename}]"
                            } else {
                                "[附帶圖片 $idx]"
                            }
                        }
                    val markersText = markers.joinToString(" ")
                    val trimmedContent = message.content.trim()
                    val author = message.author?.username ?: "Unknown"
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
                    val trimmedContent = message.content.trim()
                    val author = message.author?.username ?: "Unknown"
                    if (trimmedContent.isNotEmpty()) {
                        "[$author]: $trimmedContent $embedText"
                    } else {
                        "[$author]: $embedText"
                    }
                } else {
                    message.content
                }
            }

        val summaryInput =
            if (primaryUrl != null) {
                val post =
                    runCatching { peekResolver(primaryUrl) }
                        .onFailure { e ->
                            logger.warn("Failed to resolve URL $primaryUrl via peekResolver, falling back to message context", e)
                        }.getOrNull()
                if (post != null) {
                    val durationSeconds = post.rawData["durationSeconds"]?.toLongOrNull()
                    if (post.platform == Platform.YOUTUBE && durationSeconds != null && durationSeconds > 7200L) {
                        statusCache[event.messageId] = SummaryStatus.DONE
                        replySender(message, "此影片長度超過 2 小時上限，為避免運算逾時，請選擇較短的片段或精華進行摘要。")
                        return
                    }

                    val postContent = post.content.ifBlank { "" }.trim()
                    val combinedContent =
                        if (precedingMessages.isEmpty() && sliceImageAttachments.isEmpty()) {
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
                    SummaryInput(
                        content = combinedContent,
                        title = post.title,
                        comments = post.comments.map { it.content },
                        url = primaryUrl,
                        videoUri = if (post.platform == Platform.YOUTUBE) (post.cleanUrl.ifBlank { primaryUrl }) else null,
                        videoDurationSeconds = durationSeconds,
                        imageAttachments = sliceImageAttachments,
                    )
                } else {
                    logger.debug("No post resolved for primary URL {}, summarizing message context directly", primaryUrl)
                    SummaryInput(
                        content = formattedContent,
                        url = primaryUrl,
                        imageAttachments = sliceImageAttachments,
                        tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                    )
                }
            } else {
                SummaryInput(
                    content = formattedContent,
                    imageAttachments = sliceImageAttachments,
                    tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
                )
            }

        try {
            logger.info("Executing Gemini summarization for message {}", event.messageId)
            val baselineInput = summaryInput
            var currentInput = baselineInput
            var result =
                try {
                    summarizer.summarize(currentInput)
                } catch (e: Exception) {
                    if (currentInput.tools != null) {
                        logger.warn(
                            "Gemini tool-enabled summarization failed for message {}: {}. Retrying with baseline fallback.",
                            event.messageId,
                            e.message,
                        )
                        currentInput = currentInput.copy(tools = null)
                        try {
                            summarizer.summarize(currentInput)
                        } catch (e2: Exception) {
                            if (currentInput.imageAttachments.isNotEmpty()) {
                                logger.warn(
                                    "Unexpected exception during multimodal summarization for message {}: {}. Retrying with text-only fallback.",
                                    event.messageId,
                                    e2.message,
                                )
                                currentInput = currentInput.copy(imageAttachments = emptyList())
                                summarizer.summarize(currentInput)
                            } else {
                                throw e2
                            }
                        }
                    } else if (currentInput.imageAttachments.isNotEmpty()) {
                        logger.warn(
                            "Unexpected exception during multimodal summarization for message {}: {}. Retrying with text-only fallback.",
                            event.messageId,
                            e.message,
                        )
                        currentInput = currentInput.copy(imageAttachments = emptyList())
                        summarizer.summarize(currentInput)
                    } else {
                        throw e
                    }
                }

            // Fallback if initial tool-enabled call returned Error
            if (result is SummaryResult.Error && currentInput.tools != null) {
                logger.warn(
                    "Tool-enabled summarization returned error for message {}: {}. Falling back to baseline summary.",
                    event.messageId,
                    result.message,
                )
                currentInput = currentInput.copy(tools = null)
                result =
                    try {
                        summarizer.summarize(currentInput)
                    } catch (e: Exception) {
                        if (currentInput.imageAttachments.isNotEmpty()) {
                            currentInput = currentInput.copy(imageAttachments = emptyList())
                            summarizer.summarize(currentInput)
                        } else {
                            throw e
                        }
                    }
            }

            // Orchestrate Two-Stage Context Lookback if Gemini invokes fetch_earlier_messages
            if (result is SummaryResult.ToolCall) {
                if (result.name == GeminiTools.FETCH_EARLIER_MESSAGES) {
                    val reason = result.args["reason"] ?: ""
                    logger.info(
                        "Gemini invoked fetch_earlier_messages for message {}: reason='{}'",
                        event.messageId,
                        reason,
                    )

                    val continuation = result.continuation
                    if (continuation != null) {
                        val lookbackResult =
                            try {
                                val earliestBaseline = allMessages.first()
                                val earlierMessages =
                                    retrieveEarlierMessages(
                                        earliestBaselineMessage = earliestBaseline,
                                        fallbackHistory = unconsumedHistory,
                                    )

                                val toolResponse =
                                    if (earlierMessages.isNotEmpty()) {
                                        val (earlierTranscript, _) =
                                            formatDialogueMessages(earlierMessages, startingImageIndex = 1)
                                        val baselineTranscript =
                                            if (precedingMessages.isNotEmpty()) {
                                                formattedContent
                                            } else {
                                                formatDialogueMessages(allMessages, startingImageIndex = 1).first
                                            }
                                        GeminiPromptBuilder.formatExpandedDialogue(
                                            earlierContent = earlierTranscript,
                                            currentContent = baselineTranscript,
                                        )
                                    } else {
                                        NO_FURTHER_HISTORY_MESSAGE
                                    }

                                continuation(toolResponse)
                            } catch (e: Exception) {
                                logger.warn(
                                    "Exception during lookback continuation for message {}: {}. Falling back to baseline.",
                                    event.messageId,
                                    e.message,
                                )
                                SummaryResult.Error.Generic("Lookback continuation failed: ${e.message}", cause = e)
                            }

                        // Enforce Single Lookback Limit (strictly at most 1 round)
                        if (lookbackResult is SummaryResult.Success) {
                            result = lookbackResult
                        } else {
                            logger.warn(
                                "Lookback continuation failed or exceeded round limit for message {}: {}. Falling back to baseline summary without tools.",
                                event.messageId,
                                lookbackResult,
                            )
                            currentInput = baselineInput.copy(tools = null)
                            result =
                                try {
                                    summarizer.summarize(currentInput)
                                } catch (e: Exception) {
                                    if (currentInput.imageAttachments.isNotEmpty()) {
                                        currentInput = currentInput.copy(imageAttachments = emptyList())
                                        summarizer.summarize(currentInput)
                                    } else {
                                        throw e
                                    }
                                }
                        }
                    } else {
                        logger.warn("No continuation provided for tool call on message {}, falling back to baseline summary", event.messageId)
                        currentInput = baselineInput.copy(tools = null)
                        result = summarizer.summarize(currentInput)
                    }
                } else {
                    logger.warn("Unhandled tool call {} for message {}, falling back to baseline summary", result.name, event.messageId)
                    currentInput = baselineInput.copy(tools = null)
                    result = summarizer.summarize(currentInput)
                }
            }

            if (result is SummaryResult.Error && currentInput.imageAttachments.isNotEmpty()) {
                logger.warn(
                    "Multimodal summarization with images failed for message {}: {}. Retrying with text-only fallback.",
                    event.messageId,
                    result.message,
                )
                currentInput = currentInput.copy(imageAttachments = emptyList())
                result = summarizer.summarize(currentInput)
            }

            when (result) {
                is SummaryResult.Success -> {
                    statusCache[event.messageId] = SummaryStatus.DONE
                    val sanitized = EmojiSanitizer.stripEmojis(result.text).trim()
                    val textToSend =
                        truncateForDiscord(sanitized).takeIf { it.isNotBlank() }
                            ?: FALLBACK_EMPTY_SUMMARY_MESSAGE
                    replySender(message, textToSend)
                    JsonLogger.log(
                        layer = "SUMMARY",
                        component = "SummaryService",
                        operation = "summarize",
                        data =
                            mapOf(
                                "messageId" to event.messageId.toString(),
                                "hasUrl" to (primaryUrl != null),
                                "url" to (primaryUrl ?: ""),
                                "summaryLength" to textToSend.length,
                            ),
                    )
                }

                is SummaryResult.Error -> {
                    // Evict from cache on transient errors to allow retry
                    statusCache.remove(event.messageId)
                    logger.warn("Summarization failed for message {}: {}", event.messageId, result.message)
                    JsonLogger.error(
                        layer = "SUMMARY",
                        component = "SummaryService",
                        operation = "summarize",
                        data =
                            mapOf(
                                "messageId" to event.messageId.toString(),
                                "error" to result.message,
                            ),
                        errorMessage = result.message,
                    )
                }

                is SummaryResult.ToolCall -> {
                    // Unhandled tool call in standard reaction pipeline
                    statusCache.remove(event.messageId)
                    logger.warn("Unhandled tool call received for message {}: {}", event.messageId, result.name)
                }
            }
        } catch (e: Exception) {
            // Evict from cache on unexpected exceptions to allow retry
            statusCache.remove(event.messageId)
            logger.error("Unexpected error in summary pipeline for message {}", event.messageId, e)
            JsonLogger.error(
                layer = "SUMMARY",
                component = "SummaryService",
                operation = "summarize",
                data = mapOf("messageId" to event.messageId.toString()),
                errorMessage = e.message,
            )
        }
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

        // Normalize to chronological order (oldest first, newest last)
        val chronological =
            if (rawHistory.size >= 2) {
                val firstTime = runCatching { rawHistory.first().timestamp }.getOrNull()
                val lastTime = runCatching { rawHistory.last().timestamp }.getOrNull()
                if (firstTime != null && lastTime != null && firstTime > lastTime) {
                    rawHistory.reversed()
                } else {
                    val firstId = runCatching { rawHistory.first().id }.getOrNull()
                    val lastId = runCatching { rawHistory.last().id }.getOrNull()
                    if (firstId != null && lastId != null && firstId > lastId) {
                        rawHistory.reversed()
                    } else {
                        rawHistory
                    }
                }
            } else {
                rawHistory
            }.takeLast(maxHistoryMessages)

        // Bounded backtracking starting from targetMessage
        val acceptedPreceding = ArrayDeque<Message>()
        var referenceMessage = targetMessage
        var accumulatedLength = targetMessage.content.length
        var stopIndex = -1

        for (i in chronological.lastIndex downTo 0) {
            val msg = chronological[i]

            // Stop if an earlier message sent by the bot containing a summary is encountered
            if (isBotSummaryMessage(msg)) {
                logger.debug("Encountered bot summary message {}, stopping history backtracking", msg.id)
                stopIndex = i
                break
            }

            // Stop if elapsed time between two consecutive messages exceeds inactivity gap
            val elapsed =
                runCatching {
                    referenceMessage.timestamp - msg.timestamp
                }.getOrNull()

            if (elapsed != null && elapsed > inactivityGap) {
                logger.debug("Inactivity gap {} exceeds {}, stopping history backtracking", elapsed, inactivityGap)
                stopIndex = i
                break
            }

            // Stop if accumulated character budget exceeded
            if (accumulatedLength + msg.content.length > maxCharacterBudget) {
                logger.debug(
                    "Accumulated character budget {} exceeded with message {}, stopping history backtracking",
                    maxCharacterBudget,
                    msg.id,
                )
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

        val normalizedFetched =
            if (fetchedHistory.size >= 2) {
                val firstTime = runCatching { fetchedHistory.first().timestamp }.getOrNull()
                val lastTime = runCatching { fetchedHistory.last().timestamp }.getOrNull()
                if (firstTime != null && lastTime != null && firstTime > lastTime) {
                    fetchedHistory.reversed()
                } else {
                    val firstId = runCatching { fetchedHistory.first().id }.getOrNull()
                    val lastId = runCatching { fetchedHistory.last().id }.getOrNull()
                    if (firstId != null && lastId != null && firstId > lastId) {
                        fetchedHistory.reversed()
                    } else {
                        fetchedHistory
                    }
                }
            } else {
                fetchedHistory
            }

        val candidateFromFetched =
            normalizedFetched.filter { msg ->
                val timeOrder =
                    runCatching { msg.timestamp < earliestBaselineMessage.timestamp }.getOrNull()
                if (timeOrder != null) {
                    timeOrder
                } else {
                    msg.id != earliestBaselineMessage.id && msg.id.value < earliestBaselineMessage.id.value
                }
            }

        val rawCandidates =
            if (candidateFromFetched.isNotEmpty()) {
                candidateFromFetched
            } else {
                fallbackHistory.filter { msg ->
                    val timeOrder =
                        runCatching { msg.timestamp < earliestBaselineMessage.timestamp }.getOrNull()
                    if (timeOrder != null) {
                        timeOrder
                    } else {
                        msg.id != earliestBaselineMessage.id && msg.id.value < earliestBaselineMessage.id.value
                    }
                }
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

            if (isBotSummaryMessage(msg)) {
                logger.debug("Encountered bot summary message {}, stopping lookback backtracking", msg.id)
                break
            }

            val elapsed =
                runCatching {
                    referenceMessage.timestamp - msg.timestamp
                }.getOrNull()

            if (elapsed != null && elapsed > inactivityGap) {
                logger.debug("Another inactivity gap {} exceeds {}, stopping lookback backtracking", elapsed, inactivityGap)
                break
            }

            if (accumulatedLength + msg.content.length > maxCharacterBudget) {
                logger.debug("Accumulated character budget exceeded in lookback with message {}", msg.id)
                break
            }

            acceptedEarlier.addFirst(msg)
            accumulatedLength += msg.content.length
            referenceMessage = msg
        }

        return acceptedEarlier.filterNot { isNoiseMessage(it) }
    }

    internal fun formatDialogueMessages(
        messages: List<Message>,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        startingImageIndex: Int = 1,
    ): Pair<String, Int> {
        var imageIndex = startingImageIndex
        val formatted =
            messages.joinToString("\n") { msg ->
                val author = msg.author?.username ?: "Unknown"
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

    fun extractImageAttachments(message: Message): List<ChatImageAttachment> = imageResolver.extractImageAttachments(message)

    suspend fun isBotSummaryMessage(message: Message): Boolean = noiseFilter.isBotSummaryMessage(message)

    suspend fun isNoiseMessage(message: Message): Boolean = noiseFilter.isNoiseMessage(message)

    companion object {
        const val DEFAULT_EMOJI = "📝"
        const val DISCORD_MAX_MESSAGE_LENGTH = 2000
        const val DEFAULT_MAX_HISTORY_MESSAGES = 50
        const val MAX_HISTORY_MESSAGES = DEFAULT_MAX_HISTORY_MESSAGES
        val DEFAULT_INACTIVITY_GAP = 15.minutes
        val MAX_INACTIVITY_GAP = DEFAULT_INACTIVITY_GAP
        const val DEFAULT_MAX_CHARACTER_BUDGET = 20000
        const val DEFAULT_MAX_IMAGE_BYTES = ChatImageResolver.DEFAULT_MAX_IMAGE_BYTES
        const val FALLBACK_EMPTY_SUMMARY_MESSAGE = "抱歉，AI 無法為此內容生成有效摘要，請嘗試提供更多上下文或選擇其他內容。"

        val SUPPORTED_IMAGE_MIME_TYPES = ChatImageResolver.SUPPORTED_IMAGE_MIME_TYPES

        fun resolveImageMimeType(
            contentType: String?,
            filename: String? = null,
        ): String? = ChatImageResolver.resolveImageMimeType(contentType, filename)

        fun resolveMaxHistoryMessages(env: Map<String, String> = System.getenv()): Int =
            env["SUMMARY_MAX_HISTORY_MESSAGES"]
                ?.toIntOrNull()
                ?.coerceIn(1, 100)
                ?: DEFAULT_MAX_HISTORY_MESSAGES

        fun resolveInactivityGap(env: Map<String, String> = System.getenv()): Duration =
            env["SUMMARY_INACTIVITY_GAP_MINUTES"]
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.minutes
                ?: DEFAULT_INACTIVITY_GAP

        fun resolveMaxCharacterBudget(env: Map<String, String> = System.getenv()): Int =
            env["SUMMARY_MAX_CHARACTER_BUDGET"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_MAX_CHARACTER_BUDGET

        const val DEFAULT_MAX_IMAGES = 10
        const val DEFAULT_MAX_LOOKBACK_MESSAGES = 20
        const val MAX_LOOKBACK_MESSAGES = DEFAULT_MAX_LOOKBACK_MESSAGES
        const val NO_FURTHER_HISTORY_MESSAGE = "查無更早的歷史訊息。"

        fun resolveMaxLookbackMessages(env: Map<String, String> = System.getenv()): Int =
            env["SUMMARY_MAX_LOOKBACK_MESSAGES"]
                ?.toIntOrNull()
                ?.coerceIn(1, 50)
                ?: DEFAULT_MAX_LOOKBACK_MESSAGES

        fun resolveMaxImages(env: Map<String, String> = System.getenv()): Int =
            env["SUMMARY_MAX_IMAGES"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_MAX_IMAGES

        val URL_REGEX = Regex("""https?://[^\s<>"']+""")
    }
}
