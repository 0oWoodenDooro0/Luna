package luna.mcp.server

import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonObject
import luna.mcp.data.McpClientRepository
import org.slf4j.Logger

/**
 * Authenticated caller identity resolved for an MCP request.
 */
data class McpAuthContext(
    val clientId: String,
    val ownerId: String? = null,
)

/**
 * Result of executing an [McpTool].
 */
sealed interface McpToolResult {
    data class Text(
        val text: String,
        val isError: Boolean = false,
    ) : McpToolResult

    data class RpcError(
        val code: Int,
        val message: String,
    ) : McpToolResult
}

/**
 * A single MCP tool exposed by an [McpServerPlugin].
 */
interface McpTool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    fun isVisibleTo(auth: McpAuthContext): Boolean = true

    suspend fun execute(
        arguments: JsonObject?,
        auth: McpAuthContext,
    ): McpToolResult
}

/**
 * Self-contained capability module hosted by the decoupled MCP server.
 */
interface McpServerPlugin {
    val name: String

    fun isEnabledFor(auth: McpAuthContext): Boolean = true

    fun tools(): List<McpTool>

    fun registerRoutes(route: Route) {}
}

/**
 * Resolves [McpAuthContext] for [clientId], populating [McpAuthContext.ownerId] from [clientRepository]
 * when available and logging a structured warning if the repository or client record is missing.
 */
internal fun resolveMcpAuthContext(
    clientId: String,
    clientRepository: McpClientRepository?,
    logger: Logger,
): McpAuthContext {
    if (clientRepository == null) {
        logger.warn("McpClientRepository is absent; leaving ownerId null for clientId={}", clientId)
        return McpAuthContext(clientId = clientId, ownerId = null)
    }
    val client = clientRepository.findByClientId(clientId)
    if (client == null) {
        logger.warn("MCP client record not found; leaving ownerId null for clientId={}", clientId)
        return McpAuthContext(clientId = clientId, ownerId = null)
    }
    return McpAuthContext(clientId = clientId, ownerId = client.userId)
}
