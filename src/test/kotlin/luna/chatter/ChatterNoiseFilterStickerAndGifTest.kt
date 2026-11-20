package luna.chatter

import dev.kord.common.entity.EmbedType
import dev.kord.common.entity.MessageStickerType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.StickerItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterNoiseFilterStickerAndGifTest {
    private lateinit var filter: ChatterNoiseFilter

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        SummaryMessageRegistry.clear()
        filter = ChatterNoiseFilter(cooldownCache = BoundedChannelCooldownCache(timeSource = { 1_000_000L }))
    }

    private fun createSticker(
        id: Long,
        name: String,
        formatType: MessageStickerType,
    ): StickerItem {
        val sticker = mockk<StickerItem>()
        every { sticker.id } returns Snowflake(id)
        every { sticker.name } returns name
        every { sticker.formatType } returns formatType
        return sticker
    }

    private fun createGifEmbed(
        providerName: String = "Tenor",
        url: String = "https://tenor.com/view/cat-jam-123",
        thumbnailUrl: String = "https://media.tenor.com/abc/cat-jam.gif",
    ): Embed {
        val embed = mockk<Embed>()
        every { embed.type } returns EmbedType.Gifv
        every { embed.url } returns url
        every { embed.title } returns null
        every { embed.description } returns null
        val provider = mockk<Embed.Provider>()
        every { provider.name } returns providerName
        every { provider.url } returns null
        every { embed.provider } returns provider
        every { embed.image } returns null
        val thumbnail = mockk<Embed.Thumbnail>()
        every { thumbnail.url } returns thumbnailUrl
        every { thumbnail.proxyUrl } returns null
        every { embed.thumbnail } returns thumbnail
        every { embed.video } returns null
        return embed
    }

    private fun createMessage(
        id: Long,
        username: String = "Alice",
        content: String = "",
        isBot: Boolean = false,
        attachments: Set<Attachment> = emptySet(),
        embeds: List<Embed> = emptyList(),
        stickers: List<StickerItem> = emptyList(),
    ): Message =
        createMockMessage(
            id = id,
            username = username,
            content = content,
            isBot = isBot,
            attachments = attachments,
            embeds = embeds,
        ).also { msg ->
            every { msg.stickers } returns stickers
        }

    @Test
    fun `evaluate and context helpers admit blank-text messages containing raster or LOTTIE stickers`() =
        runTest {
            val pngStickerMsg =
                createMessage(
                    id = 101L,
                    username = "Alice",
                    content = "",
                    stickers = listOf(createSticker(501L, "blob_wave", MessageStickerType.PNG)),
                )
            val lottieStickerMsg =
                createMessage(
                    id = 102L,
                    username = "Bob",
                    content = "   ",
                    stickers = listOf(createSticker(502L, "sparkle", MessageStickerType.LOTTIE)),
                )
            val unnamedLottieMsg =
                createMessage(
                    id = 103L,
                    username = "Charlie",
                    content = "",
                    stickers = listOf(createSticker(503L, "   ", MessageStickerType.LOTTIE)),
                )

            val res1 = filter.evaluate(pngStickerMsg)
            assertIs<ChatterFilterResult.Allowed>(res1)
            assertEquals("Alice", res1.speakerName)
            assertEquals("[貼圖: blob_wave]", res1.content)
            assertTrue(filter.isContextMessage(pngStickerMsg))
            assertEquals(ChatMessage("Alice", "[貼圖: blob_wave]", authorId = "101"), filter.toChatMessageOrNull(pngStickerMsg))

            val res2 = filter.evaluate(lottieStickerMsg)
            assertIs<ChatterFilterResult.Allowed>(res2)
            assertEquals("[貼圖: sparkle]", res2.content)
            assertEquals(ChatMessage("Bob", "[貼圖: sparkle]", authorId = "102"), filter.toChatMessageOrNull(lottieStickerMsg))

            val res3 = filter.evaluate(unnamedLottieMsg)
            assertIs<ChatterFilterResult.Allowed>(res3)
            assertEquals("[貼圖]", res3.content)
            assertEquals(ChatMessage("Charlie", "[貼圖]", authorId = "103"), filter.toChatMessageOrNull(unnamedLottieMsg))
        }

    @Test
    fun `evaluate and context helpers admit blank-text messages containing Tenor or Giphy GIF embeds`() =
        runTest {
            val tenorMsg =
                createMessage(
                    id = 201L,
                    username = "Dave",
                    content = "",
                    embeds = listOf(createGifEmbed(providerName = "Tenor")),
                )
            val giphyMsg =
                createMessage(
                    id = 202L,
                    username = "Eve",
                    content = "\n ",
                    embeds =
                        listOf(
                            createGifEmbed(
                                providerName = "Giphy",
                                url = "https://giphy.com/gifs/hello-123",
                                thumbnailUrl = "https://media.giphy.com/media/123/giphy.gif",
                            ),
                        ),
                )

            val resTenor = filter.evaluate(tenorMsg)
            assertIs<ChatterFilterResult.Allowed>(resTenor)
            assertEquals("[GIF]", resTenor.content)
            assertTrue(filter.isContextMessage(tenorMsg))
            assertEquals(ChatMessage("Dave", "[GIF]", authorId = "201"), filter.toChatMessageOrNull(tenorMsg))

            val resGiphy = filter.evaluate(giphyMsg)
            assertIs<ChatterFilterResult.Allowed>(resGiphy)
            assertEquals("[GIF]", resGiphy.content)
            assertNotNull(filter.toChatMessageOrNull(giphyMsg))
        }

    @Test
    fun `resolveBlankMessagePlaceholder preserves precedence and rejects third-party bots or truly empty messages`() =
        runTest {
            val attachment = createMockAttachment("https://cdn.example.com/a.png", "image/png", "a.png")
            val sticker = createSticker(601L, "hi", MessageStickerType.PNG)
            val gifEmbed = createGifEmbed()
            val genericEmbed = createMockEmbed(title = "Link", description = null)

            val attachmentAndSticker = createMessage(301L, attachments = setOf(attachment), stickers = listOf(sticker))
            val stickerAndGif = createMessage(302L, stickers = listOf(sticker), embeds = listOf(gifEmbed))
            val genericEmbedOnly = createMessage(303L, embeds = listOf(genericEmbed))
            val emptyMsg = createMessage(304L)
            val botStickerMsg = createMessage(305L, isBot = true, stickers = listOf(sticker))

            assertEquals("[Attachment]", ChatterNoiseFilter.resolveBlankMessagePlaceholder(attachmentAndSticker))
            assertEquals("[貼圖: hi]", ChatterNoiseFilter.resolveBlankMessagePlaceholder(stickerAndGif))
            assertEquals("[Embed]", ChatterNoiseFilter.resolveBlankMessagePlaceholder(genericEmbedOnly))
            assertNull(ChatterNoiseFilter.resolveBlankMessagePlaceholder(emptyMsg))

            val emptyResult = filter.evaluate(emptyMsg)
            assertIs<ChatterFilterResult.Rejected>(emptyResult)
            assertEquals(RejectionReason.EMPTY_MESSAGE, emptyResult.reason)

            val botResult = filter.evaluate(botStickerMsg)
            assertIs<ChatterFilterResult.Rejected>(botResult)
            assertEquals(RejectionReason.BOT_MESSAGE, botResult.reason)
        }
}
