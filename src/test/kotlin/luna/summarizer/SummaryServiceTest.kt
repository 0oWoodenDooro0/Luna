package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SummaryServiceTest {
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

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>()) }
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

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>()) }
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

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>()) }
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

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>()) }
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

            coEvery { summarizer.summarize(any<SummaryInput>()) } returns SummaryResult.Success("TL;DR: Summary")

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

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>()) }
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

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>()) }
            assertEquals("This is a plain text Discord discussion without any links.", capturedContent)
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
                    peekResolver = { mockPeekPost },
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

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>()) }
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
                    peekResolver = { null },
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

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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
            assertEquals("Here is an unsupported link: https://unsupported.example.com/item", input.content)
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
            coEvery { summarizer.summarize(any<SummaryInput>()) } returns SummaryResult.Success(longSummary)

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

            coEvery { summarizer.summarize(any<SummaryInput>()) } returns SummaryResult.Error.ApiError(500, "Gemini internal error")

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
            coEvery { summarizer.summarize(any<SummaryInput>()) } returns SummaryResult.Success("TL;DR: Retry succeeded")
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
    ): Message {
        val author = mockk<User>()
        every { author.username } returns username
        every { author.isBot } returns isBot

        val msg = mockk<Message>()
        every { msg.id } returns Snowflake(id)
        every { msg.author } returns author
        every { msg.content } returns content
        every { msg.timestamp } returns timestamp
        every { msg.type } returns type
        every { msg.stickers } returns stickers
        return msg
    }

    @Test
    fun `retrieves preceding messages and formats them chronologically with username prefix`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(101L, "alice", "Hello team", timestamp = baseTime)
            val msg2 = createMockMessage(102L, "bob", "Hi Alice, what's the plan?", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(103L, "charlie", "Let's summarize our progress", timestamp = baseTime + 5.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { msg, limit ->
                        assertEquals(targetMsg, msg)
                        assertEquals(20, limit)
                        listOf(msg1, msg2)
                    },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Progress summary")
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

            val expectedContent =
                """
                [alice]: Hello team
                [bob]: Hi Alice, what's the plan?
                [charlie]: Let's summarize our progress
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `stops backtracking and discards earlier messages when inactivity gap exceeds 15 minutes`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(201L, "alice", "Old topic from 10:00", timestamp = baseTime)
            val msg2 = createMockMessage(202L, "bob", "Also old topic from 10:05", timestamp = baseTime + 5.minutes)
            // 25 minutes gap between msg2 (10:05) and msg3 (10:30)
            val msg3 = createMockMessage(203L, "alice", "New topic starts at 10:30", timestamp = baseTime + 30.minutes)
            val targetMsg = createMockMessage(204L, "charlie", "Wrapping up new topic at 10:34", timestamp = baseTime + 34.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1, msg2, msg3) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            val expectedContent =
                """
                [alice]: New topic starts at 10:30
                [charlie]: Wrapping up new topic at 10:34
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `retains single message when immediate predecessor exceeds 15 minutes gap`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(301L, "alice", "Message from an hour ago", timestamp = baseTime)
            val targetMsg = createMockMessage(302L, "bob", "Isolated message", timestamp = baseTime + 60.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            // Should retain single message without [Username]: prefix
            assertEquals("Isolated message", capturedInput?.content)
        }

    @Test
    fun `stops backtracking and discards earlier messages when bot message is encountered`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(401L, "alice", "Discussion before previous summary", timestamp = baseTime)
            val botMsg = createMockMessage(402L, "LunaBot", "TL;DR: Previous summary", isBot = true, timestamp = baseTime + 2.minutes)
            val msg3 = createMockMessage(403L, "bob", "Discussion after previous summary", timestamp = baseTime + 4.minutes)
            val targetMsg = createMockMessage(404L, "charlie", "Current summary request", timestamp = baseTime + 6.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1, botMsg, msg3) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            val expectedContent =
                """
                [bob]: Discussion after previous summary
                [charlie]: Current summary request
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `retains single message when immediate predecessor is bot message`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val botMsg = createMockMessage(501L, "LunaBot", "Previous summary", isBot = true, timestamp = baseTime)
            val targetMsg = createMockMessage(502L, "charlie", "Stand-alone message after bot", timestamp = baseTime + 2.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(botMsg) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            assertEquals("Stand-alone message after bot", capturedInput?.content)
        }

    @Test
    fun `respects maximum limit of 20 preceding messages`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var requestedLimit: Int? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            // Create 25 preceding messages 1 minute apart
            val history =
                (1..25).map { index ->
                    createMockMessage(
                        id = 600L + index,
                        username = "user$index",
                        content = "Message $index",
                        timestamp = baseTime + index.minutes,
                    )
                }
            val targetMsg =
                createMockMessage(
                    id = 700L,
                    username = "targetUser",
                    content = "Target message",
                    timestamp = baseTime + 26.minutes,
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, limit ->
                        requestedLimit = limit
                        history
                    },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            assertEquals(20, requestedLimit)
            val lines = capturedInput?.content?.lines() ?: emptyList()
            // 20 preceding messages + 1 target message = 21 lines
            assertEquals(21, lines.size)
            // First included line should be user6 (25 - 20 + 1 = 6)
            assertEquals("[user6]: Message 6", lines.first())
            assertEquals("[targetUser]: Target message", lines.last())
        }

    @Test
    fun `handles history provided in reverse chronological order and normalizes it`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(801L, "alice", "First message", timestamp = baseTime)
            val msg2 = createMockMessage(802L, "bob", "Second message", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(803L, "charlie", "Third message", timestamp = baseTime + 4.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    // Provide in reverse chronological order (newest first)
                    historyFetcher = { _, _ -> listOf(msg2, msg1) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            val expectedContent =
                """
                [alice]: First message
                [bob]: Second message
                [charlie]: Third message
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `falls back gracefully to single message when historyFetcher throws exception`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val targetMsg = createMockMessage(901L, "charlie", "Target message content")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> throw RuntimeException("Discord API 403 Forbidden") },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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
            assertEquals("Target message content", capturedInput?.content)
        }

    @Test
    fun `excludes messages matching command patterns like slash commands from conversation slice`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1001L, "alice", "Let's review the proposal", timestamp = baseTime)
            val cmd1 = createMockMessage(1002L, "bob", "/shorten https://example.com/spec", timestamp = baseTime + 1.minutes)
            val cmd2 = createMockMessage(1003L, "charlie", "/poll create \"Meeting time\"", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(1004L, "dave", "I have added my notes", timestamp = baseTime + 3.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1, cmd1, cmd2) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Proposal review")
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

            val expectedContent =
                """
                [alice]: Let's review the proposal
                [dave]: I have added my notes
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `excludes messages consisting solely of stickers or custom graphic emojis without text`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1101L, "alice", "Check out the mockup", timestamp = baseTime)
            val emoji1 = createMockMessage(1102L, "bob", "<:custom_pepe:123456789>", timestamp = baseTime + 1.minutes)
            val emoji2 = createMockMessage(1103L, "charlie", "<a:party_blob:987654321>  <:blob2:111222>", timestamp = baseTime + 2.minutes)
            val stickerMsg =
                createMockMessage(
                    id = 1104L,
                    username = "dave",
                    content = "",
                    timestamp = baseTime + 3.minutes,
                    stickers = listOf(mockk()),
                )
            val targetMsg = createMockMessage(1105L, "eve", "I like the color scheme", timestamp = baseTime + 4.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1, emoji1, emoji2, stickerMsg) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Mockup discussion")
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

            val expectedContent =
                """
                [alice]: Check out the mockup
                [eve]: I like the color scheme
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `retains messages with custom emojis that also contain text`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1201L, "alice", "Awesome job! <:tada:12345>", timestamp = baseTime)
            val targetMsg = createMockMessage(1202L, "bob", "<a:fire:67890> Ready to merge", timestamp = baseTime + 1.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Release ready")
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

            val expectedContent =
                """
                [alice]: Awesome job! <:tada:12345>
                [bob]: <a:fire:67890> Ready to merge
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `excludes empty and blank messages from conversation history slice`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1301L, "alice", "First topic", timestamp = baseTime)
            val blankMsg = createMockMessage(1302L, "bob", "   \t\n  ", timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(1303L, "charlie", "Second topic", timestamp = baseTime + 2.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1, blankMsg) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            val expectedContent =
                """
                [alice]: First topic
                [charlie]: Second topic
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `excludes system messages from conversation history slice`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1401L, "alice", "Discussion in progress", timestamp = baseTime)
            val sysMsg =
                createMockMessage(
                    id = 1402L,
                    username = "system",
                    content = "Alice pinned a message to this channel.",
                    timestamp = baseTime + 1.minutes,
                    type = MessageType.ChannelPinnedMessage,
                )
            val targetMsg = createMockMessage(1403L, "bob", "Noted the pin", timestamp = baseTime + 2.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1, sysMsg) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Discussion")
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

            val expectedContent =
                """
                [alice]: Discussion in progress
                [bob]: Noted the pin
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `retains short human conversational turns such as +1, lol, agreed, no with username attribution`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(1501L, "alice", "Should we deploy v2 today?", timestamp = baseTime)
            val msg2 = createMockMessage(1502L, "bob", "+1", timestamp = baseTime + 1.minutes)
            val msg3 = createMockMessage(1503L, "charlie", "lol", timestamp = baseTime + 2.minutes)
            val msg4 = createMockMessage(1504L, "dave", "agreed", timestamp = baseTime + 3.minutes)
            val msg5 = createMockMessage(1505L, "eve", "no", timestamp = baseTime + 4.minutes)
            val targetMsg = createMockMessage(1506L, "frank", "Understood, postponing deploy", timestamp = baseTime + 5.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1, msg2, msg3, msg4, msg5) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Decision made")
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

            val expectedContent =
                """
                [alice]: Should we deploy v2 today?
                [bob]: +1
                [charlie]: lol
                [dave]: agreed
                [eve]: no
                [frank]: Understood, postponing deploy
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
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
            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>()) }

            val emojiMsg = createMockMessage(1602L, "bob", "<:custom_pepe:123456789>")
            val event2 = mockk<ReactionAddEvent>()
            every { event2.emoji } returns ReactionEmoji.Unicode("📝")
            every { event2.userId } returns Snowflake(56L)
            every { event2.messageId } returns emojiMsg.id
            coEvery { event2.getUserOrNull() } returns user
            coEvery { event2.getMessageOrNull() } returns emojiMsg

            service.handleReaction(event2)
            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>()) }
        }
}
