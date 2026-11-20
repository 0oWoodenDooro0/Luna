package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedChannelEmojiCadenceTrackerTest {
    @Test
    fun `reply custom emoji cooldown triggers for configured turns and resets after pure text replies`() {
        val now = AtomicLong(100_000L)
        val tracker =
            BoundedChannelEmojiCadenceTracker(
                replyCooldownTurns = 2,
                replyCooldownWindowMs = 600_000L,
                timeSource = { now.get() },
            )
        val channelId = Snowflake(101L)

        assertTrue(tracker.shouldAllowReplyCustomEmojis(channelId), "Channel with no prior history should allow custom emojis")

        // Turn 1 uses custom emoji -> enters 2-turn cooldown
        tracker.recordDeliveredReply(channelId, "太扯了吧 <:blobcat:1001>")
        assertFalse(tracker.shouldAllowReplyCustomEmojis(channelId), "Custom emoji reply should activate reply emoji cooldown")

        // Turn 2 pure text -> 1 cooldown turn remains
        now.addAndGet(5_000L)
        tracker.recordDeliveredReply(channelId, "這波真的猛")
        assertFalse(tracker.shouldAllowReplyCustomEmojis(channelId), "One pure-text reply should leave 1 cooldown turn remaining")

        // Turn 3 pure text -> cooldown clears
        now.addAndGet(5_000L)
        tracker.recordDeliveredReply(channelId, "對啊超扯")
        assertTrue(tracker.shouldAllowReplyCustomEmojis(channelId), "Two pure-text replies should clear the 2-turn emoji cooldown")

        // Animated custom emoji also triggers cooldown
        tracker.recordDeliveredReply(channelId, "好耶 <a:party:2002>")
        assertFalse(tracker.shouldAllowReplyCustomEmojis(channelId), "Animated custom emoji should also activate reply emoji cooldown")
    }

    @Test
    fun `pure text reply without prior custom emoji does not trigger cooldown and zero turns disables cooldown`() {
        val now = AtomicLong(100_000L)
        val tracker =
            BoundedChannelEmojiCadenceTracker(
                replyCooldownTurns = 2,
                timeSource = { now.get() },
            )
        val channelId = Snowflake(102L)

        tracker.recordDeliveredReply(channelId, "純文字回覆")
        assertTrue(tracker.shouldAllowReplyCustomEmojis(channelId), "Pure-text reply must not trigger emoji cooldown")
        assertEquals(0, tracker.size, "Pure-text reply on idle channel should not allocate tracker state")

        val disabledTracker =
            BoundedChannelEmojiCadenceTracker(
                replyCooldownTurns = 0,
                timeSource = { now.get() },
            )
        disabledTracker.recordDeliveredReply(channelId, "帶有表情 <:ok:1001>")
        assertTrue(disabledTracker.shouldAllowReplyCustomEmojis(channelId), "replyCooldownTurns=0 must disable reply emoji cooldown")
    }

    @Test
    fun `reply custom emoji cooldown expires after window elapsed even before turn count completes`() {
        val now = AtomicLong(100_000L)
        val tracker =
            BoundedChannelEmojiCadenceTracker(
                replyCooldownTurns = 3,
                replyCooldownWindowMs = 600_000L,
                timeSource = { now.get() },
            )
        val channelId = Snowflake(103L)

        tracker.recordDeliveredReply(channelId, "笑死 <:haha:1001>")
        assertFalse(tracker.shouldAllowReplyCustomEmojis(channelId), "Cooldown must be active right after custom emoji reply")

        now.addAndGet(599_999L)
        assertFalse(tracker.shouldAllowReplyCustomEmojis(channelId), "Cooldown must remain active right before window expiry")

        now.addAndGet(1L)
        assertTrue(tracker.shouldAllowReplyCustomEmojis(channelId), "Cooldown must expire once replyCooldownWindowMs elapses")
    }

    @Test
    fun `reaction cooldown blocks rapid reactions and expires after configured duration`() {
        val now = AtomicLong(200_000L)
        val tracker =
            BoundedChannelEmojiCadenceTracker(
                reactionCooldownMs = 30_000L,
                timeSource = { now.get() },
            )
        val channelId = Snowflake(201L)

        assertFalse(tracker.isReactionOnCooldown(channelId), "Fresh channel must not be on reaction cooldown")

        tracker.recordDeliveredReaction(channelId, "🔥")
        assertTrue(tracker.isReactionOnCooldown(channelId), "Delivered reaction must start reaction cooldown")

        now.addAndGet(29_999L)
        assertTrue(tracker.isReactionOnCooldown(channelId), "Reaction cooldown must hold until full 30s elapses")

        now.addAndGet(1L)
        assertFalse(tracker.isReactionOnCooldown(channelId), "Reaction cooldown must expire at 30s boundary")
    }

    @Test
    fun `recent reactions preserve chronological order up to maxRecentReactions and expire old entries`() {
        val now = AtomicLong(300_000L)
        val tracker =
            BoundedChannelEmojiCadenceTracker(
                maxRecentReactions = 3,
                recentReactionWindowMs = 1_800_000L,
                timeSource = { now.get() },
            )
        val channelId = Snowflake(202L)

        tracker.recordDeliveredReaction(channelId, "   ")
        assertEquals(emptyList(), tracker.recentReactions(channelId))

        tracker.recordDeliveredReaction(channelId, " 😂 ")
        now.addAndGet(10_000L)
        tracker.recordDeliveredReaction(channelId, "🔥")
        now.addAndGet(10_000L)
        tracker.recordDeliveredReaction(channelId, "<:cat:5001>")
        assertEquals(listOf("😂", "🔥", "<:cat:5001>"), tracker.recentReactions(channelId))

        // Exceeds maxRecentReactions=3 -> drops oldest ("😂")
        now.addAndGet(10_000L)
        tracker.recordDeliveredReaction(channelId, "👍")
        assertEquals(listOf("🔥", "<:cat:5001>", "👍"), tracker.recentReactions(channelId))

        // Advance so "🔥" (at 310_000) and "<:cat:5001>" (at 320_000) expire while "👍" (at 330_000) stays
        now.set(320_000L + 1_800_000L)
        assertEquals(listOf("👍"), tracker.recentReactions(channelId))

        // Advance past "👍" expiry
        now.set(330_000L + 1_800_000L)
        assertEquals(emptyList(), tracker.recentReactions(channelId))
    }

    @Test
    fun `channel states are isolated and LRU eviction enforces maxEntries capacity`() {
        val now = AtomicLong(500_000L)
        val tracker =
            BoundedChannelEmojiCadenceTracker(
                maxEntries = 2,
                replyCooldownTurns = 2,
                timeSource = { now.get() },
            )
        val ch1 = Snowflake(1L)
        val ch2 = Snowflake(2L)
        val ch3 = Snowflake(3L)

        tracker.recordDeliveredReply(ch1, "hi <:a:1>")
        tracker.recordDeliveredReaction(ch2, "🔥")
        assertFalse(tracker.shouldAllowReplyCustomEmojis(ch1), "Channel 1 should be in reply emoji cooldown")
        assertTrue(tracker.shouldAllowReplyCustomEmojis(ch2), "Channel 2 reply emoji allowance must stay isolated")
        assertTrue(tracker.isReactionOnCooldown(ch2), "Channel 2 should be in reaction cooldown")
        assertFalse(tracker.isReactionOnCooldown(ch1), "Channel 1 reaction cooldown must stay isolated")
        assertEquals(2, tracker.size)

        // Touch ch1 so ch2 becomes LRU, then insert ch3 -> evicts ch2
        assertFalse(tracker.shouldAllowReplyCustomEmojis(ch1), "Accessing ch1 updates its LRU recency")
        tracker.recordDeliveredReply(ch3, "yo <:c:3>")

        assertEquals(2, tracker.size)
        assertFalse(tracker.shouldAllowReplyCustomEmojis(ch1), "Recently accessed ch1 must stay cached")
        assertFalse(tracker.shouldAllowReplyCustomEmojis(ch3), "Newly inserted ch3 must be cached")
        assertFalse(tracker.isReactionOnCooldown(ch2), "Evicted LRU channel ch2 should have cleared state")
        assertEquals(emptyList(), tracker.recentReactions(ch2))

        tracker.clear()
        assertEquals(0, tracker.size)
    }
}
