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
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
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
    val config: Map<String, Any?> = customConfig ?: loadConfig()
    val database: Database = customDatabase ?: initDatabase()

    var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
        private set

    var pluginContext: PluginContext? = null
        private set

    var kord: Kord? = null
        private set

    @OptIn(PrivilegedIntent::class)
    suspend fun start(loginKord: Boolean = true): PluginContext {
        // 1. Initialize Ktor embeddedServer (port, session cookie)
        val serverPort =
            (config["PORT"] as? String)?.toIntOrNull()
                ?: (config["PORT"] as? Number)?.toInt()
                ?: 8080

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
        val token = (config["DISCORD_TOKEN"] as? String) ?: System.getenv("DISCORD_TOKEN")
        val kordClient =
            if (kordFactory != null) {
                kordFactory.invoke(token ?: "")
            } else {
                Kord(token ?: error("Missing discord token"))
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
        fun initDatabase(): Database {
            File("data").mkdirs()
            return Database.connect("jdbc:sqlite:data/urls.db", driver = "org.sqlite.JDBC")
        }

        fun loadConfig(): Map<String, Any?> {
            val config = mutableMapOf<String, Any?>()

            // 1. Ingest .env if present
            val envFile = File(".env")
            if (envFile.exists()) {
                try {
                    envFile.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val parts = trimmed.split("=", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim()
                                var value = parts[1].trim()
                                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                                    (value.startsWith("'") && value.endsWith("'"))
                                ) {
                                    value = value.substring(1, value.length - 1)
                                }
                                config[key] = value
                            }
                        }
                    }
                } catch (e: Exception) {
                    JsonLogger.error(
                        layer = "CONFIG",
                        component = "LunaHost",
                        operation = "loadConfig",
                        data = emptyMap<String, Any?>(),
                        errorMessage = "Failed to parse .env: ${e.message}",
                    )
                }
            }

            // 2. Ingest System.getenv()
            config.putAll(System.getenv())

            // 3. Ingest application.yml
            val yamlFile = File("application.yml")
            val yamlStream: InputStream? =
                if (yamlFile.exists()) {
                    yamlFile.inputStream()
                } else {
                    Thread.currentThread().contextClassLoader.getResourceAsStream("application.yml")
                }

            yamlStream?.use { stream ->
                try {
                    val yaml = Yaml()
                    val yamlMap = yaml.load<Map<String, Any?>>(stream)
                    if (yamlMap != null) {
                        flattenYaml("", yamlMap, config)
                    }
                } catch (e: Exception) {
                    JsonLogger.error(
                        layer = "CONFIG",
                        component = "LunaHost",
                        operation = "loadConfig",
                        data = emptyMap<String, Any?>(),
                        errorMessage = "Failed to parse application.yml: ${e.message}",
                    )
                }
            }

            return config
        }

        private fun flattenYaml(
            prefix: String,
            map: Map<*, *>,
            target: MutableMap<String, Any?>,
        ) {
            for ((key, value) in map) {
                val fullKey = if (prefix.isEmpty()) key.toString() else "$prefix.$key"
                when (value) {
                    is Map<*, *> -> {
                        target[fullKey] = value
                        flattenYaml(fullKey, value, target)
                    }

                    is String -> {
                        val resolved = resolvePlaceholders(value, target)
                        target[fullKey] = resolved
                    }

                    else -> {
                        if (value != null) {
                            target[fullKey] = value
                        }
                    }
                }
            }
        }

        private fun resolvePlaceholders(
            raw: String,
            config: Map<String, Any?>,
        ): String {
            val trimmed = raw.trim()
            if (trimmed.startsWith("\${") && trimmed.endsWith("}")) {
                val inner = trimmed.substring(2, trimmed.length - 1).trim()
                val cleanInner = if (inner.startsWith("?")) inner.substring(1) else inner
                val parts = cleanInner.split(":", limit = 2)
                val envVarName = parts[0].trim()
                val defaultValue = if (parts.size > 1) parts[1].trim() else ""
                return (config[envVarName] as? String) ?: System.getenv(envVarName) ?: defaultValue
            }
            return raw
        }
    }
}

@OptIn(PrivilegedIntent::class)
suspend fun main() {
    LunaHost().start()
}
