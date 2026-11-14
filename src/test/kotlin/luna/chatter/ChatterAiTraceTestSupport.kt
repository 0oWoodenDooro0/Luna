package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.MessageChannel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import luna.core.ai.AiTraceJson
import luna.core.ai.AiTraceRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object ChatterAiTraceTestSupport {
    val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-14T15:30:00Z"), ZoneOffset.UTC)

    val clientJson: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    fun createHttpClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(clientJson)
            }
        }

    fun readTraceLines(traceDir: Path): List<AiTraceRecord> {
        if (!Files.exists(traceDir)) return emptyList()
        return Files
            .list(traceDir)
            .use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                    .sorted()
                    .toList()
                    .flatMap { Files.readAllLines(it) }
            }.filter { it.isNotBlank() }
            .map { AiTraceJson.decodeFromString(AiTraceRecord.serializer(), it) }
    }

    fun createMockMessage(
        id: Long = 100L,
        channelId: Long = 200L,
        guildId: Long? = 300L,
        authorId: Long = 400L,
        username: String = "Alice",
        content: String = "Hello Luna, what do you think?",
        isBot: Boolean = false,
        type: MessageType = MessageType.Default,
        mentionedUserIds: Set<Snowflake> = emptySet(),
        attachments: Set<Attachment> = emptySet(),
        selfId: Snowflake = Snowflake(9999L),
        channel: MessageChannel =
            mockk<MessageChannel>(relaxed = true).also { ch ->
                coEvery { ch.type() } returns Unit
                coEvery { ch.createMessage(any<String>()) } returns mockk(relaxed = true)
            },
        channelThrows: Boolean = false,
    ): Message {
        val user =
            mockk<User>().apply {
                every { this@apply.username } returns username
                every { this@apply.isBot } returns isBot
                every { this@apply.id } returns Snowflake(authorId)
            }

        val userData =
            mockk<UserData>().apply {
                every { this@apply.username } returns username
                every { this@apply.id } returns Snowflake(authorId)
            }

        val messageData =
            mockk<MessageData>().apply {
                every { this@apply.author } returns userData
                every { this@apply.content } returns content
                every { this@apply.id } returns Snowflake(id)
                every { this@apply.guildId } returns
                    if (guildId != null) {
                        OptionalSnowflake.Value(Snowflake(guildId))
                    } else {
                        OptionalSnowflake.Missing
                    }
            }

        val mockKord =
            mockk<Kord>(relaxed = true).apply {
                every { this@apply.selfId } returns selfId
            }

        return mockk<Message>().apply {
            every { this@apply.kord } returns mockKord
            every { this@apply.id } returns Snowflake(id)
            every { this@apply.channelId } returns Snowflake(channelId)
            every { this@apply.author } returns user
            every { this@apply.data } returns messageData
            every { this@apply.content } returns content
            every { this@apply.type } returns type
            every { this@apply.webhookId } returns null
            every { this@apply.attachments } returns attachments
            every { this@apply.embeds } returns emptyList()
            every { this@apply.mentionedUserIds } returns mentionedUserIds
            every { this@apply.referencedMessage } returns null
            every { this@apply.messageReference } returns null
            if (channelThrows) {
                every { this@apply.channel } throws RuntimeException("Channel lookup failed")
            } else {
                every { this@apply.channel } returns channel
                coEvery { this@apply.getChannel() } returns channel
            }
        }
    }

    fun createMockImageAttachment(
        url: String = "https://cdn.discordapp.com/attachments/1/2/photo.png",
        mimeType: String = "image/png",
        filename: String = "photo.png",
    ): Attachment =
        mockk<Attachment>().apply {
            every { this@apply.url } returns url
            every { this@apply.contentType } returns mimeType
            every { this@apply.filename } returns filename
            every { this@apply.size } returns 1024
        }
}
