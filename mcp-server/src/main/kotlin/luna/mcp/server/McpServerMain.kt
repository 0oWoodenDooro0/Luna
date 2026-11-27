package luna.mcp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import luna.ledger.LedgerDatabase
import luna.ledger.LedgerRepository
import luna.ledger.LedgerService
import luna.mcp.data.McpClientRepository
import luna.mcp.data.McpDatabase
import luna.mcp.data.McpWebhookRepository
import org.slf4j.LoggerFactory
import java.time.ZoneId

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

        val plugins = buildServerPlugins(config, broadcaster, repository)

        val server =
            embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    plugins = plugins,
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

    internal fun buildServerPlugins(
        config: McpServerConfig,
        broadcaster: DiscordWebhookBroadcaster,
        repository: McpWebhookRepository,
    ): List<McpServerPlugin> =
        buildList {
            add(DiscordWebhookPlugin(broadcaster, repository))
            if (config.ledgerEnabled) {
                logger.info(
                    "Enabling LedgerPlugin with dbPath={} currency={} timezone={}",
                    config.ledgerDbPath,
                    config.ledgerDefaultCurrency,
                    config.ledgerTimezone,
                )
                val ledgerDatabase = LedgerDatabase.connect(config.ledgerDbPath)
                val ledgerService = LedgerService(LedgerRepository(ledgerDatabase))
                add(
                    LedgerPlugin(
                        ledgerService = ledgerService,
                        defaultCurrency = config.ledgerDefaultCurrency,
                        timezone = ZoneId.of(config.ledgerTimezone),
                        allowedUserIds = config.ledgerAllowedUserIds,
                    ),
                )
            }
        }
}

fun main(args: Array<String>) = McpServerMain.main(args)
