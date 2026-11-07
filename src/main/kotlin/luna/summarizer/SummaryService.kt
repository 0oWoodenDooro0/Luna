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

    fun register(kord: Kord) {
        this.kordInstance = kord
        collector.setKord(kord)
        kord.on<ReactionAddEvent> {
            handleReaction(this)
        }
        logger.info(
            "SummaryService registered successfully with Summary Reaction emoji: {}, maxHistory: {}, inactivityGap: {}m",
            configuredEmoji ?: DEFAULT_EMOJI,
            collector.maxHistoryMessages,
            collector.inactivityGap.inWholeMinutes,
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
        collector.clearCache()
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
            val result = summarizer.summarize(slice.summaryInput, slice.lookbackProvider)

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

    companion object {
        const val DEFAULT_EMOJI = "📝"
        const val DISCORD_MAX_MESSAGE_LENGTH = 2000
        const val YOUTUBE_DURATION_EXCEEDED_MESSAGE = "此影片長度超過 2 小時上限，為避免運算逾時，請選擇較短的片段或精華進行摘要。"
        const val FALLBACK_EMPTY_SUMMARY_MESSAGE = "抱歉，AI 無法為此內容生成有效摘要，請嘗試提供更多上下文或選擇其他內容。"
    }
}
