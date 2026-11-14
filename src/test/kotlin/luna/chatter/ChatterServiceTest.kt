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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.NoOpAiTraceRecorder
import luna.plugin.DefaultPluginContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        selfId: Snowflake? = Snowflake(9999L),
        referencedMessage: Message? = null,
        embeds: List<dev.kord.core.entity.Embed> = emptyList(),
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
        if (selfId != null) {
            every { mockKord.selfId } returns selfId
        } else {
            every { mockKord.selfId } throws IllegalStateException("selfId unavailable")
        }
        every { msg.kord } returns mockKord
        every { msg.id } returns Snowflake(id)
        every { msg.channelId } returns Snowflake(channelId)
        every { msg.author } returns author
        every { msg.data } returns messageData
        every { msg.content } returns content
        every { msg.type } returns type
        every { msg.webhookId } returns webhookId
        every { msg.attachments } returns emptySet()
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
                    match<ChatterInput> {
                        it.isDirectMention &&
                            it.transcript.contains("<@9999> what do you think about Kotlin coroutines?")
                    },
                )
            }
            coVerify(exactly = 1) { channel.createMessage(replyText) }
        }

    @Test
    fun `short ambient messages enter participation decision instead of being pre-filtered`() =
        runTest {
            val channelId = 500L
            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.2)

            for ((idx, shortText) in listOf("?", "草", "真的嗎", "ok").withIndex()) {
                val msg =
                    createMockMessage(
                        id = 102L + idx,
                        channelId = channelId,
                        username = "Bob",
                        content = shortText,
                    )
                val result = service.processMessage(msg)
                assertFalse(result)
                coVerify(exactly = 1) {
                    decisionClient.decide(match { it.contains("[Bob]: $shortText") }, any())
                }
            }
        }

    @Test
    fun `embed-only message enters participation decision`() =
        runTest {
            val embed = mockk<dev.kord.core.entity.Embed>()
            every { embed.title } returns null
            every { embed.description } returns null
            val message =
                createMockMessage(
                    id = 120L,
                    channelId = 500L,
                    username = "Charlie",
                    content = "",
                    embeds = listOf(embed),
                )
            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.3)

            val result = service.processMessage(message)

            assertFalse(result)
            coVerify(exactly = 1) {
                decisionClient.decide(match { it.contains("[Charlie]: [Embed]") }, any())
            }
        }

    @Test
    fun `plain-text luna mention, reply to luna, and unresolved selfId route through participation decision with raw text`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.4)

            // 1. Plain-text @luna
            val plainLunaMsg =
                createMockMessage(
                    id = 130L,
                    channelId = 500L,
                    username = "Alice",
                    content = "Hey @luna what's up?",
                    selfId = botSelfId,
                )
            assertFalse(service.processMessage(plainLunaMsg))
            coVerify(exactly = 1) {
                decisionClient.decide(match { it.contains("[Alice]: Hey @luna what's up?") }, any())
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
                createMockMessage(
                    id = 131L,
                    channelId = 500L,
                    username = "Bob",
                    content = "Sounds good",
                    selfId = botSelfId,
                    referencedMessage = lunaMsg,
                )
            assertFalse(service.processMessage(replyToLuna))
            coVerify(exactly = 1) {
                decisionClient.decide(match { it.contains("[Bob 回覆 Luna「Earlier bot reply」]: Sounds good") }, any())
            }

            // 3. Unresolved selfId (null) does not bypass decision even with <@9999>
            val unresolvedSelfIdMsg =
                createMockMessage(
                    id = 132L,
                    channelId = 500L,
                    username = "Dave",
                    content = "<@9999> hello",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = null,
                )
            assertFalse(service.processMessage(unresolvedSelfIdMsg))
            coVerify(exactly = 1) {
                decisionClient.decide(match { it.contains("[Dave]: <@9999> hello") }, any())
            }
        }

    @Test
    fun `structural noise rejections do not invoke participation decision`() =
        runTest {
            val cmdMsg = createMockMessage(id = 140L, content = "/help")
            val prefixCmdMsg = createMockMessage(id = 141L, content = "!ping")
            val systemMsg = createMockMessage(id = 142L, content = "Pinned", type = MessageType.ChannelPinnedMessage)
            val botMsg = createMockMessage(id = 143L, content = "Bot msg", isBot = true)
            val webhookMsg = createMockMessage(id = 144L, content = "Webhook msg", isBot = true, webhookId = Snowflake(8888L))
            val blankMsg = createMockMessage(id = 145L, content = "   ")

            for (msg in listOf(cmdMsg, prefixCmdMsg, systemMsg, botMsg, webhookMsg, blankMsg)) {
                assertFalse(service.processMessage(msg))
            }

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
    fun `chatter plugin wires CHATTER_DECISION_INSTRUCTIONS and CHATTER_DECISION_MESSAGE_COUNT to decisionClient, contextCollector, and service`() =
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
                            "GEMINI_API_KEY" to "gemini-secret-key-456",
                            "CHATTER_DECISION_INSTRUCTIONS" to "Custom plugin decision instructions for Luna",
                            "CHATTER_DECISION_MESSAGE_COUNT" to 32,
                        ),
                )

            val customPlugin = ChatterPlugin()
            customPlugin.initialize(customContext)

            assertEquals("Custom plugin decision instructions for Luna", customPlugin.decisionClient?.config?.instructions)
            assertEquals("Custom plugin decision instructions for Luna", customPlugin.decisionClient?.defaultInstructions)
            assertEquals(32, customPlugin.decisionClient?.config?.decisionMessageCount)
            assertEquals(32, customPlugin.contextCollector?.decisionMessageCount)
            assertEquals(32, customPlugin.service?.decisionMessageCount)
            customPlugin.shutdown()

            val defaultContext =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = mapOf("GEMINI_API_KEY" to "gemini-secret-key-456"),
                )

            val defaultPlugin = ChatterPlugin()
            defaultPlugin.initialize(defaultContext)

            assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, defaultPlugin.decisionClient?.config?.instructions)
            assertEquals(GeminiDecisionClient.DEFAULT_INSTRUCTIONS, defaultPlugin.decisionClient?.defaultInstructions)
            assertEquals(15, defaultPlugin.decisionClient?.config?.decisionMessageCount)
            assertEquals(15, defaultPlugin.contextCollector?.decisionMessageCount)
            assertEquals(15, defaultPlugin.service?.decisionMessageCount)
            defaultPlugin.shutdown()
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
                            "GEMINI_URL_CONTEXT_ENABLED" to false,
                        ),
                )

            val customPlugin = ChatterPlugin()
            customPlugin.initialize(customContext)

            assertEquals(6, customPlugin.maxImages)
            assertEquals(6, customPlugin.contextCollector?.maxImages)
            assertSame(customPlugin.contextCollector, customPlugin.service?.contextCollector)
            assertFalse(customPlugin.chatterGenerator?.config?.searchGroundingEnabled ?: true)
            assertFalse(customPlugin.chatterGenerator?.config?.urlContextEnabled ?: true)
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
            assertTrue(defaultPlugin.chatterGenerator?.config?.urlContextEnabled ?: false)
            assertEquals(5_000L, defaultPlugin.filter?.cooldownDurationMs)
            assertEquals(5_000L, defaultPlugin.cooldownCache?.defaultCooldownDurationMs)
            assertNotNull(defaultPlugin.inFlightTracker)
            assertSame(defaultPlugin.inFlightTracker, defaultPlugin.service?.inFlightTracker)
            defaultPlugin.shutdown()
        }

    @Test
    fun `default decision message count is 15 and service fetches max of decisionMessageCount and historyMessageCount`() =
        runTest {
            assertEquals(15, ChatterContextCollector.DEFAULT_DECISION_MESSAGE_COUNT)
            assertEquals(15, ChatterService.DEFAULT_DECISION_MESSAGE_COUNT)

            val fetchedLimits = mutableListOf<Int>()
            val priorMessages =
                (1L..20L).map { idx ->
                    createMockMessage(
                        id = idx,
                        channelId = 700L,
                        username = "User$idx",
                        content = "Turn $idx",
                    )
                }
            val triggeringMsg =
                createMockMessage(
                    id = 21L,
                    channelId = 700L,
                    username = "TriggerUser",
                    content = "Latest turn",
                )

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.2)

            // Case 1: decisionMessageCount > historyMessageCount (20 > 10) -> fetches 20
            val largeDecisionCollector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    decisionMessageCount = 20,
                    historyMessageCount = 10,
                    peekResolver = { null },
                )
            val largeDecisionService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    decisionMessageCount = 20,
                    historyMessageCount = 10,
                    historyFetcher = { _, limit ->
                        fetchedLimits.add(limit)
                        priorMessages.takeLast(limit)
                    },
                    contextCollector = largeDecisionCollector,
                )

            assertFalse(largeDecisionService.processMessage(triggeringMsg))
            assertEquals(listOf(20), fetchedLimits)
            coVerify(exactly = 1) {
                decisionClient.decide(
                    match { transcript ->
                        !transcript.contains("Turn 1\n") &&
                            transcript.contains("[User2]: Turn 2") &&
                            transcript.contains("[User20]: Turn 20") &&
                            transcript.contains("[TriggerUser]: Latest turn")
                    },
                    any(),
                )
            }

            // Case 2: historyMessageCount > decisionMessageCount (15 > 5) -> fetches 15
            fetchedLimits.clear()
            val smallDecisionCollector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    decisionMessageCount = 5,
                    historyMessageCount = 15,
                    peekResolver = { null },
                )
            val smallDecisionService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    decisionMessageCount = 5,
                    historyMessageCount = 15,
                    historyFetcher = { _, limit ->
                        fetchedLimits.add(limit)
                        priorMessages.takeLast(limit)
                    },
                    contextCollector = smallDecisionCollector,
                )

            assertFalse(smallDecisionService.processMessage(triggeringMsg))
            assertEquals(listOf(15), fetchedLimits)
        }

    @Test
    fun `ChatterService forwards framed decision context with external reply chaining and unframed enriched reply context`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val externalTarget = createMockMessage(id = 801L, username = "ExtUser", content = "Original topic question")
            val prior1 = createMockMessage(id = 810L, username = "Alice", content = "Earlier message")
            val triggerMsg =
                createMockMessage(
                    id = 811L,
                    channelId = 710L,
                    username = "Bob",
                    content = "Check https://example.com/article",
                    selfId = botSelfId,
                    referencedMessage = externalTarget,
                )

            val peekPost =
                PeekPost(
                    platform = Platform.GENERIC,
                    id = "art-1",
                    originalUrl = "https://example.com/article",
                    cleanUrl = "https://example.com/article",
                    author = Author(username = "Writer"),
                    title = "Article Title",
                    content = "Article summary body",
                )
            val realCollector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { peekPost },
                )
            val pipelineService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> listOf(prior1) },
                    contextCollector = realCollector,
                )

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "Nice article"

            assertTrue(pipelineService.processMessage(triggerMsg))

            coVerify(exactly = 1) {
                decisionClient.decide(
                    match { transcript ->
                        transcript.contains("[Bot Identity: Luna | Discord Mention: <@9999>]") &&
                            transcript.contains("[Recent Context]") &&
                            transcript.contains("[Alice]: Earlier message") &&
                            transcript.contains("[Latest Message]") &&
                            transcript.contains("[Bob 回覆 ExtUser「Original topic question」]: Check https://example.com/article") &&
                            !transcript.contains("Article Title")
                    },
                    any(),
                )
            }
            coVerify(exactly = 1) {
                chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        !input.transcript.contains("[Bot Identity:") &&
                            !input.transcript.contains("[Recent Context]") &&
                            !input.transcript.contains("[Latest Message]") &&
                            input.transcript.contains("[Alice]: Earlier message") &&
                            input.transcript.contains("[Bob 回覆 ExtUser「Original topic question」]: Check https://example.com/article") &&
                            input.transcript.contains("Article Title")
                    },
                )
            }
        }

    @Test
    fun `concurrent ambient messages skip immediately before fetching history, starting trace session, or invoking AI`() =
        runTest {
            val channelId = 700L
            val otherChannelId = 701L
            val historyCalls = AtomicInteger(0)
            val traceSessionStarts = AtomicInteger(0)
            val tracker = BoundedChannelInFlightTracker()
            val enteredDecision = CompletableDeferred<Unit>()
            val releaseDecision = CompletableDeferred<Unit>()

            val countingTraceRecorder =
                object : AiTraceRecorder {
                    override fun isEnabled(): Boolean = false

                    override fun startSession(
                        pipeline: AiTracePipeline,
                        trigger: AiTraceTrigger,
                        discord: AiTraceDiscordContext,
                    ): AiTraceSession {
                        traceSessionStarts.incrementAndGet()
                        return NoOpAiTraceRecorder.startSession(pipeline, trigger, discord)
                    }

                    override fun record(trace: AiTraceRecord) = Unit

                    override suspend fun flushAndClose(timeoutMs: Long) = Unit
                }

            val guardedService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { msg, _ ->
                        historyCalls.incrementAndGet()
                        if (msg.channelId == Snowflake(channelId)) {
                            emptyList()
                        } else {
                            emptyList()
                        }
                    },
                    contextCollector = contextCollector,
                    traceRecorder = countingTraceRecorder,
                    inFlightTracker = tracker,
                )

            coEvery { decisionClient.decide(any(), any()) } coAnswers {
                val transcript = firstArg<String>()
                if (transcript.contains("first ambient")) {
                    enteredDecision.complete(Unit)
                    releaseDecision.await()
                }
                ParticipationDecision(shouldParticipate = true, noul = 0.9)
            }
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "Reply"

            val msg1 = createMockMessage(id = 7001L, channelId = channelId, username = "Alice", content = "first ambient")
            val msg2Concurrent = createMockMessage(id = 7002L, channelId = channelId, username = "Bob", content = "second ambient")
            val msgOtherChannel = createMockMessage(id = 7003L, channelId = otherChannelId, username = "Cara", content = "other channel")

            val firstRun = async { guardedService.processMessage(msg1) }
            enteredDecision.await()

            assertTrue(tracker.isInFlight(channelId))
            assertEquals(1, tracker.activeCount(channelId))
            assertEquals(1, historyCalls.get())
            assertEquals(1, traceSessionStarts.get())

            // Concurrent ambient message on the same channel skips immediately before history, trace session, or AI
            assertFalse(guardedService.processMessage(msg2Concurrent))
            assertEquals(1, historyCalls.get())
            assertEquals(1, traceSessionStarts.get())
            coVerify(exactly = 1) { decisionClient.decide(any(), any()) }

            // Different channel is unaffected by channel 700L in-flight state
            assertTrue(guardedService.processMessage(msgOtherChannel))
            assertEquals(2, historyCalls.get())
            assertEquals(2, traceSessionStarts.get())

            releaseDecision.complete(Unit)
            assertTrue(firstRun.await())

            assertFalse(tracker.isInFlight(channelId))
            assertEquals(0, tracker.activeCount(channelId))
            assertEquals(0, tracker.size)
        }

    @Test
    fun `direct bot mention bypasses in-flight guard, keeps reference count across concurrent runs, and releases on exception`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 702L
            val tracker = BoundedChannelInFlightTracker()
            val ambientEnteredDecision = CompletableDeferred<Unit>()
            val releaseAmbientDecision = CompletableDeferred<Unit>()
            val mentionEnteredGenerate = CompletableDeferred<Unit>()
            val releaseMentionGenerate = CompletableDeferred<Unit>()
            val historyCalls = AtomicInteger(0)

            val guardedService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ ->
                        historyCalls.incrementAndGet()
                        emptyList()
                    },
                    contextCollector = contextCollector,
                    inFlightTracker = tracker,
                )

            coEvery { decisionClient.decide(any(), any()) } coAnswers {
                val transcript = firstArg<String>()
                if (transcript.contains("ambient in-flight")) {
                    ambientEnteredDecision.complete(Unit)
                    releaseAmbientDecision.await()
                }
                ParticipationDecision(shouldParticipate = false, noul = 0.2)
            }
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } coAnswers {
                mentionEnteredGenerate.complete(Unit)
                releaseMentionGenerate.await()
                throw RuntimeException("simulated generator failure")
            }

            val ambient1 = createMockMessage(id = 7101L, channelId = channelId, username = "Alice", content = "ambient in-flight")
            val mentionMsg =
                createMockMessage(
                    id = 7102L,
                    channelId = channelId,
                    username = "Bob",
                    content = "<@9999> urgent question",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            val ambient2WhileMentionRuns =
                createMockMessage(id = 7103L, channelId = channelId, username = "Cara", content = "ambient burst")
            val ambient3AfterCleanup =
                createMockMessage(id = 7104L, channelId = channelId, username = "Dave", content = "after cleanup")

            val ambientRun = async { guardedService.processMessage(ambient1) }
            ambientEnteredDecision.await()
            assertEquals(1, tracker.activeCount(channelId))

            // Direct @bot mention proceeds concurrently and increments active count to 2
            val mentionRun = async { guardedService.processMessage(mentionMsg) }
            mentionEnteredGenerate.await()
            assertEquals(2, tracker.activeCount(channelId))

            // Completing ambient run only decrements count from 2 -> 1; mention is still active
            releaseAmbientDecision.complete(Unit)
            assertFalse(ambientRun.await())
            assertTrue(tracker.isInFlight(channelId))
            assertEquals(1, tracker.activeCount(channelId))

            // Ambient message while direct mention is still running must still skip immediately
            val historyCountBeforeBurst = historyCalls.get()
            assertFalse(guardedService.processMessage(ambient2WhileMentionRuns))
            assertEquals(historyCountBeforeBurst, historyCalls.get())

            // Direct mention fails with RuntimeException; try/finally must release count back to 0
            releaseMentionGenerate.complete(Unit)
            assertFalse(mentionRun.await())
            assertFalse(tracker.isInFlight(channelId))
            assertEquals(0, tracker.activeCount(channelId))

            // Subsequent ambient message proceeds normally after exception cleanup
            assertFalse(guardedService.processMessage(ambient3AfterCleanup))
            assertEquals(historyCountBeforeBurst + 1, historyCalls.get())
            assertFalse(tracker.isInFlight(channelId))
            assertEquals(0, tracker.activeCount(channelId))

            // Unhandled exception from startSession still releases in-flight count via finally
            val throwingTraceRecorder =
                object : AiTraceRecorder {
                    override fun isEnabled(): Boolean = false

                    override fun startSession(
                        pipeline: AiTracePipeline,
                        trigger: AiTraceTrigger,
                        discord: AiTraceDiscordContext,
                    ): AiTraceSession = throw IllegalStateException("trace recorder boom")

                    override fun record(trace: AiTraceRecord) = Unit

                    override suspend fun flushAndClose(timeoutMs: Long) = Unit
                }
            val throwingService =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = contextCollector,
                    traceRecorder = throwingTraceRecorder,
                    inFlightTracker = tracker,
                )
            val failingMention =
                createMockMessage(
                    id = 7105L,
                    channelId = 703L,
                    username = "Eve",
                    content = "<@9999> fail trace",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            assertFailsWith<IllegalStateException> {
                throwingService.processMessage(failingMention)
            }
            assertFalse(tracker.isInFlight(703L))
            assertEquals(0, tracker.activeCount(703L))
        }

    @Test
    fun `default 5 second cooldown blocks ambient before 5s, allows direct mention during cooldown, and allows ambient at 5s`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 704L

            coEvery { decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { chatterGenerator.generateReply(any<ChatterInput>()) } returns "Hello!"

            // Initial ambient reply starts the 5s cooldown at simulatedTime
            val firstAmbient = createMockMessage(id = 7201L, channelId = channelId, username = "Alice", content = "anyone here?")
            assertTrue(service.processMessage(firstAmbient))
            coVerify(exactly = 1) { decisionClient.decide(any(), any()) }

            // 4.999s later: ambient message is blocked by default 5s cooldown
            simulatedTime += 4_999L
            val blockedAmbient = createMockMessage(id = 7202L, channelId = channelId, username = "Bob", content = "still blocked")
            assertFalse(service.processMessage(blockedAmbient))
            coVerify(exactly = 1) { decisionClient.decide(any(), any()) }

            // Direct @bot mention during the 5s cooldown still bypasses cooldown and replies
            val mentionDuringCooldown =
                createMockMessage(
                    id = 7203L,
                    channelId = channelId,
                    username = "Cara",
                    content = "<@9999> ping",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            assertTrue(service.processMessage(mentionDuringCooldown))
            coVerify(exactly = 2) { chatterGenerator.generateReply(any<ChatterInput>()) }

            // Advance 5.000s after the mention reply: ambient message is allowed again
            simulatedTime += 5_000L
            val allowedAmbient = createMockMessage(id = 7204L, channelId = channelId, username = "Dave", content = "cooldown expired")
            assertTrue(service.processMessage(allowedAmbient))
            coVerify(exactly = 2) { decisionClient.decide(any(), any()) }
        }
}
