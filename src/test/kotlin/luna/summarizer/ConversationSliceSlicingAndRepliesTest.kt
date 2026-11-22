package luna.summarizer

import dev.kord.common.entity.Snowflake
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.summarizer.ConversationSliceTestSupport.createMockAttachment
import luna.summarizer.ConversationSliceTestSupport.createMockMessage
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ConversationSliceSlicingAndRepliesTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
    }

    @Test
    fun `collectSlice performs bidirectional slicing prioritizing backward history and collecting forward remainder`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val prev1 = createMockMessage(1061L, "Alice", "Earlier 1", timestamp = baseTime)
            val prev2 = createMockMessage(1062L, "Bob", "Earlier 2", timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(1063L, "Charlie", "Target middle", timestamp = baseTime + 2.minutes)
            val next1 = createMockMessage(1064L, "Dave", "Later 1", timestamp = baseTime + 3.minutes)
            val next2 = createMockMessage(1065L, "Eve", "Later 2", timestamp = baseTime + 4.minutes)

            val collector =
                ConversationSliceCollector(
                    maxHistoryMessages = 4,
                    historyFetcher = { _, _ -> listOf(prev1, prev2) },
                    forwardFetcher = { _, limit -> listOf(next1, next2).take(limit) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("[Alice]: Earlier 1"))
            assertTrue(content.contains("[Bob]: Earlier 2"))
            assertTrue(content.contains("[Charlie]: Target middle"))
            assertTrue(content.contains("[Dave]: Later 1"))
            assertFalse(content.contains("[Eve]: Later 2"))
        }

    @Test
    fun `collectSlice halts forward traversal when forward inactivity gap exceeds limit`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val targetMsg = createMockMessage(1071L, "Alice", "Topic start", timestamp = baseTime)
            val next1 = createMockMessage(1072L, "Bob", "Immediate reply", timestamp = baseTime + 2.minutes)
            val next2 = createMockMessage(1073L, "Charlie", "Too late reply", timestamp = baseTime + 20.minutes)

            val collector =
                ConversationSliceCollector(
                    inactivityGap = 15.minutes,
                    historyFetcher = { _, _ -> emptyList() },
                    forwardFetcher = { _, _ -> listOf(next1, next2) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("[Alice]: Topic start"))
            assertTrue(content.contains("[Bob]: Immediate reply"))
            assertFalse(content.contains("Too late reply"))
        }

    @Test
    fun `collectSlice halts forward traversal upon encountering bot summary message`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val targetMsg = createMockMessage(1081L, "Alice", "Let us begin", timestamp = baseTime)
            val next1 = createMockMessage(1082L, "Bob", "Great point", timestamp = baseTime + 1.minutes)
            val botSummary =
                createMockMessage(
                    1083L,
                    "LunaBot",
                    "TL;DR:\nThis topic was already summarized.",
                    timestamp = baseTime + 2.minutes,
                    isBot = true,
                )
            val next3 = createMockMessage(1084L, "Charlie", "Unrelated future topic", timestamp = baseTime + 3.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> emptyList() },
                    forwardFetcher = { _, _ -> listOf(next1, botSummary, next3) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("[Alice]: Let us begin"))
            assertTrue(content.contains("[Bob]: Great point"))
            assertFalse(content.contains("TL;DR:"))
            assertFalse(content.contains("Unrelated future topic"))
        }

    @Test
    fun `collectSlice resolves cross-temporal reply reference outside window and formats inline attribution`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val externalMsg =
                createMockMessage(
                    2001L,
                    "Alice",
                    "We need to decide on architecture first",
                    timestamp = baseTime - 60.minutes,
                )
            val replyMsg =
                createMockMessage(
                    2002L,
                    "Bob",
                    "I agree with the architecture choice",
                    timestamp = baseTime,
                    referencedMessage = externalMsg,
                )
            val targetMsg =
                createMockMessage(
                    2003L,
                    "Charlie",
                    "Let's proceed",
                    timestamp = baseTime + 1.minutes,
                )

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(replyMsg) },
                    forwardFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("[Bob 回覆 Alice「We need to decide on architecture first」]: I agree with the architecture choice"))
            assertTrue(content.contains("[Charlie]: Let's proceed"))
        }

    @Test
    fun `collectSlice formats Tenor GIF embed as GIF preview without ingesting GIF thumbnail image`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val gifEmbed =
                mockk<dev.kord.core.entity.Embed>(relaxed = true).also {
                    every { it.title } returns "Happy Dance"
                    every { it.description } returns null
                    every { it.url } returns "https://tenor.com/view/happy-dance-123"
                }
            val msg1 =
                createMockMessage(
                    id = 2041L,
                    username = "Alice",
                    content = "Check this",
                    timestamp = baseTime,
                    embeds = listOf(gifEmbed),
                )
            val targetMsg =
                createMockMessage(
                    id = 2042L,
                    username = "Bob",
                    content = "Nice",
                    timestamp = baseTime + 1.minutes,
                )

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1) },
                    forwardFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertTrue(
                slice.summaryInput.content.contains("[Alice]: Check this [GIF: Happy Dance]"),
                "Expected Tenor embed to render as [GIF: Happy Dance], got: ${slice.summaryInput.content}",
            )
            assertTrue(
                slice.summaryInput.imageAttachments.isEmpty(),
                "Expected Summary Pipeline to exclude GIF embed images (includeStickersAndGifEmbeds = false)",
            )
        }

    @Test
    fun `collectSlice admits Luna chatter message into Conversation Slice with Luna author attribution`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val selfId = Snowflake(42L)
            val kordMock = mockk<dev.kord.core.Kord>()
            every { kordMock.selfId } returns selfId

            val filter = DialogueNoiseFilter(kord = kordMock)
            val userMsg = createMockMessage(3001L, "Alice", "What do you think about Kotlin 2.0?", timestamp = baseTime)
            val lunaChatterMsg =
                createMockMessage(
                    id = 3002L,
                    username = "Luna",
                    content = "The K2 compiler makes compilation much faster!",
                    isBot = true,
                    timestamp = baseTime + 1.minutes,
                    authorId = selfId,
                )
            val targetMsg = createMockMessage(3003L, "Bob", "Agreed, great point!", timestamp = baseTime + 2.minutes)

            val collector =
                ConversationSliceCollector(
                    noiseFilter = filter,
                    historyFetcher = { _, _ -> listOf(userMsg, lunaChatterMsg) },
                    forwardFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("[Alice]: What do you think about Kotlin 2.0?"))
            assertTrue(content.contains("[Luna]: The K2 compiler makes compilation much faster!"))
            assertTrue(content.contains("[Bob]: Agreed, great point!"))
        }

    @Test
    fun `collectSlice halts history backtracking when encountering Luna summary message in registry`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val selfId = Snowflake(42L)
            val kordMock = mockk<dev.kord.core.Kord>()
            every { kordMock.selfId } returns selfId

            val registry = luna.core.BoundedSummaryRegistry()
            val summaryId = Snowflake(3012L)
            registry.register(summaryId)

            val filter = DialogueNoiseFilter(kord = kordMock, summaryRegistry = registry)

            val beforeSummary = createMockMessage(3011L, "Alice", "Old topic before summary", timestamp = baseTime)
            val lunaSummaryMsg =
                createMockMessage(
                    id = 3012L,
                    username = "Luna",
                    content = "Summary of old topic without standard prefix",
                    isBot = true,
                    timestamp = baseTime + 1.minutes,
                    authorId = selfId,
                )
            val afterSummary = createMockMessage(3013L, "Bob", "Starting new discussion", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(3014L, "Charlie", "Replying to new discussion", timestamp = baseTime + 3.minutes)

            val collector =
                ConversationSliceCollector(
                    noiseFilter = filter,
                    historyFetcher = { _, _ -> listOf(beforeSummary, lunaSummaryMsg, afterSummary) },
                    forwardFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertFalse(content.contains("Old topic before summary"))
            assertFalse(content.contains("Summary of old topic without standard prefix"))
            assertTrue(content.contains("[Bob]: Starting new discussion"))
            assertTrue(content.contains("[Charlie]: Replying to new discussion"))
        }

    @Test
    fun `collectSlice halts history backtracking when encountering Luna summary message with structural marker`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val selfId = Snowflake(42L)
            val kordMock = mockk<dev.kord.core.Kord>()
            every { kordMock.selfId } returns selfId

            // In-memory registry is fresh (e.g. after daemon restart)
            val registry = luna.core.BoundedSummaryRegistry()
            val filter = DialogueNoiseFilter(kord = kordMock, summaryRegistry = registry)

            val beforeSummary = createMockMessage(3021L, "Alice", "Discussion prior to rebooted summary", timestamp = baseTime)
            val lunaSummaryMsg =
                createMockMessage(
                    id = 3022L,
                    username = "Luna",
                    content = "TL;DR: Historic summary prior to reboot",
                    isBot = true,
                    timestamp = baseTime + 1.minutes,
                    authorId = selfId,
                )
            val afterSummary = createMockMessage(3023L, "Bob", "Active topic", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(3024L, "Charlie", "Target msg", timestamp = baseTime + 3.minutes)

            val collector =
                ConversationSliceCollector(
                    noiseFilter = filter,
                    historyFetcher = { _, _ -> listOf(beforeSummary, lunaSummaryMsg, afterSummary) },
                    forwardFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertFalse(content.contains("Discussion prior to rebooted summary"))
            assertFalse(content.contains("TL;DR: Historic summary prior to reboot"))
            assertTrue(content.contains("[Bob]: Active topic"))
            assertTrue(content.contains("[Charlie]: Target msg"))
        }

    @Test
    fun `collectSlice falls back to data author username when message author is null`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val embed = SummaryTestFixtures.createMockEmbed(title = "Sample Clean Post", description = "Extracted article body")
            val targetMsg =
                createMockMessage(
                    id = 8001L,
                    username = "MasqueradedAlice",
                    content = "Cleaned post link https://example.com/post",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    embeds = listOf(embed),
                    authorIsNull = true,
                )

            val collector =
                ConversationSliceCollector(
                    peekResolver = { null },
                    noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                    historyFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertTrue(slice.summaryInput.content.contains("[MasqueradedAlice]: Cleaned post link https://example.com/post"))
        }

    @Test
    fun `collectSlice falls back to single target message when historyFetcher throws`() =
        runTest {
            val targetMsg = createMockMessage(9001L, "Charlie", "Target message content")
            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> throw RuntimeException("Discord API 403 Forbidden") },
                    peekResolver = { null },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertTrue(slice.summaryInput.content.contains("Target message content"))
        }
}
