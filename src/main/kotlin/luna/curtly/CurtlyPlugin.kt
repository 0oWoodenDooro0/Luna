package luna.curtly

import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import luna.core.AuthService
import luna.core.JsonLogger
import luna.core.UserStorage
import luna.curtly.adapter.MyUrlsCommand
import luna.curtly.adapter.ShortenCommand
import luna.curtly.adapter.curtlyRouting
import luna.curtly.engine.CurtlyService
import luna.curtly.engine.ExposedUrlStorage
import luna.curtly.engine.UrlStorage
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import luna.plugin.config

class CurtlyPlugin(
    private val customStorage: UrlStorage? = null,
    private val customService: CurtlyService? = null,
    private val customAuthService: AuthService? = null,
) : LunaPlugin {
    override val name: String = "curtly"

    override suspend fun initialize(context: PluginContext) {
        val rawBaseUrl =
            context.config<String>("curtly.baseUrl")
                ?: context.config<String>("curtly.baseurl")
                ?: context.config<String>("baseUrl")
                ?: System.getenv("BASE_URL")
                ?: "http://localhost:8080/s/"

        val baseUrl =
            if (!rawBaseUrl.contains("/s")) {
                if (rawBaseUrl.endsWith("/")) "${rawBaseUrl}s/" else "$rawBaseUrl/s/"
            } else {
                rawBaseUrl
            }

        // 1. Initialize URL & analytics tables in SQLite via context.database
        val storage = customStorage ?: ExposedUrlStorage(database = context.database, autoCreateTable = true)
        val service = customService ?: CurtlyService(storage = storage, baseUrl = baseUrl, enableAuditMode = true)

        val userStorage = UserStorage(context.database)
        val authService = customAuthService ?: AuthService(userStorage)

        // 2. Register /shorten and /myurls commands via context.kord
        val shortenCommand = ShortenCommand(service)
        val myUrlsCommand = MyUrlsCommand(service, baseUrl)

        try {
            shortenCommand.register(context.kord)
            myUrlsCommand.register(context.kord)

            context.kord.on<ChatInputCommandInteractionCreateEvent> {
                val commandName = interaction.command.rootName
                val userId = interaction.user.id.toString()
                val options = interaction.command.options.mapValues { it.value.toString() }

                when (commandName) {
                    shortenCommand.name -> {
                        try {
                            shortenCommand.handle(interaction)
                            JsonLogger.log(
                                layer = "COMMAND",
                                component = "ShortenCommand",
                                operation = "handle",
                                data =
                                    mapOf(
                                        "userId" to userId,
                                        "command" to commandName,
                                        "options" to options,
                                    ),
                            )
                        } catch (e: Exception) {
                            JsonLogger.error(
                                layer = "COMMAND",
                                component = "ShortenCommand",
                                operation = "handle",
                                data =
                                    mapOf(
                                        "userId" to userId,
                                        "command" to commandName,
                                        "options" to options,
                                    ),
                                errorMessage = e.message,
                            )
                            throw e
                        }
                    }

                    myUrlsCommand.name -> {
                        try {
                            myUrlsCommand.handle(interaction)
                            JsonLogger.log(
                                layer = "COMMAND",
                                component = "MyUrlsCommand",
                                operation = "handle",
                                data =
                                    mapOf(
                                        "userId" to userId,
                                        "command" to commandName,
                                        "options" to options,
                                    ),
                            )
                        } catch (e: Exception) {
                            JsonLogger.error(
                                layer = "COMMAND",
                                component = "MyUrlsCommand",
                                operation = "handle",
                                data =
                                    mapOf(
                                        "userId" to userId,
                                        "command" to commandName,
                                        "options" to options,
                                    ),
                                errorMessage = e.message,
                            )
                            throw e
                        }
                    }
                }
            }
        } catch (e: Exception) {
            JsonLogger.log(
                layer = "PLUGIN",
                component = "CurtlyPlugin",
                operation = "registerCommands",
                data = mapOf("warning" to (e.message ?: "Could not register Kord event handler")),
                status = "SKIPPED",
            )
        }

        // 3. Mount Ktor routing (/s/{key} redirect, /api/shorten, dashboard, OAuth) via context.routing
        context.routing.curtlyRouting(
            curtlyService = service,
            authService = authService,
            baseUrl = baseUrl,
        )
    }
}
