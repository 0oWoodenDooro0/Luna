package luna.summarizer

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
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
}
