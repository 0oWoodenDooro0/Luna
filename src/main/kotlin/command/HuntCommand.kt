package website.woodendoor.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import website.woodendoor.Command
import website.woodendoor.HuntService

/**
 * Command to start a hunt.
 */
class HuntCommand : Command {
    override val name = "hunt"
    override val description = "去森林裡打獵，賺取金幣與經驗值！"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userDiscordId = interaction.user.id.toString()

        when (val result = HuntService.processHunt(userDiscordId)) {
            is HuntService.HuntResult.NotRegistered -> {
                val response = interaction.deferEphemeralResponse()
                response.respond {
                    content = "你還沒有建立角色！請先輸入 /init 建立資料。"
                }
            }
            is HuntService.HuntResult.OnCooldown -> {
                val response = interaction.deferEphemeralResponse()
                response.respond {
                    content = "⏳ 你太累了！請再休息 **${result.remainingSeconds}** 秒後再出發。"
                }
            }
            is HuntService.HuntResult.Success -> {
                val response = interaction.deferPublicResponse()
                response.respond {
                    var msg = "⚔️ 你揮舞武器打倒了怪物！獲得了 10 枚金幣與 20 點經驗值。 (目前總金幣: ${result.newGold})"
                    if (result.levelUpResult.leveledUp) {
                        msg += "\n🎉 恭喜！你等級提升至 **${result.levelUpResult.newLevel}** 了！"
                    }
                    content = msg
                }
            }
        }
    }
}
