package luna.plugin

/**
 * An isolated, self-contained business module implementing the plugin lifecycle,
 * encapsulating Discord commands, event listeners, and optional Ktor Web routes
 * without directly depending on peer plugins.
 */
interface LunaPlugin {
    /**
     * Unique identifier and human-readable name of the plugin.
     */
    val name: String

    /**
     * Called by Luna Host during startup to inject runtime capabilities and configure
     * event listeners, commands, and HTTP routes.
     */
    suspend fun initialize(context: PluginContext)

    /**
     * Optional lifecycle hook executed after all plugins have been initialized,
     * signaling that the bot and server runtime are active.
     */
    suspend fun start() {}

    /**
     * Optional lifecycle hook executed during graceful shutdown.
     */
    suspend fun shutdown() {}
}
