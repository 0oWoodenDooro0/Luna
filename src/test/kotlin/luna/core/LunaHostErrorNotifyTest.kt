package luna.core

import ch.qos.logback.classic.Logger
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.plugin.PluginLoader
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LunaHostErrorNotifyTest {
    @Test
    fun `LunaHost attaches appender and uncaught exception handler on start and restores cleanly on shutdown`() =
        runTest {
            val previousHandler = Thread.UncaughtExceptionHandler { _, _ -> }
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)

            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val deliveredLatch = CountDownLatch(1)
            val testDb = mockk<Database>(relaxed = true)
            val mockKord = mockk<Kord>(relaxed = true)

            val host =
                LunaHost(
                    customConfig =
                        mapOf(
                            "PORT" to 18083,
                            "DISCORD_TOKEN" to "dummy_token_secret_abc",
                            "DISCORD_CLIENT_ID" to "dummy_client_id",
                            "DISCORD_CLIENT_SECRET" to "dummy_client_secret",
                            "ERROR_NOTIFY_DISCORD_USER_IDS" to "999888777666555444",
                            "ERROR_NOTIFY_ENV" to "ci-test",
                        ),
                    customDatabase = testDb,
                    pluginLoader = PluginLoader(plugins = emptyList()),
                    kordFactory = { mockKord },
                    ownerErrorDmSender = { userId, content ->
                        delivered.add(userId to content)
                        deliveredLatch.countDown()
                    },
                )

            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            assertNull(rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME))

            try {
                host.start(loginKord = false)
                host.ownerErrorNotifier.onReady()

                assertNotNull(
                    rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME),
                    "Appender must be attached to ROOT logger on start",
                )
                val installedHandler = Thread.getDefaultUncaughtExceptionHandler()
                assertNotNull(installedHandler)
                assertTrue(installedHandler !== previousHandler)

                // Simulate an uncaught exception on a worker thread
                val failingThread =
                    Thread({
                        throw IllegalStateException("Uncaught crash with dummy_token_secret_abc")
                    }, "test-uncaught-worker")
                failingThread.start()
                failingThread.join(2_000)

                assertTrue(
                    deliveredLatch.await(2, TimeUnit.SECONDS),
                    "Uncaught exception must route through ERROR log into OwnerErrorNotifier",
                )
            } finally {
                host.shutdown()
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }

            assertNull(
                rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME),
                "Appender must be detached from ROOT logger on shutdown",
            )
            assertSame(
                previousHandler,
                Thread.getDefaultUncaughtExceptionHandler(),
                "Previous uncaught exception handler must be restored on shutdown",
            )

            assertEquals(1, delivered.size)
            val (recipient, dm) = delivered.single()
            assertEquals(Snowflake(999888777666555444uL), recipient)
            assertTrue(dm.contains("ci-test"))
            assertTrue(dm.contains("IllegalStateException"))
            assertTrue(!dm.contains("dummy_token_secret_abc"), "Secret token must be redacted in uncaught exception DM")
            assertTrue(dm.contains("******"))
        }

    @Test
    fun `LunaHost with disabled error notification still attaches and detaches cleanly without sending DMs`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val testDb = mockk<Database>(relaxed = true)
            val mockKord = mockk<Kord>(relaxed = true)

            val host =
                LunaHost(
                    customConfig =
                        mapOf(
                            "PORT" to 18084,
                            "DISCORD_TOKEN" to "dummy_token",
                            "DISCORD_CLIENT_ID" to "dummy_client_id",
                            "DISCORD_CLIENT_SECRET" to "dummy_client_secret",
                        ),
                    customDatabase = testDb,
                    pluginLoader = PluginLoader(plugins = emptyList()),
                    kordFactory = { mockKord },
                    ownerErrorDmSender = { userId, content -> delivered.add(userId to content) },
                )

            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            try {
                host.start(loginKord = false)
                (LoggerFactory.getLogger("luna.core.TestHostError") as Logger).error("Should not DM when disabled")
            } finally {
                host.shutdown()
            }

            assertNull(rootLogger.getAppender(OwnerErrorLogbackAppender.APPENDER_NAME))
            assertTrue(delivered.isEmpty())
        }
}
