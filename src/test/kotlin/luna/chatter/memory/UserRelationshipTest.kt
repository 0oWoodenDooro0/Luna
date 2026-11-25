package luna.chatter.memory

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserRelationshipTest {
    @Test
    fun `AffinityTier maps boundary scores deterministically with expected ambient threshold offsets`() {
        assertEquals(AffinityTier.DISLIKED, AffinityTier.fromScore(-100))
        assertEquals(AffinityTier.DISLIKED, AffinityTier.fromScore(-40))
        assertEquals(0.22, AffinityTier.DISLIKED.ambientThresholdOffset)
        assertTrue(AffinityTier.DISLIKED.suppressFriendlySilentReaction, "DISLIKED must suppress friendly silent reaction")

        assertEquals(AffinityTier.COOL, AffinityTier.fromScore(-39))
        assertEquals(AffinityTier.COOL, AffinityTier.fromScore(-15))
        assertEquals(0.10, AffinityTier.COOL.ambientThresholdOffset)
        assertFalse(AffinityTier.COOL.suppressFriendlySilentReaction, "COOL must not suppress silent reaction")

        assertEquals(AffinityTier.NEUTRAL, AffinityTier.fromScore(-14))
        assertEquals(AffinityTier.NEUTRAL, AffinityTier.fromScore(0))
        assertEquals(AffinityTier.NEUTRAL, AffinityTier.fromScore(14))
        assertEquals(0.0, AffinityTier.NEUTRAL.ambientThresholdOffset)
        assertFalse(AffinityTier.NEUTRAL.suppressFriendlySilentReaction, "NEUTRAL must not suppress silent reaction")

        assertEquals(AffinityTier.WARM, AffinityTier.fromScore(15))
        assertEquals(AffinityTier.WARM, AffinityTier.fromScore(49))
        assertEquals(-0.05, AffinityTier.WARM.ambientThresholdOffset)
        assertFalse(AffinityTier.WARM.suppressFriendlySilentReaction, "WARM must not suppress silent reaction")

        assertEquals(AffinityTier.FAVORITE, AffinityTier.fromScore(50))
        assertEquals(AffinityTier.FAVORITE, AffinityTier.fromScore(100))
        assertEquals(-0.10, AffinityTier.FAVORITE.ambientThresholdOffset)
        assertFalse(AffinityTier.FAVORITE.suppressFriendlySilentReaction, "FAVORITE must not suppress silent reaction")

        // Out-of-range inputs are handled safely
        assertEquals(AffinityTier.DISLIKED, AffinityTier.fromScore(-999))
        assertEquals(AffinityTier.FAVORITE, AffinityTier.fromScore(999))
    }

    @Test
    fun `NegativeAffinityDecay respects 7-day cooldown, drifts negative scores at +5 per 7 days capped at 0, and never decays positive scores`() {
        val t0 = 1_700_000_000_000L
        val dayMs = TimeUnit.DAYS.toMillis(1)

        // Within and at 7-day cooldown: no decay
        assertEquals(-40, NegativeAffinityDecay.effectiveScore(rawScore = -40, lastInteractionAtMs = t0, nowMs = t0))
        assertEquals(-40, NegativeAffinityDecay.effectiveScore(rawScore = -40, lastInteractionAtMs = t0, nowMs = t0 + 6 * dayMs))
        assertEquals(-40, NegativeAffinityDecay.effectiveScore(rawScore = -40, lastInteractionAtMs = t0, nowMs = t0 + 7 * dayMs))

        // After cooldown: +5 per 7 days
        assertEquals(-35, NegativeAffinityDecay.effectiveScore(rawScore = -40, lastInteractionAtMs = t0, nowMs = t0 + 14 * dayMs))
        assertEquals(-30, NegativeAffinityDecay.effectiveScore(rawScore = -40, lastInteractionAtMs = t0, nowMs = t0 + 21 * dayMs))

        // Never crosses above 0
        assertEquals(0, NegativeAffinityDecay.effectiveScore(rawScore = -3, lastInteractionAtMs = t0, nowMs = t0 + 14 * dayMs))
        assertEquals(0, NegativeAffinityDecay.effectiveScore(rawScore = -40, lastInteractionAtMs = t0, nowMs = t0 + 365 * dayMs))

        // Zero and positive scores never decay automatically
        assertEquals(0, NegativeAffinityDecay.effectiveScore(rawScore = 0, lastInteractionAtMs = t0, nowMs = t0 + 365 * dayMs))
        assertEquals(65, NegativeAffinityDecay.effectiveScore(rawScore = 65, lastInteractionAtMs = t0, nowMs = t0 + 365 * dayMs))

        // Clock skew (now < lastInteractionAt) is safe
        assertEquals(-20, NegativeAffinityDecay.effectiveScore(rawScore = -20, lastInteractionAtMs = t0, nowMs = t0 - dayMs))
    }

    @Test
    fun `clamping helpers bound delta to -15 to +15, score to -100 to +100, and toneStyle to 120 chars`() {
        assertEquals(-15, NegativeAffinityDecay.clampDelta(-50))
        assertEquals(15, NegativeAffinityDecay.clampDelta(50))
        assertEquals(7, NegativeAffinityDecay.clampDelta(7))

        assertEquals(-100, NegativeAffinityDecay.clampScore(-150))
        assertEquals(100, NegativeAffinityDecay.clampScore(150))
        assertEquals(-15, NegativeAffinityDecay.clampScore(-15))

        assertEquals("playful banter", NegativeAffinityDecay.sanitizeToneStyle("  playful banter  "))
        val oversized = "a".repeat(200)
        assertEquals(120, NegativeAffinityDecay.sanitizeToneStyle(oversized).length)
    }
}
