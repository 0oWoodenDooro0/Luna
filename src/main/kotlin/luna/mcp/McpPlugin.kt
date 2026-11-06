package luna.mcp

import dev.kord.core.Kord
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import luna.core.Command
import luna.core.JsonLogger
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext

internal fun safeScope(kord: Kord): CoroutineScope {
    val hasValidContext =
        runCatching {
            kord.coroutineContext.fold(true) { acc, _ -> acc }
        }.getOrDefault(false)

    return if (hasValidContext) kord else CoroutineScope(Dispatchers.Default)
}

/**
 * Luna plugin implementing Discord webhook management for Model Context Protocol (MCP) integrations.
 */
class McpPlugin(
    private val commandFactory: (McpWebhookStorage) -> Command = { McpWebhookCommand(it) },
) : LunaPlugin {
    override val name: String = "mcp"

    var storage: McpWebhookStorage? = null
        private set

    var webhookCommand: Command? = null
        private set

    override suspend fun initialize(context: PluginContext) {
        val webhookStorage = McpWebhookStorage(context.database)
        storage = webhookStorage

        val command = commandFactory(webhookStorage)
        webhookCommand = command
        command.register(context.kord)

        val scope = safeScope(context.kord)
        context.kord.on<ChatInputCommandInteractionCreateEvent>(scope = scope) {
            if (interaction.command.rootName == command.name) {
                try {
                    command.handle(interaction)
                    JsonLogger.log(
                        layer = "COMMAND",
                        component = command.javaClass.simpleName,
                        operation = "handle",
                        data =
                            mapOf(
                                "userId" to interaction.user.id.toString(),
                                "command" to command.name,
                            ),
                    )
                } catch (e: Exception) {
                    JsonLogger.error(
                        layer = "COMMAND",
                        component = command.javaClass.simpleName,
                        operation = "handle",
                        data =
                            mapOf(
                                "userId" to interaction.user.id.toString(),
                                "command" to command.name,
                            ),
                        errorMessage = e.message,
                    )
                    throw e
                }
            }
        }
    }
}
