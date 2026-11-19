package luna.core.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.EmbedType
import dev.kord.common.entity.MessageStickerType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.StickerItem
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordStickerAndGifExtractorTest {
    private fun createMockSticker(
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

    private fun createMockEmbed(
        type: EmbedType? = null,
        url: String? = null,
        title: String? = null,
        description: String? = null,
        providerName: String? = null,
        providerUrl: String? = null,
        imageUrl: String? = null,
        imageProxyUrl: String? = null,
        thumbnailUrl: String? = null,
        thumbnailProxyUrl: String? = null,
        videoUrl: String? = null,
    ): Embed {
        val embed = mockk<Embed>()
        every { embed.type } returns type
        every { embed.url } returns url
        every { embed.title } returns title
        every { embed.description } returns description

        val provider =
            if (providerName != null || providerUrl != null) {
                mockk<Embed.Provider>().apply {
                    every { name } returns providerName
                    every { this@apply.url } returns providerUrl
                }
            } else {
                null
            }
        every { embed.provider } returns provider

        val image =
            if (imageUrl != null || imageProxyUrl != null) {
                mockk<Embed.Image>().apply {
                    every { this@apply.url } returns imageUrl
                    every { proxyUrl } returns imageProxyUrl
                }
            } else {
                null
            }
        every { embed.image } returns image

        val thumbnail =
            if (thumbnailUrl != null || thumbnailProxyUrl != null) {
                mockk<Embed.Thumbnail>().apply {
                    every { this@apply.url } returns thumbnailUrl
                    every { proxyUrl } returns thumbnailProxyUrl
                }
            } else {
                null
            }
        every { embed.thumbnail } returns thumbnail

        val video =
            if (videoUrl != null) {
                mockk<Embed.Video>().apply {
                    every { this@apply.url } returns videoUrl
                }
            } else {
                null
            }
        every { embed.video } returns video

        return embed
    }

    private fun createMockMessage(
        id: Long,
        stickers: List<StickerItem> = emptyList(),
        embeds: List<Embed> = emptyList(),
    ): Message {
        val message = mockk<Message>()
        every { message.id } returns Snowflake(id)
        every { message.attachments } returns emptySet()
        every { message.embeds } returns embeds
        every { message.stickers } returns stickers
        return message
    }

    @Test
    fun `extractFromMessage extracts raster stickers when includeStickersAndGifEmbeds is true and skips when false`() {
        val pngSticker = createMockSticker(1001L, "wave", MessageStickerType.PNG)
        val apngSticker = createMockSticker(1002L, "party", MessageStickerType.APNG)
        val gifSticker = createMockSticker(1003L, "bounce.gif", MessageStickerType.GIF)
        val message = createMockMessage(1L, stickers = listOf(pngSticker, apngSticker, gifSticker))

        // Summary Pipeline compatibility: default includeStickersAndGifEmbeds = false ignores stickers
        assertTrue(ChatImageAttachmentExtractor.extractFromMessage(message).isEmpty())

        val extracted =
            ChatImageAttachmentExtractor.extractFromMessage(
                message = message,
                includeStickersAndGifEmbeds = true,
            )
        assertEquals(3, extracted.size)
        assertEquals(
            ChatImageAttachment(
                url = "https://cdn.discordapp.com/stickers/1001.png",
                mimeType = "image/png",
                filename = "wave.png",
            ),
            extracted[0],
        )
        assertEquals(
            ChatImageAttachment(
                url = "https://cdn.discordapp.com/stickers/1002.png",
                mimeType = "image/png",
                filename = "party.png",
            ),
            extracted[1],
        )
        assertEquals(
            ChatImageAttachment(
                url = "https://cdn.discordapp.com/stickers/1003.gif",
                mimeType = "image/gif",
                filename = "bounce.gif",
            ),
            extracted[2],
        )
    }

    @Test
    fun `extractFromMessage never extracts LOTTIE or Unknown stickers and emits debug or warn logs`() {
        val logger = LoggerFactory.getLogger(DiscordMediaEmbedExtractor::class.java) as Logger
        val previousLevel = logger.level
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        logger.level = Level.DEBUG
        logger.addAppender(listAppender)

        try {
            val lottieSticker = createMockSticker(2001L, "sparkle", MessageStickerType.LOTTIE)
            val unknownSticker = createMockSticker(2002L, "weird", MessageStickerType.from(99))
            val malformedSticker = mockk<StickerItem>()
            every { malformedSticker.id } throws IllegalStateException("corrupt sticker id")
            every { malformedSticker.name } returns "broken"
            every { malformedSticker.formatType } returns MessageStickerType.PNG

            val message =
                createMockMessage(
                    2L,
                    stickers = listOf(lottieSticker, unknownSticker, malformedSticker),
                )

            val extracted =
                ChatImageAttachmentExtractor.extractFromMessage(
                    message = message,
                    includeStickersAndGifEmbeds = true,
                )
            assertTrue(extracted.isEmpty())
            assertTrue(
                listAppender.list.any {
                    it.level == Level.DEBUG && it.formattedMessage.contains("LOTTIE")
                },
            )
            assertTrue(
                listAppender.list.any {
                    it.level == Level.WARN && it.formattedMessage.contains("2002")
                },
            )
            assertTrue(
                listAppender.list.any {
                    it.level == Level.WARN && it.formattedMessage.contains("corrupt sticker id")
                },
            )
        } finally {
            logger.detachAppender(listAppender)
            logger.level = previousLevel
            listAppender.stop()
        }
    }

    @Test
    fun `extractFromMessage extracts Tenor and Giphy GIF embed previews when includeStickersAndGifEmbeds is true`() {
        val tenorGifv =
            createMockEmbed(
                type = EmbedType.Gifv,
                url = "https://tenor.com/view/cat-jam-gif-12345",
                providerName = "Tenor",
                providerUrl = "https://tenor.com",
                thumbnailUrl = "https://media.tenor.com/abc123AAAAC/cat-jam.png",
                videoUrl = "https://media.tenor.com/abc123AAAPo/cat-jam.mp4",
            )
        val giphyImage =
            createMockEmbed(
                type = EmbedType.Image,
                url = "https://giphy.com/gifs/party-parrot-xyz",
                providerName = "Giphy",
                providerUrl = "https://giphy.com",
                imageUrl = "https://media.giphy.com/media/xyz/giphy.gif?cid=test",
            )
        val giphyProxyThumb =
            createMockEmbed(
                type = EmbedType.Gifv,
                url = "https://giphy.com/gifs/hello-999",
                thumbnailProxyUrl = "https://images-ext-1.discordapp.net/external/hash/https/media2.giphy.com/media/999/giphy.webp",
            )
        val directTenorUrlOnly =
            createMockEmbed(
                type = EmbedType.Image,
                url = "https://c.tenor.com/777/cheer.gif",
            )

        val message =
            createMockMessage(
                3L,
                embeds = listOf(tenorGifv, giphyImage, giphyProxyThumb, directTenorUrlOnly),
            )

        // Default false ignores GIF embeds
        assertTrue(ChatImageAttachmentExtractor.extractFromMessage(message).isEmpty())

        val extracted =
            ChatImageAttachmentExtractor.extractFromMessage(
                message = message,
                includeStickersAndGifEmbeds = true,
            )
        assertEquals(4, extracted.size)
        assertEquals(
            ChatImageAttachment(
                url = "https://media.tenor.com/abc123AAAAC/cat-jam.png",
                mimeType = "image/png",
                filename = "cat-jam.png",
            ),
            extracted[0],
        )
        assertEquals(
            ChatImageAttachment(
                url = "https://media.giphy.com/media/xyz/giphy.gif?cid=test",
                mimeType = "image/gif",
                filename = "giphy.gif",
            ),
            extracted[1],
        )
        assertEquals(
            ChatImageAttachment(
                url = "https://images-ext-1.discordapp.net/external/hash/https/media2.giphy.com/media/999/giphy.webp",
                mimeType = "image/webp",
                filename = "giphy.webp",
            ),
            extracted[2],
        )
        assertEquals(
            ChatImageAttachment(
                url = "https://c.tenor.com/777/cheer.gif",
                mimeType = "image/gif",
                filename = "cheer.gif",
            ),
            extracted[3],
        )
    }

    @Test
    fun `extractFromMessage rejects non-whitelisted hosts, unsupported embed formats, non-https URLs, and malformed embeds`() {
        val logger = LoggerFactory.getLogger(DiscordMediaEmbedExtractor::class.java) as Logger
        val previousLevel = logger.level
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        logger.level = Level.DEBUG
        logger.addAppender(listAppender)

        try {
            val nonTenorEmbed =
                createMockEmbed(
                    type = EmbedType.Image,
                    url = "https://example.com/post",
                    imageUrl = "https://example.com/photo.png",
                )
            val spoofedHostEmbed =
                createMockEmbed(
                    type = EmbedType.Gifv,
                    url = "https://tenor.com/view/cat-1",
                    providerName = "Tenor",
                    imageUrl = "https://evil.example.com/payload.gif",
                )
            val videoOnlyTenorEmbed =
                createMockEmbed(
                    type = EmbedType.Gifv,
                    url = "https://tenor.com/view/cat-2",
                    providerName = "Tenor",
                    thumbnailUrl = "https://media.tenor.com/abc/clip.mp4",
                    videoUrl = "https://media.tenor.com/abc/clip.mp4",
                )
            val insecureHttpEmbed =
                createMockEmbed(
                    type = EmbedType.Image,
                    providerName = "Giphy",
                    imageUrl = "http://media.giphy.com/media/abc/giphy.gif",
                )
            val htmlPageOnlyEmbed =
                createMockEmbed(
                    type = EmbedType.Gifv,
                    url = "https://tenor.com/view/no-preview-123",
                    providerName = "Tenor",
                )
            val malformedEmbed = mockk<Embed>()
            every { malformedEmbed.type } throws IllegalStateException("corrupt embed")
            every { malformedEmbed.provider } throws IllegalStateException("corrupt provider")
            every { malformedEmbed.url } returns "https://tenor.com/view/broken"
            every { malformedEmbed.image } throws IllegalStateException("corrupt image")
            every { malformedEmbed.thumbnail } throws IllegalStateException("corrupt thumbnail")
            every { malformedEmbed.video } returns null

            val message =
                createMockMessage(
                    4L,
                    embeds =
                        listOf(
                            nonTenorEmbed,
                            spoofedHostEmbed,
                            videoOnlyTenorEmbed,
                            insecureHttpEmbed,
                            htmlPageOnlyEmbed,
                            malformedEmbed,
                        ),
                )

            assertFalse(DiscordMediaEmbedExtractor.isTenorOrGiphyEmbed(nonTenorEmbed))
            assertTrue(DiscordMediaEmbedExtractor.isTenorOrGiphyEmbed(spoofedHostEmbed))

            val extracted =
                ChatImageAttachmentExtractor.extractFromMessage(
                    message = message,
                    includeStickersAndGifEmbeds = true,
                )
            assertTrue(extracted.isEmpty())
            assertTrue(ChatImageAttachmentExtractor.extractFromMessage(message, maxImageBytes = 0, includeStickersAndGifEmbeds = true).isEmpty())

            assertTrue(
                listAppender.list.any {
                    it.level == Level.WARN && it.formattedMessage.contains("evil.example.com")
                },
            )
            assertTrue(
                listAppender.list.any {
                    (it.level == Level.DEBUG || it.level == Level.WARN) && it.formattedMessage.contains("clip.mp4")
                },
            )
            assertTrue(
                listAppender.list.any {
                    it.level == Level.WARN && it.formattedMessage.contains("corrupt")
                },
            )
        } finally {
            logger.detachAppender(listAppender)
            logger.level = previousLevel
            listAppender.stop()
        }
    }

    @Test
    fun `selectChronologicalImages enforces maxImages across attachments, stickers, and GIF embeds only when opted in`() {
        val sticker1 = createMockSticker(3001L, "one", MessageStickerType.PNG)
        val sticker2 = createMockSticker(3002L, "two", MessageStickerType.APNG)
        val gifEmbed =
            createMockEmbed(
                type = EmbedType.Gifv,
                url = "https://tenor.com/view/cat-3",
                providerName = "Tenor",
                thumbnailUrl = "https://media.tenor.com/3003/cat.gif",
            )

        val msg1 = createMockMessage(10L, stickers = listOf(sticker1))
        val msg2 = createMockMessage(11L, stickers = listOf(sticker2), embeds = listOf(gifEmbed))

        // Summary Pipeline default (includeStickersAndGifEmbeds = false) retains empty lists
        val summarySelected =
            ChatImageAttachmentExtractor.collectNewestImages(
                messages = listOf(msg1, msg2),
                maxImages = 2,
            )
        assertTrue(summarySelected.isEmpty())

        // Chatter Pipeline (includeStickersAndGifEmbeds = true) keeps newest 2 across stickers & GIF embeds
        val chatterSelected =
            ChatImageAttachmentExtractor.collectNewestImages(
                messages = listOf(msg1, msg2),
                maxImages = 2,
                includeStickersAndGifEmbeds = true,
            )
        assertEquals(
            listOf(
                "https://cdn.discordapp.com/stickers/3002.png",
                "https://media.tenor.com/3003/cat.gif",
            ),
            chatterSelected.map { it.url },
        )
    }
}
