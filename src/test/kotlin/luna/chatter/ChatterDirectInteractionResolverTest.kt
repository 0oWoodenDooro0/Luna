package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.entity.Message
import dev.kord.core.entity.MessageReference
import dev.kord.core.entity.User
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterDirectInteractionResolverTest {
    private lateinit var resolver: ChatterDirectInteractionResolver

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        SummaryMessageRegistry.clear()
        resolver = ChatterDirectInteractionResolver()
    }

    private fun createMockMessage(
        id: Long = 1L,
        channelId: Long = 100L,
        username: String = "testuser",
        content: String = "Hello world",
        isBot: Boolean = false,
        type: MessageType = MessageType.Default,
        mentionedUserIds: Set<Snowflake> = emptySet(),
        referencedMessage: Message? = null,
        authorId: Snowflake? = null,
    ): Message {
        val resolvedAuthorId = authorId ?: Snowflake(id)
        val user =
            mockk<User> {
                every { this@mockk.username } returns username
                every { this@mockk.isBot } returns isBot
                every { this@mockk.id } returns resolvedAuthorId
            }
        return mockk(relaxed = true) {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.channelId } returns Snowflake(channelId)
            every { this@mockk.author } returns user
            every { this@mockk.content } returns content
            every { this@mockk.type } returns type
            every { this@mockk.mentionedUserIds } returns mentionedUserIds
            every { this@mockk.referencedMessage } returns referencedMessage
        }
    }

    @Test
    fun `isDirectBotMention detects mentionedUserIds and raw mention tags only when selfId is resolved`() {
        val botSelfId = Snowflake(9999L)
        val otherUserId = Snowflake(1234L)

        assertTrue(
            resolver.isDirectBotMention(
                createMockMessage(content = "hey", mentionedUserIds = setOf(botSelfId)),
                botSelfId,
            ),
            "Should detect bot ID in mentionedUserIds",
        )
        assertTrue(
            resolver.isDirectBotMention(createMockMessage(content = "hello <@9999>"), botSelfId),
            "Should detect <@id> mention tag",
        )
        assertTrue(
            resolver.isDirectBotMention(createMockMessage(content = "hello <@!9999>"), botSelfId),
            "Should detect <@!id> nickname mention tag",
        )
        assertFalse(
            resolver.isDirectBotMention(
                createMockMessage(content = "<@9999> hello", mentionedUserIds = setOf(botSelfId)),
                null,
            ),
            "Should return false when selfId cannot be resolved",
        )
        assertFalse(
            resolver.isDirectBotMention(createMockMessage(content = "@Luna hello"), botSelfId),
            "Should not treat plain-text @Luna as a direct bot mention",
        )
        assertFalse(
            resolver.isDirectBotMention(
                createMockMessage(content = "<@1234> hello", mentionedUserIds = setOf(otherUserId)),
                botSelfId,
            ),
            "Should return false when only another user is mentioned",
        )
    }

    @Test
    fun `classifyDirectInteraction distinguishes mention, ping-off reply, summary-reply guard, and non-reply`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val otherUserId = Snowflake(1234L)
            val lunaChatter =
                createMockMessage(
                    id = 888L,
                    username = "Luna",
                    content = "That sounds fun!",
                    isBot = true,
                    authorId = botSelfId,
                )
            val lunaSummary =
                createMockMessage(
                    id = 889L,
                    username = "Luna",
                    content = "TL;DR: Everyone talked about dinner",
                    isBot = true,
                    authorId = botSelfId,
                )
            val otherUserMsg =
                createMockMessage(
                    id = 890L,
                    username = "Bob",
                    content = "Hey there",
                    authorId = otherUserId,
                )

            assertEquals(
                DirectInteractionType.MENTION,
                resolver.classifyDirectInteraction(
                    createMockMessage(
                        id = 901L,
                        type = MessageType.Reply,
                        content = "yes",
                        mentionedUserIds = setOf(botSelfId),
                        referencedMessage = lunaChatter,
                    ),
                    botSelfId,
                ),
            )
            assertEquals(
                DirectInteractionType.DIRECT_REPLY,
                resolver.classifyDirectInteraction(
                    createMockMessage(
                        id = 902L,
                        type = MessageType.Reply,
                        content = "yes",
                        referencedMessage = lunaChatter,
                    ),
                    botSelfId,
                ),
            )
            assertEquals(
                DirectInteractionType.NONE,
                resolver.classifyDirectInteraction(
                    createMockMessage(
                        id = 903L,
                        type = MessageType.Reply,
                        content = "thanks",
                        referencedMessage = lunaSummary,
                    ),
                    botSelfId,
                ),
            )
            assertEquals(
                DirectInteractionType.NONE,
                resolver.classifyDirectInteraction(
                    createMockMessage(
                        id = 904L,
                        type = MessageType.Default,
                        content = "forwarded",
                        referencedMessage = lunaChatter,
                    ),
                    botSelfId,
                ),
            )
            assertEquals(
                DirectInteractionType.NONE,
                resolver.classifyDirectInteraction(
                    createMockMessage(
                        id = 905L,
                        type = MessageType.Reply,
                        content = "agreed Bob",
                        referencedMessage = otherUserMsg,
                    ),
                    botSelfId,
                ),
            )
        }

    @Test
    fun `isReplyToLuna respects resolveRemote flag, cross-channel guard, error fallback, and lookup timeout`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val refId = Snowflake(888L)
            val lunaChatter =
                createMockMessage(
                    id = 888L,
                    username = "Luna",
                    content = "Older Luna message",
                    isBot = true,
                    authorId = botSelfId,
                )

            val sameChannelRef =
                mockk<MessageReference> {
                    every { data.id } returns OptionalSnowflake.Value(refId)
                    every { data.channelId } returns OptionalSnowflake.Value(Snowflake(100L))
                }
            val sameChannel =
                mockk<MessageChannelBehavior> {
                    coEvery { getMessageOrNull(refId) } returns lunaChatter
                }
            val uncachedSameChannelReply =
                createMockMessage(
                    id = 910L,
                    channelId = 100L,
                    type = MessageType.Reply,
                    content = "replying to older message",
                    referencedMessage = null,
                ).apply {
                    every { messageReference } returns sameChannelRef
                    every { channel } returns sameChannel
                }

            assertFalse(
                resolver.isReplyToLuna(uncachedSameChannelReply, botSelfId, resolveRemote = false),
                "resolveRemote = false should not perform remote lookup for uncached reply target",
            )
            assertTrue(
                resolver.isReplyToLuna(uncachedSameChannelReply, botSelfId, resolveRemote = true),
                "resolveRemote = true should resolve uncached same-channel reply target",
            )

            val crossChannelRef =
                mockk<MessageReference> {
                    every { data.id } returns OptionalSnowflake.Value(refId)
                    every { data.channelId } returns OptionalSnowflake.Value(Snowflake(200L))
                }
            val crossChannelReply =
                createMockMessage(
                    id = 911L,
                    channelId = 100L,
                    type = MessageType.Reply,
                    content = "cross channel reply",
                    referencedMessage = null,
                ).apply {
                    every { messageReference } returns crossChannelRef
                }
            assertFalse(
                resolver.isReplyToLuna(crossChannelReply, botSelfId),
                "Cross-channel reply should fail safe to false",
            )

            val failingChannel =
                mockk<MessageChannelBehavior> {
                    coEvery { getMessageOrNull(refId) } throws RuntimeException("REST 500")
                }
            val failingLookupReply =
                createMockMessage(
                    id = 912L,
                    channelId = 100L,
                    type = MessageType.Reply,
                    content = "lookup fails",
                    referencedMessage = null,
                ).apply {
                    every { messageReference } returns sameChannelRef
                    every { channel } returns failingChannel
                }
            assertFalse(
                resolver.isReplyToLuna(failingLookupReply, botSelfId),
                "Lookup exception should fail safe to false",
            )

            val slowResolver = ChatterDirectInteractionResolver(referenceLookupTimeoutMs = 20L)
            val slowChannel =
                mockk<MessageChannelBehavior> {
                    coEvery { getMessageOrNull(refId) } coAnswers {
                        delay(200L)
                        lunaChatter
                    }
                }
            val slowLookupReply =
                createMockMessage(
                    id = 913L,
                    channelId = 100L,
                    type = MessageType.Reply,
                    content = "slow lookup",
                    referencedMessage = null,
                ).apply {
                    every { messageReference } returns sameChannelRef
                    every { channel } returns slowChannel
                }
            assertFalse(
                slowResolver.isReplyToLuna(slowLookupReply, botSelfId),
                "Timed-out remote lookup should fail safe to false",
            )
        }
}
