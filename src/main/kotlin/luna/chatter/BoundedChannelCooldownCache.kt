package luna.chatter

import dev.kord.common.entity.Snowflake
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Thread-safe LRU bounded cache tracking per-channel cooldown timestamps.
 *
 * Enforces a strict upper bound ([maxEntries], default 500) to adhere to
 * CODING_STANDARDS.md §1 daemon state bounds, preventing unbounded memory growth.
 */
class BoundedChannelCooldownCache(
    val maxEntries: Int = DEFAULT_MAX_CAPACITY,
    val defaultCooldownDurationMs: Long = DEFAULT_COOLDOWN_MS,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val DEFAULT_MAX_CAPACITY = 500
        const val DEFAULT_COOLDOWN_MS = 30_000L // 30 seconds
    }

    private val cache: MutableMap<Snowflake, Long> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Snowflake, Long>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, Long>?): Boolean = size > maxEntries
            },
        )

    fun record(
        channelId: Snowflake,
        timestamp: Long = timeSource(),
    ) {
        cache[channelId] = timestamp
    }

    fun record(
        channelId: Long,
        timestamp: Long = timeSource(),
    ) {
        record(Snowflake(channelId), timestamp)
    }

    fun recordActivity(
        channelId: Snowflake,
        timestamp: Long = timeSource(),
    ) {
        record(channelId, timestamp)
    }

    fun recordActivity(
        channelId: Long,
        timestamp: Long = timeSource(),
    ) {
        record(channelId, timestamp)
    }

    fun isOnCooldown(
        channelId: Snowflake,
        cooldownDurationMs: Long = defaultCooldownDurationMs,
        now: Long = timeSource(),
    ): Boolean {
        val lastTrigger = cache[channelId] ?: return false
        val elapsed = now - lastTrigger
        return elapsed < cooldownDurationMs
    }

    fun isOnCooldown(
        channelId: Long,
        cooldownDurationMs: Long = defaultCooldownDurationMs,
        now: Long = timeSource(),
    ): Boolean = isOnCooldown(Snowflake(channelId), cooldownDurationMs, now)

    fun remainingCooldownMs(
        channelId: Snowflake,
        cooldownDurationMs: Long = defaultCooldownDurationMs,
        now: Long = timeSource(),
    ): Long {
        val lastTrigger = cache[channelId] ?: return 0L
        val elapsed = now - lastTrigger
        return if (elapsed < cooldownDurationMs) cooldownDurationMs - elapsed else 0L
    }

    fun remainingCooldownMs(
        channelId: Long,
        cooldownDurationMs: Long = defaultCooldownDurationMs,
        now: Long = timeSource(),
    ): Long = remainingCooldownMs(Snowflake(channelId), cooldownDurationMs, now)

    operator fun get(channelId: Snowflake): Long? = cache[channelId]

    operator fun get(channelId: Long): Long? = cache[Snowflake(channelId)]

    operator fun set(
        channelId: Snowflake,
        timestamp: Long,
    ) {
        cache[channelId] = timestamp
    }

    fun contains(channelId: Snowflake): Boolean = cache.containsKey(channelId)

    fun contains(channelId: Long): Boolean = cache.containsKey(Snowflake(channelId))

    fun remove(channelId: Snowflake): Boolean = cache.remove(channelId) != null

    fun remove(channelId: Long): Boolean = cache.remove(Snowflake(channelId)) != null

    fun clear() {
        cache.clear()
    }

    val size: Int
        get() = cache.size
}
