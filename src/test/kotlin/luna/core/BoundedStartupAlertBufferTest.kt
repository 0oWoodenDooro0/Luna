package luna.core

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedStartupAlertBufferTest {
    private fun makeEvent(
        index: Int,
        severity: ErrorAlertSeverity = ErrorAlertSeverity.ERROR,
        loggerName: String = "luna.core.StartupTest",
        message: String = "Startup event #$index",
    ): OwnerErrorEvent =
        OwnerErrorEvent(
            timestamp = Instant.parse("2026-11-15T01:02:03Z").plusSeconds(index.toLong()),
            severity = severity,
            loggerName = loggerName,
            message = message,
        )

    @Test
    fun `buffers up to 20 pre-ready events in FIFO order, drains on markReadyAndDrain, and bypasses afterward`() {
        val buffer = BoundedStartupAlertBuffer()
        assertFalse(buffer.isReady, "Buffer must start in pre-ready state")

        val events = (1..20).map { idx -> makeEvent(idx, severity = if (idx % 2 == 0) ErrorAlertSeverity.WARN else ErrorAlertSeverity.ERROR) }
        events.forEach { event ->
            assertTrue(buffer.bufferIfNotReady(event), "Pre-ready events within capacity must be buffered")
        }
        assertEquals(20, buffer.bufferedCount)
        assertEquals(0, buffer.droppedCount)

        val drained = buffer.markReadyAndDrain()
        assertTrue(buffer.isReady, "markReadyAndDrain must transition isReady to true")
        assertEquals(events, drained, "Buffered events must drain in strict FIFO order")
        assertEquals(0, buffer.bufferedCount)

        // Idempotent subsequent drain returns empty
        assertTrue(buffer.markReadyAndDrain().isEmpty())

        // Runtime events after ready bypass the buffer
        val runtimeEvent = makeEvent(21)
        assertFalse(buffer.bufferIfNotReady(runtimeEvent), "Post-ready runtime events must bypass startup buffer")
        assertEquals(0, buffer.bufferedCount)
    }

    @Test
    fun `overflow past 20 pre-ready events drops excess safely and emits degraded internal warning under recursion guard`() {
        val internalLogger = org.slf4j.LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val guardStatesDuringLog = mutableListOf<Boolean>()
        val capturedEvents =
            ch.qos.logback.core.read
                .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        val guardCaptureAppender =
            object : ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
                override fun append(eventObject: ch.qos.logback.classic.spi.ILoggingEvent) {
                    guardStatesDuringLog.add(OwnerErrorNotifier.isRecursionGuardActive())
                }
            }.apply { start() }
        capturedEvents.start()
        internalLogger.addAppender(capturedEvents)
        internalLogger.addAppender(guardCaptureAppender)

        val buffer = BoundedStartupAlertBuffer()
        try {
            val allEvents = (1..25).map { idx -> makeEvent(idx) }
            allEvents.forEach { event ->
                assertTrue(
                    buffer.bufferIfNotReady(event),
                    "Even overflow events before ReadyEvent must return true so worker does not dispatch pre-ready",
                )
            }

            assertEquals(20, buffer.bufferedCount)
            assertEquals(5, buffer.droppedCount)

            val drained = buffer.markReadyAndDrain()
            assertEquals(allEvents.take(20), drained, "First 20 events must be retained in FIFO order")
        } finally {
            internalLogger.detachAppender(capturedEvents)
            internalLogger.detachAppender(guardCaptureAppender)
            capturedEvents.stop()
            guardCaptureAppender.stop()
        }

        assertEquals(5, capturedEvents.list.size, "Each dropped overflow event must emit a local warning")
        assertTrue(
            capturedEvents.list.all {
                it.level == ch.qos.logback.classic.Level.WARN && it.formattedMessage.contains("Startup alert buffer full")
            },
        )
        assertTrue(
            guardStatesDuringLog.isNotEmpty() && guardStatesDuringLog.all { it },
            "Overflow degraded warning must execute under OwnerErrorNotifier recursion guard",
        )
    }

    @Test
    fun `negative maxEntries fails fast with IllegalArgumentException`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BoundedStartupAlertBuffer(maxEntries = -1)
        }
    }

    @Test
    fun `OwnerErrorNotifier defaults to BoundedStartupAlertBuffer, drains FIFO on onReady, and bypasses buffer afterward`() =
        kotlinx.coroutines.test.runTest {
            val delivered = java.util.concurrent.CopyOnWriteArrayList<String>()
            val readyDrainLatch = java.util.concurrent.CountDownLatch(3)
            val runtimeLatch = java.util.concurrent.CountDownLatch(1)
            val rateLimiterCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
            val notifier =
                OwnerErrorNotifier(
                    config =
                        ErrorNotifyConfig(
                            recipientUserIds =
                                listOf(
                                    dev.kord.common.entity
                                        .Snowflake(12345uL),
                                ),
                            environment = "staging",
                        ),
                    secretConfig = mapOf("DISCORD_TOKEN" to "secret-startup-token-xyz"),
                    eventFilter = ErrorEventFilter { it.severity == ErrorAlertSeverity.WARN || it.severity == ErrorAlertSeverity.ERROR },
                    rateLimiter =
                        AlertRateLimiter { event ->
                            rateLimiterCalls.add(event.message)
                            event
                        },
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
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.delay(50)
                }
                assertTrue(delivered.isEmpty(), "Pre-ready alerts must not dispatch before ReadyEvent (onReady)")
                assertTrue(rateLimiterCalls.isEmpty(), "Rate limiter must not run until ReadyEvent drain")

                // Signal Kord ReadyEvent
                notifier.onReady()
                assertTrue(
                    readyDrainLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "All 3 buffered startup alerts must drain on onReady()",
                )

                assertEquals(3, delivered.size)
                assertTrue(delivered[0].contains("[CONFIG_DRIFT]"))
                assertTrue(!delivered[0].contains("secret-startup-token-xyz"), "Drained alerts must pass through redactor")
                assertTrue(delivered[0].contains("******"))
                assertTrue(delivered[1].contains("Plugin init error #2"))
                assertTrue(delivered[2].contains("Startup warning #3"))
                assertEquals(
                    listOf(
                        "[CONFIG_DRIFT] Unknown key FOO with secret-startup-token-xyz",
                        "Plugin init error #2",
                        "Startup warning #3",
                    ),
                    rateLimiterCalls.toList(),
                )

                // Subsequent runtime event bypasses startup buffer and dispatches immediately
                notifier.submit(makeEvent(4, severity = ErrorAlertSeverity.ERROR, message = "Runtime error #4"))
                assertTrue(
                    runtimeLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "Post-ready runtime event must bypass startup buffer and dispatch immediately",
                )
            } finally {
                notifier.flushAndClose()
            }

            assertEquals(4, delivered.size)
            assertTrue(delivered[3].contains("Runtime error #4"))
        }

    @Test
    fun `OwnerErrorNotifier flushAndClose before ReadyEvent cleanly discards buffered alerts without Kord DM calls`() =
        kotlinx.coroutines.test.runTest {
            val mockKord = io.mockk.mockk<dev.kord.core.Kord>(relaxed = true)
            val dmAttempts =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val notifier =
                OwnerErrorNotifier(
                    config =
                        ErrorNotifyConfig(
                            recipientUserIds =
                                listOf(
                                    dev.kord.common.entity
                                        .Snowflake(999uL),
                                ),
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
        kotlinx.coroutines.test.runTest {
            val delivered = java.util.concurrent.CopyOnWriteArrayList<String>()
            val deliveredLatch = java.util.concurrent.CountDownLatch(1)
            val eventsFlow =
                kotlinx.coroutines.flow.MutableSharedFlow<dev.kord.core.event.Event>(extraBufferCapacity = 8)
            val mockKord = io.mockk.mockk<dev.kord.core.Kord>(relaxed = true)
            io.mockk.every { mockKord.events } returns eventsFlow
            io.mockk.every { mockKord.coroutineContext } returns
                (kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

            val testDb = io.mockk.mockk<org.jetbrains.exposed.v1.jdbc.Database>(relaxed = true)
            val failingInitLogger = org.slf4j.LoggerFactory.getLogger("luna.core.TestStartupPlugin")
            val testPlugin =
                object : luna.plugin.LunaPlugin {
                    override val name: String = "startup-test-plugin"

                    override suspend fun initialize(context: luna.plugin.PluginContext) {
                        failingInitLogger.error("Plugin startup diagnostic before ReadyEvent")
                    }

                    override suspend fun start() {}

                    override suspend fun shutdown() {}
                }

            val rootLogger =
                org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger

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
                    pluginLoader = luna.plugin.PluginLoader(plugins = listOf(testPlugin)),
                    kordFactory = { mockKord },
                    ownerErrorDmSender = { _, content ->
                        delivered.add(content)
                        deliveredLatch.countDown()
                    },
                )

            // Init must not leave a dangling appender attached before start()
            kotlin.test.assertNull(rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME))

            try {
                host.start(loginKord = false)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    kotlinx.coroutines.delay(50)
                }
                assertTrue(delivered.isEmpty(), "Startup plugin error must stay buffered before Kord ReadyEvent")

                // Emit real Kord ReadyEvent to trigger registerReadyListener -> ownerErrorNotifier.onReady()
                eventsFlow.emit(io.mockk.mockk<dev.kord.core.event.gateway.ReadyEvent>(relaxed = true))
                assertTrue(
                    deliveredLatch.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "Kord ReadyEvent must drain buffered startup alert",
                )
            } finally {
                host.shutdown()
            }

            kotlin.test.assertNull(rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME))
            assertEquals(1, delivered.size)
            assertTrue(delivered.single().contains("Plugin startup diagnostic before ReadyEvent"))
        }

    @Test
    fun `LunaHost shutdown before ReadyEvent discards buffered plugin startup error without DM calls`() =
        kotlinx.coroutines.test.runTest {
            val dmAttempts =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val mockKord = io.mockk.mockk<dev.kord.core.Kord>(relaxed = true)
            val testDb = io.mockk.mockk<org.jetbrains.exposed.v1.jdbc.Database>(relaxed = true)
            val brokenPlugin =
                object : luna.plugin.LunaPlugin {
                    override val name: String = "broken-startup-plugin"

                    override suspend fun initialize(context: luna.plugin.PluginContext): Unit =
                        throw IllegalStateException("Broken plugin init before ReadyEvent")

                    override suspend fun start() {}

                    override suspend fun shutdown() {}
                }

            val rootLogger =
                org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger

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
                    pluginLoader = luna.plugin.PluginLoader(plugins = listOf(brokenPlugin)),
                    kordFactory = { mockKord },
                    ownerErrorDmSender = { _, _ -> dmAttempts.incrementAndGet() },
                )

            try {
                org.junit.jupiter.api.assertThrows<IllegalStateException> {
                    host.start(loginKord = false)
                }
            } finally {
                host.shutdown()
            }

            assertEquals(0, dmAttempts.get(), "Pre-ready shutdown must discard buffered plugin errors without DM calls")
            kotlin.test.assertNull(rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME))
        }
}
