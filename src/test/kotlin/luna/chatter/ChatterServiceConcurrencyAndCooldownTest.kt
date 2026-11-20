package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.socialpeek.model.Author
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.NoOpAiTraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterServiceConcurrencyAndCooldownTest {
    private val fixture = ChatterServiceTestSupport()

    @BeforeEach
    fun setUp() = fixture.setUp()

    @AfterEach
    fun tearDown() = fixture.tearDown()

    @Test
    fun `ChatterService forwards framed decision context with external reply chaining and unframed enriched reply context`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val socialUrl = "https://x.com/kotlin/status/98765"
            val webUrl = "https://example.com/article"
            val externalTarget = fixture.createMockMessage(id = 801L, username = "ExtUser", content = "Original topic question")
            val prior1 = fixture.createMockMessage(id = 810L, username = "Alice", content = "Earlier message $webUrl")
            val triggerMsg =
                fixture.createMockMessage(
                    id = 811L,
                    channelId = 710L,
                    username = "Bob",
                    content = "Check $socialUrl",
                    selfId = botSelfId,
                    referencedMessage = externalTarget,
                )

            val peekPost =
                PeekPost(
                    platform = Platform.X,
                    id = "art-1",
                    originalUrl = socialUrl,
                    cleanUrl = socialUrl,
                    author = Author(username = "Writer"),
                    title = "Article Title",
                    content = "Article summary body",
                )
            val realCollector =
                ChatterContextCollector(
                    noiseFilter = fixture.filter,
                    peekResolver = { peekPost },
                )
            val pipelineService =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> listOf(prior1) },
                    contextCollector = realCollector,
                )

            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "Nice article"

            assertTrue(pipelineService.processMessage(triggerMsg))

            coVerify(exactly = 1) {
                fixture.decisionClient.decide(
                    match { transcript ->
                        transcript.contains("[Bot Identity: Luna | Discord Mention: <@9999>]") &&
                            transcript.contains("[Recent Context]") &&
                            transcript.contains("Alice") &&
                            transcript.contains("Earlier message $webUrl") &&
                            transcript.contains("[Latest Message]") &&
                            transcript.contains("Bob") &&
                            transcript.contains("回覆 ExtUser") &&
                            transcript.contains("Original topic question") &&
                            transcript.contains("Check $socialUrl") &&
                            !transcript.contains("Article Title")
                    },
                    any(),
                    any(),
                )
            }
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        !input.transcript.contains("[Bot Identity:") &&
                            !input.transcript.contains("[Recent Context]") &&
                            !input.transcript.contains("[Latest Message]") &&
                            input.transcript.contains("Alice") &&
                            input.transcript.contains("Earlier message $webUrl") &&
                            input.transcript.contains("Bob") &&
                            input.transcript.contains("回覆 ExtUser") &&
                            input.transcript.contains("Check $socialUrl") &&
                            input.transcript.contains("Article Title") &&
                            input.webUrls == listOf(webUrl)
                    },
                )
            }
        }

    @Test
    fun `same-channel burst coalesces before history and AI while different channels run independently`() =
        runTest {
            val channelId = 700L
            val otherChannelId = 701L
            val historyCalls = AtomicInteger(0)
            val traceSessionStarts = AtomicInteger(0)
            val coordinator = BoundedChannelBurstCoordinator()

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
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ ->
                        historyCalls.incrementAndGet()
                        emptyList()
                    },
                    contextCollector = fixture.contextCollector,
                    traceRecorder = countingTraceRecorder,
                    burstCoordinator = coordinator,
                )

            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "Reply"

            val msg1 = fixture.createMockMessage(id = 7001L, channelId = channelId, username = "Alice", content = "first ambient")
            val msg2Burst = fixture.createMockMessage(id = 7002L, channelId = channelId, username = "Bob", content = "second ambient")
            val msgOtherChannel = fixture.createMockMessage(id = 7003L, channelId = otherChannelId, username = "Cara", content = "other channel")

            val firstRun = async { guardedService.processMessage(msg1) }
            val burstRun = async { guardedService.processMessage(msg2Burst) }
            val otherChannelRun = async { guardedService.processMessage(msgOtherChannel) }

            assertFalse(firstRun.await())
            assertTrue(burstRun.await())
            assertTrue(otherChannelRun.await())

            // Coalesced channel 700L invoked history/trace/decision once for the winning burst message + 1 for channel 701L
            assertEquals(2, historyCalls.get())
            assertEquals(2, traceSessionStarts.get())
            coVerify(exactly = 2) { fixture.decisionClient.decide(any(), any(), any()) }
            assertFalse(coordinator.isActive(channelId))
            assertFalse(coordinator.isActive(otherChannelId))
            assertEquals(0, coordinator.size)
        }

    @Test
    fun `direct bot mention interrupts active ambient run and coordinator releases channel state on exception`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 702L
            val coordinator = BoundedChannelBurstCoordinator()
            val ambientEnteredDecision = CompletableDeferred<Unit>()
            val historyCalls = AtomicInteger(0)

            val guardedService =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ ->
                        historyCalls.incrementAndGet()
                        emptyList()
                    },
                    contextCollector = fixture.contextCollector,
                    burstCoordinator = coordinator,
                )

            coEvery { fixture.decisionClient.decide(any(), any(), any()) } coAnswers {
                val transcript = firstArg<String>()
                if (transcript.contains("ambient in-flight")) {
                    ambientEnteredDecision.complete(Unit)
                    kotlinx.coroutines.delay(5_000L)
                }
                ParticipationDecision(shouldParticipate = false, noul = 0.2)
            }
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } throws RuntimeException("simulated generator failure")

            val ambient1 = fixture.createMockMessage(id = 7101L, channelId = channelId, username = "Alice", content = "ambient in-flight")
            val mentionMsg =
                fixture.createMockMessage(
                    id = 7102L,
                    channelId = channelId,
                    username = "Bob",
                    content = "<@9999> urgent question",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            val ambientAfterCleanup =
                fixture.createMockMessage(id = 7104L, channelId = channelId, username = "Dave", content = "after cleanup")

            val ambientRun = async { guardedService.processMessage(ambient1) }
            ambientEnteredDecision.await()
            assertTrue(coordinator.isActive(channelId))

            // Direct @bot mention cancels in-flight ambient run and fails gracefully on generator exception
            assertFalse(guardedService.processMessage(mentionMsg))
            assertFalse(ambientRun.await())
            assertFalse(coordinator.isActive(channelId))
            assertEquals(0, coordinator.size)

            // Subsequent ambient message proceeds normally after exception cleanup
            val historyBefore = historyCalls.get()
            assertFalse(guardedService.processMessage(ambientAfterCleanup))
            assertEquals(historyBefore + 1, historyCalls.get())
            assertFalse(coordinator.isActive(channelId))
            assertEquals(0, coordinator.size)

            // Unhandled exception from startSession still releases channel state via finally
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
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = fixture.contextCollector,
                    traceRecorder = throwingTraceRecorder,
                    burstCoordinator = coordinator,
                )
            val failingMention =
                fixture.createMockMessage(
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
            assertFalse(coordinator.isActive(703L))
            assertEquals(0, coordinator.size)
        }

    @Test
    fun `default 5 second cooldown blocks ambient before 5s, allows direct mention during cooldown, and allows ambient at 5s`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 704L

            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "Hello!"

            // Initial ambient reply starts the 5s cooldown at simulatedTime
            val firstAmbient = fixture.createMockMessage(id = 7201L, channelId = channelId, username = "Alice", content = "anyone here?")
            assertTrue(fixture.service.processMessage(firstAmbient))
            coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any(), any()) }

            // 4.999s later: ambient message is blocked by default 5s cooldown
            fixture.simulatedTime += 4_999L
            val blockedAmbient = fixture.createMockMessage(id = 7202L, channelId = channelId, username = "Bob", content = "still blocked")
            assertFalse(fixture.service.processMessage(blockedAmbient))
            coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any(), any()) }

            // Direct @bot mention during the 5s cooldown still bypasses cooldown and replies
            val mentionDuringCooldown =
                fixture.createMockMessage(
                    id = 7203L,
                    channelId = channelId,
                    username = "Cara",
                    content = "<@9999> ping",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            assertTrue(fixture.service.processMessage(mentionDuringCooldown))
            coVerify(exactly = 2) { fixture.chatterGenerator.generateReply(any<ChatterInput>()) }

            // Advance 5.000s after the mention reply: ambient message is allowed again
            fixture.simulatedTime += 5_000L
            val allowedAmbient = fixture.createMockMessage(id = 7204L, channelId = channelId, username = "Dave", content = "cooldown expired")
            assertTrue(fixture.service.processMessage(allowedAmbient))
            coVerify(exactly = 2) { fixture.decisionClient.decide(any(), any(), any()) }
        }
}
