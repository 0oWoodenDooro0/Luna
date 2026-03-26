package website.woodendoor.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import website.woodendoor.Command
import website.woodendoor.repository.PlayerRepository

class HuntCommand : Command {
    override val name = "hunt"
    override val description = "去森林裡打獵，賺取金幣與經驗值！"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userDiscordId = interaction.user.id.toString()
        val currentTime = System.currentTimeMillis()
        val cooldownMillis = 60 * 1000

        val playerData = PlayerRepository.getPlayer(userDiscordId)

        if (playerData == null) {
            val response = interaction.deferEphemeralResponse()
            response.respond {
                content = "你還沒有建立角色！請先輸入 /init 建立資料。"
            }
            return
        }

        val lastTime = playerData.lastHuntTime
        val timePassed = currentTime - lastTime

        if (timePassed < cooldownMillis) {
            val remainingSeconds = (cooldownMillis - timePassed) / 1000
            val response = interaction.deferEphemeralResponse()
            response.respond {
                content = "⏳ 你太累了！請再休息 **$remainingSeconds** 秒後再出發。"
            }
        } else {
            val newGold = playerData.gold + 10
            PlayerRepository.updateHuntResult(userDiscordId, newGold, currentTime)
            val response = interaction.deferPublicResponse()
            response.respond {
                content = "⚔️ 你揮舞武器打倒了怪物！獲得了 10 枚金幣。 (目前總金幣: $newGold)"
            }
        }
    }
}
