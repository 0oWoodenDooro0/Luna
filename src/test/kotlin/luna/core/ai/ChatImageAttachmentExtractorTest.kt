package luna.core.ai

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatImageAttachmentExtractorTest {
    private fun createMockAttachment(
        url: String,
        contentType: String?,
        filename: String = "image.png",
        size: Int = 1024,
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
        attachments: Set<Attachment> = emptySet(),
    ): Message {
        val message = mockk<Message>()
        every { message.id } returns Snowflake(id)
        every { message.attachments } returns attachments
        return message
    }

    @Test
    fun `resolveImageMimeType accepts supported image formats and strips parameters`() {
        assertEquals("image/jpeg", ChatImageAttachmentExtractor.resolveImageMimeType("image/jpeg", "photo.jpg"))
        assertEquals("image/jpeg", ChatImageAttachmentExtractor.resolveImageMimeType("IMAGE/JPEG", "photo.jpeg"))
        assertEquals("image/png", ChatImageAttachmentExtractor.resolveImageMimeType("image/png", "img.png"))
        assertEquals("image/webp", ChatImageAttachmentExtractor.resolveImageMimeType("image/webp; charset=utf-8", "img.webp"))
        assertEquals("image/gif", ChatImageAttachmentExtractor.resolveImageMimeType("image/gif", "anim.gif"))
    }

    @Test
    fun `resolveImageMimeType rejects unsupported and spoofed MIME types`() {
        assertNull(ChatImageAttachmentExtractor.resolveImageMimeType("application/octet-stream", "spoofed.png"))
        assertNull(ChatImageAttachmentExtractor.resolveImageMimeType("application/octet-stream", "spoofed.jpg"))
        assertNull(ChatImageAttachmentExtractor.resolveImageMimeType(null, "photo.jpg"))
        assertNull(ChatImageAttachmentExtractor.resolveImageMimeType("", "photo.png"))
        assertNull(ChatImageAttachmentExtractor.resolveImageMimeType("application/pdf", "document.png"))
        assertNull(ChatImageAttachmentExtractor.resolveImageMimeType("text/plain", "notes.txt"))
        assertNull(ChatImageAttachmentExtractor.resolveImageMimeType("video/mp4", "clip.mp4"))
        assertNull(ChatImageAttachmentExtractor.resolveImageMimeType("image/svg+xml", "vector.svg"))
    }

    @Test
    fun `extractFromMessage accepts images up to 10 MB boundary and drops oversized or unsupported attachments`() {
        val tenMb = 10 * 1024 * 1024
        val exactLimitImg = createMockAttachment("https://cdn.example.com/exact.jpg", "image/jpeg", "exact.jpg", size = tenMb)
        val oversizedImg = createMockAttachment("https://cdn.example.com/over.png", "image/png", "over.png", size = tenMb + 1)
        val octetStreamImg =
            createMockAttachment("https://cdn.example.com/spoof.png", "application/octet-stream", "spoof.png", size = 2048)
        val pdfAttachment = createMockAttachment("https://cdn.example.com/doc.pdf", "application/pdf", "doc.pdf", size = 1024)

        val message =
            createMockMessage(
                id = 1L,
                attachments = setOf(exactLimitImg, oversizedImg, octetStreamImg, pdfAttachment),
            )

        val extracted = ChatImageAttachmentExtractor.extractFromMessage(message)
        assertEquals(1, extracted.size)
        assertEquals("https://cdn.example.com/exact.jpg", extracted.first().url)
        assertEquals("image/jpeg", extracted.first().mimeType)
        assertEquals("exact.jpg", extracted.first().filename)
    }

    @Test
    fun `extractFromMessage returns empty list when maxImageBytes limit is zero or negative`() {
        val img = createMockAttachment("https://cdn.example.com/small.png", "image/png", "small.png", size = 512)
        val message = createMockMessage(id = 2L, attachments = setOf(img))

        assertTrue(ChatImageAttachmentExtractor.extractFromMessage(message, maxImageBytes = 0).isEmpty())
        assertTrue(ChatImageAttachmentExtractor.extractFromMessage(message, maxImageBytes = -100).isEmpty())
    }

    @Test
    fun `selectChronologicalImages keeps newest images within capacity in chronological order`() {
        val img1 = createMockAttachment("https://cdn.example.com/1.png", "image/png", "1.png")
        val img2 = createMockAttachment("https://cdn.example.com/2.png", "image/png", "2.png")
        val img3a = createMockAttachment("https://cdn.example.com/3a.png", "image/png", "3a.png")
        val img3b = createMockAttachment("https://cdn.example.com/3b.png", "image/png", "3b.png")

        val msg1 = createMockMessage(101L, setOf(img1))
        val msg2 = createMockMessage(102L, setOf(img2))
        val msg3 = createMockMessage(103L, setOf(img3a, img3b))

        val selectedMap =
            ChatImageAttachmentExtractor.selectChronologicalImages(
                messages = listOf(msg1, msg2, msg3),
                maxImages = 3,
            )

        assertTrue(selectedMap[msg1.id].orEmpty().isEmpty())
        assertEquals(listOf("https://cdn.example.com/2.png"), selectedMap[msg2.id]?.map { it.url })
        assertEquals(
            listOf("https://cdn.example.com/3a.png", "https://cdn.example.com/3b.png"),
            selectedMap[msg3.id]?.map { it.url },
        )

        val flattened =
            ChatImageAttachmentExtractor.collectNewestImages(
                messages = listOf(msg1, msg2, msg3),
                maxImages = 3,
            )
        assertEquals(
            listOf("https://cdn.example.com/2.png", "https://cdn.example.com/3a.png", "https://cdn.example.com/3b.png"),
            flattened.map { it.url },
        )
    }

    @Test
    fun `selectChronologicalImages prioritizes target message when prioritizedMessageId is provided`() {
        val img1 = createMockAttachment("https://cdn.example.com/1.png", "image/png", "1.png")
        val img2 = createMockAttachment("https://cdn.example.com/2.png", "image/png", "2.png")
        val img3 = createMockAttachment("https://cdn.example.com/3.png", "image/png", "3.png")

        val msg1 = createMockMessage(201L, setOf(img1))
        val targetMsg = createMockMessage(202L, setOf(img2))
        val laterMsg = createMockMessage(203L, setOf(img3))

        val selected =
            ChatImageAttachmentExtractor.selectChronologicalImages(
                messages = listOf(msg1, targetMsg, laterMsg),
                maxImages = 2,
                prioritizedMessageId = targetMsg.id,
            )

        assertTrue(selected[msg1.id].orEmpty().isEmpty())
        assertEquals(listOf("https://cdn.example.com/2.png"), selected[targetMsg.id]?.map { it.url })
        assertEquals(listOf("https://cdn.example.com/3.png"), selected[laterMsg.id]?.map { it.url })
    }

    @Test
    fun `selectChronologicalImages and collectNewestImages return empty when maxImages capacity is zero or negative`() {
        val img = createMockAttachment("https://cdn.example.com/1.png", "image/png", "1.png")
        val msg = createMockMessage(301L, setOf(img))

        assertTrue(ChatImageAttachmentExtractor.selectChronologicalImages(listOf(msg), maxImages = 0).isEmpty())
        assertTrue(ChatImageAttachmentExtractor.selectChronologicalImages(listOf(msg), maxImages = -1).isEmpty())
        assertTrue(ChatImageAttachmentExtractor.collectNewestImages(listOf(msg), maxImages = 0).isEmpty())
        assertTrue(ChatImageAttachmentExtractor.collectNewestImages(listOf(msg), maxImages = -5).isEmpty())
    }
}
