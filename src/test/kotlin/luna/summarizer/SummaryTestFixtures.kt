package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.StickerItem
import dev.kord.core.entity.User
import dev.kord.core.event.message.ReactionAddEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Instant

/**
 * Shared test fixtures and builder helpers for Summary pipeline tests.
 */
object SummaryTestFixtures {
    fun createMockMessage(
        id: Long,
        username: String,
        content: String = "",
        isBot: Boolean = false,
        timestamp: Instant = Instant.fromEpochMilliseconds(id * 1000),
        type: MessageType = MessageType.Default,
        stickers: List<StickerItem> = emptyList(),
        attachments: Set<Attachment> = emptySet(),
        webhookId: Snowflake? = null,
        embeds: List<dev.kord.core.entity.Embed> = emptyList(),
        authorIsNull: Boolean = false,
        authorId: Snowflake? = null,
    ): Message {
        val resolvedAuthorId = authorId ?: webhookId ?: Snowflake(id)
        val author =
            if (authorIsNull) {
                null
            } else {
                val mockUser = mockk<User>()
                every { mockUser.username } returns username
                every { mockUser.isBot } returns isBot
                every { mockUser.id } returns resolvedAuthorId
                mockUser
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
        every { msg.author } returns author
        every { msg.data } returns messageData
        every { msg.content } returns content
        every { msg.timestamp } returns timestamp
        every { msg.type } returns type
        every { msg.stickers } returns stickers
        every { msg.attachments } returns attachments
        every { msg.webhookId } returns webhookId
        every { msg.embeds } returns embeds
        return msg
    }

    fun createMockEmbed(
        title: String? = null,
        description: String? = null,
    ): dev.kord.core.entity.Embed {
        val embed = mockk<dev.kord.core.entity.Embed>()
        every { embed.title } returns title
        every { embed.description } returns description
        return embed
    }

    fun createMockAttachment(
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

    fun createMockReactionEvent(
        targetMessage: Message,
        emoji: ReactionEmoji = ReactionEmoji.Unicode("📝"),
        userId: Long = 55L,
        userIsBot: Boolean = false,
    ): ReactionAddEvent {
        val user = mockk<User>()
        every { user.isBot } returns userIsBot

        val event = mockk<ReactionAddEvent>()
        every { event.emoji } returns emoji
        every { event.userId } returns Snowflake(userId)
        every { event.messageId } returns targetMessage.id
        coEvery { event.getUserOrNull() } returns user
        coEvery { event.getMessageOrNull() } returns targetMessage
        return event
    }
}
