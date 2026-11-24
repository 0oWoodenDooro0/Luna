package luna.chatter.schedule

import java.time.LocalDate

/**
 * Persistence boundary for daily generated `Daily Schedule` plans and per-channel
 * `Catch-Up Watermark` records.
 */
interface DailyScheduleStore {
    fun getSchedule(date: LocalDate): DailySchedule?

    fun saveSchedule(schedule: DailySchedule)

    fun getPreviousDaySummary(date: LocalDate): String?

    fun recordPendingCatchUp(
        channelId: String,
        guildId: String,
        messageId: String,
        messageAtMillis: Long,
    ): CatchUpWatermark

    fun getCatchUpWatermark(channelId: String): CatchUpWatermark?

    fun listPendingCatchUps(): List<CatchUpWatermark>

    fun clearCatchUpWatermark(channelId: String)

    fun upsertCommitment(commitment: ScheduleCommitment)

    fun cancelCommitment(id: String): Boolean

    fun listCommitmentsForDate(date: LocalDate): List<ScheduleCommitment>

    fun listUpcomingCommitments(
        fromDate: LocalDate,
        toDateInclusive: LocalDate,
    ): List<ScheduleCommitment>
}
