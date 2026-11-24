package luna.chatter.schedule

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyScheduleSplicerTest {
    private val taipeiZone = ZoneId.of("Asia/Taipei")
    private val today = LocalDate.of(2026, 11, 24)

    private class MutableClock(
        private var currentInstant: Instant,
        private val zoneId: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant

        fun advanceMinutes(minutes: Long) {
            currentInstant = currentInstant.plusSeconds(minutes * 60)
        }
    }

    private fun baseSchedule(): DailySchedule =
        DailySchedule(
            date = today,
            timezone = "Asia/Taipei",
            blocks =
                listOf(
                    ScheduleBlock(0, 480, "睡覺", "熟睡中", "💤 睡覺", AvailabilityState.SLEEPING),
                    ScheduleBlock(480, 720, "畫圖", "趕稿中", "🎨 趕稿", AvailabilityState.BUSY),
                    ScheduleBlock(720, 780, "吃午餐", "午休滑手機", "🍜 午休", AvailabilityState.BREAK),
                    ScheduleBlock(780, 1440, "自由時間", "聊天打遊戲", "✨ 閒晃中", AvailabilityState.AVAILABLE),
                ),
            summary = "原訂計畫",
            createdAtMillis = 1_700_000_000_000L,
        )

    @Test
    fun `spliceToday splits middle of block and preserves 0 to 1440 contiguity and adjustedByConversation flags`() {
        val schedule = baseSchedule()
        val result =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(
                            startMinute = 840, // 14:00
                            endMinute = 960, // 16:00
                            activityTitle = "跟群友打瓦",
                            activityDetail = "約好一起排位",
                            statusText = "🎮 打瓦中",
                            availability = AvailabilityState.AVAILABLE,
                        ),
                    ),
                nowMinute = 800,
            )

        val success = assertIs<DailyScheduleSplicer.SpliceResult.Success>(result)
        val blocks = success.schedule.blocks
        assertEquals(6, blocks.size)

        // Untouched earlier blocks remain unadjusted
        assertEquals(0 to 480, blocks[0].startMinute to blocks[0].endMinute)
        assertEquals(false, blocks[0].adjustedByConversation)

        // Left remainder of 780..1440
        assertEquals(780 to 840, blocks[3].startMinute to blocks[3].endMinute)
        assertEquals("自由時間", blocks[3].activityTitle)
        assertEquals(false, blocks[3].adjustedByConversation)

        // Patched block
        assertEquals(840 to 960, blocks[4].startMinute to blocks[4].endMinute)
        assertEquals("跟群友打瓦", blocks[4].activityTitle)
        assertEquals(true, blocks[4].adjustedByConversation)

        // Right remainder of 780..1440
        assertEquals(960 to 1440, blocks[5].startMinute to blocks[5].endMinute)
        assertEquals("自由時間", blocks[5].activityTitle)
        assertEquals(false, blocks[5].adjustedByConversation)
    }

    @Test
    fun `spliceToday applies multiple patches spanning multiple blocks without gaps or overlaps`() {
        val schedule = baseSchedule()
        val result =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(
                            startMinute = 600, // 10:00 (splits BUSY 480..720 and covers BREAK 720..780)
                            endMinute = 840, // 14:00 (trims AVAILABLE 780..1440)
                            activityTitle = "提早吃飯聊天",
                            activityDetail = "跟大家一起摸魚",
                            statusText = "💬 聊天中",
                            availability = AvailabilityState.BREAK,
                        ),
                        SchedulePatch(
                            startMinute = 1320, // 22:00
                            endMinute = 1440, // 24:00
                            activityTitle = "早點睡",
                            activityDetail = "準備休息",
                            statusText = "💤 準備睡了",
                            availability = AvailabilityState.SLEEPING,
                        ),
                    ),
                nowMinute = 500,
            )

        val success = assertIs<DailyScheduleSplicer.SpliceResult.Success>(result)
        val blocks = success.schedule.blocks
        assertEquals(5, blocks.size)
        assertEquals(0 to 480, blocks[0].startMinute to blocks[0].endMinute)
        assertEquals(false, blocks[0].adjustedByConversation)
        assertEquals(480 to 600, blocks[1].startMinute to blocks[1].endMinute)
        assertEquals(false, blocks[1].adjustedByConversation)
        assertEquals(600 to 840, blocks[2].startMinute to blocks[2].endMinute)
        assertEquals(true, blocks[2].adjustedByConversation)
        assertEquals(840 to 1320, blocks[3].startMinute to blocks[3].endMinute)
        assertEquals(false, blocks[3].adjustedByConversation)
        assertEquals(1320 to 1440, blocks[4].startMinute to blocks[4].endMinute)
        assertEquals(true, blocks[4].adjustedByConversation)
    }

    @Test
    fun `spliceToday clamps startMinute to nowMinute when startMinute lt nowMinute lt endMinute`() {
        val schedule = baseSchedule()
        val result =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(
                            startMinute = 700, // 11:40, before now=750
                            endMinute = 900, // 15:00
                            activityTitle = "臨時開玩",
                            activityDetail = "從現在開始打遊戲",
                            statusText = "🎮 開玩",
                            availability = AvailabilityState.AVAILABLE,
                        ),
                    ),
                nowMinute = 750, // 12:30, inside 720..780 BREAK block
            )

        val success = assertIs<DailyScheduleSplicer.SpliceResult.Success>(result)
        val blocks = success.schedule.blocks
        // 720..750 remains untouched BREAK
        val pastRemainder = blocks.first { it.startMinute == 720 }
        assertEquals(750, pastRemainder.endMinute)
        assertEquals(AvailabilityState.BREAK, pastRemainder.availability)
        assertEquals(false, pastRemainder.adjustedByConversation)

        // Clamped patch starts at 750..900
        val patched = blocks.first { it.startMinute == 750 }
        assertEquals(900, patched.endMinute)
        assertEquals("臨時開玩", patched.activityTitle)
        assertEquals(true, patched.adjustedByConversation)
    }

    @Test
    fun `spliceToday rejects patches wholly in the past or with invalid time range`() {
        val schedule = baseSchedule()

        val pastResult =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(600, 700, "過去", "已結束", "⏳", AvailabilityState.AVAILABLE),
                    ),
                nowMinute = 700,
            )
        assertIs<DailyScheduleSplicer.SpliceResult.Rejected>(pastResult)

        val invertedResult =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(900, 800, "無效", "起迄顛倒", "❌", AvailabilityState.AVAILABLE),
                    ),
                nowMinute = 700,
            )
        assertIs<DailyScheduleSplicer.SpliceResult.Rejected>(invertedResult)

        val equalResult =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(800, 800, "無效", "零長度", "❌", AvailabilityState.AVAILABLE),
                    ),
                nowMinute = 700,
            )
        assertIs<DailyScheduleSplicer.SpliceResult.Rejected>(equalResult)
    }

    @Test
    fun `spliceToday rejects BUSY exceeding 4 hours and SLEEPING exceeding 8 hours`() {
        val schedule = baseSchedule()

        val tooLongBusy =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(600, 600 + 241, "超長忙碌", "超過四小時", "⛔", AvailabilityState.BUSY),
                    ),
                nowMinute = 500,
            )
        val busyRejected = assertIs<DailyScheduleSplicer.SpliceResult.Rejected>(tooLongBusy)
        assertTrue(busyRejected.reason.contains("BUSY"), "Expected BUSY duration rejection reason")

        val exactBusy =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(600, 600 + 240, "剛好四小時", "四小時忙碌", "🎨", AvailabilityState.BUSY),
                    ),
                nowMinute = 500,
            )
        assertIs<DailyScheduleSplicer.SpliceResult.Success>(exactBusy)

        val tooLongSleep =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(600, 600 + 481, "超長睡覺", "超過八小時", "💤", AvailabilityState.SLEEPING),
                    ),
                nowMinute = 500,
            )
        val sleepRejected = assertIs<DailyScheduleSplicer.SpliceResult.Rejected>(tooLongSleep)
        assertTrue(sleepRejected.reason.contains("SLEEPING"), "Expected SLEEPING duration rejection reason")

        val exactSleep =
            DailyScheduleSplicer.spliceToday(
                schedule = schedule,
                patches =
                    listOf(
                        SchedulePatch(600, 600 + 480, "剛好八小時", "八小時睡覺", "💤", AvailabilityState.SLEEPING),
                    ),
                nowMinute = 500,
            )
        assertIs<DailyScheduleSplicer.SpliceResult.Success>(exactSleep)
    }

    @Test
    fun `rate limiter enforces 3 mutations per 10 minutes and 10 mutations per local calendar day`() {
        val start = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, taipeiZone).toInstant()
        val clock = MutableClock(start, taipeiZone)
        val limiter = ScheduleMutationRateLimiter(clock = clock, timezone = taipeiZone)

        // 3 mutations within 10 minutes succeed
        repeat(3) {
            assertNull(limiter.checkAllowed())
            limiter.recordMutation()
        }
        // 4th mutation within 10 minutes is rejected
        val windowRejection = limiter.checkAllowed()
        assertNotNull(windowRejection)
        assertTrue(windowRejection.contains("10"), "Expected 10-minute window rate-limit rejection reason")

        // Advance 11 minutes -> window resets, can do 3 more (total 6)
        clock.advanceMinutes(11)
        repeat(3) {
            assertNull(limiter.checkAllowed())
            limiter.recordMutation()
        }

        // Advance 11 minutes -> 3 more (total 9)
        clock.advanceMinutes(11)
        repeat(3) {
            assertNull(limiter.checkAllowed())
            limiter.recordMutation()
        }

        // Advance 11 minutes -> 1 more (total 10, daily cap reached)
        clock.advanceMinutes(11)
        assertNull(limiter.checkAllowed())
        limiter.recordMutation()

        // 11th mutation on same calendar day is rejected even after advancing 11 minutes
        clock.advanceMinutes(11)
        val dailyRejection = limiter.checkAllowed()
        assertNotNull(dailyRejection)
        assertTrue(dailyRejection.contains("10"), "Expected 10-per-day rate-limit rejection reason")

        // Next local calendar day -> daily quota resets
        val nextDay = ZonedDateTime.of(2026, 11, 25, 0, 5, 0, 0, taipeiZone).toInstant()
        val nextDayClock = MutableClock(nextDay, taipeiZone)
        val nextDayLimiter = ScheduleMutationRateLimiter(clock = nextDayClock, timezone = taipeiZone)
        assertNull(nextDayLimiter.checkAllowed())
    }
}
