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
        every { message.type } returns MessageType.Default
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
}
