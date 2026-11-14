package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.socialpeek.model.Author
import dev.socialpeek.model.Comment
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterContextCollectorTest {
    private lateinit var filter: ChatterNoiseFilter

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        SummaryMessageRegistry.clear()
        filter = ChatterNoiseFilter()
    }

    private fun createMockAttachment(
        url: String,
        contentType: String?,
        filename: String = "image.png",
        size: Int = 512 * 1024,
    ): Attachment {
        val attachment = mockk<Attachment>()
        every { attachment.url } returns url
        every { attachment.contentType } returns contentType
        every { attachment.filename } returns filename
        every { attachment.size } returns size
        return attachment
    }

    private fun createMockEmbed(
        title: String? = null,
        description: String? = null,
    ): Embed {
        val embed = mockk<Embed>()
        every { embed.title } returns title
        every { embed.description } returns description
        return embed
    }

    private fun createMockMessage(
        id: Long,
        username: String = "Alice",
        content: String = "",
        isBot: Boolean = false,
        authorId: Snowflake? = null,
        webhookId: Snowflake? = null,
        type: MessageType = MessageType.Default,
        attachments: Set<Attachment> = emptySet(),
        embeds: List<Embed> = emptyList(),
        referencedMessage: Message? = null,
    ): Message {
        val resolvedAuthorId = authorId ?: webhookId ?: Snowflake(id)
        val author = mockk<User>()
        every { author.username } returns username
        every { author.isBot } returns isBot
        every { author.id } returns resolvedAuthorId

        val userData = mockk<UserData>()
        every { userData.username } returns username
        every { userData.id } returns resolvedAuthorId

        val messageData = mockk<MessageData>()
        every { messageData.author } returns userData
        every { messageData.content } returns content
        every { messageData.id } returns Snowflake(id)

        val message = mockk<Message>(relaxed = true)
        every { message.id } returns Snowflake(id)
        every { message.channelId } returns Snowflake(100L)
        every { message.author } returns author
        every { message.data } returns messageData
        every { message.content } returns content
        every { message.type } returns type
        every { message.webhookId } returns webhookId
        every { message.attachments } returns attachments
        every { message.embeds } returns embeds
        every { message.mentionedUserIds } returns emptySet()
        every { message.referencedMessage } returns referencedMessage
        every { message.messageReference } returns null
        return message
    }

    @Test
    fun `collectDecisionContext keeps only last 3 filtered messages with embeds, image markers, and stripped mentions`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val msg1 = createMockMessage(id = 1L, username = "Alice", content = "Earlier oldest message")
            val botNoise = createMockMessage(id = 2L, username = "MusicBot", content = "Now playing", isBot = true)
            val msg2 = createMockMessage(id = 3L, username = "Bob", content = "Second newest valid")
            val embed = createMockEmbed(title = "Release Notes", description = "v2.0 highlights")
            val msg3 =
                createMockMessage(
                    id = 4L,
                    username = "Charlie",
                    content = "Check https://example.com/release",
                    embeds = listOf(embed),
                )
            val img = createMockAttachment("https://cdn.example.com/cat.png", "image/png", "cat.png")
            val msg4 =
                createMockMessage(
                    id = 5L,
                    username = "Dave",
                    content = "@Luna look at this",
                    attachments = setOf(img),
                )

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    decisionMessageCount = 3,
                    historyMessageCount = 15,
                    peekResolver = { null },
                )

            val result =
                collector.collectDecisionContext(
                    messages = listOf(msg1, botNoise, msg2, msg3, msg4),
                    kordSelfId = botSelfId,
                )

            assertFalse(result.transcript.contains("Earlier oldest message"))
            assertFalse(result.transcript.contains("MusicBot"))
            assertTrue(result.transcript.contains("[Bob]: Second newest valid"))
            assertTrue(
                result.transcript.contains(
                    "[Charlie]: Check https://example.com/release [連結預覽: Release Notes - v2.0 highlights]",
                ),
            )
            assertTrue(result.transcript.contains("[Dave]: look at this [附帶圖片 1: cat.png]"))
            assertFalse(result.transcript.contains("@Luna"))

            assertEquals(1, result.images.size)
            assertEquals("https://cdn.example.com/cat.png", result.images.first().url)
            assertEquals("image/png", result.images.first().mimeType)
        }

    @Test
    fun `collectDecisionContext caps at 3 newest valid images and discards oversized or unsupported attachments`() =
        runTest {
            val tenMbPlusOne = 10 * 1024 * 1024 + 1
            val img1 = createMockAttachment("https://cdn.example.com/1.png", "image/png", "1.png")
            val img2 = createMockAttachment("https://cdn.example.com/2.jpg", "image/jpeg", "2.jpg")
            val img3 = createMockAttachment("https://cdn.example.com/3.webp", "image/webp", "3.webp")
            val img4 = createMockAttachment("https://cdn.example.com/4.gif", "image/gif", "4.gif")
            val oversized = createMockAttachment("https://cdn.example.com/huge.png", "image/png", "huge.png", size = tenMbPlusOne)
            val spoofed =
                createMockAttachment("https://cdn.example.com/fake.png", "application/octet-stream", "fake.png", size = 1024)

            val msg1 = createMockMessage(id = 10L, username = "Alice", content = "Old pics", attachments = setOf(img1, img2))
            val msg2 = createMockMessage(id = 11L, username = "Bob", content = "", attachments = setOf(oversized, spoofed))
            val msg3 = createMockMessage(id = 12L, username = "Charlie", content = "New pics", attachments = setOf(img3, img4))

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { null },
                )

            val result = collector.collectDecisionContext(listOf(msg1, msg2, msg3))

            assertEquals(
                listOf("https://cdn.example.com/2.jpg", "https://cdn.example.com/3.webp", "https://cdn.example.com/4.gif"),
                result.images.map { it.url },
            )
            assertTrue(result.transcript.contains("[Alice]: Old pics [附帶圖片 1: 2.jpg]"))
            assertTrue(result.transcript.contains("[Bob]: [Attachment]"))
            assertFalse(result.transcript.contains("huge.png"))
            assertFalse(result.transcript.contains("fake.png"))
            assertTrue(result.transcript.contains("[Charlie]: New pics [附帶圖片 2: 3.webp] [附帶圖片 3: 4.gif]"))
        }

    @Test
    fun `collectDecisionContext never invokes SocialPeek resolver when URLs are present`() =
        runTest {
            var peekCalled = false
            val failingPeekResolver: suspend (String) -> PeekPost? = {
                peekCalled = true
                error("SocialPeek must not be called in decision stage")
            }

            val msg = createMockMessage(id = 20L, username = "Alice", content = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = failingPeekResolver,
                )

            val result = collector.collectDecisionContext(listOf(msg))

            assertFalse(peekCalled)
            assertTrue(result.transcript.contains("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        }

    @Test
    fun `collectReplyContext keeps last 15 filtered messages and resolves up to 3 external reply references with 40 char excerpt`() =
        runTest {
            val ext1 = createMockMessage(id = 1001L, username = "ExtAlice", content = "A".repeat(60))
            val ext2 = createMockMessage(id = 1002L, username = "ExtBob", content = "Short external context")
            val ext3 = createMockMessage(id = 1003L, username = "ExtCharlie", content = "Third external msg")
            val ext4 = createMockMessage(id = 1004L, username = "ExtDave", content = "Fourth external msg beyond cap")

            val oldestDropped = createMockMessage(id = 1010L, username = "OldUser", content = "Too old beyond 15")
            val botNoise = createMockMessage(id = 1011L, username = "NoiseBot", content = "Ignore me", isBot = true)

            val windowMsgs =
                (1..15).map { idx ->
                    val id = 2000L + idx
                    when (idx) {
                        1 -> createMockMessage(id = id, username = "U1", content = "Reply to ext1", referencedMessage = ext1)
                        2 -> createMockMessage(id = id, username = "U2", content = "Reply to ext2", referencedMessage = ext2)
                        3 -> createMockMessage(id = id, username = "U3", content = "Reply to ext3", referencedMessage = ext3)
                        4 -> createMockMessage(id = id, username = "U4", content = "Reply to ext4", referencedMessage = ext4)
                        else -> createMockMessage(id = id, username = "U$idx", content = "Turn $idx")
                    }
                }
            val inWindowReply =
                createMockMessage(
                    id = 2016L,
                    username = "U16",
                    content = "Replying in window",
                    referencedMessage = windowMsgs[5],
                )
            // Keep 15 messages by dropping oldest U1? Wait: if we have 15 messages total in window, make in-window reply one of the 15
            val allMessages =
                listOf(oldestDropped, botNoise) +
                    windowMsgs.dropLast(1) +
                    listOf(inWindowReply)

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    historyMessageCount = 15,
                    peekResolver = { null },
                )

            val result = collector.collectReplyContext(allMessages)

            assertFalse(result.transcript.contains("Too old beyond 15"))
            assertFalse(result.transcript.contains("NoiseBot"))
            val expectedExcerpt = "A".repeat(37) + "..."
            assertTrue(result.transcript.contains("[U1 回覆 ExtAlice「$expectedExcerpt」]: Reply to ext1"))
            assertTrue(result.transcript.contains("[U2 回覆 ExtBob「Short external context」]: Reply to ext2"))
            assertTrue(result.transcript.contains("[U3 回覆 ExtCharlie「Third external msg」]: Reply to ext3"))
            assertTrue(result.transcript.contains("[U4]: Reply to ext4"))
            assertFalse(result.transcript.contains("回覆 ExtDave"))
            assertTrue(result.transcript.contains("[U16]: Replying in window"))
            assertFalse(result.transcript.contains("回覆 U6"))
        }

    @Test
    fun `collectReplyContext retains up to maxImages newest valid images and discards oversized or unsupported attachments`() =
        runTest {
            val tenMbPlusOne = 10 * 1024 * 1024 + 1
            val validAttachments =
                (1..12).map { idx ->
                    createMockAttachment("https://cdn.example.com/$idx.png", "image/png", "$idx.png")
                }
            val oversized = createMockAttachment("https://cdn.example.com/huge.jpg", "image/jpeg", "huge.jpg", size = tenMbPlusOne)
            val svgUnsupported = createMockAttachment("https://cdn.example.com/vec.svg", "image/svg+xml", "vec.svg", size = 1024)

            val messages =
                validAttachments.mapIndexed { idx, att ->
                    createMockMessage(id = 3000L + idx, username = "User$idx", content = "img $idx", attachments = setOf(att))
                } +
                    listOf(
                        createMockMessage(id = 3050L, username = "BadUser", content = "bad", attachments = setOf(oversized, svgUnsupported)),
                    )

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    maxImages = 10,
                    peekResolver = { null },
                )

            val result = collector.collectReplyContext(messages)

            assertEquals(10, result.imageAttachments.size)
            assertEquals(
                (3..12).map { "https://cdn.example.com/$it.png" },
                result.imageAttachments.map { it.url },
            )
            assertFalse(result.transcript.contains("huge.jpg"))
            assertFalse(result.transcript.contains("vec.svg"))
            assertTrue(result.transcript.contains("[附帶圖片 1: 3.png]"))
            assertTrue(result.transcript.contains("[附帶圖片 10: 12.png]"))
        }

    @Test
    fun `collectReplyContext resolves only latest primaryUrl via SocialPeek and enriches transcript`() =
        runTest {
            val resolvedUrls = mutableListOf<String>()
            val peekPost =
                PeekPost(
                    platform = Platform.THREADS,
                    id = "post123",
                    originalUrl = "https://www.threads.net/@alice/post/123?x=1",
                    cleanUrl = "https://www.threads.net/@alice/post/123",
                    author = Author(username = "alice"),
                    title = "Kotlin Multiplatform Updates",
                    content = "Compose Multiplatform for iOS is now stable!",
                    comments =
                        listOf(
                            Comment(author = "dev1", content = "Finally stable!"),
                            Comment(author = "dev2", content = "Awesome news"),
                        ),
                )

            val msg1 = createMockMessage(id = 4001L, username = "Bob", content = "Earlier link https://example.com/old-article")
            val msg2 =
                createMockMessage(
                    id = 4002L,
                    username = "Charlie",
                    content = "Check this thread https://www.threads.net/@alice/post/123?x=1",
                )

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { url ->
                        resolvedUrls.add(url)
                        peekPost
                    },
                )

            val result = collector.collectReplyContext(listOf(msg1, msg2))

            assertEquals(listOf("https://www.threads.net/@alice/post/123?x=1"), resolvedUrls)
            assertEquals("https://www.threads.net/@alice/post/123?x=1", result.primaryUrl)
            assertEquals("https://www.threads.net/@alice/post/123", result.url)
            assertEquals("Kotlin Multiplatform Updates", result.title)
            assertEquals("Compose Multiplatform for iOS is now stable!", result.postContent)
            assertEquals(listOf("Finally stable!", "Awesome news"), result.comments)
            assertNull(result.videoUri)
            assertNull(result.videoDurationSeconds)

            assertTrue(result.transcript.contains("[Bob]: Earlier link https://example.com/old-article"))
            assertTrue(result.transcript.contains("[Charlie]: Check this thread https://www.threads.net/@alice/post/123?x=1"))
            assertTrue(result.transcript.contains("Kotlin Multiplatform Updates"))
            assertTrue(result.transcript.contains("Compose Multiplatform for iOS is now stable!"))
            assertTrue(result.transcript.contains("Finally stable!"))
        }

    @Test
    fun `collectReplyContext captures YouTube videoUri and durationSeconds from PeekPost`() =
        runTest {
            val ytUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            val ytPost =
                PeekPost(
                    platform = Platform.YOUTUBE,
                    id = "dQw4w9WgXcQ",
                    originalUrl = ytUrl,
                    cleanUrl = ytUrl,
                    author = Author(username = "Creator"),
                    title = "Kotlin Coroutines Deep Dive",
                    content = "Video description about structured concurrency",
                    comments = listOf(Comment(author = "viewer", content = "Great explanation")),
                    rawData = mapOf("durationSeconds" to "420"),
                )

            val msg = createMockMessage(id = 4010L, username = "Alice", content = "Watch $ytUrl")
            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { ytPost },
                )

            val result = collector.collectReplyContext(listOf(msg))

            assertEquals(ytUrl, result.primaryUrl)
            assertEquals(ytUrl, result.url)
            assertEquals(ytUrl, result.videoUri)
            assertEquals(420L, result.videoDurationSeconds)
            assertEquals("Kotlin Coroutines Deep Dive", result.title)
            assertEquals("Video description about structured concurrency", result.postContent)
            assertEquals(listOf("Great explanation"), result.comments)
            assertTrue(result.transcript.contains("Kotlin Coroutines Deep Dive"))
            assertTrue(result.transcript.contains("Video description about structured concurrency"))
        }

    @Test
    fun `collectReplyContext logs warning and degrades to Discord embed transcript when SocialPeek fails or returns null`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterContextCollector::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val embed = createMockEmbed(title = "Fallback Embed Title", description = "Fallback embed snippet")
                val msg =
                    createMockMessage(
                        id = 5001L,
                        username = "Alice",
                        content = "Read https://example.com/broken",
                        embeds = listOf(embed),
                    )

                // Case 1: peekResolver throws exception
                val throwingCollector =
                    ChatterContextCollector(
                        noiseFilter = filter,
                        peekResolver = { throw IllegalStateException("HTTP 503 upstream failure") },
                    )
                val degradedOnThrow = throwingCollector.collectReplyContext(listOf(msg))

                assertEquals("https://example.com/broken", degradedOnThrow.primaryUrl)
                assertNull(degradedOnThrow.title)
                assertNull(degradedOnThrow.postContent)
                assertTrue(degradedOnThrow.comments.isEmpty())
                assertTrue(degradedOnThrow.transcript.contains("[Alice]: Read https://example.com/broken"))
                assertTrue(degradedOnThrow.transcript.contains("[連結預覽: Fallback Embed Title - Fallback embed snippet]"))
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("https://example.com/broken")
                    },
                )

                listAppender.list.clear()

                // Case 2: peekResolver returns null
                val nullCollector =
                    ChatterContextCollector(
                        noiseFilter = filter,
                        peekResolver = { null },
                    )
                val degradedOnNull = nullCollector.collectReplyContext(listOf(msg))

                assertEquals("https://example.com/broken", degradedOnNull.primaryUrl)
                assertNull(degradedOnNull.postContent)
                assertTrue(degradedOnNull.transcript.contains("[連結預覽: Fallback Embed Title - Fallback embed snippet]"))
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("https://example.com/broken")
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
