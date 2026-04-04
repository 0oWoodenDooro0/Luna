package luna.undercover.command

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import luna.core.Command
import luna.undercover.UndercoverManager

class RevealCommand : Command {
    override val name = "reveal"
    override val description = "揭曉誰是臥底的投票結果！"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val guildId = interaction.data.guildId.value ?: return
        val game = UndercoverManager.activeGames[guildId]

        if (game == null) {
            val response = interaction.deferPublicResponse()
            response.respond { content = "目前沒有正在進行的遊戲喔！請先使用 /undercover 指令開始遊戲。" }
            return
        }

        var voteResultText = "【投票結果】\n"
        if (game.votes.isNotEmpty()) {
            val voteCounts = mutableMapOf<Snowflake, Int>()
            for (votedId in game.votes.values) {
                voteCounts[votedId] = voteCounts.getOrDefault(votedId, 0) + 1
            }

            for ((id, count) in voteCounts) {
                val name = game.players[id] ?: "未知玩家"
                voteResultText += "**$name**: $count 票\n"
            }
        } else {
            voteResultText += "這局沒有任何人投票。\n"
        }

        UndercoverManager.activeGames.remove(guildId)

        val response = interaction.deferPublicResponse()
        response.respond {
            content = "$voteResultText\n解答揭曉！這次的臥底是：<@${game.spy}> 🕵️‍♂️"
        }
    }
}
