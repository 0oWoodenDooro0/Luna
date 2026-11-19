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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterContextStickerAndGifTest {
    private lateinit var filter: ChatterNoiseFilter

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        SummaryMessageRegistry.clear()
        filter = ChatterNoiseFilter()
    }

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

    private fun createGifEmbed(
        type: EmbedType? = EmbedType.Gifv,
        url: String? = null,
        title: String? = null,
        description: String? = null,
        providerName: String? = null,
        imageUrl: String? = null,
        thumbnailUrl: String? = null,
        videoUrl: String? = null,
    ): Embed {
        val embed = mockk<Embed>()
        every { embed.type } returns type
        every { embed.url } returns url
        every { embed.title } returns title
        every { embed.description } returns description
        val provider =
            providerName?.let { name ->
                mockk<Embed.Provider>().apply {
                    every { this@apply.name } returns name
                    every { this@apply.url } returns null
                }
            }
        every { embed.provider } returns provider
        val image =
            imageUrl?.let { u ->
                mockk<Embed.Image>().apply {
                    every { this@apply.url } returns u
                    every { proxyUrl } returns null
                }
            }
        every { embed.image } returns image
        val thumbnail =
            thumbnailUrl?.let { u ->
                mockk<Embed.Thumbnail>().apply {
                    every { this@apply.url } returns u
                    every { proxyUrl } returns null
                }
            }
        every { embed.thumbnail } returns thumbnail
        val video =
            videoUrl?.let { u ->
                mockk<Embed.Video>().apply {
                    every { this@apply.url } returns u
                }
            }
        every { embed.video } returns video
        return embed
    }

    private fun createMessage(
        id: Long,
        username: String = "Alice",
        content: String = "",
        attachments: Set<Attachment> = emptySet(),
        embeds: List<Embed> = emptyList(),
        stickers: List<StickerItem> = emptyList(),
    ): Message =
        createMockMessage(
            id = id,
            username = username,
            content = content,
            attachments = attachments,
            embeds = embeds,
        ).also { msg ->
            every { msg.stickers } returns stickers
        }

    @Test
    fun `collectDecisionContext annotates stickers and Tenor Giphy GIFs within maxDecisionImages budget and degrades LOTTIE`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val olderAttachment = createMockAttachment("https://cdn.example.com/old.png", "image/png", "old.png")
            val msg1 = createMessage(id = 1L, username = "Alice", content = "old pic", attachments = setOf(olderAttachment))
            val msg2 =
                createMessage(
                    id = 2L,
                    username = "Bob",
                    content = "",
                    stickers = listOf(createMockSticker(701L, "wave", MessageStickerType.PNG)),
                )
            val msg3 =
                createMessage(
                    id = 3L,
                    username = "Charlie",
                    content = "laughing",
                    stickers = listOf(createMockSticker(702L, "lottie_laugh", MessageStickerType.LOTTIE)),
                )
            val msg4 =
                createMessage(
                    id = 4L,
                    username = "Dave",
                    content = "",
                    stickers = listOf(createMockSticker(703L, "  ", MessageStickerType.LOTTIE)),
                )
            val msg5 =
                createMessage(
                    id = 5L,
                    username = "Eve",
                    content = "",
                    embeds =
                        listOf(
                            createGifEmbed(
                                url = "https://tenor.com/view/cat-jam-123",
                                providerName = "Tenor",
                                thumbnailUrl = "https://media.tenor.com/abc/cat-jam.png",
                            ),
                        ),
                )
            val msg6 =
                createMessage(
                    id = 6L,
                    username = "Frank",
                    content = "mood",
                    embeds =
                        listOf(
                            createGifEmbed(
                                title = "Party Parrot",
                                providerName = "Giphy",
                                imageUrl = "https://media.giphy.com/media/xyz/giphy.gif",
                            ),
                        ),
                )

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    maxDecisionImages = 3,
                    peekResolver = { error("SocialPeek must not be called in decision context") },
                )

            val result =
                collector.collectDecisionContext(
                    messages = listOf(msg1, msg2, msg3, msg4, msg5, msg6),
                    kordSelfId = botSelfId,
                )

            // Cap is 3: olderAttachment is evicted, keeping PNG sticker + Tenor GIF + Giphy GIF
            assertEquals(
                listOf(
                    "https://cdn.discordapp.com/stickers/701.png",
                    "https://media.tenor.com/abc/cat-jam.png",
                    "https://media.giphy.com/media/xyz/giphy.gif",
                ),
                result.images.map { it.url },
            )
            assertFalse(result.images.any { it.url.contains("702") || it.url.contains("703") })
            assertTrue(result.transcript.contains("[Alice]: old pic"))
            assertTrue(result.transcript.contains("[Bob]: [貼圖: wave] [附帶圖片 1: wave.png]"))
            assertTrue(result.transcript.contains("[Charlie]: laughing [貼圖: lottie_laugh]"))
            assertTrue(result.transcript.contains("[Dave]: [貼圖]"))
            assertTrue(result.transcript.contains("[Eve]: [GIF] [附帶圖片 2: cat-jam.png]"))
            assertTrue(result.transcript.contains("[Frank]: mood [GIF: Party Parrot] [附帶圖片 3: giphy.gif]"))
        }

    @Test
    fun `collectReplyContext extracts raster stickers and GIF embeds within maxImages and excludes captured GIF links from webUrls`() =
        runTest {
            val msgs =
                buildList {
                    for (idx in 1..9) {
                        add(
                            createMessage(
                                id = 100L + idx,
                                username = "User$idx",
                                content = "",
                                stickers = listOf(createMockSticker(800L + idx, "s$idx", MessageStickerType.APNG)),
                            ),
                        )
                    }
                    add(
                        createMessage(
                            id = 110L,
                            username = "LottieUser",
                            content = "",
                            stickers = listOf(createMockSticker(899L, "sparkle", MessageStickerType.LOTTIE)),
                        ),
                    )
                    add(
                        createMessage(
                            id = 111L,
                            username = "GifUser",
                            content = "https://tenor.com/view/cat-jam-123",
                            embeds =
                                listOf(
                                    createGifEmbed(
                                        url = "https://tenor.com/view/cat-jam-123",
                                        providerName = "Tenor",
                                        thumbnailUrl = "https://media.tenor.com/abc/cat-jam.gif",
                                    ),
                                ),
                        ),
                    )
                    add(
                        createMessage(
                            id = 112L,
                            username = "GifStickerUser",
                            content = "",
                            stickers = listOf(createMockSticker(900L, "party_gif", MessageStickerType.GIF)),
                        ),
                    )
                }

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    maxImages = 10,
                    peekResolver = { null },
                )

            val prepared = collector.collectReplyContext(msgs)

            // 9 APNG + 1 Tenor GIF + 1 GIF sticker = 11 raster images -> newest 10 kept (s1 evicted)
            assertEquals(10, prepared.imageAttachments.size)
            assertFalse(prepared.imageAttachments.any { it.url.contains("801") })
            assertTrue(prepared.imageAttachments.any { it.url == "https://cdn.discordapp.com/stickers/802.png" })
            assertTrue(prepared.imageAttachments.any { it.url == "https://media.tenor.com/abc/cat-jam.gif" })
            assertTrue(prepared.imageAttachments.any { it.url == "https://cdn.discordapp.com/stickers/900.gif" })

            // Evicted s1 still retains its sticker annotation in transcript
            assertTrue(prepared.transcript.contains("[User1]: [貼圖: s1]"))
            assertFalse(prepared.transcript.contains("s1.png"))
            assertTrue(prepared.transcript.contains("[User2]: [貼圖: s2] [附帶圖片 1: s2.png]"))
            assertTrue(prepared.transcript.contains("[LottieUser]: [貼圖: sparkle]"))
            assertTrue(prepared.transcript.contains("[GifUser]: https://tenor.com/view/cat-jam-123 [GIF] [附帶圖片 9: cat-jam.gif]"))
            assertTrue(prepared.transcript.contains("[GifStickerUser]: [貼圖: party_gif] [附帶圖片 10: party_gif.gif]"))

            // Captured Tenor embed URL is excluded from webUrls so url_context isn't invoked needlessly
            assertNull(prepared.primaryUrl)
            assertFalse(prepared.webUrls.contains("https://tenor.com/view/cat-jam-123"))
        }

    @Test
    fun `collectDecisionContext and collectReplyContext degrade invalid GIF embeds and unknown stickers safely`() =
        runTest {
            val videoOnlyTenor =
                createMessage(
                    id = 201L,
                    username = "Alice",
                    content = "",
                    embeds =
                        listOf(
                            createGifEmbed(
                                url = "https://tenor.com/view/video-only-123",
                                providerName = "Tenor",
                                thumbnailUrl = "https://media.tenor.com/abc/clip.mp4",
                                videoUrl = "https://media.tenor.com/abc/clip.mp4",
                            ),
                        ),
                )
            val unknownStickerMsg =
                createMessage(
                    id = 202L,
                    username = "Bob",
                    content = "",
                    stickers = listOf(createMockSticker(950L, "unknown_fmt", MessageStickerType.from(99))),
                )

            val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })
            val decision = collector.collectDecisionContext(listOf(videoOnlyTenor, unknownStickerMsg))
            val reply = collector.collectReplyContext(listOf(videoOnlyTenor, unknownStickerMsg))

            assertTrue(decision.images.isEmpty())
            assertTrue(decision.transcript.contains("[Alice]: [GIF]"))
            assertTrue(decision.transcript.contains("[Bob]: [貼圖: unknown_fmt]"))

            assertTrue(reply.imageAttachments.isEmpty())
            assertTrue(reply.transcript.contains("[Alice]: [GIF]"))
            assertTrue(reply.transcript.contains("[Bob]: [貼圖: unknown_fmt]"))
        }
}
