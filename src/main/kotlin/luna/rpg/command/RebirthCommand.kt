package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import luna.core.Command
import luna.rpg.repository.PlayerRepository

class RebirthCommand : Command {
    override val name = "rebirth"
    override val description = "執行重生（轉生），重置進度以獲得永久強化點數"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val player = PlayerRepository.getOrCreatePlayer(userId)
        
        val response = interaction.deferPublicResponse()
        
        if (!player.canRebirth()) {
            response.respond {
                content = "❌ 你還不能重生！需要到達第 **${luna.rpg.RpgConfig.Rebirth.MIN_FLOOR}** 層（目前第 ${player.currentFloor} 層）。"
            }
            return
        }

        val earnedPoints = player.calculateEarnedPoints()
        val result = PlayerRepository.rebirthPlayer(userId)
        
        if (result != null) {
            response.respond {
                content = "✨ **重生成功！** ✨\n你已重置進度，並獲得了 **$earnedPoints** 點重生點數！\n目前的重生點數：**${result.rebirthPoints}**\n可以使用 `/rebirth_upgrade` 來強化你的能力。"
            }
        } else {
            response.respond {
                content = "❌ 重生過程中發生錯誤。"
            }
        }
    }
}
