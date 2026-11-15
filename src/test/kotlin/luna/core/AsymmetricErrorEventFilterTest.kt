package luna.core

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import dev.kord.common.entity.Snowflake
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
    fun `first-party loggers accept both WARN and ERROR while third-party loggers accept ERROR only`() {
        val clock = MutableClock()
        val filter = AsymmetricErrorEventFilter(clock = clock)

        val firstPartyLoggers =
            listOf(
                "luna.summarizer.SummaryService",
                "dev.socialpeek.resolver.x.XResolver",
                "JSON_LOGGER",
                "luna.core.UncaughtException",
            )
        for ((idx, logger) in firstPartyLoggers.withIndex()) {
            assertTrue(
                filter.shouldNotify(event(loggerName = logger, severity = ErrorAlertSeverity.WARN, message = "fp warn $idx")),
                "Expected first-party logger $logger WARN to pass",
            )
            assertTrue(
                filter.shouldNotify(event(loggerName = logger, severity = ErrorAlertSeverity.ERROR, message = "fp error $idx")),
                "Expected first-party logger $logger ERROR to pass",
            )
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
            assertFalse(
                filter.shouldNotify(event(loggerName = logger, severity = ErrorAlertSeverity.WARN, message = "tp warn $idx")),
                "Expected third-party logger '$logger' WARN to be rejected",
            )
            assertTrue(
                filter.shouldNotify(event(loggerName = logger, severity = ErrorAlertSeverity.ERROR, message = "tp error $idx")),
                "Expected third-party logger '$logger' ERROR to pass",
            )
        }
    }

    @Test
    fun `paired SLF4J error followed by JsonLogger error within correlation window suppresses redundant JsonLogger event`() {
        val clock = MutableClock()
        val filter = AsymmetricErrorEventFilter(clock = clock, correlationWindowMs = 2_000L)

        val slf4jEvent =
            event(
                loggerName = "luna.summarizer.SummaryService",
                severity = ErrorAlertSeverity.ERROR,
                message = "Unexpected error in summary pipeline for message 123456",
                throwableClass = "java.lang.IllegalStateException",
                stackTrace = "java.lang.IllegalStateException: Gemini upstream timeout\n\tat luna.summarizer.SummaryService.summarize(SummaryService.kt:307)",
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

        assertTrue(filter.shouldNotify(slf4jEvent), "Richer SLF4J error arriving first must pass")
        clock.advanceMillis(150L)
        assertFalse(filter.shouldNotify(jsonEvent), "Paired JsonLogger error within window must be suppressed")
    }

    @Test
    fun `paired JsonLogger error followed by SLF4J error within correlation window suppresses second emission`() {
        val clock = MutableClock()
        val filter = AsymmetricErrorEventFilter(clock = clock, correlationWindowMs = 2_000L)

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
                stackTrace = "java.io.IOException: Connection reset by peer\n\tat luna.preview.SocialPreviewService.processUrl(SocialPreviewService.kt:207)",
            )

        assertTrue(filter.shouldNotify(jsonEvent), "First emission must pass")
        clock.advanceMillis(50L)
        assertFalse(filter.shouldNotify(slf4jEvent), "Redundant second emission within window must be suppressed")
    }

    @Test
    fun `paired warn emissions and mixed warn-error failure emissions correlate within window`() {
        val clock = MutableClock()
        val filter = AsymmetricErrorEventFilter(clock = clock, correlationWindowMs = 2_000L)

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
        assertTrue(filter.shouldNotify(slf4jWarn))
        clock.advanceMillis(10L)
        assertFalse(filter.shouldNotify(jsonWarn), "Paired WARN from JsonLogger must be suppressed")

        clock.advanceMillis(3_000L)

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
        assertTrue(filter.shouldNotify(summaryWarn))
        clock.advanceMillis(20L)
        assertFalse(filter.shouldNotify(summaryJsonError), "Paired SummaryResult.Error JsonLogger emission must be suppressed")
    }

    @Test
    fun `correlation suppresses at exact window boundary and allows emissions once window expires`() {
        val clock = MutableClock()
        val filter = AsymmetricErrorEventFilter(clock = clock, correlationWindowMs = 2_000L)

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

        assertTrue(filter.shouldNotify(slf4j1))
        clock.advanceMillis(2_000L)
        assertFalse(filter.shouldNotify(json1), "Emission at exact 2000ms boundary must still correlate")

        clock.advanceMillis(1L)

        val slf4j2 =
            event(
                loggerName = "luna.summarizer.SummaryService",
                message = "Failed summarizing message 102",
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

        assertTrue(filter.shouldNotify(slf4j2))
        clock.advanceMillis(2_001L)
        assertTrue(filter.shouldNotify(json2), "Emission after 2001ms window expiry must not be suppressed")
    }

    @Test
    fun `distinct concurrent errors across components, correlation keys, and same-channel logs are not suppressed`() {
        val clock = MutableClock()
        val filter = AsymmetricErrorEventFilter(clock = clock, correlationWindowMs = 2_000L)

        // 1. Different components with identical error text must both pass
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Request failed",
                    stackTrace = "java.lang.IllegalStateException: Upstream timeout",
                ),
            ),
        )
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "JSON_LOGGER",
                    component = "ChatterService",
                    message =
                        "Layer: layer=CHATTER, Component: component=ChatterService, Operation: operation=reply, " +
                            "Error: data={} | Upstream timeout",
                ),
            ),
            "Different component must not be suppressed even with identical error detail",
        )

        // 2. Same component with different messageIds and identical error text must not correlate
        clock.advanceMillis(3_000L)
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Unexpected error in summary pipeline for message 111",
                    stackTrace = "java.lang.IllegalStateException: Gemini upstream timeout",
                ),
            ),
        )
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "JSON_LOGGER",
                    component = "SummaryService",
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=222} | Gemini upstream timeout",
                    contextFields = mapOf("messageId" to "222"),
                ),
            ),
            "Distinct messageId in same component must not be falsely correlated",
        )

        // 3. Interleaved pairs for two concurrent failures in SummaryService correlate 1-to-1 with their own match
        clock.advanceMillis(3_000L)
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Unexpected error in summary pipeline for message 301",
                    stackTrace = "java.lang.IllegalStateException: Rate limited",
                ),
            ),
        )
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Unexpected error in summary pipeline for message 302",
                    stackTrace = "java.lang.IllegalStateException: Rate limited",
                ),
            ),
            "Two SLF4J errors must both pass AsymmetricErrorEventFilter (rate limiter handles dedup)",
        )
        assertFalse(
            filter.shouldNotify(
                event(
                    loggerName = "JSON_LOGGER",
                    component = "SummaryService",
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=302} | Rate limited",
                    contextFields = mapOf("messageId" to "302"),
                ),
            ),
            "JsonLogger 302 must correlate with SLF4J 302",
        )
        assertFalse(
            filter.shouldNotify(
                event(
                    loggerName = "JSON_LOGGER",
                    component = "SummaryService",
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=301} | Rate limited",
                    contextFields = mapOf("messageId" to "301"),
                ),
            ),
            "JsonLogger 301 must still correlate with SLF4J 301 after 302 was paired",
        )

        // 4. After a 1-to-1 pair completes, a subsequent SLF4J emission starts a fresh pair
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Unexpected error in summary pipeline for message 301",
                    stackTrace = "java.lang.IllegalStateException: Rate limited",
                ),
            ),
            "Fresh SLF4J emission after completed pair must pass",
        )
    }

    @Test
    fun `bounded correlation cache evicts eldest entries when capacity is exceeded`() {
        val clock = MutableClock()
        val filter = AsymmetricErrorEventFilter(clock = clock, correlationWindowMs = 5_000L, maxTrackedEntries = 2)

        // Emit 3 distinct SLF4J errors; capacity=2 evicts message 1
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Failed message 1",
                    stackTrace = "java.lang.RuntimeException: Failure one",
                ),
            ),
        )
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Failed message 2",
                    stackTrace = "java.lang.RuntimeException: Failure two",
                ),
            ),
        )
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "luna.summarizer.SummaryService",
                    message = "Failed message 3",
                    stackTrace = "java.lang.RuntimeException: Failure three",
                ),
            ),
        )

        // Evicted entry 1 fails open (not suppressed); recent entry 3 is still tracked and suppressed
        assertTrue(
            filter.shouldNotify(
                event(
                    loggerName = "JSON_LOGGER",
                    component = "SummaryService",
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=1} | Failure one",
                    contextFields = mapOf("messageId" to "1"),
                ),
            ),
            "Evicted entry must fail open and pass",
        )
        assertFalse(
            filter.shouldNotify(
                event(
                    loggerName = "JSON_LOGGER",
                    component = "SummaryService",
                    message =
                        "Layer: layer=SUMMARY, Component: component=SummaryService, Operation: operation=summarize, " +
                            "Error: data={messageId=3} | Failure three",
                    contextFields = mapOf("messageId" to "3"),
                ),
            ),
            "Most recent entry within capacity must remain tracked and correlate",
        )
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
            assertFalse(messages.any { it.contains("heartbeat missed") || it.contains("slow query notice") })
        }
}
