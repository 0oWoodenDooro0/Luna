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
 * Luna plugin implementing Discord webhook and client management for Model Context Protocol (MCP) integrations.
 */
class McpPlugin(
    private val clientCommandFactory: (McpClientRepository, McpWebhookRepository, String) -> Command = { clientRepo, webhookRepo, baseUrl ->
        McpClientCommand(clientRepo, webhookRepo, baseUrl)
    },
) : LunaPlugin {
    override val name: String = "mcp"

    var clientRepository: McpClientRepository? = null
        private set

    var webhookRepository: McpWebhookRepository? = null
        private set

    var clientCommand: Command? = null
        private set

    override suspend fun initialize(context: PluginContext) {
        val clientRepo = McpClientRepository(context.database)
        clientRepository = clientRepo

        val webhookRepo = McpWebhookRepository(context.database)
        webhookRepository = webhookRepo

        val baseUrl = context.config("MCP_PUBLIC_BASE_URL", McpClientCommand.DEFAULT_PUBLIC_BASE_URL)

        val mcpCommand = clientCommandFactory(clientRepo, webhookRepo, baseUrl)
        clientCommand = mcpCommand
        mcpCommand.register(context.kord)

        val scope = safeScope(context.kord)
        context.kord.on<ChatInputCommandInteractionCreateEvent>(scope = scope) {
            val rootName = interaction.command.rootName
            if (rootName == mcpCommand.name) {
                try {
                    mcpCommand.handle(interaction)
                    JsonLogger.log(
                        layer = "COMMAND",
                        component = mcpCommand.javaClass.simpleName,
                        operation = "handle",
                        data =
                            mapOf(
                                "userId" to interaction.user.id.toString(),
                                "command" to rootName,
                            ),
                    )
                } catch (e: Exception) {
                    JsonLogger.error(
                        layer = "COMMAND",
                        component = mcpCommand.javaClass.simpleName,
                        operation = "handle",
                        data =
                            mapOf(
                                "userId" to interaction.user.id.toString(),
                                "command" to rootName,
                            ),
                        errorMessage = e.message,
                    )
                    throw e
                }
            }
        }
    }
}
