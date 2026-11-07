package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `SummaryService delegates conversation slice and lookbackProvider to summarizer and sends reply on success`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var capturedLookbackProvider: (suspend (String) -> String?)? = null
            var sentReply: String? = null

            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val msg1 = createMockMessage(9001L, "Alice", "Let's discuss Project Luna architecture", timestamp = baseTime)
            val msg2 = createMockMessage(9002L, "Bob", "We need to design the two-stage lookback", timestamp = baseTime + 5.minutes)
            // 25 minutes inactivity gap
            val msg3 = createMockMessage(9003L, "Charlie", "Now we can start implementation", timestamp = baseTime + 30.minutes)
            val targetMsg = createMockMessage(9004L, "Alice", "Can someone summarize what we decided?", timestamp = baseTime + 32.minutes)

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg()
                capturedLookbackProvider = secondArg()
                SummaryResult.Success("TL;DR: Decided to implement two-stage lookback for Project Luna.")
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
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
                        ),
                    replySender = { _, text -> sentReply = text },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }

            // Verify SummaryService passed lookbackProvider seam
            val provider = checkNotNull(capturedLookbackProvider)
            val toolResponse = provider("Need conversational background")
            assertNotNull(toolResponse)
            assertTrue(toolResponse.contains("[前置歷史背景]"))
            assertTrue(toolResponse.contains("[Alice]: Let's discuss Project Luna architecture"))
            assertTrue(toolResponse.contains("[當前核心討論]"))
            assertTrue(toolResponse.contains("[Charlie]: Now we can start implementation"))

            // Verify final reply and status
            assertEquals("TL;DR: Decided to implement two-stage lookback for Project Luna.", sentReply)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `SummaryService delivers direct summary when summarizer returns Success without requiring lookback execution`() =
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
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ ->
                                historyFetchCount++
                                listOf(msg1)
                            },
                            peekResolver = { null },
                        ),
                    replySender = { _, text -> sentReply = text },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            assertEquals("TL;DR: Standard direct summary", sentReply)
            assertEquals(1, historyFetchCount)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `SummaryService handles summarizer Error by evicting from status cache to allow retry`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            val targetMsg = createMockMessage(9021L, "User", "Discussion content")

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } returns SummaryResult.Error.RateLimitExceeded()

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> emptyList() },
                            peekResolver = { null },
                        ),
                    replySender = { _, _ -> },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            assertNull(service.getStatus(targetMsg.id))
        }

    @Test
    fun `SummaryService handles summarizer unexpected exception by evicting from status cache`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var replySent: String? = null
            val targetMsg = createMockMessage(9031L, "User", "Discussion content")

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } throws RuntimeException("Unexpected internal network disconnect")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> emptyList() },
                            peekResolver = { null },
                        ),
                    replySender = { _, text -> replySent = text },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            assertNull(replySent)
            assertNull(service.getStatus(targetMsg.id))
        }
}
