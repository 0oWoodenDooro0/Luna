package luna.chatter

import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.Channel
import dev.kord.core.entity.channel.ForumChannel
import dev.kord.core.entity.channel.TopGuildChannel
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.core.entity.channel.thread.ThreadChannel
import org.slf4j.LoggerFactory

/**
 * Resolves and sanitizes Discord channel/thread spatial metadata into a `[頻道脈絡]` block.
 */
internal object ChatterChannelContextResolver {
    const val MAX_TOPIC_LENGTH: Int = 120
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val logger = LoggerFactory.getLogger(ChatterChannelContextResolver::class.java)

    suspend fun resolveBlock(messages: List<Message>): String? {
        val anchor = messages.lastOrNull() ?: return null
        val channel =
            runCatching { anchor.channel.asChannelOrNull() }
                .onFailure { ex ->
                    logger.debug("Failed to resolve channel metadata for message {}: {}", anchor.id, ex.message)
                }.getOrNull()
                ?: return null
        return formatChannel(channel)
    }

    internal suspend fun formatChannel(channel: Channel): String? {
        val (location, rawTopic) =
            when (channel) {
                is ThreadChannel -> {
                    val threadTitle = sanitizeMetadataText(channel.name) ?: return null
                    val parent =
                        runCatching { channel.parent.asChannelOrNull() }
                            .onFailure { ex ->
                                logger.debug(
                                    "Failed to resolve parent channel for thread {}: {}",
                                    channel.id,
                                    ex.message,
                                )
                            }.getOrNull()
                    val parentName = (parent as? TopGuildChannel)?.name?.let(::sanitizeMetadataText)
                    val locationText =
                        if (parentName != null) {
                            "#$parentName > 討論串「$threadTitle」"
                        } else {
                            "討論串「$threadTitle」"
                        }
                    locationText to extractTopic(parent)
                }

                is TopGuildChannel -> {
                    val channelName = sanitizeMetadataText(channel.name) ?: return null
                    "#$channelName" to extractTopic(channel)
                }

                else -> {
                    return null
                }
            }

        val topic = truncateTopic(sanitizeMetadataText(rawTopic))
        return buildString {
            append("[頻道脈絡]\n")
            append("位置：").append(location)
            if (topic != null) {
                append("\n主題：").append(topic)
            }
        }
    }

    private fun extractTopic(channel: Channel?): String? =
        when (channel) {
            is TopGuildMessageChannel -> channel.topic
            is ForumChannel -> channel.topic
            else -> null
        }

    internal fun sanitizeMetadataText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val flattened =
            buildString(raw.length) {
                for (ch in raw) {
                    if (ch.isISOControl()) append(' ') else append(ch)
                }
            }
        return flattened.replace(WHITESPACE_REGEX, " ").trim().ifEmpty { null }
    }

    internal fun truncateTopic(sanitizedTopic: String?): String? {
        if (sanitizedTopic.isNullOrBlank()) return null
        if (sanitizedTopic.length <= MAX_TOPIC_LENGTH) return sanitizedTopic
        return sanitizedTopic.take(MAX_TOPIC_LENGTH - 3).trimEnd() + "..."
    }
}
