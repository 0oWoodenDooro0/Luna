package luna.chatter

import dev.kord.common.entity.Snowflake
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BoundedChannelFatigueCacheTest {
    @Test
    fun `cold channel starts with zero fatigue`() {
        val cache = BoundedChannelFatigueCache(timeSource = { 1_000_000L })
        assertEquals(FatigueState.ZERO, cache.stateFor(Snowflake(100L)))
    }

    @Test
    fun `uninvited ambient replies accumulate linear fatigue capped at maxOffset`() {
        var now = 1_000_000L
        val cache =
            BoundedChannelFatigueCache(
                defaultWindowMs = 300_000L,
                defaultStep = 0.08,
                defaultMaxOffset = 0.24,
                timeSource = { now },
            )
        val channelId = Snowflake(100L)

        cache.recordAmbientReply(channelId)
        assertEquals(1, cache.stateFor(channelId).count)
        assertEquals(0.08, cache.stateFor(channelId).offset, 1e-9)

        now += 30_000L
        cache.recordAmbientReply(channelId)
        assertEquals(2, cache.stateFor(channelId).count)
        assertEquals(0.16, cache.stateFor(channelId).offset, 1e-9)

        now += 30_000L
        cache.recordAmbientReply(channelId)
        assertEquals(3, cache.stateFor(channelId).count)
        assertEquals(0.24, cache.stateFor(channelId).offset, 1e-9)

        now += 30_000L
        cache.recordAmbientReply(channelId)
        assertEquals(4, cache.stateFor(channelId).count)
        assertEquals(0.24, cache.stateFor(channelId).offset, 1e-9)
    }

    @Test
    fun `window expiration resets fatigue to zero and restarts at count one`() {
        var now = 1_000_000L
        val cache =
            BoundedChannelFatigueCache(
                defaultWindowMs = 300_000L,
                defaultStep = 0.08,
                defaultMaxOffset = 0.24,
                timeSource = { now },
            )
        val channelId = Snowflake(100L)

        cache.recordAmbientReply(channelId)
        now += 10_000L
        cache.recordAmbientReply(channelId)
        assertEquals(2, cache.stateFor(channelId).count)

        now += 300_000L
        assertEquals(FatigueState.ZERO, cache.stateFor(channelId))

        cache.recordAmbientReply(channelId)
        assertEquals(1, cache.stateFor(channelId).count)
        assertEquals(0.08, cache.stateFor(channelId).offset, 1e-9)
    }

    @Test
    fun `reset clears fatigue immediately`() {
        var now = 1_000_000L
        val cache = BoundedChannelFatigueCache(timeSource = { now })
        val channelId = Snowflake(100L)

        cache.recordAmbientReply(channelId)
        now += 1_000L
        cache.recordAmbientReply(channelId)
        assertEquals(2, cache.stateFor(channelId).count)

        cache.reset(channelId)
        assertEquals(FatigueState.ZERO, cache.stateFor(channelId))
    }

    @Test
    fun `zero windowMs step or maxOffset disables fatigue`() {
        val cache = BoundedChannelFatigueCache(timeSource = { 1_000_000L })
        val channelId = Snowflake(100L)

        cache.recordAmbientReply(channelId, windowMs = 0L)
        assertEquals(FatigueState.ZERO, cache.stateFor(channelId, windowMs = 0L))

        cache.recordAmbientReply(channelId, step = 0.0)
        assertEquals(FatigueState.ZERO, cache.stateFor(channelId, step = 0.0))

        cache.recordAmbientReply(channelId, maxOffset = 0.0)
        assertEquals(FatigueState.ZERO, cache.stateFor(channelId, maxOffset = 0.0))
        assertEquals(0, cache.size)
    }

    @Test
    fun `evicts least recently used channel when capacity is exceeded`() {
        var now = 1_000_000L
        val cache = BoundedChannelFatigueCache(maxEntries = 2, timeSource = { now })

        cache.recordAmbientReply(Snowflake(1L))
        now += 100L
        cache.recordAmbientReply(Snowflake(2L))
        // Touch channel 1 so channel 2 becomes LRU
        assertEquals(1, cache.stateFor(Snowflake(1L)).count)

        now += 100L
        cache.recordAmbientReply(Snowflake(3L))

        assertEquals(2, cache.size)
        assertEquals(1, cache.stateFor(Snowflake(1L)).count)
        assertEquals(FatigueState.ZERO, cache.stateFor(Snowflake(2L)))
        assertEquals(1, cache.stateFor(Snowflake(3L)).count)
    }
}
