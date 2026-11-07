package luna.core

import dev.kord.core.Kord
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.coroutines.runBlocking
import luna.plugin.DefaultPluginContext
import luna.plugin.PluginContext
import luna.plugin.PluginLoader
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LunaHost central runtime container and infrastructure orchestrator.
 * Responsible for configuration loading, database connection pooling,
 * Ktor server bootstrapping, Kord bot lifecycle, and plugin discovery.
 */
class LunaHost(
    customConfig: Map<String, Any?>? = null,
    customDatabase: Database? = null,
    val pluginLoader: PluginLoader = PluginLoader(),
    private val kordFactory: ((String) -> Kord)? = null,
) {
    val config: Map<String, Any?>
    val coreConfig: LunaCoreConfig
    val database: Database = customDatabase ?: initDatabase()

    var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
        private set

    var pluginContext: PluginContext? = null
        private set

    var kord: Kord? = null
        private set

    init {
        val loaded = customConfig ?: ConfigLoader.loadConfig()
        // Run startup drift audit to fail fast on missing required variables and report drift
        ConfigLoader.auditDrift(loaded)
        // Fail-fast validation against LunaCoreConfig data model
        coreConfig = LunaCoreConfig.fromMap(loaded)

        // Ensure active core config is represented in the unified config map
        val unified = loaded.toMutableMap()
        unified.putIfAbsent("PORT", coreConfig.port)
        unified.putIfAbsent("DISCORD_TOKEN", coreConfig.botToken)
        unified.putIfAbsent("DISCORD_CLIENT_ID", coreConfig.clientId)
        unified.putIfAbsent("DISCORD_CLIENT_SECRET", coreConfig.clientSecret)
        unified.putIfAbsent("BASE_URL", coreConfig.baseUrl)
        unified.putIfAbsent("DISCORD_REDIRECT_URI", coreConfig.redirectUri)
        config = unified

        // Startup audit log emitting active configuration with secrets masked
        ConfigLoader.auditLog(config)
    }

    @OptIn(PrivilegedIntent::class)
    suspend fun start(loginKord: Boolean = true): PluginContext {
        // 1. Initialize Ktor embeddedServer (port, session cookie)
        val serverPort = coreConfig.port

        val ktorServer =
            embeddedServer(Netty, port = serverPort, host = "0.0.0.0") {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
            }
        this.server = ktorServer
        val routing = ktorServer.application.routing { }

        try {
            ktorServer.start(wait = false)
        } catch (e: Exception) {
            System.err.println("⚠️ Ktor HTTP 伺服器啟動失敗（Port $serverPort 可能已被占用）：${e.message}")
        }

        // 2. Initialize Kord client with token
        val token = coreConfig.botToken
        val kordClient =
            if (kordFactory != null) {
                kordFactory.invoke(token)
            } else {
                Kord(token)
            }
        this.kord = kordClient

        // 3. Build DefaultPluginContext providing kord, routing, database, and configuration
        val context =
            DefaultPluginContext(
                kord = kordClient,
                routing = routing,
                database = database,
                configMap = config,
            )
        this.pluginContext = context

        // 4. Discover and initialize plugins via PluginLoader.initializePlugins(context) and start via PluginLoader.startPlugins()
        pluginLoader.initializePlugins(context)
        pluginLoader.startPlugins()

        // 5. Register JVM shutdown hook calling PluginLoader.shutdownPlugins()
        registerShutdownHook()

        // 6. Login to Kord gateway if requested
        if (loginKord) {
            kordClient.login {
                @OptIn(PrivilegedIntent::class)
                intents += Intent.MessageContent
            }
        }

        return context
    }

    private fun registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking {
                    try {
                        pluginLoader.shutdownPlugins()
                    } catch (e: Exception) {
                        System.err.println("Error during plugin shutdown: ${e.message}")
                    }
                    try {
                        server?.stop(1000, 2000, TimeUnit.MILLISECONDS)
                    } catch (_: Exception) {
                    }
                }
            },
        )
    }

    companion object {
        fun initDatabase(dbPath: String = ".data/urls.db"): Database {
            val file = File(dbPath)
            file.parentFile?.mkdirs()
            return Database.connect(
                "jdbc:sqlite:$dbPath",
                driver = "org.sqlite.JDBC",
                setupConnection = { connection ->
                    val meta = connection.metaData
                    val isSqlite = meta.databaseProductName.contains("SQLite", ignoreCase = true) || (meta.url?.startsWith("jdbc:sqlite") == true)
                    if (isSqlite) {
                        connection.createStatement().use { stmt ->
                            stmt.execute("PRAGMA journal_mode=WAL;")
                            stmt.execute("PRAGMA busy_timeout=5000;")
                        }
                    }
                },
            )
        }

        fun loadConfig(): Map<String, Any?> = ConfigLoader.loadConfig()
    }
}

@OptIn(PrivilegedIntent::class)
suspend fun main(args: Array<String>) {
    if (EnvSyncCli.handleCli(args)) {
        return
    }
    LunaHost().start()
}

@OptIn(PrivilegedIntent::class)
suspend fun main() {
    main(emptyArray())
}
