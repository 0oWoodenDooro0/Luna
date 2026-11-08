package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.event.Event
import dev.kord.core.event.message.MessageCreateEvent
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
import kotlin.test.assertTrue

class ChatterServiceTest {
    private var simulatedTime: Long = 100_000L
    private lateinit var cooldownCache: BoundedChannelCooldownCache
    private lateinit var filter: ChatterNoiseFilter
    private lateinit var layaClient: LayaClient
    private lateinit var chatterGenerator: ChatterGenerator
    private lateinit var channel: MessageChannel
    private lateinit var service: ChatterService

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        simulatedTime = 100_000L
        cooldownCache = BoundedChannelCooldownCache(timeSource = { simulatedTime })
        filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
        layaClient = mockk(relaxed = true)
        chatterGenerator = mockk(relaxed = true)
        channel = mockk(relaxed = true)

        coEvery { channel.type() } returns Unit
        coEvery { channel.createMessage(any<String>()) } returns mockk(relaxed = true)

        service =
            ChatterService(
                filter = filter,
                layaClient = layaClient,
                chatterGenerator = chatterGenerator,
                cooldownCache = cooldownCache,
                historyFetcher = { _, _ -> emptyList() },
            )
    }

    @AfterEach
    fun tearDown() {
        LunaWebhookRegistry.clear()
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
        every { msg.attachments } returns emptySet()
        every { msg.mentionedUserIds } returns mentionedUserIds
        every { msg.channel } returns channel
        coEvery { msg.getChannel() } returns channel
        return msg
    }

    @Test
    fun `direct mention to bot triggers immediate reply without calling laya and bypasses cooldown`() =
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

            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } returns replyText

            val result = service.processMessage(message)

            assertTrue(result, "Expected processMessage to reply to direct bot mention even during cooldown")
            coVerify(exactly = 0) { layaClient.evaluateParticipation(any()) }
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) { chatterGenerator.generateReply(any<List<ChatMessage>>()) }
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

            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } returns replyText

            val result = service.processMessage(message)

            assertTrue(result, "Expected processMessage to reply to direct pure bot mention")
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<List<ChatMessage>> { list ->
                        list.any { it.author == "Alice" && it.content == ChatterNoiseFilter.CALLOUT_GREETING }
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

            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } returns replyText

            val result = service.processMessage(message)

            assertTrue(result, "Expected processMessage to reply to mixed bot mention message")
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<List<ChatMessage>> { list ->
                        list.any { it.author == "Alice" && it.content == "今天天氣如何" }
                    },
                )
            }
            coVerify(exactly = 1) { channel.createMessage(replyText) }
        }

    @Test
    fun `trivial ambient message without bot mention, question, or substantive content does not call laya`() =
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
            coVerify(exactly = 0) { layaClient.evaluateParticipation(any()) }
        }

    @Test
    fun `successful end-to-end flow with valid message, typing indicator and reply`() =
        runTest {
            val channelId = 500L
            val message = createMockMessage(id = 101L, channelId = channelId, username = "Alice", content = "Does anyone know when Luna updates?")
            val replyText = "Luna updates frequently! Check out the releases page."

            coEvery { layaClient.evaluateParticipation(any()) } returns LayaDecision(shouldParticipate = true, probability = 0.95)
            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } returns replyText

            val result = service.processMessage(message)

            assertTrue(result, "Expected processMessage to return true when reply is successfully sent")
            coVerify(exactly = 1) { layaClient.evaluateParticipation(match { it.contains("Alice") && it.contains("Luna updates") }) }
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) { chatterGenerator.generateReply(match<List<ChatMessage>> { it.any { msg -> msg.author == "Alice" } }) }
            coVerify(exactly = 1) { channel.createMessage(replyText) }
            assertTrue(cooldownCache.isOnCooldown(channelId), "Channel cooldown must be recorded after sending reply")
        }

    @Test
    fun `laya says false results in no typing indicator, no gemini call, and no message sent`() =
        runTest {
            val channelId = 501L
            val message = createMockMessage(id = 102L, channelId = channelId, username = "Bob", content = "Good morning")

            coEvery { layaClient.evaluateParticipation(any()) } returns LayaDecision(shouldParticipate = false, probability = 0.2)

            val result = service.processMessage(message)

            assertFalse(result, "Expected processMessage to return false when Laya rejects participation")
            coVerify(exactly = 1) { layaClient.evaluateParticipation(any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<List<ChatMessage>>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
            assertFalse(cooldownCache.isOnCooldown(channelId), "Cooldown should not be recorded when not participating")
        }

    @Test
    fun `filter rejects bot messages without invoking laya or gemini`() =
        runTest {
            val message = createMockMessage(id = 103L, isBot = true, content = "Bot announcement")

            val result = service.processMessage(message)

            assertFalse(result)
            coVerify(exactly = 0) { layaClient.evaluateParticipation(any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<List<ChatMessage>>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
        }

    @Test
    fun `filter rejects slash and prefix command messages without invoking laya or gemini`() =
        runTest {
            val slashCommand = createMockMessage(id = 104L, content = "/help")
            val prefixCommand = createMockMessage(id = 105L, content = "!roll 100")

            assertFalse(service.processMessage(slashCommand))
            assertFalse(service.processMessage(prefixCommand))
            coVerify(exactly = 0) { layaClient.evaluateParticipation(any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<List<ChatMessage>>()) }
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
            coVerify(exactly = 0) { layaClient.evaluateParticipation(any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<List<ChatMessage>>()) }
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
        }

    @Test
    fun `filter rejects messages when channel is not in allowed channels whitelist`() =
        runTest {
            filter.allowedChannels = setOf(Snowflake(999L))
            val message = createMockMessage(id = 107L, channelId = 503L, content = "Testing channel whitelist")

            val result = service.processMessage(message)

            assertFalse(result)
            coVerify(exactly = 0) { layaClient.evaluateParticipation(any()) }
            coVerify(exactly = 0) { channel.type() }
            coVerify(exactly = 0) { chatterGenerator.generateReply(any<List<ChatMessage>>()) }
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

            coEvery { layaClient.evaluateParticipation(any()) } returns LayaDecision(shouldParticipate = true, probability = 0.9)
            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } returns "That looks like an interesting thread!"

            val result = service.processMessage(message)

            assertTrue(result, "SocialPreview webhook in registry must pass filter and be processed")
            coVerify(exactly = 1) { channel.type() }
            coVerify(exactly = 1) { chatterGenerator.generateReply(any<List<ChatMessage>>()) }
            coVerify(exactly = 1) { channel.createMessage("That looks like an interesting thread!") }
            assertTrue(cooldownCache.isOnCooldown(channelId))
        }

    @Test
    fun `gemini returning null or error fails closed gracefully without crashing`() =
        runTest {
            val channelId = 505L
            val message = createMockMessage(id = 109L, channelId = channelId, content = "Tell me something cool")

            coEvery { layaClient.evaluateParticipation(any()) } returns LayaDecision(shouldParticipate = true)
            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } returns null

            val resultNull = service.processMessage(message)
            assertFalse(resultNull, "Expected failure when Gemini returns null")
            coVerify(exactly = 0) { channel.createMessage(any<String>()) }
            assertFalse(cooldownCache.isOnCooldown(channelId))

            // Test Gemini throwing exception
            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } throws RuntimeException("Network timeout")
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
    fun `chatter plugin initializes configuration and instantiates components`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            val eventsFlow = MutableSharedFlow<Event>()
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns eventsFlow

            val configMap =
                mapOf(
                    "LAYA_API_KEY" to "laya-secret-key-123",
                    "LAYA_THRESHOLD" to 0.85,
                    "CHATTER_ENABLED" to true,
                    "CHATTER_ALLOWED_CHANNELS" to "111,222",
                    "CHATTER_COOLDOWN_SECONDS" to 45L,
                    "GEMINI_API_KEY" to "gemini-secret-key-456",
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
            assertEquals("laya-secret-key-123", plugin.layaClient?.config?.apiKey)
            assertEquals(0.85, plugin.layaClient?.config?.threshold)
            assertEquals("gemini-secret-key-456", plugin.chatterGenerator?.config?.apiKey)
            assertEquals("gemini-3.5-flash-test", plugin.chatterGenerator?.config?.model)
            assertEquals(setOf(Snowflake(111L), Snowflake(222L)), plugin.filter?.allowedChannels)
            assertEquals(45_000L, plugin.filter?.cooldownDurationMs)

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
                    layaClient = layaClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    layaMessageCount = 3,
                    historyMessageCount = 15,
                    historyFetcher = customHistoryFetcher,
                )

            coEvery { layaClient.evaluateParticipation(any()) } returns LayaDecision(shouldParticipate = true)
            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } returns "Yes, Luna replies to chats!"

            val result = testService.processMessage(currentMsg)

            assertTrue(result)
            // Laya state should receive valid messages only (excluding bot and command)
            coVerify(exactly = 1) {
                layaClient.evaluateParticipation(
                    match { state ->
                        state.contains("Alice") &&
                            state.contains("Charlie") &&
                            !state.contains("SpamBot") &&
                            !state.contains("!help")
                    },
                )
            }
            // Gemini history should receive chronological list of ChatMessage turns
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<List<ChatMessage>> { history ->
                        history.size == 3 &&
                            history[0].author == "Alice" && history[0].content == "What is Luna?" &&
                            history[1].author == "Charlie" && history[1].content == "Luna is a community bot." &&
                            history[2].author == "Alice" && history[2].content == "Does it reply to chats?"
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

            coEvery { layaClient.evaluateParticipation(any()) } returns LayaDecision(shouldParticipate = true)
            coEvery { chatterGenerator.generateReply(any<List<ChatMessage>>()) } returns "General Kenobi!"

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
    fun `chatter plugin skips registration and runs in disabled mode when LAYA_API_KEY is blank`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = emptyMap(),
                )

            val plugin = ChatterPlugin()
            plugin.initialize(context)

            assertFalse(plugin.isChatterEnabled)
            assertEquals("", plugin.layaClient?.config?.apiKey)
            plugin.shutdown()
        }
}
