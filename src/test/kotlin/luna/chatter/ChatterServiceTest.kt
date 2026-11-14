package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.event.Event
import dev.kord.core.event.message.MessageCreateEvent
import dev.socialpeek.model.Author
import dev.socialpeek.model.Comment
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import luna.plugin.DefaultPluginContext
import luna.plugin.LunaPlugin
import luna.plugin.PluginLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatterServiceTest {
    private var simulatedTime: Long = 100_000L
    private lateinit var cooldownCache: BoundedChannelCooldownCache
    private lateinit var filter: ChatterNoiseFilter
    private lateinit var decisionClient: GeminiDecisionClient
    private lateinit var chatterGenerator: ChatterGenerator
    private lateinit var channel: MessageChannel
    private lateinit var contextCollector: ChatterContextCollector
    private lateinit var service: ChatterService

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        simulatedTime = 100_000L
        cooldownCache = BoundedChannelCooldownCache(timeSource = { simulatedTime })
        filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
        decisionClient = mockk(relaxed = true)
        chatterGenerator = mockk(relaxed = true)
        channel = mockk(relaxed = true)
        contextCollector =
            ChatterContextCollector(
                noiseFilter = filter,
                peekResolver = { null },
            )

        coEvery { channel.type() } returns Unit
        coEvery { channel.createMessage(any<String>()) } returns mockk(relaxed = true)

        service =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = chatterGenerator,
                cooldownCache = cooldownCache,
                historyFetcher = { _, _ -> emptyList() },
                contextCollector = contextCollector,
            )
    }

    @AfterEach
    fun tearDown() {
        LunaWebhookRegistry.clear()
    }

    private fun createMockAttachment(
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

    private fun createMockEmbed(
        title: String? = null,
        description: String? = null,
    ): Embed {
        val embed = mockk<Embed>()
        every { embed.title } returns title
        every { embed.description } returns description
        return embed
    }

    private fun createMockMessage(
        id: Long = 1L,
        channelId: Long = 100L,
        username: String = "Alice",
        content: String = "Hello Luna, how are you today?",
        isBot: Boolean = false,
        webhookId: Snowflake? = null,
        type: MessageType = MessageType.Default,
        authorIsNull: Boolean = false,
        mentionedUserIds: Set<Snowflake> = emptySet(),
        selfId: Snowflake = Snowflake(9999L),
        attachments: Set<Attachment> = emptySet(),
        embeds: List<Embed> = emptyList(),
        referencedMessage: Message? = null,
    ): Message {
        val author =
            if (authorIsNull) {
                null
            } else {
                val user = mockk<User>()
                every { user.username } returns username
                every { user.isBot } returns isBot
                every { user.id } returns Snowflake(id)
                user
            }

        val userData = mockk<UserData>()
        every { userData.username } returns username
        every { userData.id } returns (webhookId ?: Snowflake(id))

        val messageData = mockk<MessageData>()
        every { messageData.author } returns userData
        every { messageData.content } returns content
        every { messageData.id } returns Snowflake(id)

        val msg = mockk<Message>()
        val mockKord = mockk<Kord>(relaxed = true)
        every { mockKord.selfId } returns selfId
        every { msg.kord } returns mockKord
        every { msg.id } returns Snowflake(id)
        every { msg.channelId } returns Snowflake(channelId)
        every { msg.author } returns author
        every { msg.data } returns messageData
        every { msg.content } returns content
        every { msg.type } returns type
        every { msg.webhookId } returns webhookId
        every { msg.attachments } returns attachments
        every { msg.embeds } returns embeds
        every { msg.mentionedUserIds } returns mentionedUserIds
        every { msg.referencedMessage } returns referencedMessage
        every { msg.messageReference } returns null
        every { msg.channel } returns channel
        coEvery { msg.getChannel() } returns channel
        return msg
    }

    @Test
    fun `direct mention to bot triggers immediate reply without calling decision client and bypasses cooldown`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 500L
            cooldownCache.record(channelId, simulatedTime)

            val message =
                createMockMessage(
                    id = 101L,
                    channelId = channelId,
                    username = "Alice",
                    content = "<@9999> what do you think about Kotlin coroutines?",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            val replyText = "Coroutines are awesome and lightweight!"

            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns replyText

            val result = service.processMessage(message)

            assertTrue(result, "Expected processMessage to reply to direct bot mention even during cooldown")
            coVerify(exactly = 0) { decisionClient.decide(any(), any()) }
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { it.isDirectMention && it.transcript.contains("what do you think about Kotlin coroutines?") },
                )
            }
            coVerify(exactly = 1) { channel.createMessage(replyText) }
        }

    @Test
    fun `direct pure bot mention triggers reply with callout greeting fallback passed to chatter generator`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 500L

            val message =
                createMockMessage(
                    id = 110L,
                    channelId = channelId,
                    username = "Alice",
                    content = "<@9999>",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            val replyText = "你好！很高興見到你！"

            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns replyText

            val result = service.processMessage(message)

            assertTrue(result, "Expected processMessage to reply to direct pure bot mention")
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        input.isDirectMention &&
                            input.transcript.contains("[Alice]: ${ChatterNoiseFilter.CALLOUT_GREETING}")
                    },
                )
            }
            coVerify(exactly = 1) { channel.createMessage(replyText) }
        }

    @Test
    fun `mixed bot mention message strips mention tag before passing to chatter generator`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 500L

            val message =
                createMockMessage(
                    id = 111L,
                    channelId = channelId,
                    username = "Alice",
                    content = "@Luna 今天天氣如何",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            val replyText = "今天天氣晴朗，很適合出門走走！"

            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns replyText

            val result = service.processMessage(message)

            assertTrue(result, "Expected processMessage to reply to mixed bot mention message")
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        input.isDirectMention &&
                            input.transcript.contains("[Alice]: 今天天氣如何") &&
                            !input.transcript.contains("@Luna")
                    },
                )
            }
            coVerify(exactly = 1) { channel.createMessage(replyText) }
        }

    @Test
    fun `trivial ambient message without bot mention, question, or substantive content does not call decision client`() =
        runTest {
            val channelId = 500L
            val message =
                createMockMessage(
                    id = 102L,
                    channelId = channelId,
                    username = "Bob",
                    content = "ok",
                    mentionedUserIds = emptySet(),
                )

            val result = service.processMessage(message)

            assertFalse(result, "Trivial ambient message should not trigger chatter reply")
            coVerify(exactly = 0) { decisionClient.decide(any(), any()) }
        }

    @Test
    fun `successful end-to-end flow with valid message, typing indicator and reply`() =
        runTest {
            val channelId = 500L
            val message = createMockMessage(id = 101L, channelId = channelId, username = "Alice", content = "Does anyone know when Luna updates?")
            val replyText = "Luna updates frequently! Check out the releases page."

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.95)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns replyText

            val result = service.processMessage(message)

            assertTrue(result, "Expected processMessage to return true when reply is successfully sent")
            coVerify(exactly = 1) { decisionClient.decide(match { it.contains("Alice") && it.contains("Luna updates") }, any()) }
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { !it.isDirectMention && it.transcript.contains("[Alice]: Does anyone know when Luna updates?") },
                )
            }
            coVerify(exactly = 1) { channel.createMessage(replyText) }
            assertTrue(cooldownCache.isOnCooldown(channelId), "Channel cooldown must be recorded after sending reply")
        }

    @Test
    fun `decision client returning false results in no typing indicator, no gemini call, and no message sent`() =
        runTest {
            val channelId = 501L
            val message = createMockMessage(id = 102L, channelId = channelId, username = "Bob", content = "Good morning")

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.2)

            val result = service.processMessage(message)

            assertFalse(result, "Expected processMessage to return false when decision client rejects participation")
            coVerify(exactly = 1) { decisionClient.decide(any(), any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<ChatterInput>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
            assertFalse(cooldownCache.isOnCooldown(channelId), "Cooldown should not be recorded when not participating")
        }

    @Test
    fun `filter rejects bot messages without invoking decision client or gemini`() =
        runTest {
            val message = createMockMessage(id = 103L, isBot = true, content = "Bot announcement")

            val result = service.processMessage(message)

            assertFalse(result)
            coVerify(exactly = 0) { decisionClient.decide(any(), any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<ChatterInput>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
        }

    @Test
    fun `filter rejects slash and prefix command messages without invoking decision client or gemini`() =
        runTest {
            val slashCommand = createMockMessage(id = 104L, content = "/help")
            val prefixCommand = createMockMessage(id = 105L, content = "!roll 100")

            assertFalse(service.processMessage(slashCommand))
            assertFalse(service.processMessage(prefixCommand))
            coVerify(exactly = 0) { decisionClient.decide(any(), any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<ChatterInput>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
        }

    @Test
    fun `filter rejects messages when channel is on cooldown`() =
        runTest {
            val channelId = 502L
            cooldownCache.record(channelId, simulatedTime)

            val message = createMockMessage(id = 106L, channelId = channelId, content = "Anyone here?")

            val result = service.processMessage(message)

            assertFalse(result)
            coVerify(exactly = 0) { decisionClient.decide(any(), any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<ChatterInput>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
        }

    @Test
    fun `filter rejects messages when channel is not in allowed channels whitelist`() =
        runTest {
            filter.allowedChannels = setOf(Snowflake(999L))
            val message = createMockMessage(id = 107L, channelId = 503L, content = "Testing channel whitelist")

            val result = service.processMessage(message)

            assertFalse(result)
            coVerify(exactly = 0) { decisionClient.decide(any(), any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<ChatterInput>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
        }

    @Test
    fun `social preview webhook in luna webhook registry passes filter and processes end to end`() =
        runTest {
            val webhookId = Snowflake(777L)
            LunaWebhookRegistry.register(webhookId)

            val channelId = 504L
            val message =
                createMockMessage(
                    id = 108L,
                    channelId = channelId,
                    username = "LunaPreview",
                    content = "Check this interesting thread: https://x.com/example/status/123",
                    authorIsNull = true,
                    webhookId = webhookId,
                )

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "That looks like an interesting thread!"

            val result = service.processMessage(message)

            assertTrue(result, "SocialPreview webhook in registry must pass filter and be processed")
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) { chatterGenerator.generateReply(any<ChatterInput>()) }
            coVerify(exactly = 1) { channel.createMessage("That looks like an interesting thread!") }
            assertTrue(cooldownCache.isOnCooldown(channelId))
        }

    @Test
    fun `gemini returning null or error fails closed gracefully without crashing`() =
        runTest {
            val channelId = 505L
            val message = createMockMessage(id = 109L, channelId = channelId, content = "Tell me something cool")

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns null

            val resultNull = service.processMessage(message)
            assertFalse(resultNull, "Expected failure when Gemini returns null")
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
            assertFalse(cooldownCache.isOnCooldown(channelId))

            // Test Gemini throwing exception
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } throws RuntimeException("Network timeout")
            val resultException = service.processMessage(message)
            assertFalse(resultException, "Expected graceful failure when Gemini throws exception")
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
            assertFalse(cooldownCache.isOnCooldown(channelId))
        }

    @Test
    fun `plugin loader discovers ChatterPlugin via ServiceLoader SPI`() {
        val loader = PluginLoader()
        val plugins = loader.loadPlugins()

        val plugin = plugins.find { it is ChatterPlugin }
        assertNotNull(plugin, "ChatterPlugin must be registered in META-INF/services/luna.plugin.LunaPlugin")
        assertEquals("chatter", plugin.name)

        val directLoader = ServiceLoader.load(LunaPlugin::class.java)
        assertTrue(
            directLoader.any { it is ChatterPlugin },
            "ChatterPlugin must be discoverable via standard java.util.ServiceLoader",
        )
    }

    @Test
    fun `chatter plugin initializes configuration and enables automatically when GEMINI_API_KEY is configured`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            val eventsFlow = MutableSharedFlow<Event>()
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns eventsFlow

            val configMap =
                mapOf(
                    "GEMINI_API_KEY" to "gemini-secret-key-456",
                    "CHATTER_DECISION_MODEL" to "gemini-decision-custom",
                    "CHATTER_DECISION_THRESHOLD" to 0.85,
                    "CHATTER_DECISION_TIMEOUT_SECONDS" to 15L,
                    "CHATTER_ALLOWED_CHANNELS" to "111,222",
                    "CHATTER_COOLDOWN_SECONDS" to 45L,
                    "CHATTER_GEMINI_MODEL" to "gemini-3.5-flash-test",
                )

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = configMap,
                )

            val plugin = ChatterPlugin()
            plugin.initialize(context)

            assertTrue(plugin.isChatterEnabled)
            assertNotNull(plugin.service)
            assertEquals("gemini-secret-key-456", plugin.decisionClient?.config?.apiKey)
            assertEquals("gemini-decision-custom", plugin.decisionClient?.config?.model)
            assertEquals(0.85, plugin.decisionClient?.config?.threshold)
            assertEquals(15_000L, plugin.decisionClient?.config?.timeoutMillis)
            assertEquals("gemini-secret-key-456", plugin.chatterGenerator?.config?.apiKey)
            assertEquals("gemini-3.5-flash-test", plugin.chatterGenerator?.config?.model)
            assertEquals(setOf(Snowflake(111L), Snowflake(222L)), plugin.filter?.allowedChannels)
            assertEquals(45_000L, plugin.filter?.cooldownDurationMs)

            plugin.shutdown()
        }

    @Test
    fun `chatter plugin remains disabled when CHATTER_ENABLED is false even with GEMINI_API_KEY configured`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            val eventsFlow = MutableSharedFlow<Event>()
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns eventsFlow

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap =
                        mapOf(
                            "GEMINI_API_KEY" to "gemini-secret-key-456",
                            "CHATTER_ENABLED" to false,
                        ),
                )

            val plugin = ChatterPlugin()
            plugin.initialize(context)

            assertFalse(plugin.isChatterEnabled)
            assertFalse(plugin.filter?.enabled ?: true)
            plugin.shutdown()
        }

    @Test
    fun `history collection preserves chronological order and filters noise messages from history slice`() =
        runTest {
            val channelId = 506L
            val msg1 = createMockMessage(id = 1L, channelId = channelId, username = "Alice", content = "What is Luna?")
            val msg2 = createMockMessage(id = 2L, channelId = channelId, username = "SpamBot", content = "Buy crypto now", isBot = true)
            val msg3 = createMockMessage(id = 3L, channelId = channelId, username = "Bob", content = "!help")
            val msg4 = createMockMessage(id = 4L, channelId = channelId, username = "Charlie", content = "Luna is a community bot.")
            val currentMsg = createMockMessage(id = 5L, channelId = channelId, username = "Alice", content = "Does it reply to chats?")

            val customHistoryFetcher: suspend (Message, Int) -> List<Message> = { _, _ ->
                listOf(msg1, msg2, msg3, msg4)
            }

            val testService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    decisionMessageCount = 3,
                    historyMessageCount = 15,
                    historyFetcher = customHistoryFetcher,
                )

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "Yes, Luna replies to chats!"

            val result = testService.processMessage(currentMsg)

            assertTrue(result)
            // Decision state should receive valid messages only (excluding bot and command)
            coVerify(exactly = 1) {
                decisionClient.decide(
                    match { state ->
                        state.contains("Alice") &&
                            state.contains("Charlie") &&
                            !state.contains("SpamBot") &&
                            !state.contains("!help")
                    },
                    any(),
                )
            }
            // Gemini reply stage should receive chronological dialogue transcript without noise
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        input.transcript.contains("[Alice]: What is Luna?") &&
                            input.transcript.contains("[Charlie]: Luna is a community bot.") &&
                            input.transcript.contains("[Alice]: Does it reply to chats?") &&
                            !input.transcript.contains("SpamBot") &&
                            !input.transcript.contains("!help")
                    },
                )
            }
        }

    @Test
    fun `event listener receives MessageCreateEvent and triggers chatter pipeline`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            val eventsFlow = MutableSharedFlow<Event>(replay = 1, extraBufferCapacity = 64)
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns eventsFlow

            val channelId = 507L
            val message = createMockMessage(id = 110L, channelId = channelId, username = "Daisy", content = "Hello there from event")

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "General Kenobi!"

            val job = service.register(mockKord, scope = this)

            val event = mockk<MessageCreateEvent>(relaxed = true)
            every { event.message } returns message

            eventsFlow.emit(event)
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) { channel.createMessage("General Kenobi!") }
            assertTrue(cooldownCache.isOnCooldown(channelId))

            job.cancel()
        }

    @Test
    fun `chatter plugin skips registration and runs in disabled mode when GEMINI_API_KEY is blank`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = mapOf("CHATTER_ENABLED" to true),
                )

            val plugin = ChatterPlugin()
            plugin.initialize(context)

            assertFalse(plugin.isChatterEnabled)
            assertEquals("", plugin.decisionClient?.config?.apiKey)
            plugin.shutdown()
        }

    @Test
    fun `image-only ambient message passes decision evaluation with ChatImageAttachments and triggers reply`() =
        runTest {
            val channelId = 601L
            val validPng = createMockAttachment("https://cdn.example.com/meme.png", "image/png", "meme.png")
            val message =
                createMockMessage(
                    id = 601L,
                    channelId = channelId,
                    username = "Alice",
                    content = "",
                    attachments = setOf(validPng),
                )

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.88)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "haha that meme is gold"

            val result = service.processMessage(message)

            assertTrue(result)
            coVerify(exactly = 1) {
                decisionClient.decide(
                    match { it.contains("[Alice]: [附帶圖片 1: meme.png]") },
                    match { it.size == 1 && it[0].url == "https://cdn.example.com/meme.png" && it[0].mimeType == "image/png" },
                )
            }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        !input.isDirectMention &&
                            input.transcript.contains("[Alice]: [附帶圖片 1: meme.png]") &&
                            input.imageAttachments.size == 1 &&
                            input.imageAttachments[0].url == "https://cdn.example.com/meme.png" &&
                            input.imageAttachments[0].mimeType == "image/png"
                    },
                )
            }
            coVerify(exactly = 1) { channel.createMessage("haha that meme is gold") }
            assertTrue(cooldownCache.isOnCooldown(channelId))
        }

    @Test
    fun `oversized image-only ambient message does not invoke decision client or chatter generator`() =
        runTest {
            val tenMbPlusOne = 10 * 1024 * 1024 + 1
            val hugeImg = createMockAttachment("https://cdn.example.com/huge.jpg", "image/jpeg", "huge.jpg", size = tenMbPlusOne)
            val message =
                createMockMessage(
                    id = 602L,
                    channelId = 602L,
                    username = "Bob",
                    content = "",
                    attachments = setOf(hugeImg),
                )

            val result = service.processMessage(message)

            assertFalse(result)
            coVerify(exactly = 0) { decisionClient.decide(any(), any()) }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<ChatterInput>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
        }

    @Test
    fun `URL-only ambient message skips SocialPeek in decision stage and propagates resolved post content to ChatterGenerator`() =
        runTest {
            val channelId = 603L
            val callStages = mutableListOf<String>()
            val peekPost =
                PeekPost(
                    platform = Platform.GENERIC,
                    id = "article-1",
                    originalUrl = "https://example.com/kotlin-post",
                    cleanUrl = "https://example.com/kotlin-post",
                    author = Author(username = "Author"),
                    title = "Kotlin 2.2 Released",
                    content = "Context parameters and new compiler improvements",
                    comments = listOf(Comment(author = "reader", content = "Looks clean!")),
                )
            val customCollector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { url ->
                        callStages.add("peek:$url")
                        peekPost
                    },
                )
            val urlService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = customCollector,
                )

            val urlMessage =
                createMockMessage(
                    id = 603L,
                    channelId = channelId,
                    username = "Alice",
                    content = "https://example.com/kotlin-post",
                )

            coEvery { decisionClient.decide(any(), any()) } answers {
                callStages.add("decide")
                ParticipationDecision(shouldParticipate = true, noul = 0.91)
            }
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } answers {
                callStages.add("generate")
                "Kotlin 2.2 context parameters look neat"
            }

            val result = urlService.processMessage(urlMessage)

            assertTrue(result)
            assertEquals(listOf("decide", "peek:https://example.com/kotlin-post", "generate"), callStages)
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        !input.isDirectMention &&
                            input.title == "Kotlin 2.2 Released" &&
                            input.url == "https://example.com/kotlin-post" &&
                            input.comments == listOf("Looks clean!") &&
                            input.transcript.contains("https://example.com/kotlin-post") &&
                            input.transcript.contains("Kotlin 2.2 Released") &&
                            input.transcript.contains("Context parameters and new compiler improvements") &&
                            input.transcript.contains("Looks clean!")
                    },
                )
            }
            coVerify(exactly = 1) { channel.createMessage("Kotlin 2.2 context parameters look neat") }
        }

    @Test
    fun `lightweight vs heavy split caps decision at 3 messages and 3 images while reply receives 15 messages and maxImages`() =
        runTest {
            val channelId = 604L
            val extRef = createMockMessage(id = 700L, channelId = channelId, username = "ExtUser", content = "Earlier architecture note")
            val historyMsgs =
                (1..14).map { idx ->
                    val att = createMockAttachment("https://cdn.example.com/$idx.png", "image/png", "$idx.png")
                    createMockMessage(
                        id = 700L + idx,
                        channelId = channelId,
                        username = "U$idx",
                        content = "Turn $idx",
                        attachments = setOf(att),
                        referencedMessage = if (idx == 1) extRef else null,
                    )
                }
            val currentAtt = createMockAttachment("https://cdn.example.com/15.png", "image/png", "15.png")
            val currentMsg =
                createMockMessage(
                    id = 715L,
                    channelId = channelId,
                    username = "U15",
                    content = "Turn 15 check this out",
                    attachments = setOf(currentAtt),
                )

            val splitCollector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    decisionMessageCount = 3,
                    historyMessageCount = 15,
                    maxDecisionImages = 3,
                    maxImages = 10,
                    peekResolver = { null },
                )
            val splitService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> historyMsgs },
                    contextCollector = splitCollector,
                )

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.8)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "nice progression"

            val result = splitService.processMessage(currentMsg)

            assertTrue(result)
            coVerify(exactly = 1) {
                decisionClient.decide(
                    match { state ->
                        !state.contains("Turn 12") &&
                            state.contains("Turn 13") &&
                            state.contains("Turn 14") &&
                            state.contains("Turn 15")
                    },
                    match { images ->
                        images.map { it.url } ==
                            listOf(
                                "https://cdn.example.com/13.png",
                                "https://cdn.example.com/14.png",
                                "https://cdn.example.com/15.png",
                            )
                    },
                )
            }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        input.transcript.contains("[U1 回覆 ExtUser「Earlier architecture note」]: Turn 1") &&
                            input.transcript.contains("Turn 15 check this out") &&
                            input.transcript.contains("[附帶圖片 1: 6.png]") &&
                            input.transcript.contains("[附帶圖片 10: 15.png]") &&
                            !input.transcript.contains("[附帶圖片 11:") &&
                            input.imageAttachments.size == 10 &&
                            input.imageAttachments.first().url == "https://cdn.example.com/6.png" &&
                            input.imageAttachments.last().url == "https://cdn.example.com/15.png"
                    },
                )
            }
        }

    @Test
    fun `SocialPeek failure in reply stage degrades cleanly to Discord embed transcript without failing reply`() =
        runTest {
            val channelId = 605L
            val embed = createMockEmbed(title = "Fallback Embed Title", description = "Fallback description")
            val message =
                createMockMessage(
                    id = 801L,
                    channelId = channelId,
                    username = "Alice",
                    content = "Check https://example.com/failing-page",
                    embeds = listOf(embed),
                )

            val failingCollector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { throw RuntimeException("SocialPeek timeout") },
                )
            val failingPeekService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = failingCollector,
                )

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "looks interesting anyway"

            val result = failingPeekService.processMessage(message)

            assertTrue(result)
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        input.transcript.contains("https://example.com/failing-page") &&
                            input.transcript.contains("[連結預覽: Fallback Embed Title - Fallback description]")
                    },
                )
            }
            coVerify(exactly = 1) { channel.createMessage("looks interesting anyway") }
        }

    @Test
    fun `YouTube PeekPost propagates videoUri, videoDurationSeconds, metadata, and isDirectMention into ChatterInput`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val ytPost =
                PeekPost(
                    platform = Platform.YOUTUBE,
                    id = "dQw4w9WgXcQ",
                    originalUrl = "https://youtu.be/dQw4w9WgXcQ",
                    cleanUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    author = Author(username = "TestChannel"),
                    title = "Deep Dive on Kotlin",
                    content = "Video description",
                    rawData = mapOf("durationSeconds" to "900"),
                    comments = listOf(Comment(author = "viewer1", content = "Great talk!")),
                )
            val ytCollector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { ytPost },
                )
            val ytService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = ytCollector,
                )

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "這部影片講得挺清楚的"

            val ambientMsg =
                createMockMessage(
                    id = 850L,
                    channelId = 606L,
                    username = "Alice",
                    content = "看看這部 https://youtu.be/dQw4w9WgXcQ",
                )
            assertTrue(ytService.processMessage(ambientMsg))
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        !input.isDirectMention &&
                            input.videoUri == "https://www.youtube.com/watch?v=dQw4w9WgXcQ" &&
                            input.videoDurationSeconds == 900L &&
                            input.title == "Deep Dive on Kotlin" &&
                            input.url == "https://www.youtube.com/watch?v=dQw4w9WgXcQ" &&
                            input.comments == listOf("Great talk!")
                    },
                )
            }

            val mentionMsg =
                createMockMessage(
                    id = 851L,
                    channelId = 607L,
                    username = "Bob",
                    content = "<@9999> 幫我看這部 https://youtu.be/dQw4w9WgXcQ",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            assertTrue(ytService.processMessage(mentionMsg))
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        input.isDirectMention &&
                            input.videoUri == "https://www.youtube.com/watch?v=dQw4w9WgXcQ" &&
                            input.videoDurationSeconds == 900L &&
                            input.title == "Deep Dive on Kotlin" &&
                            input.url == "https://www.youtube.com/watch?v=dQw4w9WgXcQ" &&
                            input.comments == listOf("Great talk!")
                    },
                )
            }
        }

    @Test
    fun `chatter plugin resolves YOUTUBE_API_KEY and SUMMARY_MAX_IMAGES and wires ChatterContextCollector`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            val eventsFlow = MutableSharedFlow<Event>()
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns eventsFlow

            val customContext =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap =
                        mapOf(
                            "GEMINI_API_KEY" to "gemini-secret",
                            "YOUTUBE_API_KEY" to "yt-secret-key",
                            "SUMMARY_MAX_IMAGES" to 6,
                            "GEMINI_SEARCH_GROUNDING_ENABLED" to false,
                        ),
                )

            val customPlugin = ChatterPlugin()
            customPlugin.initialize(customContext)

            assertEquals(6, customPlugin.maxImages)
            assertEquals(6, customPlugin.contextCollector?.maxImages)
            assertSame(customPlugin.contextCollector, customPlugin.service?.contextCollector)
            assertFalse(customPlugin.chatterGenerator?.config?.searchGroundingEnabled ?: true)
            customPlugin.shutdown()

            val defaultContext =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = mapOf("GEMINI_API_KEY" to "gemini-secret"),
                )

            val defaultPlugin = ChatterPlugin()
            defaultPlugin.initialize(defaultContext)

            assertEquals(10, defaultPlugin.maxImages)
            assertEquals(10, defaultPlugin.contextCollector?.maxImages)
            assertSame(defaultPlugin.contextCollector, defaultPlugin.service?.contextCollector)
            assertTrue(defaultPlugin.chatterGenerator?.config?.searchGroundingEnabled ?: false)
            defaultPlugin.shutdown()
        }
}
