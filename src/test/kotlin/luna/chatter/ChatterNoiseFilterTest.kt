package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
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
        every { msg.embeds } returns emptyList()
        every { msg.mentionedUserIds } returns mentionedUserIds
        every { msg.referencedMessage } returns referencedMessage
        return msg
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
    fun `preserves blank embed only message in evaluate and conversational context`() =
        runTest {
            val embed = mockk<dev.kord.core.entity.Embed>()
            val message = createMockMessage(channelId = 401L, username = "Charlie", content = "   ")
            every { message.embeds } returns listOf(embed)

            val allowedResult = filter.evaluate(message)
            assertIs<ChatterFilterResult.Allowed>(allowedResult)
            assertEquals("Charlie", allowedResult.speakerName)
            assertEquals("[Embed]", allowedResult.content)
            assertTrue(filter.shouldProcess(message))

            val chatMessage = filter.toChatMessageOrNull(message)
            assertNotNull(chatMessage)
            assertEquals("Charlie", chatMessage.author)
            assertEquals("[Embed]", chatMessage.content)
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
    fun `isDirectBotMention returns true only when kordSelfId is resolved and message explicitly at-mentions the bot`() {
        val botSelfId = Snowflake(9999L)
        val otherUserId = Snowflake(1234L)

        // 1. MentionedUserIds contains bot self ID
        val msg1 = createMockMessage(content = "hey bot", mentionedUserIds = setOf(botSelfId))
        assertTrue(filter.isDirectBotMention(msg1, botSelfId))

        // 2. Content contains <@9999> or <@!9999>
        val msg2 = createMockMessage(content = "hello <@9999> how are you")
        assertTrue(filter.isDirectBotMention(msg2, botSelfId))
        val msg3 = createMockMessage(content = "hello <@!9999> how are you")
        assertTrue(filter.isDirectBotMention(msg3, botSelfId))

        // 3. Returns false when kordSelfId is null (and message.kord is unavailable), even with @Luna or <@9999>
        val msgWithRawTag = createMockMessage(content = "<@9999> hello", mentionedUserIds = setOf(botSelfId))
        assertFalse(filter.isDirectBotMention(msgWithRawTag, null))
        val msgWithTextLuna = createMockMessage(content = "@Luna what is the weather today?")
        assertFalse(filter.isDirectBotMention(msgWithTextLuna, null))

        // 4. Plain-text @luna / @Luna does not bypass Participation Decision even when selfId is resolved
        val msgPlainLuna = createMockMessage(content = "Hey @luna, tell me a joke")
        assertFalse(filter.isDirectBotMention(msgPlainLuna, botSelfId))
        val msgUpperLuna = createMockMessage(content = "@Luna what do you think?")
        assertFalse(filter.isDirectBotMention(msgUpperLuna, botSelfId))

        // 5. Discord reply to Luna does not bypass Participation Decision
        val lunaMsg = createMockMessage(id = 888L, isBot = true)
        val userAuthor = mockk<User>()
        every { userAuthor.id } returns botSelfId
        every { lunaMsg.author } returns userAuthor
        val msgReplyToLuna = createMockMessage(content = "I agree with your point", referencedMessage = lunaMsg)
        assertFalse(filter.isDirectBotMention(msgReplyToLuna, botSelfId))

        // 6. Mentioning another user or unrelated text returns false
        val msgOtherMention = createMockMessage(content = "<@1234> hello", mentionedUserIds = setOf(otherUserId))
        assertFalse(filter.isDirectBotMention(msgOtherMention, botSelfId))
        val msgUnrelated = createMockMessage(content = "What is everyone doing tonight?")
        assertFalse(filter.isDirectBotMention(msgUnrelated, botSelfId))
    }

    @Test
    fun `evaluate allows short ambient messages and preserves raw content verbatim`() =
        runTest {
            val botSelfId = Snowflake(42L)
            val shortQuestion = createMockMessage(id = 701L, username = "Alice", content = "?")
            val shortReaction = createMockMessage(id = 702L, username = "Bob", content = "草")
            val shortPhrase = createMockMessage(id = 703L, username = "Charlie", content = "真的嗎")
            val rawSpacingAndMentions =
                createMockMessage(
                    id = 704L,
                    username = "Dave",
                    content = "  <@42>   @luna   check  this  ",
                    mentionedUserIds = setOf(botSelfId),
                )

            for (msg in listOf(shortQuestion, shortReaction, shortPhrase)) {
                val res = filter.evaluate(msg, kordSelfId = botSelfId)
                assertIs<ChatterFilterResult.Allowed>(res)
                assertEquals(msg.content, res.content)
            }

            val rawRes = filter.evaluate(rawSpacingAndMentions, kordSelfId = botSelfId)
            assertIs<ChatterFilterResult.Allowed>(rawRes)
            assertEquals("  <@42>   @luna   check  this  ", rawRes.content)
        }

    @Test
    fun `filterContextMessages preserves valid dialogue, attachments, embeds, and Luna chatter while dropping noise`() {
        val botSelfId = Snowflake(42L)
        val validMsg = createMockMessage(id = 801L, username = "Alice", content = "Check this out")
        val botMsg = createMockMessage(id = 802L, username = "SpamBot", content = "Spam", isBot = true)
        val cmdMsg = createMockMessage(id = 803L, username = "Bob", content = "!help")
        val lunaChatter =
            createMockMessage(
                id = 804L,
                username = "Luna",
                content = "looks neat",
                isBot = true,
                authorId = botSelfId,
            )
        val lunaSummary =
            createMockMessage(
                id = 805L,
                username = "Luna",
                content = "TL;DR: Summary",
                isBot = true,
                authorId = botSelfId,
            )

        val embed = mockk<dev.kord.core.entity.Embed>()
        every { embed.title } returns "Article"
        every { embed.description } returns "Details"
        val embedOnlyMsg = createMockMessage(id = 806L, username = "Charlie", content = "")
        every { embedOnlyMsg.embeds } returns listOf(embed)

        val filtered =
            filter.filterContextMessages(
                listOf(validMsg, botMsg, cmdMsg, lunaChatter, lunaSummary, embedOnlyMsg),
                kordSelfId = botSelfId,
            )

        assertEquals(listOf(801L, 804L, 806L), filtered.map { it.id.value.toLong() })
    }

    @Test
    fun `toChatMessageOrNull converts Luna bot chatter message and preserves raw mentions and spacing`() {
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

        val mentionMsg =
            createMockMessage(
                id = 502L,
                username = "Alice",
                content = "<@!42>  @luna  hello   world",
                mentionedUserIds = setOf(botSelfId),
            )
        val mentionChatMessage = filter.toChatMessageOrNull(mentionMsg, botSelfId)
        assertNotNull(mentionChatMessage)
        assertEquals("Alice", mentionChatMessage.author)
        assertEquals("<@!42>  @luna  hello   world", mentionChatMessage.content)
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
    fun `evaluate preserves raw mention tags verbatim without injecting synthetic greeting`() =
        runTest {
            val botSelfId = Snowflake(42L)

            val atLunaMsg = createMockMessage(id = 631L, username = "Dave", content = "@Luna")
            val rawMentionMsg = createMockMessage(id = 632L, username = "Dave", content = "<@42>")
            val emptyDirectMentionMsg = createMockMessage(id = 633L, username = "Dave", content = "")

            // @Luna is preserved verbatim
            val res1 = filter.evaluate(atLunaMsg, isDirectMention = false, kordSelfId = botSelfId)
            assertIs<ChatterFilterResult.Allowed>(res1)
            assertEquals("Dave", res1.speakerName)
            assertEquals("@Luna", res1.content)

            // <@42> is preserved verbatim without stripping or substituting a greeting
            val res2 = filter.evaluate(rawMentionMsg, isDirectMention = true, kordSelfId = botSelfId)
            assertIs<ChatterFilterResult.Allowed>(res2)
            assertEquals("Dave", res2.speakerName)
            assertEquals("<@42>", res2.content)

            // Direct mention with empty text is preserved without injecting synthetic greeting
            val res3 = filter.evaluate(emptyDirectMentionMsg, isDirectMention = true, kordSelfId = botSelfId)
            assertIs<ChatterFilterResult.Allowed>(res3)
            assertEquals("", res3.content)

            // Pure empty message without attachments, embeds, or direct mention remains rejected
            val res4 = filter.evaluate(emptyDirectMentionMsg, isDirectMention = false, kordSelfId = botSelfId)
            assertIs<ChatterFilterResult.Rejected>(res4)
            assertEquals(RejectionReason.EMPTY_MESSAGE, res4.reason)
        }
}
