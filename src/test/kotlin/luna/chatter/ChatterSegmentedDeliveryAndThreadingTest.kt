package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.event.Event
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import luna.plugin.DefaultPluginContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatterSegmentedDeliveryAndThreadingTest {
    private val fixture = ChatterServiceTestSupport()

    @BeforeEach
    fun setUp() = fixture.setUp()

    @AfterEach
    fun tearDown() = fixture.tearDown()

    @Test
    fun `0 or 1 intervening non-Luna messages send segment 1 via createMessage while ignoring Luna messages`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val lunaMsg1 = fixture.createMockMessage(id = 201L, channelId = 700L, authorId = 9999L, username = "Luna", content = "hi", isBot = true)
            val lunaMsg2 = fixture.createMockMessage(id = 202L, channelId = 700L, authorId = 9999L, username = "Luna", content = "yo", isBot = true)
            val bobMsg = fixture.createMockMessage(id = 203L, channelId = 700L, authorId = 3001L, username = "Bob", content = "nice")

            val capturedReplies = mutableListOf<String>()
            val capturedCreates = mutableListOf<String>()
            coEvery { fixture.channel.createMessage(any<String>()) } coAnswers {
                capturedCreates.add(firstArg())
                mockk(relaxed = true)
            }

            // Case A: 0 non-Luna messages (2 Luna messages ignored)
            val zeroNonLunaService =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    afterMessagesFetcher = { _, _ -> listOf(lunaMsg1, lunaMsg2) },
                    contextCollector = fixture.contextCollector,
                    randomProvider = { _, _ -> 800L },
                    replySender = { _, content ->
                        capturedReplies.add(content)
                        mockk(relaxed = true)
                    },
                )
            val triggerA =
                fixture.createMockMessage(
                    id = 200L,
                    channelId = 700L,
                    authorId = 1001L,
                    content = "<@9999> ping",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "zero intervening"

            assertTrue(zeroNonLunaService.processMessage(triggerA))
            assertEquals(listOf("zero intervening"), capturedCreates)
            assertTrue(capturedReplies.isEmpty())

            // Case B: 1 non-Luna message (+ 2 Luna messages ignored) -> still createMessage
            capturedCreates.clear()
            val oneNonLunaService =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    afterMessagesFetcher = { _, _ -> listOf(lunaMsg1, bobMsg, lunaMsg2) },
                    contextCollector = fixture.contextCollector,
                    randomProvider = { _, _ -> 800L },
                    replySender = { _, content ->
                        capturedReplies.add(content)
                        mockk(relaxed = true)
                    },
                )
            val triggerB =
                fixture.createMockMessage(
                    id = 210L,
                    channelId = 701L,
                    authorId = 1002L,
                    content = "<@9999> ping2",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "one intervening"

            assertTrue(oneNonLunaService.processMessage(triggerB))
            assertEquals(listOf("one intervening"), capturedCreates)
            assertTrue(capturedReplies.isEmpty())
        }

    @Test
    fun `2 or more intervening non-Luna messages send segment 1 via message reply and segments 2 and 3 via createMessage with typing delays`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 702L
            val timeline = mutableListOf<String>()
            val randomCalls = mutableListOf<Pair<Long, Long>>()

            val lunaMsg = fixture.createMockMessage(id = 301L, channelId = channelId, authorId = 9999L, username = "Luna", content = "luna", isBot = true)
            val bobMsg = fixture.createMockMessage(id = 302L, channelId = channelId, authorId = 3001L, username = "Bob", content = "msg b")
            val carolMsg = fixture.createMockMessage(id = 303L, channelId = channelId, authorId = 3002L, username = "Carol", content = "msg c")

            val trigger =
                fixture.createMockMessage(
                    id = 300L,
                    channelId = channelId,
                    authorId = 1001L,
                    content = "<@9999> multi thought",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )

            coEvery { fixture.channel.type() } coAnswers {
                timeline.add("type@$currentTime")
            }
            coEvery { fixture.channel.createMessage(any<String>()) } coAnswers {
                timeline.add("create:${firstArg<String>()}@$currentTime")
                mockk(relaxed = true)
            }
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns
                "12345\n\n67890\nthird line\nfourth line"

            val repliedTargets = mutableListOf<Snowflake>()
            val service =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    afterMessagesFetcher = { _, _ -> listOf(bobMsg, lunaMsg, carolMsg) },
                    contextCollector = fixture.contextCollector,
                    randomProvider = { min, max ->
                        randomCalls.add(min to max)
                        if (min == 800L) 1_000L else 1_100L
                    },
                    replySender = { msg, content ->
                        repliedTargets.add(msg.id)
                        timeline.add("reply:$content@$currentTime")
                        mockk(relaxed = true)
                    },
                )

            val run = async { service.processMessage(trigger) }

            // Mention debounce = 1200ms, reading = 1000ms -> type@2200
            // seg1 = "12345" (5 chars * 120ms = 600ms) -> reply@2800
            // seg2: type@2800 + 1100ms -> create@3900
            // seg3: type@3900 + 1100ms -> create@5000
            advanceUntilIdle()
            assertTrue(run.await())

            assertEquals(listOf(Snowflake(300L)), repliedTargets)
            assertEquals(
                listOf(
                    800L to 2_000L,
                    700L to 1_500L,
                    700L to 1_500L,
                ),
                randomCalls,
            )
            assertEquals(
                listOf(
                    "type@2200",
                    "reply:12345@2800",
                    "type@2800",
                    "create:67890@3900",
                    "type@3900",
                    "create:third line\nfourth line@5000",
                ),
                timeline,
            )
            coVerify(exactly = 2) { fixture.channel.createMessage(any<String>()) }
        }

    @Test
    fun `post-trigger fetch failure fails open to createMessage with warning log`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            try {
                val botSelfId = Snowflake(9999L)
                val trigger =
                    fixture.createMockMessage(
                        id = 400L,
                        channelId = 703L,
                        authorId = 1001L,
                        content = "<@9999> fail open test",
                        mentionedUserIds = setOf(botSelfId),
                        selfId = botSelfId,
                    )

                val capturedCreates = mutableListOf<String>()
                val capturedReplies = mutableListOf<String>()
                coEvery { fixture.channel.createMessage(any<String>()) } coAnswers {
                    capturedCreates.add(firstArg())
                    mockk(relaxed = true)
                }
                coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "fallback seg1\nfallback seg2"

                val service =
                    ChatterService(
                        filter = fixture.filter,
                        decisionClient = fixture.decisionClient,
                        chatterGenerator = fixture.chatterGenerator,
                        cooldownCache = fixture.cooldownCache,
                        historyFetcher = { _, _ -> emptyList() },
                        afterMessagesFetcher = { _, _ -> throw IllegalStateException("Discord API 503") },
                        contextCollector = fixture.contextCollector,
                        randomProvider = { _, _ -> 800L },
                        replySender = { _, content ->
                            capturedReplies.add(content)
                            mockk(relaxed = true)
                        },
                    )

                assertTrue(service.processMessage(trigger))
                assertEquals(listOf("fallback seg1", "fallback seg2"), capturedCreates)
                assertTrue(capturedReplies.isEmpty())

                val warnLogs = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnLogs.any { it.contains("400") && it.contains("Discord API 503") },
                    "Expected warning log with trigger message ID and failure cause, got: $warnLogs",
                )
            } finally {
                logger.detachAppender(appender)
            }
        }

    @Test
    fun `once segment 1 starts sending, newer channel messages do not cancel remaining burst segments`() =
        runTest {
            val channelId = 704L
            val seg1Sent = CompletableDeferred<Unit>()
            val sentOrder = mutableListOf<String>()
            val botSelfId = Snowflake(9999L)

            val trigger1 =
                fixture.createMockMessage(
                    id = 500L,
                    channelId = channelId,
                    authorId = 1001L,
                    content = "<@9999> burst start",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            val interruptingTrigger2 =
                fixture.createMockMessage(
                    id = 501L,
                    channelId = channelId,
                    authorId = 1002L,
                    content = "<@9999> interrupt mid burst",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )

            coEvery { fixture.channel.createMessage(any<String>()) } coAnswers {
                val text = firstArg<String>()
                sentOrder.add(text)
                if (text == "part1" && !seg1Sent.isCompleted) {
                    seg1Sent.complete(Unit)
                }
                mockk(relaxed = true)
            }
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returnsMany
                listOf("part1\npart2\npart3", "second-turn")

            val service =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    afterMessagesFetcher = { _, _ -> emptyList() },
                    contextCollector = fixture.contextCollector,
                    randomProvider = { _, _ -> 800L },
                )

            val firstRun = async { service.processMessage(trigger1) }
            seg1Sent.await()

            // Arrive mid-burst while segment 2 typing delay is pending
            val secondRun = async { service.processMessage(interruptingTrigger2) }
            advanceTimeBy(400L)
            runCurrent()
            assertFalse(firstRun.isCompleted)

            advanceUntilIdle()
            assertTrue(firstRun.await())
            assertTrue(secondRun.await())
            assertEquals(listOf("part1", "part2", "part3", "second-turn"), sentOrder)
        }

    @Test
    fun `ChatterService enforces 2000-character limit with ellipsis on segmented burst payloads`() =
        runTest {
            val channelId = 705L
            val sentPayloads = mutableListOf<String>()
            val oversizedReply = "${"a".repeat(2020)}\n${"b".repeat(500)}\n${"c".repeat(1200)}\n${"d".repeat(1200)}"

            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.95)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns oversizedReply

            val service =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    afterMessagesFetcher = { _, _ -> emptyList() },
                    contextCollector = fixture.contextCollector,
                    randomProvider = { _, _ -> 800L },
                    messageSender = { _, content ->
                        sentPayloads.add(content)
                        mockk(relaxed = true)
                    },
                )

            val msg = fixture.createMockMessage(id = 600L, channelId = channelId, content = "long segmented test")
            assertTrue(service.processMessage(msg))
            assertEquals(3, sentPayloads.size)
            assertEquals(2000, sentPayloads[0].length)
            assertTrue(sentPayloads[0].endsWith("..."))
            assertEquals("b".repeat(500), sentPayloads[1])
            assertEquals(2000, sentPayloads[2].length)
            assertTrue(sentPayloads[2].endsWith("..."))
        }

    @Test
    fun `ChatterPlugin wires afterMessagesFetcher to channel getMessagesAfter`() =
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
                    configMap = mapOf("GEMINI_API_KEY" to "test-key"),
                )

            val plugin = ChatterPlugin()
            plugin.initialize(context)

            val service = assertNotNull(plugin.service)
            val mockChannel = mockk<MessageChannel>(relaxed = true)
            val triggerMsg = fixture.createMockMessage(id = 700L, channelId = 800L)
            val after1 = fixture.createMockMessage(id = 701L, channelId = 800L, content = "after 1")
            val after2 = fixture.createMockMessage(id = 702L, channelId = 800L, content = "after 2")
            every { triggerMsg.channel } returns mockChannel
            every { mockChannel.getMessagesAfter(Snowflake(700L), 10) } returns flowOf(after1, after2)

            val fetched = service.afterMessagesFetcher(triggerMsg, 10)
            assertEquals(listOf(after1, after2), fetched)

            plugin.shutdown()
        }
}
