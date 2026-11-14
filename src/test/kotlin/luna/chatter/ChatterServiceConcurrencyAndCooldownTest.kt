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

            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "Nice article"

            assertTrue(pipelineService.processMessage(triggerMsg))

            coVerify(exactly = 1) {
                fixture.decisionClient.decide(
                    match { transcript ->
                        transcript.contains("[Bot Identity: Luna | Discord Mention: <@9999>]") &&
                            transcript.contains("[Recent Context]") &&
                            transcript.contains("[Alice]: Earlier message $webUrl") &&
                            transcript.contains("[Latest Message]") &&
                            transcript.contains("[Bob 回覆 ExtUser「Original topic question」]: Check $socialUrl") &&
                            !transcript.contains("Article Title")
                    },
                    any(),
                )
            }
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(
                    match<ChatterInput> { input ->
                        !input.transcript.contains("[Bot Identity:") &&
                            !input.transcript.contains("[Recent Context]") &&
                            !input.transcript.contains("[Latest Message]") &&
                            input.transcript.contains("[Alice]: Earlier message $webUrl") &&
                            input.transcript.contains("[Bob 回覆 ExtUser「Original topic question」]: Check $socialUrl") &&
                            input.transcript.contains("Article Title") &&
                            input.webUrls == listOf(webUrl)
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
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { msg, _ ->
                        historyCalls.incrementAndGet()
                        if (msg.channelId == Snowflake(channelId)) {
                            emptyList()
                        } else {
                            emptyList()
                        }
                    },
                    contextCollector = fixture.contextCollector,
                    traceRecorder = countingTraceRecorder,
                    inFlightTracker = tracker,
                )

            coEvery { fixture.decisionClient.decide(any(), any()) } coAnswers {
                val transcript = firstArg<String>()
                if (transcript.contains("first ambient")) {
                    enteredDecision.complete(Unit)
                    releaseDecision.await()
                }
                ParticipationDecision(shouldParticipate = true, noul = 0.9)
            }
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "Reply"

            val msg1 = fixture.createMockMessage(id = 7001L, channelId = channelId, username = "Alice", content = "first ambient")
            val msg2Concurrent = fixture.createMockMessage(id = 7002L, channelId = channelId, username = "Bob", content = "second ambient")
            val msgOtherChannel = fixture.createMockMessage(id = 7003L, channelId = otherChannelId, username = "Cara", content = "other channel")

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
            coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any()) }

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
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ ->
                        historyCalls.incrementAndGet()
                        emptyList()
                    },
                    contextCollector = fixture.contextCollector,
                    inFlightTracker = tracker,
                )

            coEvery { fixture.decisionClient.decide(any(), any()) } coAnswers {
                val transcript = firstArg<String>()
                if (transcript.contains("ambient in-flight")) {
                    ambientEnteredDecision.complete(Unit)
                    releaseAmbientDecision.await()
                }
                ParticipationDecision(shouldParticipate = false, noul = 0.2)
            }
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } coAnswers {
                mentionEnteredGenerate.complete(Unit)
                releaseMentionGenerate.await()
                throw RuntimeException("simulated generator failure")
            }

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
            val ambient2WhileMentionRuns =
                fixture.createMockMessage(id = 7103L, channelId = channelId, username = "Cara", content = "ambient burst")
            val ambient3AfterCleanup =
                fixture.createMockMessage(id = 7104L, channelId = channelId, username = "Dave", content = "after cleanup")

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
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = fixture.contextCollector,
                    traceRecorder = throwingTraceRecorder,
                    inFlightTracker = tracker,
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
            assertFalse(tracker.isInFlight(703L))
            assertEquals(0, tracker.activeCount(703L))
        }

    @Test
    fun `default 5 second cooldown blocks ambient before 5s, allows direct mention during cooldown, and allows ambient at 5s`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val channelId = 704L

            coEvery { fixture.decisionClient.decide(any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "Hello!"

            // Initial ambient reply starts the 5s cooldown at simulatedTime
            val firstAmbient = fixture.createMockMessage(id = 7201L, channelId = channelId, username = "Alice", content = "anyone here?")
            assertTrue(fixture.service.processMessage(firstAmbient))
            coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any()) }

            // 4.999s later: ambient message is blocked by default 5s cooldown
            fixture.simulatedTime += 4_999L
            val blockedAmbient = fixture.createMockMessage(id = 7202L, channelId = channelId, username = "Bob", content = "still blocked")
            assertFalse(fixture.service.processMessage(blockedAmbient))
            coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any()) }

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
            coVerify(exactly = 2) { fixture.decisionClient.decide(any(), any()) }
        }
}
