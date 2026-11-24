package luna.chatter.schedule

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate

/**
 * SQLite-backed implementation of [DailyScheduleStore] using Exposed's JDBC connection
 * and JSON-encoded [ScheduleBlock] lists.
 */
class SqliteDailyScheduleStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
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
