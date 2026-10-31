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
        logger.info("SummaryService registered successfully with Summary Reaction emoji: {}", configuredEmoji ?: DEFAULT_EMOJI)
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

        if (message.author?.isBot == true) {
            logger.debug("Ignoring reaction on message authored by bot {}", message.author?.username)
            statusCache.remove(event.messageId)
            return
        }

        if (message.content.isBlank()) {
            logger.debug("Ignoring reaction on empty message {}", event.messageId)
            statusCache.remove(event.messageId)
            return
        }

        val url = URL_REGEX.find(message.content)?.value
        val precedingMessages = retrieveContextMessages(message)
        val summaryInput =
            if (precedingMessages.isNotEmpty()) {
                val allMessages = precedingMessages + message
                val formattedContent =
                    allMessages.joinToString("\n") { msg ->
                        "[${msg.author?.username ?: "Unknown"}]: ${msg.content}"
                    }
                SummaryInput(content = formattedContent)
            } else if (url != null) {
                val post = runCatching { peekResolver(url) }.getOrNull()
                if (post != null) {
                    val commentary = message.content.replace(url, "").trim()
                    val postContent = post.content.ifBlank { message.content }
                    val combinedContent =
                        if (commentary.isNotBlank() && post.content.isNotBlank()) {
                            "使用者附註: $commentary\n\n內容:\n$postContent"
                        } else {
                            postContent
                        }
                    SummaryInput(
                        content = combinedContent,
                        title = post.title,
                        comments = post.comments.map { it.content },
                        url = url,
                    )
                } else {
                    SummaryInput(
                        content = message.content,
                        url = url,
                    )
                }
            } else {
                SummaryInput(content = message.content)
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
                                "hasUrl" to (url != null),
                                "url" to (url ?: ""),
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
                historyFetcher(targetMessage, MAX_HISTORY_MESSAGES)
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
            }.takeLast(MAX_HISTORY_MESSAGES)

        // Bounded backtracking starting from targetMessage
        val acceptedPreceding = ArrayDeque<Message>()
        var referenceMessage = targetMessage

        for (i in chronological.lastIndex downTo 0) {
            val msg = chronological[i]

            // Stop if an earlier message sent by a bot is encountered
            if (msg.author?.isBot == true) {
                logger.debug("Encountered bot message {}, stopping history backtracking", msg.id)
                break
            }

            // Stop if elapsed time between two consecutive messages exceeds 15 minutes
            val elapsed =
                runCatching {
                    referenceMessage.timestamp - msg.timestamp
                }.getOrNull()

            if (elapsed != null && elapsed > MAX_INACTIVITY_GAP) {
                logger.debug("Inactivity gap {} exceeds {}, stopping history backtracking", elapsed, MAX_INACTIVITY_GAP)
                break
            }

            acceptedPreceding.addFirst(msg)
            referenceMessage = msg
        }

        return acceptedPreceding.toList()
    }

    companion object {
        const val DEFAULT_EMOJI = "📝"
        const val DISCORD_MAX_MESSAGE_LENGTH = 2000
        const val MAX_HISTORY_MESSAGES = 20
        val MAX_INACTIVITY_GAP = 15.minutes

        val URL_REGEX = Regex("""https?://[^\s<>"']+""")
    }
}
