package luna.plugin

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import io.mockk.mockk
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

        val configMap =
            mapOf(
                "app.name" to "LunaTest",
                "plugin.timeout" to 42,
                "feature.enabled" to true,
            )

        val context =
            DefaultPluginContext(
                kord = mockKord,
                routing = mockRouting,
                database = testDb,
                configMap = configMap,
            )

        assertEquals(mockKord, context.kord)
        assertEquals(mockRouting, context.routing)
        assertEquals(testDb, context.database)

        assertEquals("LunaTest", context.config("app.name", String::class.java))
        assertEquals(42, context.config("plugin.timeout", Int::class.javaObjectType))
        assertEquals(true, context.config("feature.enabled", Boolean::class.javaObjectType))

        // Reified accessor extension
        assertEquals("LunaTest", context.config<String>("app.name"))
        assertEquals(42, context.config<Int>("plugin.timeout"))
        assertEquals(true, context.config<Boolean>("feature.enabled"))

        // Missing key returns null
        assertNull(context.config<String>("nonexistent.key"))
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
