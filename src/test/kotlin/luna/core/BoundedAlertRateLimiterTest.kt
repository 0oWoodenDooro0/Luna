package luna.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `first alert dispatches immediately, suppresses duplicates during cooldown, and reports suppressedCount after expiry`() =
        runTest {
            val clock = MutableClock(baseInstant)
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL)), cooldownSeconds = 300),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            try {
                notifier.submit(event(clock))
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "First alert must dispatch immediately")
                assertFalse(delivered[0].second.contains("repeated alert(s)"), "First alert must have 0 suppressed repeats")

                clock.advanceSeconds(10)
                notifier.submit(event(clock))
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Duplicate at +10s must be suppressed")

                clock.advanceSeconds(289) // +299s total: still within 300s cooldown
                notifier.submit(event(clock))
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Duplicate at +299s must still be suppressed")

                clock.advanceSeconds(1) // +300s total: cooldown expired
                notifier.submit(event(clock))
                testScheduler.advanceUntilIdle()
                assertEquals(2, delivered.size, "Alert at +300s after cooldown expiry must dispatch")
                assertTrue(
                    delivered[1].second.contains("2 repeated alert(s) during cooldown"),
                    "Post-cooldown alert must report suppressedCount=2",
                )

                // Counter was reset, so another expiry with no suppressed repeats sends no suppressed header
                clock.advanceSeconds(300)
                notifier.submit(event(clock))
                testScheduler.advanceUntilIdle()
                assertEquals(3, delivered.size, "Alert after quiet cooldown must dispatch")
                assertFalse(
                    delivered[2].second.contains("repeated alert(s)"),
                    "Counter must reset after post-cooldown dispatch",
                )
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `fingerprint normalization groups dynamic IDs, UUIDs, timestamps, and durations while separating distinct errors`() =
        runTest {
            val clock = MutableClock(baseInstant)
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL)), cooldownSeconds = 300),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

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

            try {
                notifier.submit(firstBurst)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "First burst alert must dispatch")

                clock.advanceSeconds(30)
                notifier.submit(secondBurst)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Burst failure with only transient IDs/timestamps changed must deduplicate")

                // Distinct severity, logger, component, and exception class must NOT be suppressed
                notifier.submit(firstBurst.copy(severity = ErrorAlertSeverity.WARN))
                testScheduler.advanceUntilIdle()
                assertEquals(2, delivered.size, "WARN severity must have a distinct fingerprint from ERROR")

                notifier.submit(firstBurst.copy(loggerName = "luna.chatter.ChatterService"))
                testScheduler.advanceUntilIdle()
                assertEquals(3, delivered.size, "Different loggerName must have a distinct fingerprint")

                notifier.submit(firstBurst.copy(component = "ChatterService"))
                testScheduler.advanceUntilIdle()
                assertEquals(4, delivered.size, "Different component must have a distinct fingerprint")

                notifier.submit(firstBurst.copy(throwableClass = "java.io.IOException"))
                testScheduler.advanceUntilIdle()
                assertEquals(5, delivered.size, "Different exception class must have a distinct fingerprint")

                // Advance past 60s global ERROR rate-limit window while staying within 300s cooldown for firstBurst
                clock.advanceSeconds(61)
                notifier.submit(event(clock, message = "Database connection pool exhausted"))
                testScheduler.advanceUntilIdle()
                assertEquals(6, delivered.size, "Distinct error message must have a distinct fingerprint")

                clock.advanceSeconds(209) // 300s total since firstBurst
                notifier.submit(secondBurst)
                testScheduler.advanceUntilIdle()
                assertEquals(7, delivered.size, "Burst fingerprint must dispatch after 300s cooldown")
                assertTrue(
                    delivered.last().second.contains("1 repeated alert(s) during cooldown"),
                    "Post-cooldown burst alert must report suppressedCount=1",
                )
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `exhausting WARN budget drops excess WARN alerts with observable warning log without starving ERROR alerts`() =
        runTest {
            val clock = MutableClock(baseInstant)
            val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as Logger
            val logAppender = ListAppender<ILoggingEvent>().apply { start() }
            internalLogger.addAppender(logAppender)

            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL)), cooldownSeconds = 300),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            try {
                val sixthWarn =
                    event(
                        clock,
                        severity = ErrorAlertSeverity.WARN,
                        message = "Distinct warn overflow sierra",
                    )
                val labels = listOf("alpha", "bravo", "charlie", "delta", "echo")
                labels.forEachIndexed { idx, label ->
                    notifier.submit(event(clock, severity = ErrorAlertSeverity.WARN, message = "Warn $label"))
                    testScheduler.advanceUntilIdle()
                    assertEquals(idx + 1, delivered.size, "Warn $label should fit within 5/min budget")
                }

                clock.advanceSeconds(10)
                notifier.submit(sixthWarn)
                testScheduler.advanceUntilIdle()
                assertEquals(5, delivered.size, "6th distinct WARN within 1 minute must be dropped by WARN global rate limit")
                assertTrue(
                    logAppender.list.any {
                        it.level == Level.WARN &&
                            it.formattedMessage.contains("WARN") &&
                            it.formattedMessage.contains("rate limit")
                    },
                    "Rate-limit drop must emit observable WARN log on INTERNAL_LOGGER_NAME",
                )

                // ERROR budget remains completely independent and unstarved
                labels.forEachIndexed { idx, label ->
                    notifier.submit(event(clock, severity = ErrorAlertSeverity.ERROR, message = "Critical error $label"))
                    testScheduler.advanceUntilIdle()
                    assertEquals(6 + idx, delivered.size, "ERROR $label must dispatch uninterrupted even when WARN budget is exhausted")
                }
                notifier.submit(event(clock, severity = ErrorAlertSeverity.ERROR, message = "Critical error foxtrot"))
                testScheduler.advanceUntilIdle()
                assertEquals(10, delivered.size, "6th distinct ERROR within 1 minute must be dropped by ERROR global rate limit")

                // After 60 seconds from baseInstant, both sliding windows expire and budgets recover.
                // Because sixthWarn was globally dropped at +10s (never dispatched), it must NOT be stuck on 300s cooldown.
                clock.advanceSeconds(50) // +60s total
                notifier.submit(sixthWarn)
                testScheduler.advanceUntilIdle()
                assertEquals(11, delivered.size, "Globally dropped alert must dispatch once 60s window recovers")
                assertFalse(
                    delivered.last().second.contains("repeated alert(s)"),
                    "Recovered globally dropped alert must have suppressedCount=0",
                )
            } finally {
                notifier.flushAndClose()
                internalLogger.detachAppender(logAppender)
                logAppender.stop()
            }
        }

    @Test
    fun `cooldown-suppressed duplicates do not consume global budget and global drop preserves pending suppressedCount`() =
        runTest {
            val clock = MutableClock(baseInstant)
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL)), cooldownSeconds = 60),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            val alpha = event(clock, message = "Error alpha")

            try {
                // 1/5 budget used by alpha
                notifier.submit(alpha)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "First alpha must dispatch")

                // 5 duplicates of alpha during cooldown must NOT consume the remaining 4 budget slots
                repeat(5) {
                    notifier.submit(alpha)
                }
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Cooldown duplicates of alpha must be suppressed")

                // Remaining 4/5 budget slots used by beta..epsilon -> all allowed!
                listOf("beta", "gamma", "delta", "epsilon").forEachIndexed { idx, name ->
                    notifier.submit(event(clock, message = "Error $name"))
                    testScheduler.advanceUntilIdle()
                    assertEquals(2 + idx, delivered.size, "Error $name must use remaining global budget slot")
                }

                // Advance +60s so alpha..epsilon expire from 60s window, then consume all 5 slots with 5 new distinct errors
                clock.advanceSeconds(60)
                listOf("zeta", "eta", "theta", "iota", "kappa").forEachIndexed { idx, name ->
                    notifier.submit(event(clock, message = "Error $name"))
                    testScheduler.advanceUntilIdle()
                    assertEquals(6 + idx, delivered.size, "Error $name must dispatch in fresh 60s window")
                }

                // Now alpha's cooldown (60s) has expired with suppressedCount = 5, but global budget is 5/5 full.
                // Global drop must preserve suppressedCount (and count this dropped event too -> 6).
                notifier.submit(alpha)
                testScheduler.advanceUntilIdle()
                assertEquals(10, delivered.size, "Alpha must be dropped when 5/5 global budget is exhausted")

                // Advance 60s so zeta..kappa expire from global window
                clock.advanceSeconds(60)
                notifier.submit(alpha)
                testScheduler.advanceUntilIdle()
                assertEquals(11, delivered.size, "Alpha must dispatch once global window recovers")
                assertTrue(
                    delivered.last().second.contains("6 repeated alert(s) during cooldown"),
                    "Alpha after recovery must report preserved suppressedCount=6",
                )
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `non-positive cooldownSeconds falls back safely to 300s default with observable warning log`() =
        runTest {
            val clock = MutableClock(baseInstant)
            val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as Logger
            val logAppender = ListAppender<ILoggingEvent>().apply { start() }
            internalLogger.addAppender(logAppender)

            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL)), cooldownSeconds = -15),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            try {
                notifier.submit(event(clock))
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "First event must dispatch")

                clock.advanceSeconds(299)
                notifier.submit(event(clock))
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Fallback 300s cooldown must still suppress at 299s")

                clock.advanceSeconds(1)
                notifier.submit(event(clock))
                testScheduler.advanceUntilIdle()
                assertEquals(2, delivered.size, "Alert at 300s must dispatch after fallback cooldown expires")
                assertTrue(
                    delivered[1].second.contains("1 repeated alert(s) during cooldown"),
                    "Post-cooldown alert must report suppressedCount=1",
                )
                assertTrue(
                    logAppender.list.any { it.level == Level.WARN && it.formattedMessage.contains("falling back to default") },
                    "Non-positive cooldown fallback must emit observable warning log",
                )
            } finally {
                notifier.flushAndClose()
                internalLogger.detachAppender(logAppender)
                logAppender.stop()
            }
        }

    @Test
    fun `OwnerErrorNotifier uses BoundedAlertRateLimiter by default and formats suppressedCount after cooldown`() =
        runTest {
            val clock = MutableClock(baseInstant)
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
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
            assertTrue(
                delivered[1].second.contains("1 repeated alert(s) during cooldown"),
                "Post-cooldown DM must include suppressedCount=1",
            )
        }
}
