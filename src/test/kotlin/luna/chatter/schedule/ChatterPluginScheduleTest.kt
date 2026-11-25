package luna.chatter.schedule

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.chatter.ChatterPlugin
import luna.config.EnvReader
import luna.core.LunaHost
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterPluginScheduleTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ChatterPlugin initializes and starts DailyScheduleCoordinator when schedule is enabled`() =
        runTest {
            val dbPath = tempDir.resolve("plugin-schedule-enabled.db").toString()
            val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())

            val plugin = ChatterPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = db,
                    env =
                        EnvReader.of(
                            mapOf(
                                "GEMINI_API_KEY" to "test-key",
                                "CHATTER_ENABLED" to "true",
                                "CHATTER_SCHEDULE_ENABLED" to "true",
                            ),
                        ),
                )

            plugin.initialize(context)

            val coordinator = assertNotNull(plugin.scheduleCoordinator, "Expected scheduleCoordinator to be initialized")
            assertTrue(coordinator.isEnabled(), "Coordinator should be enabled when CHATTER_SCHEDULE_ENABLED=true")

            plugin.shutdown()
            assertNull(plugin.scheduleCoordinator)
        }

    @Test
    fun `ChatterPlugin skips DailyScheduleCoordinator when CHATTER_SCHEDULE_ENABLED is false`() =
        runTest {
            val dbPath = tempDir.resolve("plugin-schedule-disabled.db").toString()
            val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())

            val plugin = ChatterPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = db,
                    env =
                        EnvReader.of(
                            mapOf(
                                "GEMINI_API_KEY" to "test-key",
                                "CHATTER_ENABLED" to "true",
                                "CHATTER_SCHEDULE_ENABLED" to "false",
                            ),
                        ),
                )

            plugin.initialize(context)

            assertNull(plugin.scheduleCoordinator)
            plugin.shutdown()
        }

    @Test
    fun `ChatterPlugin degrades fail-open with WARN log when DailyScheduleStore initialization fails`() =
        runTest {
            val pluginLogger = LoggerFactory.getLogger(ChatterPlugin::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            pluginLogger.addAppender(appender)

            try {
                val plugin = ChatterPlugin()
                val failingDb = mockk<Database>(relaxed = true)
                val context =
                    DefaultPluginContext(
                        kord = mockk(relaxed = true),
                        routing = mockk(relaxed = true),
                        database = failingDb,
                        env =
                            EnvReader.of(
                                mapOf(
                                    "GEMINI_API_KEY" to "test-key",
                                    "CHATTER_ENABLED" to "true",
                                    "CHATTER_SCHEDULE_ENABLED" to "true",
                                ),
                            ),
                    )

                plugin.initialize(context)

                assertNull(plugin.scheduleCoordinator)
                assertNotNull(plugin.service)
                assertTrue(
                    appender.list.any { event ->
                        event.level == Level.WARN && event.formattedMessage.contains("DailyScheduleCoordinator")
                    },
                    "Expected observable WARN log when DailyScheduleCoordinator initialization fails",
                )

                plugin.shutdown()
            } finally {
                pluginLogger.detachAppender(appender)
            }
        }

    @Test
    fun `ChatterPlugin registers ScheduleCommand even when CHATTER_SCHEDULE_ENABLED is false and clears on shutdown`() =
        runTest {
            val dbPath = tempDir.resolve("plugin-schedule-cmd.db").toString()
            val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())

            val plugin = ChatterPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = db,
                    env =
                        EnvReader.of(
                            mapOf(
                                "GEMINI_API_KEY" to "test-key",
                                "CHATTER_ENABLED" to "true",
                                "CHATTER_SCHEDULE_ENABLED" to "false",
                            ),
                        ),
                )

            plugin.initialize(context)

            val scheduleCommand = assertNotNull(plugin.scheduleCommand, "Expected scheduleCommand to be initialized")
            assertEquals("schedule", scheduleCommand.name)

            plugin.shutdown()
            assertNull(plugin.scheduleCommand)
        }

    @Test
    fun `ChatterPlugin wires kordSelfIdProvider into ChatterGenerator`() =
        runTest {
            val dbPath = tempDir.resolve("plugin-self-id.db").toString()
            val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())

            val kord =
                mockk<Kord>(relaxed = true).apply {
                    every { selfId } returns Snowflake(999123456UL)
                }
            val plugin = ChatterPlugin()
            val context =
                DefaultPluginContext(
                    kord = kord,
                    routing = mockk(relaxed = true),
                    database = db,
                    env =
                        EnvReader.of(
                            mapOf(
                                "GEMINI_API_KEY" to "test-key",
                                "CHATTER_ENABLED" to "true",
                                "CHATTER_SCHEDULE_ENABLED" to "false",
                            ),
                        ),
                )

            plugin.initialize(context)

            val generator = assertNotNull(plugin.chatterGenerator, "Expected chatterGenerator to be initialized")
            assertEquals("999123456", generator.kordSelfIdProvider())

            plugin.shutdown()
        }
}
