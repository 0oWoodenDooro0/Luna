package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Instant

internal object ConversationSliceTestSupport {
    fun createMockAttachment(
        url: String,
        contentType: String?,
        filename: String,
        size: Int = 1024 * 1024,
    ): Attachment {
        val attachment = mockk<Attachment>()
        every { attachment.url } returns url
        every { attachment.contentType } returns contentType
        every { attachment.filename } returns filename
        every { attachment.size } returns size
        return attachment
    }

    fun createMockMessage(
        id: Long,
        username: String,
        content: String,
        isBot: Boolean = false,
        timestamp: Instant = Instant.fromEpochMilliseconds(id * 1000),
        attachments: Set<Attachment> = emptySet(),
        type: MessageType = MessageType.Default,
        referencedMessage: Message? = null,
        authorId: Snowflake? = null,
        webhookId: Snowflake? = null,
        embeds: List<dev.kord.core.entity.Embed> = emptyList(),
        authorIsNull: Boolean = false,
    ): Message {
        val author =
            if (authorIsNull) {
                null
            } else {
                mockk<User>().also {
                    every { it.username } returns username
                    every { it.isBot } returns isBot
                    every { it.id } returns (authorId ?: Snowflake(id))
                }
            }

        val message = mockk<Message>()
        every { message.id } returns Snowflake(id)
        every { message.author } returns author
        every { message.content } returns content
        every { message.timestamp } returns timestamp
        every { message.attachments } returns attachments
        every { message.type } returns type
        every { message.stickers } returns emptyList()
        every { message.embeds } returns embeds
        every { message.webhookId } returns webhookId
        every { message.data.author.username } returns username
        every { message.data.webhookId.value } returns webhookId
        every { message.referencedMessage } returns referencedMessage
        every { message.messageReference } returns null
        return message
    }
}
