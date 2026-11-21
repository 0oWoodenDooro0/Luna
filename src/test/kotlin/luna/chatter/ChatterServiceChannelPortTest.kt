package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.User
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import luna.chatter.emoji.ChatterEmojiService
import luna.chatter.emoji.DefaultChatterEmojiService
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTraceSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChatterServiceChannelPortTest {
    private fun mockMessage(
        id: Long,
        channelId: Long = 100L,
        authorId: Long = id,
        username: String = "Alice",
        content: String = "Hello Luna!",
        isBot: Boolean = false,
        selfId: Snowflake = Snowflake(9999L),
        guildId: Snowflake? = Snowflake(500L),
        mentionedUserIds: Set<Snowflake> = emptySet(),
    ): Message {
        val user =
            mockk<User> {
                every { this@mockk.id } returns Snowflake(authorId)
                every { this@mockk.username } returns username
                every { this@mockk.isBot } returns isBot
            }
        val userData =
            mockk<UserData> {
                every { this@mockk.id } returns Snowflake(authorId)
                every { this@mockk.username } returns username
            }
        val msgData =
            mockk<MessageData>(relaxed = true) {
                every { this@mockk.id } returns Snowflake(id)
                every { this@mockk.author } returns userData
                every { this@mockk.content } returns content
                every { this@mockk.guildId } returns
                    if (guildId != null) {
                        dev.kord.common.entity.optional.OptionalSnowflake
                            .Value(guildId)
                    } else {
                        dev.kord.common.entity.optional.OptionalSnowflake.Missing
                    }
            }
        val mockKord =
            mockk<Kord>(relaxed = true) {
                every { this@mockk.selfId } returns selfId
            }
        return mockk {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.channelId } returns Snowflake(channelId)
            every { this@mockk.author } returns user
            every { this@mockk.data } returns msgData
            every { this@mockk.content } returns content
            every { this@mockk.type } returns MessageType.Default
            every { this@mockk.webhookId } returns null
            every { this@mockk.attachments } returns emptySet()
            every { this@mockk.embeds } returns emptyList()
            every { this@mockk.mentionedUserIds } returns mentionedUserIds
            every { this@mockk.referencedMessage } returns null
            every { this@mockk.messageReference } returns null
            every { this@mockk.kord } returns mockKord
            every { this@mockk.channel } throws AssertionError("message.channel must not be accessed when channelPort is provided")
        }
    }

    private fun buildService(
        channelPort: FakeChatterChannelPort,
        decisionClient: GeminiDecisionClient =
            mockk(relaxed = true) {
                every { config } returns ChatterDecisionConfig()
                coEvery { decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9, confidence = 0.9)
            },
        chatterGenerator: ChatterGenerator =
            mockk(relaxed = true) {
                every { config } returns ChatterConfig()
                coEvery { generateReply(any<ChatterInput>()) } returns "Hi there!"
            },
        filter: ChatterNoiseFilter = ChatterNoiseFilter(),
        contextCollector: ChatterContextCollector =
            ChatterContextCollector(noiseFilter = filter, decisionMessageCount = 7, historyMessageCount = 19, peekResolver = { null }),
        traceRecorder: luna.core.ai.AiTraceRecorder = luna.core.ai.NoOpAiTraceRecorder,
        emojiService: ChatterEmojiService = DefaultChatterEmojiService(),
        randomProvider: (Long, Long) -> Long = { min, _ -> min },
    ): ChatterService =
        ChatterService(
            filter = filter,
            decisionClient = decisionClient,
            chatterGenerator = chatterGenerator,
            contextCollector = contextCollector,
            traceRecorder = traceRecorder,
            randomProvider = randomProvider,
            personaStateProvider = CircadianPersonaDrift(enabled = false),
            emojiService = emojiService,
            channelPort = channelPort,
        )

    @Test
    fun `channelPort path derives fetch limit from contextCollector, skips message channel, and records filter cooldownCache`() =
        runTest {
            val port = FakeChatterChannelPort()
            val filterCooldown = BoundedChannelCooldownCache(timeSource = { 1_000L })
            val filter = ChatterNoiseFilter(cooldownCache = filterCooldown)
            val collector = ChatterContextCollector(noiseFilter = filter, decisionMessageCount = 8, historyMessageCount = 22, peekResolver = { null })
            val service = buildService(channelPort = port, filter = filter, contextCollector = collector)

            val trigger = mockMessage(id = 10L, channelId = 100L)
            val result = service.processMessage(trigger)

            assertTrue(result) { "Expected processMessage to succeed via channelPort" }
            assertEquals(listOf(22), port.recordedBeforeLimits)
            assertEquals(1, port.typingCount)
            assertEquals(listOf("Hi there!"), port.sentMessages.map { it.content })
            assertTrue(filterCooldown.isOnCooldown(Snowflake(100L))) { "Expected filter.cooldownCache to record channel cooldown" }
        }

    @Test
    fun `fail-open reads, typing, and negative randomProvider clamp emit warning logs without aborting reply`() =
        runTest {
            val port =
                FakeChatterChannelPort().apply {
                    historyError = IllegalStateException("history timeout")
                    guildEmojisError = IllegalStateException("emoji fetch failed")
                    typingError = IllegalStateException("typing 429")
                    afterMessagesError = IllegalStateException("after fetch failed")
                }
            val serviceLogger = LoggerFactory.getLogger(ChatterService::class.java) as Logger
            val engineLogger = LoggerFactory.getLogger(ChatterDeliveryEngine::class.java) as Logger
            val serviceAppender = ListAppender<ILoggingEvent>().apply { start() }
            val engineAppender = ListAppender<ILoggingEvent>().apply { start() }
            serviceLogger.addAppender(serviceAppender)
            engineLogger.addAppender(engineAppender)

            try {
                val service = buildService(channelPort = port, randomProvider = { _, _ -> -25L })
                val result = service.processMessage(mockMessage(id = 10L))

                assertTrue(result) { "Expected reply to succeed despite fail-open read/typing errors" }
                assertEquals(listOf("Hi there!"), port.sentMessages.map { it.content })
                assertTrue(serviceAppender.list.any { it.level == Level.WARN && it.formattedMessage.contains("history") }) {
                    "Expected warning log for history fetch failure"
                }
                assertTrue(serviceAppender.list.any { it.level == Level.WARN && it.formattedMessage.contains("guild emojis") }) {
                    "Expected warning log for guild emoji failure"
                }
                assertTrue(engineAppender.list.any { it.level == Level.WARN && it.formattedMessage.contains("triggerTyping") }) {
                    "Expected warning log for triggerTyping failure"
                }
                assertTrue(engineAppender.list.any { it.level == Level.WARN && it.formattedMessage.contains("post-trigger") }) {
                    "Expected warning log for fetchMessagesAfter failure"
                }
                assertTrue(engineAppender.list.any { it.level == Level.WARN && it.formattedMessage.contains("Negative") }) {
                    "Expected warning log for negative randomProvider delay clamping"
                }
            } finally {
                serviceLogger.detachAppender(serviceAppender)
                engineLogger.detachAppender(engineAppender)
            }
        }

    @Test
    fun `outbound sendMessage failure fails closed with AI Trace FAILED and skips cooldown and reply cadence`() =
        runTest {
            val port = FakeChatterChannelPort().apply { sendError = IllegalStateException("503 Service Unavailable") }
            val session = mockk<AiTraceSession>(relaxed = true)
            val recorder =
                mockk<luna.core.ai.AiTraceRecorder>(relaxed = true) {
                    every { startSession(any(), any(), any()) } returns session
                }
            val filter = ChatterNoiseFilter()
            val emojiService = DefaultChatterEmojiService()
            val service =
                buildService(
                    channelPort = port,
                    filter = filter,
                    traceRecorder = recorder,
                    emojiService = emojiService,
                )

            val result = service.processMessage(mockMessage(id = 10L, channelId = 100L))

            assertFalse(result) { "Expected processMessage to return false on sendMessage failure" }
            assertFalse(filter.cooldownCache.isOnCooldown(Snowflake(100L))) {
                "Expected no cooldown recorded when sendMessage fails"
            }
            assertTrue(
                emojiService
                    .beginTurn(Snowflake(100L), null, guildEmojisProvider = { listOf(GuildCustomEmoji(Snowflake(1L), "ok")) })
                    .prepareReplyEmojis("check")
                    .guildEmojis
                    .isNotEmpty(),
            ) {
                "Expected no reply emoji cadence update when sendMessage fails"
            }
            io.mockk.verify(exactly = 1) { session.fail(AiTraceFailureCategory.UNEXPECTED_ERROR, any()) }
        }

    @Test
    fun `addReaction failure marks AI Trace SKIPPED without typing or recording reaction cadence`() =
        runTest {
            val port = FakeChatterChannelPort().apply { reactionError = IllegalStateException("reaction blocked") }
            val session = mockk<AiTraceSession>(relaxed = true)
            val recorder =
                mockk<luna.core.ai.AiTraceRecorder>(relaxed = true) {
                    every { startSession(any(), any(), any()) } returns session
                }
            val decisionClient =
                mockk<GeminiDecisionClient>(relaxed = true) {
                    every { config } returns ChatterDecisionConfig(threshold = 0.8)
                    coEvery { decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.6, confidence = 0.7)
                }
            val generator =
                mockk<ChatterGenerator>(relaxed = true) {
                    every { config } returns ChatterConfig(reactionThreshold = 0.4)
                    coEvery { selectReactionEmoji(any()) } returns "👍"
                }
            val emojiService = DefaultChatterEmojiService()
            val service =
                buildService(
                    channelPort = port,
                    decisionClient = decisionClient,
                    chatterGenerator = generator,
                    traceRecorder = recorder,
                    emojiService = emojiService,
                )

            val result = service.processMessage(mockMessage(id = 10L, channelId = 100L))

            assertFalse(result) { "Expected processMessage to return false when addReaction fails" }
            assertEquals(0, port.typingCount)
            assertTrue(
                emojiService
                    .beginTurn(Snowflake(100L), null, guildEmojisProvider = { emptyList() })
                    .prepareReactionEmojis()
                    .recentReactions
                    .isEmpty(),
            ) {
                "Expected no reaction cadence recorded when addReaction fails"
            }
            io.mockk.verify(exactly = 1) {
                session.skip(any(), AiTraceFailureCategory.UNEXPECTED_ERROR)
            }
        }

    @Test
    fun `CancellationException from channelPort propagates out of ChatterDeliveryEngine and skips session without fail`() =
        runTest {
            val port = FakeChatterChannelPort().apply { sendError = CancellationException("cancelled") }
            val engine = ChatterDeliveryEngine(channelPort = port, randomProvider = { min, _ -> min })
            val trigger = mockMessage(id = 10L, channelId = 100L)

            assertThrows<CancellationException> {
                engine.withReadingSimulation {
                    engine.deliverSegmentedReply(
                        anchor = trigger,
                        segments = listOf("hi"),
                        selfId = Snowflake(9999L),
                        readingSimulation = this,
                        markDispatching = { true },
                    )
                }
            }

            val session = mockk<AiTraceSession>(relaxed = true)
            val recorder =
                mockk<luna.core.ai.AiTraceRecorder>(relaxed = true) {
                    every { startSession(any(), any(), any()) } returns session
                }
            val filter = ChatterNoiseFilter()
            val service = buildService(channelPort = port, filter = filter, traceRecorder = recorder)

            val result = service.processMessage(trigger)

            assertFalse(result) { "Expected processMessage to return false when cancelled" }
            assertFalse(filter.cooldownCache.isOnCooldown(Snowflake(100L))) {
                "Expected no cooldown recorded on cancellation"
            }
            io.mockk.verify(exactly = 1) { session.skip(any(), isNull()) }
            io.mockk.verify(exactly = 0) { session.fail(any(), any()) }
        }
}
