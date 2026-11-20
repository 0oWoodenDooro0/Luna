package luna.core

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.test.runTest
import net.logstash.logback.argument.StructuredArguments.kv
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerErrorNotifierTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-15T01:02:03Z"), ZoneOffset.UTC)

    private object ImmediateReadyStartupAlertBuffer : StartupAlertBuffer {
        override val isReady: Boolean = true

        override fun bufferIfNotReady(event: OwnerErrorEvent): Boolean = false

        override fun markReadyAndDrain(): List<OwnerErrorEvent> = emptyList()
    }

    private fun attachTestAppender(
        loggerName: String,
        notifier: OwnerErrorNotifier,
    ): Pair<Logger, OwnerErrorLogbackAppender> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val appender =
            OwnerErrorLogbackAppender(notifier).apply {
                this.context = context
                this.name = "TEST_OWNER_ERROR_NOTIFIER_${System.nanoTime()}"
                start()
            }
        logger.addAppender(appender)
        return logger to appender
    }

    @Test
    fun `delivers structured DM alert with env, context IDs, and stack trace to all configured recipients`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val config =
                ErrorNotifyConfig(
                    recipientUserIds = listOf(Snowflake(111111111111111111uL), Snowflake(222222222222222222uL)),
                    environment = "production",
                    cooldownSeconds = 300,
                )
            val notifier =
                OwnerErrorNotifier(
                    config = config,
                    startupBuffer = ImmediateReadyStartupAlertBuffer,
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )

            val (logger, appender) = attachTestAppender("luna.summarizer.SummaryServiceTestLogger", notifier)
            try {
                MDC.put("traceId", "trace-abc-999")
                logger.error(
                    "Summary generation failed for {} in {}",
                    kv("layer", "SUMMARY"),
                    kv("component", "SummaryService"),
                    kv("operation", "summarize"),
                    kv(
                        "data",
                        mapOf(
                            "guildId" to "333444555",
                            "channelId" to "666777888",
                        ),
                    ),
                    IllegalStateException("Gemini upstream timeout"),
                )
            } finally {
                MDC.remove("traceId")
                logger.detachAppender(appender)
                appender.stop()
                notifier.flushAndClose()
            }

            assertEquals(2, delivered.size)
            assertEquals(setOf(Snowflake(111111111111111111uL), Snowflake(222222222222222222uL)), delivered.map { it.first }.toSet())

            val dm = delivered.first().second
            assertTrue(dm.length <= OwnerErrorNotifier.MAX_DISCORD_MESSAGE_LENGTH)
            assertTrue(dm.contains("ERROR"), "DM must indicate ERROR severity")
            assertTrue(dm.contains("production"), "DM must include ERROR_NOTIFY_ENV")
            assertTrue(dm.contains("SummaryService"), "DM must include component/source")
            assertTrue(dm.contains("SUMMARY"), "DM must include layer")
            assertTrue(dm.contains("summarize"), "DM must include operation")
            assertTrue(dm.contains("trace-abc-999"), "DM must include traceId")
            assertTrue(dm.contains("333444555"), "DM must include guildId")
            assertTrue(dm.contains("666777888"), "DM must include channelId")
            assertTrue(dm.contains("IllegalStateException"), "DM must include exception class")
            assertTrue(dm.contains("Gemini upstream timeout"), "DM must include exception message/stack trace")
        }

    @Test
    fun `disabled config does not queue or invoke dmSender on ERROR logs`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = emptyList()),
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )

            val (logger, appender) = attachTestAppender("luna.core.DisabledNotifierTestLogger", notifier)
            try {
                logger.error("Should not trigger DM when disabled")
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                notifier.flushAndClose()
            }

            assertTrue(delivered.isEmpty())
        }

    @Test
    fun `redacts configured secrets, headers, query keys, omits chat transcripts, and caps at 2000 chars`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val secretConfig =
                mapOf(
                    "DISCORD_TOKEN" to "super-secret-discord-token-xyz",
                    "GEMINI_API_KEY" to "AIzaSySecretGeminiKey987654",
                )
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(123uL)), environment = "staging"),
                    env = luna.config.EnvReader.of(secretConfig),
                    startupBuffer = ImmediateReadyStartupAlertBuffer,
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )

            val hugeStackTrace =
                List(80) { idx -> "    at luna.core.FakeFrame.step$idx(FakeFrame.kt:$idx)" }.joinToString("\n")
            val event =
                OwnerErrorEvent(
                    timestamp = fixedClock.instant(),
                    severity = ErrorAlertSeverity.ERROR,
                    loggerName = "luna.core.ai.GeminiInteractionClient",
                    message =
                        "Call failed for https://api.example.com/v1?key=querySecret123 " +
                            "x-goog-api-key: headerSecret456 Authorization: Bearer bearerSecret789 " +
                            "token=super-secret-discord-token-xyz apiKey=AIzaSySecretGeminiKey987654",
                    throwableClass = "java.lang.RuntimeException",
                    stackTrace = hugeStackTrace,
                    component = "GeminiInteractionClient",
                    traceId = "tr-999",
                    contextFields =
                        mapOf(
                            "traceId" to "tr-999",
                            "transcript" to "USER_CHAT_SHOULD_NOT_APPEAR",
                            "DISCORD_TOKEN" to "super-secret-discord-token-xyz",
                        ),
                )

            notifier.submit(event)
            notifier.flushAndClose()

            assertEquals(1, delivered.size)
            val dm = delivered.single().second
            assertTrue(dm.length <= 2_000, "DM must be <= 2000 chars, got ${dm.length}")
            assertTrue(dm.contains("..."), "Truncated stack trace/message must include '...'")
            assertTrue(!dm.contains("super-secret-discord-token-xyz"), "Must redact DISCORD_TOKEN")
            assertTrue(!dm.contains("AIzaSySecretGeminiKey987654"), "Must redact GEMINI_API_KEY")
            assertTrue(!dm.contains("querySecret123"), "Must redact query key")
            assertTrue(!dm.contains("headerSecret456"), "Must redact x-goog-api-key header")
            assertTrue(!dm.contains("bearerSecret789"), "Must redact Bearer token")
            assertTrue(!dm.contains("USER_CHAT_SHOULD_NOT_APPEAR"), "Must never embed transcript context fields")
            assertTrue(dm.contains("******"))
        }

    @Test
    fun `extracts only safe metadata keys and never embeds chat transcript keys from Logback event`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(123uL))),
                    startupBuffer = ImmediateReadyStartupAlertBuffer,
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )

            val (logger, appender) = attachTestAppender("luna.chatter.ChatterServiceTestLogger", notifier)
            try {
                logger.error(
                    "Chatter pipeline failure",
                    kv("layer", "CHATTER"),
                    kv("component", "ChatterService"),
                    kv(
                        "data",
                        mapOf(
                            "guildId" to "g-100",
                            "channelId" to "c-200",
                            "transcript" to "SECRET_USER_CHAT_TRANSCRIPT",
                            "prompt" to "PRIVATE_PROMPT_CONTENT",
                            "messages" to "FULL_CHAT_HISTORY",
                        ),
                    ),
                )
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                notifier.flushAndClose()
            }

            assertEquals(1, delivered.size)
            val dm = delivered.single().second
            assertTrue(dm.contains("g-100"))
            assertTrue(dm.contains("c-200"))
            assertTrue(!dm.contains("SECRET_USER_CHAT_TRANSCRIPT"))
            assertTrue(!dm.contains("PRIVATE_PROMPT_CONTENT"))
            assertTrue(!dm.contains("FULL_CHAT_HISTORY"))
        }

    @Test
    fun `collaborator seams isolate filtering, rate limiting with suppressedCount, and startup buffering`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val buffered = mutableListOf<OwnerErrorEvent>()
            val bufferedLatch = java.util.concurrent.CountDownLatch(2)
            var ready = false

            val customBuffer =
                object : StartupAlertBuffer {
                    override val isReady: Boolean get() = ready

                    override fun bufferIfNotReady(event: OwnerErrorEvent): Boolean {
                        if (ready) return false
                        buffered.add(event)
                        bufferedLatch.countDown()
                        return true
                    }

                    override fun markReadyAndDrain(): List<OwnerErrorEvent> {
                        ready = true
                        return buffered.toList().also { buffered.clear() }
                    }
                }

            val customFilter = ErrorEventFilter { it.severity == ErrorAlertSeverity.WARN || it.severity == ErrorAlertSeverity.ERROR }
            val customLimiter =
                AlertRateLimiter { event ->
                    if (event.message.contains("DROP_ME")) null else event.copy(suppressedCount = 4)
                }

            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    eventFilter = customFilter,
                    rateLimiter = customLimiter,
                    startupBuffer = customBuffer,
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )

            notifier.submit(
                OwnerErrorEvent(
                    timestamp = fixedClock.instant(),
                    severity = ErrorAlertSeverity.WARN,
                    loggerName = "luna.core.StartupCheck",
                    message = "CONFIG_DRIFT detected",
                ),
            )
            notifier.submit(
                OwnerErrorEvent(
                    timestamp = fixedClock.instant(),
                    severity = ErrorAlertSeverity.ERROR,
                    loggerName = "luna.core.StartupCheck",
                    message = "DROP_ME duplicate",
                ),
            )

            assertTrue(
                bufferedLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
                "Pre-ready events must reach startupBuffer",
            )
            assertTrue(delivered.isEmpty(), "Pre-ready events must stay buffered until onReady()")

            notifier.onReady()
            notifier.flushAndClose()

            assertEquals(1, delivered.size)
            val dm = delivered.single().second
            assertTrue(dm.contains("WARN"))
            assertTrue(dm.contains("CONFIG_DRIFT detected"))
            assertTrue(dm.contains("4 repeated alert(s)"))
        }

    @Test
    fun `default asymmetric filter accepts first-party WARN and rejects third-party WARN while accepting third-party ERROR`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    startupBuffer = ImmediateReadyStartupAlertBuffer,
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            val (firstPartyLogger, firstPartyAppender) = attachTestAppender("luna.core.DefaultFilterTestLogger", notifier)
            val (thirdPartyLogger, thirdPartyAppender) = attachTestAppender("dev.kord.rest.DefaultFilterTestThirdParty", notifier)
            try {
                thirdPartyLogger.warn("Routine third-party warn ignored by default asymmetric filter")
                firstPartyLogger.warn("First-party degraded warning accepted by default asymmetric filter")
                thirdPartyLogger.error("Critical third-party error accepted by default asymmetric filter")
            } finally {
                firstPartyLogger.detachAppender(firstPartyAppender)
                thirdPartyLogger.detachAppender(thirdPartyAppender)
                firstPartyAppender.stop()
                thirdPartyAppender.stop()
                notifier.flushAndClose()
            }

            assertEquals(2, delivered.size)
            assertTrue(delivered.any { it.second.contains("First-party degraded warning accepted") })
            assertTrue(delivered.any { it.second.contains("Critical third-party error accepted") })
        }

    @Test
    fun `queue overflow drops safely and emits observable degraded warning on internal logger`() =
        runTest {
            val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as Logger
            val internalEvents =
                ch.qos.logback.core.read
                    .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
                    .apply { start() }
            internalLogger.addAppender(internalEvents)

            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    startupBuffer = ImmediateReadyStartupAlertBuffer,
                    queueCapacity = 1,
                    clock = fixedClock,
                    dmSender = { userId, content ->
                        gate.await()
                        delivered.add(userId to content)
                    },
                )

            try {
                repeat(10) { idx ->
                    notifier.submit(
                        OwnerErrorEvent(
                            timestamp = fixedClock.instant(),
                            severity = ErrorAlertSeverity.ERROR,
                            loggerName = "luna.core.BurstTest",
                            message = "Burst error #$idx",
                        ),
                    )
                }
                gate.complete(Unit)
                notifier.flushAndClose()
            } finally {
                internalLogger.detachAppender(internalEvents)
                internalEvents.stop()
            }

            assertTrue(delivered.size < 10, "Bounded queue capacity=1 must drop overflow events")
            assertTrue(
                internalEvents.list.any {
                    it.level == ch.qos.logback.classic.Level.WARN && it.formattedMessage.contains("queue full")
                },
                "Overflow must emit observable degraded warning on internal logger",
            )
        }

    @Test
    fun `dmSender failure is fail-open, logs degraded warning, and recursion guard prevents re-entry`() =
        runTest {
            val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as Logger
            val internalEvents =
                ch.qos.logback.core.read
                    .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
                    .apply { start() }
            internalLogger.addAppender(internalEvents)

            val attemptedUsers = CopyOnWriteArrayList<Snowflake>()
            val dispatchLatch = java.util.concurrent.CountDownLatch(2)
            val notifier =
                OwnerErrorNotifier(
                    config =
                        ErrorNotifyConfig(
                            recipientUserIds = listOf(Snowflake(101uL), Snowflake(202uL)),
                        ),
                    startupBuffer = ImmediateReadyStartupAlertBuffer,
                    clock = fixedClock,
                    dmSender = { userId, _ ->
                        try {
                            attemptedUsers.add(userId)
                            // Simulate Kord/Ktor logging an ERROR asynchronously during DM failure
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                (LoggerFactory.getLogger("dev.kord.rest.DefaultRequestHandler") as Logger)
                                    .error("403 Cannot send messages to this user")
                            }
                            if (userId == Snowflake(101uL)) {
                                throw IllegalStateException("DM disabled by recipient")
                            }
                        } finally {
                            dispatchLatch.countDown()
                        }
                    },
                )

            val (kordLogger, kordAppender) = attachTestAppender("dev.kord.rest.DefaultRequestHandler", notifier)
            val (appLogger, appAppender) = attachTestAppender("luna.core.FailOpenTrigger", notifier)
            try {
                appLogger.error("Initial failure triggering owner DM")
                assertTrue(
                    dispatchLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "Initial DM dispatches must complete",
                )
            } finally {
                kordLogger.detachAppender(kordAppender)
                appLogger.detachAppender(appAppender)
                kordAppender.stop()
                appAppender.stop()
                notifier.flushAndClose()
                internalLogger.detachAppender(internalEvents)
                internalEvents.stop()
            }

            assertEquals(
                listOf(Snowflake(101uL), Snowflake(202uL)),
                attemptedUsers.toList(),
                "Failure on user 101 must not prevent attempt on user 202 and must not re-enter pipeline",
            )
            assertTrue(
                internalEvents.list.any {
                    it.level == ch.qos.logback.classic.Level.WARN && it.formattedMessage.contains("Failed to deliver owner error DM")
                },
                "DM failure must emit observable degraded warning on internal logger",
            )
        }

    @Test
    fun `default Kord sender delivers via getUser getDmChannel createMessage and fails open when user is null`() =
        runTest {
            val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as Logger
            val internalEvents =
                ch.qos.logback.core.read
                    .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
                    .apply { start() }
            internalLogger.addAppender(internalEvents)

            val mockKord = io.mockk.mockk<dev.kord.core.Kord>(relaxed = true)
            val mockResources = io.mockk.mockk<dev.kord.core.ClientResources>(relaxed = true)
            val mockUser = io.mockk.mockk<dev.kord.core.entity.User>(relaxed = true)
            val mockDmChannel = io.mockk.mockk<dev.kord.core.entity.channel.DmChannel>(relaxed = true)
            val reachableId = Snowflake(501uL)
            val unreachableId = Snowflake(502uL)

            io.mockk.every { mockKord.resources } returns mockResources
            io.mockk.coEvery { mockKord.getUser(reachableId, any()) } returns mockUser
            io.mockk.coEvery { mockKord.getUser(unreachableId, any()) } returns null
            io.mockk.coEvery { mockUser.getDmChannel() } returns mockDmChannel

            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(unreachableId, reachableId)),
                    startupBuffer = ImmediateReadyStartupAlertBuffer,
                    clock = fixedClock,
                )
            notifier.bindKord(mockKord)

            try {
                notifier.submit(
                    OwnerErrorEvent(
                        timestamp = fixedClock.instant(),
                        severity = ErrorAlertSeverity.ERROR,
                        loggerName = "luna.core.KordDmPathTest",
                        message = "Real Kord path failure",
                    ),
                )
                notifier.flushAndClose()
            } finally {
                internalLogger.detachAppender(internalEvents)
                internalEvents.stop()
            }

            io.mockk.coVerify(exactly = 1) {
                mockDmChannel.createMessage(match<String> { it.contains("Real Kord path failure") })
            }
            assertTrue(
                internalEvents.list.any {
                    it.level == ch.qos.logback.classic.Level.WARN && it.formattedMessage.contains("502")
                },
                "Unreachable user 502 must log degraded warning without blocking user 501",
            )
        }

    @Test
    fun `blank log message with throwable falls back to throwable message or class name and redacts secrets`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL)), cooldownSeconds = 1),
                    env = luna.config.EnvReader.of(mapOf("GEMINI_API_KEY" to "AIzaSyFallbackSecret999")),
                    startupBuffer = ImmediateReadyStartupAlertBuffer,
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )

            val (logger, appender) = attachTestAppender("luna.core.EmptyMsgFallbackLogger", notifier)
            try {
                logger.error("", java.net.SocketException("Connection reset"))
                logger.error("", java.io.EOFException())
                logger.error("", IllegalStateException("Auth failed apiKey=AIzaSyFallbackSecret999"))
                logger.error("")
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                notifier.flushAndClose()
            }

            assertEquals(4, delivered.size)
            val messages = delivered.map { it.second }
            assertTrue(
                messages[0].contains("**Message:** Connection reset") && !messages[0].contains("(empty message)"),
                "Blank log with SocketException must fall back to exception message: ${messages[0]}",
            )
            assertTrue(
                messages[1].contains("**Message:** java.io.EOFException") && !messages[1].contains("(empty message)"),
                "Blank log with null-message EOFException must fall back to exception class: ${messages[1]}",
            )
            assertTrue(
                messages[2].contains("******") && !messages[2].contains("AIzaSyFallbackSecret999"),
                "Fallback throwable message must still be redacted: ${messages[2]}",
            )
            assertTrue(
                messages[3].contains("**Message:** (empty message)"),
                "Blank log without throwable should still display (empty message): ${messages[3]}",
            )
        }
}
