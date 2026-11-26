package luna.chatter

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.LinkedHashMap

/**
 * Single-lock-owner state store for per-channel burst-debounce rounds and bounded LRU eviction.
 */
internal class ChannelBurstRoundStore(
    private val debounceMs: Long,
    private val mentionDebounceMs: Long,
    private val maxInterrupts: Int,
    private val maxEntries: Int,
) {
    internal interface RoundHandle

    internal enum class Phase {
        DEBOUNCING,
        EXECUTING,
        DISPATCHING,
        DONE,
    }

    internal data class HandoffOutcome(
        val shouldExecute: Boolean,
        val effectiveDirectInteraction: DirectInteractionType,
    )

    internal data class DebounceSnapshot(
        val waitMs: Long,
        val seqBefore: Long,
        val wakeup: CompletableDeferred<Unit>,
        val evictedOrDone: Boolean,
    )

    internal sealed interface JoinAction {
        data class RunDebounce(
            val round: RoundHandle,
            val myToken: Long,
            val initialDirectInteraction: DirectInteractionType,
        ) : JoinAction

        data class Coalesced(
            val round: RoundHandle,
            val myToken: Long,
            val deferred: CompletableDeferred<HandoffOutcome>,
        ) : JoinAction

        data class WaitForCompletion(
            val completion: CompletableDeferred<Unit>,
        ) : JoinAction

        data object Ignore : JoinAction
    }

    private class ChannelRound(
        val channelId: Snowflake,
        var directInteraction: DirectInteractionType,
        var mentionAuthorId: Snowflake?,
        var nextDebounceMs: Long,
        var ownerToken: Long,
        var interruptCount: Int = 0,
    ) : RoundHandle {
        val isMentionBurst: Boolean
            get() = directInteraction.isDirect
        var phase: Phase = Phase.DEBOUNCING
        var coalesceSeq: Long = 0L
        var debounceWakeup: CompletableDeferred<Unit> = CompletableDeferred()
        val waiters: MutableMap<Long, CompletableDeferred<HandoffOutcome>> = mutableMapOf()
        var executionJob: Job? = null
        var interrupted: Boolean = false
        var evicted: Boolean = false
        val completion: CompletableDeferred<Unit> = CompletableDeferred()
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

    fun isActive(channelId: Snowflake): Boolean = synchronized(lock) { channelId in rounds }

    val size: Int
        get() = synchronized(lock) { rounds.size }

    fun openOrJoin(
        channelId: Snowflake,
        authorId: Snowflake?,
        directInteraction: DirectInteractionType,
    ): JoinAction {
        var toCancelJob: Job? = null
        var action: JoinAction? = null

        synchronized(lock) {
            val current = rounds[channelId]
            if (current == null || current.phase == Phase.DONE || current.evicted) {
                val round = openRoundLocked(channelId, authorId, directInteraction)
                action =
                    JoinAction.RunDebounce(
                        round = round,
                        myToken = round.ownerToken,
                        initialDirectInteraction = round.directInteraction,
                    )
                return@synchronized
            }

            if (current.isMentionBurst) {
                val isSameAuthor = authorId != null && current.mentionAuthorId != null && authorId == current.mentionAuthorId
                if (!directInteraction.isDirect && !isSameAuthor) {
                    action = JoinAction.Ignore
                    return@synchronized
                }
                if (directInteraction.isDirect && !isSameAuthor) {
                    action = JoinAction.WaitForCompletion(current.completion)
                    return@synchronized
                }
            }

            when (current.phase) {
                Phase.DEBOUNCING -> {
                    val token = ++nextToken
                    val waiter = CompletableDeferred<HandoffOutcome>()
                    current.waiters[token] = waiter
                    current.ownerToken = token
                    if (directInteraction.isDirect && !current.isMentionBurst) {
                        current.directInteraction = directInteraction
                        current.mentionAuthorId = authorId
                        current.nextDebounceMs = mentionDebounceMs
                    } else if (current.isMentionBurst) {
                        current.directInteraction = current.directInteraction.mergeWith(directInteraction)
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
                    if (current.interruptCount >= maxInterrupts) {
                        action = JoinAction.WaitForCompletion(current.completion)
                    } else {
                        current.interrupted = true
                        current.phase = Phase.DONE
                        toCancelJob = current.executionJob

                        val isSameMentionAuthor = current.isMentionBurst && authorId != null && authorId == current.mentionAuthorId
                        val inheritedInteraction =
                            if (isSameMentionAuthor) current.directInteraction.mergeWith(directInteraction) else directInteraction
                        val mentionAuthor =
                            when {
                                directInteraction.isDirect -> authorId
                                isSameMentionAuthor -> current.mentionAuthorId
                                else -> null
                            }
                        val replacement =
                            openRoundLocked(
                                channelId = channelId,
                                authorId = mentionAuthor,
                                directInteraction = inheritedInteraction,
                                interruptCount = current.interruptCount + 1,
                            )
                        action =
                            JoinAction.RunDebounce(
                                round = replacement,
                                myToken = replacement.ownerToken,
                                initialDirectInteraction = replacement.directInteraction,
                            )
                    }
                }

                Phase.DISPATCHING -> {
                    action = JoinAction.WaitForCompletion(current.completion)
                }

                Phase.DONE -> {
                    val round = openRoundLocked(channelId, authorId, directInteraction)
                    action =
                        JoinAction.RunDebounce(
                            round = round,
                            myToken = round.ownerToken,
                            initialDirectInteraction = round.directInteraction,
                        )
                }
            }
        }

        abortEvictedRound()
        toCancelJob?.cancel(BurstInterruptedCancellationException("Interrupted by newer message in channel $channelId"))
        return action!!
    }

    fun cancelCoalescedWaiter(
        channelId: Snowflake,
        handle: RoundHandle,
        token: Long,
    ) {
        val round = handle.asRound()
        var completed = false
        synchronized(lock) {
            round.waiters.remove(token)
            if (round.ownerToken == token && round.phase == Phase.EXECUTING) {
                round.phase = Phase.DONE
                if (rounds[channelId] === round) {
                    rounds.remove(channelId)
                }
                completed = true
            }
        }
        if (completed) {
            round.completion.complete(Unit)
        }
    }

    fun pollDebounceStep(handle: RoundHandle): DebounceSnapshot {
        val round = handle.asRound()
        return synchronized(lock) {
            DebounceSnapshot(
                waitMs = round.nextDebounceMs,
                seqBefore = round.coalesceSeq,
                wakeup = round.debounceWakeup,
                evictedOrDone = round.evicted || round.phase != Phase.DEBOUNCING,
            )
        }
    }

    fun hasDebounceChanged(
        handle: RoundHandle,
        seqBefore: Long,
    ): Boolean {
        val round = handle.asRound()
        return synchronized(lock) { round.coalesceSeq != seqBefore || round.evicted }
    }

    fun finishDebounce(
        handle: RoundHandle,
        runnerToken: Long,
    ): HandoffOutcome {
        val round = handle.asRound()
        val waitersToNotify = mutableListOf<Pair<CompletableDeferred<HandoffOutcome>, HandoffOutcome>>()
        val outcomeForRunner: HandoffOutcome
        synchronized(lock) {
            if (round.evicted || round.phase != Phase.DEBOUNCING) {
                val cancelOutcome = HandoffOutcome(shouldExecute = false, effectiveDirectInteraction = round.directInteraction)
                round.waiters.values.forEach { waitersToNotify.add(it to cancelOutcome) }
                round.waiters.clear()
                outcomeForRunner = cancelOutcome
            } else {
                val winnerToken = round.ownerToken
                val effectiveInteraction = round.directInteraction
                round.phase = Phase.EXECUTING
                round.waiters.forEach { (tok, deferred) ->
                    val outcome =
                        HandoffOutcome(
                            shouldExecute = (tok == winnerToken),
                            effectiveDirectInteraction = effectiveInteraction,
                        )
                    waitersToNotify.add(deferred to outcome)
                }
                round.waiters.clear()
                outcomeForRunner =
                    HandoffOutcome(
                        shouldExecute = (winnerToken == runnerToken),
                        effectiveDirectInteraction = effectiveInteraction,
                    )
            }
        }
        waitersToNotify.forEach { (deferred, outcome) -> deferred.complete(outcome) }
        return outcomeForRunner
    }

    fun attachExecutionJob(
        channelId: Snowflake,
        handle: RoundHandle,
        job: Job,
    ): Boolean {
        val round = handle.asRound()
        val attached =
            synchronized(lock) {
                if (round.evicted || round.interrupted || round.phase != Phase.EXECUTING) {
                    if (rounds[channelId] === round) {
                        rounds.remove(channelId)
                    }
                    false
                } else {
                    round.executionJob = job
                    true
                }
            }
        if (!attached) {
            round.completion.complete(Unit)
        }
        return attached
    }

    fun markDispatching(handle: RoundHandle): Boolean {
        val round = handle.asRound()
        return synchronized(lock) {
            if (round.evicted || round.interrupted || round.phase != Phase.EXECUTING) {
                false
            } else {
                round.phase = Phase.DISPATCHING
                true
            }
        }
    }

    fun isInterruptedOrEvicted(handle: RoundHandle): Boolean {
        val round = handle.asRound()
        return synchronized(lock) { round.interrupted || round.evicted }
    }

    fun completePhase2(
        channelId: Snowflake,
        handle: RoundHandle,
    ) {
        val round = handle.asRound()
        synchronized(lock) {
            if (rounds[channelId] === round) {
                round.phase = Phase.DONE
                rounds.remove(channelId)
            }
        }
        round.completion.complete(Unit)
    }

    fun failDebounce(
        channelId: Snowflake,
        handle: RoundHandle,
    ) {
        val round = handle.asRound()
        val waitersToNotify = mutableListOf<Pair<CompletableDeferred<HandoffOutcome>, HandoffOutcome>>()
        val cancelOutcome = HandoffOutcome(shouldExecute = false, effectiveDirectInteraction = round.directInteraction)
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
    }

    fun cleanupEvicted(
        channelId: Snowflake,
        handle: RoundHandle,
    ) {
        val round = handle.asRound()
        var shouldComplete = false
        synchronized(lock) {
            if (round.evicted) {
                if (rounds[channelId] === round) {
                    rounds.remove(channelId)
                }
                shouldComplete = true
            }
        }
        if (shouldComplete) {
            round.completion.complete(Unit)
        }
    }

    fun clear() {
        val toAbort: List<ChannelRound>
        synchronized(lock) {
            toAbort = rounds.values.toList()
            rounds.clear()
            pendingEviction = null
        }
        toAbort.forEach { abortRound(it, "Coordinator cleared") }
    }

    private fun abortEvictedRound() {
        val evicted =
            synchronized(lock) {
                val r = pendingEviction
                pendingEviction = null
                r
            } ?: return
        abortRound(evicted, "Evicted due to maxEntries capacity ($maxEntries)")
    }

    private fun abortRound(
        round: ChannelRound,
        reason: String,
    ) {
        val cancelWaiters = mutableListOf<CompletableDeferred<HandoffOutcome>>()
        val jobToCancel: Job?
        val wakeupToComplete: CompletableDeferred<Unit>
        val effectiveInteraction: DirectInteractionType
        synchronized(lock) {
            round.evicted = true
            round.phase = Phase.DONE
            cancelWaiters.addAll(round.waiters.values)
            round.waiters.clear()
            jobToCancel = round.executionJob
            wakeupToComplete = round.debounceWakeup
            effectiveInteraction = round.directInteraction
        }
        wakeupToComplete.complete(Unit)
        val outcome = HandoffOutcome(shouldExecute = false, effectiveDirectInteraction = effectiveInteraction)
        cancelWaiters.forEach { it.complete(outcome) }
        jobToCancel?.cancel(BurstInterruptedCancellationException(reason))
        round.completion.complete(Unit)
    }

    private fun openRoundLocked(
        channelId: Snowflake,
        authorId: Snowflake?,
        directInteraction: DirectInteractionType,
        interruptCount: Int = 0,
    ): ChannelRound {
        val round =
            ChannelRound(
                channelId = channelId,
                directInteraction = directInteraction,
                mentionAuthorId = if (directInteraction.isDirect) authorId else null,
                nextDebounceMs = if (directInteraction.isDirect) mentionDebounceMs else debounceMs,
                ownerToken = ++nextToken,
                interruptCount = interruptCount,
            )
        rounds[channelId] = round
        return round
    }

    private fun RoundHandle.asRound(): ChannelRound = this as ChannelRound
}
