package luna.chatter

import dev.kord.common.entity.AllowedMentionType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalBoolean
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.ApplicationEmoji
import dev.kord.core.entity.Guild
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.rest.json.request.MultipartMessageCreateRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

abstract class ChatterChannelPortContractTest {
    protected abstract fun createScenario(
        channelId: Snowflake = Snowflake(100L),
        anchorId: Snowflake = Snowflake(10L),
        beforeMessagesNewestFirst: List<Message> = emptyList(),
        afterMessages: List<Message> = emptyList(),
        guildEmojis: List<GuildEmoji>? = emptyList(),
        applicationEmojis: List<ApplicationEmoji> = emptyList(),
        beforeError: Throwable? = null,
        afterError: Throwable? = null,
        typingError: Throwable? = null,
        sendError: Throwable? = null,
        replyError: Throwable? = null,
        reactionError: Throwable? = null,
        guildEmojisError: Throwable? = null,
        applicationEmojisError: Throwable? = null,
    ): PortScenario

    protected data class PortScenario(
        val port: ChatterChannelPort,
        val anchor: Message,
        val assertTypingTriggered: () -> Unit,
        val assertPlainMessageSent: (String) -> Unit,
        val assertReplySent: (Snowflake, String) -> Unit,
        val assertReactionAdded: (ReactionEmoji) -> Unit,
    )

    protected fun mockMessage(
        id: Long,
        channelId: Long = 100L,
        content: String = "msg-$id",
    ): Message =
        mockk(relaxed = true) {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.channelId } returns Snowflake(channelId)
            every { this@mockk.content } returns content
        }

    protected fun mockGuildEmoji(
        id: Long,
        name: String?,
        isAvailable: Boolean,
        isAnimated: Boolean = false,
    ): GuildEmoji =
        mockk {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.name } returns name
            every { this@mockk.isAvailable } returns isAvailable
            every { this@mockk.isAnimated } returns isAnimated
        }

    protected fun mockApplicationEmoji(
        id: Long,
        name: String?,
        isAvailable: Boolean,
        isAnimated: Boolean = false,
    ): ApplicationEmoji =
        mockk {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.name } returns name
            every { this@mockk.isAvailable } returns isAvailable
            every { this@mockk.isAnimated } returns isAnimated
        }

    @Test
    fun `fetchMessagesBefore returns chronological oldest-first order`() =
        runTest {
            val oldest = mockMessage(1L)
            val middle = mockMessage(2L)
            val newest = mockMessage(3L)
            val scenario = createScenario(beforeMessagesNewestFirst = listOf(newest, middle, oldest))

            val result = scenario.port.fetchMessagesBefore(scenario.anchor, limit = 3)

            assertEquals(listOf(oldest.id, middle.id, newest.id), result.map { it.id })
        }

    @Test
    fun `fetchMessagesBefore returns emptyList when limit is zero or negative`() =
        runTest {
            val scenario = createScenario(beforeMessagesNewestFirst = listOf(mockMessage(1L)))

            assertTrue(scenario.port.fetchMessagesBefore(scenario.anchor, limit = 0).isEmpty()) {
                "Expected empty list for limit=0"
            }
            assertTrue(scenario.port.fetchMessagesBefore(scenario.anchor, limit = -5).isEmpty()) {
                "Expected empty list for negative limit"
            }
        }

    @Test
    fun `fetchMessagesAfter returns messages strictly after anchor and emptyList when limit is non-positive`() =
        runTest {
            val anchorId = Snowflake(10L)
            val after1 = mockMessage(11L)
            val after2 = mockMessage(12L)
            val scenario =
                createScenario(
                    anchorId = anchorId,
                    afterMessages = listOf(after1, after2),
                )

            val result = scenario.port.fetchMessagesAfter(scenario.anchor, limit = 10)
            assertEquals(listOf(after1.id, after2.id), result.map { it.id })
            assertTrue(scenario.port.fetchMessagesAfter(scenario.anchor, limit = 0).isEmpty()) {
                "Expected empty list for after limit=0"
            }
            assertTrue(scenario.port.fetchMessagesAfter(scenario.anchor, limit = -1).isEmpty()) {
                "Expected empty list for negative after limit"
            }
        }

    @Test
    fun `triggerTyping invokes channel typing`() =
        runTest {
            val scenario = createScenario()

            scenario.port.triggerTyping(scenario.anchor)

            scenario.assertTypingTriggered()
        }

    @Test
    fun `sendMessage sends plain channel message`() =
        runTest {
            val scenario = createScenario()

            scenario.port.sendMessage(scenario.anchor, "hello channel")

            scenario.assertPlainMessageSent("hello channel")
        }

    @Test
    fun `sendReply anchors messageReference to target message id`() =
        runTest {
            val targetId = Snowflake(42L)
            val scenario = createScenario(anchorId = targetId)

            scenario.port.sendReply(scenario.anchor, "reply text")

            scenario.assertReplySent(targetId, "reply text")
        }

    @Test
    fun `addReaction adds reaction to target message`() =
        runTest {
            val emoji = ReactionEmoji.Unicode("🔥")
            val scenario = createScenario()

            scenario.port.addReaction(scenario.anchor, emoji)

            scenario.assertReactionAdded(emoji)
        }

    @Test
    fun `listGuildCustomEmojis filters unavailable or blank emojis and returns empty outside guilds`() =
        runTest {
            val valid = mockGuildEmoji(1L, "wave", isAvailable = true)
            val unavailable = mockGuildEmoji(2L, "broken", isAvailable = false)
            val blank = mockGuildEmoji(3L, "  ", isAvailable = true)
            val nullName = mockGuildEmoji(4L, null, isAvailable = true)

            val guildScenario =
                createScenario(guildEmojis = listOf(valid, unavailable, blank, nullName))
            val dmScenario = createScenario(guildEmojis = null)

            val guildResult = guildScenario.port.listGuildCustomEmojis(guildScenario.anchor)
            val dmResult = dmScenario.port.listGuildCustomEmojis(dmScenario.anchor)

            assertEquals(listOf(Snowflake(1L)), guildResult.map { it.id })
            assertTrue(dmResult.isEmpty()) { "Expected empty guild emoji list outside guilds" }
        }

    @Test
    fun `listApplicationEmojis filters unavailable or blank emojis and preserves animation flag`() =
        runTest {
            val validStatic = mockApplicationEmoji(11L, "luna_smug", isAvailable = true, isAnimated = false)
            val validAnimated = mockApplicationEmoji(12L, "luna_spin", isAvailable = true, isAnimated = true)
            val unavailable = mockApplicationEmoji(13L, "broken_app", isAvailable = false, isAnimated = false)
            val blank = mockApplicationEmoji(14L, "   ", isAvailable = true, isAnimated = false)
            val nullName = mockApplicationEmoji(15L, null, isAvailable = true, isAnimated = false)

            val scenario =
                createScenario(
                    applicationEmojis = listOf(validStatic, validAnimated, unavailable, blank, nullName),
                )

            val result = scenario.port.listApplicationEmojis(scenario.anchor)

            assertEquals(
                listOf(
                    GuildCustomEmoji(id = Snowflake(11L), name = "luna_smug", isAnimated = false),
                    GuildCustomEmoji(id = Snowflake(12L), name = "luna_spin", isAnimated = true),
                ),
                result,
            )
        }

    @Test
    fun `all eight operations propagate runtime exceptions and CancellationException unchanged`() =
        runTest {
            val beforeErr = IllegalStateException("before boom")
            val afterErr = IllegalStateException("after boom")
            val typingErr = IllegalStateException("typing boom")
            val sendErr = IllegalStateException("send boom")
            val replyErr = IllegalStateException("reply boom")
            val reactionErr = IllegalStateException("reaction boom")
            val emojiErr = IllegalStateException("emoji boom")
            val appEmojiErr = IllegalStateException("app emoji boom")

            assertSame(
                beforeErr,
                assertThrows<IllegalStateException> {
                    createScenario(beforeError = beforeErr).let { it.port.fetchMessagesBefore(it.anchor, 5) }
                },
            )
            assertSame(
                afterErr,
                assertThrows<IllegalStateException> {
                    createScenario(afterError = afterErr).let { it.port.fetchMessagesAfter(it.anchor, 5) }
                },
            )
            assertSame(
                typingErr,
                assertThrows<IllegalStateException> {
                    createScenario(typingError = typingErr).let { it.port.triggerTyping(it.anchor) }
                },
            )
            assertSame(
                sendErr,
                assertThrows<IllegalStateException> {
                    createScenario(sendError = sendErr).let { it.port.sendMessage(it.anchor, "x") }
                },
            )
            assertSame(
                replyErr,
                assertThrows<IllegalStateException> {
                    createScenario(replyError = replyErr).let { it.port.sendReply(it.anchor, "x") }
                },
            )
            assertSame(
                reactionErr,
                assertThrows<IllegalStateException> {
                    createScenario(reactionError = reactionErr).let {
                        it.port.addReaction(it.anchor, ReactionEmoji.Unicode("👍"))
                    }
                },
            )
            assertSame(
                emojiErr,
                assertThrows<IllegalStateException> {
                    createScenario(guildEmojisError = emojiErr).let { it.port.listGuildCustomEmojis(it.anchor) }
                },
            )
            assertSame(
                appEmojiErr,
                assertThrows<IllegalStateException> {
                    createScenario(applicationEmojisError = appEmojiErr).let { it.port.listApplicationEmojis(it.anchor) }
                },
            )

            val cancelErr = CancellationException("cancel boom")
            assertThrows<CancellationException> {
                createScenario(sendError = cancelErr).let { it.port.sendMessage(it.anchor, "x") }
            }
            assertThrows<CancellationException> {
                createScenario(applicationEmojisError = cancelErr).let { it.port.listApplicationEmojis(it.anchor) }
            }
        }
}

class KordChatterChannelAdapterContractTest : ChatterChannelPortContractTest() {
    override fun createScenario(
        channelId: Snowflake,
        anchorId: Snowflake,
        beforeMessagesNewestFirst: List<Message>,
        afterMessages: List<Message>,
        guildEmojis: List<GuildEmoji>?,
        applicationEmojis: List<ApplicationEmoji>,
        beforeError: Throwable?,
        afterError: Throwable?,
        typingError: Throwable?,
        sendError: Throwable?,
        replyError: Throwable?,
        reactionError: Throwable?,
        guildEmojisError: Throwable?,
        applicationEmojisError: Throwable?,
    ): PortScenario {
        val channel = mockk<MessageChannel>(relaxed = true)
        val anchor = mockk<Message>(relaxed = true)
        every { anchor.id } returns anchorId
        every { anchor.channelId } returns channelId
        every { anchor.channel } returns channel
        every { channel.id } returns channelId
        every { channel.kord } returns anchor.kord

        if (beforeError != null) {
            every { channel.getMessagesBefore(anchorId, any()) } throws beforeError
        } else {
            every { channel.getMessagesBefore(anchorId, any()) } returns flowOf(*beforeMessagesNewestFirst.toTypedArray())
        }

        if (afterError != null) {
            every { channel.getMessagesAfter(anchorId, any()) } throws afterError
        } else {
            every { channel.getMessagesAfter(anchorId, any()) } returns flowOf(*afterMessages.toTypedArray())
        }

        if (typingError != null) {
            coEvery { channel.type() } throws typingError
        } else {
            coEvery { channel.type() } returns Unit
        }

        val sentContents = mutableListOf<String>()
        val capturedReplies = mutableListOf<Pair<Snowflake?, String>>()
        val restError = sendError ?: replyError
        if (restError != null) {
            coEvery {
                anchor.kord.rest.channel
                    .createMessage(channelId, any<MultipartMessageCreateRequest>())
            } throws restError
        } else {
            coEvery {
                anchor.kord.rest.channel
                    .createMessage(channelId, any<MultipartMessageCreateRequest>())
            } answers {
                val req = secondArg<MultipartMessageCreateRequest>().request
                val refId =
                    req.messageReference.value
                        ?.id
                        ?.value
                val text = req.content.value.orEmpty()
                if (refId != null) {
                    capturedReplies += refId to text
                } else {
                    sentContents += text
                }
                mockk(relaxed = true)
            }
        }

        if (reactionError != null) {
            coEvery { anchor.addReaction(any<ReactionEmoji>()) } throws reactionError
        } else {
            coEvery { anchor.addReaction(any<ReactionEmoji>()) } returns Unit
        }

        if (guildEmojisError != null) {
            coEvery { anchor.getGuildOrNull() } throws guildEmojisError
        } else if (guildEmojis == null) {
            coEvery { anchor.getGuildOrNull() } returns null
        } else {
            val guild = mockk<Guild>()
            every { guild.emojis } returns flowOf(*guildEmojis.toTypedArray())
            coEvery { anchor.getGuildOrNull() } returns guild
        }

        if (applicationEmojisError != null) {
            every { anchor.kord.emojis } returns flow { throw applicationEmojisError }
        } else {
            every { anchor.kord.emojis } returns flowOf(*applicationEmojis.toTypedArray())
        }

        return PortScenario(
            port = KordChatterChannelAdapter,
            anchor = anchor,
            assertTypingTriggered = { coVerify(exactly = 1) { channel.type() } },
            assertPlainMessageSent = { expected ->
                assertTrue(sentContents.contains(expected)) { "Expected sentContents to contain '$expected', got $sentContents" }
            },
            assertReplySent = { expectedTargetId, expectedContent ->
                assertTrue(capturedReplies.any { it.first == expectedTargetId && it.second == expectedContent }) {
                    "Expected reply referencing $expectedTargetId with '$expectedContent', got $capturedReplies"
                }
            },
            assertReactionAdded = { expectedEmoji ->
                coVerify(exactly = 1) { anchor.addReaction(expectedEmoji) }
            },
        )
    }

    @Test
    fun `sendMessage and sendReply enforce allowedMentions for Users only and block everyone, here, and Roles`() =
        runTest {
            val channelId = Snowflake(100L)
            val anchorId = Snowflake(10L)
            val channel = mockk<MessageChannel>(relaxed = true)
            val anchor = mockk<Message>(relaxed = true)
            every { anchor.id } returns anchorId
            every { anchor.channelId } returns channelId
            every { anchor.channel } returns channel
            every { channel.id } returns channelId
            every { channel.kord } returns anchor.kord

            val capturedRequests = mutableListOf<MultipartMessageCreateRequest>()
            coEvery {
                anchor.kord.rest.channel
                    .createMessage(channelId, any<MultipartMessageCreateRequest>())
            } answers {
                capturedRequests += secondArg<MultipartMessageCreateRequest>()
                mockk(relaxed = true)
            }

            KordChatterChannelAdapter.sendMessage(anchor, "@everyone @here <@&999> <@123>")
            KordChatterChannelAdapter.sendReply(anchor, "@everyone @here <@&999> <@123>")

            assertEquals(2, capturedRequests.size)

            val plainAllowed = capturedRequests[0].request.allowedMentions.value
            assertEquals(listOf(AllowedMentionType.UserMentions), plainAllowed?.parse)
            assertEquals(OptionalBoolean.Missing, plainAllowed?.repliedUser)

            val replyAllowed = capturedRequests[1].request.allowedMentions.value
            assertEquals(listOf(AllowedMentionType.UserMentions), replyAllowed?.parse)
            assertEquals(OptionalBoolean.Value(true), replyAllowed?.repliedUser)
        }
}

class FakeChatterChannelPortContractTest : ChatterChannelPortContractTest() {
    override fun createScenario(
        channelId: Snowflake,
        anchorId: Snowflake,
        beforeMessagesNewestFirst: List<Message>,
        afterMessages: List<Message>,
        guildEmojis: List<GuildEmoji>?,
        applicationEmojis: List<ApplicationEmoji>,
        beforeError: Throwable?,
        afterError: Throwable?,
        typingError: Throwable?,
        sendError: Throwable?,
        replyError: Throwable?,
        reactionError: Throwable?,
        guildEmojisError: Throwable?,
        applicationEmojisError: Throwable?,
    ): PortScenario {
        val fake =
            FakeChatterChannelPort().apply {
                this.historyMessages = beforeMessagesNewestFirst.reversed()
                this.afterMessages = afterMessages
                this.guildEmojis = guildEmojis.orEmpty()
                this.applicationEmojis =
                    applicationEmojis.mapNotNull { emoji ->
                        val emojiName = emoji.name?.takeIf { it.isNotBlank() }
                        if (emoji.isAvailable && emojiName != null) {
                            GuildCustomEmoji(id = emoji.id, name = emojiName, isAnimated = emoji.isAnimated)
                        } else {
                            null
                        }
                    }
                this.historyError = beforeError
                this.afterMessagesError = afterError
                this.typingError = typingError
                this.sendError = sendError
                this.replyError = replyError
                this.reactionError = reactionError
                this.guildEmojisError = guildEmojisError
                this.applicationEmojisError = applicationEmojisError
            }
        val anchor = mockMessage(anchorId.value.toLong(), channelId = channelId.value.toLong())

        return PortScenario(
            port = fake,
            anchor = anchor,
            assertTypingTriggered = {
                assertEquals(1, fake.typingCount)
            },
            assertPlainMessageSent = { expected ->
                assertEquals(listOf(expected), fake.sentMessages.map { it.content })
            },
            assertReplySent = { expectedTargetId, expectedContent ->
                assertEquals(1, fake.sentReplies.size)
                assertEquals(expectedTargetId, fake.sentReplies.single().targetMessageId)
                assertEquals(expectedContent, fake.sentReplies.single().content)
            },
            assertReactionAdded = { expectedEmoji ->
                assertEquals(listOf(expectedEmoji), fake.addedReactions.map { it.emoji })
            },
        )
    }
}
