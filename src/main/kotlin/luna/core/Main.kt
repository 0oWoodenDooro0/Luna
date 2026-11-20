package luna.core

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.on
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import luna.core.ai.AiTraceConfig
import luna.core.ai.AiTraceRecorder
import luna.core.ai.JsonlAiTraceRecorder
import luna.plugin.DefaultPluginContext
import luna.plugin.PluginContext
import luna.plugin.PluginLoader
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val ownerErrorDmSender: (suspend (Snowflake, String) -> Unit)? = null,
) {
    val config: Map<String, Any?>
    val coreConfig: LunaCoreConfig
    val aiTraceConfig: AiTraceConfig
    val aiTraceRecorder: AiTraceRecorder
    val errorNotifyConfig: ErrorNotifyConfig
    val ownerErrorNotifier: OwnerErrorNotifier
    val database: Database = customDatabase ?: initDatabase()

    var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
        private set

    var pluginContext: PluginContext? = null
        private set

    var kord: Kord? = null
        private set

    private var ownerErrorAppender: OwnerErrorLogbackAppender? = null
    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var installedUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var readyListenerJob: Job? = null

    init {
        val loaded = customConfig ?: ConfigLoader.loadConfig()
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

        aiTraceConfig = AiTraceConfig.fromMap(config)
        aiTraceRecorder = JsonlAiTraceRecorder.fromConfig(aiTraceConfig, config)
        errorNotifyConfig = ErrorNotifyConfig.fromMap(config)
        ownerErrorNotifier =
            OwnerErrorNotifier(
                config = errorNotifyConfig,
                secretConfig = config,
                dmSender = ownerErrorDmSender,
            )

        // Temporarily attach appender during init audit so pre-ReadyEvent [CONFIG_DRIFT] alerts
        // are captured and buffered without leaving a dangling appender if start() is not called.
        attachOwnerErrorAppender()
        try {
            // Run startup drift audit to fail fast on missing required variables and report drift
            ConfigLoader.auditDrift(loaded)
            // Startup audit log emitting active configuration with secrets masked
            ConfigLoader.auditLog(config)
        } catch (t: Throwable) {
            runBlocking { ownerErrorNotifier.flushAndClose() }
            throw t
        } finally {
            detachOwnerErrorAppender()
        }
    }

    @OptIn(PrivilegedIntent::class)
    suspend fun start(loginKord: Boolean = true): PluginContext {
        attachOwnerErrorAppender()
        installUncaughtExceptionHandler()

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
        ownerErrorNotifier.bindKord(kordClient)
        registerReadyListener(kordClient)

        // 3. Build DefaultPluginContext providing kord, routing, database, configuration, and AI trace recorder
        val context =
            DefaultPluginContext(
                kord = kordClient,
                routing = routing,
                database = database,
                configMap = config,
                aiTraceRecorder = aiTraceRecorder,
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

    suspend fun shutdown() {
        try {
            readyListenerJob?.cancel()
            readyListenerJob = null
        } catch (_: Exception) {
        }
        try {
            pluginLoader.shutdownPlugins()
        } catch (e: Exception) {
            System.err.println("Error during plugin shutdown: ${e.message}")
        }
        try {
            aiTraceRecorder.flushAndClose()
        } catch (e: Exception) {
            System.err.println("Error during AI trace flush: ${e.message}")
        }
        detachOwnerErrorAppender()
        restoreUncaughtExceptionHandler()
        try {
            ownerErrorNotifier.bindKord(null)
            ownerErrorNotifier.flushAndClose()
        } catch (e: Exception) {
            System.err.println("Error during OwnerErrorNotifier flush: ${e.message}")
        }
        try {
            server?.stop(1000, 2000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun attachOwnerErrorAppender() {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.detachAppender(OwnerErrorLogbackAppender.APPENDER_NAME)

        val appender =
            OwnerErrorLogbackAppender(ownerErrorNotifier).apply {
                context = loggerContext
                name = OwnerErrorLogbackAppender.APPENDER_NAME
                start()
            }
        rootLogger.addAppender(appender)
        ownerErrorAppender = appender
    }

    private fun detachOwnerErrorAppender() {
        val appender = ownerErrorAppender ?: return
        ownerErrorAppender = null
        runCatching {
            val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext
            loggerContext?.getLogger(Logger.ROOT_LOGGER_NAME)?.detachAppender(appender)
            appender.stop()
        }
    }

    private fun installUncaughtExceptionHandler() {
        if (installedUncaughtExceptionHandler != null) return
        val uncaughtLogger = LoggerFactory.getLogger("luna.core.UncaughtException")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler =
            Thread.UncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    uncaughtLogger.error(
                        "Uncaught exception in thread '{}': {}",
                        thread.name,
                        throwable.message ?: throwable::class.simpleName ?: "Unknown error",
                        throwable,
                    )
                }
                if (previous != null && previous !== installedUncaughtExceptionHandler) {
                    previous.uncaughtException(thread, throwable)
                }
            }
        previousUncaughtExceptionHandler = previous
        installedUncaughtExceptionHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    private fun restoreUncaughtExceptionHandler() {
        val installed = installedUncaughtExceptionHandler ?: return
        val previous = previousUncaughtExceptionHandler
        installedUncaughtExceptionHandler = null
        previousUncaughtExceptionHandler = null
        if (Thread.getDefaultUncaughtExceptionHandler() === installed) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun registerReadyListener(kordClient: Kord) {
        val hasValidContext =
            runCatching {
                kordClient.coroutineContext.fold(true) { acc, _ -> acc }
            }.getOrDefault(false)
        if (!hasValidContext) return

        runCatching {
            readyListenerJob =
                kordClient.on<ReadyEvent> {
                    ownerErrorNotifier.onReady()
                }
        }
    }

    private fun registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking {
                    shutdown()
                }
            },
        )
    }

    companion object {
        const val DEFAULT_SQLITE_VEC_EXTENSION_PATH = "/app/lib/vec0.so"
        private val logger = LoggerFactory.getLogger(LunaHost::class.java)

        fun initDatabase(
            dbPath: String = ".data/luna.db",
            vecExtensionPath: String? = DEFAULT_SQLITE_VEC_EXTENSION_PATH,
        ): Database {
            val file = File(dbPath)
            file.parentFile?.mkdirs()
            val warnedVecUnavailable = AtomicBoolean(false)
            return Database.connect(
                "jdbc:sqlite:$dbPath?enable_load_extension=true",
                driver = "org.sqlite.JDBC",
                setupConnection = { connection ->
                    val meta = connection.metaData
                    val isSqlite =
                        meta.databaseProductName.contains("SQLite", ignoreCase = true) ||
                            (meta.url?.startsWith("jdbc:sqlite") == true)
                    if (isSqlite) {
                        connection.createStatement().use { stmt ->
                            stmt.execute("PRAGMA journal_mode=WAL;")
                            stmt.execute("PRAGMA busy_timeout=5000;")
                            stmt.execute("PRAGMA cache_size=-8000;")
                            stmt.execute("PRAGMA mmap_size=0;")
                        }
                        loadSqliteVecExtensionFailOpen(connection, vecExtensionPath, warnedVecUnavailable)
                    }
                },
            )
        }

        private fun loadSqliteVecExtensionFailOpen(
            connection: java.sql.Connection,
            vecExtensionPath: String?,
            warnedVecUnavailable: AtomicBoolean,
        ) {
            val normalizedPath = vecExtensionPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val extFile = File(normalizedPath)
            if (!extFile.exists()) {
                if (warnedVecUnavailable.compareAndSet(false, true)) {
                    logger.warn(
                        "sqlite-vec extension not found at '{}'; continuing without native vector search (Fail-Open)",
                        normalizedPath,
                    )
                }
                return
            }
            runCatching {
                connection.prepareStatement("SELECT load_extension(?)").use { ps ->
                    ps.setString(1, extFile.absolutePath)
                    ps.execute()
                }
            }.onFailure { error ->
                if (warnedVecUnavailable.compareAndSet(false, true)) {
                    logger.warn(
                        "Failed to load sqlite-vec extension from '{}'; continuing without native vector search (Fail-Open): {}",
                        normalizedPath,
                        error.message,
                    )
                }
            }
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
