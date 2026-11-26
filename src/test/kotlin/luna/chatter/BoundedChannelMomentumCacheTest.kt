package luna.chatter

import dev.kord.common.entity.Snowflake
import luna.config.ConfigValidationException
import luna.config.EnvReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedChannelMomentumCacheTest {
    @Test
    fun `cold channel has inactive momentum with zero turns`() {
        val now = 1_000_000L
        val cache = BoundedChannelMomentumCache(timeSource = { now })

        val state = cache.stateFor(Snowflake(100L))

        assertFalse(state.active, "Cold channel should not have active momentum")
        assertFalse(state.withinWindow, "Cold channel should not be within momentum window")
        assertEquals(0, state.ambientTurnCount)
    }

    @Test
    fun `direct mention reply starts active window with zero ambient turns`() {
        val now = 1_000_000L
        val cache = BoundedChannelMomentumCache(timeSource = { now })
        val channelId = Snowflake(100L)

        cache.recordReply(channelId, isDirectMention = true)

        val state = cache.stateFor(channelId)
        assertTrue(state.active, "Direct mention should activate momentum")
        assertTrue(state.withinWindow, "Direct mention should start momentum window")
        assertEquals(0, state.ambientTurnCount)
    }

    @Test
    fun `uninvited ambient reply on cold channel does not start momentum`() {
        val now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 2, timeSource = { now })
        val channelId = Snowflake(100L)

        cache.recordReply(channelId, isDirectMention = false)

        val state = cache.stateFor(channelId)
        assertFalse(state.active, "Cold-start ambient reply should not activate momentum")
        assertFalse(state.withinWindow, "Cold-start ambient reply should not open momentum window")
        assertEquals(0, state.ambientTurnCount)
        assertEquals(0, cache.size)
    }

    @Test
    fun `ambient replies after direct mention increment turn count until max turns exhausted`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 2, timeSource = { now })
        val channelId = Snowflake(100L)

        // Direct mention starts active momentum at turn 0
        cache.recordReply(channelId, isDirectMention = true)
        assertEquals(MomentumState(active = true, withinWindow = true, ambientTurnCount = 0), cache.stateFor(channelId))

        // Ambient turn 1 (still active: 1 < 2)
        now += 2_000L
        cache.recordReply(channelId, isDirectMention = false)
        assertEquals(MomentumState(active = true, withinWindow = true, ambientTurnCount = 1), cache.stateFor(channelId))

        // Ambient turn 2 -> reaches maxTurns=2, now exhausted (active = false)
        now += 2_000L
        cache.recordReply(channelId, isDirectMention = false)
        val exhausted = cache.stateFor(channelId)
        assertFalse(exhausted.active, "Momentum should pause once ambientTurnCount reaches maxTurns")
        assertTrue(exhausted.withinWindow, "Exhausted channel should still be inside momentum window")
        assertEquals(2, exhausted.ambientTurnCount)
    }

    @Test
    fun `ambient reply while exhausted inside window does not refresh timestamp or increment count`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 2, timeSource = { now })
        val channelId = Snowflake(100L)

        cache.recordReply(channelId, isDirectMention = true)
        repeat(2) {
            now += 2_000L
            cache.recordReply(channelId, isDirectMention = false)
        }
        assertFalse(cache.stateFor(channelId).active, "Momentum should be exhausted after 2 ambient turns")

        // Wait 6s and deliver another ambient reply while exhausted
        now += 6_000L
        cache.recordReply(channelId, isDirectMention = false)
        assertEquals(2, cache.stateFor(channelId).ambientTurnCount)

        // 24s later (30s since the 2nd ambient turn, only 24s since the exhausted ambient reply): window expires
        now += 24_000L
        val expired = cache.stateFor(channelId)
        assertFalse(expired.active, "Expired momentum should be inactive")
        assertFalse(expired.withinWindow, "Exhausted ambient reply should not have refreshed window")
        assertEquals(0, expired.ambientTurnCount)
    }

    @Test
    fun `direct mention resets exhausted momentum back to zero ambient turns`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 2, timeSource = { now })
        val channelId = Snowflake(100L)

        cache.recordReply(channelId, isDirectMention = true)
        repeat(2) {
            now += 2_000L
            cache.recordReply(channelId, isDirectMention = false)
        }
        assertFalse(cache.stateFor(channelId).active, "Momentum should be exhausted before mention reset")

        now += 2_000L
        cache.recordReply(channelId, isDirectMention = true)

        val state = cache.stateFor(channelId)
        assertTrue(state.active, "Direct mention should re-activate exhausted momentum")
        assertTrue(state.withinWindow, "Direct mention should refresh momentum window")
        assertEquals(0, state.ambientTurnCount)
    }

    @Test
    fun `window expiration prevents subsequent ambient reply from restarting momentum`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 2, timeSource = { now })
        val channelId = Snowflake(100L)

        cache.recordReply(channelId, isDirectMention = true)
        now += 2_000L
        cache.recordReply(channelId, isDirectMention = false)
        assertEquals(1, cache.stateFor(channelId).ambientTurnCount)

        // Advance past full 30s window
        now += 30_000L
        assertFalse(cache.stateFor(channelId).withinWindow, "Momentum window should have expired")

        cache.recordReply(channelId, isDirectMention = false)
        assertEquals(MomentumState.INACTIVE, cache.stateFor(channelId))
    }

    @Test
    fun `zero windowMs or zero maxTurns disables momentum`() {
        val now = 1_000_000L
        val cache = BoundedChannelMomentumCache(timeSource = { now })
        val channelId = Snowflake(100L)

        cache.recordReply(channelId, isDirectMention = true, windowMs = 0L, maxTurns = 3)
        assertFalse(cache.stateFor(channelId, windowMs = 0L, maxTurns = 3).active, "windowMs=0 should disable momentum")
        assertEquals(0, cache.size)

        cache.recordReply(channelId, isDirectMention = true, windowMs = 30_000L, maxTurns = 0)
        assertFalse(cache.stateFor(channelId, windowMs = 30_000L, maxTurns = 0).active, "maxTurns=0 should disable momentum")
        assertEquals(0, cache.size)
    }

    @Test
    fun `evicts least recently used channel when capacity is exceeded`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(maxEntries = 2, timeSource = { now })

        cache.recordReply(Snowflake(1L), isDirectMention = true)
        now += 100L
        cache.recordReply(Snowflake(2L), isDirectMention = true)
        // Touch channel 1 so channel 2 becomes LRU
        assertTrue(cache.stateFor(Snowflake(1L)).active, "Channel 1 should remain active")

        now += 100L
        cache.recordReply(Snowflake(3L), isDirectMention = true)

        assertEquals(2, cache.size)
        assertTrue(cache.stateFor(Snowflake(1L)).active, "Recently accessed channel 1 should stay in LRU")
        assertFalse(cache.stateFor(Snowflake(2L)).withinWindow, "LRU channel 2 should be evicted")
        assertTrue(cache.stateFor(Snowflake(3L)).active, "Newly inserted channel 3 should be active")
    }

    @Test
    fun `ChatterConfig parses and validates conversation momentum env vars`() {
        val defaults = ChatterConfig.from(EnvReader.of())
        assertEquals(30_000L, defaults.filter.momentumWindowMs)
        assertEquals(1_500L, defaults.filter.momentumCooldownFloorMs)
        assertEquals(2, defaults.filter.momentumMaxTurns)
        assertEquals(0.10, defaults.momentumThresholdBoost)

        val custom =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_MOMENTUM_WINDOW_SECONDS" to "45",
                    "CHATTER_MOMENTUM_COOLDOWN_FLOOR_MS" to "800",
                    "CHATTER_MOMENTUM_MAX_TURNS" to "5",
                    "CHATTER_MOMENTUM_THRESHOLD_BOOST" to "0.15",
                ),
            )
        assertEquals(45_000L, custom.filter.momentumWindowMs)
        assertEquals(800L, custom.filter.momentumCooldownFloorMs)
        assertEquals(5, custom.filter.momentumMaxTurns)
        assertEquals(0.15, custom.momentumThresholdBoost)

        val disabled =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_MOMENTUM_WINDOW_SECONDS" to "0",
                    "CHATTER_MOMENTUM_MAX_TURNS" to "0",
                    "CHATTER_MOMENTUM_THRESHOLD_BOOST" to "0.0",
                ),
            )
        assertEquals(0L, disabled.filter.momentumWindowMs)
        assertEquals(0, disabled.filter.momentumMaxTurns)
        assertEquals(0.0, disabled.momentumThresholdBoost)

        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_MOMENTUM_WINDOW_SECONDS" to "-1")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_MOMENTUM_COOLDOWN_FLOOR_MS" to "-1")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_MOMENTUM_MAX_TURNS" to "-1")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_MOMENTUM_THRESHOLD_BOOST" to "-0.01")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_MOMENTUM_THRESHOLD_BOOST" to "1.01")) }
    }
}
