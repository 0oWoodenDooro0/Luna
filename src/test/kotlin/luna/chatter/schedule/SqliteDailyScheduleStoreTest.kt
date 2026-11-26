package luna.chatter.schedule

import luna.core.LunaHost
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteDailyScheduleStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = current

        fun advanceMinutes(minutes: Long) {
            current = current.plusSeconds(minutes * 60)
        }
    }

    private fun sampleSchedule(
        date: LocalDate,
        summary: String = "昨天追番到凌晨兩點",
    ): DailySchedule =
        DailySchedule(
            date = date,
            timezone = "Asia/Taipei",
            blocks =
                listOf(
                    ScheduleBlock(0, 480, "睡覺", "補眠中", "💤 睡覺中", AvailabilityState.SLEEPING),
                    ScheduleBlock(480, 720, "畫圖", "趕委託線稿", "🎨 趕稿中", AvailabilityState.BUSY),
                    ScheduleBlock(720, 780, "吃飯", "吃飯滑手機", "🍜 午休", AvailabilityState.BREAK),
                    ScheduleBlock(780, 1440, "耍廢", "聊天打遊戲", "✨ 閒晃中", AvailabilityState.AVAILABLE),
                ),
            summary = summary,
            createdAtMillis = 1_700_000_000_000L,
        )

    @Test
    fun `persists and reloads DailySchedule across SQLite database reopen and retrieves previous day summary`() {
        val dbPath = tempDir.resolve("schedule-${System.nanoTime()}.db").toString()
        val vecPath = tempDir.resolve("missing-vec0.so").toString()
        val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))

        val db1 = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store1 = SqliteDailyScheduleStore(database = db1, clock = clock)

        val day1 = LocalDate.of(2026, 11, 23)
        val day2 = LocalDate.of(2026, 11, 24)
        assertNull(store1.getSchedule(day1))
        assertNull(store1.getPreviousDaySummary(day2))

        store1.saveSchedule(sampleSchedule(day1, summary = "Day 1 summary"))
        store1.saveSchedule(sampleSchedule(day2, summary = "Day 2 summary"))

        // Reopen DB to verify SQLite persistence across restarts
        val db2 = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store2 = SqliteDailyScheduleStore(database = db2, clock = clock)

        val reloaded = store2.getSchedule(day2)
        assertNotNull(reloaded)
        assertEquals(day2, reloaded.date)
        assertEquals("Asia/Taipei", reloaded.timezone)
        assertEquals("Day 2 summary", reloaded.summary)
        assertEquals(4, reloaded.blocks.size)
        assertEquals(AvailabilityState.BUSY, reloaded.blocks[1].availability)

        assertEquals("Day 1 summary", store2.getPreviousDaySummary(day2))
        assertNull(store2.getPreviousDaySummary(day1))
    }

    @Test
    fun `records, updates, lists, and clears per-channel CatchUpWatermark across restarts`() {
        val dbPath = tempDir.resolve("catchup-${System.nanoTime()}.db").toString()
        val vecPath = tempDir.resolve("missing-vec0.so").toString()
        val clock = MutableClock(Instant.parse("2026-11-24T02:00:00Z"))

        val db1 = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store1 = SqliteDailyScheduleStore(database = db1, clock = clock)

        assertTrue(store1.listPendingCatchUps().isEmpty(), "Expected no pending catch-ups initially")

        val first =
            store1.recordPendingCatchUp(
                channelId = "chan-1",
                guildId = "guild-1",
                messageId = "1001",
                messageAtMillis = 1_700_000_010_000L,
            )
        assertEquals("1001", first.firstPendingMessageId)
        assertEquals("1001", first.lastPendingMessageId)

        clock.advanceMinutes(10)
        val updated =
            store1.recordPendingCatchUp(
                channelId = "chan-1",
                guildId = "guild-1",
                messageId = "1005",
                messageAtMillis = 1_700_000_610_000L,
            )
        assertEquals("1001", updated.firstPendingMessageId, "firstPendingMessageId must remain the earliest deferred message")
        assertEquals("1005", updated.lastPendingMessageId, "lastPendingMessageId must advance to latest message")

        store1.recordPendingCatchUp(
            channelId = "chan-2",
            guildId = "guild-2",
            messageId = "2001",
            messageAtMillis = 1_700_000_700_000L,
        )

        // Reopen DB
        val db2 = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store2 = SqliteDailyScheduleStore(database = db2, clock = clock)

        val pending = store2.listPendingCatchUps()
        assertEquals(2, pending.size)
        assertEquals("1001", store2.getCatchUpWatermark("chan-1")?.firstPendingMessageId)
        assertEquals("1005", store2.getCatchUpWatermark("chan-1")?.lastPendingMessageId)

        store2.clearCatchUpWatermark("chan-1")
        assertNull(store2.getCatchUpWatermark("chan-1"))
        assertEquals(1, store2.listPendingCatchUps().size)
        assertEquals("chan-2", store2.listPendingCatchUps().single().channelId)
    }

    @Test
    fun `persists adjustedByConversation flag and defaults legacy blocks_json without it to false`() {
        val dbPath = tempDir.resolve("adjusted-flag-${System.nanoTime()}.db").toString()
        val vecPath = tempDir.resolve("missing-vec0.so").toString()
        val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))

        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store = SqliteDailyScheduleStore(database = db, clock = clock)

        val dayWithAdjusted = LocalDate.of(2026, 11, 24)
        val adjustedSchedule =
            DailySchedule(
                date = dayWithAdjusted,
                timezone = "Asia/Taipei",
                blocks =
                    listOf(
                        ScheduleBlock(0, 720, "睡覺", "熟睡中", "💤 睡覺", AvailabilityState.SLEEPING),
                        ScheduleBlock(
                            720,
                            1440,
                            "跟大家玩",
                            "臨時約打遊戲",
                            "🎮 打遊戲",
                            AvailabilityState.AVAILABLE,
                            adjustedByConversation = true,
                        ),
                    ),
                summary = "臨時約打遊戲",
                createdAtMillis = 1_700_000_000_000L,
            )
        store.saveSchedule(adjustedSchedule)

        val legacyDate = LocalDate.of(2026, 11, 23)
        val legacyBlocksJson =
            """[{"startMinute":0,"endMinute":1440,"activityTitle":"舊排程","activityDetail":"無欄位","statusText":"✨","availability":"AVAILABLE"}]"""
        org.jetbrains.exposed.v1.jdbc.transactions.transaction(db) {
            val conn =
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
                    .current()
                    .connection.connection as java.sql.Connection
            conn
                .prepareStatement(
                    "INSERT INTO chatter_daily_schedules(schedule_date, timezone, summary, blocks_json, created_at) VALUES (?, ?, ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, legacyDate.toString())
                    ps.setString(2, "Asia/Taipei")
                    ps.setString(3, "legacy")
                    ps.setString(4, legacyBlocksJson)
                    ps.setLong(5, 1_699_900_000_000L)
                    ps.executeUpdate()
                }
        }

        val reloadedAdjusted = store.getSchedule(dayWithAdjusted)
        assertNotNull(reloadedAdjusted)
        assertEquals(false, reloadedAdjusted.blocks[0].adjustedByConversation)
        assertEquals(true, reloadedAdjusted.blocks[1].adjustedByConversation)

        val reloadedLegacy = store.getSchedule(legacyDate)
        assertNotNull(reloadedLegacy)
        assertEquals(false, reloadedLegacy.blocks.single().adjustedByConversation)
    }

    @Test
    fun `upserts, updates, cancels, lists, and auto-prunes expired ScheduleCommitments across SQLite reopen`() {
        val dbPath = tempDir.resolve("commitments-${System.nanoTime()}.db").toString()
        val vecPath = tempDir.resolve("missing-vec0.so").toString()
        val taipei = java.time.ZoneId.of("Asia/Taipei")
        // 2026-11-24 10:00 Asia/Taipei
        val clock =
            MutableClock(
                java.time.ZonedDateTime
                    .of(2026, 11, 24, 10, 0, 0, 0, taipei)
                    .toInstant(),
            )

        val db1 = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store1 = SqliteDailyScheduleStore(database = db1, clock = clock, timezone = taipei)

        val day24 = LocalDate.of(2026, 11, 24)
        val day25 = LocalDate.of(2026, 11, 25)
        val day26 = LocalDate.of(2026, 11, 26)
        val day30 = LocalDate.of(2026, 11, 30)

        val c1 =
            ScheduleCommitment(
                id = "c-1",
                targetDate = day25,
                startMinute = 1140,
                endMinute = 1260,
                activityTitle = "打瓦",
                activityDetail = "跟群友五排特戰英豪",
                statusText = "🎮 打瓦中",
                availability = AvailabilityState.AVAILABLE,
                createdAtMillis = clock.millis(),
            )
        val c2 =
            ScheduleCommitment(
                id = "c-2",
                targetDate = day26,
                startMinute = 600,
                endMinute = 720,
                activityTitle = "交稿",
                activityDetail = "送出插畫完稿",
                statusText = "🎨 交稿中",
                availability = AvailabilityState.BUSY,
                createdAtMillis = clock.millis() + 1000L,
            )
        store1.upsertCommitment(c1)
        store1.upsertCommitment(c2)

        // Update c1 in-place
        val c1Updated = c1.copy(activityTitle = "打 APEX", statusText = "🎮 打 APEX")
        store1.upsertCommitment(c1Updated)

        // Reopen DB
        val db2 = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store2 = SqliteDailyScheduleStore(database = db2, clock = clock, timezone = taipei)

        val for25 = store2.listCommitmentsForDate(day25)
        assertEquals(1, for25.size)
        assertEquals("打 APEX", for25.single().activityTitle)

        val upcoming = store2.listUpcomingCommitments(fromDate = day25, toDateInclusive = day30)
        assertEquals(listOf("c-1", "c-2"), upcoming.map { it.id })

        // Cancel c2
        assertEquals(true, store2.cancelCommitment("c-2"))
        assertEquals(false, store2.cancelCommitment("c-2"))
        assertEquals(listOf("c-1"), store2.listUpcomingCommitments(day25, day30).map { it.id })

        // Advance clock to 2026-11-25 00:05 (day25 is now today; day24 is past)
        // day25 commitment must STILL be returned by listCommitmentsForDate(day25) for rollover anchoring
        clock.advanceMinutes(14 * 60 + 5)
        assertEquals(1, store2.listCommitmentsForDate(day25).size)

        // Advance clock to 2026-11-26 00:05 (day25 is now expired/past) -> auto-pruned
        clock.advanceMinutes(24 * 60)
        assertTrue(store2.listCommitmentsForDate(day25).isEmpty(), "Expired commitment for day25 must be auto-pruned")
        assertTrue(store2.listUpcomingCommitments(day26, day30).isEmpty(), "Upcoming list must be empty after day25 expires")
    }

    @Test
    fun `persists activityType and migrates legacy chatter_schedule_commitments table without activity_type column`() {
        val dbPath = tempDir.resolve("activity-migration-${System.nanoTime()}.db").toString()
        val vecPath = tempDir.resolve("missing-vec0.so").toString()
        val taipei = java.time.ZoneId.of("Asia/Taipei")
        val clock =
            MutableClock(
                java.time.ZonedDateTime
                    .of(2026, 11, 24, 10, 0, 0, 0, taipei)
                    .toInstant(),
            )

        // Pre-create legacy table without activity_type column and insert a legacy row
        val db1 = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        org.jetbrains.exposed.v1.jdbc.transactions.transaction(db1) {
            val conn =
                (
                    org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
                        .current()
                        .connection.connection as java.sql.Connection
                )
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE chatter_schedule_commitments (
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
                    INSERT INTO chatter_schedule_commitments(
                        id, target_date, start_minute, end_minute,
                        activity_title, activity_detail, status_text, availability, created_at
                    ) VALUES ('legacy-c1', '2026-11-25', 600, 720, '舊約定', '沒有 activity_type 欄位', '🎨 趕稿中', 'BUSY', 1700000000000);
                    """.trimIndent(),
                )
            }
        }

        // Opening SqliteDailyScheduleStore must migrate idempotently and preserve legacy row as CUSTOM
        val store = SqliteDailyScheduleStore(database = db1, clock = clock, timezone = taipei)
        val day25 = LocalDate.of(2026, 11, 25)
        val legacyRow = store.listCommitmentsForDate(day25).single()
        assertEquals(PresenceActivityType.CUSTOM, legacyRow.activityType)

        // Upsert new commitment with PLAYING and reload after reopen
        store.upsertCommitment(
            ScheduleCommitment(
                id = "new-c2",
                targetDate = day25,
                startMinute = 1140,
                endMinute = 1260,
                activityTitle = "打薩爾達",
                activityDetail = "探索海拉魯",
                statusText = "薩爾達傳說",
                availability = AvailabilityState.AVAILABLE,
                createdAtMillis = clock.millis(),
                activityType = PresenceActivityType.PLAYING,
            ),
        )

        val db2 = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store2 = SqliteDailyScheduleStore(database = db2, clock = clock, timezone = taipei)
        val reloaded = store2.listCommitmentsForDate(day25)
        assertEquals(2, reloaded.size)
        assertEquals(PresenceActivityType.CUSTOM, reloaded[0].activityType)
        assertEquals(PresenceActivityType.PLAYING, reloaded[1].activityType)
    }
}
