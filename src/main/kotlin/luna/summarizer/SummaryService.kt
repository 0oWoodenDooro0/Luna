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
import luna.core.JsonLogger
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.LinkedHashMap

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
        val summaryInput =
            if (url != null) {
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

    companion object {
        const val DEFAULT_EMOJI = "📝"
        const val DISCORD_MAX_MESSAGE_LENGTH = 2000

        val URL_REGEX = Regex("""https?://[^\s<>"']+""")
    }
}
