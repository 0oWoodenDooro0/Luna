package luna.chatter.schedule

import luna.chatter.CircadianPeriod
import luna.chatter.CircadianPersonaDrift
import luna.chatter.PersonaMood
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DailySchedulePersonaDriftTest {
    private fun fixedMoodRandom(mood: PersonaMood): Random {
        val index = PersonaMood.entries.indexOf(mood)
        return object : Random() {
            override fun nextBits(bitCount: Int): Int = 0

            override fun nextInt(until: Int): Int = index.coerceAtMost(until - 1)

            override fun nextLong(
                from: Long,
                until: Long,
            ): Long = until - 1L
        }
    }

    private fun sampleSchedule(availability: AvailabilityState): DailySchedule =
        DailySchedule(
            date = LocalDate.of(2026, 11, 19),
            timezone = "Asia/Taipei",
            blocks =
                listOf(
                    ScheduleBlock(
                        startMinute = 0,
                        endMinute = 1440,
                        activityTitle = "剪片",
                        activityDetail = "剪接新影片順便滑 Discord",
                        statusText = "剪片中",
                        availability = availability,
                    ),
                ),
            summary = "今天專心剪片",
            createdAtMillis = 1000L,
        )

    private class FakeScheduleProvider(
        private val enabled: Boolean = true,
        private val schedule: DailySchedule? = null,
        private val block: ScheduleBlock? = schedule?.blocks?.firstOrNull(),
    ) : DailyScheduleProvider {
        override fun isEnabled(): Boolean = enabled

        override fun currentSchedule(): DailySchedule? = schedule

        override fun currentBlock(): ScheduleBlock? = block
    }

    @Test
    fun `active DailySchedule replaces fixed CircadianPeriod while keeping PersonaMood`() {
        // 03:00 Asia/Taipei (19:00 UTC prev day) would normally be LATE_NIGHT (+0.08)
        val lateNightInstant = Instant.parse("2026-11-18T19:00:00Z")
        val schedule = sampleSchedule(AvailabilityState.AVAILABLE)
        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                clock = Clock.fixed(lateNightInstant, ZoneId.of("UTC")),
                random = fixedMoodRandom(PersonaMood.ENERGETIC),
                scheduleProvider = FakeScheduleProvider(schedule = schedule),
            )

        val state = drift.currentState(baseThreshold = 0.70)

        assertNull(state.circadianPeriod)
        assertEquals(PersonaMood.ENERGETIC, state.mood)
        assertEquals(schedule.blocks.first(), state.scheduleBlock)
        assertEquals(schedule, state.dailySchedule)
        // 0.70 + 0.0 (AVAILABLE) - 0.08 (ENERGETIC) = 0.62 (not +0.08 from LATE_NIGHT)
        assertEquals(0.62, state.effectiveThreshold, 1e-6)
    }

    @Test
    fun `BREAK block applies positive threshold offset on top of PersonaMood`() {
        val instant = Instant.parse("2026-11-19T04:00:00Z")
        val schedule = sampleSchedule(AvailabilityState.BREAK)
        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                clock = Clock.fixed(instant, ZoneId.of("UTC")),
                random = fixedMoodRandom(PersonaMood.STEADY),
                scheduleProvider = FakeScheduleProvider(schedule = schedule),
            )

        val state = drift.currentState(baseThreshold = 0.70)

        assertNull(state.circadianPeriod)
        assertEquals(AvailabilityState.BREAK, state.scheduleBlock?.availability)
        assertEquals(0.70 + CircadianPersonaDrift.BREAK_THRESHOLD_OFFSET, state.effectiveThreshold, 1e-6)
    }

    @Test
    fun `falls back cleanly to CircadianPeriod when scheduleProvider returns null block`() {
        val lateNightInstant = Instant.parse("2026-11-18T19:00:00Z")
        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                clock = Clock.fixed(lateNightInstant, ZoneId.of("UTC")),
                random = fixedMoodRandom(PersonaMood.STEADY),
                scheduleProvider = FakeScheduleProvider(enabled = true, schedule = null, block = null),
            )

        val state = drift.currentState(baseThreshold = 0.70)

        assertEquals(CircadianPeriod.LATE_NIGHT, state.circadianPeriod)
        assertEquals(PersonaMood.STEADY, state.mood)
        assertNull(state.scheduleBlock)
        assertNull(state.dailySchedule)
        assertNotNull(state.circadianPeriod)
        assertEquals(0.78, state.effectiveThreshold, 1e-6)
    }
}
