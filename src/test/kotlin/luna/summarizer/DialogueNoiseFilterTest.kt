package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.StickerItem
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.summarizer.SummaryTestFixtures.createMockMessage
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
    fun `identifies commands as noise`() =
        runTest {
            val slashMsg = createMockMessage(1L, "alice", "/shorten https://example.com")
            val bangMsg = createMockMessage(2L, "alice", "!help")
            val chatInputMsg = createMockMessage(3L, "alice", "execute", type = MessageType.ChatInputCommand)

            assertTrue(filter.isNoiseMessage(slashMsg))
            assertTrue(filter.isNoiseMessage(bangMsg))
            assertTrue(filter.isNoiseMessage(chatInputMsg))
        }

    @Test
    fun `identifies system messages and webhooks as noise`() =
        runTest {
            val pinMsg = createMockMessage(1L, "system", "Pinned a message", type = MessageType.ChannelPinnedMessage)
            val webhookMsg = createMockMessage(2L, "github", "Push to master", webhookId = Snowflake(999L))

            assertTrue(filter.isNoiseMessage(pinMsg))
            assertTrue(filter.isNoiseMessage(webhookMsg))
        }

    @Test
    fun `identifies empty messages and standalone stickers as noise`() =
        runTest {
            val emptyMsg = createMockMessage(1L, "alice", "   ")
            val stickerItem = mockk<StickerItem>()
            val stickerOnlyMsg = createMockMessage(2L, "alice", "", stickers = listOf(stickerItem))

            assertTrue(filter.isNoiseMessage(emptyMsg))
            assertTrue(filter.isNoiseMessage(stickerOnlyMsg))
        }

    @Test
    fun `retains messages with blank text if attachments are present`() =
        runTest {
            val attachment = mockk<Attachment>()
            val msgWithAttachment = createMockMessage(1L, "alice", "", attachments = setOf(attachment))

            assertFalse(filter.isNoiseMessage(msgWithAttachment))
        }

    @Test
    fun `retains messages with image attachments even when content is solely custom emojis`() =
        runTest {
            val attachment = mockk<Attachment>()
            val emojiOnlyMsg = createMockMessage(1L, "alice", "<:custom_pepe:123456789>", attachments = setOf(attachment))
            val multiEmojiMsg = createMockMessage(2L, "bob", "<a:party_blob:98765> <:cat:123>", attachments = setOf(attachment))

            assertFalse(filter.isNoiseMessage(emojiOnlyMsg))
            assertFalse(filter.isNoiseMessage(multiEmojiMsg))
        }

    @Test
    fun `retains messages with image attachments even when stickers are present and text is blank`() =
        runTest {
            val attachment = mockk<Attachment>()
            val stickerItem = mockk<StickerItem>()
            val stickerWithAttachmentMsg =
                createMockMessage(
                    id = 1L,
                    username = "alice",
                    content = "",
                    stickers = listOf(stickerItem),
                    attachments = setOf(attachment),
                )

            assertFalse(filter.isNoiseMessage(stickerWithAttachmentMsg))
        }

    @Test
    fun `retains short human conversational turns`() =
        runTest {
            val plusOne = createMockMessage(1L, "alice", "+1")
            val lol = createMockMessage(2L, "bob", "lol")
            val agreed = createMockMessage(3L, "charlie", "agreed")

            assertFalse(filter.isNoiseMessage(plusOne))
            assertFalse(filter.isNoiseMessage(lol))
            assertFalse(filter.isNoiseMessage(agreed))
        }

    @Test
    fun `identifies luna webhook replacement messages as not noise`() =
        runTest {
            val lunaWebhookId = Snowflake(100L)
            val customFilter =
                DialogueNoiseFilter(
                    isLunaWebhook = { it == lunaWebhookId },
                )
            val webhookReplacementMsg =
                createMockMessage(
                    id = 10L,
                    username = "Alice",
                    content = "Clean url https://example.com/post",
                    isBot = true,
                    webhookId = lunaWebhookId,
                )

            assertFalse(customFilter.isNoiseMessage(webhookReplacementMsg))
        }

    @Test
    fun `identifies third-party webhooks as noise`() =
        runTest {
            val lunaWebhookId = Snowflake(100L)
            val customFilter =
                DialogueNoiseFilter(
                    isLunaWebhook = { it == lunaWebhookId },
                )
            val thirdPartyWebhookMsg =
                createMockMessage(
                    id = 11L,
                    username = "GitHubBot",
                    content = "Commit pushed https://github.com/repo",
                    isBot = true,
                    webhookId = Snowflake(999L),
                )

            assertTrue(customFilter.isNoiseMessage(thirdPartyWebhookMsg))
        }

    @Test
    fun `caches verified luna webhooks avoiding duplicate queries`() =
        runTest {
            val lunaWebhookId = Snowflake(100L)
            val queryCount = AtomicInteger(0)
            val customFilter =
                DialogueNoiseFilter(
                    isLunaWebhook = { id ->
                        queryCount.incrementAndGet()
                        id == lunaWebhookId
                    },
                )

            val msg1 = createMockMessage(10L, "Alice", "Link 1 https://example.com/1", isBot = true, webhookId = lunaWebhookId)
            val msg2 = createMockMessage(11L, "Alice", "Link 2 https://example.com/2", isBot = true, webhookId = lunaWebhookId)

            assertFalse(customFilter.isNoiseMessage(msg1))
            assertFalse(customFilter.isNoiseMessage(msg2))
            assertEquals(1, queryCount.get())
        }

    @Test
    fun `verifies luna webhook using kord when isLunaWebhook is not provided`() =
        runTest {
            val kordMock = mockk<dev.kord.core.Kord>()
            val resourcesMock = mockk<dev.kord.core.ClientResources>()
            val strategyMock = mockk<dev.kord.core.supplier.EntitySupplyStrategy<*>>()
            io.mockk.every { resourcesMock.defaultStrategy } returns strategyMock
            io.mockk.every { kordMock.resources } returns resourcesMock

            val lunaSelfId = Snowflake(42L)
            io.mockk.every { kordMock.selfId } returns lunaSelfId

            val lunaCreatedWebhook = mockk<dev.kord.core.entity.Webhook>()
            io.mockk.every { lunaCreatedWebhook.creatorId } returns lunaSelfId
            io.mockk.every { lunaCreatedWebhook.name } returns "Webhook1"
            io.mockk.coEvery { kordMock.getWebhookOrNull(Snowflake(100L)) } returns lunaCreatedWebhook

            val namedLunaWebhook = mockk<dev.kord.core.entity.Webhook>()
            io.mockk.every { namedLunaWebhook.creatorId } returns Snowflake(999L)
            io.mockk.every { namedLunaWebhook.name } returns "Luna Preview"
            io.mockk.coEvery { kordMock.getWebhookOrNull(Snowflake(200L)) } returns namedLunaWebhook

            val thirdPartyWebhook = mockk<dev.kord.core.entity.Webhook>()
            io.mockk.every { thirdPartyWebhook.creatorId } returns Snowflake(888L)
            io.mockk.every { thirdPartyWebhook.name } returns "Other Bot"
            io.mockk.coEvery { kordMock.getWebhookOrNull(Snowflake(300L)) } returns thirdPartyWebhook

            val customFilter = DialogueNoiseFilter(kord = kordMock)

            val lunaMsg = createMockMessage(1L, "Alice", "Msg 1", isBot = true, webhookId = Snowflake(100L))
            val namedMsg = createMockMessage(2L, "Bob", "Msg 2", isBot = true, webhookId = Snowflake(200L))
            val otherMsg = createMockMessage(3L, "Bot", "Msg 3", isBot = true, webhookId = Snowflake(300L))

            assertFalse(customFilter.isNoiseMessage(lunaMsg))
            assertFalse(customFilter.isNoiseMessage(namedMsg))
            assertTrue(customFilter.isNoiseMessage(otherMsg))
        }

    @Test
    fun `does not identify webhook messages as bot summaries even if starting with prefix`() {
        val webhookTldr =
            createMockMessage(
                id = 1L,
                username = "Alice",
                content = "TL;DR: discussion summary",
                isBot = true,
                webhookId = Snowflake(100L),
            )
        assertFalse(filter.isBotSummaryMessage(webhookTldr))
    }
}
