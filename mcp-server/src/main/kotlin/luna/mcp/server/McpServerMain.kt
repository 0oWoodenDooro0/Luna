package luna.mcp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory

object McpServerMain {
    private val logger = LoggerFactory.getLogger(McpServerMain::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("MCP_PORT")?.toIntOrNull() ?: 8081
        val dbPath = System.getenv("MCP_DB_PATH") ?: "./data/urls.db"

        logger.info("Starting standalone MCP server on port {} with database path {}", port, dbPath)

        val repository = McpWebhookRepository.create(dbPath)
        val broadcaster = DiscordWebhookBroadcaster(repository)
        val authConfig = McpOAuthConfig()

        logger.info(
            "MCP server publicBaseUrl={}, clientId={}, authEnabled={}",
            authConfig.publicBaseUrl,
            authConfig.clientId,
            authConfig.isAuthEnabled,
        )

        val server =
            embeddedServer(CIO, port = port, host = "0.0.0.0") {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
            }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutting down MCP server...")
                server.stop(1000, 2000)
            },
        )

        server.start(wait = true)
    }
}

fun main(args: Array<String>) = McpServerMain.main(args)
