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
    fun `ambient replies increment turn count until max turns exhausted`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 3, timeSource = { now })
        val channelId = Snowflake(100L)

        // Cold start ambient reply -> turn 1 (active)
        cache.recordReply(channelId, isDirectMention = false)
        assertEquals(MomentumState(active = true, withinWindow = true, ambientTurnCount = 1), cache.stateFor(channelId))

        // Turn 2 (active)
        now += 2_000L
        cache.recordReply(channelId, isDirectMention = false)
        assertEquals(MomentumState(active = true, withinWindow = true, ambientTurnCount = 2), cache.stateFor(channelId))

        // Turn 3 -> reaches maxTurns=3, now exhausted (active = false)
        now += 2_000L
        cache.recordReply(channelId, isDirectMention = false)
        val exhausted = cache.stateFor(channelId)
        assertFalse(exhausted.active, "Momentum should pause once ambientTurnCount reaches maxTurns")
        assertTrue(exhausted.withinWindow, "Exhausted channel should still be inside momentum window")
        assertEquals(3, exhausted.ambientTurnCount)
    }

    @Test
    fun `ambient reply while exhausted inside window refreshes timestamp without resetting turn count`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 3, timeSource = { now })
        val channelId = Snowflake(100L)

        repeat(3) {
            now += 2_000L
            cache.recordReply(channelId, isDirectMention = false)
        }
        assertFalse(cache.stateFor(channelId).active, "Momentum should be exhausted after 3 ambient turns")

        // Wait 6s (past normal 5s cooldown, still inside 30s window) and deliver another ambient reply
        now += 6_000L
        cache.recordReply(channelId, isDirectMention = false)

        // 25s after that reply (past original window, still inside refreshed 30s window): still exhausted
        now += 25_000L
        val state = cache.stateFor(channelId)
        assertFalse(state.active, "Momentum should remain exhausted within refreshed window")
        assertTrue(state.withinWindow, "Refreshed window should still be open")
        assertEquals(4, state.ambientTurnCount)

        // Full 30s silence from last reply -> window expires and resets
        now += 5_000L
        val expired = cache.stateFor(channelId)
        assertFalse(expired.active, "Expired momentum should be inactive")
        assertFalse(expired.withinWindow, "Expired momentum should be outside window")
        assertEquals(0, expired.ambientTurnCount)
    }

    @Test
    fun `direct mention resets exhausted momentum back to zero ambient turns`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 3, timeSource = { now })
        val channelId = Snowflake(100L)

        repeat(3) {
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
    fun `window expiration resets cold start ambient turn count to one on next ambient reply`() {
        var now = 1_000_000L
        val cache = BoundedChannelMomentumCache(defaultWindowMs = 30_000L, defaultMaxTurns = 3, timeSource = { now })
        val channelId = Snowflake(100L)

        cache.recordReply(channelId, isDirectMention = false)
        now += 2_000L
        cache.recordReply(channelId, isDirectMention = false)
        assertEquals(2, cache.stateFor(channelId).ambientTurnCount)

        // Advance past full 30s window
        now += 30_000L
        assertFalse(cache.stateFor(channelId).withinWindow, "Momentum window should have expired")

        cache.recordReply(channelId, isDirectMention = false)
        assertEquals(MomentumState(active = true, withinWindow = true, ambientTurnCount = 1), cache.stateFor(channelId))
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
        assertEquals(3, defaults.filter.momentumMaxTurns)
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
