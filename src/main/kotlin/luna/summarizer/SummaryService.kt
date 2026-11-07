package luna.summarizer

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
import kotlinx.coroutines.flow.toList
import luna.core.JsonLogger
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.time.Duration

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

/**
 * Lean event coordinator responsible for listening to Discord Summary Reactions,
 * dispatching to [ConversationSliceCollector] and [GeminiSummarizer], and delivering formatted summaries.
 */
class SummaryService(
    val summarizer: GeminiSummarizer = GeminiSummarizer(),
    val collector: ConversationSliceCollector = ConversationSliceCollector(),
    val configuredEmoji: String? = null,
    private val replySender: suspend (message: Message, content: String) -> Unit = { message, content ->
        message.reply {
            this.messageReference = message.id
            this.content = content
            allowedMentions {
                repliedUser = false
            }
        }
    },
    kord: Kord? = null,
) {
    private val logger = LoggerFactory.getLogger(SummaryService::class.java)
    private val statusCache = BoundedStatusCache()
    private var kordInstance: Kord? = kord

    constructor(
        summarizer: GeminiSummarizer = GeminiSummarizer(),
        peekResolver: suspend (String) -> PeekPost? = { SocialPeek.peekOrNull(it) },
        replySender: suspend (message: Message, content: String) -> Unit = { message, content ->
            message.reply {
                this.messageReference = message.id
                this.content = content
                allowedMentions {
                    repliedUser = false
                }
            }
        },
        configuredEmoji: String? = null,
        maxHistoryMessages: Int = ConversationSliceCollector.DEFAULT_MAX_HISTORY_MESSAGES,
        inactivityGap: Duration = ConversationSliceCollector.DEFAULT_INACTIVITY_GAP,
        maxCharacterBudget: Int = ConversationSliceCollector.DEFAULT_MAX_CHARACTER_BUDGET,
        maxImages: Int = ConversationSliceCollector.DEFAULT_MAX_IMAGES,
        maxLookbackMessages: Int = ConversationSliceCollector.DEFAULT_MAX_LOOKBACK_MESSAGES,
        noiseFilter: DialogueNoiseFilter? = null,
        imageResolver: Any? = null,
        historyFetcher: suspend (message: Message, limit: Int) -> List<Message> = { message, limit ->
            message.channel
                .getMessagesBefore(message.id, limit = limit)
                .toList()
                .reversed()
        },
        isLunaWebhook: (suspend (Snowflake) -> Boolean)? = null,
        kord: Kord? = null,
    ) : this(
        summarizer = summarizer,
        collector =
            ConversationSliceCollector(
                maxHistoryMessages = maxHistoryMessages,
                inactivityGap = inactivityGap,
                maxCharacterBudget = maxCharacterBudget,
                maxImages = maxImages,
                maxLookbackMessages = maxLookbackMessages,
                noiseFilter = noiseFilter ?: DialogueNoiseFilter(isLunaWebhook = isLunaWebhook, kord = kord),
                peekResolver = peekResolver,
                historyFetcher = historyFetcher,
            ),
        configuredEmoji = configuredEmoji,
        replySender = replySender,
        kord = kord,
    )

    val maxHistoryMessages: Int get() = collector.maxHistoryMessages
    val inactivityGap: Duration get() = collector.inactivityGap
    val maxCharacterBudget: Int get() = collector.maxCharacterBudget
    val maxImages: Int get() = collector.maxImages
    val maxLookbackMessages: Int get() = collector.maxLookbackMessages
    val noiseFilter: DialogueNoiseFilter get() = collector.noiseFilter

    fun register(kord: Kord) {
        this.kordInstance = kord
        collector.noiseFilter.setKord(kord)
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
        collector.noiseFilter.clearWebhookCache()
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

        val slice =
            try {
                collector.collectSlice(message)
            } catch (e: Exception) {
                logger.warn("Error collecting conversation slice for message {}: {}", event.messageId, e.message)
                statusCache.remove(event.messageId)
                return
            }

        if (slice == null) {
            statusCache.remove(event.messageId)
            return
        }

        if (slice.durationExceeded) {
            statusCache[event.messageId] = SummaryStatus.DONE
            replySender(message, YOUTUBE_DURATION_EXCEEDED_MESSAGE)
            return
        }

        try {
            logger.info("Executing Gemini summarization for message {}", event.messageId)
            var currentInput = slice.summaryInput
            var result: SummaryResult =
                try {
                    summarizer.summarize(currentInput)
                } catch (e: Exception) {
                    if (currentInput.imageAttachments.isNotEmpty()) {
                        logger.warn(
                            "Unexpected exception during multimodal summarization for message {}: {}. Retrying with text-only fallback.",
                            event.messageId,
                            e.message,
                        )
                        currentInput = currentInput.copy(imageAttachments = emptyList(), tools = null)
                        try {
                            summarizer.summarize(currentInput)
                        } catch (e2: Exception) {
                            SummaryResult.Error.Generic("Text-only fallback failed: ${e2.message}", cause = e2)
                        }
                    } else if (currentInput.tools != null) {
                        logger.warn(
                            "Gemini tool-enabled summarization failed for message {}: {}. Retrying with baseline fallback.",
                            event.messageId,
                            e.message,
                        )
                        currentInput = currentInput.copy(tools = null)
                        try {
                            summarizer.summarize(currentInput)
                        } catch (e2: Exception) {
                            throw e2
                        }
                    } else {
                        throw e
                    }
                }

            if (result is SummaryResult.Error) {
                if (currentInput.imageAttachments.isNotEmpty()) {
                    logger.warn(
                        "Multimodal summarization with images failed for message {}: {}. Retrying with text-only fallback.",
                        event.messageId,
                        result.message,
                    )
                    currentInput = currentInput.copy(imageAttachments = emptyList(), tools = null)
                    result =
                        try {
                            summarizer.summarize(currentInput)
                        } catch (e: Exception) {
                            SummaryResult.Error.Generic("Text-only fallback failed: ${e.message}", cause = e)
                        }
                } else if (currentInput.tools != null) {
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
                            SummaryResult.Error.Generic("Baseline fallback failed: ${e.message}", cause = e)
                        }
                }
            }

            if (result is SummaryResult.ToolCall && result.name == GeminiTools.FETCH_EARLIER_MESSAGES) {
                val reason = result.args["reason"] ?: ""
                val continuation = result.continuation
                if (continuation != null) {
                    val toolResponse =
                        slice.lookbackProvider?.invoke(reason)
                            ?: ConversationSliceCollector.NO_FURTHER_HISTORY_MESSAGE
                    val lookbackResult =
                        try {
                            continuation(toolResponse)
                        } catch (e: Exception) {
                            logger.warn("Continuation failed: {}", e.message)
                            null
                        }
                    if (lookbackResult is SummaryResult.Success) {
                        result = lookbackResult
                    } else {
                        currentInput = currentInput.copy(tools = null)
                        result = summarizer.summarize(currentInput)
                    }
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
                                "hasUrl" to (slice.primaryUrl != null),
                                "url" to (slice.primaryUrl ?: ""),
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

    suspend fun retrieveContextMessagesWithHistory(targetMessage: Message) = collector.retrieveContextMessagesWithHistory(targetMessage)

    suspend fun retrieveContextMessages(targetMessage: Message) = collector.retrieveContextMessages(targetMessage)

    suspend fun retrievePrecedingMessages(targetMessage: Message) = collector.retrievePrecedingMessages(targetMessage)

    suspend fun retrieveEarlierMessages(
        earliestBaselineMessage: Message,
        fallbackHistory: List<Message> = emptyList(),
    ) = collector.retrieveEarlierMessages(earliestBaselineMessage, fallbackHistory)

    fun formatDialogueMessages(
        messages: List<Message>,
        imagesByMessage: Map<Snowflake, List<ChatImageAttachment>> = emptyMap(),
        startingImageIndex: Int = 1,
    ) = collector.formatDialogueMessages(messages, imagesByMessage, startingImageIndex)

    fun formatEmbedPreviews(message: Message) = collector.formatEmbedPreviews(message)

    fun extractImageAttachments(message: Message): List<ChatImageAttachment> = collector.extractImageAttachments(message)

    fun resolveAuthorName(message: Message): String = collector.resolveAuthorName(message)

    suspend fun isBotSummaryMessage(message: Message): Boolean = collector.isBotSummaryMessage(message)

    suspend fun isNoiseMessage(message: Message): Boolean = collector.isNoiseMessage(message)

    companion object {
        const val DEFAULT_EMOJI = "📝"
        const val DISCORD_MAX_MESSAGE_LENGTH = 2000
        const val YOUTUBE_DURATION_EXCEEDED_MESSAGE = "此影片長度超過 2 小時上限，為避免運算逾時，請選擇較短的片段或精華進行摘要。"
        const val FALLBACK_EMPTY_SUMMARY_MESSAGE = "抱歉，AI 無法為此內容生成有效摘要，請嘗試提供更多上下文或選擇其他內容。"

        const val DEFAULT_MAX_HISTORY_MESSAGES = ConversationSliceCollector.DEFAULT_MAX_HISTORY_MESSAGES
        const val MAX_HISTORY_MESSAGES = DEFAULT_MAX_HISTORY_MESSAGES
        val DEFAULT_INACTIVITY_GAP = ConversationSliceCollector.DEFAULT_INACTIVITY_GAP
        val MAX_INACTIVITY_GAP = DEFAULT_INACTIVITY_GAP
        const val DEFAULT_MAX_CHARACTER_BUDGET = ConversationSliceCollector.DEFAULT_MAX_CHARACTER_BUDGET
        const val DEFAULT_MAX_IMAGE_BYTES = ConversationSliceCollector.DEFAULT_MAX_IMAGE_BYTES
        const val DEFAULT_MAX_IMAGES = ConversationSliceCollector.DEFAULT_MAX_IMAGES
        const val DEFAULT_MAX_LOOKBACK_MESSAGES = ConversationSliceCollector.DEFAULT_MAX_LOOKBACK_MESSAGES
        const val MAX_LOOKBACK_MESSAGES = DEFAULT_MAX_LOOKBACK_MESSAGES
        const val NO_FURTHER_HISTORY_MESSAGE = ConversationSliceCollector.NO_FURTHER_HISTORY_MESSAGE

        val SUPPORTED_IMAGE_MIME_TYPES = ConversationSliceCollector.SUPPORTED_IMAGE_MIME_TYPES

        fun resolveImageMimeType(
            contentType: String?,
            filename: String? = null,
        ): String? = ConversationSliceCollector.resolveImageMimeType(contentType, filename)

        fun resolveMaxHistoryMessages(env: Map<String, String> = emptyMap()): Int = ConversationSliceCollector.resolveMaxHistoryMessages(env)

        fun resolveInactivityGap(env: Map<String, String> = emptyMap()): Duration = ConversationSliceCollector.resolveInactivityGap(env)

        fun resolveMaxCharacterBudget(env: Map<String, String> = emptyMap()): Int = ConversationSliceCollector.resolveMaxCharacterBudget(env)

        fun resolveMaxLookbackMessages(env: Map<String, String> = emptyMap()): Int = ConversationSliceCollector.resolveMaxLookbackMessages(env)

        fun resolveMaxImages(env: Map<String, String> = emptyMap()): Int = ConversationSliceCollector.resolveMaxImages(env)

        val URL_REGEX = ConversationSliceCollector.URL_REGEX
    }
}
