package luna.chatter.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import luna.chatter.ChatterConfig
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Coordinates Luna's daily 24-hour [DailySchedule] lifecycle:
 * - Loads today's persisted schedule from [DailyScheduleStore] or generates & persists via [DailyScheduleGenerator].
 * - Retries failed generation with exponential backoff (1m -> 2m -> 4m -> ... -> max 15m) without a hardcoded fallback.
 * - Schedules exact-boundary wakeups for [ScheduleBlock] transitions and midnight rollovers in [timezone].
 * - Syncs Discord presence via [SchedulePresencePort] on startup and on every block transition.
 * - Fires [onBecomeAvailable] when transitioning from `BUSY`/`SLEEPING` into `BREAK`/`AVAILABLE`.
 */
open class DailyScheduleCoordinator(
    private val store: DailyScheduleStore,
    private val generator: DailyScheduleGenerator,
    private val presencePort: SchedulePresencePort? = null,
    private val enabled: Boolean = true,
    private val timezone: ZoneId = ZoneId.of(ChatterConfig.DEFAULT_TIMEZONE),
    private val clock: Clock = Clock.systemUTC(),
    private val initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS,
    private val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    onBecomeAvailable: (suspend (previous: AvailabilityState, current: ScheduleBlock) -> Unit)? = null,
) : DailyScheduleProvider {
    val rateLimiter: ScheduleMutationRateLimiter =
        ScheduleMutationRateLimiter(clock = clock, timezone = timezone)

    private data class PresenceSyncKey(
        val date: LocalDate,
        val startMinute: Int,
        val activityTitle: String,
        val statusText: String,
        val availability: AvailabilityState,
    )

    private val logger = LoggerFactory.getLogger(DailyScheduleCoordinator::class.java)

    @Volatile
    private var activeSchedule: DailySchedule? = null

    @Volatile
    private var onBecomeAvailableHook: (suspend (AvailabilityState, ScheduleBlock) -> Unit)? = onBecomeAvailable

    private val mutationMutex = Mutex()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private var lastSyncedBlockKey: PresenceSyncKey? = null
    private var previousAvailability: AvailabilityState? = null
    private var schedulerJob: Job? = null

    fun setOnBecomeAvailableListener(listener: (suspend (AvailabilityState, ScheduleBlock) -> Unit)?) {
        onBecomeAvailableHook = listener
    }

    override fun isEnabled(): Boolean = enabled

    override fun currentSchedule(): DailySchedule? {
        if (!enabled) return null
        val today = currentLocalDate()
        return activeSchedule?.takeIf { it.date == today }
    }

    override fun currentBlock(): ScheduleBlock? {
        val schedule = currentSchedule() ?: return null
        val localTime = clock.instant().atZone(timezone).toLocalTime()
        return schedule.blockAt(localTime)
    }

    override fun upcomingCommitments(): List<ScheduleCommitment> {
        if (!enabled) return emptyList()
        val today = currentLocalDate()
        return runCatching {
            store.listUpcomingCommitments(
                fromDate = today.plusDays(1),
                toDateInclusive = today.plusDays(MAX_COMMITMENT_HORIZON_DAYS),
            )
        }.onFailure { ex ->
            logger.warn("Failed to list upcoming schedule commitments: {}", ex.message)
        }.getOrDefault(emptyList())
    }

    sealed interface CommitmentResult {
        data class Upserted(
            val commitment: ScheduleCommitment,
        ) : CommitmentResult

        data class Cancelled(
            val id: String,
        ) : CommitmentResult

        data class Rejected(
            val reason: String,
        ) : CommitmentResult
    }

    /**
     * Starts the background schedule coordinator loop in [scope].
     * No-op returning `null` when [enabled] is `false`.
     */
    fun start(scope: CoroutineScope): Job? {
        if (!enabled) {
            logger.info("DailyScheduleCoordinator is disabled via configuration; skipping startup")
            return null
        }
        schedulerJob?.cancel()
        val job =
            scope.launch {
                runLoop()
            }
        schedulerJob = job
        return job
    }

    fun shutdown() {
        schedulerJob?.cancel()
        schedulerJob = null
        activeSchedule = null
        lastSyncedBlockKey = null
        previousAvailability = null
    }

    /**
     * Deterministically splices [patches] into today's [DailySchedule], enforces future-only
     * boundaries and rate limits, persists to [store], updates [activeSchedule], syncs Discord
     * presence immediately if the active block changed, and wakes [runLoop] to recalculate
     * the next boundary timer.
     */
    open suspend fun applyTodayPatches(patches: List<SchedulePatch>): DailyScheduleSplicer.SpliceResult {
        if (!enabled) {
            return DailyScheduleSplicer.SpliceResult.Rejected("DailyScheduleCoordinator is disabled.")
        }
        return mutationMutex.withLock {
            val nowInstant = clock.instant()
            val nowZoned = nowInstant.atZone(timezone)
            val today = nowZoned.toLocalDate()
            val nowMinute = nowZoned.hour * 60 + nowZoned.minute

            val rateRejection = rateLimiter.checkAllowed(nowInstant.toEpochMilli())
            if (rateRejection != null) {
                return@withLock DailyScheduleSplicer.SpliceResult.Rejected(rateRejection)
            }

            val current =
                activeSchedule?.takeIf { it.date == today }
                    ?: runCatching { store.getSchedule(today) }
                        .onFailure { ex ->
                            logger.warn("Failed to load DailySchedule for {} during mutation: {}", today, ex.message)
                        }.getOrNull()
                    ?: return@withLock DailyScheduleSplicer.SpliceResult.Rejected(
                        "Today's schedule ($today) is not available yet.",
                    )

            val spliceResult = DailyScheduleSplicer.spliceToday(current, patches, nowMinute)
            if (spliceResult !is DailyScheduleSplicer.SpliceResult.Success) {
                return@withLock spliceResult
            }

            try {
                store.saveSchedule(spliceResult.schedule)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.warn("Failed to persist mutated DailySchedule for {}: {}", today, ex.message)
                return@withLock DailyScheduleSplicer.SpliceResult.Rejected(
                    "Failed to persist schedule update: ${ex.message}",
                )
            }

            rateLimiter.recordMutation(nowInstant.toEpochMilli())
            activeSchedule = spliceResult.schedule

            val newBlock = spliceResult.schedule.blockAt(nowZoned.toLocalTime())
            if (newBlock != null) {
                syncBlockTransitionLocked(today, newBlock)
            }
            wakeSignal.trySend(Unit)
            spliceResult
        }
    }

    /**
     * Validates and persists a cross-day [ScheduleCommitment] within the `tomorrow..+7 days`
     * horizon, enforcing [ScheduleGuardrails], the 10-commitment active cap, and [rateLimiter].
     */
    open suspend fun upsertCommitment(
        id: String? = null,
        targetDate: LocalDate,
        startMinute: Int,
        endMinute: Int,
        activityTitle: String,
        activityDetail: String,
        statusText: String,
        availability: AvailabilityState,
    ): CommitmentResult {
        if (!enabled) {
            return CommitmentResult.Rejected("DailyScheduleCoordinator is disabled.")
        }
        return mutationMutex.withLock {
            val nowMillis = clock.millis()
            val today = currentLocalDate()
            val minDate = today.plusDays(1)
            val maxDate = today.plusDays(MAX_COMMITMENT_HORIZON_DAYS)

            val rateRejection = rateLimiter.checkAllowed(nowMillis)
            if (rateRejection != null) {
                return@withLock CommitmentResult.Rejected(rateRejection)
            }

            if (targetDate <= today) {
                return@withLock CommitmentResult.Rejected(
                    "Commitment targetDate ($targetDate) must be in the future ($minDate..$maxDate); " +
                        "use today's schedule update for $today.",
                )
            }
            if (targetDate > maxDate) {
                return@withLock CommitmentResult.Rejected(
                    "Commitment targetDate ($targetDate) exceeds the $MAX_COMMITMENT_HORIZON_DAYS-day horizon ($minDate..$maxDate).",
                )
            }

            val patch =
                SchedulePatch(
                    startMinute = startMinute,
                    endMinute = endMinute,
                    activityTitle = activityTitle,
                    activityDetail = activityDetail,
                    statusText = statusText,
                    availability = availability,
                )
            val fieldError = ScheduleGuardrails.validatePatchFields(patch)
            if (fieldError != null) {
                return@withLock CommitmentResult.Rejected(fieldError)
            }
            val durationError = ScheduleGuardrails.validateDuration(availability, endMinute - startMinute)
            if (durationError != null) {
                return@withLock CommitmentResult.Rejected(durationError)
            }

            val effectiveId = id?.trim()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val existingUpcoming =
                try {
                    store.listUpcomingCommitments(minDate, maxDate)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    logger.warn("Failed to load upcoming commitments for cap check: {}", ex.message)
                    return@withLock CommitmentResult.Rejected("Failed to load upcoming commitments: ${ex.message}")
                }

            val existing = existingUpcoming.firstOrNull { it.id == effectiveId }
            if (existing == null && existingUpcoming.size >= MAX_ACTIVE_COMMITMENTS) {
                return@withLock CommitmentResult.Rejected(
                    "Maximum active commitments cap ($MAX_ACTIVE_COMMITMENTS) reached; cancel or update an existing commitment first.",
                )
            }

            val commitment =
                ScheduleCommitment(
                    id = effectiveId,
                    targetDate = targetDate,
                    startMinute = startMinute,
                    endMinute = endMinute,
                    activityTitle = activityTitle.trim(),
                    activityDetail = activityDetail.trim(),
                    statusText = statusText.trim(),
                    availability = availability,
                    createdAtMillis = existing?.createdAtMillis ?: nowMillis,
                )

            try {
                store.upsertCommitment(commitment)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.warn("Failed to persist ScheduleCommitment {}: {}", effectiveId, ex.message)
                return@withLock CommitmentResult.Rejected("Failed to persist commitment: ${ex.message}")
            }

            rateLimiter.recordMutation(nowMillis)
            CommitmentResult.Upserted(commitment)
        }
    }

    /**
     * Cancels an upcoming [ScheduleCommitment] by [id], enforcing [rateLimiter].
     */
    open suspend fun cancelCommitment(id: String): CommitmentResult {
        if (!enabled) {
            return CommitmentResult.Rejected("DailyScheduleCoordinator is disabled.")
        }
        val trimmedId = id.trim()
        if (trimmedId.isBlank()) {
            return CommitmentResult.Rejected("Commitment id must not be blank.")
        }
        return mutationMutex.withLock {
            val nowMillis = clock.millis()
            val rateRejection = rateLimiter.checkAllowed(nowMillis)
            if (rateRejection != null) {
                return@withLock CommitmentResult.Rejected(rateRejection)
            }

            val cancelled =
                try {
                    store.cancelCommitment(trimmedId)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    logger.warn("Failed to cancel ScheduleCommitment {}: {}", trimmedId, ex.message)
                    return@withLock CommitmentResult.Rejected("Failed to cancel commitment: ${ex.message}")
                }

            if (!cancelled) {
                return@withLock CommitmentResult.Rejected(
                    "Schedule commitment '$trimmedId' was not found or already expired.",
                )
            }

            rateLimiter.recordMutation(nowMillis)
            CommitmentResult.Cancelled(trimmedId)
        }
    }

    private suspend fun runLoop() {
        var retryDelayMs = initialRetryDelayMs

        while (kotlin.coroutines.coroutineContext.isActive) {
            while (wakeSignal.tryReceive().isSuccess) {
                // Drain any coalesced wakeups before loading/evaluating schedule
            }
            val today = currentLocalDate()
            val loadedSchedule = loadOrGenerateSchedule(today)
            if (loadedSchedule == null) {
                activeSchedule = null
                val waitMs = retryDelayMs.coerceAtMost(maxRetryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxRetryDelayMs)
                awaitNextBoundaryOrWake(waitMs)
                continue
            }

            retryDelayMs = initialRetryDelayMs
            val block =
                mutationMutex.withLock {
                    val effectiveSchedule = activeSchedule?.takeIf { it.date == today } ?: loadedSchedule
                    activeSchedule = effectiveSchedule
                    val nowZoned = clock.instant().atZone(timezone)
                    val currentBlock = effectiveSchedule.blockAt(nowZoned.toLocalTime())
                    if (currentBlock != null) {
                        syncBlockTransitionLocked(today, currentBlock)
                    }
                    currentBlock
                }

            val nextBoundaryMillis = computeNextBoundaryMillis(today, block)
            val waitMs = nextBoundaryMillis - clock.millis()
            awaitNextBoundaryOrWake(waitMs)
        }
    }

    private suspend fun syncBlockTransitionLocked(
        today: LocalDate,
        block: ScheduleBlock,
    ) {
        val blockKey =
            PresenceSyncKey(
                date = today,
                startMinute = block.startMinute,
                activityTitle = block.activityTitle,
                statusText = block.statusText,
                availability = block.availability,
            )
        if (blockKey != lastSyncedBlockKey) {
            presencePort?.updatePresence(block)
            val prev = previousAvailability
            if (prev != null && prev.isUnavailable && block.availability.isRealTimeReplyAllowed) {
                invokeBecomeAvailable(prev, block)
            }
            lastSyncedBlockKey = blockKey
            previousAvailability = block.availability
        }
    }

    private suspend fun awaitNextBoundaryOrWake(waitMs: Long) {
        if (waitMs <= 0L) return
        coroutineScope {
            val timerJob = launch { delayFn(waitMs) }
            val wakeJob = launch { wakeSignal.receive() }
            select<Unit> {
                timerJob.onJoin { wakeJob.cancel() }
                wakeJob.onJoin { timerJob.cancel() }
            }
        }
    }

    private suspend fun loadOrGenerateSchedule(today: LocalDate): DailySchedule? {
        val existing =
            activeSchedule?.takeIf { it.date == today }
                ?: runCatching { store.getSchedule(today) }
                    .onFailure { ex ->
                        logger.warn("Failed to read persisted DailySchedule for {}: {}", today, ex.message)
                    }.getOrNull()
        if (existing != null) {
            return existing
        }

        val previousSummary =
            runCatching { store.getPreviousDaySummary(today) }
                .onFailure { ex ->
                    logger.warn("Failed to read previous day summary before {}: {}", today, ex.message)
                }.getOrNull()

        val todayCommitments =
            runCatching { store.listCommitmentsForDate(today) }
                .onFailure { ex ->
                    logger.warn("Failed to load ScheduleCommitments for {}: {}", today, ex.message)
                }.getOrDefault(emptyList())

        val generated =
            generator.generate(
                date = today,
                previousDaySummary = previousSummary,
                commitments = todayCommitments,
            )
        return generated
            .map { schedule ->
                if (todayCommitments.isEmpty()) {
                    schedule
                } else {
                    val commitmentBlocks = todayCommitments.map { it.toScheduleBlock(adjustedByConversation = true) }
                    schedule.copy(blocks = DailyScheduleSplicer.spliceBlocks(schedule.blocks, commitmentBlocks))
                }
            }.onSuccess { schedule ->
                runCatching { store.saveSchedule(schedule) }
                    .onFailure { ex ->
                        logger.warn("Failed to persist generated DailySchedule for {}: {}", today, ex.message)
                    }
            }.onFailure { ex ->
                logger.error(
                    "Failed to generate DailySchedule for {} (will retry with backoff; no fallback schedule): {}",
                    today,
                    ex.message,
                )
            }.getOrNull()
    }

    private suspend fun invokeBecomeAvailable(
        previous: AvailabilityState,
        current: ScheduleBlock,
    ) {
        val hook = onBecomeAvailableHook ?: return
        try {
            hook.invoke(previous, current)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(
                "onBecomeAvailable hook failed for transition {} -> {} ({}): {}",
                previous,
                current.availability,
                current.activityTitle,
                ex.message,
            )
        }
    }

    private fun computeNextBoundaryMillis(
        today: LocalDate,
        currentBlock: ScheduleBlock?,
    ): Long {
        val nextMinute = currentBlock?.endMinute ?: ScheduleBlock.MINUTES_PER_DAY
        val nextZoned =
            if (nextMinute >= ScheduleBlock.MINUTES_PER_DAY) {
                today.plusDays(1).atStartOfDay(timezone)
            } else {
                today.atTime(nextMinute / 60, nextMinute % 60).atZone(timezone)
            }
        return nextZoned.toInstant().toEpochMilli()
    }

    private fun currentLocalDate(): LocalDate = clock.instant().atZone(timezone).toLocalDate()

    companion object {
        const val DEFAULT_INITIAL_RETRY_DELAY_MS = 60_000L
        const val DEFAULT_MAX_RETRY_DELAY_MS = 900_000L
        const val MAX_COMMITMENT_HORIZON_DAYS = 7L
        const val MAX_ACTIVE_COMMITMENTS = 10
    }
}
