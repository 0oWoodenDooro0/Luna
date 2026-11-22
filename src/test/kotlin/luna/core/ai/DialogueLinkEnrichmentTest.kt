package luna.core.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.socialpeek.model.Author
import dev.socialpeek.model.Comment
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogueLinkEnrichmentTest {
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
        content: String,
    ): Message {
        val message = mockk<Message>(relaxed = true)
        every { message.id } returns Snowflake(id)
        every { message.content } returns content
        every { message.attachments } returns emptySet()
        every { message.stickers } returns emptyList()
        every { message.embeds } returns emptyList()
        every { message.kord.selfId } returns selfId
        val author = mockk<User>(relaxed = true)
        every { author.id } returns Snowflake(100L)
        every { author.username } returns "Alice"
        every { author.isBot } returns false
        every { message.author } returns author
        return message
    }

    @Test
    fun `enrichLinks selects latest social or YouTube URL in reverse chronological order`() =
        runTest {
            val msg1 = mockMessage(1L, "Earlier post https://x.com/user/status/111")
            val msg2 = mockMessage(2L, "Newer video https://youtu.be/abc12345678 and docs https://kotlinlang.org")
            var resolvedTarget: String? = null

            val result =
                engine.enrichLinks(
                    messages = listOf(msg1, msg2),
                    policy = DialogueContextPolicy.chatter(),
                    peekResolver = { url ->
                        resolvedTarget = url
                        PeekPost(
                            platform = Platform.YOUTUBE,
                            id = "abc12345678",
                            originalUrl = "https://youtu.be/abc12345678",
                            cleanUrl = "https://www.youtube.com/watch?v=abc12345678",
                            author = Author(username = "Creator"),
                            title = "Kotlin Conf",
                            content = "Keynote description",
                            comments = listOf(Comment(author = "Bob", content = "Great talk!")),
                            rawData = mapOf("durationSeconds" to "600"),
                        )
                    },
                )

            assertEquals("https://youtu.be/abc12345678", resolvedTarget)
            assertEquals("https://youtu.be/abc12345678", result.primaryUrl)
            assertEquals("https://www.youtube.com/watch?v=abc12345678", result.videoUri)
            assertFalse(result.durationExceeded, "600s video must not exceed 7200s cap")
            assertEquals(listOf("https://kotlinlang.org"), result.classifiedUrls.webUrls)
        }

    @Test
    fun `fallbackPrimaryUrlToWeb promotes web URL only when policy enables it`() =
        runTest {
            val msg = mockMessage(1L, "Read https://example.com/article and https://kotlinlang.org/docs")

            val chatterResult =
                engine.enrichLinks(
                    messages = listOf(msg),
                    policy = DialogueContextPolicy.chatter(),
                    peekResolver = { null },
                )
            assertNull(chatterResult.primaryUrl)
            assertEquals(
                listOf("https://example.com/article", "https://kotlinlang.org/docs"),
                chatterResult.classifiedUrls.webUrls,
            )

            val summaryResult =
                engine.enrichLinks(
                    messages = listOf(msg),
                    policy = DialogueContextPolicy.summary(),
                    peekResolver = { null },
                )
            assertEquals("https://kotlinlang.org/docs", summaryResult.primaryUrl)
        }

    @Test
    fun `YouTube duration cap omits videoUri above 7200s while retaining extracted content and allows exact 7200s`() =
        runTest {
            val logger = LoggerFactory.getLogger(DiscordDialogueContextEngine::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(listAppender)

            try {
                val msg = mockMessage(1L, "Watch https://www.youtube.com/watch?v=longvideo12")

                val overLimit =
                    engine.enrichLinks(
                        messages = listOf(msg),
                        policy = DialogueContextPolicy.chatter(),
                        peekResolver = {
                            PeekPost(
                                platform = Platform.YOUTUBE,
                                id = "longvideo12",
                                originalUrl = "https://www.youtube.com/watch?v=longvideo12",
                                cleanUrl = "https://www.youtube.com/watch?v=longvideo12",
                                author = Author(username = "Creator"),
                                title = "3-Hour Livestream",
                                content = "Creator chapters and notes",
                                comments = listOf(Comment(author = "Fan", content = "Loved the Q&A")),
                                rawData = mapOf("durationSeconds" to "7201"),
                            )
                        },
                    )
                assertTrue(overLimit.durationExceeded, "7201s must trigger durationExceeded=true")
                assertNull(overLimit.videoUri)
                assertEquals(7201L, overLimit.videoDurationSeconds)
                assertEquals("3-Hour Livestream", overLimit.title)
                assertEquals("Creator chapters and notes", overLimit.postContent)
                assertEquals(listOf("Loved the Q&A"), overLimit.comments)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN &&
                            it.formattedMessage.contains("7201s") &&
                            it.formattedMessage.contains("maxYouTubeDurationSeconds=7200s")
                    },
                    "Expected WARN log when YouTube video duration exceeds 7200s cap",
                )

                listAppender.list.clear()

                val exactLimit =
                    engine.enrichLinks(
                        messages = listOf(msg),
                        policy = DialogueContextPolicy.summary(),
                        peekResolver = {
                            PeekPost(
                                platform = Platform.YOUTUBE,
                                id = "longvideo12",
                                originalUrl = "https://www.youtube.com/watch?v=longvideo12",
                                cleanUrl = "https://www.youtube.com/watch?v=longvideo12",
                                author = Author(username = "Creator"),
                                title = "2-Hour Exact",
                                content = "Exact limit",
                                rawData = mapOf("durationSeconds" to "7200"),
                            )
                        },
                    )
                assertFalse(exactLimit.durationExceeded, "7200s boundary must be allowed")
                assertEquals("https://www.youtube.com/watch?v=longvideo12", exactLimit.videoUri)
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `YouTube link falls back to regex videoUri when peekResolver throws or returns null`() =
        runTest {
            val logger = LoggerFactory.getLogger(DiscordDialogueContextEngine::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(listAppender)

            try {
                val ytUrl = "https://www.youtube.com/watch?v=fallback123"
                val socialUrl = "https://x.com/user/status/999"
                val ytMsg = mockMessage(1L, "Check $ytUrl")
                val socialMsg = mockMessage(2L, "Check $socialUrl")

                val onThrow =
                    engine.enrichLinks(
                        messages = listOf(ytMsg),
                        policy = DialogueContextPolicy.chatter(),
                        peekResolver = { error("YouTube API 429") },
                    )
                assertEquals(ytUrl, onThrow.videoUri)
                assertFalse(onThrow.durationExceeded, "Fallback without duration must not mark durationExceeded")
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("YouTube API 429")
                    },
                    "Expected WARN log when peekResolver throws",
                )
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN &&
                            it.formattedMessage.contains("YouTubeResolver failed or returned null") &&
                            it.formattedMessage.contains(ytUrl)
                    },
                    "Expected WARN log for YouTube regex videoUri fallback after exception",
                )

                listAppender.list.clear()

                val onNull =
                    engine.enrichLinks(
                        messages = listOf(ytMsg),
                        policy = DialogueContextPolicy.summary(),
                        peekResolver = { null },
                    )
                assertEquals(ytUrl, onNull.videoUri)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN &&
                            it.formattedMessage.contains("SocialPeek returned null") &&
                            it.formattedMessage.contains(ytUrl)
                    },
                    "Expected WARN log when SocialPeek returns null for YouTube URL",
                )
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN &&
                            it.formattedMessage.contains("YouTubeResolver failed or returned null") &&
                            it.formattedMessage.contains(ytUrl)
                    },
                    "Expected WARN log for YouTube regex videoUri fallback after null",
                )

                listAppender.list.clear()

                val socialOnNull =
                    engine.enrichLinks(
                        messages = listOf(socialMsg),
                        policy = DialogueContextPolicy.chatter(),
                        peekResolver = { null },
                    )
                assertNull(socialOnNull.videoUri)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN &&
                            it.formattedMessage.contains("SocialPeek returned null") &&
                            it.formattedMessage.contains(socialUrl)
                    },
                    "Expected WARN log when SocialPeek returns null for social URL",
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
