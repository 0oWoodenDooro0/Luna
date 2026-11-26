package luna.chatter

import dev.kord.common.entity.Snowflake
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.min

/**
 * Snapshot of a channel's current `Ambient Fatigue` state.
 *
 * @property count Number of uninvited `AMBIENT` text replies within the active fatigue window.
 * @property offset Computed threshold penalty (`min(maxOffset, count * step)`), or `0.0` when inactive.
 */
data class FatigueState(
    val count: Int,
    val offset: Double,
) {
    companion object {
        val ZERO = FatigueState(count = 0, offset = 0.0)
    }
}

/**
 * Thread-safe LRU bounded cache tracking per-channel `Ambient Fatigue` state.
 *
 * Enforces a strict upper bound ([maxEntries], default 500) to adhere to
 * CODING_STANDARDS.md §1 daemon state bounds, preventing unbounded memory growth.
 */
class BoundedChannelFatigueCache(
    val maxEntries: Int = DEFAULT_MAX_CAPACITY,
    val defaultWindowMs: Long = DEFAULT_WINDOW_MS,
    val defaultStep: Double = DEFAULT_STEP,
    val defaultMaxOffset: Double = DEFAULT_MAX_OFFSET,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val DEFAULT_MAX_CAPACITY = 500
        const val DEFAULT_WINDOW_SECONDS = 300L
        const val DEFAULT_WINDOW_MS = DEFAULT_WINDOW_SECONDS * 1_000L
        const val DEFAULT_STEP = 0.08
        const val DEFAULT_MAX_OFFSET = 0.24
    }

    private data class FatigueEntry(
        val lastAmbientAtMs: Long,
        val count: Int,
    )

    private val cache: MutableMap<Snowflake, FatigueEntry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Snowflake, FatigueEntry>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, FatigueEntry>?): Boolean = size > maxEntries
            },
        )

    /**
     * Queries the current [FatigueState] for [channelId] at [now].
     */
    fun stateFor(
        channelId: Snowflake,
        windowMs: Long = defaultWindowMs,
        step: Double = defaultStep,
        maxOffset: Double = defaultMaxOffset,
        now: Long = timeSource(),
    ): FatigueState {
        if (windowMs <= 0L || step <= 0.0 || maxOffset <= 0.0) {
            return FatigueState.ZERO
        }
        val entry = synchronized(cache) { cache[channelId] } ?: return FatigueState.ZERO
        val elapsed = now - entry.lastAmbientAtMs
        if (elapsed >= windowMs) {
            return FatigueState.ZERO
        }
        return FatigueState(
            count = entry.count,
            offset = min(maxOffset, entry.count * step),
        )
    }

    /**
     * Records an uninvited `AMBIENT` text reply for [channelId], incrementing its fatigue count
     * (or starting at `1` if cold or expired).
     */
    fun recordAmbientReply(
        channelId: Snowflake,
        windowMs: Long = defaultWindowMs,
        step: Double = defaultStep,
        maxOffset: Double = defaultMaxOffset,
        timestamp: Long = timeSource(),
    ) {
        if (windowMs <= 0L || step <= 0.0 || maxOffset <= 0.0) {
            return
        }
        synchronized(cache) {
            val existing = cache[channelId]
            val nextCount =
                if (existing == null || timestamp - existing.lastAmbientAtMs >= windowMs) {
                    1
                } else {
                    existing.count + 1
                }
            cache[channelId] = FatigueEntry(lastAmbientAtMs = timestamp, count = nextCount)
        }
    }

    /**
     * Immediately resets `Ambient Fatigue` for [channelId] (e.g., upon a delivered `Direct Interaction` reply).
     */
    fun reset(channelId: Snowflake) {
        synchronized(cache) {
            cache.remove(channelId)
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
