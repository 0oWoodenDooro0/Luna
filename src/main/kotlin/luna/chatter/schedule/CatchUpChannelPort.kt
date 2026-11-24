package luna.chatter.schedule

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.MessageChannel
import kotlinx.coroutines.flow.toList

/**
 * Cohesive channel-history port for `Deferred Catch-Up` to fetch missed messages
 * from a persisted [CatchUpWatermark] without expanding [DeferredCatchUpService] with raw Kord lambdas.
 */
interface CatchUpChannelPort {
    /**
     * Fetches missed messages in [channelId] starting at [firstPendingMessageId] (inclusive),
     * bounded by [limit], in chronological oldest-first order.
     */
    suspend fun fetchMissedMessages(
        channelId: Snowflake,
        firstPendingMessageId: Snowflake,
        limit: Int,
    ): List<Message>
}

/**
 * Stateless thin Kord adapter implementing [CatchUpChannelPort].
 */
class KordCatchUpChannelAdapter(
    private val kord: Kord,
) : CatchUpChannelPort {
    override suspend fun fetchMissedMessages(
        channelId: Snowflake,
        firstPendingMessageId: Snowflake,
        limit: Int,
    ): List<Message> {
        if (limit <= 0) return emptyList()
        val channel = kord.getChannelOf<MessageChannel>(channelId) ?: return emptyList()
        val firstMessage = runCatching { channel.getMessageOrNull(firstPendingMessageId) }.getOrNull()
        val remainingLimit = if (firstMessage != null) limit - 1 else limit
        val afterMessages =
            if (remainingLimit > 0) {
                channel.getMessagesAfter(firstPendingMessageId, limit = remainingLimit).toList()
            } else {
                emptyList()
            }
        return buildList {
            if (firstMessage != null) add(firstMessage)
            addAll(afterMessages)
        }.distinctBy { it.id }
            .sortedBy { it.id }
            .takeLast(limit)
    }
}
