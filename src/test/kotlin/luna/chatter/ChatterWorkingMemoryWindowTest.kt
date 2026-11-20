package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ChatterWorkingMemoryWindowTest {
    private lateinit var filter: ChatterNoiseFilter
    private val anchorTime = Instant.parse("2026-11-20T12:00:00Z")

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        SummaryMessageRegistry.clear()
        filter = ChatterNoiseFilter()
    }

    @Test
    fun `ChatterContextCollector defaults to 30 reply messages, 15 decision messages, and 10 minute window`() {
        assertEquals(30, ChatterContextCollector.DEFAULT_HISTORY_MESSAGE_COUNT)
        assertEquals(15, ChatterContextCollector.DEFAULT_DECISION_MESSAGE_COUNT)
        assertEquals(10L, ChatterContextCollector.DEFAULT_HISTORY_WINDOW_MINUTES)

        val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })
        assertEquals(30, collector.historyMessageCount)
        assertEquals(15, collector.decisionMessageCount)
        assertEquals(10L, collector.historyWindowMinutes)
    }

    @Test
    fun `collectDecisionContext and collectReplyContext exclude messages before inactivity gap and cap at 15 and 30`() =
        runTest {
            val staleMsg =
                createMockMessage(
                    id = 1L,
                    username = "StaleUser",
                    content = "Multi-day old message",
                    timestamp = anchorTime - 25.minutes,
                )
            val boundaryInMsg =
                createMockMessage(
                    id = 2L,
                    username = "BoundaryUser",
                    content = "Exactly 10m ago",
                    timestamp = anchorTime - 10.minutes,
                )
            val recentMsgs =
                (1..32).map { idx ->
                    createMockMessage(
                        id = 100L + idx,
                        username = "User$idx",
                        content = "Recent turn $idx",
                        timestamp = anchorTime - (32 - idx).minutes * 0.2,
                    )
                }

            val allMessages = listOf(staleMsg, boundaryInMsg) + recentMsgs
            val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })

            val decision = collector.collectDecisionContext(allMessages)
            assertFalse(decision.transcript.contains("Multi-day old message"), "Decision window should exclude stale message beyond inactivity gap")
            assertFalse(decision.transcript.contains("Exactly 10m ago"), "Decision window should cap at 15 newest messages")
            assertFalse(decision.transcript.contains("Recent turn 17\n"), "Decision window should drop turn 17 when 18..32 fill 15 slots")
            assertTrue(decision.transcript.contains("User18 (uid:118): Recent turn 18"), "Decision window should include turn 18")
            assertTrue(decision.transcript.contains("User32 (uid:132): Recent turn 32"), "Decision window should include latest turn 32")

            val reply = collector.collectReplyContext(allMessages)
            assertFalse(reply.transcript.contains("Multi-day old message"), "Reply window should exclude stale message beyond inactivity gap")
            assertFalse(reply.transcript.contains("Exactly 10m ago"), "Reply window should cap at 30 newest messages")
            assertFalse(reply.transcript.contains("Recent turn 2\n"), "Reply window should drop turn 2 when 3..32 fill 30 slots")
            assertTrue(reply.transcript.contains("User3 (uid:103): Recent turn 3"), "Reply window should include turn 3")
            assertTrue(reply.transcript.contains("User32 (uid:132): Recent turn 32"), "Reply window should include latest turn 32")
            assertEquals(recentMsgs[2].timestamp.toEpochMilliseconds(), reply.oldestWindowTimestampMs)
        }

    @Test
    fun `inactivity gap backtracking keeps continuous dialogue beyond 10m up to max lookback cap`() =
        runTest {
            val beyondCap =
                createMockMessage(
                    id = 1L,
                    username = "OldCap",
                    content = "Continuous but beyond 60m cap",
                    timestamp = anchorTime - 65.minutes,
                )
            val continuousMsgs =
                listOf(55, 47, 39, 31, 23, 15, 7, 0).mapIndexed { idx, minutesAgo ->
                    createMockMessage(
                        id = 10L + idx,
                        username = "Talker$idx",
                        content = "Turn at -${minutesAgo}m",
                        timestamp = anchorTime - minutesAgo.minutes,
                    )
                }

            val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })
            val reply = collector.collectReplyContext(listOf(beyondCap) + continuousMsgs)

            assertFalse(
                reply.transcript.contains("Continuous but beyond 60m cap"),
                "Messages older than the 60-minute max lookback cap must be excluded even if gaps are <= 10m",
            )
            assertTrue(
                reply.transcript.contains("Turn at -55m"),
                "Continuous conversation with <= 10m gaps within 60m should remain intact",
            )
            assertTrue(
                reply.transcript.contains("Turn at -23m"),
                "Mid-conversation turns older than 10m should remain intact when adjacent gaps are <= 10m",
            )
            assertEquals((anchorTime - 55.minutes).toEpochMilliseconds(), reply.oldestWindowTimestampMs)
        }

    @Test
    fun `negative boundary - reply inside 10m window to a 30m+ old message preserves Reply Reference Chaining without pulling other stale messages`() =
        runTest {
            val staleOther =
                createMockMessage(
                    id = 10L,
                    username = "StaleOther",
                    content = "Unrelated stale chatter from 45m ago",
                    timestamp = anchorTime - 45.minutes,
                )
            val staleRepliedTarget =
                createMockMessage(
                    id = 11L,
                    username = "Bob",
                    content = "Original question from 35m ago",
                    timestamp = anchorTime - 35.minutes,
                )
            val recentReply =
                createMockMessage(
                    id = 12L,
                    username = "Alice",
                    content = "Here is the answer to your earlier question",
                    referencedMessage = staleRepliedTarget,
                    timestamp = anchorTime - 2.minutes,
                )
            val latest =
                createMockMessage(
                    id = 13L,
                    username = "Charlie",
                    content = "Oh nice thanks",
                    timestamp = anchorTime,
                )

            val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })
            val allMessages = listOf(staleOther, staleRepliedTarget, recentReply, latest)

            val decision = collector.collectDecisionContext(allMessages)
            assertFalse(decision.transcript.contains("Unrelated stale chatter"), "Unrelated stale chatter should remain excluded")
            assertTrue(
                decision.transcript.contains(
                    "Alice (uid:12) 回覆 Bob (uid:11)「Original question from 35m ago」: Here is the answer to your earlier question",
                ),
                "Decision context should preserve Reply Reference Chaining to 35m-old target",
            )
            assertTrue(decision.transcript.contains("Charlie (uid:13): Oh nice thanks"), "Decision context should include latest turn")

            val reply = collector.collectReplyContext(allMessages)
            assertFalse(reply.transcript.contains("Unrelated stale chatter"), "Unrelated stale chatter should remain excluded from reply context")
            assertTrue(
                reply.transcript.contains(
                    "Alice (uid:12) 回覆 Bob (uid:11)「Original question from 35m ago」: Here is the answer to your earlier question",
                ),
                "Reply context should preserve Reply Reference Chaining to 35m-old target",
            )
            assertTrue(reply.transcript.contains("Charlie (uid:13): Oh nice thanks"), "Reply context should include latest turn")
        }

    @Test
    fun `third-party bot messages appear with BOT badge and uid in context while system notices stay filtered and evaluate rejects bots`() =
        runTest {
            val selfId = Snowflake(9999L)
            val humanMsg =
                createMockMessage(
                    id = 123456L,
                    username = "Alice",
                    content = "Did the server deploy?",
                    timestamp = anchorTime - 3.minutes,
                )
            val thirdPartyBotMsg =
                createMockMessage(
                    id = 789012L,
                    username = "NewsBot",
                    content = "Deploy succeeded v2.4.0",
                    isBot = true,
                    timestamp = anchorTime - 2.minutes,
                )
            val systemNotice =
                createMockMessage(
                    id = 555555L,
                    username = "System",
                    content = "Alice pinned a message",
                    type = MessageType.ChannelPinnedMessage,
                    timestamp = anchorTime - 1.minutes,
                )
            val lunaMsg =
                createMockMessage(
                    id = 300001L,
                    username = "Luna",
                    content = "好耶",
                    isBot = true,
                    authorId = selfId,
                    timestamp = anchorTime,
                )

            // Negative boundary: ChatterNoiseFilter.evaluate still rejects third-party bot messages
            val botEval = filter.evaluate(thirdPartyBotMsg, kordSelfId = selfId)
            assertIs<ChatterFilterResult.Rejected>(botEval)
            assertEquals(RejectionReason.BOT_MESSAGE, botEval.reason)

            val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })
            val messages = listOf(humanMsg, thirdPartyBotMsg, systemNotice, lunaMsg)

            val decision = collector.collectDecisionContext(messages, kordSelfId = selfId)
            assertTrue(decision.transcript.contains("Alice (uid:123456): Did the server deploy?"), "Human speaker should format with uid")
            assertTrue(decision.transcript.contains("NewsBot [BOT] (uid:789012): Deploy succeeded v2.4.0"), "Third-party bot should appear with [BOT] and uid")
            assertTrue(decision.transcript.contains("Luna (uid:9999): 好耶"), "Luna should appear with uid and without [BOT]")
            assertFalse(decision.transcript.contains("Luna [BOT]"), "Luna should never be tagged as [BOT]")
            assertFalse(decision.transcript.contains("pinned a message"), "System notices must remain filtered out")

            val reply = collector.collectReplyContext(messages, kordSelfId = selfId)
            assertEquals(
                "Alice (uid:123456): Did the server deploy?\n" +
                    "NewsBot [BOT] (uid:789012): Deploy succeeded v2.4.0\n" +
                    "Luna (uid:9999): 好耶",
                reply.transcript,
            )
        }
}
