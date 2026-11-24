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
}
