package luna.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import luna.config.EnvReader
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import luna.plugin.PluginLoader
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedStartupAlertBufferTest {
    private val distinctTags =
        listOf(
            "alpha",
            "bravo",
            "charlie",
            "delta",
            "echo",
            "foxtrot",
            "golf",
            "hotel",
            "india",
            "juliet",
            "kilo",
            "lima",
            "mike",
            "november",
            "oscar",
            "papa",
            "quebec",
            "romeo",
            "sierra",
            "tango",
            "uniform",
            "victor",
            "whiskey",
            "xray",
            "yankee",
            "zulu",
        )

    private fun makeEvent(
        index: Int,
        severity: ErrorAlertSeverity = ErrorAlertSeverity.ERROR,
        loggerName: String = "luna.core.StartupTest",
        message: String = "Startup event ${distinctTags[index]}",
    ): OwnerErrorEvent =
        OwnerErrorEvent(
            timestamp = Instant.parse("2026-11-15T01:02:03Z").plusSeconds(index.toLong()),
            severity = severity,
            loggerName = loggerName,
            message = message,
        )

    @Test
    fun `buffers pre-ready events in FIFO order, drains on onReady, and bypasses buffer afterward`() =
        runTest {
            val delivered = CopyOnWriteArrayList<String>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    dispatcher = dispatcher,
                    dmSender = { _, content -> delivered.add(content) },
                )

            // 4 ERROR + 4 WARN = 8 events (within 5/min per-severity cap)
            val preReadyEvents =
                (1..8).map { idx ->
                    makeEvent(
                        idx,
                        severity = if (idx % 2 == 0) ErrorAlertSeverity.WARN else ErrorAlertSeverity.ERROR,
                    )
                }
            try {
                preReadyEvents.forEach { notifier.submit(it) }
                testScheduler.advanceUntilIdle()
                assertTrue(delivered.isEmpty(), "Pre-ready events must stay buffered until onReady()")

                notifier.onReady()
                testScheduler.advanceUntilIdle()

                assertEquals(8, delivered.size, "All 8 buffered events must drain on onReady()")
                preReadyEvents.forEachIndexed { idx, event ->
                    assertTrue(
                        delivered[idx].contains(event.message),
                        "Buffered event #${idx + 1} must drain in strict FIFO order",
                    )
                }

                // Idempotent subsequent onReady() does not re-drain
                notifier.onReady()
                testScheduler.advanceUntilIdle()
                assertEquals(8, delivered.size, "Subsequent onReady() must be idempotent")

                // Post-ready runtime event bypasses buffer and dispatches immediately
                notifier.submit(makeEvent(9, severity = ErrorAlertSeverity.ERROR, message = "Post-ready runtime event #9"))
                testScheduler.advanceUntilIdle()
                assertEquals(9, delivered.size, "Post-ready runtime event must dispatch immediately")
                assertTrue(
                    delivered.last().contains("Post-ready runtime event #9"),
                    "Delivered DM must match the post-ready runtime event",
                )
            } finally {
                notifier.flushAndClose()
            }
        }

    @Test
    fun `overflow past 20 pre-ready events drops excess safely and emits degraded internal warning under recursion guard`() =
        runTest {
            val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as Logger
            val guardStatesDuringLog = mutableListOf<Boolean>()
            val capturedEvents = ListAppender<ILoggingEvent>().apply { start() }
            val guardCaptureAppender =
                object : AppenderBase<ILoggingEvent>() {
                    override fun append(eventObject: ILoggingEvent) {
                        guardStatesDuringLog.add(OwnerErrorNotifier.isRecursionGuardActive())
                    }
                }.apply { start() }
            internalLogger.addAppender(capturedEvents)
            internalLogger.addAppender(guardCaptureAppender)

            val delivered = CopyOnWriteArrayList<String>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    dispatcher = dispatcher,
                    dmSender = { _, content -> delivered.add(content) },
                )

            try {
                // Events 1..5 ERROR, 6..10 WARN, 11..20 ERROR, 21..25 ERROR (last 5 overflow the 20-event cap)
                val allEvents =
                    (1..25).map { idx ->
                        val severity = if (idx in 6..10) ErrorAlertSeverity.WARN else ErrorAlertSeverity.ERROR
                        makeEvent(idx, severity = severity, message = "Startup overflow event ${distinctTags[idx]}")
                    }
                allEvents.forEach { notifier.submit(it) }
                testScheduler.advanceUntilIdle()
                assertTrue(delivered.isEmpty(), "All pre-ready events including overflow must not dispatch before onReady()")

                assertEquals(5, capturedEvents.list.size, "Each dropped overflow event must emit a local warning")
                assertTrue(
                    capturedEvents.list.all {
                        it.level == Level.WARN && it.formattedMessage.contains("Startup alert buffer full")
                    },
                    "Overflow warnings must log 'Startup alert buffer full' at WARN level",
                )
                assertTrue(
                    guardStatesDuringLog.isNotEmpty() && guardStatesDuringLog.all { it },
                    "Overflow degraded warning must execute under OwnerErrorNotifier recursion guard",
                )

                notifier.onReady()
                testScheduler.advanceUntilIdle()
            } finally {
                internalLogger.detachAppender(capturedEvents)
                internalLogger.detachAppender(guardCaptureAppender)
                capturedEvents.stop()
                guardCaptureAppender.stop()
                notifier.flushAndClose()
            }

            // 5 ERRORs (#1..#5) + 5 WARNs (#6..#10) dispatch within 5/min per-severity caps; overflow #21..#25 never appear
            assertEquals(10, delivered.size, "First 5 ERRORs and 5 WARNs from retained 20 must dispatch on onReady()")
            assertFalse(
                delivered.any { msg -> (21..25).any { idx -> msg.contains("Startup overflow event ${distinctTags[idx]}") } },
                "Dropped overflow events #21..#25 must never be delivered",
            )
        }

    @Test
    fun `OwnerErrorNotifier defaults to BoundedStartupAlertBuffer, drains FIFO on onReady, and bypasses buffer afterward`() =
        runTest {
            val delivered = CopyOnWriteArrayList<String>()
            val readyDrainLatch = CountDownLatch(3)
            val runtimeLatch = CountDownLatch(1)
            val notifier =
                OwnerErrorNotifier(
                    config =
                        ErrorNotifyConfig(
                            recipientUserIds = listOf(Snowflake(12345uL)),
                            environment = "staging",
                        ),
                    env = EnvReader.of(mapOf("DISCORD_TOKEN" to "secret-startup-token-xyz")),
                    dmSender = { _, content ->
                        delivered.add(content)
                        if (content.contains("Runtime error #4")) {
                            runtimeLatch.countDown()
                        } else {
                            readyDrainLatch.countDown()
                        }
                    },
                )

            try {
                notifier.submit(
                    makeEvent(
                        1,
                        severity = ErrorAlertSeverity.WARN,
                        message = "[CONFIG_DRIFT] Unknown key FOO with secret-startup-token-xyz",
                    ),
                )
                notifier.submit(makeEvent(2, severity = ErrorAlertSeverity.ERROR, message = "Plugin init error #2"))
                notifier.submit(makeEvent(3, severity = ErrorAlertSeverity.WARN, message = "Startup warning #3"))

                // Give background worker thread time to drain channel into startup buffer
                withContext(Dispatchers.Default) {
                    delay(50)
                }
                assertTrue(delivered.isEmpty(), "Pre-ready alerts must not dispatch before ReadyEvent (onReady)")

                // Signal Kord ReadyEvent
                notifier.onReady()
                assertTrue(
                    readyDrainLatch.await(2, TimeUnit.SECONDS),
                    "All 3 buffered startup alerts must drain on onReady()",
                )

                assertEquals(3, delivered.size)
                assertTrue(delivered[0].contains("[CONFIG_DRIFT]"), "First drained alert must be CONFIG_DRIFT")
                assertTrue(!delivered[0].contains("secret-startup-token-xyz"), "Drained alerts must pass through redactor")
                assertTrue(delivered[0].contains("******"), "Redacted secret must be replaced with ******")
                assertTrue(delivered[1].contains("Plugin init error #2"), "Second drained alert must be Plugin init error #2")
                assertTrue(delivered[2].contains("Startup warning #3"), "Third drained alert must be Startup warning #3")

                // Subsequent runtime event bypasses startup buffer and dispatches immediately
                notifier.submit(makeEvent(4, severity = ErrorAlertSeverity.ERROR, message = "Runtime error #4"))
                assertTrue(
                    runtimeLatch.await(2, TimeUnit.SECONDS),
                    "Post-ready runtime event must bypass startup buffer and dispatch immediately",
                )
            } finally {
                notifier.flushAndClose()
            }

            assertEquals(4, delivered.size)
            assertTrue(delivered[3].contains("Runtime error #4"), "Fourth delivered DM must be Runtime error #4")
        }

    @Test
    fun `OwnerErrorNotifier flushAndClose before ReadyEvent cleanly discards buffered alerts without Kord DM calls`() =
        runTest {
            val mockKord = io.mockk.mockk<dev.kord.core.Kord>(relaxed = true)
            val dmAttempts = AtomicInteger(0)
            val notifier =
                OwnerErrorNotifier(
                    config =
                        ErrorNotifyConfig(
                            recipientUserIds = listOf(Snowflake(999uL)),
                        ),
                    dmSender = { _, _ -> dmAttempts.incrementAndGet() },
                )
            notifier.bindKord(mockKord)

            notifier.submit(makeEvent(1, message = "Pre-ready startup failure #1"))
            notifier.submit(makeEvent(2, message = "Pre-ready startup failure #2"))

            // Shutdown before onReady() ever runs
            notifier.flushAndClose(timeoutMs = 1_000L)

            assertEquals(0, dmAttempts.get(), "Pre-ready shutdown must discard alerts without attempting DM dispatch")
            io.mockk.coVerify(exactly = 0) { mockKord.getUser(any(), any()) }
        }

    @Test
    fun `LunaHost buffers pre-ReadyEvent plugin errors and drains on Kord ReadyEvent without dangling init appender`() =
        runTest {
            val delivered = CopyOnWriteArrayList<String>()
            val deliveredLatch = CountDownLatch(2)
            val eventsFlow =
                MutableSharedFlow<dev.kord.core.event.Event>(extraBufferCapacity = 8)
            val mockKord = io.mockk.mockk<dev.kord.core.Kord>(relaxed = true)
            io.mockk.every { mockKord.events } returns eventsFlow
            io.mockk.every { mockKord.coroutineContext } returns
                (Dispatchers.Default + SupervisorJob())

            val testDb = io.mockk.mockk<Database>(relaxed = true)
            val failingInitLogger = LoggerFactory.getLogger("luna.core.TestStartupPlugin")
            val testPlugin =
                object : LunaPlugin {
                    override val name: String = "startup-test-plugin"

                    override suspend fun initialize(context: PluginContext) {
                        failingInitLogger.error("Plugin startup diagnostic before ReadyEvent")
                    }

                    override suspend fun start() {}

                    override suspend fun shutdown() {}
                }

            val rootLogger =
                LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

            val host =
                LunaHost(
                    customConfig =
                        mapOf(
                            "PORT" to 18085,
                            "DISCORD_TOKEN" to "dummy_token",
                            "DISCORD_CLIENT_ID" to "dummy_client_id",
                            "DISCORD_CLIENT_SECRET" to "dummy_client_secret",
                            "ERROR_NOTIFY_DISCORD_USER_IDS" to "999888777666555444",
                            "UNKNOWN_DRIFT_STARTUP_KEY" to "drift-value",
                        ),
                    customDatabase = testDb,
                    pluginLoader = PluginLoader(plugins = listOf(testPlugin)),
                    kordFactory = { mockKord },
                    ownerErrorDmSender = { _, content ->
                        delivered.add(content)
                        deliveredLatch.countDown()
                    },
                )

            // Init must not leave a dangling appender attached before start()
            assertNull(rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME))

            try {
                host.start(loginKord = false)

                withContext(Dispatchers.Default) {
                    delay(50)
                }
                assertTrue(delivered.isEmpty(), "Startup plugin error must stay buffered before Kord ReadyEvent")

                // Emit real Kord ReadyEvent to trigger registerReadyListener -> ownerErrorNotifier.onReady()
                eventsFlow.emit(io.mockk.mockk<dev.kord.core.event.gateway.ReadyEvent>(relaxed = true))
                assertTrue(
                    deliveredLatch.await(2, TimeUnit.SECONDS),
                    "Kord ReadyEvent must drain buffered startup alert",
                )
            } finally {
                host.shutdown()
            }

            assertNull(rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME))
            assertEquals(2, delivered.size)
            assertTrue(
                delivered[0].contains("[WARN]") && delivered[0].contains("CONFIG_DRIFT"),
                "First delivered DM must be CONFIG_DRIFT WARN",
            )
            assertTrue(
                delivered[1].contains("[ERROR]") && delivered[1].contains("Plugin startup diagnostic before ReadyEvent"),
                "Second delivered DM must be Plugin startup diagnostic ERROR",
            )
        }

    @Test
    fun `LunaHost shutdown before ReadyEvent discards buffered plugin startup error without DM calls`() =
        runTest {
            val dmAttempts = AtomicInteger(0)
            val mockKord = io.mockk.mockk<dev.kord.core.Kord>(relaxed = true)
            val testDb = io.mockk.mockk<Database>(relaxed = true)
            val brokenPlugin =
                object : LunaPlugin {
                    override val name: String = "broken-startup-plugin"

                    override suspend fun initialize(context: PluginContext): Unit = throw IllegalStateException("Broken plugin init before ReadyEvent")

                    override suspend fun start() {}

                    override suspend fun shutdown() {}
                }

            val rootLogger =
                LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

            val host =
                LunaHost(
                    customConfig =
                        mapOf(
                            "PORT" to 18086,
                            "DISCORD_TOKEN" to "dummy_token",
                            "DISCORD_CLIENT_ID" to "dummy_client_id",
                            "DISCORD_CLIENT_SECRET" to "dummy_client_secret",
                            "ERROR_NOTIFY_DISCORD_USER_IDS" to "999888777666555444",
                        ),
                    customDatabase = testDb,
                    pluginLoader = PluginLoader(plugins = listOf(brokenPlugin)),
                    kordFactory = { mockKord },
                    ownerErrorDmSender = { _, _ -> dmAttempts.incrementAndGet() },
                )

            try {
                assertThrows<IllegalStateException> {
                    host.start(loginKord = false)
                }
            } finally {
                host.shutdown()
            }

            assertEquals(0, dmAttempts.get(), "Pre-ready shutdown must discard buffered plugin errors without DM calls")
            assertNull(rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME))
        }
}
