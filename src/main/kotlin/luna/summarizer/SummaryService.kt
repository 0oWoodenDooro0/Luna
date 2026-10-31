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
    private val noiseFilter: DialogueNoiseFilter = DialogueNoiseFilter(),
    private val historyFetcher: suspend (message: Message, limit: Int) -> List<Message> = { message, limit ->
        message.channel
            .getMessagesBefore(message.id, limit = limit)
            .toList()
            .reversed()
    },
) {
    private val logger = LoggerFactory.getLogger(SummaryService::class.java)
    private val statusCache = BoundedStatusCache()

    fun register(kord: Kord) {
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

        val precedingMessages = retrieveContextMessages(message).filterNot { isNoiseMessage(it) }
        val allMessages =
            if (precedingMessages.isNotEmpty()) {
                precedingMessages + message
            } else {
                listOf(message)
            }
        val primaryUrl = allMessages.asReversed().firstNotNullOfOrNull { msg -> URL_REGEX.find(msg.content)?.value }

        val formattedContent =
            if (precedingMessages.isNotEmpty()) {
                allMessages.joinToString("\n") { msg ->
                    "[${msg.author?.username ?: "Unknown"}]: ${msg.content}"
                }
            } else {
                message.content
            }

        val summaryInput =
            if (primaryUrl != null) {
                val post = runCatching { peekResolver(primaryUrl) }.getOrNull()
                if (post != null) {
                    val durationSeconds = post.rawData["durationSeconds"]?.toLongOrNull()
                    if (post.platform == Platform.YOUTUBE && durationSeconds != null && durationSeconds > 7200L) {
                        statusCache[event.messageId] = SummaryStatus.DONE
                        replySender(message, "此影片長度超過 2 小時上限，為避免運算逾時，請選擇較短的片段或精華進行摘要。")
                        return
                    }

                    val postContent = post.content.ifBlank { "" }.trim()
                    val combinedContent =
                        if (precedingMessages.isEmpty()) {
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
                    )
                } else {
                    SummaryInput(
                        content = formattedContent,
                        url = primaryUrl,
                    )
                }
            } else {
                SummaryInput(content = formattedContent)
            }

        try {
            logger.info("Executing Gemini summarization for message {}", event.messageId)
            val result = summarizer.summarize(summaryInput)
            when (result) {
                is SummaryResult.Success -> {
                    statusCache[event.messageId] = SummaryStatus.DONE
                    val textToSend = truncateForDiscord(result.text)
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

    private suspend fun retrieveContextMessages(targetMessage: Message): List<Message> {
        val rawHistory =
            runCatching {
                historyFetcher(targetMessage, maxHistoryMessages)
            }.getOrElse { e ->
                logger.warn("Failed to retrieve message history for {}: {}", targetMessage.id, e.message)
                emptyList()
            }

        if (rawHistory.isEmpty()) {
            return emptyList()
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

        for (i in chronological.lastIndex downTo 0) {
            val msg = chronological[i]

            // Stop if an earlier message sent by the bot containing a summary is encountered
            if (isBotSummaryMessage(msg)) {
                logger.debug("Encountered bot summary message {}, stopping history backtracking", msg.id)
                break
            }

            // Stop if elapsed time between two consecutive messages exceeds inactivity gap
            val elapsed =
                runCatching {
                    referenceMessage.timestamp - msg.timestamp
                }.getOrNull()

            if (elapsed != null && elapsed > inactivityGap) {
                logger.debug("Inactivity gap {} exceeds {}, stopping history backtracking", elapsed, inactivityGap)
                break
            }

            // Stop if accumulated character budget exceeded
            if (accumulatedLength + msg.content.length > maxCharacterBudget) {
                logger.debug(
                    "Accumulated character budget {} exceeded with message {}, stopping history backtracking",
                    maxCharacterBudget,
                    msg.id,
                )
                break
            }

            acceptedPreceding.addFirst(msg)
            accumulatedLength += msg.content.length
            referenceMessage = msg
        }

        return acceptedPreceding.toList()
    }

    fun isBotSummaryMessage(message: Message): Boolean = noiseFilter.isBotSummaryMessage(message)

    fun isNoiseMessage(message: Message): Boolean = noiseFilter.isNoiseMessage(message)

    companion object {
        const val DEFAULT_EMOJI = "📝"
        const val DISCORD_MAX_MESSAGE_LENGTH = 2000
        const val DEFAULT_MAX_HISTORY_MESSAGES = 50
        const val MAX_HISTORY_MESSAGES = DEFAULT_MAX_HISTORY_MESSAGES
        val DEFAULT_INACTIVITY_GAP = 15.minutes
        val MAX_INACTIVITY_GAP = DEFAULT_INACTIVITY_GAP
        const val DEFAULT_MAX_CHARACTER_BUDGET = 20000

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

        val URL_REGEX = Regex("""https?://[^\s<>"']+""")
    }
}
