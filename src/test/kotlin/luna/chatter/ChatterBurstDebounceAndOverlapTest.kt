package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.event.Event
import dev.kord.core.event.channel.TypingStartEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import luna.core.ai.AiTraceOutcome
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatterBurstDebounceAndOverlapTest {
    private val fixture = ChatterServiceTestSupport()

    @BeforeEach
    fun setUp() = fixture.setUp()

    @AfterEach
    fun tearDown() = fixture.tearDown()

    @Test
    fun `ambient burst waits debounce window, overlaps reading with AI, types only after both finish, and paces typing delay`() =
        runTest {
            val channelId = 900L
            val timeline = mutableListOf<String>()
            val service =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = fixture.contextCollector,
                    randomProvider = { min, max ->
                        assertEquals(800L, min)
                        assertEquals(2_000L, max)
                        1_600L
                    },
                    messageSender = { _, content ->
                        timeline.add("send:$content@$currentTime")
                        mockk(relaxed = true)
                    },
                )

            coEvery { fixture.channel.type() } coAnswers {
                timeline.add("type@$currentTime")
            }
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } coAnswers {
                delay(200L)
                timeline.add("decide@$currentTime")
                ParticipationDecision(shouldParticipate = true, noul = 0.9)
            }
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } coAnswers {
                delay(300L)
                timeline.add("generate@$currentTime")
                "1234567890" // 10 chars -> 1200ms typing delay
            }

            // Rapid ambient burst: msg1@0ms, msg2@1000ms, msg3@2000ms -> closes at 4500ms
            val r1 = async { service.processMessage(fixture.createMockMessage(id = 1L, channelId = channelId, content = "first")) }
            advanceTimeBy(1_000L)
            runCurrent()
            val r2 = async { service.processMessage(fixture.createMockMessage(id = 2L, channelId = channelId, content = "second")) }
            advanceTimeBy(1_000L)
            runCurrent()
            val r3 = async { service.processMessage(fixture.createMockMessage(id = 3L, channelId = channelId, content = "third")) }

            // Before debounce closes at 4500ms: no AI calls and no typing
            advanceTimeBy(2_499L)
            runCurrent()
            assertTrue(timeline.isEmpty(), "Expected no AI calls before debounce closes, got $timeline")

            // Debounce closes at 4500ms; decide(200ms)->4700ms; generate(300ms)->5000ms; reading finishes at 4500+1600=6100ms
            advanceTimeBy(1L)
            runCurrent()
            advanceTimeBy(600L)
            runCurrent()
            assertEquals(listOf("decide@4700", "generate@5000"), timeline)
            coVerify(exactly = 0) { fixture.channel.type() }

            // Still waiting on reading simulation (1600ms from 4500ms -> 6100ms; currently at 5100ms)
            advanceTimeBy(999L)
            runCurrent()
            coVerify(exactly = 0) { fixture.channel.type() }

            // At 6100ms, both reading and AI have finished -> channel.type() triggers
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("decide@4700", "generate@5000", "type@6100"), timeline)

            // Typing delay = 10 * 120ms = 1200ms -> sends at 7300ms
            advanceTimeBy(1_199L)
            runCurrent()
            assertEquals(3, timeline.size)
            assertFalse(fixture.cooldownCache.isOnCooldown(channelId))

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(
                listOf("decide@4700", "generate@5000", "type@6100", "send:1234567890@7300"),
                timeline,
            )
            assertTrue(fixture.cooldownCache.isOnCooldown(channelId))

            advanceUntilIdle()
            assertFalse(r1.await())
            assertFalse(r2.await())
            assertTrue(r3.await())
        }

    @Test
    fun `when AI latency exceeds reading delay typing starts right after generate and caps typing delay at 4000ms`() =
        runTest {
            val channelId = 901L
            val timeline = mutableListOf<String>()
            val service =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = fixture.contextCollector,
                    randomProvider = { _, _ -> 900L },
                    messageSender = { _, _ ->
                        timeline.add("send@$currentTime")
                        mockk(relaxed = true)
                    },
                )

            coEvery { fixture.channel.type() } coAnswers {
                timeline.add("type@$currentTime")
            }
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } coAnswers {
                delay(1_000L)
                ParticipationDecision(shouldParticipate = true, noul = 0.9)
            }
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } coAnswers {
                delay(1_200L)
                "x".repeat(50) // 50 * 120 = 6000ms -> capped to 4000ms
            }

            val run = async { service.processMessage(fixture.createMockMessage(id = 10L, channelId = channelId, content = "long reply")) }

            // Debounce (2500ms) + max(900ms reading, 2200ms AI) = 4700ms
            advanceTimeBy(4_699L)
            runCurrent()
            coVerify(exactly = 0) { fixture.channel.type() }

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("type@4700"), timeline)

            // Capped at 4000ms -> 4700 + 4000 = 8700ms
            advanceTimeBy(3_999L)
            runCurrent()
            assertEquals(listOf("type@4700"), timeline)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("type@4700", "send@8700"), timeline)
            assertTrue(run.await())
        }

    @Test
    fun `channel type is never called when decision skips or generate returns null, blank, or throws`() =
        runTest {
            val service =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = fixture.contextCollector,
                    randomProvider = { _, _ -> 2_000L },
                )

            // 1. Decision returns false
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.2)
            assertFalse(service.processMessage(fixture.createMockMessage(id = 20L, channelId = 902L, content = "skip me")))

            // 2. Generator returns null
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = true, noul = 0.9)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns null
            assertFalse(service.processMessage(fixture.createMockMessage(id = 21L, channelId = 902L, content = "null gen")))

            // 3. Generator returns blank
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "   "
            assertFalse(service.processMessage(fixture.createMockMessage(id = 22L, channelId = 902L, content = "blank gen")))

            // 4. Generator throws
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } throws IllegalStateException("gen boom")
            assertFalse(service.processMessage(fixture.createMockMessage(id = 23L, channelId = 902L, content = "error gen")))

            coVerify(exactly = 0) { fixture.channel.type() }
            coVerify(exactly = 0) { fixture.channel.createMessage(any<String>()) }
            assertFalse(fixture.cooldownCache.isOnCooldown(902L))
        }

    @Test
    fun `pre-send cancellation records SKIPPED in AI Trace, TypingStartEvent is ignored, and maxInterrupts caps starvation`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val channelId = 903L
        val coordinator = BoundedChannelBurstCoordinator(maxInterrupts = 1)
        val sentReplies = mutableListOf<String>()
        val enteredTyping1 = CompletableDeferred<Unit>()
        val enteredReading2 = CompletableDeferred<Unit>()

        val mockKord = mockk<Kord>(relaxed = true)
        val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 16)
        every { mockKord.coroutineContext } returns EmptyCoroutineContext
        every { mockKord.events } returns eventsFlow

        val service =
            ChatterService(
                filter = fixture.filter,
                decisionClient = fixture.decisionClient,
                chatterGenerator = fixture.chatterGenerator,
                cooldownCache = fixture.cooldownCache,
                historyFetcher = { _, _ -> emptyList() },
                contextCollector = fixture.contextCollector,
                traceRecorder = recorder,
                burstCoordinator = coordinator,
                randomProvider = { _, _ -> 1_000L },
                messageSender = { _, content ->
                    sentReplies.add(content)
                    mockk(relaxed = true)
                },
            )

        val regJob = service.register(mockKord, scope = this)

        coEvery { fixture.decisionClient.decide(any(), any(), any()) } coAnswers {
            val transcript = firstArg<String>()
            if (transcript.contains("msg-2")) {
                enteredReading2.complete(Unit)
                delay(500L)
            }
            ParticipationDecision(shouldParticipate = true, noul = 0.9)
        }
        coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } coAnswers {
            val input = firstArg<ChatterInput>()
            if (input.transcript.contains("msg-1")) {
                "reply-1-long-enough-to-type"
            } else {
                "reply-2-wins-cap"
            }
        }
        coEvery { fixture.channel.type() } coAnswers {
            if (!enteredTyping1.isCompleted) {
                enteredTyping1.complete(Unit)
            }
        }

        val run1 = async { service.processMessage(fixture.createMockMessage(id = 30L, channelId = channelId, authorId = 1L, content = "msg-1")) }
        enteredTyping1.await()

        // Emit TypingStartEvent while typing/running: must have zero effect on coordinator or run1
        val typingEvent = mockk<TypingStartEvent>(relaxed = true)
        every { typingEvent.channelId } returns Snowflake(channelId)
        eventsFlow.emit(typingEvent)
        runCurrent()
        assertTrue(coordinator.isActive(channelId))

        // Interrupt #1 while msg-1 is in pre-send typing delay -> cancels msg-1 and records SKIPPED
        val run2 = async { service.processMessage(fixture.createMockMessage(id = 31L, channelId = channelId, authorId = 2L, content = "msg-2")) }
        enteredReading2.await()

        // msg-3 arrives after maxInterrupts=1 reached -> waits for msg-2 instead of cancelling it
        val run3 = async { service.processMessage(fixture.createMockMessage(id = 32L, channelId = channelId, authorId = 3L, content = "msg-3")) }

        advanceUntilIdle()
        recorder.flushAndClose()

        assertFalse(run1.await())
        assertTrue(run2.await())
        assertFalse(run3.await(), "Queued msg-3 should skip after msg-2 records cooldown")
        assertEquals(listOf("reply-2-wins-cap"), sentReplies)

        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(2, traces.size, "msg-3 skipped at cooldown precheck should emit no trace")
        assertEquals(AiTraceOutcome.SKIPPED, traces[0].outcome)
        assertEquals(AiTraceOutcome.SUCCESS, traces[1].outcome)

        regJob.cancel()
    }

    @Test
    fun `cooldown rejects ambient without disturbing mention burst while mention coalesces same author and ignores bystanders`() =
        runTest {
            val selfId = Snowflake(9999L)
            val aliceId = 5001L
            val bobId = 5002L
            val mentionChannel = 904L
            val cooldownChannel = 905L

            // 1. Cooldown channel: ambient message rejected before debounce opens
            fixture.cooldownCache.record(cooldownChannel, fixture.simulatedTime)
            val coordinator = BoundedChannelBurstCoordinator()
            val sentReplies = mutableListOf<String>()
            val service =
                ChatterService(
                    filter = fixture.filter,
                    decisionClient = fixture.decisionClient,
                    chatterGenerator = fixture.chatterGenerator,
                    cooldownCache = fixture.cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = fixture.contextCollector,
                    burstCoordinator = coordinator,
                    randomProvider = { _, _ -> 800L },
                    messageSender = { _, content ->
                        sentReplies.add(content)
                        mockk(relaxed = true)
                    },
                )

            assertFalse(
                service.processMessage(
                    fixture.createMockMessage(
                        id = 40L,
                        channelId = cooldownChannel,
                        authorId = aliceId,
                        content = "hello during cooldown",
                    ),
                ),
            )
            assertFalse(coordinator.isActive(cooldownChannel))

            // 2. Direct @Luna mention + Bob ambient bystander + Alice non-mention follow-up
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "hi alice"
            val aliceMention =
                fixture.createMockMessage(
                    id = 41L,
                    channelId = mentionChannel,
                    authorId = aliceId,
                    username = "Alice",
                    content = "<@9999> hey",
                    mentionedUserIds = setOf(selfId),
                    selfId = selfId,
                )
            val bobBystander =
                fixture.createMockMessage(
                    id = 42L,
                    channelId = mentionChannel,
                    authorId = bobId,
                    username = "Bob",
                    content = "unrelated ambient",
                    selfId = selfId,
                )
            val aliceFollowUp =
                fixture.createMockMessage(
                    id = 43L,
                    channelId = mentionChannel,
                    authorId = aliceId,
                    username = "Alice",
                    content = "look at this too",
                    selfId = selfId,
                )

            val r1 = async { service.processMessage(aliceMention) }
            advanceTimeBy(400L)
            runCurrent()

            val r2 = async { service.processMessage(bobBystander) }
            runCurrent()
            assertFalse(r2.await(), "Ambient bystander should be ignored cleanly")

            val r3 = async { service.processMessage(aliceFollowUp) }
            advanceUntilIdle()

            assertFalse(r1.await())
            assertTrue(r3.await())
            assertEquals(listOf("hi alice"), sentReplies)
            coVerify(exactly = 0) { fixture.decisionClient.decide(any(), any(), any()) }
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(
                    match<ChatterInput> { it.isDirectMention && it.transcript.contains("look at this too") },
                )
            }
        }
}
