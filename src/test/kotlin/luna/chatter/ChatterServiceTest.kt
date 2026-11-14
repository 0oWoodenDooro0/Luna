package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterServiceTest {
    private val fixture = ChatterServiceTestSupport()

    @BeforeEach
    fun setUp() = fixture.setUp()

    @AfterEach
    fun tearDown() = fixture.tearDown()

    @Test
    fun `direct mention to bot triggers immediate reply without calling decision client and bypasses cooldown`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 500L
            fixture.cooldownCache.record(channelId, fixture.simulatedTime)

            val message =
                fixture.createMockMessage(
                    id = 101L,
                    channelId = channelId,
                    username = "Alice",
                    content = "<@9999> what do you think about Kotlin coroutines?",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            val replyText = "Coroutines are awesome and lightweight!"

            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns replyText

            val result = fixture.service.processMessage(message)

            assertTrue(result, "Expected processMessage to reply to direct bot mention even during cooldown")
            coVerify(exactly = 0) { fixture.decisionClient.decide(any(), any()) }
            coVerify(exactly = 1) { fixture.channel.type() }
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(
                    match<ChatterInput> {
                        it.isDirectMention &&
                            it.transcript.contains("<@9999> what do you think about Kotlin coroutines?")
                    },
                )
            }
            coVerify(exactly = 1) { fixture.channel.createMessage(replyText) }
        }

    @Test
    fun `short ambient messages enter participation decision instead of being pre-filtered`() =
        runTest {
            val channelId = 500L
            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.2)

            for ((idx, shortText) in listOf("?", "草", "真的嗎", "ok").withIndex()) {
                val msg =
                    fixture.createMockMessage(
                        id = 102L + idx,
                        channelId = channelId,
                        username = "Bob",
                        content = shortText,
                    )
                val result = fixture.service.processMessage(msg)
                assertFalse(result)
                coVerify(exactly = 1) {
                    fixture.decisionClient.decide(match { it.contains("[Bob]: $shortText") }, any())
                }
            }
        }

    @Test
    fun `embed-only message enters participation decision`() =
        runTest {
            val embed = mockk<Embed>()
            every { embed.title } returns null
            every { embed.description } returns null
            val message =
                fixture.createMockMessage(
                    id = 120L,
                    channelId = 500L,
                    username = "Charlie",
                    content = "",
                    embeds = listOf(embed),
                )
            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.3)

            val result = fixture.service.processMessage(message)

            assertFalse(result)
            coVerify(exactly = 1) {
                fixture.decisionClient.decide(match { it.contains("[Charlie]: [Embed]") }, any())
            }
        }

    @Test
    fun `plain-text luna mention, reply to luna, and unresolved selfId route through participation decision with raw text`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.4)

            // 1. Plain-text @luna
            val plainLunaMsg =
                fixture.createMockMessage(
                    id = 130L,
                    channelId = 500L,
                    username = "Alice",
                    content = "Hey @luna what's up?",
                    selfId = botSelfId,
                )
            assertFalse(fixture.service.processMessage(plainLunaMsg))
            coVerify(exactly = 1) {
                fixture.decisionClient.decide(match { it.contains("[Alice]: Hey @luna what's up?") }, any())
            }

            // 2. Discord reply to Luna
            val lunaMsg = mockk<Message>(relaxed = true)
            val lunaAuthor = mockk<User>(relaxed = true)
            every { lunaAuthor.id } returns botSelfId
            every { lunaAuthor.username } returns "Luna"
            every { lunaMsg.id } returns Snowflake(129L)
            every { lunaMsg.author } returns lunaAuthor
            every { lunaMsg.content } returns "Earlier bot reply"

            val replyToLuna =
                fixture.createMockMessage(
                    id = 131L,
                    channelId = 500L,
                    username = "Bob",
                    content = "Sounds good",
                    selfId = botSelfId,
                    referencedMessage = lunaMsg,
                )
            assertFalse(fixture.service.processMessage(replyToLuna))
            coVerify(exactly = 1) {
                fixture.decisionClient.decide(match { it.contains("[Bob 回覆 Luna「Earlier bot reply」]: Sounds good") }, any())
            }

            // 3. Unresolved selfId (null) does not bypass decision even with <@9999>
            val unresolvedSelfIdMsg =
                fixture.createMockMessage(
                    id = 132L,
                    channelId = 500L,
                    username = "Dave",
                    content = "<@9999> hello",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = null,
                )
            assertFalse(fixture.service.processMessage(unresolvedSelfIdMsg))
            coVerify(exactly = 1) {
                fixture.decisionClient.decide(match { it.contains("[Dave]: <@9999> hello") }, any())
            }
        }

    @Test
    fun `structural noise rejections do not invoke participation decision`() =
        runTest {
            val cmdMsg = fixture.createMockMessage(id = 140L, content = "/help")
            val prefixCmdMsg = fixture.createMockMessage(id = 141L, content = "!ping")
            val systemMsg = fixture.createMockMessage(id = 142L, content = "Pinned", type = MessageType.ChannelPinnedMessage)
            val botMsg = fixture.createMockMessage(id = 143L, content = "Bot msg", isBot = true)
            val webhookMsg = fixture.createMockMessage(id = 144L, content = "Webhook msg", isBot = true, webhookId = Snowflake(8888L))
            val blankMsg = fixture.createMockMessage(id = 145L, content = "   ")

            for (msg in listOf(cmdMsg, prefixCmdMsg, systemMsg, botMsg, webhookMsg, blankMsg)) {
                assertFalse(fixture.service.processMessage(msg))
            }

            coVerify(exactly = 0) { fixture.decisionClient.decide(any(), any()) }
        }

    @Test
    fun `successful end-to-end flow with valid message, typing indicator and reply`() =
        runTest {
            val channelId = 500L
            val message = fixture.createMockMessage(id = 101L, channelId = channelId, username = "Alice", content = "Does anyone know when Luna updates?")
            val replyText = "Luna updates frequently! Check out the releases page."

            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.95)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns replyText

            val result = fixture.service.processMessage(message)

            assertTrue(result, "Expected processMessage to return true when reply is successfully sent")
            coVerify(exactly = 1) { fixture.decisionClient.decide(match { it.contains("Alice") && it.contains("Luna updates") }, any()) }
            coVerify(exactly = 1) { fixture.channel.type() }
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(
                    match<ChatterInput> { !it.isDirectMention && it.transcript.contains("[Alice]: Does anyone know when Luna updates?") },
                )
            }
            coVerify(exactly = 1) { fixture.channel.createMessage(replyText) }
            assertTrue(fixture.cooldownCache.isOnCooldown(channelId), "Channel cooldown must be recorded after sending reply")
        }

    @Test
    fun `decision client returning false results in no typing indicator, no gemini call, and no message sent`() =
        runTest {
            val channelId = 501L
            val message = fixture.createMockMessage(id = 102L, channelId = channelId, username = "Bob", content = "Good morning")

            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.2)

            val result = fixture.service.processMessage(message)

            assertFalse(result, "Expected processMessage to return false when decision client rejects participation")
            coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any()) }
            coVerify(exactly = 0) { fixture.channel.type() }
            coVerify(exactly = 0) { fixture.chatterGenerator.generateReply(any<ChatterInput>()) }
            coVerify(exactly = 0) { fixture.channel.createMessage(any<String>()) }
            assertFalse(fixture.cooldownCache.isOnCooldown(channelId), "Cooldown should not be recorded when not participating")
        }

    @Test
    fun `gemini returning null or error fails closed gracefully without crashing`() =
        runTest {
            val channelId = 505L
            val message = fixture.createMockMessage(id = 109L, channelId = channelId, content = "Tell me something cool")

            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns null

            val resultNull = fixture.service.processMessage(message)
            assertFalse(resultNull, "Expected failure when Gemini returns null")
            coVerify(exactly = 0) { fixture.channel.createMessage(any<String>()) }
            assertFalse(fixture.cooldownCache.isOnCooldown(channelId))

            // Test Gemini throwing exception
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } throws RuntimeException("Network timeout")
            val resultException = fixture.service.processMessage(message)
            assertFalse(resultException, "Expected graceful failure when Gemini throws exception")
            coVerify(exactly = 0) { fixture.channel.createMessage(any<String>()) }
            assertFalse(fixture.cooldownCache.isOnCooldown(channelId))
        }
}
