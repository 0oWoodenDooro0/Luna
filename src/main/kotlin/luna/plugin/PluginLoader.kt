package luna.plugin

import luna.core.JsonLogger
import java.util.ServiceLoader

/**
 * Luna Host infrastructure orchestrator helper responsible for discovering and managing
 * the lifecycle of [LunaPlugin] implementations available on the classpath.
 */
class PluginLoader(
    val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    plugins: List<LunaPlugin>? = null,
) {
    private val _plugins = mutableListOf<LunaPlugin>()
    private var discovered = false

    val plugins: List<LunaPlugin>
        get() = _plugins.toList()

    init {
        if (plugins != null) {
            _plugins.addAll(plugins)
            discovered = true
        }
    }

    /**
     * Discovers and loads all [LunaPlugin] implementations using [ServiceLoader].
     */
    fun loadPlugins(): List<LunaPlugin> {
        val loader = ServiceLoader.load(LunaPlugin::class.java, classLoader)
        _plugins.clear()
        _plugins.addAll(loader.toList())
        discovered = true

        JsonLogger.log(
            layer = "PLUGIN",
            component = "PluginLoader",
            operation = "loadPlugins",
            data =
                mapOf(
                    "count" to _plugins.size,
                    "plugins" to _plugins.map { it.name },
                ),
        )

        return plugins
    }

    /**
     * Initializes all discovered plugins with the provided [context].
     * Discovers plugins via [loadPlugins] if not already loaded.
     */
    suspend fun initializePlugins(context: PluginContext) {
        if (!discovered) {
            loadPlugins()
        }

        for (plugin in _plugins) {
            try {
                plugin.initialize(context)
                JsonLogger.log(
                    layer = "PLUGIN",
                    component = "PluginLoader",
                    operation = "initializePlugin",
                    data = mapOf("plugin" to plugin.name),
                )
            } catch (e: Exception) {
                JsonLogger.error(
                    layer = "PLUGIN",
                    component = "PluginLoader",
                    operation = "initializePlugin",
                    data = mapOf("plugin" to plugin.name),
                    errorMessage = e.message,
                )
                throw IllegalStateException("Failed to initialize plugin '${plugin.name}': ${e.message}", e)
            }
        }
    }

    /**
     * Signals all initialized plugins to start their active background jobs or listeners.
     */
    suspend fun startPlugins() {
        for (plugin in _plugins) {
            try {
                plugin.start()
                JsonLogger.log(
                    layer = "PLUGIN",
                    component = "PluginLoader",
                    operation = "startPlugin",
                    data = mapOf("plugin" to plugin.name),
                )
            } catch (e: Exception) {
                JsonLogger.error(
                    layer = "PLUGIN",
                    component = "PluginLoader",
                    operation = "startPlugin",
                    data = mapOf("plugin" to plugin.name),
                    errorMessage = e.message,
                )
                throw IllegalStateException("Failed to start plugin '${plugin.name}': ${e.message}", e)
            }
        }
    }

    /**
     * Signals all plugins to shutdown gracefully in reverse initialization order.
     * Continues shut down of remaining plugins if an individual plugin throws.
     */
    suspend fun shutdownPlugins() {
        for (plugin in _plugins.reversed()) {
            try {
                plugin.shutdown()
                JsonLogger.log(
                    layer = "PLUGIN",
                    component = "PluginLoader",
                    operation = "shutdownPlugin",
                    data = mapOf("plugin" to plugin.name),
                )
            } catch (e: Exception) {
                JsonLogger.error(
                    layer = "PLUGIN",
                    component = "PluginLoader",
                    operation = "shutdownPlugin",
                    data = mapOf("plugin" to plugin.name),
                    errorMessage = e.message,
                )
            }
        }
    }
}
