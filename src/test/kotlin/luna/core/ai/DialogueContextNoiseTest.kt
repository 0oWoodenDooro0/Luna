package luna.core.ai

import dev.kord.common.entity.MessageStickerType
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.StickerItem
import dev.kord.core.entity.User
import io.mockk.every
import io.mockk.mockk
import luna.core.BoundedSummaryRegistry
import luna.core.LunaWebhookRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueContextNoiseTest {
    private val selfId = Snowflake(999L)
    private val summaryRegistry = BoundedSummaryRegistry()
    private val engine = DiscordDialogueContextEngine(summaryRegistry = summaryRegistry)

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        summaryRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        LunaWebhookRegistry.clear()
        summaryRegistry.clear()
    }

    private fun mockMessage(
        id: Long = 1L,
        content: String = "hello",
        authorId: Long? = 100L,
        authorName: String = "Alice",
        isBot: Boolean = false,
        type: MessageType = MessageType.Default,
        webhookId: Long? = null,
        attachments: Set<Attachment> = emptySet(),
        stickers: List<StickerItem> = emptyList(),
        embeds: List<Embed> = emptyList(),
    ): Message {
        val message = mockk<Message>(relaxed = true)
        every { message.id } returns Snowflake(id)
        every { message.content } returns content
        every { message.type } returns type
        every { message.webhookId } returns webhookId?.let { Snowflake(it) }
        every { message.attachments } returns attachments
        every { message.stickers } returns stickers
        every { message.embeds } returns embeds
        every { message.kord.selfId } returns selfId

        if (authorId != null) {
            val author = mockk<User>(relaxed = true)
            every { author.id } returns Snowflake(authorId)
            every { author.username } returns authorName
            every { author.isBot } returns isBot
            every { message.author } returns author
        } else {
            every { message.author } returns null
        }

        return message
    }

    @Test
    fun `summary policy rejects third-party bots while chatter policy retains them`() {
        val botMsg = mockMessage(id = 10L, content = "MusicBot playing track", authorId = 200L, authorName = "MusicBot", isBot = true)

        val summaryEval = engine.evaluateNoise(botMsg, DialogueContextPolicy.summary(), selfId)
        assertTrue(summaryEval.isNoise, "Summary policy must reject third-party bot messages")
        assertEquals(DialogueNoiseReason.THIRD_PARTY_BOT, summaryEval.reason)

        val chatterEval = engine.evaluateNoise(botMsg, DialogueContextPolicy.chatter(), selfId)
        assertFalse(chatterEval.isNoise, "Chatter policy must retain third-party bot messages as dialogue context")
        assertTrue(chatterEval.speaker.isThirdPartyBot, "Speaker metadata should mark third-party bot")
        assertEquals("MusicBot", chatterEval.speaker.name)
    }

    @Test
    fun `both policies reject own-bot summary messages but retain own-bot conversational messages`() {
        val summaryMsg =
            mockMessage(
                id = 20L,
                content = "TL;DR: Everyone agreed on pizza",
                authorId = selfId.value.toLong(),
                authorName = "Luna",
                isBot = true,
            )
        val registeredSummaryMsg =
            mockMessage(
                id = 21L,
                content = "Normal text",
                authorId = selfId.value.toLong(),
                authorName = "Luna",
                isBot = true,
            )
        summaryRegistry.register(21L)
        val chatMsg =
            mockMessage(
                id = 22L,
                content = "I like pizza too!",
                authorId = selfId.value.toLong(),
                authorName = "Luna",
                isBot = true,
            )

        for (policy in listOf(DialogueContextPolicy.chatter(), DialogueContextPolicy.summary())) {
            val eval1 = engine.evaluateNoise(summaryMsg, policy, selfId)
            assertTrue(eval1.isNoise, "Policy $policy must reject own-bot TL;DR summary message")
            assertEquals(DialogueNoiseReason.BOT_SUMMARY, eval1.reason)

            val eval2 = engine.evaluateNoise(registeredSummaryMsg, policy, selfId)
            assertTrue(eval2.isNoise, "Policy $policy must reject registered summary message")
            assertEquals(DialogueNoiseReason.BOT_SUMMARY, eval2.reason)

            val eval3 = engine.evaluateNoise(chatMsg, policy, selfId)
            assertFalse(eval3.isNoise, "Policy $policy must retain own-bot non-summary message")
            assertEquals("Luna", eval3.speaker.name)
            assertTrue(eval3.speaker.isLunaBot, "Speaker metadata should identify Luna bot")
        }
    }

    @Test
    fun `webhook registry allows verified Luna webhooks and rejects unverified webhooks`() {
        val verifiedWebhookId = 555L
        val unverifiedWebhookId = 666L
        LunaWebhookRegistry.register(verifiedWebhookId)

        val verifiedMsg =
            mockMessage(
                id = 30L,
                content = "Replaced link",
                authorId = null,
                authorName = "Alice",
                isBot = true,
                webhookId = verifiedWebhookId,
            )
        val unverifiedMsg =
            mockMessage(
                id = 31L,
                content = "External hook",
                authorId = null,
                authorName = "GitHub",
                isBot = true,
                webhookId = unverifiedWebhookId,
            )

        for (policy in listOf(DialogueContextPolicy.chatter(), DialogueContextPolicy.summary())) {
            val verifiedEval = engine.evaluateNoise(verifiedMsg, policy, selfId)
            assertFalse(verifiedEval.isNoise, "Registered Luna webhook must be accepted under $policy")

            val unverifiedEval = engine.evaluateNoise(unverifiedMsg, policy, selfId)
            assertTrue(unverifiedEval.isNoise, "Unverified webhook must be rejected under $policy")
            assertEquals(DialogueNoiseReason.UNVERIFIED_WEBHOOK, unverifiedEval.reason)
        }
    }

    @Test
    fun `command prefix rules enforce chatter vs summary command semantics`() {
        val slashCmd = mockMessage(id = 40L, content = "/summarize")
        val chatInputCmd = mockMessage(id = 41L, content = "ignored", type = MessageType.ChatInputCommand)
        val bangLetterCmd = mockMessage(id = 42L, content = "!play song")
        val tildeCmd = mockMessage(id = 43L, content = "~help")
        val bangPunctuation = mockMessage(id = 44L, content = "!?!?")
        val bangDigit = mockMessage(id = 45L, content = "!123")

        val chatter = DialogueContextPolicy.chatter()
        val summary = DialogueContextPolicy.summary()

        assertTrue(engine.evaluateNoise(slashCmd, chatter, selfId).isNoise, "Chatter must reject / commands")
        assertTrue(engine.evaluateNoise(slashCmd, summary, selfId).isNoise, "Summary must reject / commands")
        assertTrue(engine.evaluateNoise(chatInputCmd, chatter, selfId).isNoise, "Chatter must reject ChatInputCommand")
        assertTrue(engine.evaluateNoise(chatInputCmd, summary, selfId).isNoise, "Summary must reject ChatInputCommand")
        assertTrue(engine.evaluateNoise(bangLetterCmd, chatter, selfId).isNoise, "Chatter must reject !play")
        assertTrue(engine.evaluateNoise(bangLetterCmd, summary, selfId).isNoise, "Summary must reject !play")

        assertTrue(engine.evaluateNoise(tildeCmd, chatter, selfId).isNoise, "Chatter must reject ~help")
        assertFalse(engine.evaluateNoise(tildeCmd, summary, selfId).isNoise, "Summary does not filter ~ prefix")

        assertFalse(engine.evaluateNoise(bangPunctuation, chatter, selfId).isNoise, "Chatter must not filter !?!?")
        assertFalse(engine.evaluateNoise(bangPunctuation, summary, selfId).isNoise, "Summary must not filter !?!?")

        assertTrue(engine.evaluateNoise(bangDigit, chatter, selfId).isNoise, "Chatter filters ! followed by digit")
        assertFalse(engine.evaluateNoise(bangDigit, summary, selfId).isNoise, "Summary only filters ! followed by letter")
    }

    @Test
    fun `system messages are rejected by both policies`() {
        val joinMsg = mockMessage(id = 50L, content = "", type = MessageType.UserJoin)
        val replyMsg = mockMessage(id = 51L, content = "Agreed", type = MessageType.Reply)

        for (policy in listOf(DialogueContextPolicy.chatter(), DialogueContextPolicy.summary())) {
            val joinEval = engine.evaluateNoise(joinMsg, policy, selfId)
            assertTrue(joinEval.isNoise, "System message UserJoin must be rejected")
            assertEquals(DialogueNoiseReason.SYSTEM_MESSAGE, joinEval.reason)

            val replyEval = engine.evaluateNoise(replyMsg, policy, selfId)
            assertFalse(replyEval.isNoise, "Reply message type must be accepted")
        }
    }

    @Test
    fun `sticker-only, custom-emoji-only, and embed-only blank messages follow policy flags`() {
        val sticker = mockk<StickerItem>(relaxed = true)
        every { sticker.name } returns "Wave"
        every { sticker.id } returns Snowflake(77L)
        every { sticker.formatType } returns MessageStickerType.PNG

        val stickerOnly = mockMessage(id = 60L, content = "", stickers = listOf(sticker))
        val customEmojiOnly = mockMessage(id = 61L, content = "<:pepe:123456>")
        val embedOnly = mockMessage(id = 62L, content = "", embeds = listOf(mockk(relaxed = true)))
        val attachmentOnly = mockMessage(id = 63L, content = "", attachments = setOf(mockk(relaxed = true)))
        val trulyEmpty = mockMessage(id = 64L, content = "   ")

        val chatter = DialogueContextPolicy.chatter()
        val summary = DialogueContextPolicy.summary()

        assertFalse(engine.evaluateNoise(stickerOnly, chatter, selfId).isNoise, "Chatter retains sticker-only messages")
        assertTrue(engine.evaluateNoise(stickerOnly, summary, selfId).isNoise, "Summary rejects sticker-only messages")

        assertFalse(engine.evaluateNoise(customEmojiOnly, chatter, selfId).isNoise, "Chatter retains custom-emoji-only messages")
        assertTrue(engine.evaluateNoise(customEmojiOnly, summary, selfId).isNoise, "Summary rejects custom-emoji-only messages")

        assertFalse(engine.evaluateNoise(embedOnly, chatter, selfId).isNoise, "Chatter retains embed-only blank messages")
        assertTrue(
            engine.evaluateNoise(embedOnly, summary, selfId).isNoise,
            "Summary rejects embed-only blank messages without attachments",
        )

        assertFalse(engine.evaluateNoise(attachmentOnly, chatter, selfId).isNoise, "Chatter retains attachment-only messages")
        assertFalse(engine.evaluateNoise(attachmentOnly, summary, selfId).isNoise, "Summary retains attachment-only messages")

        assertTrue(engine.evaluateNoise(trulyEmpty, chatter, selfId).isNoise, "Chatter rejects truly empty messages")
        assertTrue(engine.evaluateNoise(trulyEmpty, summary, selfId).isNoise, "Summary rejects truly empty messages")
    }
}
