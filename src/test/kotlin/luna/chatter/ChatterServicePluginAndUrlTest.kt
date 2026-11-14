package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
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

class ChatterServicePluginAndUrlTest {
    private val fixture = ChatterServiceTestSupport()

    @BeforeEach
    fun setUp() = fixture.setUp()

    @AfterEach
    fun tearDown() = fixture.tearDown()

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
            val message = fixture.createMockMessage(id = 110L, channelId = channelId, username = "Daisy", content = "Hello there from event")

            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "General Kenobi!"

            val job = fixture.service.register(mockKord, scope = this)

            val event = mockk<MessageCreateEvent>(relaxed = true)
            every { event.message } returns message

            eventsFlow.emit(event)
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { fixture.channel.type() }
            coVerify(exactly = 1) { fixture.channel.createMessage("General Kenobi!") }
            assertTrue(fixture.cooldownCache.isOnCooldown(channelId))

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
    fun `URL-only social ambient message skips SocialPeek in decision stage and propagates resolved post content to ChatterGenerator`() =
        runTest {
            val channelId = 603L
            val socialUrl = "https://www.threads.net/@alice/post/kotlin-post"
            val callStages = mutableListOf<String>()
            val peekPost =
                PeekPost(
                    platform = Platform.THREADS,
                    id = "article-1",
                    originalUrl = socialUrl,
                    cleanUrl = socialUrl,
                    author = Author(username = "Author"),
                    title = "Kotlin 2.2 Released",
                    content = "Context parameters and new compiler improvements",
                    comments = listOf(Comment(author = "reader", content = "Looks clean!")),
                )
            val customCollector =
                ChatterContextCollector(
                    noiseFilter = fixture.filter,
                    peekResolver = { url ->
                        callStages.add("peek:$url")
                        peekPost
                    },
                )
            val urlService =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = customCollector,
                )

            val urlMessage =
                fixture.createMockMessage(
                    id = 603L,
                    channelId = channelId,
                    username = "Alice",
                    content = socialUrl,
                )

            coEvery { fixture.decisionClient.decide(any(), any()) } answers {
                callStages.add("decide")
                ParticipationDecision(shouldParticipate = true, noul = 0.91)
            }
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } answers {
                callStages.add("generate")
                "Kotlin 2.2 context parameters look neat"
            }

            val result = urlService.processMessage(urlMessage)

            assertTrue(result)
            assertEquals(listOf("decide", "peek:$socialUrl", "generate"), callStages)
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        !input.isDirectMention &&
                            input.title == "Kotlin 2.2 Released" &&
                            input.url == socialUrl &&
                            input.comments == listOf("Looks clean!") &&
                            input.webUrls.isEmpty() &&
                            input.transcript.contains(socialUrl) &&
                            input.transcript.contains("Kotlin 2.2 Released") &&
                            input.transcript.contains("Context parameters and new compiler improvements") &&
                            input.transcript.contains("Looks clean!")
                    },
                )
            }
            coVerify(exactly = 1) { fixture.channel.createMessage("Kotlin 2.2 context parameters look neat") }
        }

    @Test
    fun `general web URL ambient message skips SocialPeek completely and propagates webUrls to ChatterGenerator`() =
        runTest {
            val channelId = 604L
            val webUrl = "https://example.com/kotlin-post"
            var peekCalled = false
            val customCollector =
                ChatterContextCollector(
                    noiseFilter = fixture.filter,
                    peekResolver = {
                        peekCalled = true
                        null
                    },
                )
            val urlService =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = customCollector,
                )

            val urlMessage =
                fixture.createMockMessage(
                    id = 604L,
                    channelId = channelId,
                    username = "Alice",
                    content = "Check $webUrl",
                )

            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.91)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "Kotlin 2.2 context parameters look neat"

            assertTrue(urlService.processMessage(urlMessage))
            assertFalse(peekCalled)
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        !input.isDirectMention &&
                            input.webUrls == listOf(webUrl) &&
                            input.transcript.contains(webUrl)
                    },
                )
            }
            coVerify(exactly = 1) { fixture.channel.createMessage("Kotlin 2.2 context parameters look neat") }
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
                    fixture.createMockMessage(
                        id = idx,
                        channelId = 700L,
                        username = "User$idx",
                        content = "Turn $idx",
                    )
                }
            val triggeringMsg =
                fixture.createMockMessage(
                    id = 21L,
                    channelId = 700L,
                    username = "TriggerUser",
                    content = "Latest turn",
                )

            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.2)

            // Case 1: decisionMessageCount > historyMessageCount (20 > 10) -> fetches 20
            val largeDecisionCollector =
                ChatterContextCollector(
                    noiseFilter = fixture.filter,
                    decisionMessageCount = 20,
                    historyMessageCount = 10,
                    peekResolver = { null },
                )
            val largeDecisionService =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
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
                fixture.decisionClient.decide(
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
                    noiseFilter = fixture.filter,
                    decisionMessageCount = 5,
                    historyMessageCount = 15,
                    peekResolver = { null },
                )
            val smallDecisionService =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
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
}
