package luna.core.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.MessageStickerType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.StickerItem
import dev.kord.core.entity.User
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.core.BoundedSummaryRegistry
import luna.core.LunaWebhookRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogueContextEngineTest {
    private val selfId = Snowflake(999L)
    private val summaryRegistry = BoundedSummaryRegistry()
    private val engine = DiscordDialogueContextEngine(summaryRegistry = summaryRegistry)

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        summaryRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        LunaWebhookRegistry.clear()
        summaryRegistry.clear()
    }

    private fun mockMessage(
        id: Long,
        content: String = "",
        authorId: Long = 100L,
        authorName: String = "Alice",
        isBot: Boolean = false,
        attachments: Set<Attachment> = emptySet(),
        stickers: List<StickerItem> = emptyList(),
        embeds: List<Embed> = emptyList(),
    ): Message {
        val message = mockk<Message>(relaxed = true)
        every { message.id } returns Snowflake(id)
        every { message.content } returns content
        every { message.attachments } returns attachments
        every { message.stickers } returns stickers
        every { message.embeds } returns embeds
        every { message.kord.selfId } returns selfId

        val author = mockk<User>(relaxed = true)
        every { author.id } returns Snowflake(authorId)
        every { author.username } returns authorName
        every { author.isBot } returns isBot
        every { message.author } returns author
        return message
    }

    private fun mockEmbed(
        title: String? = null,
        description: String? = null,
        url: String? = null,
        providerName: String? = null,
    ): Embed {
        val embed = mockk<Embed>(relaxed = true)
        every { embed.title } returns title
        every { embed.description } returns description
        every { embed.url } returns url
        if (providerName != null) {
            val provider = mockk<Embed.Provider>(relaxed = true)
            every { provider.name } returns providerName
            every { embed.provider } returns provider
        } else {
            every { embed.provider } returns null
        }
        every { embed.image } returns null
        every { embed.thumbnail } returns null
        every { embed.video } returns null
        return embed
    }

    @Test
    fun `resolveExternalReplyReferences skips in-slice replies, enforces cap, and deduplicates shared targets`() =
        runTest {
            val logger = LoggerFactory.getLogger(DiscordDialogueContextEngine::class.java) as Logger
            val previousLevel = logger.level
            val listAppender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.DEBUG
            logger.addAppender(listAppender)

            try {
                val inSliceTarget = mockMessage(1L, content = "In slice", authorName = "Bob")
                val msgInSliceReply = mockMessage(2L, content = "Replying in slice", authorName = "Alice")
                val ext1 = mockMessage(100L, content = "External 1", authorName = "Carol")
                val ext2 = mockMessage(101L, content = "External 2", authorName = "Dave")
                val ext3 = mockMessage(102L, content = "External 3", authorName = "Eve")

                val msg3 = mockMessage(3L, content = "Reply to ext1", authorName = "Alice")
                val msg4 = mockMessage(4L, content = "Also reply to ext1", authorName = "Bob")
                val msg5 = mockMessage(5L, content = "Reply to ext2", authorName = "Alice")
                val msg6 = mockMessage(6L, content = "Reply to ext3 exceeds cap", authorName = "Bob")

                val refs =
                    mapOf(
                        2L to inSliceTarget,
                        3L to ext1,
                        4L to ext1,
                        5L to ext2,
                        6L to ext3,
                    )
                val policy = DialogueContextPolicy.summary(maxExternalReferences = 2)
                val resolved =
                    engine.resolveExternalReplyReferences(
                        messages = listOf(inSliceTarget, msgInSliceReply, msg3, msg4, msg5, msg6),
                        policy = policy,
                        referenceFetcher = { msg -> refs[msg.id.value.toLong()] },
                    )

                assertEquals(3, resolved.size)
                assertEquals(ext1, resolved[msg3.id])
                assertEquals(ext1, resolved[msg4.id])
                assertEquals(ext2, resolved[msg5.id])
                assertTrue(msg6.id !in resolved, "External reference beyond maxExternalReferences=2 must be skipped")
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.DEBUG &&
                            it.formattedMessage.contains("maxExternalReferences=2") &&
                            it.formattedMessage.contains("102")
                    },
                    "Expected DEBUG log when maxExternalReferences cap is reached",
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
                logger.level = previousLevel
            }
        }

    @Test
    fun `resolveExternalReplyReferences guards non-positive cap and degrades cleanly on fetcher failure`() =
        runTest {
            val logger = LoggerFactory.getLogger(DiscordDialogueContextEngine::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(listAppender)

            try {
                val msg1 = mockMessage(1L, content = "Reply 1")
                val msg2 = mockMessage(2L, content = "Reply 2")
                val ext2 = mockMessage(200L, content = "Recovered ref")
                var fetchCalls = 0

                val zeroCap =
                    engine.resolveExternalReplyReferences(
                        messages = listOf(msg1, msg2),
                        policy = DialogueContextPolicy.chatter(maxExternalReferences = 0),
                        referenceFetcher = {
                            fetchCalls++
                            ext2
                        },
                    )
                assertTrue(zeroCap.isEmpty(), "maxExternalReferences <= 0 must return empty map")
                assertEquals(0, fetchCalls)

                val negativeCap =
                    engine.resolveExternalReplyReferences(
                        messages = listOf(msg1),
                        policy = DialogueContextPolicy.chatter(maxExternalReferences = -3),
                        referenceFetcher = {
                            fetchCalls++
                            ext2
                        },
                    )
                assertTrue(negativeCap.isEmpty(), "Negative maxExternalReferences must return empty map")
                assertEquals(0, fetchCalls)

                val partial =
                    engine.resolveExternalReplyReferences(
                        messages = listOf(msg1, msg2),
                        policy = DialogueContextPolicy.summary(),
                        referenceFetcher = { msg ->
                            if (msg.id == msg1.id) error("Discord 500") else ext2
                        },
                    )
                assertEquals(1, partial.size)
                assertEquals(ext2, partial[msg2.id])
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("Discord 500")
                    },
                    "Expected WARN log when referenceFetcher throws",
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `formatDialogueMessages truncates reply excerpts at 120 chars by default and respects custom cap`() {
        val logger = LoggerFactory.getLogger(DiscordDialogueContextEngine::class.java) as Logger
        val previousLevel = logger.level
        val listAppender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(listAppender)

        try {
            val longRefText = "A".repeat(130)
            val refMsg = mockMessage(10L, content = longRefText, authorName = "Bob")
            val replyMsg = mockMessage(11L, content = "Look at this", authorName = "Alice")
            val refs = mapOf(replyMsg.id to refMsg)

            val defaultFormatted =
                engine.formatDialogueMessages(
                    messages = listOf(replyMsg),
                    policy = DialogueContextPolicy.summary(),
                    replyReferencesByMessage = refs,
                    formatSpeakerHeader = { it.name },
                    kordSelfId = selfId,
                )
            val expectedDefaultExcerpt = "A".repeat(117) + "..."
            assertEquals("[Alice 回覆 Bob「$expectedDefaultExcerpt」]: Look at this", defaultFormatted.transcript)
            assertTrue(
                listAppender.list.any {
                    it.level == Level.DEBUG && it.formattedMessage.contains("maxReplyExcerptLength=120")
                },
                "Expected DEBUG log when reply excerpt is truncated at default 120 chars",
            )

            listAppender.list.clear()

            val customFormatted =
                engine.formatDialogueMessages(
                    messages = listOf(replyMsg),
                    policy = DialogueContextPolicy.summary(maxReplyExcerptLength = 20),
                    replyReferencesByMessage = refs,
                    formatSpeakerHeader = { it.name },
                    kordSelfId = selfId,
                )
            val expectedCustomExcerpt = "A".repeat(17) + "..."
            assertEquals("[Alice 回覆 Bob「$expectedCustomExcerpt」]: Look at this", customFormatted.transcript)
            assertTrue(
                listAppender.list.any {
                    it.level == Level.DEBUG && it.formattedMessage.contains("maxReplyExcerptLength=20")
                },
                "Expected DEBUG log when reply excerpt is truncated at custom 20 chars",
            )
        } finally {
            logger.detachAppender(listAppender)
            listAppender.stop()
            logger.level = previousLevel
        }
    }

    @Test
    fun `formatEmbedPreviews formats Tenor and Giphy embeds as GIF and other embeds as link previews`() {
        val tenorEmbed = mockEmbed(title = "Happy Dance", providerName = "Tenor")
        val giphyEmbed = mockEmbed(description = "Cat Typing", url = "https://giphy.com/gifs/abc")
        val emptyGifEmbed = mockEmbed(providerName = "Tenor")
        val articleEmbed = mockEmbed(title = "Kotlin 2.1", description = "Release notes", url = "https://kotlinlang.org")

        val msg = mockMessage(1L, embeds = listOf(tenorEmbed, giphyEmbed, emptyGifEmbed, articleEmbed))
        val preview = engine.formatEmbedPreviews(msg)

        assertEquals(
            "[GIF: Happy Dance] [GIF: Cat Typing] [GIF] [連結預覽: Kotlin 2.1 - Release notes]",
            preview,
        )
    }

    @Test
    fun `formatDialogueMessages gates sticker annotations and image extraction by policy`() {
        val sticker = mockk<StickerItem>(relaxed = true)
        every { sticker.name } returns "ThumbsUp"
        every { sticker.id } returns Snowflake(500L)
        every { sticker.formatType } returns MessageStickerType.PNG

        val attachment = mockk<Attachment>(relaxed = true)
        every { attachment.url } returns "https://cdn.discordapp.com/attachments/1/2/photo.png"
        every { attachment.contentType } returns "image/png"
        every { attachment.filename } returns "photo.png"
        every { attachment.size } returns 1024

        val msg1 = mockMessage(1L, content = "Nice!", authorName = "Alice", stickers = listOf(sticker))
        val msg2 = mockMessage(2L, content = "", authorName = "Bob", attachments = setOf(attachment))

        val chatterImages =
            engine.selectImages(
                messages = listOf(msg1, msg2),
                policy = DialogueContextPolicy.chatter(),
                maxImages = 10,
            )
        val chatterResult =
            engine.formatDialogueMessages(
                messages = listOf(msg1, msg2),
                policy = DialogueContextPolicy.chatter(),
                imagesByMessage = chatterImages,
                formatSpeakerHeader = { it.name },
                kordSelfId = selfId,
            )
        assertTrue(chatterResult.transcript.contains("[貼圖: ThumbsUp]"), "Chatter must include sticker annotation")
        assertTrue(chatterResult.transcript.contains("[附帶圖片 1: ThumbsUp.png]"), "Chatter must include sticker image marker")
        assertTrue(chatterResult.transcript.contains("[附帶圖片 2: photo.png]"), "Chatter must increment image index sequentially")
        assertEquals(3, chatterResult.nextImageIndex)

        val summaryImages =
            engine.selectImages(
                messages = listOf(msg1, msg2),
                policy = DialogueContextPolicy.summary(),
                maxImages = 10,
            )
        val summaryResult =
            engine.formatDialogueMessages(
                messages = listOf(msg1, msg2),
                policy = DialogueContextPolicy.summary(),
                imagesByMessage = summaryImages,
                formatSpeakerHeader = { it.name },
                kordSelfId = selfId,
            )
        assertTrue(!summaryResult.transcript.contains("[貼圖:"), "Summary must omit sticker annotation")
        assertTrue(!summaryResult.transcript.contains("ThumbsUp.png"), "Summary must omit sticker image")
        assertTrue(summaryResult.transcript.contains("[Bob]: [附帶圖片 1: photo.png]"), "Summary must include photo attachment marker")
        assertEquals(2, summaryResult.nextImageIndex)
    }
}
