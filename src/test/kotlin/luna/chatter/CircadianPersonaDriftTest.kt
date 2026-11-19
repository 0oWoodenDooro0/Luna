package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircadianPersonaDriftTest {
    @Test
    fun `CircadianPersonaDrift resolves default Asia-Taipei timezone, custom valid timezone, and falls back with warning log on invalid timezone`() {
        val fixedClock = Clock.fixed(Instant.parse("2026-11-19T00:30:00Z"), ZoneId.of("UTC"))

        val defaultDrift = CircadianPersonaDrift(clock = fixedClock)
        val defaultState = defaultDrift.currentState(0.7)
        assertEquals("Asia/Taipei", defaultState.timezone)
        assertEquals("08:30", defaultState.formattedTime)
        assertEquals("星期四", defaultState.formattedWeekday)

        val nyDrift = CircadianPersonaDrift(timezone = "America/New_York", clock = fixedClock)
        val nyState = nyDrift.currentState(0.7)
        assertEquals("America/New_York", nyState.timezone)
        assertEquals("19:30", nyState.formattedTime)
        assertEquals("星期三", nyState.formattedWeekday)

        val driftLogger = LoggerFactory.getLogger(CircadianPersonaDrift::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        driftLogger.addAppender(appender)
        try {
            val invalidDrift = CircadianPersonaDrift(timezone = "Mars/Olympus_Mons", clock = fixedClock)
            val fallbackState = invalidDrift.currentState(0.7)
            assertEquals("Asia/Taipei", fallbackState.timezone)
            assertEquals("08:30", fallbackState.formattedTime)
            assertTrue(
                appender.list.any { event ->
                    event.level == Level.WARN &&
                        event.formattedMessage.contains("Mars/Olympus_Mons") &&
                        event.formattedMessage.contains("Asia/Taipei")
                },
                "Expected warning log when invalid CHATTER_TIMEZONE falls back to Asia/Taipei",
            )
        } finally {
            driftLogger.detachAppender(appender)
        }
    }

    private class MutableClock(
        private var currentInstant: Instant,
        private val zone: ZoneId = ZoneId.of("UTC"),
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant

        fun advanceSeconds(seconds: Long) {
            currentInstant = currentInstant.plusSeconds(seconds)
        }
    }

    @Test
    fun `CircadianPersonaDrift maps all four circadian windows across exact boundary minutes and applies documented offsets`() {
        // Select STEADY mood (index 2 -> offset 0.0) deterministically via seeded Random or testing period offsets
        val steadyIndex = PersonaMood.entries.indexOf(PersonaMood.STEADY)
        val fixedZeroOffsetRandom =
            object : kotlin.random.Random() {
                override fun nextBits(bitCount: Int): Int = 0

                override fun nextInt(until: Int): Int = steadyIndex.coerceAtMost(until - 1)

                override fun nextLong(
                    from: Long,
                    until: Long,
                ): Long = until - 1L
            }

        data class BoundaryCase(
            val localIso: String,
            val expectedPeriod: CircadianPeriod,
            val expectedPeriodOffset: Double,
            val expectedFormattedTime: String,
        )

        val cases =
            listOf(
                BoundaryCase("2026-11-19T00:59:00+08:00", CircadianPeriod.EVENING, -0.02, "00:59"),
                BoundaryCase("2026-11-19T01:00:00+08:00", CircadianPeriod.LATE_NIGHT, 0.08, "01:00"),
                BoundaryCase("2026-11-19T05:59:00+08:00", CircadianPeriod.LATE_NIGHT, 0.08, "05:59"),
                BoundaryCase("2026-11-19T06:00:00+08:00", CircadianPeriod.MORNING, 0.02, "06:00"),
                BoundaryCase("2026-11-19T11:59:00+08:00", CircadianPeriod.MORNING, 0.02, "11:59"),
                BoundaryCase("2026-11-19T12:00:00+08:00", CircadianPeriod.DAYTIME, 0.0, "12:00"),
                BoundaryCase("2026-11-19T17:59:00+08:00", CircadianPeriod.DAYTIME, 0.0, "17:59"),
                BoundaryCase("2026-11-19T18:00:00+08:00", CircadianPeriod.EVENING, -0.02, "18:00"),
                BoundaryCase("2026-11-19T23:59:00+08:00", CircadianPeriod.EVENING, -0.02, "23:59"),
                BoundaryCase("2026-11-20T00:00:00+08:00", CircadianPeriod.EVENING, -0.02, "00:00"),
            )

        for (case in cases) {
            val clock =
                Clock.fixed(
                    Instant.parse(
                        java.time.OffsetDateTime
                            .parse(case.localIso)
                            .toInstant()
                            .toString(),
                    ),
                    ZoneId.of("UTC"),
                )
            val drift = CircadianPersonaDrift(timezone = "Asia/Taipei", clock = clock, random = fixedZeroOffsetRandom)
            val state = drift.currentState(baseThreshold = 0.7)

            assertEquals(case.expectedPeriod, state.circadianPeriod, "Mismatch for ${case.localIso}")
            assertEquals(case.expectedPeriodOffset, state.circadianPeriod?.thresholdOffset, "Offset mismatch for ${case.localIso}")
            assertEquals(case.expectedFormattedTime, state.formattedTime, "Time mismatch for ${case.localIso}")
            assertEquals(PersonaMood.STEADY, state.mood)
            assertEquals(0.7 + case.expectedPeriodOffset, state.effectiveThreshold, 1e-9, "Effective threshold mismatch for ${case.localIso}")
        }
    }

    @Test
    fun `CircadianPersonaDrift keeps mood stable within 2-4h interval, transitions randomly after expiry, and clamps effectiveThreshold to 0_05-0_98`() {
        val clock = MutableClock(Instant.parse("2026-11-19T04:00:00Z")) // 12:00 Asia/Taipei (DAYTIME 0.0)
        // Use a deterministic Random with 3-hour (10_800_000ms) intervals
        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                clock = clock,
                random = kotlin.random.Random(42),
            )

        val initialState = drift.currentState(0.7)
        val initialMood = initialState.mood!!
        assertTrue(initialMood.thresholdOffset in -0.08..0.12)

        // At +1h59m59s (strictly under 2-hour minimum), mood must stay identical
        clock.advanceSeconds(2 * 3600L - 1L)
        val beforeMinExpiry = drift.currentState(0.7)
        assertEquals(initialMood, beforeMinExpiry.mood)
        assertEquals(initialState.effectiveThreshold, beforeMinExpiry.effectiveThreshold)

        // Advance past the 4-hour maximum ceiling so expiry is guaranteed to have passed
        clock.advanceSeconds(2 * 3600L + 2L)
        val afterMaxExpiry = drift.currentState(0.7)
        assertTrue(afterMaxExpiry.mood != initialMood, "Expected mood transition once 2-4h expiry passed")
        assertTrue(afterMaxExpiry.mood!!.thresholdOffset in -0.08..0.12)

        // Verify clamping at both ends [0.05, 0.98] across extreme base thresholds and worst-case offsets
        // Late night (03:00 Taipei = 19:00 UTC) +0.08, plus max mood offset +0.12
        val lateNightClock = Clock.fixed(Instant.parse("2026-11-18T19:00:00Z"), ZoneId.of("UTC"))
        val maxMoodIndex = PersonaMood.entries.indexOf(PersonaMood.LOW_BATTERY)
        val maxOffsetRandom =
            object : kotlin.random.Random() {
                override fun nextBits(bitCount: Int): Int = 0

                override fun nextInt(until: Int): Int = maxMoodIndex.coerceAtMost(until - 1)
            }
        val highDrift = CircadianPersonaDrift(timezone = "Asia/Taipei", clock = lateNightClock, random = maxOffsetRandom)
        assertEquals(0.98, highDrift.currentState(0.95).effectiveThreshold)
        assertEquals(0.98, highDrift.currentState(1.0).effectiveThreshold)

        // Evening (20:00 Taipei = 12:00 UTC) -0.02, plus min mood offset -0.08
        val eveningClock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneId.of("UTC"))
        val minMoodIndex = PersonaMood.entries.indexOf(PersonaMood.ENERGETIC)
        val minOffsetRandom =
            object : kotlin.random.Random() {
                override fun nextBits(bitCount: Int): Int = 0

                override fun nextInt(until: Int): Int = minMoodIndex.coerceAtMost(until - 1)
            }
        val lowDrift = CircadianPersonaDrift(timezone = "Asia/Taipei", clock = eveningClock, random = minOffsetRandom)
        assertEquals(0.05, lowDrift.currentState(0.0).effectiveThreshold)
        assertEquals(0.05, lowDrift.currentState(0.08).effectiveThreshold)
    }

    @Test
    fun `CircadianPersonaDrift with enabled=false returns exact configured baseThreshold without circadian or mood offsets`() {
        val lateNightClock = Clock.fixed(Instant.parse("2026-11-18T19:00:00Z"), ZoneId.of("UTC"))
        val disabledDrift = CircadianPersonaDrift(timezone = "Asia/Taipei", enabled = false, clock = lateNightClock)

        val stateDefault = disabledDrift.currentState(0.7)
        assertEquals(false, stateDefault.driftEnabled)
        assertEquals(null, stateDefault.circadianPeriod)
        assertEquals(null, stateDefault.mood)
        assertEquals(0.7, stateDefault.effectiveThreshold)

        // Extreme base thresholds are preserved un-clamped when drift is disabled
        assertEquals(0.02, disabledDrift.currentState(0.02).effectiveThreshold)
        assertEquals(0.99, disabledDrift.currentState(0.99).effectiveThreshold)
    }
}
