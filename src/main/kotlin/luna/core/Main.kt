package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
import com.github._0owoodendooro0.curtly.FileUrlStorage
import com.github._0owoodendooro0.curtly.curtlyRouting
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.SelectMenuInteractionCreateEvent
import dev.kord.core.on
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import luna.core.JsonLogger
import luna.undercover.UndercoverGame
import luna.undercover.UndercoverManager
import luna.undercover.command.RevealCommand
import luna.undercover.command.UndercoverCommand
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream

suspend fun main() {
    val storage = FileUrlStorage(File("data/urls.properties"))

    val yamlFile = File("application.yml")
    val yamlStream: InputStream? =
        if (yamlFile.exists()) {
            yamlFile.inputStream()
        } else {
            Thread.currentThread().contextClassLoader.getResourceAsStream("application.yml")
        }

    val yamlBaseUrl =
        yamlStream?.use { stream ->
            try {
                val yaml = Yaml()
                val config = yaml.load<Map<String, Any>>(stream)
                val curtlyConfig = config["curtly"] as? Map<*, *>
                val rawBaseUrl = curtlyConfig?.get("baseUrl") as? String ?: curtlyConfig?.get("baseurl") as? String

                if (rawBaseUrl != null && rawBaseUrl.trim().startsWith("\${") && rawBaseUrl.trim().endsWith("}")) {
                    val trimmed = rawBaseUrl.trim()
                    val inner = trimmed.substring(2, trimmed.length - 1).trim()
                    val cleanInner = if (inner.startsWith("?")) inner.substring(1) else inner
                    val parts = cleanInner.split(":", limit = 2)
                    val envVarName = parts[0].trim()
                    val defaultValue = if (parts.size > 1) parts[1].trim() else ""
                    System.getenv(envVarName) ?: defaultValue
                } else {
                    rawBaseUrl
                }
            } catch (e: Exception) {
                null
            }
        }

    val baseUrl = yamlBaseUrl ?: System.getenv("BASE_URL") ?: "http://localhost:8080"
    val curtlyService = CurtlyService(storage = storage, baseUrl = baseUrl)

    val serverPort = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val server =
        embeddedServer(Netty, port = serverPort, host = "0.0.0.0") {
            routing {
                curtlyRouting(curtlyService)
            }
        }

    // Start Ktor server in a background coroutine
    CoroutineScope(Dispatchers.Default).launch {
        server.start(wait = true)
    }

    val kord = Kord(System.getenv("DISCORD_TOKEN") ?: error("Missing discord token"))

    val commands =
        listOf(
            UndercoverCommand(),
            RevealCommand(),
            ShortenCommand(curtlyService),
        )
    commands.forEach { it.register(kord) }

    kord.on<ChatInputCommandInteractionCreateEvent> {
        val commandName = interaction.command.rootName
        val userId = interaction.user.id.toString()
        val options = interaction.command.options.mapValues { it.value.toString() }

        val matchedCommand = commands.find { it.name == commandName }

        if (matchedCommand != null) {
            try {
                matchedCommand.handle(interaction)
                JsonLogger.log(
                    layer = "COMMAND",
                    component = matchedCommand.javaClass.simpleName,
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
                    component = matchedCommand.javaClass.simpleName,
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

    kord.on<SelectMenuInteractionCreateEvent> {
        if (interaction.componentId == "undercover_vote") {
            val guildId = interaction.data.guildId.value ?: return@on
            val voterId = interaction.user.id
            val game = UndercoverManager.activeGames[guildId]

            if (game == null) {
                val response = interaction.deferEphemeralResponse()
                response.respond { content = "目前沒有正在進行的遊戲！" }
                return@on
            }

            if (!game.players.containsKey(voterId)) {
                val response = interaction.deferEphemeralResponse()
                response.respond { content = "你沒有參與這場遊戲，無法投票喔！" }
                return@on
            }

            val votedUserIdString = interaction.values.firstOrNull() ?: return@on
            val votedUserId = Snowflake(votedUserIdString)

            game.votes[voterId] = votedUserId
            val votedUserName = game.players[votedUserId] ?: "未知玩家"

            val response = interaction.deferEphemeralResponse()
            response.respond { content = "你已秘密投票給：$votedUserName" }
        }
    }

    kord.login()
}
