package luna.chatter

import dev.kord.common.entity.Snowflake
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Snapshot of a channel's current `Conversation Momentum` state.
 *
 * @property active `true` when momentum is within the active window and below `maxTurns`.
 * @property withinWindow `true` when Luna has replied within the configured momentum window.
 * @property ambientTurnCount Number of consecutive ambient turns recorded in the active window.
 */
data class MomentumState(
    val active: Boolean,
    val withinWindow: Boolean,
    val ambientTurnCount: Int,
) {
    companion object {
        val INACTIVE = MomentumState(active = false, withinWindow = false, ambientTurnCount = 0)
    }
}

/**
 * Thread-safe LRU bounded cache tracking per-channel `Conversation Momentum` state.
 *
 * Enforces a strict upper bound ([maxEntries], default 500) to adhere to
 * CODING_STANDARDS.md §1 daemon state bounds, preventing unbounded memory growth.
 */
class BoundedChannelMomentumCache(
    val maxEntries: Int = DEFAULT_MAX_CAPACITY,
    val defaultWindowMs: Long = DEFAULT_WINDOW_MS,
    val defaultMaxTurns: Int = DEFAULT_MAX_TURNS,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val DEFAULT_MAX_CAPACITY = 500
        const val DEFAULT_WINDOW_SECONDS = 30L
        const val DEFAULT_WINDOW_MS = DEFAULT_WINDOW_SECONDS * 1_000L
        const val DEFAULT_MAX_TURNS = 3
    }

    private data class MomentumEntry(
        val lastReplyAtMs: Long,
        val ambientTurnCount: Int,
    )

    private val cache: MutableMap<Snowflake, MomentumEntry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Snowflake, MomentumEntry>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, MomentumEntry>?): Boolean = size > maxEntries
            },
        )

    /**
     * Queries the current [MomentumState] for [channelId] at [now].
     */
    fun stateFor(
        channelId: Snowflake,
        windowMs: Long = defaultWindowMs,
        maxTurns: Int = defaultMaxTurns,
        now: Long = timeSource(),
    ): MomentumState {
        if (windowMs <= 0L || maxTurns <= 0) {
            return MomentumState.INACTIVE
        }
        val entry = synchronized(cache) { cache[channelId] } ?: return MomentumState.INACTIVE
        val elapsed = now - entry.lastReplyAtMs
        if (elapsed >= windowMs) {
            return MomentumState.INACTIVE
        }
        return MomentumState(
            active = entry.ambientTurnCount < maxTurns,
            withinWindow = true,
            ambientTurnCount = entry.ambientTurnCount,
        )
    }

    /**
     * Returns `true` when [channelId] has active, non-exhausted `Conversation Momentum`.
     */
    fun isActive(
        channelId: Snowflake,
        windowMs: Long = defaultWindowMs,
        maxTurns: Int = defaultMaxTurns,
        now: Long = timeSource(),
    ): Boolean = stateFor(channelId = channelId, windowMs = windowMs, maxTurns = maxTurns, now = now).active

    /**
     * Records a delivered reply for [channelId].
     *
     * Direct `@mention` / reply-to-Luna deliveries start or refresh the window with `ambientTurnCount = 0`.
     * Ambient replies start at `1` on cold start or expired window, and increment while within [windowMs].
     */
    fun recordReply(
        channelId: Snowflake,
        isDirectMention: Boolean,
        windowMs: Long = defaultWindowMs,
        maxTurns: Int = defaultMaxTurns,
        timestamp: Long = timeSource(),
    ) {
        if (windowMs <= 0L || maxTurns <= 0) {
            return
        }
        synchronized(cache) {
            if (isDirectMention) {
                cache[channelId] = MomentumEntry(lastReplyAtMs = timestamp, ambientTurnCount = 0)
            } else {
                val existing = cache[channelId]
                val nextCount =
                    if (existing == null || timestamp - existing.lastReplyAtMs >= windowMs) {
                        1
                    } else {
                        existing.ambientTurnCount + 1
                    }
                cache[channelId] = MomentumEntry(lastReplyAtMs = timestamp, ambientTurnCount = nextCount)
            }
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }

    val size: Int
        get() = synchronized(cache) { cache.size }
}
