package luna.chatter

import dev.kord.common.entity.Snowflake
import java.util.LinkedHashMap

/**
 * Thread-safe, capacity-bounded LRU tracker recording active in-flight Chatter runs per channel
 * using reference-counted acquire/release semantics.
 *
 * Enforces an upper bound ([maxEntries], default 500) to adhere to CODING_STANDARDS.md §1 daemon state bounds.
 */
class BoundedChannelInFlightTracker(
    val maxEntries: Int = DEFAULT_MAX_CAPACITY,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, got $maxEntries" }
    }

    private val lock = Any()
    private val activeCounts: MutableMap<Snowflake, Int> =
        object : LinkedHashMap<Snowflake, Int>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, Int>?): Boolean = size > maxEntries
        }

    /**
     * Atomically acquires an in-flight slot for [channelId] only if no execution is currently active.
     * Returns `true` if acquired (count becomes 1), or `false` if the channel already has an active run.
     */
    fun tryAcquire(channelId: Snowflake): Boolean =
        synchronized(lock) {
            val current = activeCounts[channelId] ?: 0
            if (current > 0) {
                return false
            }
            activeCounts[channelId] = 1
            true
        }

    fun tryAcquire(channelId: Long): Boolean = tryAcquire(Snowflake(channelId))

    /**
     * Unconditionally increments the active in-flight count for [channelId] (used by direct `@bot` mentions
     * that bypass ambient concurrency gating) and returns the updated count.
     */
    fun acquire(channelId: Snowflake): Int =
        synchronized(lock) {
            val next = (activeCounts[channelId] ?: 0) + 1
            activeCounts[channelId] = next
            next
        }

    fun acquire(channelId: Long): Int = acquire(Snowflake(channelId))

    /**
     * Decrements the active in-flight count for [channelId], removing the entry when the count reaches zero.
     * Returns the remaining active count (never negative).
     */
    fun release(channelId: Snowflake): Int =
        synchronized(lock) {
            val current = activeCounts[channelId] ?: return 0
            if (current <= 1) {
                activeCounts.remove(channelId)
                0
            } else {
                val next = current - 1
                activeCounts[channelId] = next
                next
            }
        }

    fun release(channelId: Long): Int = release(Snowflake(channelId))

    fun isInFlight(channelId: Snowflake): Boolean = synchronized(lock) { (activeCounts[channelId] ?: 0) > 0 }

    fun isInFlight(channelId: Long): Boolean = isInFlight(Snowflake(channelId))

    fun activeCount(channelId: Snowflake): Int = synchronized(lock) { activeCounts[channelId] ?: 0 }

    fun activeCount(channelId: Long): Int = activeCount(Snowflake(channelId))

    val size: Int
        get() = synchronized(lock) { activeCounts.size }

    fun clear() {
        synchronized(lock) {
            activeCounts.clear()
        }
    }

    companion object {
        const val DEFAULT_MAX_CAPACITY = 500
    }
}
