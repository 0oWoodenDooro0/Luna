package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
        every { msg.attachments } returns emptySet()
        every { msg.embeds } returns emptyList()
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
