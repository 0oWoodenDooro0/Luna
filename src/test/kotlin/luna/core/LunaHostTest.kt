package luna.core

import dev.kord.core.Kord
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceTrigger
import luna.core.ai.NoOpAiTraceRecorder
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import luna.plugin.PluginLoader
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LunaHostTest {
    @Test
    fun testLunaHostBootstrap() =
        runTest {
            val testDb = mockk<Database>(relaxed = true)
            val mockKord = mockk<Kord>(relaxed = true)
            val events = mutableListOf<String>()

            val testPlugin =
                object : LunaPlugin {
                    override val name: String = "test-plugin"

                    override suspend fun initialize(context: PluginContext) {
                        events.add("init")
                        assertNotNull(context.kord)
                        assertNotNull(context.routing)
                        assertNotNull(context.database)
                    }

                    override suspend fun start() {
                        events.add("start")
                    }

                    override suspend fun shutdown() {
                        events.add("shutdown")
                    }
                }

            val loader = PluginLoader(plugins = listOf(testPlugin))
            val host =
                LunaHost(
                    customConfig =
                        mapOf(
                            "PORT" to 8080,
                            "DISCORD_TOKEN" to "dummy_token",
                            "DISCORD_CLIENT_ID" to "dummy_client_id",
                            "DISCORD_CLIENT_SECRET" to "dummy_client_secret",
                        ),
                    customDatabase = testDb,
                    pluginLoader = loader,
                    kordFactory = { mockKord },
                )

            val context = host.start(loginKord = false)
            assertNotNull(context)
            assertEquals(listOf("init", "start"), events)
            assertNotNull(host.server)
            assertNotNull(host.kord)

            host.shutdown()
            assertEquals(listOf("init", "start", "shutdown"), events)
        }

    @Test
    fun testConfigLoading() {
        val config = LunaHost.loadConfig()
        assertNotNull(config)
        // Verify application.yml flattened keys exist
        assertTrue(config.containsKey("curtly.baseUrl"))
    }

    @Test
    fun `LunaHost wires JsonlAiTraceRecorder into PluginContext and drains on shutdown`(
        @TempDir tempDir: Path,
    ) = runTest {
        val testDb = mockk<Database>(relaxed = true)
        val mockKord = mockk<Kord>(relaxed = true)
        val traceDir = tempDir.resolve("ai-traces")

        val host =
            LunaHost(
                customConfig =
                    mapOf(
                        "PORT" to 18081,
                        "DISCORD_TOKEN" to "dummy_token",
                        "DISCORD_CLIENT_ID" to "dummy_client_id",
                        "DISCORD_CLIENT_SECRET" to "dummy_client_secret",
                        "AI_TRACE_ENABLED" to "true",
                        "AI_TRACE_DIR" to traceDir.toString(),
                    ),
                customDatabase = testDb,
                pluginLoader = PluginLoader(plugins = emptyList()),
                kordFactory = { mockKord },
            )

        val context = host.start(loginKord = false)
        assertTrue(context.aiTraceRecorder.isEnabled())

        val session =
            context.aiTraceRecorder.startSession(
                pipeline = AiTracePipeline.SUMMARY,
                trigger = AiTraceTrigger.REACTION,
                discord = AiTraceDiscordContext(guildId = "g", channelId = "c", messageId = "m", authorId = "a"),
            )
        session.recordAttempt(stage = AiAttemptStage.SUMMARY_GENERATE, parsedOutcome = AiAttemptOutcome.SUCCESS)
        session.complete("Host wired output")
        session.finish()

        host.shutdown()

        val files = Files.list(traceDir).use { it.toList() }
        assertEquals(1, files.size, "Host shutdown must flush trace file to AI_TRACE_DIR")
        val lines = Files.readAllLines(files.first())
        assertEquals(1, lines.size)
        assertTrue(lines.first().contains("Host wired output"))
    }

    @Test
    fun `LunaHost uses NoOpAiTraceRecorder when AI_TRACE_ENABLED is false`() =
        runTest {
            val testDb = mockk<Database>(relaxed = true)
            val mockKord = mockk<Kord>(relaxed = true)

            val host =
                LunaHost(
                    customConfig =
                        mapOf(
                            "PORT" to 18082,
                            "DISCORD_TOKEN" to "dummy_token",
                            "DISCORD_CLIENT_ID" to "dummy_client_id",
                            "DISCORD_CLIENT_SECRET" to "dummy_client_secret",
                            "AI_TRACE_ENABLED" to "false",
                        ),
                    customDatabase = testDb,
                    pluginLoader = PluginLoader(plugins = emptyList()),
                    kordFactory = { mockKord },
                )

            val context = host.start(loginKord = false)
            assertFalse(context.aiTraceRecorder.isEnabled())
            assertEquals(NoOpAiTraceRecorder, context.aiTraceRecorder)
            host.shutdown()
        }
}
