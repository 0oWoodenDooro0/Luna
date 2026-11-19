package luna.chatter

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.LinkedHashMap

internal class BurstInterruptedCancellationException(
    message: String,
) : CancellationException(message)

/**
 * Thread-safe, capacity-bounded LRU burst-debounce and pre-send cancellation coordinator for Chatter.
 *
 * Replaces immediate fire-or-drop in-flight tracking with per-channel virtual-time-compatible:
 * 1. Message-driven burst debounce window (extending up to [maxDebounceMs] from initial trigger).
 * 2. Direct `@Luna` mention priority ([mentionDebounceMs]), same-author coalescing, bystander ambient isolation,
 *    and queuing of competing direct mentions from different authors.
 * 3. Pre-send cancellation & restart capped at [maxInterrupts] per round, plus dispatch-time locking.
 *
 * Enforces an upper capacity bound ([maxEntries], default 500) to adhere to CODING_STANDARDS.md §1.
 */
class BoundedChannelBurstCoordinator(
    debounceMs: Long = ChatterConfig.DEFAULT_DEBOUNCE_MILLIS,
    mentionDebounceMs: Long = ChatterConfig.DEFAULT_MENTION_DEBOUNCE_MILLIS,
    maxDebounceMs: Long = ChatterConfig.DEFAULT_DEBOUNCE_MAX_MILLIS,
    maxInterrupts: Int = ChatterConfig.DEFAULT_MAX_INTERRUPTS,
    val maxEntries: Int = DEFAULT_MAX_CAPACITY,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, got $maxEntries" }
    }

    val debounceMs: Long = normalizePositiveMs(debounceMs, ChatterConfig.DEFAULT_DEBOUNCE_MILLIS, "debounceMs")
    val mentionDebounceMs: Long =
        normalizePositiveMs(mentionDebounceMs, ChatterConfig.DEFAULT_MENTION_DEBOUNCE_MILLIS, "mentionDebounceMs")
    val maxDebounceMs: Long = normalizeMaxDebounceMs(maxDebounceMs)
    val maxInterrupts: Int = normalizeNonNegativeInterrupts(maxInterrupts)

    private enum class Phase {
        DEBOUNCING,
        EXECUTING,
        DISPATCHING,
        DONE,
    }

    private data class HandoffOutcome(
        val shouldExecute: Boolean,
        val effectiveIsDirectMention: Boolean,
    )

    private class ChannelRound(
        val channelId: Snowflake,
        var isMentionBurst: Boolean,
        var mentionAuthorId: Snowflake?,
        var nextDebounceMs: Long,
        var ownerToken: Long,
        var interruptCount: Int = 0,
    ) {
        var phase: Phase = Phase.DEBOUNCING
        var coalesceSeq: Long = 0L
        var debounceWakeup: CompletableDeferred<Unit> = CompletableDeferred()
        val waiters: MutableMap<Long, CompletableDeferred<HandoffOutcome>> = mutableMapOf()
        var executionJob: Job? = null
        var interrupted: Boolean = false
        var evicted: Boolean = false
        val completion: CompletableDeferred<Unit> = CompletableDeferred()
    }

    private sealed interface JoinAction {
        data class RunDebounce(
            val round: ChannelRound,
            val myToken: Long,
            val evictedRound: ChannelRound?,
        ) : JoinAction

        data class Coalesced(
            val round: ChannelRound,
            val myToken: Long,
            val deferred: CompletableDeferred<HandoffOutcome>,
        ) : JoinAction

        data class WaitForCompletion(
            val completion: CompletableDeferred<Unit>,
        ) : JoinAction

        data object Ignore : JoinAction
    }

    private val lock = Any()
    private var nextToken: Long = 0L
    private var pendingEviction: ChannelRound? = null

    private val rounds: MutableMap<Snowflake, ChannelRound> =
        object : LinkedHashMap<Snowflake, ChannelRound>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, ChannelRound>?): Boolean {
                if (size > maxEntries && eldest != null) {
                    eldest.value.evicted = true
                    pendingEviction = eldest.value
                    return true
                }
                return false
            }
        }

    /**
     * Coordinates [messageId] in [channelId] through the burst debounce window and pre-send cancellation lifecycle.
     *
     * Returns `true` if this message won its burst and [block] returned `true`; returns `false` if coalesced,
     * ignored (e.g. bystander during a direct mention), skipped by [canProceedAfterWait], interrupted by a newer
     * message before dispatch, or evicted.
     */
    suspend fun executeWithDebounce(
        channelId: Snowflake,
        messageId: Snowflake,
        authorId: Snowflake?,
        isDirectMention: Boolean,
        canProceedAfterWait: () -> Boolean = { true },
        block: suspend (effectiveIsDirectMention: Boolean, markDispatching: () -> Boolean) -> Boolean,
    ): Boolean {
        var isFirstAttempt = true
        while (true) {
            if (!isFirstAttempt && !canProceedAfterWait()) {
                logger.debug("Skipping queued message {} in channel {} after waiting: preflight returned false", messageId, channelId)
                return false
            }
            isFirstAttempt = false

            when (val action = joinOrReplaceRound(channelId, authorId, isDirectMention)) {
                is JoinAction.Ignore -> {
                    logger.debug(
                        "Ignoring bystander ambient message {} in channel {} during active direct-mention burst",
                        messageId,
                        channelId,
                    )
                    return false
                }

                is JoinAction.WaitForCompletion -> {
                    logger.debug("Message {} waiting for locked/different-author round in channel {}", messageId, channelId)
                    action.completion.await()
                }

                is JoinAction.Coalesced -> {
                    abortEvictedRound()
                    val outcome =
                        try {
                            action.deferred.await()
                        } catch (t: Throwable) {
                            synchronized(lock) {
                                action.round.waiters.remove(action.myToken)
                                if (action.round.ownerToken == action.myToken && action.round.phase == Phase.EXECUTING) {
                                    action.round.phase = Phase.DONE
                                    if (rounds[channelId] === action.round) {
                                        rounds.remove(channelId)
                                    }
                                    action.round.completion.complete(Unit)
                                }
                            }
                            throw t
                        }
                    if (!outcome.shouldExecute) {
                        logger.debug("Coalesced message {} in channel {} yielded to newer burst message", messageId, channelId)
                        return false
                    }
                    return runPhase2(channelId, messageId, action.round, outcome.effectiveIsDirectMention, block)
                }

                is JoinAction.RunDebounce -> {
                    abortEvictedRound()
                    val outcome = runDebounceLoop(channelId, messageId, action.round, action.myToken)
                    if (!outcome.shouldExecute) {
                        return false
                    }
                    return runPhase2(channelId, messageId, action.round, outcome.effectiveIsDirectMention, block)
                }
            }
        }
    }

    private fun joinOrReplaceRound(
        channelId: Snowflake,
        authorId: Snowflake?,
        isDirectMention: Boolean,
    ): JoinAction {
        var toCancelJob: Job? = null
        var action: JoinAction? = null

        synchronized(lock) {
            val current = rounds[channelId]
            if (current == null || current.phase == Phase.DONE || current.evicted) {
                val round =
                    ChannelRound(
                        channelId = channelId,
                        isMentionBurst = isDirectMention,
                        mentionAuthorId = if (isDirectMention) authorId else null,
                        nextDebounceMs = if (isDirectMention) mentionDebounceMs else debounceMs,
                        ownerToken = ++nextToken,
                    )
                rounds[channelId] = round
                action = JoinAction.RunDebounce(round = round, myToken = round.ownerToken, evictedRound = null)
                return@synchronized
            }

            if (current.isMentionBurst) {
                if (!isDirectMention) {
                    val isSameAuthor = authorId != null && current.mentionAuthorId != null && authorId == current.mentionAuthorId
                    if (!isSameAuthor) {
                        action = JoinAction.Ignore
                        return@synchronized
                    }
                    // Same-author follow-up to an active direct mention: coalesce or wait/interrupt while preserving mention mode
                } else {
                    val isSameAuthor = authorId != null && current.mentionAuthorId != null && authorId == current.mentionAuthorId
                    if (!isSameAuthor) {
                        // Different author's direct mention waits for the active direct mention instead of cancelling it
                        action = JoinAction.WaitForCompletion(current.completion)
                        return@synchronized
                    }
                }
            }

            when (current.phase) {
                Phase.DEBOUNCING -> {
                    val token = ++nextToken
                    val waiter = CompletableDeferred<HandoffOutcome>()
                    current.waiters[token] = waiter
                    current.ownerToken = token
                    if (isDirectMention && !current.isMentionBurst) {
                        current.isMentionBurst = true
                        current.mentionAuthorId = authorId
                        current.nextDebounceMs = mentionDebounceMs
                    } else if (current.isMentionBurst) {
                        current.nextDebounceMs = mentionDebounceMs
                    } else {
                        current.nextDebounceMs = debounceMs
                    }
                    current.coalesceSeq++
                    val oldWakeup = current.debounceWakeup
                    current.debounceWakeup = CompletableDeferred()
                    oldWakeup.complete(Unit)
                    action = JoinAction.Coalesced(round = current, myToken = token, deferred = waiter)
                }

                Phase.EXECUTING -> {
                    val isLockedByCap = current.interruptCount >= maxInterrupts
                    if (isLockedByCap) {
                        action = JoinAction.WaitForCompletion(current.completion)
                    } else {
                        // Pre-send cancellation: interrupt active run and open a new debounce round carrying interruptCount + 1
                        current.interrupted = true
                        current.phase = Phase.DONE
                        toCancelJob = current.executionJob

                        val inheritMention = isDirectMention || (current.isMentionBurst && authorId != null && authorId == current.mentionAuthorId)
                        val mentionAuthor =
                            if (isDirectMention) {
                                authorId
                            } else if (current.isMentionBurst && authorId != null && authorId == current.mentionAuthorId) {
                                current.mentionAuthorId
                            } else {
                                null
                            }
                        val nextDebounce = if (inheritMention) mentionDebounceMs else debounceMs
                        val replacement =
                            ChannelRound(
                                channelId = channelId,
                                isMentionBurst = inheritMention,
                                mentionAuthorId = mentionAuthor,
                                nextDebounceMs = nextDebounce,
                                ownerToken = ++nextToken,
                                interruptCount = current.interruptCount + 1,
                            )
                        rounds[channelId] = replacement
                        action = JoinAction.RunDebounce(round = replacement, myToken = replacement.ownerToken, evictedRound = null)
                    }
                }

                Phase.DISPATCHING -> {
                    action = JoinAction.WaitForCompletion(current.completion)
                }

                Phase.DONE -> {
                    // Already unreachable above, fallback cleanly
                    val round =
                        ChannelRound(
                            channelId = channelId,
                            isMentionBurst = isDirectMention,
                            mentionAuthorId = if (isDirectMention) authorId else null,
                            nextDebounceMs = if (isDirectMention) mentionDebounceMs else debounceMs,
                            ownerToken = ++nextToken,
                        )
                    rounds[channelId] = round
                    action = JoinAction.RunDebounce(round = round, myToken = round.ownerToken, evictedRound = null)
                }
            }
        }

        toCancelJob?.cancel(BurstInterruptedCancellationException("Interrupted by newer message in channel $channelId"))
        return action!!
    }

    private suspend fun runDebounceLoop(
        channelId: Snowflake,
        messageId: Snowflake,
        round: ChannelRound,
        runnerToken: Long,
    ): HandoffOutcome {
        var outcomeForRunner = HandoffOutcome(shouldExecute = false, effectiveIsDirectMention = round.isMentionBurst)
        try {
            withTimeoutOrNull(maxDebounceMs) {
                while (true) {
                    val (waitMs, seqBefore, wakeup, evictedOrDone) =
                        synchronized(lock) {
                            DebounceSnapshot(
                                waitMs = round.nextDebounceMs,
                                seqBefore = round.coalesceSeq,
                                wakeup = round.debounceWakeup,
                                evictedOrDone = round.evicted || round.phase != Phase.DEBOUNCING,
                            )
                        }
                    if (evictedOrDone) break

                    val woken =
                        withTimeoutOrNull(waitMs) {
                            // Re-check sequence before suspending so no wakeup between snapshots is ever lost
                            val changedAlready = synchronized(lock) { round.coalesceSeq != seqBefore || round.evicted }
                            if (!changedAlready) {
                                wakeup.await()
                            }
                        }
                    if (woken == null) {
                        // Debounce window expired quietly without another coalesced message
                        break
                    }
                    // Coalesced or evicted: loop again (if evicted, top check exits immediately)
                }
            }

            val waitersToNotify = mutableListOf<Pair<CompletableDeferred<HandoffOutcome>, HandoffOutcome>>()
            synchronized(lock) {
                if (round.evicted || round.phase != Phase.DEBOUNCING) {
                    val cancelOutcome = HandoffOutcome(shouldExecute = false, effectiveIsDirectMention = round.isMentionBurst)
                    round.waiters.values.forEach { waitersToNotify.add(it to cancelOutcome) }
                    round.waiters.clear()
                    outcomeForRunner = cancelOutcome
                } else {
                    val winnerToken = round.ownerToken
                    val effectiveMention = round.isMentionBurst
                    round.phase = Phase.EXECUTING
                    round.waiters.forEach { (tok, deferred) ->
                        val outcome =
                            HandoffOutcome(
                                shouldExecute = (tok == winnerToken),
                                effectiveIsDirectMention = effectiveMention,
                            )
                        waitersToNotify.add(deferred to outcome)
                    }
                    round.waiters.clear()
                    outcomeForRunner =
                        HandoffOutcome(
                            shouldExecute = (winnerToken == runnerToken),
                            effectiveIsDirectMention = effectiveMention,
                        )
                }
            }
            waitersToNotify.forEach { (deferred, outcome) -> deferred.complete(outcome) }
            return outcomeForRunner
        } catch (t: Throwable) {
            val waitersToNotify = mutableListOf<Pair<CompletableDeferred<HandoffOutcome>, HandoffOutcome>>()
            val cancelOutcome = HandoffOutcome(shouldExecute = false, effectiveIsDirectMention = round.isMentionBurst)
            synchronized(lock) {
                round.phase = Phase.DONE
                if (rounds[channelId] === round) {
                    rounds.remove(channelId)
                }
                round.waiters.values.forEach { waitersToNotify.add(it to cancelOutcome) }
                round.waiters.clear()
            }
            waitersToNotify.forEach { (deferred, outcome) -> deferred.complete(outcome) }
            round.completion.complete(Unit)
            throw t
        } finally {
            if (!outcomeForRunner.shouldExecute) {
                // If runner handed off to a coalescer, that coalescer owns completion and round cleanup.
                // However, if evicted before handing off, ensure cleanup runs.
                synchronized(lock) {
                    if (round.evicted) {
                        if (rounds[channelId] === round) {
                            rounds.remove(channelId)
                        }
                        round.completion.complete(Unit)
                    }
                }
            }
            logger.debug(
                "Debounce closed for channel {} (trigger={}, runnerShouldExecute={})",
                channelId,
                messageId,
                outcomeForRunner.shouldExecute,
            )
        }
    }

    private suspend fun runPhase2(
        channelId: Snowflake,
        messageId: Snowflake,
        round: ChannelRound,
        effectiveIsDirectMention: Boolean,
        block: suspend (effectiveIsDirectMention: Boolean, markDispatching: () -> Boolean) -> Boolean,
    ): Boolean =
        coroutineScope {
            var blockResult = false
            var uncaughtFailure: Throwable? = null
            val markDispatching: () -> Boolean = {
                synchronized(lock) {
                    if (round.evicted || round.interrupted || round.phase != Phase.EXECUTING) {
                        false
                    } else {
                        round.phase = Phase.DISPATCHING
                        true
                    }
                }
            }

            val job =
                launch(start = CoroutineStart.LAZY) {
                    try {
                        blockResult = block(effectiveIsDirectMention, markDispatching)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        uncaughtFailure = t
                    }
                }

            val canStart =
                synchronized(lock) {
                    if (round.evicted || round.interrupted || round.phase != Phase.EXECUTING) {
                        false
                    } else {
                        round.executionJob = job
                        true
                    }
                }

            if (!canStart) {
                job.cancel()
                synchronized(lock) {
                    if (rounds[channelId] === round) {
                        rounds.remove(channelId)
                    }
                }
                round.completion.complete(Unit)
                return@coroutineScope false
            }

            try {
                job.start()
                job.join()
                val failure = uncaughtFailure
                if (failure != null) {
                    throw failure
                }
                val wasInterruptedOrEvicted = synchronized(lock) { round.interrupted || round.evicted }
                if (wasInterruptedOrEvicted || job.isCancelled) {
                    logger.debug("Phase 2 interrupted/evicted for message {} in channel {}", messageId, channelId)
                    return@coroutineScope false
                }
                return@coroutineScope blockResult
            } finally {
                synchronized(lock) {
                    if (rounds[channelId] === round) {
                        round.phase = Phase.DONE
                        rounds.remove(channelId)
                    }
                }
                round.completion.complete(Unit)
            }
        }

    private fun abortEvictedRound() {
        val evicted =
            synchronized(lock) {
                val r = pendingEviction
                pendingEviction = null
                r
            } ?: return

        val cancelWaiters = mutableListOf<CompletableDeferred<HandoffOutcome>>()
        val jobToCancel: Job?
        val wakeupToComplete: CompletableDeferred<Unit>
        synchronized(lock) {
            evicted.phase = Phase.DONE
            cancelWaiters.addAll(evicted.waiters.values)
            evicted.waiters.clear()
            jobToCancel = evicted.executionJob
            wakeupToComplete = evicted.debounceWakeup
        }
        wakeupToComplete.complete(Unit)
        val outcome = HandoffOutcome(shouldExecute = false, effectiveIsDirectMention = evicted.isMentionBurst)
        cancelWaiters.forEach { it.complete(outcome) }
        jobToCancel?.cancel(BurstInterruptedCancellationException("Evicted due to maxEntries capacity ($maxEntries)"))
        evicted.completion.complete(Unit)
    }

    fun isActive(channelId: Snowflake): Boolean = synchronized(lock) { channelId in rounds }

    fun isActive(channelId: Long): Boolean = isActive(Snowflake(channelId))

    val size: Int
        get() = synchronized(lock) { rounds.size }

    fun clear() {
        val toAbort: List<ChannelRound>
        synchronized(lock) {
            toAbort = rounds.values.toList()
            rounds.clear()
            pendingEviction = null
        }
        toAbort.forEach { round ->
            val cancelWaiters = mutableListOf<CompletableDeferred<HandoffOutcome>>()
            val job: Job?
            val wakeup: CompletableDeferred<Unit>
            synchronized(lock) {
                round.evicted = true
                round.phase = Phase.DONE
                cancelWaiters.addAll(round.waiters.values)
                round.waiters.clear()
                job = round.executionJob
                wakeup = round.debounceWakeup
            }
            wakeup.complete(Unit)
            val outcome = HandoffOutcome(shouldExecute = false, effectiveIsDirectMention = round.isMentionBurst)
            cancelWaiters.forEach { it.complete(outcome) }
            job?.cancel(BurstInterruptedCancellationException("Coordinator cleared"))
            round.completion.complete(Unit)
        }
    }

    private data class DebounceSnapshot(
        val waitMs: Long,
        val seqBefore: Long,
        val wakeup: CompletableDeferred<Unit>,
        val evictedOrDone: Boolean,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(BoundedChannelBurstCoordinator::class.java)
        const val DEFAULT_MAX_CAPACITY = 500

        private fun normalizePositiveMs(
            raw: Long,
            defaultValue: Long,
            name: String,
        ): Long {
            if (raw <= 0L) {
                logger.warn("Invalid {} '{}ms'; falling back to default {}ms", name, raw, defaultValue)
                return defaultValue
            }
            return raw
        }

        private fun normalizeMaxDebounceMs(raw: Long): Long {
            if (raw <= 0L) {
                logger.warn(
                    "Invalid maxDebounceMs '{}ms'; falling back to default {}ms",
                    raw,
                    ChatterConfig.DEFAULT_DEBOUNCE_MAX_MILLIS,
                )
                return ChatterConfig.DEFAULT_DEBOUNCE_MAX_MILLIS
            }
            val clamped = raw.coerceIn(ChatterConfig.MIN_DEBOUNCE_MAX_MILLIS, ChatterConfig.MAX_DEBOUNCE_MAX_MILLIS)
            if (clamped != raw) {
                logger.warn(
                    "Out-of-range maxDebounceMs '{}ms'; clamped to {}ms (allowed [{}, {}])",
                    raw,
                    clamped,
                    ChatterConfig.MIN_DEBOUNCE_MAX_MILLIS,
                    ChatterConfig.MAX_DEBOUNCE_MAX_MILLIS,
                )
            }
            return clamped
        }

        private fun normalizeNonNegativeInterrupts(raw: Int): Int {
            if (raw < 0) {
                logger.warn("Invalid maxInterrupts '{}'; falling back to default {}", raw, ChatterConfig.DEFAULT_MAX_INTERRUPTS)
                return ChatterConfig.DEFAULT_MAX_INTERRUPTS
            }
            return raw
        }
    }
}
