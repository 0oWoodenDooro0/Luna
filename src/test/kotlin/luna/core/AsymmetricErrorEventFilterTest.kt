package luna.core

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AsymmetricErrorEventFilterTest {
    private class MutableClock(
        private var currentInstant: Instant = Instant.parse("2026-11-15T01:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = currentInstant

        fun advanceMillis(millis: Long) {
            currentInstant = currentInstant.plusMillis(millis)
        }
    }

    private fun event(
        loggerName: String,
        severity: ErrorAlertSeverity = ErrorAlertSeverity.ERROR,
        message: String = "Failure occurred",
        throwableClass: String? = null,
        stackTrace: String? = null,
        layer: String? = null,
        component: String? = null,
        operation: String? = null,
        traceId: String? = null,
        guildId: String? = null,
        channelId: String? = null,
        contextFields: Map<String, String> = emptyMap(),
        timestamp: Instant = Instant.parse("2026-11-15T01:00:00Z"),
    ): OwnerErrorEvent =
        OwnerErrorEvent(
            timestamp = timestamp,
            severity = severity,
            loggerName = loggerName,
            message = message,
            throwableClass = throwableClass,
            stackTrace = stackTrace,
            layer = layer,
            component = component,
            operation = operation,
            traceId = traceId,
            guildId = guildId,
            channelId = channelId,
            contextFields = contextFields,
        )

    @Test
    fun `first-party loggers accept both WARN and ERROR while third-party loggers accept ERROR only`() =
        runTest {
            val clock = MutableClock()
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            try {
                val firstPartyLoggers =
                    listOf(
                        "luna.summarizer.SummaryService",
                        "dev.socialpeek.resolver.x.XResolver",
                        "JSON_LOGGER",
                        "luna.core.UncaughtException",
                    )
                for ((idx, logger) in firstPartyLoggers.withIndex()) {
                    val before = delivered.size
                    notifier.submit(event(loggerName = logger, severity = ErrorAlertSeverity.WARN, message = "fp warn $idx"))
                    notifier.submit(event(loggerName = logger, severity = ErrorAlertSeverity.ERROR, message = "fp error $idx"))
                    testScheduler.advanceUntilIdle()
                    assertEquals(before + 2, delivered.size, "Expected first-party logger $logger WARN and ERROR to pass")
                    clock.advanceMillis(61_000L)
                }

                val thirdPartyLoggers =
                    listOf(
                        "dev.kord.rest.DefaultRequestHandler",
                        "io.ktor.server.Application",
                        "io.netty.channel.DefaultChannelPipeline",
                        "org.jetbrains.exposed.v1.core.Exposed",
                        "org.sqlite.JDBC",
                        "lunatic.thirdparty.NoisyClient",
                        "dev.socialpeek_fork.Client",
                        "",
                    )
                for ((idx, logger) in thirdPartyLoggers.withIndex()) {
                    val before = delivered.size
                    notifier.submit(event(loggerName = logger, severity = ErrorAlertSeverity.WARN, message = "tp warn $idx"))
                    testScheduler.advanceUntilIdle()
                    assertEquals(before, delivered.size, "Expected third-party logger '$logger' WARN to be rejected")

                    notifier.submit(event(loggerName = logger, severity = ErrorAlertSeverity.ERROR, message = "tp error $idx"))
                    testScheduler.advanceUntilIdle()
                    assertEquals(before + 1, delivered.size, "Expected third-party logger '$logger' ERROR to pass")
                    clock.advanceMillis(61_000L)
                }
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `paired SLF4J error followed by JsonLogger error within correlation window suppresses redundant JsonLogger event`() =
        runTest {
            val clock = MutableClock()
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            val slf4jEvent =
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    severity = ErrorAlertSeverity.ERROR,
                    message = "Unexpected error in summary pipeline for message 123456",
                    throwableClass = "java.lang.IllegalStateException",
                    stackTrace =
                        "java.lang.IllegalStateException: Gemini upstream timeout\n" +
                            "\tat luna.summarizer.SummaryService.summarize(SummaryService.kt:307)",
                    traceId = "trace-1",
                )
            val jsonEvent =
                event(
                    loggerName = "JSON_LOGGER",
                    severity = ErrorAlertSeverity.ERROR,
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=123456} | Gemini upstream timeout",
                    layer = "SUMMARY",
                    component = "SummaryService",
                    operation = "summarize",
                    traceId = "trace-1",
                    contextFields = mapOf("messageId" to "123456"),
                )

            try {
                notifier.submit(slf4jEvent)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Richer SLF4J error arriving first must pass")

                clock.advanceMillis(150L)
                notifier.submit(jsonEvent)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Paired JsonLogger error within window must be suppressed")
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `paired JsonLogger error followed by SLF4J error within correlation window suppresses second emission`() =
        runTest {
            val clock = MutableClock()
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            val jsonEvent =
                event(
                    loggerName = "JSON_LOGGER",
                    severity = ErrorAlertSeverity.ERROR,
                    message =
                        "Layer: layer=PREVIEW, Component: component=SocialPreviewService, Operation: operation=processUrl, " +
                            "Error: data={url=https://x.com/user/status/1} | Connection reset by peer",
                    layer = "PREVIEW",
                    component = "SocialPreviewService",
                    operation = "processUrl",
                )
            val slf4jEvent =
                event(
                    loggerName = "luna.preview.SocialPreviewService",
                    severity = ErrorAlertSeverity.ERROR,
                    message = "Error processing preview for URL https://x.com/user/status/1",
                    throwableClass = "java.io.IOException",
                    stackTrace =
                        "java.io.IOException: Connection reset by peer\n" +
                            "\tat luna.preview.SocialPreviewService.processUrl(SocialPreviewService.kt:207)",
                )

            try {
                notifier.submit(jsonEvent)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "First emission must pass")

                clock.advanceMillis(50L)
                notifier.submit(slf4jEvent)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Redundant second emission within window must be suppressed")
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `paired warn emissions and mixed warn-error failure emissions correlate within window`() =
        runTest {
            val clock = MutableClock()
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            val slf4jWarn =
                event(
                    loggerName = "luna.core.ai.JsonlAiTraceRecorder",
                    severity = ErrorAlertSeverity.WARN,
                    message = "Trace disk quota exceeded, pruning oldest files",
                )
            val jsonWarn =
                event(
                    loggerName = "JSON_LOGGER",
                    severity = ErrorAlertSeverity.WARN,
                    message = "Trace disk quota exceeded, pruning oldest files",
                    layer = "AI_TRACE",
                    component = "JsonlAiTraceRecorder",
                    operation = "enforceQuota",
                )
            val summaryWarn =
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    severity = ErrorAlertSeverity.WARN,
                    message = "Summarization failed for message 998877: Upstream 503",
                )
            val summaryJsonError =
                event(
                    loggerName = "JSON_LOGGER",
                    severity = ErrorAlertSeverity.ERROR,
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=998877, error=Upstream 503} | Upstream 503",
                    layer = "SUMMARY",
                    component = "SummaryService",
                    operation = "summarize",
                    contextFields = mapOf("messageId" to "998877"),
                )

            try {
                notifier.submit(slf4jWarn)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "First WARN emission must pass")

                clock.advanceMillis(10L)
                notifier.submit(jsonWarn)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Paired WARN from JsonLogger must be suppressed")

                clock.advanceMillis(3_000L)

                notifier.submit(summaryWarn)
                testScheduler.advanceUntilIdle()
                assertEquals(2, delivered.size, "Summary WARN emission must pass")

                clock.advanceMillis(20L)
                notifier.submit(summaryJsonError)
                testScheduler.advanceUntilIdle()
                assertEquals(2, delivered.size, "Paired SummaryResult.Error JsonLogger emission must be suppressed")
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `correlation suppresses at exact window boundary and allows emissions once window expires`() =
        runTest {
            val clock = MutableClock()
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    clock = clock,
                    dispatcher = dispatcher,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()
            testScheduler.advanceUntilIdle()

            val slf4j1 =
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Failed summarizing message 101",
                    stackTrace = "java.lang.RuntimeException: Gateway timeout",
                )
            val json1 =
                event(
                    loggerName = "JSON_LOGGER",
                    component = "SummaryService",
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=101} | Gateway timeout",
                    contextFields = mapOf("messageId" to "101"),
                )
            val slf4j2 =
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Model overloaded while summarizing message 102",
                    stackTrace = "java.lang.RuntimeException: Model overloaded",
                )
            val json2 =
                event(
                    loggerName = "JSON_LOGGER",
                    component = "SummaryService",
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=102} | Model overloaded",
                    contextFields = mapOf("messageId" to "102"),
                )

            try {
                notifier.submit(slf4j1)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "First SLF4J event must pass")

                clock.advanceMillis(2_000L)
                notifier.submit(json1)
                testScheduler.advanceUntilIdle()
                assertEquals(1, delivered.size, "Emission at exact 2000ms boundary must still correlate")

                clock.advanceMillis(1L)

                notifier.submit(slf4j2)
                testScheduler.advanceUntilIdle()
                assertEquals(2, delivered.size, "Second SLF4J event must pass")

                clock.advanceMillis(2_001L)
                notifier.submit(json2)
                testScheduler.advanceUntilIdle()
                assertEquals(3, delivered.size, "Emission after 2001ms window expiry must not be suppressed")
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `distinct concurrent errors across components, correlation keys, and same-channel logs are not suppressed`() =
        runTest {
            val clock = MutableClock()
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

            try {
                // 1. Different components with identical error text must both pass
                notifier.submit(
                    event(
                        loggerName = "luna.summarizer.SummaryService",
                        message = "Request failed",
                        stackTrace = "java.lang.IllegalStateException: Upstream timeout",
                    ),
                )
                notifier.submit(
                    event(
                        loggerName = "JSON_LOGGER",
                        component = "ChatterService",
                        message =
                            "Layer: layer=CHATTER, Component: component=ChatterService, Operation: operation=reply, " +
                                "Error: data={} | Upstream timeout",
                    ),
                )
                testScheduler.advanceUntilIdle()
                assertEquals(2, delivered.size, "Different component must not be suppressed even with identical error detail")

                // 2. Same component with different messageIds and identical error text must not correlate
                clock.advanceMillis(61_000L)
                notifier.submit(
                    event(
                        loggerName = "luna.summarizer.SummaryService",
                        message = "Unexpected error in summary pipeline for message 111",
                        stackTrace = "java.lang.IllegalStateException: Gemini upstream timeout",
                    ),
                )
                notifier.submit(
                    event(
                        loggerName = "JSON_LOGGER",
                        component = "SummaryService",
                        message =
                            "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                                "Error: data={messageId=222} | Gemini upstream timeout",
                        contextFields = mapOf("messageId" to "222"),
                    ),
                )
                testScheduler.advanceUntilIdle()
                assertEquals(4, delivered.size, "Distinct messageId in same component must not be falsely correlated")

                // 3. Interleaved pairs for two concurrent failures in SummaryService correlate 1-to-1 with their own match
                clock.advanceMillis(61_000L)
                notifier.submit(
                    event(
                        loggerName = "luna.summarizer.SummaryService",
                        message = "Unexpected error in summary pipeline for message 301",
                        stackTrace = "java.lang.IllegalStateException: Rate limited",
                    ),
                )
                notifier.submit(
                    event(
                        loggerName = "luna.summarizer.SummaryService",
                        message = "Upstream timeout in summary pipeline for message 302",
                        stackTrace = "java.lang.IllegalStateException: Upstream timeout",
                    ),
                )
                testScheduler.advanceUntilIdle()
                assertEquals(6, delivered.size, "Two distinct SLF4J errors must both pass")

                notifier.submit(
                    event(
                        loggerName = "JSON_LOGGER",
                        component = "SummaryService",
                        message =
                            "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                                "Error: data={messageId=302} | Upstream timeout",
                        contextFields = mapOf("messageId" to "302"),
                    ),
                )
                notifier.submit(
                    event(
                        loggerName = "JSON_LOGGER",
                        component = "SummaryService",
                        message =
                            "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                                "Error: data={messageId=301} | Rate limited",
                        contextFields = mapOf("messageId" to "301"),
                    ),
                )
                testScheduler.advanceUntilIdle()
                assertEquals(6, delivered.size, "Both interleaved JsonLogger events must correlate 1-to-1 and be suppressed")

                // 4. After a 1-to-1 pair completes (and cooldown expires), a subsequent SLF4J emission starts a fresh pair
                clock.advanceMillis(61_000L)
                notifier.submit(
                    event(
                        loggerName = "luna.summarizer.SummaryService",
                        message = "Unexpected error in summary pipeline for message 301",
                        stackTrace = "java.lang.IllegalStateException: Rate limited",
                    ),
                )
                testScheduler.advanceUntilIdle()
                assertEquals(7, delivered.size, "Fresh SLF4J emission after completed pair must pass")
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `Logback SLF4J and JsonLogger integration sends single DM with stack trace and filters third-party noise`() =
        runTest {
            val clock = MutableClock()
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(777uL)), environment = "test"),
                    clock = clock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()

            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val appender =
                OwnerErrorLogbackAppender(notifier).apply {
                    this.context = context
                    this.name = "TEST_ASYMMETRIC_FILTER_${System.nanoTime()}"
                    start()
                }

            val summaryLogger = LoggerFactory.getLogger("luna.summarizer.SummaryService") as Logger
            val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
            val uncaughtLogger = LoggerFactory.getLogger("luna.core.UncaughtException") as Logger
            val kordLogger = LoggerFactory.getLogger("dev.kord.rest.DefaultRequestHandler") as Logger
            val ktorLogger = LoggerFactory.getLogger("io.ktor.server.Application") as Logger
            val nettyLogger = LoggerFactory.getLogger("io.netty.channel.DefaultChannelPipeline") as Logger
            val exposedLogger = LoggerFactory.getLogger("org.jetbrains.exposed.v1.core.Exposed") as Logger

            val attachedLoggers = listOf(summaryLogger, jsonLogger, uncaughtLogger, kordLogger, ktorLogger, nettyLogger, exposedLogger)
            attachedLoggers.forEach { it.addAppender(appender) }

            try {
                // 1. Paired SLF4J + JsonLogger failure -> single DM with stack trace
                MDC.put("traceId", "trace-integration-1")
                summaryLogger.error(
                    "Unexpected error in summary pipeline for message {}",
                    "555666",
                    IllegalStateException("Gemini API 503"),
                )
                JsonLogger.error(
                    layer = "SUMMARY",
                    component = "SummaryService",
                    operation = "summarize",
                    data = mapOf("messageId" to "555666"),
                    errorMessage = "Gemini API 503",
                )
                MDC.remove("traceId")

                // 2. First-party WARN and ERROR from UncaughtException -> both pass with distinct severity headers
                uncaughtLogger.warn("Degraded coroutine dispatcher warning")
                uncaughtLogger.error("Fatal uncaught failure", RuntimeException("Boom"))

                // 3. Third-party TRACE/DEBUG/INFO/WARN rejected; third-party ERROR accepted
                kordLogger.trace("Kord trace")
                kordLogger.debug("Kord debug")
                ktorLogger.info("Ktor info")
                kordLogger.warn("Kord gateway heartbeat missed")
                nettyLogger.warn("Netty channel closed cleanly")
                exposedLogger.warn("Exposed slow query notice")
                exposedLogger.error("SQLite database disk image is malformed")
            } finally {
                MDC.remove("traceId")
                attachedLoggers.forEach { it.detachAppender(appender) }
                appender.stop()
                notifier.flushAndClose()
            }

            assertEquals(4, delivered.size, "Expected exactly 4 DMs: 1 correlated pair + 2 uncaught + 1 third-party ERROR")
            val messages = delivered.map { it.second }
            assertTrue(
                messages.any {
                    it.contains("[ERROR]") &&
                        it.contains("SummaryService") &&
                        it.contains("IllegalStateException") &&
                        it.contains("Gemini API 503")
                },
                "Correlated alert must preserve SLF4J stack trace and ERROR badge",
            )
            assertTrue(
                messages.any { it.contains("[WARN]") && it.contains("Degraded coroutine dispatcher warning") },
                "First-party WARN must preserve WARN badge",
            )
            assertTrue(
                messages.any { it.contains("[ERROR]") && it.contains("Fatal uncaught failure") },
                "Uncaught ERROR must preserve ERROR badge",
            )
            assertTrue(
                messages.any { it.contains("[ERROR]") && it.contains("SQLite database disk image is malformed") },
                "Third-party ERROR must pass",
            )
            assertFalse(
                messages.any { it.contains("heartbeat missed") || it.contains("slow query notice") },
                "Third-party WARN noise must be filtered out",
            )
        }
}
