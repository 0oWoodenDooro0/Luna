package luna.plugin

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import io.mockk.mockk
import luna.config.EnvReader
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginContractTest {
    @Test
    fun `plugin context provides capability references and typed config access`() {
        val mockKord = mockk<Kord>(relaxed = true)
        val mockRouting = mockk<Routing>(relaxed = true)
        val testDb = mockk<Database>(relaxed = true)

        val env =
            EnvReader.of(
                mapOf(
                    "APP_NAME" to "LunaTest",
                    "PLUGIN_TIMEOUT" to "42",
                    "FEATURE_ENABLED" to "true",
                ),
            )

        val context =
            DefaultPluginContext(
                kord = mockKord,
                routing = mockRouting,
                database = testDb,
                env = env,
            )

        assertEquals(mockKord, context.kord)
        assertEquals(mockRouting, context.routing)
        assertEquals(testDb, context.database)

        assertEquals("LunaTest", context.env.string("APP_NAME"))
        assertEquals(42, context.env.int("PLUGIN_TIMEOUT", 0))
        assertEquals(true, context.env.boolean("FEATURE_ENABLED", false))

        // Missing key returns null
        assertNull(context.env.string("NONEXISTENT_KEY"))
    }

    @Test
    fun `luna plugin provides default no-op start and shutdown lifecycle hooks`() =
        kotlinx.coroutines.test.runTest {
            var initialized = false
            val plugin =
                object : LunaPlugin {
                    override val name: String = "minimal-plugin"

                    override suspend fun initialize(context: PluginContext) {
                        initialized = true
                    }
                }

            val mockContext = mockk<PluginContext>(relaxed = true)
            plugin.initialize(mockContext)
            assertTrue(initialized)

            // Calling start and shutdown should complete cleanly
            plugin.start()
            plugin.shutdown()
        }
}
