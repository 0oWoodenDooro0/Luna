package luna.chatter

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedChannelBurstCoordinatorTest {
    @Test
    fun `single ambient message waits full debounceMs before executing and cleans up channel state`() =
        runTest {
            val coordinator = BoundedChannelBurstCoordinator()
            val executedAt = mutableListOf<Long>()

            val run =
                async {
                    coordinator.executeWithDebounce(
                        channelId = Snowflake(100L),
                        messageId = Snowflake(1L),
                        authorId = Snowflake(10L),
                        isDirectMention = false,
                    ) { _, _ ->
                        executedAt.add(currentTime)
                        true
                    }
                }

            advanceTimeBy(2_499L)
            runCurrent()
            assertTrue(executedAt.isEmpty(), "Execution must not begin before 2500ms debounce closes")
            assertTrue(coordinator.isActive(100L))

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(2_500L), executedAt)
            assertTrue(run.await())
            assertFalse(coordinator.isActive(100L))
            assertEquals(0, coordinator.size)
        }

    @Test
    fun `ambient burst extends debounce window and flushes at maxDebounceMs ceiling from initial trigger`() =
        runTest {
            val coordinator = BoundedChannelBurstCoordinator()
            val executedMessages = mutableListOf<Pair<Long, Long>>()

            val r1 =
                async {
                    coordinator.executeWithDebounce(Snowflake(100L), Snowflake(1L), Snowflake(10L), false) { _, _ ->
                        executedMessages.add(1L to currentTime)
                        true
                    }
                }
            advanceTimeBy(2_000L)
            runCurrent()

            val r2 =
                async {
                    coordinator.executeWithDebounce(Snowflake(100L), Snowflake(2L), Snowflake(11L), false) { _, _ ->
                        executedMessages.add(2L to currentTime)
                        true
                    }
                }
            advanceTimeBy(2_000L)
            runCurrent()

            val r3 =
                async {
                    coordinator.executeWithDebounce(Snowflake(100L), Snowflake(3L), Snowflake(12L), false) { _, _ ->
                        executedMessages.add(3L to currentTime)
                        true
                    }
                }
            advanceTimeBy(2_000L)
            runCurrent()

            // t = 6000ms: msg4 extends, but must flush at 7000ms ceiling (not 8500ms)
            val r4 =
                async {
                    coordinator.executeWithDebounce(Snowflake(100L), Snowflake(4L), Snowflake(13L), false) { _, _ ->
                        executedMessages.add(4L to currentTime)
                        true
                    }
                }
            advanceTimeBy(999L)
            runCurrent()
            assertTrue(executedMessages.isEmpty(), "Must still wait until 7000ms max ceiling")

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(4L to 7_000L), executedMessages)

            advanceUntilIdle()
            assertFalse(r1.await())
            assertFalse(r2.await())
            assertFalse(r3.await())
            assertTrue(r4.await())
        }

    @Test
    fun `direct mention uses mentionDebounceMs, coalesces same-author follow-ups, and ignores ambient bystanders`() =
        runTest {
            val coordinator = BoundedChannelBurstCoordinator()
            val alice = Snowflake(42L)
            val bob = Snowflake(99L)
            val executed = mutableListOf<Triple<Long, Boolean, Long>>()

            val r1 =
                async {
                    coordinator.executeWithDebounce(Snowflake(200L), Snowflake(1L), alice, isDirectMention = true) { isMention, _ ->
                        executed.add(Triple(1L, isMention, currentTime))
                        true
                    }
                }
            advanceTimeBy(500L)
            runCurrent()

            // Bob's ambient bystander message must be ignored completely without extending or cancelling Alice's mention
            val bystander =
                async {
                    coordinator.executeWithDebounce(Snowflake(200L), Snowflake(2L), bob, isDirectMention = false) { isMention, _ ->
                        executed.add(Triple(2L, isMention, currentTime))
                        true
                    }
                }
            runCurrent()
            assertFalse(bystander.await())

            // Alice's non-mention follow-up at t=800ms must coalesce and preserve effectiveIsDirectMention=true
            advanceTimeBy(300L)
            runCurrent()
            val r3 =
                async {
                    coordinator.executeWithDebounce(Snowflake(200L), Snowflake(3L), alice, isDirectMention = false) { isMention, _ ->
                        executed.add(Triple(3L, isMention, currentTime))
                        true
                    }
                }

            // Should not fire at t=1200ms because Alice extended at t=800ms -> closes at t=2000ms
            advanceTimeBy(400L)
            runCurrent()
            assertTrue(executed.isEmpty())

            advanceTimeBy(800L)
            runCurrent()
            assertEquals(listOf(Triple(3L, true, 2_000L)), executed)

            advanceUntilIdle()
            assertFalse(r1.await())
            assertTrue(r3.await())
        }

    @Test
    fun `different author direct mention waits for active direct mention instead of cancelling it`() =
        runTest {
            val coordinator = BoundedChannelBurstCoordinator()
            val alice = Snowflake(42L)
            val bob = Snowflake(99L)
            val aliceEntered = CompletableDeferred<Unit>()
            val releaseAlice = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()

            val aliceRun =
                async {
                    coordinator.executeWithDebounce(Snowflake(201L), Snowflake(1L), alice, isDirectMention = true) { _, _ ->
                        order.add("alice-start")
                        aliceEntered.complete(Unit)
                        releaseAlice.await()
                        order.add("alice-end")
                        true
                    }
                }

            aliceEntered.await()

            val bobRun =
                async {
                    coordinator.executeWithDebounce(Snowflake(201L), Snowflake(2L), bob, isDirectMention = true) { _, _ ->
                        order.add("bob-run")
                        true
                    }
                }

            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(listOf("alice-start"), order, "Bob's mention must wait until Alice finishes")

            releaseAlice.complete(Unit)
            advanceUntilIdle()

            assertTrue(aliceRun.await())
            assertTrue(bobRun.await())
            assertEquals(listOf("alice-start", "alice-end", "bob-run"), order)
        }

    @Test
    fun `pre-send cancellation restarts up to maxInterrupts and locks active run thereafter`() =
        runTest {
            val coordinator = BoundedChannelBurstCoordinator(maxInterrupts = 2)
            val channelId = Snowflake(300L)
            val entered1 = CompletableDeferred<Unit>()
            val entered2 = CompletableDeferred<Unit>()
            val entered3 = CompletableDeferred<Unit>()
            val release3 = CompletableDeferred<Unit>()
            val cancelledCount = AtomicInteger(0)
            val recheckCalls = AtomicInteger(0)
            val completedIds = mutableListOf<Long>()

            val r1 =
                async {
                    coordinator.executeWithDebounce(channelId, Snowflake(1L), Snowflake(10L), false) { _, _ ->
                        try {
                            entered1.complete(Unit)
                            delay(5_000L)
                            completedIds.add(1L)
                            true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            cancelledCount.incrementAndGet()
                            throw e
                        }
                    }
                }
            entered1.await()

            // Interrupt #1
            val r2 =
                async {
                    coordinator.executeWithDebounce(channelId, Snowflake(2L), Snowflake(11L), false) { _, _ ->
                        try {
                            entered2.complete(Unit)
                            delay(5_000L)
                            completedIds.add(2L)
                            true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            cancelledCount.incrementAndGet()
                            throw e
                        }
                    }
                }
            entered2.await()
            assertEquals(1, cancelledCount.get())

            // Interrupt #2
            val r3 =
                async {
                    coordinator.executeWithDebounce(channelId, Snowflake(3L), Snowflake(12L), false) { _, _ ->
                        try {
                            entered3.complete(Unit)
                            release3.await()
                            completedIds.add(3L)
                            true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            cancelledCount.incrementAndGet()
                            throw e
                        }
                    }
                }
            entered3.await()
            assertEquals(2, cancelledCount.get())

            // Message #4 arrives after 2 interrupts -> must NOT cancel r3; waits and then re-checks canProceedAfterWait
            val r4 =
                async {
                    coordinator.executeWithDebounce(
                        channelId = channelId,
                        messageId = Snowflake(4L),
                        authorId = Snowflake(13L),
                        isDirectMention = false,
                        canProceedAfterWait = {
                            recheckCalls.incrementAndGet()
                            false
                        },
                    ) { _, _ ->
                        completedIds.add(4L)
                        true
                    }
                }

            advanceTimeBy(3_000L)
            runCurrent()
            assertEquals(2, cancelledCount.get(), "Locked run #3 must not be cancelled")
            assertEquals(0, recheckCalls.get(), "Waiting run #4 must wait until #3 completes")

            release3.complete(Unit)
            advanceUntilIdle()

            assertFalse(r1.await())
            assertFalse(r2.await())
            assertTrue(r3.await())
            assertFalse(r4.await())
            assertEquals(1, recheckCalls.get())
            assertEquals(listOf(3L), completedIds)
        }

    @Test
    fun `markDispatching locks active run against cancellation`() =
        runTest {
            val coordinator = BoundedChannelBurstCoordinator(maxInterrupts = 2)
            val channelId = Snowflake(400L)
            val enteredDispatch = CompletableDeferred<Unit>()
            val releaseDispatch = CompletableDeferred<Unit>()
            val executed = mutableListOf<Long>()

            val r1 =
                async {
                    coordinator.executeWithDebounce(channelId, Snowflake(1L), Snowflake(10L), false) { _, markDispatching ->
                        assertTrue(markDispatching())
                        enteredDispatch.complete(Unit)
                        releaseDispatch.await()
                        executed.add(1L)
                        true
                    }
                }
            enteredDispatch.await()

            val r2 =
                async {
                    coordinator.executeWithDebounce(channelId, Snowflake(2L), Snowflake(11L), false) { _, _ ->
                        executed.add(2L)
                        true
                    }
                }
            advanceTimeBy(3_000L)
            runCurrent()
            assertTrue(executed.isEmpty(), "Run #2 must wait while Run #1 is locked dispatching")

            releaseDispatch.complete(Unit)
            advanceUntilIdle()
            assertTrue(r1.await())
            assertTrue(r2.await())
            assertEquals(listOf(1L, 2L), executed)
        }

    @Test
    fun `rejects non-positive maxEntries and clamps or falls back debounce bounds safely`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                BoundedChannelBurstCoordinator(maxEntries = 0)
            }
            assertFailsWith<IllegalArgumentException> {
                BoundedChannelBurstCoordinator(maxEntries = -1)
            }

            val coordinator =
                BoundedChannelBurstCoordinator(
                    debounceMs = 0L,
                    mentionDebounceMs = -500L,
                    maxDebounceMs = 2_000L,
                    maxInterrupts = -2,
                )
            assertEquals(2_500L, coordinator.debounceMs)
            assertEquals(1_200L, coordinator.mentionDebounceMs)
            assertEquals(6_000L, coordinator.maxDebounceMs)
            assertEquals(2, coordinator.maxInterrupts)

            val highMax = BoundedChannelBurstCoordinator(maxDebounceMs = 15_000L)
            assertEquals(8_000L, highMax.maxDebounceMs)

            val zeroMax = BoundedChannelBurstCoordinator(maxDebounceMs = 0L)
            assertEquals(7_000L, zeroMax.maxDebounceMs)
        }

    @Test
    fun `evicts eldest channel entry when active channels exceed maxEntries`() =
        runTest {
            val coordinator = BoundedChannelBurstCoordinator(maxEntries = 2)
            val gate1 = CompletableDeferred<Unit>()
            val gate2 = CompletableDeferred<Unit>()
            val entered1 = CompletableDeferred<Unit>()
            val entered2 = CompletableDeferred<Unit>()

            val r1 =
                async {
                    coordinator.executeWithDebounce(Snowflake(1L), Snowflake(101L), Snowflake(10L), false) { _, _ ->
                        entered1.complete(Unit)
                        gate1.await()
                        true
                    }
                }
            entered1.await()

            val r2 =
                async {
                    coordinator.executeWithDebounce(Snowflake(2L), Snowflake(102L), Snowflake(11L), false) { _, _ ->
                        entered2.complete(Unit)
                        gate2.await()
                        true
                    }
                }
            entered2.await()
            assertEquals(2, coordinator.size)

            val r3 =
                async {
                    coordinator.executeWithDebounce(Snowflake(3L), Snowflake(103L), Snowflake(12L), false) { _, _ ->
                        true
                    }
                }
            advanceUntilIdle()

            assertFalse(r1.await(), "Eldest channel 1 should have been evicted and cancelled")
            assertFalse(coordinator.isActive(1L))
            gate2.complete(Unit)
            assertTrue(r2.await())
            assertTrue(r3.await())
            assertEquals(0, coordinator.size)
        }
}
