package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import java.util.LinkedHashMap

/**
 * Thread-safe LRU-bounded per-channel tracker regulating custom emoji cadence in text replies
 * and cooldown / recent-selection diversity in the `Silent Reaction Path`.
 *
 * Enforces capacity bounds per CODING_STANDARDS.md §1 to prevent unbounded daemon memory growth.
 */
class BoundedChannelEmojiCadenceTracker(
    val maxEntries: Int = DEFAULT_MAX_CAPACITY,
    val replyCooldownTurns: Int = DEFAULT_REPLY_EMOJI_COOLDOWN_TURNS,
    val replyCooldownWindowMs: Long = DEFAULT_REPLY_EMOJI_COOLDOWN_WINDOW_MS,
    val reactionCooldownMs: Long = DEFAULT_REACTION_COOLDOWN_MS,
    val maxRecentReactions: Int = DEFAULT_MAX_RECENT_REACTIONS,
    val recentReactionWindowMs: Long = DEFAULT_RECENT_REACTION_WINDOW_MS,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    private val normalizedMaxEntries: Int = maxEntries.coerceAtLeast(1)
    private val normalizedReplyCooldownTurns: Int = replyCooldownTurns.coerceAtLeast(0)
    private val normalizedReplyCooldownWindowMs: Long = replyCooldownWindowMs.coerceAtLeast(0L)
    private val normalizedReactionCooldownMs: Long = reactionCooldownMs.coerceAtLeast(0L)
    private val normalizedMaxRecentReactions: Int = maxRecentReactions.coerceAtLeast(0)
    private val normalizedRecentReactionWindowMs: Long = recentReactionWindowMs.coerceAtLeast(0L)

    private val lock = Any()
    private val states: MutableMap<Snowflake, ChannelEmojiCadenceState> =
        object : LinkedHashMap<Snowflake, ChannelEmojiCadenceState>(normalizedMaxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, ChannelEmojiCadenceState>?): Boolean = size > normalizedMaxEntries
        }

    /**
     * Returns `true` when [channelId] is permitted to receive the guild custom emoji pool for reply generation.
     */
    fun shouldAllowReplyCustomEmojis(
        channelId: Snowflake,
        now: Long = timeSource(),
    ): Boolean =
        synchronized(lock) {
            if (normalizedReplyCooldownTurns == 0) return true
            val state = states[channelId] ?: return true
            if (state.remainingReplyEmojiCooldownTurns <= 0) return true

            val lastUsedAt = state.lastReplyEmojiAtMs
            if (normalizedReplyCooldownWindowMs > 0L &&
                lastUsedAt != null &&
                (now - lastUsedAt) >= normalizedReplyCooldownWindowMs
            ) {
                state.remainingReplyEmojiCooldownTurns = 0
                return true
            }
            false
        }

    /**
     * Records a delivered reply for [channelId], starting a turn/time cooldown when [deliveredText]
     * contains a Discord custom emoji token, or counting down an active cooldown on pure-text replies.
     */
    fun recordDeliveredReply(
        channelId: Snowflake,
        deliveredText: String,
        now: Long = timeSource(),
    ) {
        val hasCustomEmoji = ChatterEmojiTranscriptAnnotator.extractEmojiTokens(deliveredText).isNotEmpty()
        synchronized(lock) {
            if (hasCustomEmoji) {
                val state = states.getOrPut(channelId) { ChannelEmojiCadenceState() }
                state.remainingReplyEmojiCooldownTurns = normalizedReplyCooldownTurns
                state.lastReplyEmojiAtMs = now
                return
            }

            val state = states[channelId] ?: return
            if (state.remainingReplyEmojiCooldownTurns <= 0) return

            val lastUsedAt = state.lastReplyEmojiAtMs
            if (normalizedReplyCooldownWindowMs > 0L &&
                lastUsedAt != null &&
                (now - lastUsedAt) >= normalizedReplyCooldownWindowMs
            ) {
                state.remainingReplyEmojiCooldownTurns = 0
            } else {
                state.remainingReplyEmojiCooldownTurns = (state.remainingReplyEmojiCooldownTurns - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Returns `true` while [channelId] is inside the `Silent Reaction Path` cooldown window.
     */
    fun isReactionOnCooldown(
        channelId: Snowflake,
        now: Long = timeSource(),
    ): Boolean =
        synchronized(lock) {
            if (normalizedReactionCooldownMs <= 0L) return false
            val lastReactionAt = states[channelId]?.lastReactionAtMs ?: return false
            (now - lastReactionAt) < normalizedReactionCooldownMs
        }

    /**
     * Returns chronologically ordered non-expired reaction emojis recently delivered in [channelId].
     */
    fun recentReactions(
        channelId: Snowflake,
        now: Long = timeSource(),
    ): List<String> =
        synchronized(lock) {
            if (normalizedMaxRecentReactions == 0) return emptyList()
            val state = states[channelId] ?: return emptyList()
            pruneExpiredReactions(state.recentReactions, now)
            state.recentReactions.map { it.emoji }
        }

    /**
     * Records a delivered silent reaction emoji for [channelId], updating reaction cooldown and history.
     */
    fun recordDeliveredReaction(
        channelId: Snowflake,
        emoji: String,
        now: Long = timeSource(),
    ) {
        val clean = emoji.trim()
        if (clean.isEmpty()) return

        synchronized(lock) {
            val state = states.getOrPut(channelId) { ChannelEmojiCadenceState() }
            state.lastReactionAtMs = now
            if (normalizedMaxRecentReactions > 0) {
                pruneExpiredReactions(state.recentReactions, now)
                state.recentReactions.addLast(ReactionHistoryEntry(emoji = clean, timestampMs = now))
                while (state.recentReactions.size > normalizedMaxRecentReactions) {
                    state.recentReactions.removeFirst()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            states.clear()
        }
    }

    val size: Int
        get() = synchronized(lock) { states.size }

    private fun pruneExpiredReactions(
        entries: ArrayDeque<ReactionHistoryEntry>,
        now: Long,
    ) {
        if (normalizedRecentReactionWindowMs <= 0L) return
        while (entries.isNotEmpty() && (now - entries.first().timestampMs) >= normalizedRecentReactionWindowMs) {
            entries.removeFirst()
        }
    }

    private data class ReactionHistoryEntry(
        val emoji: String,
        val timestampMs: Long,
    )

    private class ChannelEmojiCadenceState {
        var remainingReplyEmojiCooldownTurns: Int = 0
        var lastReplyEmojiAtMs: Long? = null
        var lastReactionAtMs: Long? = null
        val recentReactions: ArrayDeque<ReactionHistoryEntry> = ArrayDeque()
    }

    companion object {
        const val DEFAULT_MAX_CAPACITY = 500
        const val DEFAULT_REPLY_EMOJI_COOLDOWN_TURNS = 2
        const val DEFAULT_REPLY_EMOJI_COOLDOWN_WINDOW_MS = 600_000L
        const val DEFAULT_REACTION_COOLDOWN_MS = 30_000L
        const val DEFAULT_MAX_RECENT_REACTIONS = 5
        const val DEFAULT_RECENT_REACTION_WINDOW_MS = 1_800_000L
    }
}
