package luna.chatter.schedule

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DailyScheduleModelsTest {
    private val sampleDate = LocalDate.of(2026, 11, 24)

    private fun validBlocks(): List<ScheduleBlock> =
        listOf(
            ScheduleBlock(
                startMinute = 0,
                endMinute = 480,
                activityTitle = "睡覺",
                activityDetail = "窩在被窩裡熟睡中",
                statusText = "💤 補眠中",
                availability = AvailabilityState.SLEEPING,
            ),
            ScheduleBlock(
                startMinute = 480,
                endMinute = 720,
                activityTitle = "接案畫圖",
                activityDetail = "趕委託的線稿與上色",
                statusText = "🎨 趕稿中",
                availability = AvailabilityState.BUSY,
            ),
            ScheduleBlock(
                startMinute = 720,
                endMinute = 780,
                activityTitle = "吃午餐滑手機",
                activityDetail = "叫了外送邊吃邊看群組",
                statusText = "🍜 午休滑手機",
                availability = AvailabilityState.BREAK,
            ),
            ScheduleBlock(
                startMinute = 780,
                endMinute = 1440,
                activityTitle = "自由耍廢",
                activityDetail = "追番、打遊戲、跟大家聊天",
                statusText = "✨ 閒晃中",
                availability = AvailabilityState.AVAILABLE,
            ),
        )

    @Test
    fun `DailySchedule accepts contiguous 0 to 1440 blocks and resolves blockAt by local time`() {
        val schedule =
            DailySchedule(
                date = sampleDate,
                timezone = "Asia/Taipei",
                blocks = validBlocks(),
                summary = "白天趕稿，下午開始自由聊天",
                createdAtMillis = 1_700_000_000_000L,
            )

        assertEquals(AvailabilityState.SLEEPING, schedule.blockAt(LocalTime.of(0, 0))?.availability)
        assertEquals(AvailabilityState.SLEEPING, schedule.blockAt(LocalTime.of(7, 59))?.availability)
        assertEquals(AvailabilityState.BUSY, schedule.blockAt(LocalTime.of(8, 0))?.availability)
        assertEquals(AvailabilityState.BREAK, schedule.blockAt(LocalTime.of(12, 30))?.availability)
        assertEquals(AvailabilityState.AVAILABLE, schedule.blockAt(LocalTime.of(23, 59))?.availability)
        assertNotNull(schedule.blockAt(0))
        assertNull(schedule.blockAt(1440))
    }

    @Test
    fun `DailySchedule rejects gaps, overlaps, non-zero start, and non-1440 end`() {
        // Gap between 480 and 500
        assertFailsWith<IllegalArgumentException> {
            DailySchedule(
                date = sampleDate,
                timezone = "Asia/Taipei",
                blocks =
                    listOf(
                        validBlocks()[0],
                        validBlocks()[1].copy(startMinute = 500),
                        validBlocks()[2],
                        validBlocks()[3],
                    ),
                summary = "gap",
                createdAtMillis = 1L,
            )
        }

        // Overlap between 480 and 450
        assertFailsWith<IllegalArgumentException> {
            DailySchedule(
                date = sampleDate,
                timezone = "Asia/Taipei",
                blocks =
                    listOf(
                        validBlocks()[0],
                        validBlocks()[1].copy(startMinute = 450),
                        validBlocks()[2],
                        validBlocks()[3],
                    ),
                summary = "overlap",
                createdAtMillis = 1L,
            )
        }

        // Does not start at 0
        assertFailsWith<IllegalArgumentException> {
            DailySchedule(
                date = sampleDate,
                timezone = "Asia/Taipei",
                blocks = listOf(validBlocks()[3]),
                summary = "late start",
                createdAtMillis = 1L,
            )
        }

        // Does not end at 1440
        assertFailsWith<IllegalArgumentException> {
            DailySchedule(
                date = sampleDate,
                timezone = "Asia/Taipei",
                blocks = listOf(validBlocks()[0]),
                summary = "early end",
                createdAtMillis = 1L,
            )
        }
    }

    @Test
    fun `ScheduleBlock rejects out-of-range minutes and blank text fields`() {
        assertFailsWith<IllegalArgumentException> {
            ScheduleBlock(-1, 60, "title", "detail", "status", AvailabilityState.AVAILABLE)
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduleBlock(0, 1441, "title", "detail", "status", AvailabilityState.AVAILABLE)
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduleBlock(120, 120, "title", "detail", "status", AvailabilityState.AVAILABLE)
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduleBlock(0, 60, "  ", "detail", "status", AvailabilityState.AVAILABLE)
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduleBlock(0, 60, "title", "", "status", AvailabilityState.AVAILABLE)
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduleBlock(0, 60, "title", "detail", " ", AvailabilityState.AVAILABLE)
        }
    }
}
