package luna.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedAlertRateLimiterTest {
    private class MutableClock(
        private var currentInstant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = currentInstant

        fun advanceSeconds(seconds: Long) {
            currentInstant = currentInstant.plusSeconds(seconds)
        }
    }

    private val baseInstant: Instant = Instant.parse("2026-11-15T01:00:00Z")

    private fun event(
        clock: Clock,
        severity: ErrorAlertSeverity = ErrorAlertSeverity.ERROR,
        loggerName: String = "luna.summarizer.SummaryService",
        message: String = "Gemini request timed out",
        throwableClass: String? = "java.lang.IllegalStateException",
        component: String? = "SummaryService",
    ): OwnerErrorEvent =
        OwnerErrorEvent(
            timestamp = clock.instant(),
            severity = severity,
            loggerName = loggerName,
            message = message,
            throwableClass = throwableClass,
            component = component,
        )

    @Test
    fun `first alert dispatches immediately, suppresses duplicates during cooldown, and reports suppressedCount after expiry`() {
        val clock = MutableClock(baseInstant)
        val limiter = BoundedAlertRateLimiter(cooldownSeconds = 300L, clock = clock)

        val first = limiter.acquire(event(clock))
        assertNotNull(first)
        assertEquals(0, first.suppressedCount)

        clock.advanceSeconds(10)
        assertNull(limiter.acquire(event(clock)))

        clock.advanceSeconds(289) // +299s total: still within 300s cooldown
        assertNull(limiter.acquire(event(clock)))

        clock.advanceSeconds(1) // +300s total: cooldown expired
        val afterCooldown = limiter.acquire(event(clock))
        assertNotNull(afterCooldown)
        assertEquals(2, afterCooldown.suppressedCount)

        // Counter was reset, so another expiry with no suppressed repeats sends suppressedCount = 0
        clock.advanceSeconds(300)
        val nextAfterQuiet = limiter.acquire(event(clock))
        assertNotNull(nextAfterQuiet)
        assertEquals(0, nextAfterQuiet.suppressedCount)
    }

    @Test
    fun `fingerprint normalization groups dynamic IDs, UUIDs, timestamps, and durations while separating distinct errors`() {
        val clock = MutableClock(baseInstant)
        val limiter =
            BoundedAlertRateLimiter(
                cooldownSeconds = 300L,
                maxErrorsPerMinute = 20,
                maxWarnsPerMinute = 20,
                clock = clock,
            )

        val firstBurst =
            event(
                clock = clock,
                message =
                    "Summary failed for message 123456789012345678 in channel 987654321098765432 " +
                        "uuid 550e8400-e29b-41d4-a716-446655440000 at 2026-11-15T01:02:03.456Z " +
                        "traceId=tr-alpha-111 elapsed 1523ms hex 0xdeadbeef",
            )
        val secondBurst =
            event(
                clock = clock,
                message =
                    "Summary failed for message 111222333444555666 in channel 444555666777888999 " +
                        "uuid a1b2c3d4-1234-5678-90ab-cdef12345678 at 2026-11-15T01:05:44.999Z " +
                        "traceId=tr-beta-999 elapsed 2890ms hex 0xcafebabe",
            )

        assertNotNull(limiter.acquire(firstBurst))
        clock.advanceSeconds(30)
        assertNull(limiter.acquire(secondBurst), "Burst failure with only transient IDs/timestamps changed must deduplicate")

        // Distinct severity, logger, component, exception class, and message must NOT be suppressed
        assertNotNull(
            limiter.acquire(firstBurst.copy(severity = ErrorAlertSeverity.WARN)),
            "WARN severity must have a distinct fingerprint from ERROR",
        )
        assertNotNull(
            limiter.acquire(firstBurst.copy(loggerName = "luna.chatter.ChatterService")),
            "Different loggerName must have a distinct fingerprint",
        )
        assertNotNull(
            limiter.acquire(firstBurst.copy(component = "ChatterService")),
            "Different component must have a distinct fingerprint",
        )
        assertNotNull(
            limiter.acquire(firstBurst.copy(throwableClass = "java.io.IOException")),
            "Different exception class must have a distinct fingerprint",
        )
        assertNotNull(
            limiter.acquire(event(clock, message = "Database connection pool exhausted")),
            "Distinct error message must have a distinct fingerprint",
        )

        clock.advanceSeconds(270) // 300s since firstBurst
        val afterExpiry = limiter.acquire(secondBurst)
        assertNotNull(afterExpiry)
        assertEquals(1, afterExpiry.suppressedCount)
    }

    @Test
    fun `bounded LRU cache evicts least recently used fingerprint at maxFingerprints capacity`() {
        val clock = MutableClock(baseInstant)
        val limiter =
            BoundedAlertRateLimiter(
                cooldownSeconds = 300L,
                maxErrorsPerMinute = 20,
                maxFingerprints = 3,
                clock = clock,
            )

        val f1 = event(clock, message = "Failure alpha")
        val f2 = event(clock, message = "Failure beta")
        val f3 = event(clock, message = "Failure gamma")
        val f4 = event(clock, message = "Failure delta")

        assertNotNull(limiter.acquire(f1))
        assertNotNull(limiter.acquire(f2))
        assertNotNull(limiter.acquire(f3))

        // Access f1 during cooldown so f1 becomes MRU and f2 becomes LRU
        clock.advanceSeconds(5)
        assertNull(limiter.acquire(f1))

        // Inserting f4 must evict f2 (the LRU entry)
        assertNotNull(limiter.acquire(f4))

        // f1 and f3 must still be cached and suppressed during cooldown
        assertNull(limiter.acquire(f1))
        assertNull(limiter.acquire(f3))

        // f2 was evicted, so it must dispatch immediately as a fresh fingerprint
        val reacquiredF2 = limiter.acquire(f2)
        assertNotNull(reacquiredF2)
        assertEquals(0, reacquiredF2.suppressedCount)
    }

    @Test
    fun `exhausting WARN budget drops excess WARN alerts with observable warning log without starving ERROR alerts`() {
        val clock = MutableClock(baseInstant)
        val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as Logger
        val logAppender = ListAppender<ILoggingEvent>().apply { start() }
        internalLogger.addAppender(logAppender)

        try {
            val limiter =
                BoundedAlertRateLimiter(
                    cooldownSeconds = 300L,
                    maxErrorsPerMinute = 5,
                    maxWarnsPerMinute = 5,
                    clock = clock,
                )
            val sixthWarn =
                event(
                    clock,
                    severity = ErrorAlertSeverity.WARN,
                    message = "Distinct warn overflow sierra",
                )
            val labels = listOf("alpha", "bravo", "charlie", "delta", "echo")
            labels.forEach { label ->
                assertNotNull(
                    limiter.acquire(event(clock, severity = ErrorAlertSeverity.WARN, message = "Warn $label")),
                    "Warn $label should fit within 5/min budget",
                )
            }

            clock.advanceSeconds(10)
            assertNull(
                limiter.acquire(sixthWarn),
                "6th distinct WARN within 1 minute must be dropped by WARN global rate limit",
            )
            assertTrue(
                logAppender.list.any {
                    it.level == Level.WARN &&
                        it.formattedMessage.contains("WARN") &&
                        it.formattedMessage.contains("rate limit")
                },
                "Rate-limit drop must emit observable WARN log on INTERNAL_LOGGER_NAME",
            )

            // ERROR budget remains completely independent and unstarved
            labels.forEach { label ->
                assertNotNull(
                    limiter.acquire(event(clock, severity = ErrorAlertSeverity.ERROR, message = "Critical error $label")),
                    "ERROR $label must dispatch uninterrupted even when WARN budget is exhausted",
                )
            }
            assertNull(
                limiter.acquire(event(clock, severity = ErrorAlertSeverity.ERROR, message = "Critical error foxtrot")),
                "6th distinct ERROR within 1 minute must be dropped by ERROR global rate limit",
            )

            // After 60 seconds from baseInstant, both sliding windows expire and budgets recover.
            // Because sixthWarn was globally dropped at +10s (never dispatched), it must NOT be stuck on 300s cooldown.
            clock.advanceSeconds(50) // +60s total
            val recoveredWarn = limiter.acquire(sixthWarn)
            assertNotNull(recoveredWarn, "Globally dropped alert must dispatch once 60s window recovers")
            assertEquals(0, recoveredWarn.suppressedCount)
        } finally {
            internalLogger.detachAppender(logAppender)
            logAppender.stop()
        }
    }

    @Test
    fun `cooldown-suppressed duplicates do not consume global budget and global drop preserves pending suppressedCount`() {
        val clock = MutableClock(baseInstant)
        val limiter =
            BoundedAlertRateLimiter(
                cooldownSeconds = 60L,
                maxErrorsPerMinute = 2,
                clock = clock,
            )

        val alpha = event(clock, message = "Error alpha")
        val beta = event(clock, message = "Error beta")
        val gamma = event(clock, message = "Error gamma")
        val delta = event(clock, message = "Error delta")

        // 1/2 budget used by alpha
        assertNotNull(limiter.acquire(alpha))
        // 5 duplicates of alpha during cooldown must NOT consume the remaining budget slot
        repeat(5) {
            assertNull(limiter.acquire(alpha))
        }
        // 2/2 budget used by beta -> still allowed!
        assertNotNull(limiter.acquire(beta))

        // Advance to +50s and +55s with new distinct errors? Budget is full until +60s.
        // Advance to +60s so alpha & beta expire from 60s window, then immediately consume both slots with gamma & delta
        clock.advanceSeconds(60)
        assertNotNull(limiter.acquire(gamma))
        assertNotNull(limiter.acquire(delta))

        // Now alpha's cooldown (60s) has expired with suppressedCount = 5, but global budget is 2/2 full.
        // Global drop must preserve suppressedCount (and count this dropped event too -> 6).
        assertNull(limiter.acquire(alpha))

        // Advance 60s so gamma & delta expire from global window
        clock.advanceSeconds(60)
        val alphaAfterRecovery = limiter.acquire(alpha)
        assertNotNull(alphaAfterRecovery)
        assertEquals(6, alphaAfterRecovery.suppressedCount)
    }

    @Test
    fun `non-positive cooldownSeconds and rate limit bounds fall back safely to defaults`() {
        val clock = MutableClock(baseInstant)
        val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as Logger
        val logAppender = ListAppender<ILoggingEvent>().apply { start() }
        internalLogger.addAppender(logAppender)

        try {
            val zeroCooldownLimiter =
                BoundedAlertRateLimiter(
                    cooldownSeconds = 0L,
                    maxErrorsPerMinute = 0,
                    maxWarnsPerMinute = -3,
                    maxFingerprints = -10,
                    clock = clock,
                )
            assertEquals(ErrorNotifyConfig.DEFAULT_COOLDOWN_SECONDS.toLong(), zeroCooldownLimiter.effectiveCooldownSeconds)
            assertEquals(BoundedAlertRateLimiter.DEFAULT_MAX_ERRORS_PER_MINUTE, zeroCooldownLimiter.effectiveMaxErrorsPerMinute)
            assertEquals(BoundedAlertRateLimiter.DEFAULT_MAX_WARNS_PER_MINUTE, zeroCooldownLimiter.effectiveMaxWarnsPerMinute)
            assertEquals(BoundedAlertRateLimiter.DEFAULT_MAX_FINGERPRINTS, zeroCooldownLimiter.effectiveMaxFingerprints)

            val negativeCooldownLimiter = BoundedAlertRateLimiter(cooldownSeconds = -15L, clock = clock)
            assertEquals(300L, negativeCooldownLimiter.effectiveCooldownSeconds)

            assertNotNull(negativeCooldownLimiter.acquire(event(clock)))
            clock.advanceSeconds(299)
            assertNull(negativeCooldownLimiter.acquire(event(clock)), "Fallback 300s cooldown must still suppress at 299s")
            clock.advanceSeconds(1)
            val afterDefaultCooldown = negativeCooldownLimiter.acquire(event(clock))
            assertNotNull(afterDefaultCooldown)
            assertEquals(1, afterDefaultCooldown.suppressedCount)

            assertTrue(
                logAppender.list.any { it.level == Level.WARN && it.formattedMessage.contains("falling back to default") },
                "Non-positive cooldown fallback must emit observable warning log",
            )
        } finally {
            internalLogger.detachAppender(logAppender)
            logAppender.stop()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `OwnerErrorNotifier uses BoundedAlertRateLimiter by default and formats suppressedCount after cooldown`() =
        runTest {
            val clock = MutableClock(baseInstant)
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config =
                        ErrorNotifyConfig(
                            recipientUserIds = listOf(Snowflake(42uL)),
                            cooldownSeconds = 60,
                        ),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            notifier.submit(event(clock, message = "Upstream failed for message 111222333444555666"))
            testScheduler.advanceUntilIdle()

            clock.advanceSeconds(10)
            notifier.submit(event(clock, message = "Upstream failed for message 999888777666555444"))
            testScheduler.advanceUntilIdle()

            clock.advanceSeconds(50) // +60s total: cooldown expired
            notifier.submit(event(clock, message = "Upstream failed for message 777666555444333222"))
            testScheduler.advanceUntilIdle()
            notifier.flushAndClose()

            assertEquals(2, delivered.size)
            assertTrue(delivered[1].second.contains("1 repeated alert(s) during cooldown"))
        }
}
