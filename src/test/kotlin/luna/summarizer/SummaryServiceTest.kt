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
        attachments: Set<Attachment> = emptySet(),
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
        )

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
                        assertEquals(50, limit)
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
    fun `respects default maximum limit of 50 preceding messages`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var requestedLimit: Int? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            // Create 55 preceding messages 1 minute apart
            val history =
                (1..55).map { index ->
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
                    timestamp = baseTime + 56.minutes,
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

            assertEquals(50, requestedLimit)
            val lines = capturedInput?.content?.lines() ?: emptyList()
            // 50 preceding messages + 1 target message = 51 lines
            assertEquals(51, lines.size)
            // First included line should be user6 (55 - 50 + 1 = 6)
            assertEquals("[user6]: Message 6", lines.first())
            assertEquals("[targetUser]: Target message", lines.last())
        }

    @Test
    fun `resolves configurable maxHistoryMessages from environment with bounds clamping`() {
        assertEquals(50, SummaryService.resolveMaxHistoryMessages(emptyMap()))
        assertEquals(25, SummaryService.resolveMaxHistoryMessages(mapOf("SUMMARY_MAX_HISTORY_MESSAGES" to "25")))
        assertEquals(100, SummaryService.resolveMaxHistoryMessages(mapOf("SUMMARY_MAX_HISTORY_MESSAGES" to "500")))
        assertEquals(1, SummaryService.resolveMaxHistoryMessages(mapOf("SUMMARY_MAX_HISTORY_MESSAGES" to "-5")))
        assertEquals(50, SummaryService.resolveMaxHistoryMessages(mapOf("SUMMARY_MAX_HISTORY_MESSAGES" to "invalid")))
    }

    @Test
    fun `resolves configurable inactivityGap from environment`() {
        assertEquals(15.minutes, SummaryService.resolveInactivityGap(emptyMap()))
        assertEquals(30.minutes, SummaryService.resolveInactivityGap(mapOf("SUMMARY_INACTIVITY_GAP_MINUTES" to "30")))
        assertEquals(15.minutes, SummaryService.resolveInactivityGap(mapOf("SUMMARY_INACTIVITY_GAP_MINUTES" to "-10")))
        assertEquals(15.minutes, SummaryService.resolveInactivityGap(mapOf("SUMMARY_INACTIVITY_GAP_MINUTES" to "not-a-number")))
    }

    @Test
    fun `respects custom inactivityGap allowing longer conversational pauses`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            // msg1 is 20 minutes before targetMsg (exceeds default 15m, but within custom 30m)
            val msg1 = createMockMessage(651L, "alice", "Deep discussion point", timestamp = baseTime)
            val targetMsg = createMockMessage(652L, "bob", "Follow up discussion", timestamp = baseTime + 20.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    inactivityGap = 30.minutes,
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

            val expectedContent =
                """
                [alice]: Deep discussion point
                [bob]: Follow up discussion
                """.trimIndent()
            assertEquals(expectedContent, capturedInput?.content)
        }

    @Test
    fun `respects maxCharacterBudget halting backtracking when accumulated characters exceed limit`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(661L, "alice", "A".repeat(300), timestamp = baseTime)
            val msg2 = createMockMessage(662L, "bob", "B".repeat(300), timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(663L, "charlie", "C".repeat(100), timestamp = baseTime + 2.minutes)

            // Budget of 500 characters: target is 100, msg2 adds 300 (total 400 <= 500), msg1 adds 300 (total 700 > 500 -> stops)
            val service =
                SummaryService(
                    summarizer = summarizer,
                    maxCharacterBudget = 500,
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
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

            val lines = capturedInput?.content?.lines() ?: emptyList()
            assertEquals(2, lines.size)
            assertEquals("[bob]: " + "B".repeat(300), lines.first())
            assertEquals("[charlie]: " + "C".repeat(100), lines.last())
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
                    historyFetcher = { _, _ -> listOf(msg1) },
                    peekResolver = { url ->
                        resolvedUrl = url
                        mockPeekPost
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
                    historyFetcher = { _, _ -> listOf(msg1) },
                    peekResolver = { mockPeekPost },
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
                    historyFetcher = { _, _ -> listOf(msg1) },
                    peekResolver = { null },
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
            assertEquals(expectedDialogue, input.content)
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
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                    peekResolver = { url ->
                        resolvedUrl = url
                        mockPeekPost
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

            assertEquals("https://example.com/second", resolvedUrl)
            assertEquals("https://example.com/second", capturedInput?.url)
            assertEquals("Second Link Title", capturedInput?.title)
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
                    historyFetcher = { _, _ -> listOf(msg1, msg2, msg3) },
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

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>()) }
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
            assertEquals(expectedContent, input.content)

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
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                    peekResolver = { mockPeekPost },
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

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
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
    fun `retains messages with blank text if attachments are present`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msgWithAttachment =
                createMockMessage(
                    id = 1901L,
                    username = "alice",
                    content = "",
                    timestamp = baseTime,
                    attachments = setOf(mockk()),
                )
            val targetMsg = createMockMessage(1902L, "bob", "Reviewed the attachment", timestamp = baseTime + 1.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msgWithAttachment) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Reviewed attachment")
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
            assertTrue(input.content.contains("[alice]: "))
            assertTrue(input.content.contains("[bob]: Reviewed the attachment"))
        }

    @Test
    fun `continues backtracking past third-party non-summary bot messages while filtering them from dialogue`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 = createMockMessage(2001L, "alice", "Initial discussion point", timestamp = baseTime)
            val musicBotMsg = createMockMessage(2002L, "MusicBot", "Now playing: Track 1", isBot = true, timestamp = baseTime + 1.minutes)
            val msg2 = createMockMessage(2003L, "bob", "Second discussion point", timestamp = baseTime + 2.minutes)
            val targetMsg = createMockMessage(2004L, "charlie", "Concluding remarks", timestamp = baseTime + 3.minutes)

            val service =
                SummaryService(
                    summarizer = summarizer,
                    historyFetcher = { _, _ -> listOf(msg1, musicBotMsg, msg2) },
                    replySender = { _, _ -> },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Discussion summary")
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
            // msg1 is retained because musicBot did not break history backtracking
            assertTrue(input.content.contains("[alice]: Initial discussion point"))
            // musicBot is excluded by noise filter
            assertFalse(input.content.contains("MusicBot"))
            assertFalse(input.content.contains("Now playing: Track 1"))
            // msg2 and targetMsg are retained
            assertTrue(input.content.contains("[bob]: Second discussion point"))
            assertTrue(input.content.contains("[charlie]: Concluding remarks"))
        }

    @Test
    fun `Summary Reaction on YouTube URL extracts videoUri, duration, and comments into SummaryInput and sends formatted reply`() =
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

            val youtubePost =
                PeekPost(
                    platform = Platform.YOUTUBE,
                    id = "dQw4w9WgXcQ",
                    originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    cleanUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    author = Author(username = "RickAstley", displayName = "Rick Astley"),
                    title = "Never Gonna Give You Up",
                    content = "Never Gonna Give You Up",
                    comments = listOf(Comment(author = "Viewer1", content = "Great music video!")),
                    rawData = mapOf("durationSeconds" to "213"),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    peekResolver = { youtubePost },
                    replySender = { _, content -> sentReply = content },
                )

            coEvery { summarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Summary of Rick Astley video\n\n核心重點:\n• Classic hit")
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
            assertEquals(213L, input.videoDurationSeconds)
            assertEquals(listOf("Great music video!"), input.comments)
            assertEquals("Never Gonna Give You Up", input.title)

            kotlin.test.assertNotNull(sentReply)
            assertTrue(sentReply.contains("TL;DR: Summary of Rick Astley video"))
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
                    peekResolver = { longVideoPost },
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

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>()) }
            kotlin.test.assertNotNull(sentReply)
            assertTrue(sentReply.contains("超過 2 小時上限"))
        }
}
