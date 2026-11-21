package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.ReactionEmoji
import io.ktor.server.routing.Routing
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.chatter.ChatterPlugin
import luna.chatter.GuildCustomEmoji
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatterEmojiServiceCadenceAndReactionTest {
    private class MutableClock(
        initialEpochMs: Long,
    ) : Clock() {
        private val nowMs = AtomicLong(initialEpochMs)

        fun advanceMs(deltaMs: Long) {
            nowMs.addAndGet(deltaMs)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(nowMs.get())
    }

    @Test
    fun `database null mode preserves reply cadence gating reaction cooldown and history`() =
        runTest {
            val clock = MutableClock(1_700_000_000_000L)
            val guildEmoji = GuildCustomEmoji(Snowflake(1001L), "party", isAnimated = false)
            val appEmoji = GuildCustomEmoji(Snowflake(2001L), "luna_wink", isAnimated = false)

            val service: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = null,
                    applicationEmojiFetcher = { listOf(appEmoji) },
                    clock = clock,
                    replyEmojiCooldownTurns = 2,
                    replyEmojiCooldownWindowMs = 600_000L,
                    reactionCooldownMs = 30_000L,
                    maxRecentReactions = 2,
                    recentReactionWindowMs = 120_000L,
                )

            val channelId = Snowflake(10L)
            val guildId = Snowflake(99L)

            // Turn 1: pools allowed; delivering a custom emoji triggers 2-turn cooldown
            val turn1 = service.beginTurn(channelId, guildId, guildEmojisProvider = { listOf(guildEmoji) })
            val p1 = turn1.prepareReplyEmojis("hi")
            assertEquals(1, p1.applicationEmojis.size)
            assertEquals(1, p1.guildEmojis.size)
            turn1.recordDeliveredReply(listOf("nice <:luna_wink:2001>"))

            // Turn 2: cooldown active (2 remaining) -> pools omitted; plain-text reply decrements to 1
            val turn2 = service.beginTurn(channelId, guildId, guildEmojisProvider = { listOf(guildEmoji) })
            val p2 = turn2.prepareReplyEmojis("still here")
            assertTrue(p2.applicationEmojis.isEmpty(), "Expected app pool omitted during reply cooldown")
            assertTrue(p2.guildEmojis.isEmpty(), "Expected guild pool omitted during reply cooldown")
            turn2.recordDeliveredReply(listOf("plain reply one"))

            // Turn 3: cooldown still active (1 remaining) -> plain-text reply decrements to 0
            val turn3 = service.beginTurn(channelId, guildId, guildEmojisProvider = { listOf(guildEmoji) })
            assertTrue(turn3.prepareReplyEmojis("next").guildEmojis.isEmpty(), "Expected guild pool omitted on 2nd cooldown turn")
            turn3.recordDeliveredReply(listOf("plain reply two"))

            // Turn 4: cooldown cleared -> pools restored
            val turn4 = service.beginTurn(channelId, guildId, guildEmojisProvider = { listOf(guildEmoji) })
            assertEquals(1, turn4.prepareReplyEmojis("back").guildEmojis.size)

            // Reaction cooldown & bounded recent reactions history
            assertFalse(turn4.isReactionOnCooldown(), "Expected no reaction cooldown initially")
            val rPrep1 = turn4.prepareReactionEmojis()
            assertTrue(rPrep1.recentReactions.isEmpty(), "Expected empty recent reactions initially")
            val resolved1 = assertIs<ReactionResolution.Valid>(turn4.resolveReaction("👍"))
            turn4.recordDeliveredReaction(resolved1)
            assertTrue(turn4.isReactionOnCooldown(), "Expected reaction cooldown right after delivery")

            // Past 30s cooldown: recent reactions includes 👍
            clock.advanceMs(35_000L)
            val turn5 = service.beginTurn(channelId, guildId, guildEmojisProvider = { listOf(guildEmoji) })
            assertFalse(turn5.isReactionOnCooldown(), "Expected reaction cooldown expired after 35s")
            assertEquals(listOf("👍"), turn5.prepareReactionEmojis().recentReactions)
            turn5.recordDeliveredReaction(assertIs<ReactionResolution.Valid>(turn5.resolveReaction("🔥")))

            clock.advanceMs(35_000L)
            val turn6 = service.beginTurn(channelId, guildId, guildEmojisProvider = { listOf(guildEmoji) })
            turn6.prepareReactionEmojis()
            turn6.recordDeliveredReaction(assertIs<ReactionResolution.Valid>(turn6.resolveReaction("<:luna_wink:2001>")))

            // Bounded to maxRecentReactions = 2 (keeps latest 2: 🔥 and <:luna_wink:2001>)
            clock.advanceMs(35_000L)
            val turn7 = service.beginTurn(channelId, guildId, guildEmojisProvider = { listOf(guildEmoji) })
            assertEquals(listOf("🔥", "<:luna_wink:2001>"), turn7.prepareReactionEmojis().recentReactions)

            // Past 120s history window -> expired entries pruned
            clock.advanceMs(125_000L)
            val turn8 = service.beginTurn(channelId, guildId, guildEmojisProvider = { listOf(guildEmoji) })
            assertTrue(turn8.prepareReactionEmojis().recentReactions.isEmpty(), "Expected expired recent reactions to be pruned")
        }

    @Test
    fun `resolveReaction handles NONE valid Unicode app and guild emojis and rejects unverified outputs`() =
        runTest {
            val appEmoji = GuildCustomEmoji(Snowflake(111L), "luna_smug", isAnimated = false)
            val guildEmoji = GuildCustomEmoji(Snowflake(222L), "party", isAnimated = true)
            val service: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = null,
                    applicationEmojiFetcher = { listOf(appEmoji) },
                )

            val turn = service.beginTurn(Snowflake(10L), Snowflake(99L), guildEmojisProvider = { listOf(guildEmoji) })
            turn.prepareReactionEmojis()

            // Explicit NONE -> Declined
            assertIs<ReactionResolution.Declined>(turn.resolveReaction("NONE"))
            assertIs<ReactionResolution.Declined>(turn.resolveReaction("  none \n"))

            // Verified Application Emoji -> Valid
            val validApp = assertIs<ReactionResolution.Valid>(turn.resolveReaction("<:luna_smug:111>"))
            assertEquals("<:luna_smug:111>", validApp.normalizedToken)
            assertEquals(ReactionEmoji.Custom(Snowflake(111L), "luna_smug", isAnimated = false), validApp.reactionEmoji)

            // Verified animated Guild Emoji (canonicalizes missing 'a' prefix) -> Valid
            val validGuild = assertIs<ReactionResolution.Valid>(turn.resolveReaction("<:party:222>"))
            assertEquals("<a:party:222>", validGuild.normalizedToken)
            assertEquals(ReactionEmoji.Custom(Snowflake(222L), "party", isAnimated = true), validGuild.reactionEmoji)

            // Valid single Unicode emojis (basic, VS16, skin tone, ZWJ, flag, keycap)
            for (emoji in listOf("👍", "❤️", "👍🏻", "🤦‍♀️", "🇹🇼", "1️⃣")) {
                val resolved = assertIs<ReactionResolution.Valid>(turn.resolveReaction(" $emoji "))
                assertEquals(emoji, resolved.normalizedToken)
                assertEquals(ReactionEmoji.Unicode(emoji), resolved.reactionEmoji)
            }

            // Negative boundary rejections -> Invalid
            for (bad in listOf("", "   ", "👍👍", "👍 nice", "<:external:999>", "<:wrong_name:111>", "hello", "123")) {
                assertIs<ReactionResolution.Invalid>(turn.resolveReaction(bad))
            }
        }

    @Test
    fun `ChatterPlugin wires shared ChatterEmojiService into ChatterService`() =
        runTest {
            val context =
                DefaultPluginContext(
                    kord = mockk<Kord>(relaxed = true),
                    routing = mockk<Routing>(relaxed = true),
                    database = mockk<Database>(relaxed = true),
                    env =
                        luna.config.EnvReader.of(
                            "GEMINI_API_KEY" to "plugin-key",
                            "CHATTER_ENABLED" to "false",
                        ),
                )
            val plugin = ChatterPlugin()
            plugin.initialize(context)
            try {
                val wired = assertNotNull(plugin.emojiService)
                assertTrue(
                    wired === plugin.service?.emojiService,
                    "ChatterPlugin must wire the same ChatterEmojiService instance into ChatterService",
                )
            } finally {
                plugin.shutdown()
            }
        }
}
