package luna.plugin

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLoaderTest {
    @Test
    fun `plugin loader initializes discovered plugins and passes context`() =
        runTest {
            val events = mutableListOf<String>()
            val pluginA =
                object : LunaPlugin {
                    override val name: String = "plugin-a"

                    override suspend fun initialize(context: PluginContext) {
                        events.add("init-a:${context.config<String>("env")}")
                    }

                    override suspend fun start() {
                        events.add("start-a")
                    }

                    override suspend fun shutdown() {
                        events.add("shutdown-a")
                    }
                }

            val pluginB =
                object : LunaPlugin {
                    override val name: String = "plugin-b"

                    override suspend fun initialize(context: PluginContext) {
                        events.add("init-b")
                    }

                    override suspend fun start() {
                        events.add("start-b")
                    }

                    override suspend fun shutdown() {
                        events.add("shutdown-b")
                    }
                }

            val loader = PluginLoader(plugins = listOf(pluginA, pluginB))
            assertEquals(2, loader.plugins.size)

            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = mapOf("env" to "test"),
                )

            loader.initializePlugins(context)
            assertEquals(listOf("init-a:test", "init-b"), events)

            loader.startPlugins()
            assertEquals(listOf("init-a:test", "init-b", "start-a", "start-b"), events)

            loader.shutdownPlugins()
            assertEquals(
                listOf("init-a:test", "init-b", "start-a", "start-b", "shutdown-b", "shutdown-a"),
                events,
            )
        }

    @Test
    fun `plugin loader isolates shutdown exceptions and continues shutting down other plugins`() =
        runTest {
            val events = mutableListOf<String>()

            val failingPlugin =
                object : LunaPlugin {
                    override val name: String = "failing-plugin"

                    override suspend fun initialize(context: PluginContext) {}

                    override suspend fun shutdown() {
                        events.add("shutdown-failing")
                        throw RuntimeException("Shutdown explosion")
                    }
                }

            val healthyPlugin =
                object : LunaPlugin {
                    override val name: String = "healthy-plugin"

                    override suspend fun initialize(context: PluginContext) {}

                    override suspend fun shutdown() {
                        events.add("shutdown-healthy")
                    }
                }

            // Failing plugin is registered after healthy plugin, so in reverse shutdown order, failing runs first
            val loader = PluginLoader(plugins = listOf(healthyPlugin, failingPlugin))
            loader.shutdownPlugins()

            assertEquals(listOf("shutdown-failing", "shutdown-healthy"), events)
        }

    @Test
    fun `plugin loader propagates initialization failure when a plugin fails to initialize`() =
        runTest {
            val brokenPlugin =
                object : LunaPlugin {
                    override val name: String = "broken-plugin"

                    override suspend fun initialize(context: PluginContext): Unit = throw IllegalStateException("Missing required configuration")
                }

            val loader = PluginLoader(plugins = listOf(brokenPlugin))
            val context = mockk<PluginContext>(relaxed = true)

            val exception =
                assertFailsWith<IllegalStateException> {
                    loader.initializePlugins(context)
                }
            assertTrue(exception.message!!.contains("broken-plugin"))
        }

    @Test
    fun `plugin loader skips disabled plugins when configured via plugins dot name dot enabled false`() =
        runTest {
            var initialized = false
            val plugin =
                object : LunaPlugin {
                    override val name: String = "optional-plugin"

                    override suspend fun initialize(context: PluginContext) {
                        initialized = true
                    }
                }

            val loader = PluginLoader(plugins = listOf(plugin))
            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = mapOf("plugins.optional-plugin.enabled" to false),
                )

            loader.initializePlugins(context)
            assertFalse(initialized, "Disabled plugin should not be initialized")
            assertEquals(0, loader.plugins.size, "Disabled plugin should be removed from active plugins")
        }

    @Test
    fun `plugin loader skips disabled plugins when listed in plugins dot disabled`() =
        runTest {
            var initialized = false
            val plugin =
                object : LunaPlugin {
                    override val name: String = "feature-x"

                    override suspend fun initialize(context: PluginContext) {
                        initialized = true
                    }
                }

            val loader = PluginLoader(plugins = listOf(plugin))
            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = mapOf("plugins.disabled" to "feature-y, feature-x, feature-z"),
                )

            loader.initializePlugins(context)
            assertFalse(initialized, "Plugin listed in plugins.disabled should not be initialized")
            assertEquals(0, loader.plugins.size)
        }
}
