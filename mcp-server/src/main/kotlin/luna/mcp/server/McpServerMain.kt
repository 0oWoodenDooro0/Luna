package luna.mcp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import luna.mcp.data.McpClientRepository
import luna.mcp.data.McpDatabase
import luna.mcp.data.McpWebhookRepository
import org.slf4j.LoggerFactory

object McpServerMain {
    private val logger = LoggerFactory.getLogger(McpServerMain::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        val config =
            try {
                McpServerConfig.load()
            } catch (e: Exception) {
                logger.error("Failed to load MCP server configuration: {}", e.message)
                throw e
            }

        config.auditLog(logger)

        logger.info("Starting standalone MCP server on port {} with database path {}", config.port, config.dbPath)

        val database = McpDatabase.connect(config.dbPath)
        val repository = McpWebhookRepository(database)
        val clientRepository = McpClientRepository(database)
        val broadcaster = DiscordWebhookBroadcaster(repository)
        val authConfig = config.toOAuthConfig()

        logger.info(
            "MCP server publicBaseUrl={}",
            authConfig.publicBaseUrl,
        )

        val server =
            embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                )
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
