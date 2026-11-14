package luna.summarizer

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
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
import luna.core.ai.GroundingCitation
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryServiceTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
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
        attachments: Set<Attachment> = emptySet(),
    ): Message =
        SummaryTestFixtures.createMockMessage(
            id = id,
            username = username,
            content = content,
            attachments = attachments,
        )

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
    fun `buildReply configures suppressEmbeds true and repliedUser false`() {
        val service = SummaryService()
        val message = createMockMessage(id = 8001L, username = "alice", content = "Message needing summary")
        val builder = service.buildReply(message, "Formatted summary text")

        assertEquals(message.id, builder.messageReference)
        assertEquals("Formatted summary text", builder.content)
        assertEquals(true, builder.suppressEmbeds)
        assertEquals(false, builder.allowedMentions?.repliedUser)
    }

    @Test
    fun `summary delivery appends search grounding citations to Discord reply with angle brackets`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var replyCaptured: String? = null

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, content -> replyCaptured = content },
                )

            val targetMsg = createMockMessage(id = 8002L, username = "bob", content = "What is Kotlin K2?")
            val citations =
                listOf(
                    GroundingCitation(title = "Kotlin 2.0 Released", url = "https://kotlinlang.org/k2"),
                    GroundingCitation(title = "JetBrains Blog", url = "https://blog.jetbrains.com/kotlin"),
                )

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } returns
                SummaryResult.Success(
                    text = "TL;DR: Kotlin 2.0 introduces K2 compiler.\n\n核心重點:\n• Faster compilation",
                    citations = citations,
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

            val reply = replyCaptured
            assertNotNull(reply)
            assertContains(reply, "TL;DR: Kotlin 2.0 introduces K2 compiler.")
            assertContains(reply, "核心重點:")
            assertContains(reply, "[Kotlin 2.0 Released](<https://kotlinlang.org/k2>)")
            assertContains(reply, "[JetBrains Blog](<https://blog.jetbrains.com/kotlin>)")
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `summary delivery with citations exceeding 2000 characters is cleanly truncated with ellipsis`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var replyCaptured: String? = null

            val service =
                SummaryService(
                    summarizer = summarizer,
                    replySender = { _, content -> replyCaptured = content },
                )

            val targetMsg = createMockMessage(id = 8003L, username = "charlie", content = "Explain quantum computing")
            val longSummary = "Q".repeat(1950)
            val citations =
                listOf(
                    GroundingCitation(title = "Quantum Computing IBM", url = "https://ibm.com/quantum"),
                    GroundingCitation(title = "Google Quantum AI", url = "https://quantumai.google"),
                )

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } returns
                SummaryResult.Success(
                    text = longSummary,
                    citations = citations,
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

            val reply = replyCaptured
            assertNotNull(reply)
            assertEquals(2000, reply.length)
            assertTrue(reply.endsWith("..."))
        }

    @Test
    fun `default replySender registers created reply message ID into SummaryMessageRegistry`() =
        runTest {
            val registry = luna.core.BoundedSummaryRegistry()
            val replyMessage = mockk<Message>()
            val replyId = Snowflake(998877L)
            every { replyMessage.id } returns replyId

            val originalMessage = mockk<Message>()
            every { originalMessage.id } returns Snowflake(112233L)

            SummaryService.defaultReplySender(
                message = originalMessage,
                content = "Summary content",
                summaryRegistry = registry,
                replyAction = { _, _ -> replyMessage },
            )

            assertTrue(registry.contains(replyId))
            assertEquals(1, registry.size)
        }
}
