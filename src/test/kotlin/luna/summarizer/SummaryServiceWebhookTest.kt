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

class SummaryServiceWebhookTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
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

    private fun createMockEmbed(
        title: String? = null,
        description: String? = null,
    ): dev.kord.core.entity.Embed = SummaryTestFixtures.createMockEmbed(title = title, description = description)

    private fun createMockAttachment(
        url: String,
        contentType: String?,
        filename: String = "file",
        size: Int = 1024,
    ): Attachment = SummaryTestFixtures.createMockAttachment(url, contentType, filename, size)

    @Test
    fun `reacting to a Luna Webhook Replacement message when author is null triggers summarization and formats username from data author`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val summarizer = mockk<GeminiSummarizer>()
            val post =
                PeekPost(
                    id = "post-webhook-null-author",
                    originalUrl = "https://example.com/post",
                    cleanUrl = "https://example.com/post",
                    platform = Platform.GENERIC,
                    title = "Sample Clean Post",
                    content = "Extracted article body",
                    author = Author(username = "author_name"),
                )
            val peekResolver: suspend (String) -> PeekPost? = { null }

            var repliedMessage: Message? = null
            var replyContent: String? = null
            val replySender: suspend (Message, String) -> Unit = { msg, text ->
                repliedMessage = msg
                replyContent = text
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            peekResolver = peekResolver,
                            noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                        ),
                    replySender = replySender,
                )

            val embed = SummaryTestFixtures.createMockEmbed(title = "Sample Clean Post", description = "Extracted article body")
            val targetMsg =
                SummaryTestFixtures.createMockMessage(
                    id = 8001L,
                    username = "MasqueradedAlice",
                    content = "Cleaned post link https://example.com/post",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    embeds = listOf(embed),
                    authorIsNull = true,
                )

            var capturedInput: SummaryInput? = null
            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("TL;DR summary of clean post")
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
            assertEquals(targetMsg, repliedMessage)
            assertEquals("TL;DR summary of clean post", replyContent)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
            val input = capturedInput
            assertNotNull(input)
            assertTrue(input.content.contains("[MasqueradedAlice]:"))
        }

    @Test
    fun `reacting to a Luna Webhook Replacement message triggers link extraction, Gemini summarizer, and message reply`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val summarizer = mockk<GeminiSummarizer>()
            val post =
                PeekPost(
                    id = "post-webhook-1",
                    originalUrl = "https://example.com/post",
                    cleanUrl = "https://example.com/post",
                    platform = Platform.GENERIC,
                    title = "Sample Clean Post",
                    content = "Extracted article body",
                    author = Author(username = "author_name"),
                )
            val peekResolver: suspend (String) -> PeekPost? = { post }

            var repliedMessage: Message? = null
            var replyContent: String? = null
            val replySender: suspend (Message, String) -> Unit = { msg, text ->
                repliedMessage = msg
                replyContent = text
            }

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            peekResolver = peekResolver,
                            noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                        ),
                    replySender = replySender,
                )

            val targetMsg =
                createMockMessage(
                    id = 8001L,
                    username = "MasqueradedAlice",
                    content = "Cleaned post link https://example.com/post",
                    isBot = true,
                    webhookId = lunaWebhookId,
                )

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } returns SummaryResult.Success("TL;DR summary of clean post")

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(match<SummaryInput> { it.url == "https://example.com/post" }, any()) }
            assertEquals(targetMsg, repliedMessage)
            assertEquals("TL;DR summary of clean post", replyContent)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `author attribution in dialogue log uses the masqueraded user's username`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("Slice summary")
            }

            val baseTime = Instant.fromEpochMilliseconds(1700000000000L)
            val webhookMsg =
                createMockMessage(
                    id = 8101L,
                    username = "MasqueradedBob",
                    content = "Interesting discussion context",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime,
                )
            val targetMsg =
                createMockMessage(
                    id = 8102L,
                    username = "Charlie",
                    content = "Agreed with the above",
                    isBot = false,
                    timestamp = baseTime + 1.minutes,
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(webhookMsg) },
                            noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                        ),
                    replySender = { _, _ -> },
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

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            assertNotNull(capturedInput)
            assertTrue(capturedInput.content.contains("[MasqueradedBob]: Interesting discussion context"))
            assertTrue(capturedInput.content.contains("[Charlie]: Agreed with the above"))
        }

    @Test
    fun `reacting to a third-party webhook message is rejected as noise and does not trigger summarization`() =
        runTest {
            val summarizer = mockk<GeminiSummarizer>()
            var replyCalled = false
            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == Snowflake(777L) }),
                        ),
                    replySender = { _, _ -> replyCalled = true },
                )

            val thirdPartyMsg =
                createMockMessage(
                    id = 8201L,
                    username = "GitHubNotifier",
                    content = "Commit pushed https://example.com/repo",
                    isBot = true,
                    webhookId = Snowflake(999L),
                )

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns thirdPartyMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns thirdPartyMsg

            service.handleReaction(event)

            coVerify(exactly = 0) { summarizer.summarize(any<SummaryInput>(), any()) }
            assertFalse(replyCalled)
            assertNull(service.getStatus(thirdPartyMsg.id))
        }

    @Test
    fun `in-memory caching works and does not make duplicate queries for the same webhook ID`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val queryCount = AtomicInteger(0)
            val summarizer = mockk<GeminiSummarizer>()
            coEvery { summarizer.summarize(any<SummaryInput>(), any()) } returns SummaryResult.Success("Summary")

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            noiseFilter =
                                DialogueNoiseFilter(
                                    isLunaWebhook = { id ->
                                        queryCount.incrementAndGet()
                                        id == lunaWebhookId
                                    },
                                ),
                        ),
                    replySender = { _, _ -> },
                )

            val msg1 =
                createMockMessage(
                    id = 8301L,
                    username = "Alice",
                    content = "Post 1 https://example.com/1",
                    isBot = true,
                    webhookId = lunaWebhookId,
                )
            val msg2 =
                createMockMessage(
                    id = 8302L,
                    username = "Alice",
                    content = "Post 2 https://example.com/2",
                    isBot = true,
                    webhookId = lunaWebhookId,
                )

            val user = mockk<User>()
            every { user.isBot } returns false

            val event1 = mockk<ReactionAddEvent>()
            every { event1.emoji } returns ReactionEmoji.Unicode("📝")
            every { event1.userId } returns Snowflake(55L)
            every { event1.messageId } returns msg1.id
            coEvery { event1.getUserOrNull() } returns user
            coEvery { event1.getMessageOrNull() } returns msg1

            val event2 = mockk<ReactionAddEvent>()
            every { event2.emoji } returns ReactionEmoji.Unicode("📝")
            every { event2.userId } returns Snowflake(55L)
            every { event2.messageId } returns msg2.id
            coEvery { event2.getUserOrNull() } returns user
            coEvery { event2.getMessageOrNull() } returns msg2

            service.handleReaction(event1)
            service.handleReaction(event2)

            assertEquals(1, queryCount.get())
        }

    @Test
    fun `multi-message conversation retains preceding Luna Webhook Replacement message with URL and formats transcript with author attribution`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var resolvedUrl: String? = null

            val post =
                PeekPost(
                    id = "post-article-1",
                    originalUrl = "https://example.com/luna-post",
                    cleanUrl = "https://example.com/luna-post",
                    platform = Platform.GENERIC,
                    title = "Deep Dive Article",
                    content = "Comprehensive guide on distributed systems and event ordering.",
                    author = Author(username = "lead_author"),
                    comments = listOf(Comment(author = "TechLead", content = "Great architectural breakdown.")),
                )
            val peekResolver: suspend (String) -> PeekPost? = { url ->
                resolvedUrl = url
                post
            }

            var repliedMessage: Message? = null
            var replyContent: String? = null
            val replySender: suspend (Message, String) -> Unit = { msg, text ->
                repliedMessage = msg
                replyContent = text
            }

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val webhookMsg =
                createMockMessage(
                    id = 9001L,
                    username = "OriginalAuthor",
                    content = "Here is the cleaned link https://example.com/luna-post",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime,
                )
            val userMsg1 =
                createMockMessage(
                    id = 9002L,
                    username = "Bob",
                    content = "This looks very informative!",
                    isBot = false,
                    timestamp = baseTime + 1.minutes,
                )
            val targetMsg =
                createMockMessage(
                    id = 9003L,
                    username = "Charlie",
                    content = "Could someone summarize the key takeaways?",
                    isBot = false,
                    timestamp = baseTime + 2.minutes,
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            peekResolver = peekResolver,
                            historyFetcher = { _, _ -> listOf(webhookMsg, userMsg1) },
                            noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                        ),
                    replySender = replySender,
                )

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("TL;DR: Summary of discussion and article")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            // Verify a: The Luna Webhook message is retained in precedingMessages
            val precedingMessages = service.collector.retrievePrecedingMessages(targetMsg)
            assertEquals(listOf(webhookMsg, userMsg1), precedingMessages)

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            val input = capturedInput
            assertNotNull(input)

            // Verify b: The dialogue transcript formatted for Gemini includes [OriginalAuthor]: ... with clean content
            assertTrue(input.content.contains("[OriginalAuthor]: Here is the cleaned link https://example.com/luna-post"))
            assertTrue(input.content.contains("[Bob]: This looks very informative!"))
            assertTrue(input.content.contains("[Charlie]: Could someone summarize the key takeaways?"))

            // Verify c: The primaryUrl is extracted from preceding Webhook Replacement message and passed to peekResolver
            assertEquals("https://example.com/luna-post", resolvedUrl)
            assertEquals("https://example.com/luna-post", input.url)

            // Verify d: Summarizer receives combined dialogue and post content
            assertTrue(input.content.contains("Comprehensive guide on distributed systems and event ordering."))
            assertEquals("Deep Dive Article", input.title)
            assertEquals(listOf("Great architectural breakdown."), input.comments)
            assertEquals(targetMsg, repliedMessage)
            assertEquals("TL;DR: Summary of discussion and article", replyContent)
            assertEquals(SummaryStatus.DONE, service.getStatus(targetMsg.id))
        }

    @Test
    fun `conversation slice history with mixed messages includes Luna Webhook and excludes third-party webhooks`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val thirdPartyWebhookId = Snowflake(999L)
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var resolvedUrl: String? = null

            val post =
                PeekPost(
                    id = "post-proposal-1",
                    originalUrl = "https://example.com/proposal",
                    cleanUrl = "https://example.com/proposal",
                    platform = Platform.GENERIC,
                    title = "Proposal v2",
                    content = "Detailed engineering proposal for review.",
                    author = Author(username = "bob"),
                )

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg1 =
                createMockMessage(
                    id = 9101L,
                    username = "Alice",
                    content = "Starting our daily sync",
                    isBot = false,
                    timestamp = baseTime,
                )
            val githubBot =
                createMockMessage(
                    id = 9102L,
                    username = "GitHub",
                    content = "[repo:main] 1 new commit pushed by octocat https://github.com/repo/commit/123",
                    isBot = true,
                    webhookId = thirdPartyWebhookId,
                    timestamp = baseTime + 1.minutes,
                )
            val lunaWebhook =
                createMockMessage(
                    id = 9103L,
                    username = "Bob",
                    content = "Shared the updated proposal https://example.com/proposal",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime + 2.minutes,
                )
            val msg2 =
                createMockMessage(
                    id = 9104L,
                    username = "Charlie",
                    content = "Reviewing the proposal now",
                    isBot = false,
                    timestamp = baseTime + 3.minutes,
                )
            val targetMsg =
                createMockMessage(
                    id = 9105L,
                    username = "Dave",
                    content = "Let's summarize our discussion points",
                    isBot = false,
                    timestamp = baseTime + 4.minutes,
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            peekResolver = { url ->
                                resolvedUrl = url
                                post
                            },
                            historyFetcher = { _, _ -> listOf(msg1, githubBot, lunaWebhook, msg2) },
                            noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                        ),
                    replySender = { _, _ -> },
                )

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("TL;DR: Proposal discussion summary")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            // Verify precedingMessages excludes third-party webhook and includes Luna webhook and humans
            val precedingMessages = service.collector.retrievePrecedingMessages(targetMsg)
            assertEquals(listOf(msg1, lunaWebhook, msg2), precedingMessages)
            assertFalse(precedingMessages.contains(githubBot))

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            val input = capturedInput
            assertNotNull(input)

            // Verify dialogue transcript contains human and Luna Webhook messages
            assertTrue(input.content.contains("[Alice]: Starting our daily sync"))
            assertTrue(input.content.contains("[Bob]: Shared the updated proposal https://example.com/proposal"))
            assertTrue(input.content.contains("[Charlie]: Reviewing the proposal now"))
            assertTrue(input.content.contains("[Dave]: Let's summarize our discussion points"))

            // Verify third-party webhook is completely omitted from the dialogue prompt
            assertFalse(input.content.contains("GitHub"))
            assertFalse(input.content.contains("[repo:main]"))
            assertFalse(input.content.contains("octocat"))
            assertFalse(input.content.contains("https://github.com/repo/commit/123"))

            // Verify URL picked up is from Luna Webhook, not third-party webhook
            assertEquals("https://example.com/proposal", resolvedUrl)
            assertEquals("https://example.com/proposal", input.url)
            assertTrue(input.content.contains("Detailed engineering proposal for review."))
        }

    @Test
    fun `history backtracking halts at prior bot summary message even when preceding messages include webhooks`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            var resolvedUrl: String? = null

            val post =
                PeekPost(
                    id = "post-new-1",
                    originalUrl = "https://example.com/new",
                    cleanUrl = "https://example.com/new",
                    platform = Platform.GENERIC,
                    title = "New Link Title",
                    content = "New Link Content",
                    author = Author(username = "bob"),
                )

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg0 =
                createMockMessage(
                    id = 9201L,
                    username = "Alice",
                    content = "Old discussion before previous summary",
                    isBot = false,
                    timestamp = baseTime,
                )
            val oldWebhook =
                createMockMessage(
                    id = 9202L,
                    username = "Bob",
                    content = "Old webhook link https://example.com/old",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime + 1.minutes,
                )
            val botSummary =
                createMockMessage(
                    id = 9203L,
                    username = "LunaBot",
                    content = "TL;DR: Earlier topic summarized",
                    isBot = true,
                    webhookId = null,
                    timestamp = baseTime + 2.minutes,
                )
            val lunaWebhook =
                createMockMessage(
                    id = 9204L,
                    username = "Bob",
                    content = "New discussion link https://example.com/new",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime + 4.minutes,
                )
            val msg1 =
                createMockMessage(
                    id = 9205L,
                    username = "Charlie",
                    content = "Discussing the new link",
                    isBot = false,
                    timestamp = baseTime + 5.minutes,
                )
            val targetMsg =
                createMockMessage(
                    id = 9206L,
                    username = "Dave",
                    content = "Summarize the new discussion",
                    isBot = false,
                    timestamp = baseTime + 6.minutes,
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            peekResolver = { url ->
                                resolvedUrl = url
                                post
                            },
                            historyFetcher = { _, _ -> listOf(msg0, oldWebhook, botSummary, lunaWebhook, msg1) },
                            noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                        ),
                    replySender = { _, _ -> },
                )

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("TL;DR: Summary of new topic")
            }

            val user = mockk<User>()
            every { user.isBot } returns false

            val event = mockk<ReactionAddEvent>()
            every { event.emoji } returns ReactionEmoji.Unicode("📝")
            every { event.userId } returns Snowflake(55L)
            every { event.messageId } returns targetMsg.id
            coEvery { event.getUserOrNull() } returns user
            coEvery { event.getMessageOrNull() } returns targetMsg

            // Verify history backtracking stopped at bot summary without crashing on webhook entries
            val precedingMessages = service.collector.retrievePrecedingMessages(targetMsg)
            assertEquals(listOf(lunaWebhook, msg1), precedingMessages)

            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            val input = capturedInput
            assertNotNull(input)

            // Verify dialogue includes only the slice after the prior summary
            assertTrue(input.content.contains("[Bob]: New discussion link https://example.com/new"))
            assertTrue(input.content.contains("[Charlie]: Discussing the new link"))
            assertTrue(input.content.contains("[Dave]: Summarize the new discussion"))

            // Verify prior messages and summary are omitted
            assertFalse(input.content.contains("Old discussion before previous summary"))
            assertFalse(input.content.contains("Old webhook link"))
            assertFalse(input.content.contains("https://example.com/old"))
            assertFalse(input.content.contains("TL;DR: Earlier topic summarized"))

            assertEquals("https://example.com/new", resolvedUrl)
            assertEquals("https://example.com/new", input.url)
        }

    @Test
    fun `history backtracking does not falsely halt on Luna Webhook Replacement message starting with TL DR`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null

            val baseTime = Instant.parse("2026-10-31T10:00:00Z")
            val msg0 =
                createMockMessage(
                    id = 9301L,
                    username = "Alice",
                    content = "Earlier context before webhook",
                    isBot = false,
                    timestamp = baseTime,
                )
            val webhookWithTldr =
                createMockMessage(
                    id = 9302L,
                    username = "Bob",
                    content = "TL;DR: check out this release https://example.com/rel",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime + 1.minutes,
                )
            val targetMsg =
                createMockMessage(
                    id = 9303L,
                    username = "Charlie",
                    content = "Thoughts on this?",
                    isBot = false,
                    timestamp = baseTime + 2.minutes,
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(msg0, webhookWithTldr) },
                            noiseFilter = DialogueNoiseFilter(isLunaWebhook = { it == lunaWebhookId }),
                        ),
                    replySender = { _, _ -> },
                )

            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
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

            val precedingMessages = service.collector.retrievePrecedingMessages(targetMsg)
            assertEquals(listOf(msg0, webhookWithTldr), precedingMessages)

            service.handleReaction(event)

            val input = capturedInput
            assertNotNull(input)
            assertTrue(input.content.contains("[Alice]: Earlier context before webhook"))
            assertTrue(input.content.contains("[Bob]: TL;DR: check out this release https://example.com/rel"))
            assertTrue(input.content.contains("[Charlie]: Thoughts on this?"))
        }

    @Test
    fun `conversation slice extracts embed title and description and formats dialogue turn preserving masqueraded author`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            luna.core.LunaWebhookRegistry.register(lunaWebhookId)

            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("Slice summary")
            }

            val baseTime = Instant.fromEpochMilliseconds(1700000000000L)
            val embed =
                SummaryTestFixtures.createMockEmbed(
                    title = "Kotlin 2.0 Released",
                    description = "The K2 compiler is now stable and officially released.",
                )
            val webhookMsg =
                createMockMessage(
                    id = 8201L,
                    username = "MasqueradedAlice",
                    content = "Check this clean url: https://kotlinlang.org",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime,
                    embeds = listOf(embed),
                )
            val targetMsg =
                createMockMessage(
                    id = 8202L,
                    username = "Bob",
                    content = "This is huge news!",
                    isBot = false,
                    timestamp = baseTime + 1.minutes,
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(webhookMsg) },
                            peekResolver = { null },
                        ),
                    replySender = { _, _ -> },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            coVerify(exactly = 1) { summarizer.summarize(any<SummaryInput>(), any()) }
            val input = capturedInput
            assertNotNull(input)
            assertTrue(
                input.content.contains(
                    "[MasqueradedAlice]: Check this clean url: https://kotlinlang.org [連結預覽: Kotlin 2.0 Released - The K2 compiler is now stable and officially released.]",
                ),
            )
            assertTrue(input.content.contains("[Bob]: This is huge news!"))
        }

    @Test
    fun `dialogue turn formats embed with only title or only description correctly`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            luna.core.LunaWebhookRegistry.register(lunaWebhookId)

            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("Slice summary")
            }

            val baseTime = Instant.fromEpochMilliseconds(1700000000000L)
            val embedOnlyTitle = SummaryTestFixtures.createMockEmbed(title = "Only Title", description = null)
            val embedOnlyDesc = SummaryTestFixtures.createMockEmbed(title = "", description = "Only Description")

            val msg1 =
                createMockMessage(
                    id = 8301L,
                    username = "Alice",
                    content = "Message 1",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime,
                    embeds = listOf(embedOnlyTitle),
                )
            val msg2 =
                createMockMessage(
                    id = 8302L,
                    username = "Bob",
                    content = "Message 2",
                    isBot = false,
                    timestamp = baseTime + 1.minutes,
                    embeds = listOf(embedOnlyDesc),
                )
            val targetMsg =
                createMockMessage(
                    id = 8303L,
                    username = "Charlie",
                    content = "Target message",
                    isBot = false,
                    timestamp = baseTime + 2.minutes,
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            historyFetcher = { _, _ -> listOf(msg1, msg2) },
                            peekResolver = { null },
                        ),
                    replySender = { _, _ -> },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            val input = capturedInput
            assertNotNull(input)
            assertTrue(input.content.contains("[Alice]: Message 1 [連結預覽: Only Title]"))
            assertTrue(input.content.contains("[Bob]: Message 2 [連結預覽: Only Description]"))
            assertTrue(input.content.contains("[Charlie]: Target message"))
        }

    @Test
    fun `dialogue turn formats embed alongside image attachments in conversation slice`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            luna.core.LunaWebhookRegistry.register(lunaWebhookId)

            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("Slice summary")
            }

            val baseTime = Instant.fromEpochMilliseconds(1700000000000L)
            val embed = SummaryTestFixtures.createMockEmbed(title = "Preview Title", description = "Preview Desc")
            val imageAttachment = mockk<Attachment>()
            every { imageAttachment.contentType } returns "image/png"
            every { imageAttachment.filename } returns "chart.png"
            every { imageAttachment.size } returns 1024
            every { imageAttachment.url } returns "https://cdn.example.com/chart.png"
            every { imageAttachment.proxyUrl } returns "https://cdn.example.com/chart.png"

            val msg1 =
                createMockMessage(
                    id = 8401L,
                    username = "Alice",
                    content = "Here is the update",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    timestamp = baseTime,
                    attachments = setOf(imageAttachment),
                    embeds = listOf(embed),
                )
            val targetMsg =
                createMockMessage(
                    id = 8402L,
                    username = "Bob",
                    content = "Looks good",
                    isBot = false,
                    timestamp = baseTime + 1.minutes,
                )

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

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            val input = capturedInput
            assertNotNull(input)
            assertTrue(input.content.contains("[Alice]: Here is the update [連結預覽: Preview Title - Preview Desc] [附帶圖片 1: chart.png]"))
        }

    @Test
    fun `webhook registered in LunaWebhookRegistry is processed without calling Kord REST API`() =
        runTest {
            val lunaWebhookId = Snowflake(9999L)
            luna.core.LunaWebhookRegistry.register(lunaWebhookId)

            val kordMock = mockk<dev.kord.core.Kord>()
            val resourcesMock = mockk<dev.kord.core.ClientResources>()
            val strategyMock = mockk<dev.kord.core.supplier.EntitySupplyStrategy<*>>()
            every { resourcesMock.defaultStrategy } returns strategyMock
            every { kordMock.resources } returns resourcesMock

            val summarizer = mockk<GeminiSummarizer>()
            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } returns SummaryResult.Success("Summary text")

            val targetMsg =
                createMockMessage(
                    id = 8501L,
                    username = "MasqueradedUser",
                    content = "Post content",
                    isBot = true,
                    webhookId = lunaWebhookId,
                )

            var replyText: String? = null
            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            peekResolver = { null },
                            noiseFilter = DialogueNoiseFilter(kord = kordMock),
                        ),
                    replySender = { _, text -> replyText = text },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            assertEquals("Summary text", replyText)
            io.mockk.coVerify(exactly = 0) { kordMock.getWebhookOrNull(any()) }
        }

    @Test
    fun `single message reaction with embed formats preview in dialogue content`() =
        runTest {
            val lunaWebhookId = Snowflake(777L)
            luna.core.LunaWebhookRegistry.register(lunaWebhookId)

            val summarizer = mockk<GeminiSummarizer>()
            var capturedInput: SummaryInput? = null
            coEvery {
                summarizer.summarize(any<SummaryInput>(), any())
            } answers {
                capturedInput = firstArg<SummaryInput>()
                SummaryResult.Success("Single message summary")
            }

            val embed = SummaryTestFixtures.createMockEmbed(title = "Single Post", description = "Single Description")
            val targetMsg =
                createMockMessage(
                    id = 8601L,
                    username = "MasqueradedAlice",
                    content = "Original commentary",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    embeds = listOf(embed),
                )

            val service =
                SummaryService(
                    summarizer = summarizer,
                    collector =
                        ConversationSliceCollector(
                            peekResolver = { null },
                        ),
                    replySender = { _, _ -> },
                )

            val event = SummaryTestFixtures.createMockReactionEvent(targetMsg)
            service.handleReaction(event)

            val input = capturedInput
            assertNotNull(input)
            assertTrue(input.content.contains("[MasqueradedAlice]: Original commentary"))
            assertTrue(input.content.contains("[連結預覽: Single Post - Single Description]"))
        }
}
