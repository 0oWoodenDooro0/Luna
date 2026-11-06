package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.StickerItem
import dev.kord.core.entity.User
import dev.kord.core.event.message.ReactionAddEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SummaryServiceLookbackTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
    }

    private fun createMockMessage(
        id: Long,
        username: String,
        content: String,
        isBot: Boolean = false,
        timestamp: Instant = Instant.fromEpochMilliseconds(id * 1000),
        type: MessageType = MessageType.Default,
        stickers: List<StickerItem> = emptyList(),
        attachments: Set<Attachment> = emptySet(),
        webhookId: Snowflake? = null,
        embeds: List<dev.kord.core.entity.Embed> = emptyList(),
    ): Message =
        SummaryTestFixtures.createMockMessage(
            id = id,
            username = username,
            content = content,
            isBot = isBot,
            timestamp = timestamp,
            type = type,
            stickers = stickers,
            attachments = attachments,
            webhookId = webhookId,
            embeds = embeds,
        )

    @Test
    fun `AI triggers fetch_earlier_messages, preceding block is fetched across gap, prompt is segmented, and full summary returned`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInitialInput: SummaryInput? = null
            var capturedToolResponse: String? = null
            var sentReply: String? = null

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val msg1 = createMockMessage(9001L, "Alice", "Let's discuss Project Luna architecture", timestamp = baseTime)
            val msg2 = createMockMessage(9002L, "Bob", "We need to design the two-stage lookback", timestamp = baseTime + 5.minutes)
            // 25 minutes inactivity gap
            val msg3 = createMockMessage(9003L, "Charlie", "Now we can start implementation", timestamp = baseTime + 30.minutes)
            val targetMsg = createMockMessage(9004L, "Alice", "Can someone summarize what we decided?", timestamp = baseTime + 32.minutes)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                capturedInitialInput = firstArg<SummaryInput>()
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Need conversational background"),
                    continuation = { toolResponse ->
                        capturedToolResponse = toolResponse
                        SummaryResult.Success("TL;DR: Decided to implement two-stage lookback for Project Luna.")
                    },
                )
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { msg, _ ->
                        if (msg.id == targetMsg.id) {
                            listOf(msg1, msg2, msg3)
                        } else if (msg.id == msg3.id) {
                            listOf(msg1, msg2)
                        } else {
                            emptyList()
                        }
                    },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            // Verify initial input had tools configured
            val initialInput = checkNotNull(capturedInitialInput)
            val tools = checkNotNull(initialInput.tools)
            assertTrue(tools.any { it.actualFunctionDeclarations.any { decl -> decl.name == GeminiTools.FETCH_EARLIER_MESSAGES } })

            // Verify tool response sent to continuation contains segmented sections
            val toolResp = checkNotNull(capturedToolResponse)
            assertTrue(toolResp.contains("[前置歷史背景]"))
            assertTrue(toolResp.contains("[Alice]: Let's discuss Project Luna architecture"))
            assertTrue(toolResp.contains("[Bob]: We need to design the two-stage lookback"))
            assertTrue(toolResp.contains("[當前核心討論]"))
            assertTrue(toolResp.contains("[Charlie]: Now we can start implementation"))
            assertTrue(toolResp.contains("[Alice]: Can someone summarize what we decided?"))

            // Verify final reply and status
            assertEquals("TL;DR: Decided to implement two-stage lookback for Project Luna.", sentReply)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `AI does not trigger fetch_earlier_messages, standard summary returned directly`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null
            var historyFetchCount = 0

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val msg1 = createMockMessage(9011L, "Alice", "Hello team", timestamp = baseTime)
            val targetMsg = createMockMessage(9012L, "Bob", "Let's summarize", timestamp = baseTime + 2.minutes)

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } returns SummaryResult.Success("TL;DR: Standard direct summary")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { _, _ ->
                        historyFetchCount++
                        listOf(msg1)
                    },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            assertEquals("TL;DR: Standard direct summary", sentReply)
            assertEquals(1, historyFetchCount)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `AI triggers fetch_earlier_messages but no earlier messages exist, informs tool with no further history message`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedToolResponse: String? = null
            var sentReply: String? = null

            val targetMsg = createMockMessage(9021L, "SoloUser", "Stand-alone discussion kickoff")

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Checking for context"),
                    continuation = { toolResponse ->
                        capturedToolResponse = toolResponse
                        SummaryResult.Success("TL;DR: Kickoff summary with baseline only.")
                    },
                )
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { _, _ -> emptyList() },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            assertEquals(SummaryService.NO_FURTHER_HISTORY_MESSAGE, capturedToolResponse)
            assertEquals("TL;DR: Kickoff summary with baseline only.", sentReply)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `enforces single lookback limit by falling back to baseline summary when AI attempts second tool call`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null
            var secondRoundAttempted = false

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val msg1 = createMockMessage(9031L, "Alice", "Earlier topic", timestamp = baseTime)
            val msg2 = createMockMessage(9032L, "Bob", "Current topic", timestamp = baseTime + 30.minutes)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Round 1"),
                    continuation = { _ ->
                        // Attempting round 2 by returning another ToolCall
                        SummaryResult.ToolCall(
                            name = GeminiTools.FETCH_EARLIER_MESSAGES,
                            args = mapOf("reason" to "Round 2 attempt"),
                            continuation = {
                                secondRoundAttempted = true
                                SummaryResult.Success("Round 2 result")
                            },
                        )
                    },
                )
            }

            // Fallback call without tools
            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools == null }, any())
            } returns SummaryResult.Success("TL;DR: Graceful baseline summary after round limit.")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { _, _ -> listOf(msg1) },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(msg2)
            service.handleReaction(event)

            assertFalse(secondRoundAttempted, "Service must not execute round 2 lookback")
            assertEquals("TL;DR: Graceful baseline summary after round limit.", sentReply)
            assertEquals(SummaryStatus.DONE, service.getStatus(msg2.id))
        }

    @Test
    fun `falls back gracefully to baseline summary when tool continuation throws exception`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val msg1 = createMockMessage(9041L, "Alice", "Earlier topic", timestamp = baseTime)
            val msg2 = createMockMessage(9042L, "Bob", "Current topic", timestamp = baseTime + 30.minutes)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Need context"),
                    continuation = {
                        throw RuntimeException("Network socket timeout during continuation")
                    },
                )
            }

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools == null }, any())
            } returns SummaryResult.Success("TL;DR: Baseline summary after continuation exception.")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { _, _ -> listOf(msg1) },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(msg2)
            service.handleReaction(event)

            assertEquals("TL;DR: Baseline summary after continuation exception.", sentReply)
            assertEquals(SummaryStatus.DONE, service.getStatus(msg2.id))
        }

    @Test
    fun `falls back gracefully to baseline summary when tool continuation returns error`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val msg1 = createMockMessage(9051L, "Alice", "Earlier topic", timestamp = baseTime)
            val msg2 = createMockMessage(9052L, "Bob", "Current topic", timestamp = baseTime + 30.minutes)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Need context"),
                    continuation = {
                        SummaryResult.Error.Timeout("Gemini API request timed out during continuation")
                    },
                )
            }

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools == null }, any())
            } returns SummaryResult.Success("TL;DR: Baseline summary after continuation error.")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { _, _ -> listOf(msg1) },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(msg2)
            service.handleReaction(event)

            assertEquals("TL;DR: Baseline summary after continuation error.", sentReply)
            assertEquals(SummaryStatus.DONE, service.getStatus(msg2.id))
        }

    @Test
    fun `falls back gracefully to baseline summary when initial tool-enabled summarization throws exception`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null

            val targetMsg = createMockMessage(9061L, "Alice", "Target discussion")

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } throws RuntimeException("Gemini tool calling unsupported on this model")

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools == null }, any())
            } returns SummaryResult.Success("TL;DR: Baseline summary after initial tool exception.")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { _, _ -> emptyList() },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            assertEquals("TL;DR: Baseline summary after initial tool exception.", sentReply)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `falls back gracefully to baseline summary when initial tool-enabled summarization returns error`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null

            val targetMsg = createMockMessage(9071L, "Alice", "Target discussion")

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } returns SummaryResult.Error.Generic("Function calling disabled")

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools == null }, any())
            } returns SummaryResult.Success("TL;DR: Baseline summary after initial tool error.")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { _, _ -> emptyList() },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            assertEquals("TL;DR: Baseline summary after initial tool error.", sentReply)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `lookback traversal halts at another inactivity gap in earlier history block`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedToolResponse: String? = null

            val baseTime = Instant.parse("2026-11-06T08:00:00Z")
            val msg0 = createMockMessage(9080L, "Alice", "Old topic from 8am", timestamp = baseTime)
            // 2 hours gap between msg0 (08:00) and msg1 (10:00)
            val msg1 = createMockMessage(9081L, "Alice", "Earlier active topic 1", timestamp = baseTime + 120.minutes)
            val msg2 = createMockMessage(9082L, "Bob", "Earlier active topic 2", timestamp = baseTime + 125.minutes)
            // 25 minutes gap between msg2 (10:05) and msg3 (10:30)
            val msg3 = createMockMessage(9083L, "Charlie", "Baseline topic", timestamp = baseTime + 150.minutes)
            val targetMsg = createMockMessage(9084L, "Alice", "Wrap up", timestamp = baseTime + 152.minutes)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Need context"),
                    continuation = { toolResponse ->
                        capturedToolResponse = toolResponse
                        SummaryResult.Success("TL;DR: Done.")
                    },
                )
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> },
                    historyFetcher = { msg, _ ->
                        if (msg.id == targetMsg.id) {
                            listOf(msg0, msg1, msg2, msg3)
                        } else {
                            listOf(msg0, msg1, msg2)
                        }
                    },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            val response = checkNotNull(capturedToolResponse)
            assertTrue(response.contains("[Alice]: Earlier active topic 1"))
            assertTrue(response.contains("[Bob]: Earlier active topic 2"))
            assertFalse(response.contains("Old topic from 8am"), "Should stop at another inactivity gap")
        }

    @Test
    fun `lookback traversal halts at previous bot summary in earlier history block`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedToolResponse: String? = null

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val botMsg =
                createMockMessage(
                    id = 9091L,
                    username = "Luna",
                    content = "TL;DR: Earlier summary by bot\n核心重點:\n• Item 1",
                    isBot = true,
                    timestamp = baseTime,
                )
            val msg2 = createMockMessage(9092L, "Bob", "Discussion after bot summary", timestamp = baseTime + 2.minutes)
            // 25 minutes gap between msg2 and msg3
            val msg3 = createMockMessage(9093L, "Charlie", "Baseline message", timestamp = baseTime + 27.minutes)
            val targetMsg = createMockMessage(9094L, "Alice", "Current wrap up", timestamp = baseTime + 29.minutes)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Need context"),
                    continuation = { toolResponse ->
                        capturedToolResponse = toolResponse
                        SummaryResult.Success("TL;DR: Done.")
                    },
                )
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> },
                    historyFetcher = { msg, _ ->
                        if (msg.id == targetMsg.id) {
                            listOf(botMsg, msg2, msg3)
                        } else {
                            listOf(botMsg, msg2)
                        }
                    },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            val response = checkNotNull(capturedToolResponse)
            assertTrue(response.contains("[Bob]: Discussion after bot summary"))
            assertFalse(response.contains("Earlier summary by bot"), "Should halt at previous bot summary")
        }

    @Test
    fun `lookback traversal halts immediately when message across gap is a bot summary`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedToolResponse: String? = null

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val botMsg =
                createMockMessage(
                    id = 9101L,
                    username = "Luna",
                    content = "TL;DR: Bot summary immediately across gap\n核心重點:\n• Item 1",
                    isBot = true,
                    timestamp = baseTime,
                )
            // 25 minutes gap
            val msg3 = createMockMessage(9102L, "Charlie", "Baseline message", timestamp = baseTime + 25.minutes)
            val targetMsg = createMockMessage(9103L, "Alice", "Target message", timestamp = baseTime + 27.minutes)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Need context"),
                    continuation = { toolResponse ->
                        capturedToolResponse = toolResponse
                        SummaryResult.Success("TL;DR: Done.")
                    },
                )
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> },
                    historyFetcher = { msg, _ ->
                        if (msg.id == targetMsg.id) {
                            listOf(botMsg, msg3)
                        } else {
                            listOf(botMsg)
                        }
                    },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            assertEquals(SummaryService.NO_FURTHER_HISTORY_MESSAGE, capturedToolResponse)
        }

    @Test
    fun `lookback traversal respects max lookback messages limit of 20`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedToolResponse: String? = null

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            // Create 25 messages within the earlier active block (1 minute apart)
            val earlierMessages =
                (1..25).map { idx ->
                    createMockMessage(
                        id = 9200L + idx,
                        username = "User$idx",
                        content = "Earlier message number $idx",
                        timestamp = baseTime + idx.minutes,
                    )
                }
            // 25 minutes gap after the last earlier message
            val baselineTime = baseTime + 25.minutes + 25.minutes
            val targetMsg = createMockMessage(9300L, "Alice", "Target question", timestamp = baselineTime)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Need context"),
                    continuation = { toolResponse ->
                        capturedToolResponse = toolResponse
                        SummaryResult.Success("TL;DR: Done.")
                    },
                )
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> },
                    historyFetcher = { msg, _ ->
                        if (msg.id == targetMsg.id) {
                            earlierMessages
                        } else {
                            earlierMessages
                        }
                    },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            val response = checkNotNull(capturedToolResponse)
            // Should contain the last 20 messages (numbers 6 to 25)
            assertTrue(response.contains("[User25]: Earlier message number 25"))
            assertTrue(response.contains("[User6]: Earlier message number 6"))
            // Should NOT contain the oldest 5 messages (numbers 1 to 5)
            assertFalse(response.contains("[User5]: Earlier message number 5"))
            assertFalse(response.contains("[User1]: Earlier message number 1"))
        }

    @Test
    fun `lookback traversal excludes noise messages from earlier history block`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedToolResponse: String? = null

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val msg1 = createMockMessage(9401L, "Alice", "Legitimate discussion point", timestamp = baseTime)
            val slashCommand = createMockMessage(9402L, "Bob", "/help command", timestamp = baseTime + 1.minutes)
            val ambientBot = createMockMessage(9403L, "MusicBot", "!play music", isBot = true, timestamp = baseTime + 2.minutes)
            val msg4 = createMockMessage(9404L, "Charlie", "Another legitimate point", timestamp = baseTime + 3.minutes)
            // 25 minutes gap
            val targetMsg = createMockMessage(9405L, "Alice", "Target wrap up", timestamp = baseTime + 28.minutes)

            coEvery {
                summarizer.summarize(match<SummaryInput> { it.tools != null }, any())
            } answers {
                SummaryResult.ToolCall(
                    name = GeminiTools.FETCH_EARLIER_MESSAGES,
                    args = mapOf("reason" to "Need context"),
                    continuation = { toolResponse ->
                        capturedToolResponse = toolResponse
                        SummaryResult.Success("TL;DR: Done.")
                    },
                )
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> },
                    historyFetcher = { msg, _ ->
                        if (msg.id == targetMsg.id) {
                            listOf(msg1, slashCommand, ambientBot, msg4)
                        } else {
                            listOf(msg1, slashCommand, ambientBot, msg4)
                        }
                    },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            val response = checkNotNull(capturedToolResponse)
            assertTrue(response.contains("[Alice]: Legitimate discussion point"))
            assertTrue(response.contains("[Charlie]: Another legitimate point"))
            assertFalse(response.contains("/help command"), "Slash commands should be filtered out")
            assertFalse(response.contains("MusicBot"), "Third-party bot messages should be filtered out")
        }

    @Test
    fun `final reply sanitizes emojis and truncates text exceeding 2000 characters`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null

            val targetMsg = createMockMessage(9501L, "Alice", "Discussion")

            val longSummaryWithEmojis = "TL;DR: 🚀 Feature ready! 👍 " + "A".repeat(2500) + " 💡"

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } returns SummaryResult.Success(longSummaryWithEmojis)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, text -> sentReply = text },
                    historyFetcher = { _, _ -> emptyList() },
                    peekResolver = { null },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            val reply = checkNotNull(sentReply)
            assertFalse(EmojiSanitizer.containsEmoji(reply), "Reply should have emojis stripped")
            assertEquals(2000, reply.length)
            assertTrue(reply.endsWith("..."), "Reply should end with ellipsis")
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }
}
