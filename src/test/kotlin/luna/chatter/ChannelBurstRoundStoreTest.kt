package luna.chatter

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChannelBurstRoundStoreTest {
    private fun createStore(
        debounceMs: Long = 2_500L,
        mentionDebounceMs: Long = 500L,
        maxInterrupts: Int = 2,
        maxEntries: Int = 10,
    ): ChannelBurstRoundStore =
        ChannelBurstRoundStore(
            debounceMs = debounceMs,
            mentionDebounceMs = mentionDebounceMs,
            maxInterrupts = maxInterrupts,
            maxEntries = maxEntries,
        )

    @Test
    fun `single round transitions through debouncing, executing, dispatching, and cleanup`() =
        runTest {
            val store = createStore()
            val channelId = Snowflake(100L)

            val action = store.openOrJoin(channelId, Snowflake(10L), DirectInteractionType.NONE)
            val runDebounce = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(action)
            assertTrue(store.isActive(channelId), "Channel should be active after opening round")
            assertEquals(1, store.size)

            val snapshot = store.pollDebounceStep(runDebounce.round)
            assertEquals(2_500L, snapshot.waitMs)
            assertFalse(snapshot.evictedOrDone, "Fresh round should not be evicted or done")
            assertFalse(store.hasDebounceChanged(runDebounce.round, snapshot.seqBefore), "Sequence should not have changed")

            val outcome = store.finishDebounce(runDebounce.round, runDebounce.myToken)
            assertTrue(outcome.shouldExecute, "Single runner should win debounce")
            assertEquals(DirectInteractionType.NONE, outcome.effectiveDirectInteraction)

            val job = Job()
            assertTrue(store.attachExecutionJob(channelId, runDebounce.round, job), "Should attach job while EXECUTING")
            assertTrue(store.markDispatching(runDebounce.round), "Should transition EXECUTING -> DISPATCHING")
            assertFalse(store.markDispatching(runDebounce.round), "Second markDispatching should fail once DISPATCHING")
            assertFalse(store.isInterruptedOrEvicted(runDebounce.round), "Round should not be interrupted or evicted")

            store.completePhase2(channelId, runDebounce.round)
            assertFalse(store.isActive(channelId), "Channel should be inactive after Phase 2 completion")
            assertEquals(0, store.size)
        }

    @Test
    fun `coalescing in debouncing hands execution to latest joiner and upgrades ambient to direct mention`() =
        runTest {
            val store = createStore()
            val channelId = Snowflake(100L)

            val first = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(channelId, Snowflake(10L), DirectInteractionType.NONE))
            val initialSnapshot = store.pollDebounceStep(first.round)

            val second =
                assertIs<ChannelBurstRoundStore.JoinAction.Coalesced>(
                    store.openOrJoin(channelId, Snowflake(11L), DirectInteractionType.MENTION),
                )
            assertTrue(store.hasDebounceChanged(first.round, initialSnapshot.seqBefore), "Coalesce should bump sequence")
            assertTrue(initialSnapshot.wakeup.isCompleted, "Coalesce should complete previous wakeup")

            val upgradedSnapshot = store.pollDebounceStep(first.round)
            assertEquals(500L, upgradedSnapshot.waitMs)

            val runnerOutcome = store.finishDebounce(first.round, first.myToken)
            assertFalse(runnerOutcome.shouldExecute, "Initial runner should yield to coalescer")
            assertEquals(DirectInteractionType.MENTION, runnerOutcome.effectiveDirectInteraction)

            val coalescedOutcome = second.deferred.await()
            assertTrue(coalescedOutcome.shouldExecute, "Latest coalescer should execute")
            assertEquals(DirectInteractionType.MENTION, coalescedOutcome.effectiveDirectInteraction)
        }

    @Test
    fun `mention burst ignores bystander ambient messages and queues competing direct mention authors`() =
        runTest {
            val store = createStore()
            val channelId = Snowflake(100L)
            val authorA = Snowflake(10L)
            val authorB = Snowflake(20L)
            val authorC = Snowflake(30L)

            val first = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(channelId, authorA, DirectInteractionType.MENTION))

            val bystander = store.openOrJoin(channelId, authorB, DirectInteractionType.NONE)
            assertIs<ChannelBurstRoundStore.JoinAction.Ignore>(bystander)

            val competing = store.openOrJoin(channelId, authorC, DirectInteractionType.DIRECT_REPLY)
            val waitAction = assertIs<ChannelBurstRoundStore.JoinAction.WaitForCompletion>(competing)
            assertFalse(waitAction.completion.isCompleted, "Competing author should wait until round completes")

            val sameAuthor = store.openOrJoin(channelId, authorA, DirectInteractionType.NONE)
            assertIs<ChannelBurstRoundStore.JoinAction.Coalesced>(sameAuthor)

            store.finishDebounce(first.round, first.myToken)
            store.completePhase2(channelId, first.round)
            assertTrue(waitAction.completion.isCompleted, "Completion deferred should resolve after Phase 2")
        }

    @Test
    fun `cancelCoalescedWaiter cleans up executing owner round when waiter fails`() =
        runTest {
            val store = createStore()
            val channelId = Snowflake(100L)

            val first = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(channelId, Snowflake(10L), DirectInteractionType.NONE))
            val second = assertIs<ChannelBurstRoundStore.JoinAction.Coalesced>(store.openOrJoin(channelId, Snowflake(11L), DirectInteractionType.NONE))
            store.finishDebounce(first.round, first.myToken)

            store.cancelCoalescedWaiter(channelId, second.round, second.myToken)
            assertFalse(store.isActive(channelId), "Cancelling executing owner waiter should clean up active round")
        }

    @Test
    fun `executing round is interrupted up to maxInterrupts and locks at cap or during dispatching`() =
        runTest {
            val store = createStore(maxInterrupts = 1)
            val channelId = Snowflake(100L)
            val authorA = Snowflake(10L)

            val first = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(channelId, authorA, DirectInteractionType.MENTION))
            store.finishDebounce(first.round, first.myToken)
            val job1 = Job()
            assertTrue(store.attachExecutionJob(channelId, first.round, job1), "First job should attach")

            // Interrupt #1 (within maxInterrupts = 1)
            val second = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(channelId, authorA, DirectInteractionType.NONE))
            assertTrue(job1.isCancelled, "Previous execution job should be cancelled on interrupt")
            assertTrue(store.isInterruptedOrEvicted(first.round), "Interrupted round should report interrupted")
            assertEquals(DirectInteractionType.MENTION, second.initialDirectInteraction)

            store.finishDebounce(second.round, second.myToken)
            val job2 = Job()
            assertTrue(store.attachExecutionJob(channelId, second.round, job2), "Second job should attach")

            // Interrupt #2 exceeds maxInterrupts = 1 -> WaitForCompletion
            val atCap = store.openOrJoin(channelId, authorA, DirectInteractionType.MENTION)
            assertIs<ChannelBurstRoundStore.JoinAction.WaitForCompletion>(atCap)
            assertFalse(job2.isCancelled, "Job must not be cancelled when maxInterrupts cap is reached")

            // Dispatching also forces WaitForCompletion
            assertTrue(store.markDispatching(second.round), "Should enter DISPATCHING")
            val whileDispatching = store.openOrJoin(channelId, authorA, DirectInteractionType.MENTION)
            assertIs<ChannelBurstRoundStore.JoinAction.WaitForCompletion>(whileDispatching)
        }

    @Test
    fun `failDebounce marks round done, removes it, and cancels pending waiters`() =
        runTest {
            val store = createStore()
            val channelId = Snowflake(100L)

            val first = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(channelId, Snowflake(10L), DirectInteractionType.NONE))
            val waiter = assertIs<ChannelBurstRoundStore.JoinAction.Coalesced>(store.openOrJoin(channelId, Snowflake(11L), DirectInteractionType.NONE))

            store.failDebounce(channelId, first.round)

            assertFalse(store.isActive(channelId), "Channel should be inactive after failDebounce")
            val outcome = waiter.deferred.await()
            assertFalse(outcome.shouldExecute, "Waiter should receive shouldExecute=false on failure")
        }

    @Test
    fun `LRU eviction aborts eldest round, completes waiters, and cancels execution job`() =
        runTest {
            val store = createStore(maxEntries = 2)
            val ch1 = Snowflake(1L)
            val ch2 = Snowflake(2L)
            val ch3 = Snowflake(3L)
            val ch4 = Snowflake(4L)

            // ch1 stays in DEBOUNCING with a pending waiter
            val r1 = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(ch1, Snowflake(10L), DirectInteractionType.NONE))
            val r1Waiter = assertIs<ChannelBurstRoundStore.JoinAction.Coalesced>(store.openOrJoin(ch1, Snowflake(11L), DirectInteractionType.NONE))

            // ch2 transitions to EXECUTING with an attached Job
            val r2 = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(ch2, Snowflake(20L), DirectInteractionType.NONE))
            store.finishDebounce(r2.round, r2.myToken)
            val job2 = Job()
            assertTrue(store.attachExecutionJob(ch2, r2.round, job2), "Should attach job to ch2")
            assertEquals(2, store.size)

            // Opening ch3 evicts ch1 (DEBOUNCING)
            assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(ch3, Snowflake(30L), DirectInteractionType.NONE))
            assertEquals(2, store.size)
            assertFalse(store.isActive(ch1), "Eldest channel ch1 should be evicted")
            assertTrue(store.isInterruptedOrEvicted(r1.round), "Evicted round r1 should report evicted")
            assertFalse(r1Waiter.deferred.await().shouldExecute, "Evicted debouncing waiter should not execute")

            // Opening ch4 evicts ch2 (EXECUTING)
            assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(ch4, Snowflake(40L), DirectInteractionType.NONE))
            assertFalse(store.isActive(ch2), "Eldest channel ch2 should be evicted")
            assertTrue(store.isActive(ch3), "ch3 should remain active")
            assertTrue(store.isActive(ch4), "ch4 should be active")
            assertTrue(store.isInterruptedOrEvicted(r2.round), "Evicted round r2 should report evicted")
            assertTrue(job2.isCancelled, "Evicted round execution job should be cancelled")
        }

    @Test
    fun `clear aborts all active rounds and resets store size`() =
        runTest {
            val store = createStore()
            val ch1 = Snowflake(1L)
            val ch2 = Snowflake(2L)

            val r1 = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(ch1, Snowflake(10L), DirectInteractionType.NONE))
            val snap1 = store.pollDebounceStep(r1.round)

            val r2 = assertIs<ChannelBurstRoundStore.JoinAction.RunDebounce>(store.openOrJoin(ch2, Snowflake(20L), DirectInteractionType.NONE))
            store.finishDebounce(r2.round, r2.myToken)
            val job2 = Job()
            assertTrue(store.attachExecutionJob(ch2, r2.round, job2), "Should attach job to ch2")

            store.clear()

            assertEquals(0, store.size)
            assertFalse(store.isActive(ch1), "ch1 should be inactive after clear")
            assertFalse(store.isActive(ch2), "ch2 should be inactive after clear")
            assertTrue(snap1.wakeup.isCompleted, "Debouncing wakeup should be completed on clear")
            assertTrue(job2.isCancelled, "Executing job should be cancelled on clear")
        }
}
