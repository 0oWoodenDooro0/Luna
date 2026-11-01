package luna.summarizer

import dev.kord.core.entity.Attachment
import io.mockk.every
import io.mockk.mockk
import luna.summarizer.SummaryTestFixtures.createMockMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatImageResolverTest {
    private val resolver = ChatImageResolver()

    private fun createMockAttachment(
        url: String,
        contentType: String?,
        filename: String = "photo.png",
        size: Int = 1024,
    ): Attachment {
        val attachment = mockk<Attachment>()
        every { attachment.url } returns url
        every { attachment.contentType } returns contentType
        every { attachment.filename } returns filename
        every { attachment.size } returns size
        return attachment
    }

    @Test
    fun `extracts supported image attachments accurately`() {
        val png = createMockAttachment("https://cdn.discordapp.com/test.png", "image/png", "test.png")
        val jpeg = createMockAttachment("https://cdn.discordapp.com/test.jpg", "image/jpeg; charset=utf-8", "test.jpg")
        val webp = createMockAttachment("https://cdn.discordapp.com/test.webp", "image/webp", "test.webp")
        val gif = createMockAttachment("https://cdn.discordapp.com/test.gif", "image/gif", "test.gif")

        val message = createMockMessage(1L, "alice", "images", attachments = setOf(png, jpeg, webp, gif))
        val extracted = resolver.extractImageAttachments(message)

        assertEquals(4, extracted.size)
        assertEquals("image/png", extracted[0].mimeType)
        assertEquals("image/jpeg", extracted[1].mimeType)
        assertEquals("image/webp", extracted[2].mimeType)
        assertEquals("image/gif", extracted[3].mimeType)
    }

    @Test
    fun `excludes oversized attachments exceeding 10MB limit`() {
        val normalImg = createMockAttachment("https://cdn.discordapp.com/small.png", "image/png", "small.png", size = 5 * 1024 * 1024)
        val oversizedImg = createMockAttachment("https://cdn.discordapp.com/large.png", "image/png", "large.png", size = 12 * 1024 * 1024)

        val message = createMockMessage(2L, "bob", "check size", attachments = setOf(normalImg, oversizedImg))
        val extracted = resolver.extractImageAttachments(message)

        assertEquals(1, extracted.size)
        assertEquals("https://cdn.discordapp.com/small.png", extracted.first().url)
    }

    @Test
    fun `respects custom size budget configured via constructor`() {
        val customResolver = ChatImageResolver(maxImageBytes = 2 * 1024 * 1024)
        val img1Mb = createMockAttachment("https://cdn.discordapp.com/1mb.png", "image/png", size = 1 * 1024 * 1024)
        val img3Mb = createMockAttachment("https://cdn.discordapp.com/3mb.png", "image/png", size = 3 * 1024 * 1024)

        val message = createMockMessage(3L, "charlie", "custom limit", attachments = setOf(img1Mb, img3Mb))
        val extracted = customResolver.extractImageAttachments(message)

        assertEquals(1, extracted.size)
        assertEquals("https://cdn.discordapp.com/1mb.png", extracted.first().url)
    }

    @Test
    fun `strictly discards non-image or missing MIME types even with image extensions`() {
        assertNull(ChatImageResolver.resolveImageMimeType("application/octet-stream", "fake.png"))
        assertNull(ChatImageResolver.resolveImageMimeType(null, "photo.jpg"))
        assertNull(ChatImageResolver.resolveImageMimeType("application/pdf", "doc.png"))
        assertNull(ChatImageResolver.resolveImageMimeType("text/plain", "notes.jpg"))
        assertNull(ChatImageResolver.resolveImageMimeType("video/mp4", "clip.gif"))

        assertEquals("image/png", ChatImageResolver.resolveImageMimeType("image/png", "photo.png"))
        assertEquals("image/jpeg", ChatImageResolver.resolveImageMimeType("IMAGE/JPEG", "photo.jpg"))
        assertEquals("image/webp", ChatImageResolver.resolveImageMimeType("image/webp; charset=binary", "photo.webp"))
        assertEquals("image/gif", ChatImageResolver.resolveImageMimeType("image/gif", "anim.gif"))
    }

    @Test
    fun `returns empty list when message has no attachments`() {
        val message = createMockMessage(4L, "dan", "no images", attachments = emptySet())
        val extracted = resolver.extractImageAttachments(message)
        assertTrue(extracted.isEmpty())
    }
}
