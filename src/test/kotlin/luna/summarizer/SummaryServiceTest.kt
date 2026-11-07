package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.StickerItem
import dev.kord.core.entity.User
import dev.kord.core.event.message.ReactionAddEvent
import dev.socialpeek.model.Author
import dev.socialpeek.model.Comment
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SummaryServiceTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
    }

    @Test
    fun `isSummaryEmoji matches default memo emojis and custom configured emoji`() {
        val service = SummaryService(configuredEmoji = "⭐")

        assertTrue(service.isSummaryEmoji(ReactionEmoji.Unicode("📝")))
        assertTrue(service.isSummaryEmoji(ReactionEmoji.Unicode("\uD83D\uDCDD")))
        assertTrue(service.isSummaryEmoji(ReactionEmoji.Unicode(":memo:")))
        assertTrue(service.isSummaryEmoji(ReactionEmoji.Unicode("memo")))
        assertTrue(service.isSummaryEmoji(ReactionEmoji.Unicode("⭐")))

        assertFalse(service.isSummaryEmoji(ReactionEmoji.Unicode("👍")))
        assertFalse(service.isSummaryEmoji(ReactionEmoji.Unicode("🎉")))
    }

    @Test
    fun `truncateForDiscord truncates text exceeding 2000 characters`() {
        val service = SummaryService()
        val shortText = "Short summary"
        assertEquals(shortText, service.truncateForDiscord(shortText))

        val exact2000 = "a".repeat(2000)
        assertEquals(exact2000, service.truncateForDiscord(exact2000))

        val over2000 = "a".repeat(2500)
        val truncated = service.truncateForDiscord(over2000)
        assertEquals(2000, truncated.length)
        assertTrue(truncated.endsWith("..."))
        assertEquals("a".repeat(1997) + "...", truncated)
    }

    @Test
    fun `ignores reaction when emoji does not match summary emoji`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            val service = SummaryService(summarizer = summarizer)

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("👍")

            service.handleReaction(event)

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>(), any()) }
        }

    @Test
    fun `ignores reaction when reacting user is a bot`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            val service = SummaryService(summarizer = summarizer)

            val botUser = mockk<User>()
            every { botUser.isBot } returns true

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(111L)
            coEvery { event.getUserOrNull() } returns botUser

            service.handleReaction(event)

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>(), any()) }
        }

    @Test
    fun `ignores reaction when message author is a bot`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            val service = SummaryService(summarizer = summarizer)

            val reactingUser = mockk<User>()
            every { reactingUser.isBot } returns false

            val botAuthor = mockk<User>()
            every { botAuthor.isBot } returns true
            every { botAuthor.username } returns "BotAuthor"

            val message = mockk<Message>()
            every { message.author } returns botAuthor
            every { message.content } returns "Hello from bot"

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(111L)
            every { event.messageId } returns Snowflake(222L)
            coEvery { event.getUserOrNull() } returns reactingUser
            coEvery { event.getMessageOrNull() } returns message

            service.handleReaction(event)

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>(), any()) }
        }

    @Test
    fun `ignores reaction when message content is blank`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            val service = SummaryService(summarizer = summarizer)

            val reactingUser = mockk<User>()
            every { reactingUser.isBot } returns false

            val author = mockk<User>()
            every { author.isBot } returns false

            val message = mockk<Message>()
            every { message.author } returns author
            every { message.content } returns "   "

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(111L)
            every { event.messageId } returns Snowflake(222L)
            coEvery { event.getUserOrNull() } returns reactingUser
            coEvery { event.getMessageOrNull() } returns message

            service.handleReaction(event)

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>(), any()) }
        }

    @Test
    fun `deduplicates multiple reactions on same message so Gemini is called only once`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var repliesSent = 0
            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> repliesSent++ },
                )

            val user1 = mockk<User>()
            every { user1.isBot } returns false
            val user2 = mockk<User>()
            every { user2.isBot } returns false

            val author = mockk<User>()
            every { author.isBot } returns false

            val messageId = Snowflake(999L)
            val message = mockk<Message>()
            every { message.id } returns messageId
            every { message.author } returns author
            every { message.content } returns "Important discussion text"

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } returns SummaryResult.Success("TL;DR: Summary")

            val event1 = mockk<ReactionAddEvent>()
            every { event1.emoji } returns ReactionEmoji.Unicode("📝")
            every { event1.userId } returns Snowflake(1L)
            every { event1.messageId } returns messageId
            coEvery { event1.getUserOrNull() } returns user1
            coEvery { event1.getMessageOrNull() } returns message

            val event2 = mockk<ReactionAddEvent>()
            every { event2.emoji } returns ReactionEmoji.Unicode("📝")
            every { event2.userId } returns Snowflake(2L)
            every { event2.messageId } returns messageId
            coEvery { event2.getUserOrNull() } returns user2
            coEvery { event2.getMessageOrNull() } returns message

            // Run reactions concurrently
            val job1 = async { service.handleReaction(event1) }
            val job2 = async { service.handleReaction(event2) }
            awaitAll(job1, job2)

            // Later third reaction
            val event3 = mockk<ReactionAddEvent>()
            every { event3.emoji } returns ReactionEmoji.Unicode("📝")
            every { event3.userId } returns Snowflake(3L)
            every { event3.messageId } returns messageId
            coEvery { event3.getUserOrNull() } returns user1
            coEvery { event3.getMessageOrNull() } returns message
            service.handleReaction(event3)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            assertEquals(1, repliesSent)
            assertEquals(SummaryStatus.DONE, service.getStatus(messageId))
        }

    @Test
    fun `summarizes plain text message when no URL is present`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedContent: String? = null
            var replyCaptured: String? = null

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, content -> replyCaptured = content },
                )

            val user = mockk<User>()
            every { user.isBot } returns false
            val author = mockk<User>()
            every { author.isBot } returns false

            val messageId = Snowflake(1234L)
            val message = mockk<Message>()
            every { message.id } returns messageId
            every { message.author } returns author
            every { message.content } returns "This is a plain text Discord discussion without any links."

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                val input = firstArg<SummaryInput>()
                capturedContent = input.content
                SummaryResult.Success("TL;DR: Summary of plain text")
            }

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns messageId
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns message

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            assertTrue(capturedContent?.contains("This is a plain text Discord discussion without any links.") == true)
            assertEquals("TL;DR: Summary of plain text", replyCaptured)
            assertEquals(SummaryStatus.DONE, service.getStatus(messageId))
        }

    @Test
    fun `extracts SocialPeek post with comments when URL is present`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var replyCaptured: String? = null

            val mockPeekPost =
                PeekPost(
                    platform = Platform.REDDIT,
                    id = "post-1",
                    originalUrl = "https://reddit.com/r/kotlin/comments/123/post",
                    author = Author(username = "kotlindev"),
                    title = "Exciting Kotlin Features",
                    content = "Kotlin 2.0 brings K2 compiler with huge performance improvements.",
                    comments =
                        listOf(
                            Comment(author = "user1", content = "Build times are much faster!"),
                            Comment(author = "user2", content = "Smart cast improvements are great."),
                        ),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector = ConversationSliceCollector(peekResolver = { mockPeekPost }),
                    replySender = { _, content -> replyCaptured = content },
                )

            val user = mockk<User>()
            every { user.isBot } returns false
            val author = mockk<User>()
            every { author.isBot } returns false

            val messageId = Snowflake(5678L)
            val message = mockk<Message>()
            every { message.id } returns messageId
            every { message.author } returns author
            every { message.content } returns "Check out this thread: https://reddit.com/r/kotlin/comments/123/post it is awesome"

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Kotlin 2.0 release overview\n• Faster builds\n• Smart casts")
            }

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns messageId
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns message

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertTrue(input.content.contains("Check out this thread:  it is awesome"))
            assertTrue(input.content.contains("Kotlin 2.0 brings K2 compiler with huge performance improvements."))
            assertEquals("Exciting Kotlin Features", input.title)
            assertEquals(
                listOf("Build times are much faster!", "Smart cast improvements are great."),
                input.comments,
            )
            assertEquals("https://reddit.com/r/kotlin/comments/123/post", input.url)
            assertEquals("TL;DR: Kotlin 2.0 release overview\n• Faster builds\n• Smart casts", replyCaptured)
        }

    @Test
    fun `falls back to message content when SocialPeek resolver returns null`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector = ConversationSliceCollector(peekResolver = { null }),
                    replySender = { _, _ -> },
                )

            val user = mockk<User>()
            every { user.isBot } returns false
            val author = mockk<User>()
            every { author.isBot } returns false

            val messageId = Snowflake(4444L)
            val message = mockk<Message>()
            every { message.id } returns messageId
            every { message.author } returns author
            every { message.content } returns "Here is an unsupported link: https://unsupported.example.com/item"

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Unsupported link")
            }

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns messageId
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns message

            service.handleReaction(event)

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertTrue(input.content.contains("Here is an unsupported link: https://unsupported.example.com/item"))
            assertEquals("https://unsupported.example.com/item", input.url)
        }

    @Test
    fun `truncates summary response in Discord reply if exceeding limit`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var replyCaptured: String? = null

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, content -> replyCaptured = content },
                )

            val user = mockk<User>()
            every { user.isBot } returns false
            val author = mockk<User>()
            every { author.isBot } returns false

            val messageId = Snowflake(7777L)
            val message = mockk<Message>()
            every { message.id } returns messageId
            every { message.author } returns author
            every { message.content } returns "Long text to summarize"

            val longSummary = "A".repeat(2500)
            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } returns SummaryResult.Success(longSummary)

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns messageId
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns message

            service.handleReaction(event)

            val reply = replyCaptured
            kotlin.test.assertNotNull(reply)
            assertEquals(2000, reply.length)
            assertTrue(reply.endsWith("..."))
        }

    @Test
    fun `handles Gemini error gracefully without throwing exceptions`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var repliesSent = 0

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> repliesSent++ },
                )

            val user = mockk<User>()
            every { user.isBot } returns false
            val author = mockk<User>()
            every { author.isBot } returns false

            val messageId = Snowflake(8888L)
            val message = mockk<Message>()
            every { message.id } returns messageId
            every { message.author } returns author
            every { message.content } returns "Some text"

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } returns SummaryResult.Error.ApiError(500, "Gemini internal error")

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns messageId
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns message

            // Should not throw
            service.handleReaction(event)

            assertEquals(0, repliesSent)
            // Error is evicted from cache to allow subsequent retry
            assertEquals(null, service.getStatus(messageId))

            // Confirm subsequent reaction retries
            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } returns SummaryResult.Success("TL;DR: Retry succeeded")
            service.handleReaction(event)
            assertEquals(1, repliesSent)
            assertEquals(SummaryStatus.DONE, service.getStatus(messageId))
        }

    private fun createMockMessage(
        id: Long,
        username: String,
        content: String,
        isBot: Boolean = false,
        timestamp: Instant = Instant.fromEpochMilliseconds(id * 1000),
        type: MessageType = MessageType.Default,
        stickers: List<StickerItem> = emptyList(),
        attachments: Set<Attachment> = emptySet(),
        webhookId: Snowflake? = null,
        embeds: List<dev.kord.core.entity.Embed> = emptyList(),
    ): Message =
        SummaryTestFixtures.createMockMessage(
            id = id,
            username = username,
            content = content,
            isBot = isBot,
            timestamp = timestamp,
            type = type,
            stickers = stickers,
            attachments = attachments,
            webhookId = webhookId,
            embeds = embeds,
        )

    @Test
    fun `falls back gracefully to single message when historyFetcher throws exception`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val targetMsg = createMockMessage(901L, "charlie", "Target message content")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> throw RuntimeException("Discord API 403 Forbidden") },
                        ),
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            // Graceful fallback to single message
            val content = checkNotNull(capturedInput?.content)
            assertTrue(content.contains("Target message content"))
        }

    @Test
    fun `detects and resolves URL present in an earlier message of conversation slice via peekResolver`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var resolvedUrl: String? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1001L, "alice", "Check this out https://reddit.com/r/kotlin/comments/123/post", timestamp = baseTime)
            val targetMsg = createMockMessage(1002L, "bob", "What do you think of this?", timestamp = baseTime + 2.minutes)

            val mockPeekPost =
                PeekPost(
                    platform = Platform.REDDIT,
                    id = "post-100",
                    originalUrl = "https://reddit.com/r/kotlin/comments/123/post",
                    author = Author(username = "kotlindev"),
                    title = "Exciting Kotlin Features",
                    content = "Kotlin 2.0 brings K2 compiler.",
                    comments = listOf(Comment(author = "reviewer", content = "Looks great")),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(msg1) },
                            peekResolver = { url ->
                                resolvedUrl = url
                                mockPeekPost
                            },
                        ),
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            assertEquals("https://reddit.com/r/kotlin/comments/123/post", resolvedUrl)
            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertEquals("Exciting Kotlin Features", input.title)
            assertEquals("https://reddit.com/r/kotlin/comments/123/post", input.url)
        }

    @Test
    fun `includes extracted title, content, and comments from link alongside chat dialogue in SummaryInput`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1101L, "alice", "Interesting article: https://example.com/guide", timestamp = baseTime)
            val targetMsg = createMockMessage(1102L, "bob", "Let's summarize this discussion", timestamp = baseTime + 3.minutes)

            val mockPeekPost =
                PeekPost(
                    platform = Platform.GENERIC,
                    id = "guide-1",
                    originalUrl = "https://example.com/guide",
                    author = Author(username = "editor"),
                    title = "Comprehensive Guide to Kotlin Flows",
                    content = "Flows are cold asynchronous data streams in Kotlin Coroutines.",
                    comments =
                        listOf(
                            Comment(author = "reader1", content = "Super clear explanation!"),
                            Comment(author = "reader2", content = "Helped me understand backpressure."),
                        ),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(msg1) },
                            peekResolver = { mockPeekPost },
                        ),
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertEquals("Comprehensive Guide to Kotlin Flows", input.title)
            assertEquals("https://example.com/guide", input.url)
            assertEquals(
                listOf("Super clear explanation!", "Helped me understand backpressure."),
                input.comments,
            )
            // Dialogue context is preserved
            assertTrue(input.content.contains("[alice]: Interesting article: https://example.com/guide"))
            assertTrue(input.content.contains("[bob]: Let's summarize this discussion"))
            // Resolved article content is preserved
            assertTrue(input.content.contains("Flows are cold asynchronous data streams in Kotlin Coroutines."))
        }

    @Test
    fun `gracefully falls back to raw conversation dialogue when URL cannot be resolved`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1201L, "alice", "Broken link here: https://unreachable.example.com/404", timestamp = baseTime)
            val targetMsg = createMockMessage(1202L, "bob", "Yeah that link is broken", timestamp = baseTime + 2.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(msg1) },
                            peekResolver = { null },
                        ),
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertEquals(null, input.title)
            assertEquals(emptyList(), input.comments)
            assertEquals("https://unreachable.example.com/404", input.url)
            val expectedDialogue =
                """
                [alice]: Broken link here: https://unreachable.example.com/404
                [bob]: Yeah that link is broken
                """.trimIndent()
            assertTrue(input.content.contains("[alice]: Broken link here: https://unreachable.example.com/404"))
            assertTrue(input.content.contains("[bob]: Yeah that link is broken"))
        }

    @Test
    fun `identifies most recent URL in conversation slice when multiple messages contain URLs`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var resolvedUrl: String? = null
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1301L, "alice", "Old topic link: https://example.com/first", timestamp = baseTime)
            val msg2 = createMockMessage(1302L, "bob", "No, look at this one instead: https://example.com/second", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(1303L, "charlie", "Let's summarize this", timestamp = baseTime + 4.minutes)

            val mockPeekPost =
                PeekPost(
                    platform = Platform.GENERIC,
                    id = "second-1",
                    originalUrl = "https://example.com/second",
                    author = Author(username = "bob"),
                    title = "Second Link Title",
                    content = "Second Link Content",
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(msg1, msg2) },
                            peekResolver = { url ->
                                resolvedUrl = url
                                mockPeekPost
                            },
                        ),
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            assertEquals("https://example.com/second", resolvedUrl)
            assertEquals("https://example.com/second", capturedInput?.url)
            assertEquals("Second Link Title", capturedInput?.title)
        }

    @Test
    fun `ignores reaction when target message is a slash command or noise`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            val service = SummaryService(summarizer = summarizer)

            val user = mockk<User>()
            every { user.isBot } returns false

            val cmdMsg = createMockMessage(1601L, "alice", "/shorten https://example.com")
            val event1 = mockk<ReactionAddEvent>()
            every { event1.emoji } returns ReactionEmoji.Unicode("📝")
            every { event1.userId } returns Snowflake(55L)
            every { event1.messageId } returns cmdMsg.id
            coEvery { event1.getUserOrNull() } returns user
            coEvery { event1.getMessageOrNull() } returns cmdMsg

            service.handleReaction(event1)
            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>(), any()) }

            val emojiMsg = createMockMessage(1602L, "bob", "<:custom_pepe:123456789>")
            val event2 = mockk<ReactionAddEvent>()
            every { event2.emoji } returns ReactionEmoji.Unicode("📝")
            every { event2.userId } returns Snowflake(56L)
            every { event2.messageId } returns emojiMsg.id
            coEvery { event2.getUserOrNull() } returns user
            coEvery { event2.getMessageOrNull() } returns emojiMsg

            service.handleReaction(event2)
            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>(), any()) }
        }

    @Test
    fun `complete flow multi-participant dialogue produces structured summary with viewpoints and replies to Discord`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var replyCaptured: String? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1701L, "alice", "我們是否應該改用 PostgreSQL？", timestamp = baseTime)
            val msg2 = createMockMessage(1702L, "bob", "PostgreSQL 的 JSON 支援更完整，我贊成更換。", timestamp = baseTime + 1.minutes)
            val msg3 = createMockMessage(1703L, "charlie", "MySQL 目前維運穩定，更換會有停機風險，建議保留。", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(1704L, "alice", "那我們先在測試環境做 benchmark 再做決定。", timestamp = baseTime + 3.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector = ConversationSliceCollector(historyFetcher = { _, _ -> listOf(msg1, msg2, msg3) }),
                    replySender = { _, content -> replyCaptured = content },
                )

            val structuredSummary =
                """
                TL;DR:
                團隊討論是否將資料庫改為 PostgreSQL，最終共識為先在測試環境進行效能測試再決定。

                核心重點:
                • 團隊針對資料庫選型展開技術評估。
                • 針對 PostgreSQL 功能優勢與現有系統維運成本進行權衡。
                • 決議建立測試環境進行基準測試，避免未經評估直接遷移。

                參與者觀點:
                • bob: 肯定 PostgreSQL 的 JSON 支援能力，主張更換。
                • charlie: 考量維運穩定度與停機風險，建議維持現狀。
                • alice: 提出折衷方案，先做 benchmark 作為決策依據。
                """.trimIndent()

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success(structuredSummary)
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            val expectedContent =
                """
                [alice]: 我們是否應該改用 PostgreSQL？
                [bob]: PostgreSQL 的 JSON 支援更完整，我贊成更換。
                [charlie]: MySQL 目前維運穩定，更換會有停機風險，建議保留。
                [alice]: 那我們先在測試環境做 benchmark 再做決定。
                """.trimIndent()
            assertTrue(input.content.contains("PostgreSQL 的 JSON 支援更完整") || input.content.contains("附帶圖片"))

            // Verify prompt builder correctly formats this dialogue input
            val builtPrompt = GeminiPromptBuilder.buildUserPrompt(input)
            assertTrue(builtPrompt.contains(expectedContent))

            // Verify Discord reply contains all structured sections without emojis
            val reply = replyCaptured
            kotlin.test.assertNotNull(reply)
            assertTrue(reply.contains("TL;DR:"))
            assertTrue(reply.contains("核心重點:"))
            assertTrue(reply.contains("參與者觀點:"))
            assertFalse(EmojiSanitizer.containsEmoji(reply))
        }

    @Test
    fun `complete flow multi-participant dialogue discussing peeked link produces full context and replies`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var replyCaptured: String? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1801L, "alice", "大家看看這篇報導 https://news.example.com/ai-update", timestamp = baseTime)
            val msg2 = createMockMessage(1802L, "bob", "模型參數量看起來提升很多", timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(1803L, "charlie", "幫忙總結一下重點與大家看法", timestamp = baseTime + 2.minutes)

            val mockPeekPost =
                PeekPost(
                    platform = Platform.GENERIC,
                    id = "news-1",
                    originalUrl = "https://news.example.com/ai-update",
                    author = Author(username = "reporter"),
                    title = "AI Architecture Evolution in 2026",
                    content = "Detailed analysis of new AI models and reasoning capabilities.",
                    comments = listOf(Comment(author = "commenter1", content = "Impressive benchmark results")),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(msg1, msg2) },
                            peekResolver = { mockPeekPost },
                        ),
                    replySender = { _, content -> replyCaptured = content },
                )

            val mockResponse =
                """
                TL;DR:
                報導介紹 2026 年新一代 AI 架構，團隊成員探討模型規模並由共識總結。

                核心重點:
                • 新架構顯著提升推理能力與參數量。
                • 基準測試表現亮眼。

                參與者觀點:
                • bob: 注意到參數量大幅成長。
                • charlie: 請求統整核心資訊與團隊討論。
                """.trimIndent()

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success(mockResponse)
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertEquals("AI Architecture Evolution in 2026", input.title)
            assertEquals("https://news.example.com/ai-update", input.url)
            assertEquals(listOf("Impressive benchmark results"), input.comments)
            assertTrue(input.content.contains("[alice]: 大家看看這篇報導 https://news.example.com/ai-update"))
            assertTrue(input.content.contains("[bob]: 模型參數量看起來提升很多"))
            assertTrue(input.content.contains("[charlie]: 幫忙總結一下重點與大家看法"))
            assertTrue(input.content.contains("Detailed analysis of new AI models and reasoning capabilities."))

            val request = GeminiPromptBuilder.buildRequest(input)
            val userText =
                request.contents
                    .first()
                    .parts
                    .first()
                    .text
            kotlin.test.assertNotNull(userText)
            assertTrue(userText.contains("標題：AI Architecture Evolution in 2026"))
            assertTrue(userText.contains("來源網址：https://news.example.com/ai-update"))
            assertTrue(userText.contains("社群留言與討論："))
            assertTrue(userText.contains("1. Impressive benchmark results"))

            val reply = replyCaptured
            kotlin.test.assertNotNull(reply)
            assertTrue(reply.contains("TL;DR:"))
            assertTrue(reply.contains("核心重點:"))
            assertTrue(reply.contains("參與者觀點:"))
            assertFalse(EmojiSanitizer.containsEmoji(reply))
        }

    @Test
    fun `Summary Reaction on YouTube URL with creator description, title, comments verifies SummaryInput and replies`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var sentReply: String? = null

            val targetMsg =
                createMockMessage(
                    3001L,
                    "alice",
                    "Check out this video: https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                )

            val sanitizedDescription =
                """
                00:00 - Introduction to Kotlin Coroutines
                02:15 - Dispatchers and Structured Concurrency
                05:30 - Flow and StateFlow Live Demo
                08:45 - Key Takeaways and Summary
                """.trimIndent()

            val youtubePost =
                PeekPost(
                    platform = Platform.YOUTUBE,
                    id = "dQw4w9WgXcQ",
                    originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    cleanUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    author = Author(username = "KotlinDev", displayName = "Kotlin Developer"),
                    title = "Kotlin Coroutines Deep Dive",
                    content = sanitizedDescription,
                    comments =
                        listOf(
                            Comment(author = "Viewer1", content = "Crystal clear explanation at 02:15!"),
                            Comment(author = "Viewer2", content = "StateFlow vs SharedFlow was awesome"),
                        ),
                    rawData =
                        mapOf(
                            "durationSeconds" to "600",
                            "description" to sanitizedDescription,
                        ),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector = ConversationSliceCollector(peekResolver = { youtubePost }),
                    replySender = { _, content -> sentReply = content },
                )

            val formattedReply =
                """
                TL;DR:
                本影片深度剖析 Kotlin 協程的核心架構與實務應用，解說調度器運作與結構化併發機制。

                核心重點:
                • [00:00] 介紹協程基本概念與輕量執行緒優勢。
                • [02:15] 詳細解析 Dispatchers 與結構化併發模型。
                • [05:30] 實機展示 Flow 與 Channels 異步資料流處理。
                • [08:45] 總結常見開發陷阱與最佳實踐準則。

                社群觀點:
                • 觀眾高度肯定 02:30 調度器與併發架構的清晰解說。
                • 期待後續推出更多進階教學內容。
                """.trimIndent()

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success(formattedReply)
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", input.videoUri)
            assertEquals(600L, input.videoDurationSeconds)
            assertEquals(
                listOf("Crystal clear explanation at 02:15!", "StateFlow vs SharedFlow was awesome"),
                input.comments,
            )
            assertEquals("Kotlin Coroutines Deep Dive", input.title)
            assertTrue(input.content.contains(sanitizedDescription))
            assertTrue(input.content.contains("Check out this video:"))

            kotlin.test.assertNotNull(sentReply)
            assertEquals(formattedReply, sentReply)
            assertFalse(EmojiSanitizer.containsEmoji(sentReply))
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `Summary Reaction on a YouTube URL without comments or description succeeds cleanly with video substance and proper prompt structure`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var sentReply: String? = null

            val targetMsg =
                createMockMessage(
                    3004L,
                    "bob",
                    "https://www.youtube.com/watch?v=cleanVideo123",
                )

            val youtubePost =
                PeekPost(
                    platform = Platform.YOUTUBE,
                    id = "cleanVideo123",
                    originalUrl = "https://www.youtube.com/watch?v=cleanVideo123",
                    cleanUrl = "https://www.youtube.com/watch?v=cleanVideo123",
                    author = Author(username = "SoloCreator", displayName = "Solo Creator"),
                    title = "Solo Software Architecture Walkthrough",
                    content = "", // No description provided
                    comments = emptyList(), // No comments
                    rawData = mapOf("durationSeconds" to "180"),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector = ConversationSliceCollector(peekResolver = { youtubePost }),
                    replySender = { _, content -> sentReply = content },
                )

            val videoSubstanceReply =
                """
                TL;DR:
                作者展示並逐步解說單人軟體架構設計與模組拆分策略。

                核心重點:
                • [00:30] 展示系統整體架構圖與模組相依性規劃。
                • [01:45] 逐步示範核心模組介面隔離與深層模組設計原則。
                • [02:40] 總結單人開發之程式庫組織策略與維護考量。
                """.trimIndent()

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success(videoSubstanceReply)
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertEquals("https://www.youtube.com/watch?v=cleanVideo123", input.videoUri)
            assertEquals(180L, input.videoDurationSeconds)
            assertTrue(input.comments.isEmpty())
            assertEquals("Solo Software Architecture Walkthrough", input.title)
            assertTrue(input.content.isNotBlank())

            kotlin.test.assertNotNull(sentReply)
            assertEquals(videoSubstanceReply, sentReply)
            assertFalse(sentReply.contains("社群觀點:"), "Reply must omit community perspectives section")
            assertFalse(EmojiSanitizer.containsEmoji(sentReply))
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `SummaryService properly extracts and propagates YouTube video metadata without regressing text input`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var lastCapturedInput: SummaryInput? = null

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            peekResolver = { url ->
                                if (url.contains("youtube.com")) {
                                    PeekPost(
                                        platform = Platform.YOUTUBE,
                                        id = "ytTest1",
                                        originalUrl = url,
                                        cleanUrl = url,
                                        author = Author(username = "DevChannel"),
                                        title = "Video Tech Spec",
                                        content = "00:00 Spec Overview\n03:00 Code Implementation",
                                        comments = listOf(Comment(author = "dev", content = "LGTM")),
                                        rawData = mapOf("durationSeconds" to "240"),
                                    )
                                } else {
                                    null
                                }
                            },
                        ),
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                lastCapturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary text\n\n核心重點:\n• Point")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            // 1. YouTube summary interaction
            val ytMsg = createMockMessage(3005L, "alice", "https://www.youtube.com/watch?v=ytTest1")
            val ytEvent = mockk<ReactionAddEvent>()
            every { ytEvent.emoji } returns ReactionEmoji.Unicode("📝")
            every { ytEvent.userId } returns Snowflake(55L)
            every { ytEvent.messageId } returns ytMsg.id
            coEvery { ytEvent.getUserOrNull() } returns user
            coEvery { ytEvent.getMessageOrNull() } returns ytMsg

            service.handleReaction(ytEvent)

            val ytInput = lastCapturedInput
            kotlin.test.assertNotNull(ytInput)
            assertEquals("https://www.youtube.com/watch?v=ytTest1", ytInput.videoUri)
            assertEquals(240L, ytInput.videoDurationSeconds)
            assertEquals("Video Tech Spec", ytInput.title)
            assertEquals(listOf("LGTM"), ytInput.comments)

            // 2. Text conversation summary interaction (regression prevention)
            val textMsg1 = createMockMessage(3006L, "bob", "We should decide on the schema")
            val textMsg2 = createMockMessage(3007L, "charlie", "I prefer using PostgreSQL JSONB")
            val textService =
                SummaryService(
                    summarizer = summarizer,
                    collector = ConversationSliceCollector(historyFetcher = { _, _ -> listOf(textMsg1) }),
                    replySender = { _, _ -> },
                )

            val textEvent = mockk<ReactionAddEvent>()
            every { textEvent.emoji } returns ReactionEmoji.Unicode("📝")
            every { textEvent.userId } returns Snowflake(56L)
            every { textEvent.messageId } returns textMsg2.id
            coEvery { textEvent.getUserOrNull() } returns user
            coEvery { textEvent.getMessageOrNull() } returns textMsg2

            textService.handleReaction(textEvent)

            val textInput = lastCapturedInput
            kotlin.test.assertNotNull(textInput)
            assertNull(textInput.videoUri)
            assertNull(textInput.videoDurationSeconds)
            assertTrue(textInput.comments.isEmpty())
            assertTrue(textInput.content.contains("We should decide on the schema"))
            assertTrue(textInput.content.contains("I prefer using PostgreSQL JSONB"))
        }

    @Test
    fun `Summary Reaction on YouTube URL exceeding 120 minutes replies with warning and does not call summarizer`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null

            val targetMsg =
                createMockMessage(
                    3002L,
                    "alice",
                    "https://www.youtube.com/watch?v=ultraLongStream",
                )

            val longVideoPost =
                PeekPost(
                    platform = Platform.YOUTUBE,
                    id = "ultraLongStream",
                    originalUrl = "https://www.youtube.com/watch?v=ultraLongStream",
                    cleanUrl = "https://www.youtube.com/watch?v=ultraLongStream",
                    author = Author(username = "Streamer", displayName = "Streamer"),
                    title = "5-Hour Marathon Stream",
                    content = "5-Hour Marathon Stream",
                    rawData = mapOf("durationSeconds" to "18000"), // 5 hours = 18000s > 7200s
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector = ConversationSliceCollector(peekResolver = { longVideoPost }),
                    replySender = { _, content -> sentReply = content },
                )

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>(), any()) }
            kotlin.test.assertNotNull(sentReply)
            assertTrue(sentReply.contains("超過 2 小時上限"))
        }

    @Test
    fun `Summary Reaction sends friendly fallback message when Gemini returns blank or whitespace text`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var sentReply: String? = null

            val targetMsg =
                createMockMessage(
                    3003L,
                    "alice",
                    "Some short video or text",
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, content -> sentReply = content },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } returns SummaryResult.Success("")

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            kotlin.test.assertNotNull(sentReply)
            assertTrue(sentReply.isNotBlank(), "Sent reply must not be empty or blank")
            assertTrue(sentReply.contains("無法為此內容生成有效摘要"))
        }

    private fun createMockAttachment(
        url: String,
        contentType: String?,
        filename: String = "file",
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
    fun `Summary Reaction on target message with image attachments extracts images and passes to SummaryInput`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val imgAttachment1 =
                createMockAttachment(
                    url = "https://cdn.discordapp.com/attachments/123/456/photo.png",
                    contentType = "image/png",
                    filename = "photo.png",
                )
            val imgAttachment2 =
                createMockAttachment(
                    url = "https://cdn.discordapp.com/attachments/123/789/diagram.jpg",
                    contentType = "image/jpeg",
                    filename = "diagram.jpg",
                )

            val targetMsg =
                createMockMessage(
                    id = 4001L,
                    username = "alice",
                    content = "Check out these two screenshots",
                    attachments = setOf(imgAttachment1, imgAttachment2),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary of images")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertTrue(input.content.contains("[alice]: Check out these two screenshots"))
            assertTrue(input.content.contains("[附帶圖片 1: photo.png]"))
            assertTrue(input.content.contains("[附帶圖片 2: diagram.jpg]"))
            assertEquals(2, input.imageAttachments.size)
            assertEquals("https://cdn.discordapp.com/attachments/123/456/photo.png", input.imageAttachments[0].url)
            assertEquals("image/png", input.imageAttachments[0].mimeType)
            assertEquals("photo.png", input.imageAttachments[0].filename)
            assertEquals("https://cdn.discordapp.com/attachments/123/789/diagram.jpg", input.imageAttachments[1].url)
            assertEquals("image/jpeg", input.imageAttachments[1].mimeType)
            assertEquals("diagram.jpg", input.imageAttachments[1].filename)
        }

    @Test
    fun `Summary Reaction on target message filters out non-image attachments like pdf, zip, txt`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val validImage =
                createMockAttachment(
                    url = "https://cdn.discordapp.com/attachments/123/111/screen.webp",
                    contentType = "image/webp",
                    filename = "screen.webp",
                )
            val pdfAttachment =
                createMockAttachment(
                    url = "https://cdn.discordapp.com/attachments/123/222/document.pdf",
                    contentType = "application/pdf",
                    filename = "document.pdf",
                )
            val zipAttachment =
                createMockAttachment(
                    url = "https://cdn.discordapp.com/attachments/123/333/archive.zip",
                    contentType = "application/zip",
                    filename = "archive.zip",
                )
            val textAttachment =
                createMockAttachment(
                    url = "https://cdn.discordapp.com/attachments/123/444/log.txt",
                    contentType = "text/plain",
                    filename = "log.txt",
                )
            val gifImage =
                createMockAttachment(
                    url = "https://cdn.discordapp.com/attachments/123/555/animation.gif",
                    contentType = "image/gif",
                    filename = "animation.gif",
                )

            val targetMsg =
                createMockMessage(
                    id = 4002L,
                    username = "bob",
                    content = "Here are the files and screenshots",
                    attachments = setOf(validImage, pdfAttachment, zipAttachment, textAttachment, gifImage),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary of mixed attachments")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            val input = capturedInput
            kotlin.test.assertNotNull(input)
            // Only image/webp and image/gif should be retained
            assertEquals(2, input.imageAttachments.size)
            assertEquals("https://cdn.discordapp.com/attachments/123/111/screen.webp", input.imageAttachments[0].url)
            assertEquals("image/webp", input.imageAttachments[0].mimeType)
            assertEquals("https://cdn.discordapp.com/attachments/123/555/animation.gif", input.imageAttachments[1].url)
            assertEquals("image/gif", input.imageAttachments[1].mimeType)
        }

    @Test
    fun `Summary Reaction on target message with image attachment and empty text still passes image to SummaryInput`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val imgAttachment =
                createMockAttachment(
                    url = "https://cdn.discordapp.com/attachments/123/999/isolated.png",
                    contentType = "image/png",
                    filename = "isolated.png",
                )

            val targetMsg =
                createMockMessage(
                    id = 4003L,
                    username = "charlie",
                    content = "",
                    attachments = setOf(imgAttachment),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Visual summary")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            val input = capturedInput
            kotlin.test.assertNotNull(input)
            assertEquals(1, input.imageAttachments.size)
            assertEquals("https://cdn.discordapp.com/attachments/123/999/isolated.png", input.imageAttachments[0].url)
            assertEquals("image/png", input.imageAttachments[0].mimeType)
        }

    @Test
    fun `gracefully falls back to text-only summarization when multimodal summary with images fails`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var replyCaptured: String? = null
            val capturedInputs = mutableListOf<SummaryInput>()

            val img = createMockAttachment("https://cdn.discordapp.com/attachments/1/photo.png", "image/png", "photo.png")
            val targetMsg =
                createMockMessage(
                    id = 6201L,
                    username = "alice",
                    content = "Discussion with image attachment",
                    attachments = setOf(img),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, content -> replyCaptured = content },
                )

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInputs.add(firstArg())
                SummaryResult.Success("TL;DR: Image discussion summary succeeded")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }

            assertEquals("TL;DR: Image discussion summary succeeded", replyCaptured)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))

            val input = capturedInputs.firstOrNull()
            kotlin.test.assertNotNull(input)
            assertEquals(1, input.imageAttachments.size)
            assertEquals("https://cdn.discordapp.com/attachments/1/photo.png", input.imageAttachments.first().url)
        }

    @Test
    fun `handles summarizer error gracefully without crashing and evicts from status cache`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            val img = createMockAttachment("https://cdn.discordapp.com/attachments/1/photo.png", "image/png", "photo.png")
            val targetMsg = createMockMessage(id = 7101L, username = "alice", content = "Test message", attachments = setOf(img))

            val service = SummaryService(summarizer = summarizer, replySender = { _, _ -> })

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } returns SummaryResult.Error.Generic("Text summary failed")

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            kotlin.test.assertNull(service.getStatus(targetMsg.id))
        }
}
