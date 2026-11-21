package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Reusable in-memory test fake for [ChatterChannelPort] with single-channel defaults,
 * one-line fault injectors, direct projections, virtual-time event logs, and optional hooks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FakeChatterChannelPort : ChatterChannelPort {
    sealed interface ChannelEvent {
        val channelId: Snowflake
        val anchorMessageId: Snowflake
        val virtualTimeMs: Long

        data class FetchedBefore(
            override val channelId: Snowflake,
            override val anchorMessageId: Snowflake,
            val limit: Int,
            override val virtualTimeMs: Long,
        ) : ChannelEvent

        data class FetchedAfter(
            override val channelId: Snowflake,
            override val anchorMessageId: Snowflake,
            val limit: Int,
            override val virtualTimeMs: Long,
        ) : ChannelEvent

        data class TypingTriggered(
            override val channelId: Snowflake,
            override val anchorMessageId: Snowflake,
            override val virtualTimeMs: Long,
        ) : ChannelEvent

        data class MessageSent(
            override val channelId: Snowflake,
            override val anchorMessageId: Snowflake,
            val content: String,
            override val virtualTimeMs: Long,
        ) : ChannelEvent

        data class ReplySent(
            override val channelId: Snowflake,
            override val anchorMessageId: Snowflake,
            val content: String,
            override val virtualTimeMs: Long,
        ) : ChannelEvent

        data class ReactionAdded(
            override val channelId: Snowflake,
            override val anchorMessageId: Snowflake,
            val emoji: ReactionEmoji,
            override val virtualTimeMs: Long,
        ) : ChannelEvent

        data class ListedGuildEmojis(
            override val channelId: Snowflake,
            override val anchorMessageId: Snowflake,
            override val virtualTimeMs: Long,
        ) : ChannelEvent
    }

    data class SentMessage(
        val channelId: Snowflake,
        val anchorMessageId: Snowflake,
        val content: String,
        val virtualTimeMs: Long,
    )

    data class SentReply(
        val channelId: Snowflake,
        val targetMessageId: Snowflake,
        val content: String,
        val virtualTimeMs: Long,
    )

    data class AddedReaction(
        val channelId: Snowflake,
        val targetMessageId: Snowflake,
        val emoji: ReactionEmoji,
        val virtualTimeMs: Long,
    )

    data class FetchRequest(
        val channelId: Snowflake,
        val anchorMessageId: Snowflake,
        val limit: Int,
    )

    var historyMessages: List<Message> = emptyList()
    var afterMessages: List<Message> = emptyList()
    var guildEmojis: List<GuildEmoji> = emptyList()

    var historyError: Throwable? = null
    var afterMessagesError: Throwable? = null
    var typingError: Throwable? = null
    var sendError: Throwable? = null
    var replyError: Throwable? = null
    var reactionError: Throwable? = null
    var guildEmojisError: Throwable? = null

    var onFetchBefore: (suspend (anchor: Message, limit: Int) -> List<Message>)? = null
    var onFetchAfter: (suspend (anchor: Message, limit: Int) -> List<Message>)? = null
    var onTriggerTyping: (suspend (anchor: Message) -> Unit)? = null
    var onSendMessage: (suspend (anchor: Message, content: String) -> Unit)? = null
    var onReplyTo: (suspend (target: Message, content: String) -> Unit)? = null
    var onAddReaction: (suspend (target: Message, emoji: ReactionEmoji) -> Unit)? = null
    var onListGuildCustomEmojis: (suspend (anchor: Message) -> List<GuildEmoji>)? = null

    private val lock = Any()
    private val _events = mutableListOf<ChannelEvent>()
    private val _sentMessages = mutableListOf<SentMessage>()
    private val _sentReplies = mutableListOf<SentReply>()
    private val _allDeliveredTexts = mutableListOf<String>()
    private val _addedReactions = mutableListOf<AddedReaction>()
    private val _fetchBeforeRequests = mutableListOf<FetchRequest>()
    private var _typingCount: Int = 0

    val events: List<ChannelEvent>
        get() = synchronized(lock) { _events.toList() }

    val sentMessages: List<SentMessage>
        get() = synchronized(lock) { _sentMessages.toList() }

    val sentReplies: List<SentReply>
        get() = synchronized(lock) { _sentReplies.toList() }

    val allDeliveredTexts: List<String>
        get() = synchronized(lock) { _allDeliveredTexts.toList() }

    val addedReactions: List<AddedReaction>
        get() = synchronized(lock) { _addedReactions.toList() }

    val typingCount: Int
        get() = synchronized(lock) { _typingCount }

    val fetchBeforeRequests: List<FetchRequest>
        get() = synchronized(lock) { _fetchBeforeRequests.toList() }

    val recordedBeforeLimits: List<Int>
        get() = synchronized(lock) { _fetchBeforeRequests.map { it.limit } }

    fun events(channelId: Snowflake): List<ChannelEvent> = synchronized(lock) { _events.filter { it.channelId == channelId } }

    fun typingCount(channelId: Snowflake): Int =
        synchronized(lock) {
            _events.count { it is ChannelEvent.TypingTriggered && it.channelId == channelId }
        }

    override suspend fun fetchMessagesBefore(
        anchor: Message,
        limit: Int,
    ): List<Message> {
        val timeMs = currentVirtualTimeMs()
        synchronized(lock) {
            _fetchBeforeRequests += FetchRequest(anchor.channelId, anchor.id, limit)
            _events += ChannelEvent.FetchedBefore(anchor.channelId, anchor.id, limit, timeMs)
        }
        if (limit <= 0) return emptyList()
        historyError?.let { throw it }
        onFetchBefore?.let { return it.invoke(anchor, limit) }
        return historyMessages.filter { it.id != anchor.id }.takeLast(limit)
    }

    override suspend fun fetchMessagesAfter(
        anchor: Message,
        limit: Int,
    ): List<Message> {
        val timeMs = currentVirtualTimeMs()
        synchronized(lock) {
            _events += ChannelEvent.FetchedAfter(anchor.channelId, anchor.id, limit, timeMs)
        }
        if (limit <= 0) return emptyList()
        afterMessagesError?.let { throw it }
        onFetchAfter?.let { return it.invoke(anchor, limit) }
        return afterMessages.filter { it.id != anchor.id }.take(limit)
    }

    override suspend fun triggerTyping(anchor: Message) {
        val timeMs = currentVirtualTimeMs()
        synchronized(lock) {
            _typingCount += 1
            _events += ChannelEvent.TypingTriggered(anchor.channelId, anchor.id, timeMs)
        }
        typingError?.let { throw it }
        onTriggerTyping?.invoke(anchor)
    }

    override suspend fun sendMessage(
        anchor: Message,
        content: String,
    ) {
        val timeMs = currentVirtualTimeMs()
        sendError?.let { throw it }
        onSendMessage?.invoke(anchor, content)
        synchronized(lock) {
            _sentMessages += SentMessage(anchor.channelId, anchor.id, content, timeMs)
            _allDeliveredTexts += content
            _events += ChannelEvent.MessageSent(anchor.channelId, anchor.id, content, timeMs)
        }
    }

    override suspend fun sendReply(
        target: Message,
        content: String,
    ) {
        val timeMs = currentVirtualTimeMs()
        replyError?.let { throw it }
        onReplyTo?.invoke(target, content)
        synchronized(lock) {
            _sentReplies += SentReply(target.channelId, target.id, content, timeMs)
            _allDeliveredTexts += content
            _events += ChannelEvent.ReplySent(target.channelId, target.id, content, timeMs)
        }
    }

    override suspend fun addReaction(
        target: Message,
        emoji: ReactionEmoji,
    ) {
        val timeMs = currentVirtualTimeMs()
        reactionError?.let { throw it }
        onAddReaction?.invoke(target, emoji)
        synchronized(lock) {
            _addedReactions += AddedReaction(target.channelId, target.id, emoji, timeMs)
            _events += ChannelEvent.ReactionAdded(target.channelId, target.id, emoji, timeMs)
        }
    }

    override suspend fun listGuildCustomEmojis(anchor: Message): List<GuildEmoji> {
        val timeMs = currentVirtualTimeMs()
        synchronized(lock) {
            _events += ChannelEvent.ListedGuildEmojis(anchor.channelId, anchor.id, timeMs)
        }
        guildEmojisError?.let { throw it }
        val source = onListGuildCustomEmojis?.invoke(anchor) ?: guildEmojis
        return source.filter { it.isAvailable && !it.name.isNullOrBlank() }
    }

    private suspend fun currentVirtualTimeMs(): Long = currentCoroutineContext()[TestCoroutineScheduler]?.currentTime ?: 0L
}
