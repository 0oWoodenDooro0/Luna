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
     * Skips plugins that are disabled via configuration (e.g. `plugins.<name>.enabled: false` or `plugins.disabled`).
     */
    suspend fun initializePlugins(context: PluginContext) {
        if (!discovered) {
            loadPlugins()
        }

        val enabledPlugins = mutableListOf<LunaPlugin>()
        for (plugin in _plugins) {
            if (!isPluginEnabled(plugin, context)) {
                JsonLogger.log(
                    layer = "PLUGIN",
                    component = "PluginLoader",
                    operation = "skipPlugin",
                    data = mapOf("plugin" to plugin.name, "reason" to "disabled_by_config"),
                )
                continue
            }

            try {
                plugin.initialize(context)
                enabledPlugins.add(plugin)
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

        _plugins.clear()
        _plugins.addAll(enabledPlugins)
    }

    private fun isPluginEnabled(
        plugin: LunaPlugin,
        context: PluginContext,
    ): Boolean {
        val envKey = "PLUGINS_${plugin.name.replace('-', '_').uppercase()}_ENABLED"
        val specificEnabled =
            runCatching {
                context.config<Boolean>("plugins.${plugin.name}.enabled")
                    ?: context.config<String>("plugins.${plugin.name}.enabled")?.toBooleanStrictOrNull()
                    ?: context.config<Boolean>(envKey)
                    ?: context.config<String>(envKey)?.toBooleanStrictOrNull()
                    ?: context.config<Boolean>("plugins.${plugin.name}")
                    ?: context.config<String>("plugins.${plugin.name}")?.toBooleanStrictOrNull()
            }.getOrNull()
        if (specificEnabled == false) return false

        val disabledList =
            runCatching {
                (
                    context.config<String>("plugins.disabled")
                        ?: context.config<String>("PLUGINS_DISABLED")
                )?.split(",")
                    ?.map { it.trim().lowercase() }
            }.getOrNull() ?: emptyList()
        if (plugin.name.lowercase() in disabledList) return false

        return true
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
