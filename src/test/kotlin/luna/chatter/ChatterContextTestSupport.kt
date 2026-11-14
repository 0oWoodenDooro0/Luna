package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import io.mockk.every
import io.mockk.mockk

internal fun createMockAttachment(
    url: String,
    contentType: String?,
    filename: String = "image.png",
    size: Int = 512 * 1024,
): Attachment {
    val attachment = mockk<Attachment>()
    every { attachment.url } returns url
    every { attachment.contentType } returns contentType
    every { attachment.filename } returns filename
    every { attachment.size } returns size
    return attachment
}

internal fun createMockEmbed(
    title: String? = null,
    description: String? = null,
): Embed {
    val embed = mockk<Embed>()
    every { embed.title } returns title
    every { embed.description } returns description
    return embed
}

internal fun createMockMessage(
    id: Long,
    username: String = "Alice",
    content: String = "",
    isBot: Boolean = false,
    authorId: Snowflake? = null,
    webhookId: Snowflake? = null,
    type: MessageType = MessageType.Default,
    attachments: Set<Attachment> = emptySet(),
    embeds: List<Embed> = emptyList(),
    referencedMessage: Message? = null,
    kordUnavailable: Boolean = false,
): Message {
    val resolvedAuthorId = authorId ?: webhookId ?: Snowflake(id)
    val author = mockk<User>()
    every { author.username } returns username
    every { author.isBot } returns isBot
    every { author.id } returns resolvedAuthorId

    val userData = mockk<UserData>()
    every { userData.username } returns username
    every { userData.id } returns resolvedAuthorId

    val messageData = mockk<MessageData>()
    every { messageData.author } returns userData
    every { messageData.content } returns content
    every { messageData.id } returns Snowflake(id)

    val message = mockk<Message>(relaxed = true)
    if (kordUnavailable) {
        every { message.kord } throws IllegalStateException("kord unavailable")
    }
    every { message.id } returns Snowflake(id)
    every { message.channelId } returns Snowflake(100L)
    every { message.author } returns author
    every { message.data } returns messageData
    every { message.content } returns content
    every { message.type } returns type
    every { message.webhookId } returns webhookId
    every { message.attachments } returns attachments
    every { message.embeds } returns embeds
    every { message.mentionedUserIds } returns emptySet()
    every { message.referencedMessage } returns referencedMessage
    every { message.messageReference } returns null
    return message
}
