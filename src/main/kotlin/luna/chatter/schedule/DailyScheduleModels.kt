package luna.chatter.schedule

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * Four-tier availability state for a [ScheduleBlock] in Luna's `Daily Schedule`.
 */
@Serializable
enum class AvailabilityState {
    /** Real-time `Chatter Pipeline` participation governed by `Participation Decision` + `PersonaMood`. */
    AVAILABLE,

    /** Real-time replies allowed with elevated participation threshold and quick-break prompt cue. */
    BREAK,

    /** Defers all `Chatter Pipeline` replies without LLM calls and records `Catch-Up Watermark`. */
    BUSY,

    /** Defers all `Chatter Pipeline` replies without LLM calls and records `Catch-Up Watermark`. */
    SLEEPING,
    ;

    val isRealTimeReplyAllowed: Boolean
        get() = this == AVAILABLE || this == BREAK

    val isUnavailable: Boolean
        get() = this == BUSY || this == SLEEPING
}

/**
 * Discord presence rendering mode for a [ScheduleBlock] or [ScheduleCommitment].
 */
@Serializable
enum class PresenceActivityType {
    CUSTOM,
    PLAYING,
    LISTENING,
    WATCHING,
    ;

    companion object {
        fun fromOrDefault(raw: String?): PresenceActivityType {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == normalized } ?: CUSTOM
        }
    }
}

/**
 * A single contiguous time block within a 24-hour [DailySchedule] (`[startMinute, endMinute)`).
 */
@Serializable
data class ScheduleBlock(
    val startMinute: Int,
    val endMinute: Int,
    val activityTitle: String,
    val activityDetail: String,
    val statusText: String,
    val availability: AvailabilityState,
    val adjustedByConversation: Boolean = false,
    val activityType: PresenceActivityType = PresenceActivityType.CUSTOM,
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY) {
            "startMinute must be in 0..1439, got $startMinute"
        }
        require(endMinute in 1..MINUTES_PER_DAY) {
            "endMinute must be in 1..1440, got $endMinute"
        }
        require(startMinute < endMinute) {
            "startMinute ($startMinute) must be < endMinute ($endMinute)"
        }
        require(activityTitle.isNotBlank()) { "activityTitle must not be blank" }
        require(activityDetail.isNotBlank()) { "activityDetail must not be blank" }
        require(statusText.isNotBlank()) { "statusText must not be blank" }
    }

    fun containsMinute(minuteOfDay: Int): Boolean = minuteOfDay in startMinute until endMinute

    fun formattedTimeRange(): String = "${formatMinute(startMinute)}–${formatMinute(endMinute)}"

    companion object {
        const val MINUTES_PER_DAY = 1440

        fun formatMinute(minute: Int): String {
            val hours = minute / 60
            val mins = minute % 60
            return "%02d:%02d".format(hours, mins)
        }
    }
}

/**
 * Globally shared 24-hour `Daily Schedule` for a local calendar day (`00:00–24:00` contiguous blocks).
 */
data class DailySchedule(
    val date: LocalDate,
    val timezone: String,
    val blocks: List<ScheduleBlock>,
    val summary: String,
    val createdAtMillis: Long,
) {
    init {
        require(timezone.isNotBlank()) { "timezone must not be blank" }
        require(summary.isNotBlank()) { "summary must not be blank" }
        require(blocks.isNotEmpty()) { "blocks must not be empty" }
        require(blocks.first().startMinute == 0) {
            "First block must start at minute 0 (00:00), got ${blocks.first().startMinute}"
        }
        require(blocks.last().endMinute == ScheduleBlock.MINUTES_PER_DAY) {
            "Last block must end at minute 1440 (24:00), got ${blocks.last().endMinute}"
        }
        for (i in 0 until blocks.size - 1) {
            val current = blocks[i]
            val next = blocks[i + 1]
            require(current.endMinute == next.startMinute) {
                "Schedule blocks must be contiguous without gaps or overlaps: " +
                    "block[$i].endMinute (${current.endMinute}) != block[${i + 1}].startMinute (${next.startMinute})"
            }
        }
    }

    fun blockAt(minuteOfDay: Int): ScheduleBlock? = blocks.firstOrNull { it.containsMinute(minuteOfDay) }

    fun blockAt(localTime: LocalTime): ScheduleBlock? = blockAt(localTime.hour * 60 + localTime.minute)
}

/**
 * Persisted per-channel watermark tracking deferred messages received during `BUSY` or `SLEEPING` blocks.
 */
data class CatchUpWatermark(
    val channelId: String,
    val guildId: String,
    val firstPendingMessageId: String,
    val firstPendingAtMillis: Long,
    val lastPendingMessageId: String,
    val lastPendingAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * Cross-day `Schedule Commitment` booked for a future local calendar date (`tomorrow..+7 days`).
 */
data class ScheduleCommitment(
    val id: String,
    val targetDate: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
    val activityTitle: String,
    val activityDetail: String,
    val statusText: String,
    val availability: AvailabilityState,
    val createdAtMillis: Long,
    val activityType: PresenceActivityType = PresenceActivityType.CUSTOM,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(startMinute in 0 until ScheduleBlock.MINUTES_PER_DAY) {
            "startMinute must be in 0..1439, got $startMinute"
        }
        require(endMinute in 1..ScheduleBlock.MINUTES_PER_DAY) {
            "endMinute must be in 1..1440, got $endMinute"
        }
        require(startMinute < endMinute) {
            "startMinute ($startMinute) must be < endMinute ($endMinute)"
        }
        require(activityTitle.isNotBlank()) { "activityTitle must not be blank" }
        require(activityDetail.isNotBlank()) { "activityDetail must not be blank" }
        require(statusText.isNotBlank()) { "statusText must not be blank" }
    }

    fun formattedTimeRange(): String = "${ScheduleBlock.formatMinute(startMinute)}–${ScheduleBlock.formatMinute(endMinute)}"

    fun toScheduleBlock(adjustedByConversation: Boolean = true): ScheduleBlock =
        ScheduleBlock(
            startMinute = startMinute,
            endMinute = endMinute,
            activityTitle = activityTitle,
            activityDetail = activityDetail,
            statusText = statusText,
            availability = availability,
            adjustedByConversation = adjustedByConversation,
            activityType = activityType,
        )
}

/**
 * Read-only seam exposing Luna's active `Daily Schedule` state to `ChatterService`,
 * `CircadianPersonaDrift`, and `/schedule` without coupling to coordinator lifecycle.
 */
interface DailyScheduleProvider {
    fun isEnabled(): Boolean

    fun currentSchedule(): DailySchedule?

    fun currentBlock(): ScheduleBlock?

    fun upcomingCommitments(): List<ScheduleCommitment> = emptyList()
}
