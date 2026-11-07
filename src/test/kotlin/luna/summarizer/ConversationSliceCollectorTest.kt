package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.socialpeek.model.Author
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
    ): Message {
        val author = mockk<User>()
        every { author.username } returns username
        every { author.isBot } returns isBot

        val message = mockk<Message>()
        every { message.id } returns Snowflake(id)
        every { message.author } returns author
        every { message.content } returns content
        every { message.timestamp } returns timestamp
        every { message.attachments } returns attachments
        every { message.type } returns type
        every { message.stickers } returns emptyList()
        every { message.embeds } returns emptyList()
        every { message.data.author.username } returns username
        every { message.data.webhookId.value } returns null
        return message
    }

    @Test
    fun `extractImageAttachments correctly resolves valid image attachments and drops oversized ones`() {
        val collector = ConversationSliceCollector(maxImageBytes = 5 * 1024 * 1024)

        val validImg = createMockAttachment("https://cdn.discordapp.com/valid.png", "image/png", "valid.png", size = 2 * 1024 * 1024)
        val oversizedImg = createMockAttachment("https://cdn.discordapp.com/large.jpg", "image/jpeg", "large.jpg", size = 8 * 1024 * 1024)
        val nonImg = createMockAttachment("https://cdn.discordapp.com/doc.pdf", "application/pdf", "doc.pdf", size = 1024)

        val message = createMockMessage(1L, "Alice", "Check files", attachments = setOf(validImg, oversizedImg, nonImg))
        val extracted = collector.extractImageAttachments(message)

        assertEquals(1, extracted.size)
        assertEquals("https://cdn.discordapp.com/valid.png", extracted.first().url)
        assertEquals("image/png", extracted.first().mimeType)
        assertEquals("valid.png", extracted.first().filename)
    }

    @Test
    fun `resolveImageMimeType validates supported formats and rejects unsupported or spoofed types`() {
        assertEquals("image/png", ConversationSliceCollector.resolveImageMimeType("image/png", "test.png"))
        assertEquals("image/jpeg", ConversationSliceCollector.resolveImageMimeType("IMAGE/JPEG", "test.jpg"))
        assertEquals("image/webp", ConversationSliceCollector.resolveImageMimeType("image/webp; charset=utf-8", "test.webp"))
        assertEquals("image/gif", ConversationSliceCollector.resolveImageMimeType("image/gif", "test.gif"))

        assertNull(ConversationSliceCollector.resolveImageMimeType("application/octet-stream", "fake.png"))
        assertNull(ConversationSliceCollector.resolveImageMimeType(null, "photo.jpg"))
        assertNull(ConversationSliceCollector.resolveImageMimeType("application/pdf", "document.png"))
        assertNull(ConversationSliceCollector.resolveImageMimeType("text/plain", "log.txt"))
        assertNull(ConversationSliceCollector.resolveImageMimeType("video/mp4", "clip.mp4"))
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
}
