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

class ChatterNoiseFilterContextTest {
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
    fun `allows chatter triggers after 5 second default cooldown duration expires`() =
        runTest {
            assertEquals(5_000L, BoundedChannelCooldownCache.DEFAULT_COOLDOWN_MS)

            val channelId = Snowflake(402L)
            val message = createMockMessage(channelId = 402L, content = "Trigger after cooldown")

            // Record reply at simulatedTime = 1_000_000
            cooldownCache.record(channelId)
            assertTrue(cooldownCache.isOnCooldown(channelId))

            // Advance time to 4.999 seconds later: still on cooldown
            simulatedTime += 4_999L
            assertFalse(filter.shouldProcess(message))

            // Advance time to 5.000 seconds total: cooldown expired
            simulatedTime += 1L
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
}
