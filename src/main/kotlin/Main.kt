package website.woodendoor

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.SelectMenuInteractionCreateEvent
import dev.kord.core.on
import website.woodendoor.command.RevealCommand
import website.woodendoor.command.UndercoverCommand

suspend fun main() {
    val kord = Kord(System.getenv("DISCORD_TOKEN") ?: error("Missing discord token"))

    val commands = listOf(UndercoverCommand(), RevealCommand())
    commands.forEach { it.register(kord) }

    kord.on<ChatInputCommandInteractionCreateEvent> {
        val commandName = interaction.command.rootName

        val matchedCommand = commands.find { it.name == commandName }

        matchedCommand?.handle(interaction)
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