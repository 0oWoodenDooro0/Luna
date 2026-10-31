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
    ): Message {
        val author = mockk<User>()
        every { author.username } returns username
        every { author.isBot } returns isBot

        val msg = mockk<Message>()
        every { msg.id } returns Snowflake(id)
        every { msg.author } returns author
        every { msg.content } returns content
        every { msg.timestamp } returns timestamp
        every { msg.type } returns type
        every { msg.stickers } returns stickers
        every { msg.attachments } returns attachments
        every { msg.webhookId } returns webhookId
        return msg
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
