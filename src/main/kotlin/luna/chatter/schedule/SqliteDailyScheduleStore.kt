package luna.chatter.schedule

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import luna.chatter.ChatterConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * SQLite-backed implementation of [DailyScheduleStore] using Exposed's JDBC connection
 * and JSON-encoded [ScheduleBlock] lists.
 */
class SqliteDailyScheduleStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val timezone: ZoneId = ZoneId.of(ChatterConfig.DEFAULT_TIMEZONE),
) : DailyScheduleStore {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    init {
        transaction(database) {
            val conn = currentJdbcConnection()
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chatter_daily_schedules (
                        schedule_date TEXT PRIMARY KEY,
                        timezone TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        blocks_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chatter_catchup_watermarks (
                        channel_id TEXT PRIMARY KEY,
                        guild_id TEXT NOT NULL,
                        first_pending_message_id TEXT NOT NULL,
                        first_pending_at INTEGER NOT NULL,
                        last_pending_message_id TEXT NOT NULL,
                        last_pending_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS chatter_schedule_commitments (
                        id TEXT PRIMARY KEY,
                        target_date TEXT NOT NULL,
                        start_minute INTEGER NOT NULL,
                        end_minute INTEGER NOT NULL,
                        activity_title TEXT NOT NULL,
                        activity_detail TEXT NOT NULL,
                        status_text TEXT NOT NULL,
                        availability TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_chatter_schedule_commitments_target_date
                        ON chatter_schedule_commitments(target_date, start_minute);
                    """.trimIndent(),
                )
            }
        }
    }

    override fun getSchedule(date: LocalDate): DailySchedule? =
        transaction(database) {
            val conn = currentJdbcConnection()
            conn
                .prepareStatement(
                    """
                    SELECT schedule_date, timezone, summary, blocks_json, created_at
                    FROM chatter_daily_schedules
                    WHERE schedule_date = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, date.toString())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            val blocks = json.decodeFromString<List<ScheduleBlock>>(rs.getString("blocks_json"))
                            DailySchedule(
                                date = LocalDate.parse(rs.getString("schedule_date")),
                                timezone = rs.getString("timezone"),
                                blocks = blocks,
                                summary = rs.getString("summary"),
                                createdAtMillis = rs.getLong("created_at"),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override fun saveSchedule(schedule: DailySchedule) {
        val blocksJson = json.encodeToString(schedule.blocks)
        transaction(database) {
            val conn = currentJdbcConnection()
            conn
                .prepareStatement(
                    """
                    INSERT INTO chatter_daily_schedules(
                        schedule_date, timezone, summary, blocks_json, created_at
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(schedule_date) DO UPDATE SET
                        timezone = excluded.timezone,
                        summary = excluded.summary,
                        blocks_json = excluded.blocks_json,
                        created_at = excluded.created_at
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, schedule.date.toString())
                    ps.setString(2, schedule.timezone)
                    ps.setString(3, schedule.summary)
                    ps.setString(4, blocksJson)
                    ps.setLong(5, schedule.createdAtMillis)
                    ps.executeUpdate()
                }
        }
    }

    override fun getPreviousDaySummary(date: LocalDate): String? =
        transaction(database) {
            val conn = currentJdbcConnection()
            conn
                .prepareStatement(
                    """
                    SELECT summary
                    FROM chatter_daily_schedules
                    WHERE schedule_date < ?
                    ORDER BY schedule_date DESC
                    LIMIT 1
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, date.toString())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("summary") else null
                    }
                }
        }

    override fun recordPendingCatchUp(
        channelId: String,
        guildId: String,
        messageId: String,
        messageAtMillis: Long,
    ): CatchUpWatermark =
        transaction(database) {
            val now = clock.millis()
            val conn = currentJdbcConnection()
            conn
                .prepareStatement(
                    """
                    INSERT INTO chatter_catchup_watermarks(
                        channel_id, guild_id,
                        first_pending_message_id, first_pending_at,
                        last_pending_message_id, last_pending_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(channel_id) DO UPDATE SET
                        guild_id = excluded.guild_id,
                        last_pending_message_id = excluded.last_pending_message_id,
                        last_pending_at = excluded.last_pending_at,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, channelId)
                    ps.setString(2, guildId)
                    ps.setString(3, messageId)
                    ps.setLong(4, messageAtMillis)
                    ps.setString(5, messageId)
                    ps.setLong(6, messageAtMillis)
                    ps.setLong(7, now)
                    ps.executeUpdate()
                }
            requireNotNull(getCatchUpWatermarkInternal(conn, channelId)) {
                "Failed to reload CatchUpWatermark for channel $channelId after upsert"
            }
        }

    override fun getCatchUpWatermark(channelId: String): CatchUpWatermark? =
        transaction(database) {
            getCatchUpWatermarkInternal(currentJdbcConnection(), channelId)
        }

    override fun listPendingCatchUps(): List<CatchUpWatermark> =
        transaction(database) {
            val conn = currentJdbcConnection()
            conn
                .prepareStatement(
                    """
                    SELECT channel_id, guild_id,
                           first_pending_message_id, first_pending_at,
                           last_pending_message_id, last_pending_at,
                           updated_at
                    FROM chatter_catchup_watermarks
                    ORDER BY first_pending_at ASC
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(readWatermarkRow(rs))
                            }
                        }
                    }
                }
        }

    override fun clearCatchUpWatermark(channelId: String) {
        transaction(database) {
            val conn = currentJdbcConnection()
            conn
                .prepareStatement("DELETE FROM chatter_catchup_watermarks WHERE channel_id = ?")
                .use { ps ->
                    ps.setString(1, channelId)
                    ps.executeUpdate()
                }
        }
    }

    override fun upsertCommitment(commitment: ScheduleCommitment) {
        transaction(database) {
            val conn = currentJdbcConnection()
            pruneExpiredCommitmentsLocked(conn, currentLocalDate())
            conn
                .prepareStatement(
                    """
                    INSERT INTO chatter_schedule_commitments(
                        id, target_date, start_minute, end_minute,
                        activity_title, activity_detail, status_text, availability, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        target_date = excluded.target_date,
                        start_minute = excluded.start_minute,
                        end_minute = excluded.end_minute,
                        activity_title = excluded.activity_title,
                        activity_detail = excluded.activity_detail,
                        status_text = excluded.status_text,
                        availability = excluded.availability
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, commitment.id)
                    ps.setString(2, commitment.targetDate.toString())
                    ps.setInt(3, commitment.startMinute)
                    ps.setInt(4, commitment.endMinute)
                    ps.setString(5, commitment.activityTitle)
                    ps.setString(6, commitment.activityDetail)
                    ps.setString(7, commitment.statusText)
                    ps.setString(8, commitment.availability.name)
                    ps.setLong(9, commitment.createdAtMillis)
                    ps.executeUpdate()
                }
        }
    }

    override fun cancelCommitment(id: String): Boolean =
        transaction(database) {
            val conn = currentJdbcConnection()
            conn
                .prepareStatement("DELETE FROM chatter_schedule_commitments WHERE id = ?")
                .use { ps ->
                    ps.setString(1, id)
                    ps.executeUpdate() > 0
                }
        }

    override fun listCommitmentsForDate(date: LocalDate): List<ScheduleCommitment> =
        transaction(database) {
            val conn = currentJdbcConnection()
            pruneExpiredCommitmentsLocked(conn, currentLocalDate())
            conn
                .prepareStatement(
                    """
                    SELECT id, target_date, start_minute, end_minute,
                           activity_title, activity_detail, status_text, availability, created_at
                    FROM chatter_schedule_commitments
                    WHERE target_date = ?
                    ORDER BY start_minute ASC, created_at ASC
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, date.toString())
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(readCommitmentRow(rs))
                            }
                        }
                    }
                }
        }

    override fun listUpcomingCommitments(
        fromDate: LocalDate,
        toDateInclusive: LocalDate,
    ): List<ScheduleCommitment> =
        transaction(database) {
            val conn = currentJdbcConnection()
            pruneExpiredCommitmentsLocked(conn, currentLocalDate())
            conn
                .prepareStatement(
                    """
                    SELECT id, target_date, start_minute, end_minute,
                           activity_title, activity_detail, status_text, availability, created_at
                    FROM chatter_schedule_commitments
                    WHERE target_date >= ? AND target_date <= ?
                    ORDER BY target_date ASC, start_minute ASC, created_at ASC
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, fromDate.toString())
                    ps.setString(2, toDateInclusive.toString())
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(readCommitmentRow(rs))
                            }
                        }
                    }
                }
        }

    private fun pruneExpiredCommitmentsLocked(
        conn: Connection,
        today: LocalDate,
    ) {
        conn
            .prepareStatement("DELETE FROM chatter_schedule_commitments WHERE target_date < ?")
            .use { ps ->
                ps.setString(1, today.toString())
                ps.executeUpdate()
            }
    }

    private fun readCommitmentRow(rs: ResultSet): ScheduleCommitment =
        ScheduleCommitment(
            id = rs.getString("id"),
            targetDate = LocalDate.parse(rs.getString("target_date")),
            startMinute = rs.getInt("start_minute"),
            endMinute = rs.getInt("end_minute"),
            activityTitle = rs.getString("activity_title"),
            activityDetail = rs.getString("activity_detail"),
            statusText = rs.getString("status_text"),
            availability = AvailabilityState.valueOf(rs.getString("availability")),
            createdAtMillis = rs.getLong("created_at"),
        )

    private fun currentLocalDate(): LocalDate = clock.instant().atZone(timezone).toLocalDate()

    private fun getCatchUpWatermarkInternal(
        conn: Connection,
        channelId: String,
    ): CatchUpWatermark? =
        conn
            .prepareStatement(
                """
                SELECT channel_id, guild_id,
                       first_pending_message_id, first_pending_at,
                       last_pending_message_id, last_pending_at,
                       updated_at
                FROM chatter_catchup_watermarks
                WHERE channel_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, channelId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) readWatermarkRow(rs) else null
                }
            }

    private fun readWatermarkRow(rs: ResultSet): CatchUpWatermark =
        CatchUpWatermark(
            channelId = rs.getString("channel_id"),
            guildId = rs.getString("guild_id"),
            firstPendingMessageId = rs.getString("first_pending_message_id"),
            firstPendingAtMillis = rs.getLong("first_pending_at"),
            lastPendingMessageId = rs.getString("last_pending_message_id"),
            lastPendingAtMillis = rs.getLong("last_pending_at"),
            updatedAtMillis = rs.getLong("updated_at"),
        )

    private fun currentJdbcConnection(): Connection = TransactionManager.current().connection.connection as Connection
}
