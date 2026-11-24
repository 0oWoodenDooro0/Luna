package luna.chatter.schedule

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * A structured time-range patch (`[startMinute, endMinute)`) to splice into a [DailySchedule].
 */
data class SchedulePatch(
    val startMinute: Int,
    val endMinute: Int,
    val activityTitle: String,
    val activityDetail: String,
    val statusText: String,
    val availability: AvailabilityState,
)

/**
 * Shared duration and field validation guardrails for today's schedule patches and
 * cross-day `Schedule Commitment` entries.
 */
object ScheduleGuardrails {
    const val MAX_BUSY_MINUTES = 240
    const val MAX_SLEEPING_MINUTES = 480

    /**
     * Returns `null` if [durationMinutes] is valid for [availability], or a clear
     * model-readable rejection reason otherwise.
     */
    fun validateDuration(
        availability: AvailabilityState,
        durationMinutes: Int,
    ): String? {
        if (durationMinutes <= 0) {
            return "Duration must be positive, got $durationMinutes minutes."
        }
        return when (availability) {
            AvailabilityState.BUSY -> {
                if (durationMinutes > MAX_BUSY_MINUTES) {
                    "BUSY block duration ($durationMinutes min) exceeds the $MAX_BUSY_MINUTES-minute (4h) limit."
                } else {
                    null
                }
            }

            AvailabilityState.SLEEPING -> {
                if (durationMinutes > MAX_SLEEPING_MINUTES) {
                    "SLEEPING block duration ($durationMinutes min) exceeds the $MAX_SLEEPING_MINUTES-minute (8h) limit."
                } else {
                    null
                }
            }

            AvailabilityState.AVAILABLE,
            AvailabilityState.BREAK,
            -> {
                null
            }
        }
    }

    /**
     * Validates non-time fields and day-range bounds of a [SchedulePatch].
     */
    fun validatePatchFields(patch: SchedulePatch): String? {
        if (patch.startMinute !in 0 until ScheduleBlock.MINUTES_PER_DAY ||
            patch.endMinute !in 1..ScheduleBlock.MINUTES_PER_DAY ||
            patch.startMinute >= patch.endMinute
        ) {
            return "Invalid time range ${patch.startMinute}..${patch.endMinute}: " +
                "start_time must be before end_time within 00:00–24:00."
        }
        if (patch.activityTitle.isBlank()) {
            return "activityTitle must not be blank."
        }
        if (patch.activityDetail.isBlank()) {
            return "activityDetail must not be blank."
        }
        if (patch.statusText.isBlank()) {
            return "statusText must not be blank."
        }
        return null
    }
}

/**
 * Deterministic range splicer that splits, trims, and replaces overlapping [ScheduleBlock]
 * intervals in Kotlin while preserving `00:00–24:00` contiguity without gaps or overlaps.
 */
object DailyScheduleSplicer {
    sealed interface SpliceResult {
        data class Success(
            val schedule: DailySchedule,
            val appliedBlocks: List<ScheduleBlock>,
        ) : SpliceResult

        data class Rejected(
            val reason: String,
        ) : SpliceResult
    }

    /**
     * Validates and splices [patches] into today's [schedule] enforcing the strict future-only
     * boundary (`>= nowMinute`) and [ScheduleGuardrails] duration caps.
     */
    fun spliceToday(
        schedule: DailySchedule,
        patches: List<SchedulePatch>,
        nowMinute: Int,
    ): SpliceResult {
        if (patches.isEmpty()) {
            return SpliceResult.Rejected("At least one schedule patch is required.")
        }

        val clampedBlocks = ArrayList<ScheduleBlock>(patches.size)
        for (patch in patches) {
            val fieldError = ScheduleGuardrails.validatePatchFields(patch)
            if (fieldError != null) {
                return SpliceResult.Rejected(fieldError)
            }
            if (patch.endMinute <= nowMinute) {
                return SpliceResult.Rejected(
                    "Cannot modify past schedule block (${ScheduleBlock.formatMinute(patch.startMinute)}–" +
                        "${ScheduleBlock.formatMinute(patch.endMinute)} <= current time ${ScheduleBlock.formatMinute(nowMinute)}).",
                )
            }
            val effectiveStart = maxOf(patch.startMinute, nowMinute)
            val durationError = ScheduleGuardrails.validateDuration(patch.availability, patch.endMinute - effectiveStart)
            if (durationError != null) {
                return SpliceResult.Rejected(durationError)
            }
            clampedBlocks.add(
                ScheduleBlock(
                    startMinute = effectiveStart,
                    endMinute = patch.endMinute,
                    activityTitle = patch.activityTitle.trim(),
                    activityDetail = patch.activityDetail.trim(),
                    statusText = patch.statusText.trim(),
                    availability = patch.availability,
                    adjustedByConversation = true,
                ),
            )
        }

        val updatedBlocks = spliceBlocks(schedule.blocks, clampedBlocks)
        return SpliceResult.Success(
            schedule = schedule.copy(blocks = updatedBlocks),
            appliedBlocks = clampedBlocks,
        )
    }

    /**
     * Pure deterministic range splicer: sequentially splices [patches] into [blocks],
     * splitting/trimming overlapping blocks while preserving contiguity and `adjustedByConversation`.
     */
    fun spliceBlocks(
        blocks: List<ScheduleBlock>,
        patches: List<ScheduleBlock>,
    ): List<ScheduleBlock> {
        var current = blocks
        for (patch in patches) {
            current = spliceSingleBlock(current, patch)
        }
        return current
    }

    private fun spliceSingleBlock(
        blocks: List<ScheduleBlock>,
        patch: ScheduleBlock,
    ): List<ScheduleBlock> {
        val result = ArrayList<ScheduleBlock>(blocks.size + 2)
        var patchEmitted = false
        for (block in blocks) {
            if (block.endMinute <= patch.startMinute || block.startMinute >= patch.endMinute) {
                result.add(block)
                continue
            }
            if (block.startMinute < patch.startMinute) {
                result.add(block.copy(endMinute = patch.startMinute))
            }
            if (!patchEmitted) {
                result.add(patch)
                patchEmitted = true
            }
            if (block.endMinute > patch.endMinute) {
                result.add(block.copy(startMinute = patch.endMinute))
            }
        }
        return result
    }
}

/**
 * Thread-safe sliding-window and local-calendar-day rate limiter shared across
 * schedule mutations (default: max 3 per 10 minutes, max 10 per local calendar day).
 */
class ScheduleMutationRateLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val timezone: ZoneId,
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxPerDay: Int = DEFAULT_MAX_PER_DAY,
) {
    private val lock = Any()
    private val timestamps = ArrayDeque<Long>()

    /**
     * Checks whether a mutation is currently allowed without consuming quota.
     * Returns `null` if allowed, or a clear model-readable rejection reason.
     */
    fun checkAllowed(nowMillis: Long = clock.millis()): String? =
        synchronized(lock) {
            checkAllowedLocked(nowMillis)
        }

    /**
     * Records a successful mutation timestamp.
     */
    fun recordMutation(nowMillis: Long = clock.millis()) {
        synchronized(lock) {
            pruneLocked(nowMillis)
            timestamps.addLast(nowMillis)
        }
    }

    /**
     * Atomically checks quota and records a mutation if allowed.
     * Returns `null` if acquired, or a clear model-readable rejection reason.
     */
    fun tryAcquire(nowMillis: Long = clock.millis()): String? =
        synchronized(lock) {
            val rejection = checkAllowedLocked(nowMillis)
            if (rejection != null) return rejection
            timestamps.addLast(nowMillis)
            null
        }

    private fun checkAllowedLocked(nowMillis: Long): String? {
        pruneLocked(nowMillis)
        val windowCutoff = nowMillis - windowMillis
        val dayStartMillis = startOfLocalDayMillis(nowMillis)
        val inWindow = timestamps.count { it > windowCutoff }
        if (inWindow >= maxPerWindow) {
            return "Schedule mutation rate limit exceeded: maximum $maxPerWindow mutations per ${windowMillis / 60_000} minutes."
        }
        val inDay = timestamps.count { it >= dayStartMillis }
        if (inDay >= maxPerDay) {
            return "Schedule mutation daily limit exceeded: maximum $maxPerDay mutations per local calendar day."
        }
        return null
    }

    private fun pruneLocked(nowMillis: Long) {
        val retainCutoff = minOf(nowMillis - windowMillis, startOfLocalDayMillis(nowMillis))
        while (timestamps.isNotEmpty() && timestamps.first() < retainCutoff) {
            timestamps.removeFirst()
        }
    }

    private fun startOfLocalDayMillis(epochMillis: Long): Long {
        val localDate = Instant.ofEpochMilli(epochMillis).atZone(timezone).toLocalDate()
        return localDate.atStartOfDay(timezone).toInstant().toEpochMilli()
    }

    companion object {
        const val DEFAULT_MAX_PER_WINDOW = 3
        const val DEFAULT_WINDOW_MILLIS = 10 * 60 * 1000L
        const val DEFAULT_MAX_PER_DAY = 10
    }
}
