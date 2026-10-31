package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.StickerItem
import io.mockk.mockk
import luna.summarizer.SummaryTestFixtures.createMockMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueNoiseFilterTest {
    private val filter = DialogueNoiseFilter()

    @Test
    fun `identifies bot summary messages by prefix or keywords`() {
        val tldrMsg = createMockMessage(1L, "luna", "TL;DR: discussion summary", isBot = true)
        val keypointsMsg = createMockMessage(2L, "luna", "重點摘要: some points", isBot = true)
        val prevSummaryMsg = createMockMessage(3L, "luna", "Previous summary of discussion", isBot = true)
        val normalBotMsg = createMockMessage(4L, "musicbot", "Now playing: track 1", isBot = true)
        val humanMsgWithTldr = createMockMessage(5L, "alice", "TL;DR: my human opinion", isBot = false)

        assertTrue(filter.isBotSummaryMessage(tldrMsg))
        assertTrue(filter.isBotSummaryMessage(keypointsMsg))
        assertTrue(filter.isBotSummaryMessage(prevSummaryMsg))
        assertFalse(filter.isBotSummaryMessage(normalBotMsg))
        assertFalse(filter.isBotSummaryMessage(humanMsgWithTldr))
    }

    @Test
    fun `identifies commands as noise`() {
        val slashMsg = createMockMessage(1L, "alice", "/shorten https://example.com")
        val bangMsg = createMockMessage(2L, "alice", "!help")
        val chatInputMsg = createMockMessage(3L, "alice", "execute", type = MessageType.ChatInputCommand)

        assertTrue(filter.isNoiseMessage(slashMsg))
        assertTrue(filter.isNoiseMessage(bangMsg))
        assertTrue(filter.isNoiseMessage(chatInputMsg))
    }

    @Test
    fun `identifies system messages and webhooks as noise`() {
        val pinMsg = createMockMessage(1L, "system", "Pinned a message", type = MessageType.ChannelPinnedMessage)
        val webhookMsg = createMockMessage(2L, "github", "Push to master", webhookId = Snowflake(999L))

        assertTrue(filter.isNoiseMessage(pinMsg))
        assertTrue(filter.isNoiseMessage(webhookMsg))
    }

    @Test
    fun `identifies empty messages and standalone stickers as noise`() {
        val emptyMsg = createMockMessage(1L, "alice", "   ")
        val stickerItem = mockk<StickerItem>()
        val stickerOnlyMsg = createMockMessage(2L, "alice", "", stickers = listOf(stickerItem))

        assertTrue(filter.isNoiseMessage(emptyMsg))
        assertTrue(filter.isNoiseMessage(stickerOnlyMsg))
    }

    @Test
    fun `retains messages with blank text if attachments are present`() {
        val attachment = mockk<Attachment>()
        val msgWithAttachment = createMockMessage(1L, "alice", "", attachments = setOf(attachment))

        assertFalse(filter.isNoiseMessage(msgWithAttachment))
    }

    @Test
    fun `retains short human conversational turns`() {
        val plusOne = createMockMessage(1L, "alice", "+1")
        val lol = createMockMessage(2L, "bob", "lol")
        val agreed = createMockMessage(3L, "charlie", "agreed")

        assertFalse(filter.isNoiseMessage(plusOne))
        assertFalse(filter.isNoiseMessage(lol))
        assertFalse(filter.isNoiseMessage(agreed))
    }
}
