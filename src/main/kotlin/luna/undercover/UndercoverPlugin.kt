package luna.undercover

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.SelectMenuInteraction
import dev.kord.core.event.interaction.SelectMenuInteractionCreateEvent
import dev.kord.core.on
import luna.core.Command
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import luna.undercover.command.RevealCommand
import luna.undercover.command.UndercoverCommand
import org.slf4j.LoggerFactory

/**
 * Luna plugin encapsulating the Undercover party game commands and voting interaction.
 */
class UndercoverPlugin(
    val undercoverCommand: Command = UndercoverCommand(),
    val revealCommand: Command = RevealCommand(),
) : LunaPlugin {
    private val logger = LoggerFactory.getLogger(UndercoverPlugin::class.java)

    override val name: String = "undercover"

    override suspend fun initialize(context: PluginContext) {
        val kord = context.kord

        // Register slash commands to context.kord
        undercoverCommand.register(kord)
        revealCommand.register(kord)

        // Attach slash command interaction listener to context.kord
        try {
            kord.on<dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent> {
                val commandName = interaction.command.rootName
                val matchedCommand =
                    when (commandName) {
                        undercoverCommand.name -> undercoverCommand
                        revealCommand.name -> revealCommand
                        else -> null
                    }
                if (matchedCommand != null) {
                    val userId = interaction.user.id.toString()
                    val options = interaction.command.options.mapValues { it.value.toString() }
                    try {
                        matchedCommand.handle(interaction)
                        luna.core.JsonLogger.log(
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
                        luna.core.JsonLogger.error(
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
        } catch (e: Exception) {
            logger.warn("Could not attach chat input command interaction listener to Kord: {}", e.message)
        }

        // Attach select menu interaction listener for undercover_vote to context.kord
        try {
            kord.on<SelectMenuInteractionCreateEvent> {
                if (interaction.componentId == VOTE_COMPONENT_ID) {
                    handleVoteInteraction(interaction)
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not attach select menu interaction listener to Kord: {}", e.message)
        }
    }

    override suspend fun shutdown() {
        UndercoverManager.activeGames.clear()
    }

    internal suspend fun handleVoteInteraction(interaction: SelectMenuInteraction) {
        val guildId = interaction.data.guildId.value ?: return
        val voterId = interaction.user.id
        val game = UndercoverManager.activeGames[guildId]

        if (game == null) {
            val response = interaction.deferEphemeralResponse()
            response.respond { content = "目前沒有正在進行的遊戲！" }
            return
        }

        if (!game.players.containsKey(voterId)) {
            val response = interaction.deferEphemeralResponse()
            response.respond { content = "你沒有參與這場遊戲，無法投票喔！" }
            return
        }

        val votedUserIdString = interaction.values.firstOrNull() ?: return
        val votedUserId = Snowflake(votedUserIdString)

        game.votes[voterId] = votedUserId
        val votedUserName = game.players[votedUserId] ?: "未知玩家"

        val response = interaction.deferEphemeralResponse()
        response.respond { content = "你已秘密投票給：$votedUserName" }
    }

    companion object {
        const val VOTE_COMPONENT_ID = "undercover_vote"
    }
}
