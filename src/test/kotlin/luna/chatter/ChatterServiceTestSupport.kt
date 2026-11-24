package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import io.mockk.every
import io.mockk.mockk
import luna.core.LunaWebhookRegistry

internal class ChatterServiceTestSupport {
    var simulatedTime: Long = 100_000L
    lateinit var cooldownCache: BoundedChannelCooldownCache
    lateinit var filter: ChatterNoiseFilter
    lateinit var decisionClient: GeminiDecisionClient
    lateinit var chatterGenerator: ChatterGenerator
    lateinit var channelPort: FakeChatterChannelPort
    lateinit var contextCollector: ChatterContextCollector
    lateinit var service: ChatterService

    fun setUp() {
        LunaWebhookRegistry.clear()
        simulatedTime = 100_000L
        cooldownCache = BoundedChannelCooldownCache(timeSource = { simulatedTime })
        filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
        decisionClient = mockk(relaxed = true)
        every { decisionClient.config } returns ChatterConfig()
        chatterGenerator = mockk(relaxed = true)
        every { chatterGenerator.config } returns ChatterConfig()
        channelPort = FakeChatterChannelPort()
        contextCollector =
            ChatterContextCollector(
                noiseFilter = filter,
                peekResolver = { null },
            )

        service =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = chatterGenerator,
                contextCollector = contextCollector,
                channelPort = channelPort,
                personaStateProvider = CircadianPersonaDrift(enabled = false),
            )
    }

    fun tearDown() {
        LunaWebhookRegistry.clear()
    }

    fun createMockMessage(
        id: Long = 1L,
        channelId: Long = 100L,
        authorId: Long = id,
        username: String = "Alice",
        content: String = "Hello Luna, how are you today?",
        isBot: Boolean = false,
        webhookId: Snowflake? = null,
        type: MessageType = MessageType.Default,
        authorIsNull: Boolean = false,
        mentionedUserIds: Set<Snowflake> = emptySet(),
        selfId: Snowflake? = Snowflake(9999L),
        referencedMessage: Message? = null,
        embeds: List<Embed> = emptyList(),
    ): Message {
        val author =
            if (authorIsNull) {
                null
            } else {
                val user = mockk<User>()
                every { user.username } returns username
                every { user.isBot } returns isBot
                every { user.id } returns Snowflake(authorId)
                user
            }

        val userData = mockk<UserData>()
        every { userData.username } returns username
        every { userData.id } returns (webhookId ?: Snowflake(authorId))

        val messageData = mockk<MessageData>()
        every { messageData.author } returns userData
        every { messageData.content } returns content
        every { messageData.id } returns Snowflake(id)

        val msg = mockk<Message>()
        val mockKord = mockk<Kord>(relaxed = true)
        if (selfId != null) {
            every { mockKord.selfId } returns selfId
        } else {
            every { mockKord.selfId } throws IllegalStateException("selfId unavailable")
        }
        every { msg.kord } returns mockKord
        every { msg.id } returns Snowflake(id)
        every { msg.channelId } returns Snowflake(channelId)
        every { msg.author } returns author
        every { msg.data } returns messageData
        every { msg.content } returns content
        every { msg.type } returns type
        every { msg.webhookId } returns webhookId
        every { msg.attachments } returns emptySet()
        every { msg.embeds } returns embeds
        every { msg.mentionedUserIds } returns mentionedUserIds
        every { msg.referencedMessage } returns referencedMessage
        every { msg.messageReference } returns null
        return msg
    }
}
