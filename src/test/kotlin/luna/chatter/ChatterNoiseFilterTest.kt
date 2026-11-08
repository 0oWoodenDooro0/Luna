package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.MessageChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatterNoiseFilterTest {
    private var simulatedTime: Long = 1_000_000L
    private lateinit var cooldownCache: BoundedChannelCooldownCache
    private lateinit var filter: ChatterNoiseFilter

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
        simulatedTime = 1_000_000L
        cooldownCache = BoundedChannelCooldownCache(timeSource = { simulatedTime })
        filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
    }

    private fun createMockMessage(
        id: Long = 1L,
        channelId: Long = 100L,
        username: String = "testuser",
        content: String = "Hello world",
        isBot: Boolean = false,
        webhookId: Snowflake? = null,
        type: MessageType = MessageType.Default,
        authorIsNull: Boolean = false,
        mentionedUserIds: Set<Snowflake> = emptySet(),
        referencedMessage: Message? = null,
        authorId: Snowflake? = null,
    ): Message {
        val resolvedAuthorId = authorId ?: webhookId ?: Snowflake(id)
        val author =
            if (authorIsNull) {
                null
            } else {
                val user = mockk<User>()
                every { user.username } returns username
                every { user.isBot } returns isBot
                every { user.id } returns resolvedAuthorId
                user
            }

        val userData = mockk<dev.kord.core.cache.data.UserData>()
        every { userData.username } returns username
        every { userData.id } returns resolvedAuthorId

        val messageData = mockk<dev.kord.core.cache.data.MessageData>()
        every { messageData.author } returns userData
        every { messageData.content } returns content
        every { messageData.id } returns Snowflake(id)

        val msg = mockk<Message>()
        every { msg.id } returns Snowflake(id)
        every { msg.channelId } returns Snowflake(channelId)
        every { msg.author } returns author
        every { msg.data } returns messageData
        every { msg.content } returns content
        every { msg.type } returns type
        every { msg.webhookId } returns webhookId
        every { msg.attachments } returns emptySet()
        every { msg.mentionedUserIds } returns mentionedUserIds
        every { msg.referencedMessage } returns referencedMessage
        return msg
    }

    private fun createMockChannel(channelId: Long): MessageChannel {
        val channel = mockk<MessageChannel>()
        every { channel.id } returns Snowflake(channelId)
        return channel
    }

    @Test
    fun `accepts regular user message when chatter is enabled and not on cooldown`() =
        runTest {
            val message = createMockMessage(id = 101L, channelId = 500L, username = "Alice", content = "Good morning everyone")
            val result = filter.evaluate(message)

            assertIs<ChatterFilterResult.Allowed>(result)
            assertEquals("Alice", result.speakerName)
            assertEquals("Good morning everyone", result.content)
            assertTrue(filter.shouldProcess(message))
        }

    @Test
    fun `rejects messages when chatter is globally disabled`() =
        runTest {
            filter.enabled = false
            val message = createMockMessage(id = 102L, channelId = 500L, username = "Alice", content = "Are you there?")
            val result = filter.evaluate(message)

            assertIs<ChatterFilterResult.Rejected>(result)
            assertEquals(RejectionReason.CHATTER_DISABLED, result.reason)
            assertFalse(filter.shouldProcess(message))
        }

    @Test
    fun `rejects messages when channel is not in allowed channels whitelist`() =
        runTest {
            filter.allowedChannels = setOf(Snowflake(500L), Snowflake(501L))
            val messageInAllowed = createMockMessage(id = 103L, channelId = 500L, username = "Alice", content = "Allowed channel")
            val messageInDisallowed = createMockMessage(id = 104L, channelId = 999L, username = "Bob", content = "Disallowed channel")

            val allowedResult = filter.evaluate(messageInAllowed)
            assertIs<ChatterFilterResult.Allowed>(allowedResult)

            val disallowedResult = filter.evaluate(messageInDisallowed)
            assertIs<ChatterFilterResult.Rejected>(disallowedResult)
            assertEquals(RejectionReason.CHANNEL_NOT_ALLOWED, disallowedResult.reason)
            assertFalse(filter.shouldProcess(messageInDisallowed))
        }

    @Test
    fun `allows messages across any channel when allowed channels whitelist is unconfigured`() =
        runTest {
            filter.allowedChannels = emptySet()
            val msg1 = createMockMessage(channelId = 111L, content = "Channel 1")
            val msg2 = createMockMessage(channelId = 222L, content = "Channel 2")

            assertTrue(filter.shouldProcess(msg1))
            assertTrue(filter.shouldProcess(msg2))
        }

    @Test
    fun `rejects ambient third party bots with isBot true`() =
        runTest {
            val botMessage = createMockMessage(id = 105L, username = "MusicBot", content = "Playing song", isBot = true)
            val result = filter.evaluate(botMessage)

            assertIs<ChatterFilterResult.Rejected>(result)
            assertEquals(RejectionReason.BOT_MESSAGE, result.reason)
            assertFalse(filter.shouldProcess(botMessage))
        }

    @Test
    fun `rejects unverified external webhooks`() =
        runTest {
            val externalWebhookId = Snowflake(8888L)
            val webhookMsg =
                createMockMessage(
                    id = 106L,
                    username = "GitHubNotifier",
                    content = "PR #42 opened",
                    isBot = true,
                    webhookId = externalWebhookId,
                )

            val result = filter.evaluate(webhookMsg)
            assertIs<ChatterFilterResult.Rejected>(result)
            assertEquals(RejectionReason.UNVERIFIED_WEBHOOK, result.reason)
            assertFalse(filter.shouldProcess(webhookMsg))
        }

    @Test
    fun `accepts and permits masqueraded webhook messages verified via LunaWebhookRegistry`() =
        runTest {
            val lunaWebhookId = Snowflake(7777L)
            LunaWebhookRegistry.register(lunaWebhookId)

            val masqueradedMsg =
                createMockMessage(
                    id = 107L,
                    username = "AliceMasquerade",
                    content = "Check this clean url https://example.com/item",
                    isBot = true,
                    webhookId = lunaWebhookId,
                )

            val result = filter.evaluate(masqueradedMsg)
            assertIs<ChatterFilterResult.Allowed>(result)
            assertEquals("AliceMasquerade", result.speakerName)
            assertEquals("AliceMasquerade", filter.extractSpeakerName(masqueradedMsg))
            assertTrue(filter.shouldProcess(masqueradedMsg))
        }

    @Test
    fun `accepts masqueraded webhook messages even when author entity is null using data author username`() =
        runTest {
            val lunaWebhookId = Snowflake(7778L)
            LunaWebhookRegistry.register(lunaWebhookId)

            val masqueradedMsg =
                createMockMessage(
                    id = 108L,
                    username = "BobMasquerade",
                    content = "Look at this preview",
                    isBot = true,
                    webhookId = lunaWebhookId,
                    authorIsNull = true,
                )

            val result = filter.evaluate(masqueradedMsg)
            assertIs<ChatterFilterResult.Allowed>(result)
            assertEquals("BobMasquerade", result.speakerName)
            assertEquals("BobMasquerade", filter.extractSpeakerName(masqueradedMsg))
            assertTrue(filter.shouldProcess(masqueradedMsg))
        }

    @Test
    fun `rejects slash commands and chat input commands`() =
        runTest {
            val slashCommandMsg = createMockMessage(content = "/chatter status")
            val chatInputCommandMsg = createMockMessage(content = "help", type = MessageType.ChatInputCommand)
            val contextCommandMsg = createMockMessage(content = "inspect", type = MessageType.ContextMenuCommand)

            val slashResult = filter.evaluate(slashCommandMsg)
            val chatInputResult = filter.evaluate(chatInputCommandMsg)
            val contextResult = filter.evaluate(contextCommandMsg)

            assertIs<ChatterFilterResult.Rejected>(slashResult)
            assertEquals(RejectionReason.COMMAND, slashResult.reason)
            assertIs<ChatterFilterResult.Rejected>(chatInputResult)
            assertEquals(RejectionReason.COMMAND, chatInputResult.reason)
            assertIs<ChatterFilterResult.Rejected>(contextResult)
            assertEquals(RejectionReason.COMMAND, contextResult.reason)
        }

    @Test
    fun `rejects bot prefix commands starting with exclamation or other prefixes`() =
        runTest {
            val bangCmd = createMockMessage(content = "!play song name")
            val tildeCmd = createMockMessage(content = "~help")
            val questionCmd = createMockMessage(content = "?status")

            assertIs<ChatterFilterResult.Rejected>(filter.evaluate(bangCmd))
            assertIs<ChatterFilterResult.Rejected>(filter.evaluate(tildeCmd))
            assertIs<ChatterFilterResult.Rejected>(filter.evaluate(questionCmd))
        }

    @Test
    fun `allows natural human exclamation or question punctuation that are not bot commands`() =
        runTest {
            val excitingSentence = createMockMessage(content = "That's awesome! Let's go!")
            val questionSentence = createMockMessage(content = "Are you coming?")
            val singleBang = createMockMessage(content = "!")
            val singleQuestion = createMockMessage(content = "?")

            assertTrue(filter.shouldProcess(excitingSentence))
            assertTrue(filter.shouldProcess(questionSentence))
            assertTrue(filter.shouldProcess(singleBang))
            assertTrue(filter.shouldProcess(singleQuestion))
        }

    @Test
    fun `rejects system notices and non speech message types`() =
        runTest {
            val pinNotice = createMockMessage(content = "Pinned a message", type = MessageType.ChannelPinnedMessage)
            val joinNotice = createMockMessage(content = "Joined the server", type = MessageType.UserJoin)
            val boostNotice = createMockMessage(content = "Boosted the server", type = MessageType.GuildBoost)

            assertIs<ChatterFilterResult.Rejected>(filter.evaluate(pinNotice))
            assertEquals(RejectionReason.SYSTEM_MESSAGE, (filter.evaluate(pinNotice) as ChatterFilterResult.Rejected).reason)
            assertIs<ChatterFilterResult.Rejected>(filter.evaluate(joinNotice))
            assertIs<ChatterFilterResult.Rejected>(filter.evaluate(boostNotice))
        }

    @Test
    fun `rejects empty and whitespace only messages`() =
        runTest {
            val emptyMsg = createMockMessage(content = "")
            val whitespaceMsg = createMockMessage(content = "    \n\t   ")

            val emptyResult = filter.evaluate(emptyMsg)
            assertIs<ChatterFilterResult.Rejected>(emptyResult)
            assertEquals(RejectionReason.EMPTY_MESSAGE, emptyResult.reason)

            val whitespaceResult = filter.evaluate(whitespaceMsg)
            assertIs<ChatterFilterResult.Rejected>(whitespaceResult)
            assertEquals(RejectionReason.EMPTY_MESSAGE, whitespaceResult.reason)
        }

    @Test
    fun `preserves blank message with media attachments in conversational context`() =
        runTest {
            val attachment = mockk<dev.kord.core.entity.Attachment>()
            val message = createMockMessage(channelId = 400L, content = "")
            every { message.attachments } returns setOf(attachment)

            val allowedResult = filter.evaluate(message)
            assertIs<ChatterFilterResult.Allowed>(allowedResult)
            assertTrue(filter.shouldProcess(message))

            val chatMessage = filter.toChatMessageOrNull(message)
            assertNotNull(chatMessage)
            assertEquals("[Attachment]", chatMessage.content)
        }

    @Test
    fun `suppresses chatter triggers when channel is within cooldown window`() =
        runTest {
            val channel = createMockChannel(401L)
            val message = createMockMessage(channelId = 401L, content = "First trigger")

            // Channel is initially allowed
            assertTrue(filter.shouldProcess(message))

            // Record active/reply trigger at simulatedTime = 1_000_000
            cooldownCache.record(channel.id)
            assertTrue(cooldownCache.isOnCooldown(channel.id))

            // Subsequent message 10 seconds later (within 30s cooldown window)
            simulatedTime += 10_000L
            val suppressedResult = filter.evaluate(message)
            assertIs<ChatterFilterResult.Rejected>(suppressedResult)
            assertEquals(RejectionReason.CHANNEL_ON_COOLDOWN, suppressedResult.reason)
            assertFalse(filter.shouldProcess(message))
        }

    @Test
    fun `allows chatter triggers after cooldown duration expires`() =
        runTest {
            val channelId = Snowflake(402L)
            val message = createMockMessage(channelId = 402L, content = "Trigger after cooldown")

            // Record reply at simulatedTime = 1_000_000
            cooldownCache.record(channelId)
            assertTrue(cooldownCache.isOnCooldown(channelId))

            // Advance time to 29.999 seconds later: still on cooldown
            simulatedTime += 29_999L
            assertFalse(filter.shouldProcess(message))

            // Advance time past 30.000 seconds (e.g. 30.001s total)
            simulatedTime += 2L
            assertFalse(cooldownCache.isOnCooldown(channelId))

            val allowedResult = filter.evaluate(message)
            assertIs<ChatterFilterResult.Allowed>(allowedResult)
            assertTrue(filter.shouldProcess(message))
        }

    @Test
    fun `supports custom cooldown duration when configured`() =
        runTest {
            val customCache =
                BoundedChannelCooldownCache(
                    defaultCooldownDurationMs = 15_000L,
                    timeSource = { simulatedTime },
                )
            val customFilter = ChatterNoiseFilter(cooldownCache = customCache, cooldownDurationMs = 15_000L)
            val message = createMockMessage(channelId = 403L, content = "Custom cooldown test")

            customCache.record(message.channelId)
            simulatedTime += 14_000L
            assertFalse(customFilter.shouldProcess(message))

            simulatedTime += 2_000L // 16s total > 15s
            assertTrue(customFilter.shouldProcess(message))
        }

    @Test
    fun `evicts eldest channel when capacity exceeds 500 entries`() =
        runTest {
            val boundedCache = BoundedChannelCooldownCache(maxEntries = 500, timeSource = { simulatedTime })

            // Fill cache with 500 channels
            for (i in 1..500) {
                boundedCache.record(Snowflake(i.toLong()), timestamp = simulatedTime)
            }
            assertEquals(500, boundedCache.size)
            assertTrue(boundedCache.contains(Snowflake(1L)))
            assertTrue(boundedCache.contains(Snowflake(500L)))

            // Insert 501st channel
            boundedCache.record(Snowflake(501L), timestamp = simulatedTime)
            assertEquals(500, boundedCache.size)
            // Channel 1 should have been evicted as eldest
            assertFalse(boundedCache.contains(Snowflake(1L)))
            assertTrue(boundedCache.contains(Snowflake(501L)))
        }

    @Test
    fun `LRU access refreshes entry order before eviction`() =
        runTest {
            val boundedCache = BoundedChannelCooldownCache(maxEntries = 3, timeSource = { simulatedTime })

            boundedCache.record(Snowflake(1L), timestamp = simulatedTime)
            boundedCache.record(Snowflake(2L), timestamp = simulatedTime)
            boundedCache.record(Snowflake(3L), timestamp = simulatedTime)

            // Access channel 1 to refresh its LRU recency
            boundedCache.get(Snowflake(1L))

            // Add channel 4 -> channel 2 should be evicted, not channel 1
            boundedCache.record(Snowflake(4L), timestamp = simulatedTime)
            assertEquals(3, boundedCache.size)
            assertTrue(boundedCache.contains(Snowflake(1L)))
            assertFalse(boundedCache.contains(Snowflake(2L)))
            assertTrue(boundedCache.contains(Snowflake(3L)))
            assertTrue(boundedCache.contains(Snowflake(4L)))
        }

    @Test
    fun `direct bot mention bypasses channel cooldown in evaluate and shouldProcess`() =
        runTest {
            val channelId = 100L
            cooldownCache.record(channelId, simulatedTime)
            assertTrue(cooldownCache.isOnCooldown(channelId))

            val botSelfId = Snowflake(9999L)
            val message =
                createMockMessage(
                    channelId = channelId,
                    content = "<@9999> hello there",
                    mentionedUserIds = setOf(botSelfId),
                )

            // When isDirectMention = false, cooldown blocks it
            val rejected = filter.evaluate(message, isDirectMention = false)
            assertIs<ChatterFilterResult.Rejected>(rejected)
            assertEquals(RejectionReason.CHANNEL_ON_COOLDOWN, rejected.reason)
            assertFalse(filter.shouldProcess(message, isDirectMention = false))

            // When isDirectMention = true, cooldown is bypassed
            val allowed = filter.evaluate(message, isDirectMention = true)
            assertIs<ChatterFilterResult.Allowed>(allowed)
            assertTrue(filter.shouldProcess(message, isDirectMention = true))
        }

    @Test
    fun `isDirectBotMention detects user mentions, string mentions, reply references, and text prefix`() {
        val botSelfId = Snowflake(9999L)

        // 1. MentionedUserIds contains bot self ID
        val msg1 = createMockMessage(content = "hey bot", mentionedUserIds = setOf(botSelfId))
        assertTrue(filter.isDirectBotMention(msg1, botSelfId))

        // 2. Content contains <@9999> or <@!9999>
        val msg2 = createMockMessage(content = "hello <@9999> how are you")
        assertTrue(filter.isDirectBotMention(msg2, botSelfId))
        val msg3 = createMockMessage(content = "hello <@!9999> how are you")
        assertTrue(filter.isDirectBotMention(msg3, botSelfId))

        // 3. Replied-to message author matches bot self ID
        val lunaMsg = createMockMessage(id = 888L, isBot = true)
        val userAuthor = mockk<User>()
        every { userAuthor.id } returns botSelfId
        every { lunaMsg.author } returns userAuthor
        val msg4 = createMockMessage(content = "I agree with your point", referencedMessage = lunaMsg)
        assertTrue(filter.isDirectBotMention(msg4, botSelfId))

        // 4. Content starts with or contains @Luna
        val msg5 = createMockMessage(content = "@Luna what is the weather today?")
        assertTrue(filter.isDirectBotMention(msg5, null))
        val msg6 = createMockMessage(content = "Hey @luna, tell me a joke")
        assertTrue(filter.isDirectBotMention(msg6, botSelfId))

        // 5. Unrelated message does not match
        val msg7 = createMockMessage(content = "What is everyone doing tonight?")
        assertFalse(filter.isDirectBotMention(msg7, botSelfId))
    }

    @Test
    fun `isEligibleForLayaEvaluation validates question marks, luna keyword, and substantive message length`() {
        // Trivial messages (< 5 chars, no ? or luna) -> false
        assertFalse(filter.isEligibleForLayaEvaluation("ok"))
        assertFalse(filter.isEligibleForLayaEvaluation("好"))
        assertFalse(filter.isEligibleForLayaEvaluation("++"))
        assertFalse(filter.isEligibleForLayaEvaluation("111"))
        assertFalse(filter.isEligibleForLayaEvaluation("    "))

        // Questions (? or ？) -> true regardless of length
        assertTrue(filter.isEligibleForLayaEvaluation("在嗎?"))
        assertTrue(filter.isEligibleForLayaEvaluation("吃飽了嗎？"))

        // Luna keywords -> true regardless of length
        assertTrue(filter.isEligibleForLayaEvaluation("luna"))
        assertTrue(filter.isEligibleForLayaEvaluation("嗨Luna"))
        assertTrue(filter.isEligibleForLayaEvaluation("露娜在嗎"))

        // Substantive messages (>= 5 characters) -> true
        assertTrue(filter.isEligibleForLayaEvaluation("今天天氣真好"))
        assertTrue(filter.isEligibleForLayaEvaluation("週末大家有什麼安排呢"))
    }

    @Test
    fun `toChatMessageOrNull converts Luna bot chatter message to ChatMessage with speaker Luna`() {
        val botSelfId = Snowflake(42L)
        val chatterMsg =
            createMockMessage(
                id = 501L,
                username = "Luna",
                content = "I really enjoyed reading this article!",
                isBot = true,
                authorId = botSelfId,
            )

        val chatMessage = filter.toChatMessageOrNull(chatterMsg, botSelfId)
        assertNotNull(chatMessage)
        assertEquals("Luna", chatMessage.author)
        assertEquals("I really enjoyed reading this article!", chatMessage.content)
    }

    @Test
    fun `toChatMessageOrNull excludes Luna bot summary message registered in SummaryMessageRegistry`() {
        val botSelfId = Snowflake(42L)
        val registry = luna.core.BoundedSummaryRegistry()
        val customFilter = ChatterNoiseFilter(summaryRegistry = registry)

        val summaryId = Snowflake(502L)
        registry.register(summaryId)

        val summaryMsg =
            createMockMessage(
                id = 502L,
                username = "Luna",
                content = "Custom formatted summary without standard markers",
                isBot = true,
                authorId = botSelfId,
            )

        val chatMessage = customFilter.toChatMessageOrNull(summaryMsg, botSelfId)
        kotlin.test.assertNull(chatMessage)
    }

    @Test
    fun `toChatMessageOrNull excludes Luna bot summary message with structural marker even when registry is empty`() {
        val botSelfId = Snowflake(42L)
        val registry = luna.core.BoundedSummaryRegistry()
        val customFilter = ChatterNoiseFilter(summaryRegistry = registry)

        val tldrMsg =
            createMockMessage(
                id = 503L,
                username = "Luna",
                content = "TL;DR: Summary generated before bot was restarted",
                isBot = true,
                authorId = botSelfId,
            )
        val keypointsMsg =
            createMockMessage(
                id = 504L,
                username = "Luna",
                content = "討論重點摘要:\n• 重點一\n• 重點二",
                isBot = true,
                authorId = botSelfId,
            )

        kotlin.test.assertNull(customFilter.toChatMessageOrNull(tldrMsg, botSelfId))
        kotlin.test.assertNull(customFilter.toChatMessageOrNull(keypointsMsg, botSelfId))
    }

    @Test
    fun `toChatMessageOrNull excludes third party bot messages`() {
        val botSelfId = Snowflake(42L)
        val thirdPartyBotId = Snowflake(9999L)
        val thirdPartyMsg =
            createMockMessage(
                id = 505L,
                username = "MusicBot",
                content = "Now playing: track 1",
                isBot = true,
                authorId = thirdPartyBotId,
            )

        val chatMessage = filter.toChatMessageOrNull(thirdPartyMsg, botSelfId)
        kotlin.test.assertNull(chatMessage)
    }

    @Test
    fun `toChatMessageOrNull excludes unverified webhook messages`() {
        val botSelfId = Snowflake(42L)
        val unverifiedWebhookId = Snowflake(8888L)
        val webhookMsg =
            createMockMessage(
                id = 506L,
                username = "ExternalWebhook",
                content = "New deployment finished",
                isBot = true,
                webhookId = unverifiedWebhookId,
            )

        val chatMessage = filter.toChatMessageOrNull(webhookMsg, botSelfId)
        kotlin.test.assertNull(chatMessage)
    }
}
