package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.socialpeek.model.Author
import dev.socialpeek.model.Comment
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ConversationSliceCollectorTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
    }

    private fun createMockAttachment(
        url: String,
        contentType: String?,
        filename: String,
        size: Int = 1024 * 1024,
    ): Attachment {
        val attachment = mockk<Attachment>()
        every { attachment.url } returns url
        every { attachment.contentType } returns contentType
        every { attachment.filename } returns filename
        every { attachment.size } returns size
        return attachment
    }

    private fun createMockMessage(
        id: Long,
        username: String,
        content: String,
        isBot: Boolean = false,
        timestamp: Instant = Instant.fromEpochMilliseconds(id * 1000),
        attachments: Set<Attachment> = emptySet(),
        type: MessageType = MessageType.Default,
        referencedMessage: Message? = null,
        authorId: Snowflake? = null,
        webhookId: Snowflake? = null,
        embeds: List<dev.kord.core.entity.Embed> = emptyList(),
        authorIsNull: Boolean = false,
    ): Message {
        val author =
            if (authorIsNull) {
                null
            } else {
                mockk<User>().also {
                    every { it.username } returns username
                    every { it.isBot } returns isBot
                    every { it.id } returns (authorId ?: Snowflake(id))
                }
            }

        val message = mockk<Message>()
        every { message.id } returns Snowflake(id)
        every { message.author } returns author
        every { message.content } returns content
        every { message.timestamp } returns timestamp
        every { message.attachments } returns attachments
        every { message.type } returns type
        every { message.stickers } returns emptyList()
        every { message.embeds } returns embeds
        every { message.webhookId } returns webhookId
        every { message.data.author.username } returns username
        every { message.data.webhookId.value } returns webhookId
        every { message.referencedMessage } returns referencedMessage
        every { message.messageReference } returns null
        return message
    }

    @Test
    fun `collectSlice resolves valid image attachments and drops oversized or spoofed ones`() =
        runTest {
            val collector =
                ConversationSliceCollector(
                    maxImageBytes = 5 * 1024 * 1024,
                    historyFetcher = { _, _ -> emptyList() },
                )

            val validImg = createMockAttachment("https://cdn.discordapp.com/valid.png", "image/png", "valid.png", size = 2 * 1024 * 1024)
            val oversizedImg = createMockAttachment("https://cdn.discordapp.com/large.jpg", "image/jpeg", "large.jpg", size = 8 * 1024 * 1024)
            val spoofedImg = createMockAttachment("https://cdn.discordapp.com/fake.png", "application/octet-stream", "fake.png", size = 1024)
            val nonImg = createMockAttachment("https://cdn.discordapp.com/doc.pdf", "application/pdf", "doc.pdf", size = 1024)

            val message =
                createMockMessage(
                    id = 1L,
                    username = "Alice",
                    content = "Check files",
                    attachments = setOf(validImg, oversizedImg, spoofedImg, nonImg),
                )
            val slice = collector.collectSlice(message)

            assertNotNull(slice)
            assertEquals(1, slice.summaryInput.imageAttachments.size)
            val retainedImage = slice.summaryInput.imageAttachments.first()
            assertEquals("https://cdn.discordapp.com/valid.png", retainedImage.url)
            assertEquals("image/png", retainedImage.mimeType)
            assertEquals("valid.png", retainedImage.filename)
        }

    @Test
    fun `collectSlice on plain message performs dual-boundary backtracking and attaches lookback provider`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(101L, "Alice", "Meeting start", timestamp = baseTime)
            val msg2 = createMockMessage(102L, "Bob", "Agreed on plan", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(103L, "Charlie", "Let's summarize", timestamp = baseTime + 4.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertFalse(slice.durationExceeded)
            assertNull(slice.primaryUrl)
            assertNotNull(slice.lookbackProvider)

            assertTrue(slice.summaryInput.content.contains("[Alice]: Meeting start"))
            assertTrue(slice.summaryInput.content.contains("[Bob]: Agreed on plan"))
            assertTrue(slice.summaryInput.content.contains("[Charlie]: Let's summarize"))
            assertNull(slice.summaryInput.tools)
        }

    @Test
    fun `collectSlice halts backtracking when inactivity gap exceeds limit`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(201L, "Alice", "Old topic", timestamp = baseTime)
            // 20 minutes gap
            val msg2 = createMockMessage(202L, "Bob", "New topic", timestamp = baseTime + 20.minutes)
            val targetMsg = createMockMessage(203L, "Charlie", "Wrap up", timestamp = baseTime + 22.minutes)

            val collector =
                ConversationSliceCollector(
                    inactivityGap = 15.minutes,
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertFalse(slice.summaryInput.content.contains("Old topic"))
            assertTrue(slice.summaryInput.content.contains("[Bob]: New topic"))
            assertTrue(slice.summaryInput.content.contains("[Charlie]: Wrap up"))
        }

    @Test
    fun `collectSlice flags durationExceeded when YouTube video exceeds 2 hour limit`() =
        runTest {
            val targetMsg = createMockMessage(301L, "Alice", "Watch this https://www.youtube.com/watch?v=dQw4w9WgXcQ")

            val fakePost =
                PeekPost(
                    id = "dQw4w9WgXcQ",
                    originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    platform = Platform.YOUTUBE,
                    title = "Super Long Stream",
                    content = "Stream description",
                    author = Author(username = "Creator"),
                    rawData = mapOf("durationSeconds" to "7500"),
                )

            val collector =
                ConversationSliceCollector(
                    peekResolver = { fakePost },
                    historyFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertTrue(slice.durationExceeded)
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", slice.primaryUrl)
        }

    @Test
    fun `lookbackProvider returns formatted expanded dialogue when earlier messages are found`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T10:00:00Z")
            val earlier1 = createMockMessage(401L, "Alice", "Earlier topic discussion", timestamp = baseTime)
            val earlier2 = createMockMessage(402L, "Bob", "Important historical context", timestamp = baseTime + 1.minutes)
            val recent1 = createMockMessage(403L, "Charlie", "Current decision", timestamp = baseTime + 30.minutes)
            val targetMsg = createMockMessage(404L, "Alice", "Summarize please", timestamp = baseTime + 32.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { msg, _ ->
                        if (msg.id == targetMsg.id) {
                            listOf(earlier1, earlier2, recent1)
                        } else {
                            listOf(earlier1, earlier2)
                        }
                    },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val lookback = checkNotNull(slice.lookbackProvider)

            val expandedContent = lookback("Need historical background")
            assertNotNull(expandedContent)
            assertTrue(expandedContent.contains("[前置歷史背景]"))
            assertTrue(expandedContent.contains("[Alice]: Earlier topic discussion"))
            assertTrue(expandedContent.contains("[Bob]: Important historical context"))
            assertTrue(expandedContent.contains("[當前核心討論]"))
            assertTrue(expandedContent.contains("[Charlie]: Current decision"))
        }

    @Test
    fun `lookbackProvider returns no further history message when earlier list is empty`() =
        runTest {
            val targetMsg = createMockMessage(501L, "Alice", "Single target message")

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val lookback = checkNotNull(slice.lookbackProvider)

            val expandedContent = lookback("Need background")
            assertEquals(ConversationSliceCollector.NO_FURTHER_HISTORY_MESSAGE, expandedContent)
        }

    @Test
    fun `collectSlice respects maxCharacterBudget halting backtracking when accumulated characters exceed limit`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(601L, "Alice", "A".repeat(300), timestamp = baseTime)
            val msg2 = createMockMessage(602L, "Bob", "B".repeat(300), timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(603L, "Charlie", "C".repeat(100), timestamp = baseTime + 2.minutes)

            val collector =
                ConversationSliceCollector(
                    maxCharacterBudget = 500,
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val lines = slice.summaryInput.content.lines()
            assertEquals(2, lines.size)
            assertTrue(lines.first().contains("B".repeat(300)))
            assertTrue(lines.last().contains("C".repeat(100)))
        }

    @Test
    fun `collectSlice normalizes history provided in reverse chronological order`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(701L, "Alice", "First message", timestamp = baseTime)
            val msg2 = createMockMessage(702L, "Bob", "Second message", timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(703L, "Charlie", "Third message", timestamp = baseTime + 2.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg2, msg1) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            val idx1 = content.indexOf("First message")
            val idx2 = content.indexOf("Second message")
            val idx3 = content.indexOf("Third message")
            assertTrue(idx1 != -1 && idx2 != -1 && idx3 != -1)
            assertTrue(idx1 < idx2)
            assertTrue(idx2 < idx3)
        }

    @Test
    fun `collectSlice filters noise messages including slash commands, system messages, and blank messages`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(801L, "Alice", "Valid message", timestamp = baseTime)
            val slashCmd = createMockMessage(802L, "Bob", "/poll create \"Lunch\"", timestamp = baseTime + 1.minutes)
            val sysMsg =
                createMockMessage(
                    803L,
                    "System",
                    "Alice pinned a message",
                    timestamp = baseTime + 2.minutes,
                    type = MessageType.ChannelPinnedMessage,
                )
            val blankMsg = createMockMessage(804L, "Dave", "   \t\n  ", timestamp = baseTime + 3.minutes)
            val targetMsg = createMockMessage(805L, "Eve", "Wrap up", timestamp = baseTime + 4.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, slashCmd, sysMsg, blankMsg) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("Valid message"))
            assertFalse(content.contains("/poll create"))
            assertFalse(content.contains("pinned a message"))
            assertTrue(content.contains("Wrap up"))
        }

    @Test
    fun `collectSlice truncates images exceeding maxImages budget`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val img1 = createMockAttachment("https://cdn.discordapp.com/1.png", "image/png", "1.png")
            val img2 = createMockAttachment("https://cdn.discordapp.com/2.png", "image/png", "2.png")
            val img3 = createMockAttachment("https://cdn.discordapp.com/3.png", "image/png", "3.png")

            val msg1 = createMockMessage(901L, "Alice", "Image 1", timestamp = baseTime, attachments = setOf(img1))
            val msg2 = createMockMessage(902L, "Bob", "Image 2", timestamp = baseTime + 1.minutes, attachments = setOf(img2))
            val targetMsg = createMockMessage(903L, "Charlie", "Image 3", timestamp = baseTime + 2.minutes, attachments = setOf(img3))

            val collector =
                ConversationSliceCollector(
                    maxImages = 2,
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertEquals(2, slice.summaryInput.imageAttachments.size)
            // Target image always retained, plus newest preceding (img2)
            assertEquals("https://cdn.discordapp.com/2.png", slice.summaryInput.imageAttachments[0].url)
            assertEquals("https://cdn.discordapp.com/3.png", slice.summaryInput.imageAttachments[1].url)
        }

    @Test
    fun `collectSlice stops backtracking and discards earlier messages when bot summary is encountered`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(1001L, "Alice", "Before bot summary", timestamp = baseTime)
            val botSummary = createMockMessage(1002L, "SummaryBot", "TL;DR: Earlier topic summarized", isBot = true, timestamp = baseTime + 1.minutes)
            val msg2 = createMockMessage(1003L, "Bob", "After bot summary", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(1004L, "Charlie", "Target message", timestamp = baseTime + 3.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, botSummary, msg2) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertFalse(slice.summaryInput.content.contains("Before bot summary"))
            assertFalse(slice.summaryInput.content.contains("Earlier topic summarized"))
            assertTrue(slice.summaryInput.content.contains("[Bob]: After bot summary"))
            assertTrue(slice.summaryInput.content.contains("[Charlie]: Target message"))
        }

    @Test
    fun `collectSlice retains single message when immediate predecessor is bot summary`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val botMsg = createMockMessage(1011L, "SummaryBot", "TL;DR: Previous summary", isBot = true, timestamp = baseTime)
            val targetMsg = createMockMessage(1012L, "Alice", "Single target message", timestamp = baseTime + 1.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(botMsg) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertFalse(slice.summaryInput.content.contains("Previous summary"))
            assertEquals("Single target message", slice.summaryInput.content.trim())
        }

    @Test
    fun `collectSlice retains single message when immediate predecessor exceeds inactivity gap`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val oldMsg = createMockMessage(1021L, "Alice", "Very old conversation", timestamp = baseTime)
            val targetMsg = createMockMessage(1022L, "Bob", "Isolated message", timestamp = baseTime + 30.minutes)

            val collector =
                ConversationSliceCollector(
                    inactivityGap = 15.minutes,
                    historyFetcher = { _, _ -> listOf(oldMsg) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertFalse(slice.summaryInput.content.contains("Very old conversation"))
            assertEquals("Isolated message", slice.summaryInput.content.trim())
        }

    @Test
    fun `collectSlice with multiple messages and images extracts images and formats markers in transcript`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val img1 = createMockAttachment("https://cdn.discordapp.com/screenshot.png", "image/png", "screenshot.png")
            val img2 = createMockAttachment("https://cdn.discordapp.com/photo.jpg", "image/jpeg", "photo.jpg")

            val msg1 = createMockMessage(1031L, "Alice", "Check screenshot", timestamp = baseTime, attachments = setOf(img1))
            val msg2 = createMockMessage(1032L, "Bob", "Look at photo", timestamp = baseTime + 1.minutes, attachments = setOf(img2))
            val targetMsg = createMockMessage(1033L, "Charlie", "Review these", timestamp = baseTime + 2.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertEquals(2, slice.summaryInput.imageAttachments.size)
            assertTrue(slice.summaryInput.content.contains("[Alice]: Check screenshot [附帶圖片 1: screenshot.png]"))
            assertTrue(slice.summaryInput.content.contains("[Bob]: Look at photo [附帶圖片 2: photo.jpg]"))
            assertTrue(slice.summaryInput.content.contains("[Charlie]: Review these"))
        }

    @Test
    fun `collectSlice with image-only messages formats author attribution with image marker and preserves images`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val img1 = createMockAttachment("https://cdn.discordapp.com/pure.png", "image/png", "pure.png")
            val targetImg = createMockAttachment("https://cdn.discordapp.com/target.png", "image/png", "target.png")

            val msg1 = createMockMessage(1041L, "Alice", "", timestamp = baseTime, attachments = setOf(img1))
            val targetMsg = createMockMessage(1042L, "Bob", "", timestamp = baseTime + 1.minutes, attachments = setOf(targetImg))

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1) },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertEquals(2, slice.summaryInput.imageAttachments.size)
            assertTrue(slice.summaryInput.content.contains("[Alice]: [附帶圖片 1: pure.png]"))
            assertTrue(slice.summaryInput.content.contains("[Bob]: [附帶圖片 2: target.png]"))
        }

    @Test
    fun `collectSlice truncates images when target message alone exceeds maxImages budget`() =
        runTest {
            val img1 = createMockAttachment("https://cdn.discordapp.com/1.png", "image/png", "1.png")
            val img2 = createMockAttachment("https://cdn.discordapp.com/2.png", "image/png", "2.png")
            val img3 = createMockAttachment("https://cdn.discordapp.com/3.png", "image/png", "3.png")

            val targetMsg = createMockMessage(1051L, "Alice", "Three images", attachments = setOf(img1, img2, img3))

            val collector =
                ConversationSliceCollector(
                    maxImages = 2,
                    historyFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertEquals(2, slice.summaryInput.imageAttachments.size)
            assertEquals("https://cdn.discordapp.com/1.png", slice.summaryInput.imageAttachments[0].url)
            assertEquals("https://cdn.discordapp.com/2.png", slice.summaryInput.imageAttachments[1].url)
        }

    @Test
    fun `resolves default configuration values when environment is unconfigured`() {
        assertEquals(50, ConversationSliceCollector.resolveMaxHistoryMessages())
        assertEquals(15.minutes, ConversationSliceCollector.resolveInactivityGap())
        assertEquals(20000, ConversationSliceCollector.resolveMaxCharacterBudget())
        assertEquals(10, ConversationSliceCollector.resolveMaxImages())
        assertEquals(20, ConversationSliceCollector.resolveMaxLookbackMessages())
    }

    @Test
    fun `resolves custom configuration values from environment with bounds clamping`() {
        val env =
            mapOf(
                "SUMMARY_MAX_HISTORY_MESSAGES" to "30",
                "SUMMARY_INACTIVITY_GAP_MINUTES" to "25",
                "SUMMARY_MAX_CHARACTER_BUDGET" to "12000",
                "SUMMARY_MAX_IMAGES" to "8",
                "SUMMARY_MAX_LOOKBACK_MESSAGES" to "10",
            )
        assertEquals(30, ConversationSliceCollector.resolveMaxHistoryMessages(env))
        assertEquals(25.minutes, ConversationSliceCollector.resolveInactivityGap(env))
        assertEquals(12000, ConversationSliceCollector.resolveMaxCharacterBudget(env))
        assertEquals(8, ConversationSliceCollector.resolveMaxImages(env))
        assertEquals(10, ConversationSliceCollector.resolveMaxLookbackMessages(env))

        val clampedEnv =
            mapOf(
                "SUMMARY_MAX_HISTORY_MESSAGES" to "500",
                "SUMMARY_MAX_LOOKBACK_MESSAGES" to "0",
            )
        assertEquals(100, ConversationSliceCollector.resolveMaxHistoryMessages(clampedEnv))
        assertEquals(1, ConversationSliceCollector.resolveMaxLookbackMessages(clampedEnv))
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
    fun `collectSlice formats normally without inline quote when referenced message is already in slice window`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val inWindowMsg =
                createMockMessage(
                    2011L,
                    "Alice",
                    "Plan proposal",
                    timestamp = baseTime,
                )
            val replyMsg =
                createMockMessage(
                    2012L,
                    "Bob",
                    "Looks good",
                    timestamp = baseTime + 1.minutes,
                    referencedMessage = inWindowMsg,
                )
            val targetMsg =
                createMockMessage(
                    2013L,
                    "Charlie",
                    "Finalizing",
                    timestamp = baseTime + 2.minutes,
                )

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(inWindowMsg, replyMsg) },
                    forwardFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("[Alice]: Plan proposal"))
            assertTrue(content.contains("[Bob]: Looks good"))
            assertFalse(content.contains("回覆 Alice"))
        }

    @Test
    fun `collectSlice bounds external reference lookups to maximum of 3 messages`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val ext1 = createMockMessage(2021L, "Ext1", "External 1", timestamp = baseTime - 60.minutes)
            val ext2 = createMockMessage(2022L, "Ext2", "External 2", timestamp = baseTime - 50.minutes)
            val ext3 = createMockMessage(2023L, "Ext3", "External 3", timestamp = baseTime - 40.minutes)
            val ext4 = createMockMessage(2024L, "Ext4", "External 4", timestamp = baseTime - 30.minutes)

            val r1 = createMockMessage(2025L, "U1", "Reply 1", timestamp = baseTime, referencedMessage = ext1)
            val r2 = createMockMessage(2026L, "U2", "Reply 2", timestamp = baseTime + 1.minutes, referencedMessage = ext2)
            val r3 = createMockMessage(2027L, "U3", "Reply 3", timestamp = baseTime + 2.minutes, referencedMessage = ext3)
            val r4 = createMockMessage(2028L, "U4", "Reply 4", timestamp = baseTime + 3.minutes, referencedMessage = ext4)
            val targetMsg = createMockMessage(2029L, "U5", "Target", timestamp = baseTime + 4.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(r1, r2, r3, r4) },
                    forwardFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("[U1 回覆 Ext1「External 1」]: Reply 1"))
            assertTrue(content.contains("[U2 回覆 Ext2「External 2」]: Reply 2"))
            assertTrue(content.contains("[U3 回覆 Ext3「External 3」]: Reply 3"))
            assertTrue(content.contains("[U4]: Reply 4"))
            assertFalse(content.contains("回覆 Ext4"))
        }

    @Test
    fun `collectSlice clamps reply reference excerpt to 40 characters with ellipsis`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val longContent = "A".repeat(60)
            val ext = createMockMessage(2031L, "Alice", longContent, timestamp = baseTime - 60.minutes)
            val replyMsg = createMockMessage(2032L, "Bob", "Comment", timestamp = baseTime, referencedMessage = ext)
            val targetMsg = createMockMessage(2033L, "Charlie", "Target", timestamp = baseTime + 1.minutes)

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(replyMsg) },
                    forwardFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            val expectedExcerpt = "A".repeat(37) + "..."
            assertTrue(content.contains("[Bob 回覆 Alice「$expectedExcerpt」]: Comment"))
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
    fun `collectSlice formats Discord embeds with title, description, or attachments`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val lunaWebhookId = Snowflake(777L)
            val img = createMockAttachment("https://cdn.example.com/chart.png", "image/png", "chart.png")

            val both =
                createMockMessage(
                    id = 8201L,
                    username = "MasqueradedAlice",
                    content = "Update",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime,
                    attachments = setOf(img),
                    embeds = listOf(SummaryTestFixtures.createMockEmbed(title = "Preview Title", description = "Preview Desc")),
                )
            val onlyTitle =
                createMockMessage(
                    id = 8202L,
                    username = "Bob",
                    content = "Msg 1",
                    timestamp = baseTime + 1.minutes,
                    embeds = listOf(SummaryTestFixtures.createMockEmbed(title = "Only Title", description = null)),
                )
            val onlyDesc =
                createMockMessage(
                    id = 8203L,
                    username = "Charlie",
                    content = "Msg 2",
                    timestamp = baseTime + 2.minutes,
                    embeds = listOf(SummaryTestFixtures.createMockEmbed(title = "", description = "Only Description")),
                )
            val targetMsg =
                createMockMessage(
                    id = 8204L,
                    username = "Dave",
                    content = "Target",
                    timestamp = baseTime + 3.minutes,
                )

            val collector =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(both, onlyTitle, onlyDesc) },
                    peekResolver = { null },
                    noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            val content = slice.summaryInput.content
            assertTrue(content.contains("[MasqueradedAlice]: Update [連結預覽: Preview Title - Preview Desc] [附帶圖片 1: chart.png]"))
            assertTrue(content.contains("[Bob]: Msg 1 [連結預覽: Only Title]"))
            assertTrue(content.contains("[Charlie]: Msg 2 [連結預覽: Only Description]"))
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

    @Test
    fun `collectSlice resolves most recent SocialPeek URL via peekResolver and degrades cleanly when unresolved`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(9101L, "Alice", "Old post: https://x.com/alice/status/111", timestamp = baseTime)
            val msg2 = createMockMessage(9102L, "Bob", "New post: https://x.com/bob/status/222", timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(9103L, "Charlie", "Thoughts?", timestamp = baseTime + 2.minutes)

            var resolvedUrl: String? = null
            val enrichedSlice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                    peekResolver = { url ->
                        resolvedUrl = url
                        PeekPost(
                            platform = Platform.X,
                            id = "222",
                            originalUrl = url,
                            author = Author(username = "bob"),
                            title = "Second Post Title",
                            content = "Resolved social post body.",
                            comments = listOf(Comment(author = "reader1", content = "Clear explanation")),
                        )
                    },
                ).collectSlice(targetMsg)

            assertNotNull(enrichedSlice)
            assertEquals("https://x.com/bob/status/222", resolvedUrl)
            assertEquals("https://x.com/bob/status/222", enrichedSlice.summaryInput.url)
            assertEquals("Second Post Title", enrichedSlice.summaryInput.title)
            assertEquals(listOf("Clear explanation"), enrichedSlice.summaryInput.comments)
            assertTrue(enrichedSlice.summaryInput.content.contains("Resolved social post body."))
            assertTrue(enrichedSlice.summaryInput.webUrls.isEmpty())

            val fallbackSlice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg2) },
                    peekResolver = { null },
                ).collectSlice(targetMsg)

            assertNotNull(fallbackSlice)
            assertEquals("https://x.com/bob/status/222", fallbackSlice.summaryInput.url)
            assertNull(fallbackSlice.summaryInput.title)
            assertEquals(emptyList(), fallbackSlice.summaryInput.comments)
            assertTrue(fallbackSlice.summaryInput.content.contains("[Bob]: New post: https://x.com/bob/status/222"))
            assertNotNull(fallbackSlice.lookbackProvider)
        }

    @Test
    fun `collectSlice skips peekResolver for general public web URLs populates deduplicated webUrls and preserves lookbackProvider`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val embed = SummaryTestFixtures.createMockEmbed(title = "OpenGraph Title", description = "OpenGraph snippet")
            val msg1 =
                createMockMessage(
                    9201L,
                    "Alice",
                    "Read https://kotlinlang.org/docs/whatsnew20.html",
                    timestamp = baseTime,
                    embeds = listOf(embed),
                )
            val msg2 =
                createMockMessage(
                    9202L,
                    "Bob",
                    "Also check https://docs.example.org/guide and https://kotlinlang.org/docs/whatsnew20.html/",
                    timestamp = baseTime + 1.minutes,
                )
            val targetMsg = createMockMessage(9203L, "Charlie", "Can someone summarize these docs?", timestamp = baseTime + 2.minutes)

            var peekCalled = false
            val slice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                    peekResolver = {
                        peekCalled = true
                        null
                    },
                ).collectSlice(targetMsg)

            assertNotNull(slice)
            assertFalse(peekCalled, "peekResolver must not be invoked for general public web URLs")
            assertEquals(
                listOf(
                    "https://kotlinlang.org/docs/whatsnew20.html",
                    "https://docs.example.org/guide",
                ),
                slice.summaryInput.webUrls,
            )
            assertEquals("https://docs.example.org/guide", slice.primaryUrl)
            assertEquals("https://docs.example.org/guide", slice.summaryInput.url)
            assertTrue(slice.summaryInput.content.contains("https://kotlinlang.org/docs/whatsnew20.html"))
            assertTrue(slice.summaryInput.content.contains("https://docs.example.org/guide"))
            assertNotNull(slice.lookbackProvider, "lookbackProvider must be preserved for web URL slices")
        }

    @Test
    fun `collectSlice excludes Discord CDN URLs from webUrls and enforces 20 URL cap`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val cdnAttachment = createMockAttachment("https://cdn.discordapp.com/attachments/1/2/diagram.png", "image/png", "diagram.png")
            val urlsPart1 = (1..12).joinToString(" ") { "https://site$it.example.com/article" }
            val urlsPart2 = (13..24).joinToString(" ") { "https://site$it.example.com/article" }

            val msg1 =
                createMockMessage(
                    9301L,
                    "Alice",
                    "CDN link https://cdn.discordapp.com/attachments/1/2/diagram.png and https://media.discordapp.net/attachments/9/8/pic.webp $urlsPart1",
                    timestamp = baseTime,
                    attachments = setOf(cdnAttachment),
                )
            val targetMsg = createMockMessage(9302L, "Bob", "More links: $urlsPart2", timestamp = baseTime + 1.minutes)

            var peekCalled = false
            val slice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1) },
                    peekResolver = {
                        peekCalled = true
                        null
                    },
                ).collectSlice(targetMsg)

            assertNotNull(slice)
            assertFalse(peekCalled)
            assertEquals(20, slice.summaryInput.webUrls.size)
            assertEquals("https://site1.example.com/article", slice.summaryInput.webUrls.first())
            assertEquals("https://site20.example.com/article", slice.summaryInput.webUrls.last())
            assertFalse(slice.summaryInput.webUrls.any { it.contains("discordapp") })
            assertTrue(slice.summaryInput.originalUrls.size >= 24)
            assertTrue(slice.summaryInput.originalUrls.contains("https://site24.example.com/article"))
        }

    @Test
    fun `collectSlice resolves SocialPeek URL alongside web URLs in same slice without losing webUrls`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(9401L, "Alice", "Reference doc: https://docs.example.org/spec", timestamp = baseTime)
            val msg2 = createMockMessage(9402L, "Bob", "Video talk: https://www.youtube.com/watch?v=mixed123", timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(9403L, "Charlie", "Summarize both", timestamp = baseTime + 2.minutes)

            val peekedUrls = mutableListOf<String>()
            val slice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                    peekResolver = { url ->
                        peekedUrls.add(url)
                        PeekPost(
                            platform = Platform.YOUTUBE,
                            id = "mixed123",
                            originalUrl = url,
                            cleanUrl = url,
                            author = Author(username = "Speaker"),
                            title = "Mixed Talk",
                            content = "00:00 Intro",
                            rawData = mapOf("durationSeconds" to "120"),
                        )
                    },
                ).collectSlice(targetMsg)

            assertNotNull(slice)
            assertEquals(listOf("https://www.youtube.com/watch?v=mixed123"), peekedUrls)
            assertEquals("https://www.youtube.com/watch?v=mixed123", slice.summaryInput.videoUri)
            assertEquals(listOf("https://docs.example.org/spec"), slice.summaryInput.webUrls)
        }
}
