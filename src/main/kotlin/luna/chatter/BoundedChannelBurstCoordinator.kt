package luna.chatter

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

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

    private val store =
        ChannelBurstRoundStore(
            debounceMs = this.debounceMs,
            mentionDebounceMs = this.mentionDebounceMs,
            maxInterrupts = this.maxInterrupts,
            maxEntries = maxEntries,
        )

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
    ): Boolean =
        executeWithDirectInteraction(
            channelId = channelId,
            messageId = messageId,
            authorId = authorId,
            directInteraction = if (isDirectMention) DirectInteractionType.MENTION else DirectInteractionType.NONE,
            canProceedAfterWait = canProceedAfterWait,
        ) { effectiveInteraction, markDispatching ->
            block(effectiveInteraction.isDirect, markDispatching)
        }

    suspend fun executeWithDirectInteraction(
        channelId: Snowflake,
        messageId: Snowflake,
        authorId: Snowflake?,
        directInteraction: DirectInteractionType,
        canProceedAfterWait: () -> Boolean = { true },
        block: suspend (effectiveDirectInteraction: DirectInteractionType, markDispatching: () -> Boolean) -> Boolean,
    ): Boolean {
        var isFirstAttempt = true
        while (true) {
            if (!isFirstAttempt && !canProceedAfterWait()) {
                logger.debug("Skipping queued message {} in channel {} after waiting: preflight returned false", messageId, channelId)
                return false
            }
            isFirstAttempt = false

            when (val action = store.openOrJoin(channelId, authorId, directInteraction)) {
                is ChannelBurstRoundStore.JoinAction.Ignore -> {
                    logger.debug(
                        "Ignoring bystander ambient message {} in channel {} during active direct-mention burst",
                        messageId,
                        channelId,
                    )
                    return false
                }

                is ChannelBurstRoundStore.JoinAction.WaitForCompletion -> {
                    logger.debug("Message {} waiting for locked/different-author round in channel {}", messageId, channelId)
                    action.completion.await()
                }

                is ChannelBurstRoundStore.JoinAction.Coalesced -> {
                    val outcome =
                        try {
                            action.deferred.await()
                        } catch (t: Throwable) {
                            store.cancelCoalescedWaiter(channelId, action.round, action.myToken)
                            throw t
                        }
                    if (!outcome.shouldExecute) {
                        logger.debug("Coalesced message {} in channel {} yielded to newer burst message", messageId, channelId)
                        return false
                    }
                    return runPhase2(channelId, messageId, action.round, outcome.effectiveDirectInteraction, block)
                }

                is ChannelBurstRoundStore.JoinAction.RunDebounce -> {
                    val outcome =
                        runDebounceLoop(
                            channelId = channelId,
                            messageId = messageId,
                            round = action.round,
                            runnerToken = action.myToken,
                            initialDirectInteraction = action.initialDirectInteraction,
                        )
                    if (!outcome.shouldExecute) {
                        return false
                    }
                    return runPhase2(channelId, messageId, action.round, outcome.effectiveDirectInteraction, block)
                }
            }
        }
    }

    private suspend fun runDebounceLoop(
        channelId: Snowflake,
        messageId: Snowflake,
        round: ChannelBurstRoundStore.RoundHandle,
        runnerToken: Long,
        initialDirectInteraction: DirectInteractionType,
    ): ChannelBurstRoundStore.HandoffOutcome {
        var outcomeForRunner =
            ChannelBurstRoundStore.HandoffOutcome(
                shouldExecute = false,
                effectiveDirectInteraction = initialDirectInteraction,
            )
        try {
            withTimeoutOrNull(maxDebounceMs) {
                while (true) {
                    val snapshot = store.pollDebounceStep(round)
                    if (snapshot.evictedOrDone) break

                    val woken =
                        withTimeoutOrNull(snapshot.waitMs) {
                            // Re-check sequence before suspending so no wakeup between snapshots is ever lost
                            if (!store.hasDebounceChanged(round, snapshot.seqBefore)) {
                                snapshot.wakeup.await()
                            }
                        }
                    if (woken == null) {
                        // Debounce window expired quietly without another coalesced message
                        break
                    }
                    // Coalesced or evicted: loop again (if evicted, top check exits immediately)
                }
            }

            outcomeForRunner = store.finishDebounce(round, runnerToken)
            return outcomeForRunner
        } catch (t: Throwable) {
            store.failDebounce(channelId, round)
            throw t
        } finally {
            if (!outcomeForRunner.shouldExecute) {
                // If runner handed off to a coalescer, that coalescer owns completion and round cleanup.
                // However, if evicted before handing off, ensure cleanup runs.
                store.cleanupEvicted(channelId, round)
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
        round: ChannelBurstRoundStore.RoundHandle,
        effectiveDirectInteraction: DirectInteractionType,
        block: suspend (effectiveDirectInteraction: DirectInteractionType, markDispatching: () -> Boolean) -> Boolean,
    ): Boolean =
        coroutineScope {
            var blockResult = false
            var uncaughtFailure: Throwable? = null
            val markDispatching: () -> Boolean = { store.markDispatching(round) }

            val job =
                launch(start = CoroutineStart.LAZY) {
                    try {
                        blockResult = block(effectiveDirectInteraction, markDispatching)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        uncaughtFailure = t
                    }
                }

            if (!store.attachExecutionJob(channelId, round, job)) {
                job.cancel()
                return@coroutineScope false
            }

            try {
                job.start()
                job.join()
                val failure = uncaughtFailure
                if (failure != null) {
                    throw failure
                }
                if (store.isInterruptedOrEvicted(round) || job.isCancelled) {
                    logger.debug("Phase 2 interrupted/evicted for message {} in channel {}", messageId, channelId)
                    return@coroutineScope false
                }
                return@coroutineScope blockResult
            } finally {
                store.completePhase2(channelId, round)
            }
        }

    fun isActive(channelId: Snowflake): Boolean = store.isActive(channelId)

    fun isActive(channelId: Long): Boolean = store.isActive(Snowflake(channelId))

    val size: Int
        get() = store.size

    fun clear() = store.clear()

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
            val positive = normalizePositiveMs(raw, ChatterConfig.DEFAULT_DEBOUNCE_MAX_MILLIS, "maxDebounceMs")
            val clamped = positive.coerceIn(ChatterConfig.MIN_DEBOUNCE_MAX_MILLIS, ChatterConfig.MAX_DEBOUNCE_MAX_MILLIS)
            if (clamped != positive) {
                logger.warn(
                    "Out-of-range maxDebounceMs '{}ms'; clamped to {}ms (allowed [{}, {}])",
                    positive,
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
