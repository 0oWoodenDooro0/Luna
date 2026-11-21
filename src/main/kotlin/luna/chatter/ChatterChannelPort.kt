package luna.chatter

import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.reply
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import kotlinx.coroutines.flow.toList

/**
 * Cohesive Discord channel I/O port consolidating all per-message operations for Chatter.
 */
interface ChatterChannelPort {
    /**
     * Fetches up to [limit] messages before [anchor] in chronological oldest-first order.
     * Returns an empty list when [limit] <= 0.
     */
    suspend fun fetchMessagesBefore(
        anchor: Message,
        limit: Int,
    ): List<Message>

    /**
     * Fetches up to [limit] messages strictly after [anchor].
     * Returns an empty list when [limit] <= 0.
     */
    suspend fun fetchMessagesAfter(
        anchor: Message,
        limit: Int,
    ): List<Message>

    /**
     * Emits a Discord typing indicator in [anchor]'s channel.
     */
    suspend fun triggerTyping(anchor: Message)

    /**
     * Sends a plain message with [content] to [anchor]'s channel.
     */
    suspend fun sendMessage(
        anchor: Message,
        content: String,
    )

    /**
     * Sends a Discord reply to [target] with `messageReference` anchored to [target]'s ID.
     */
    suspend fun sendReply(
        target: Message,
        content: String,
    )

    /**
     * Adds [emoji] as a reaction on [target].
     */
    suspend fun addReaction(
        target: Message,
        emoji: ReactionEmoji,
    )

    /**
     * Lists available guild custom emojis with non-blank names for [anchor]'s guild,
     * or returns an empty list outside guilds.
     */
    suspend fun listGuildCustomEmojis(anchor: Message): List<GuildEmoji>
}

/**
 * Stateless thin Kord transport adapter implementing [ChatterChannelPort].
 *
 * Propagates all exceptions unchanged without catching, logging, or swallowing [kotlinx.coroutines.CancellationException].
 */
object KordChatterChannelAdapter : ChatterChannelPort {
    override suspend fun fetchMessagesBefore(
        anchor: Message,
        limit: Int,
    ): List<Message> {
        if (limit <= 0) return emptyList()
        return anchor.channel
            .getMessagesBefore(anchor.id, limit = limit)
            .toList()
            .reversed()
    }

    override suspend fun fetchMessagesAfter(
        anchor: Message,
        limit: Int,
    ): List<Message> {
        if (limit <= 0) return emptyList()
        return anchor.channel
            .getMessagesAfter(anchor.id, limit = limit)
            .toList()
            .filter { it.id != anchor.id }
    }

    override suspend fun triggerTyping(anchor: Message) {
        anchor.channel.type()
    }

    override suspend fun sendMessage(
        anchor: Message,
        content: String,
    ) {
        anchor.channel.createMessage(content)
    }

    override suspend fun sendReply(
        target: Message,
        content: String,
    ) {
        target.reply {
            this.messageReference = target.id
            this.content = content
        }
    }

    override suspend fun addReaction(
        target: Message,
        emoji: ReactionEmoji,
    ) {
        target.addReaction(emoji)
    }

    override suspend fun listGuildCustomEmojis(anchor: Message): List<GuildEmoji> {
        val guild = anchor.getGuildOrNull() ?: return emptyList()
        return guild.emojis.toList().filter { it.isAvailable && !it.name.isNullOrBlank() }
    }
}
