package luna.poker.command

import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.component.actionRow
import luna.core.Command
import luna.poker.User

class UpgradeCommand : Command {
    override val name = "升級"
    override val description = "開啟你的個人能力升級選單"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id
        val username = interaction.user.username
        val pokerUser = User.getOrCreate(userId.toString())

        val responseText = buildMenuText(userId.toString(), username, pokerUser)
        val response = interaction.deferPublicResponse()

        response.respond {
            content = responseText
            val nextCost = pokerUser.getNextDrawUpgradeCost()
            if (pokerUser.drawCount < 7 && nextCost != null) {
                actionRow {
                    interactionButton(ButtonStyle.Primary, "upgrade_draw_${userId}") {
                        label = "升級抽牌數量 (${pokerUser.drawCount} ➔ ${pokerUser.drawCount + 1} 抽)"
                    }
                }
            } else {
                actionRow {
                    interactionButton(ButtonStyle.Secondary, "upgrade_draw_max") {
                        label = "抽牌數量已達上限 (7 抽)"
                        disabled = true
                    }
                }
            }
        }
    }

    companion object {
        fun buildMenuText(userId: String, username: String, pokerUser: User): String {
            val sb = StringBuilder()
            sb.append("✨ **能力升級選單** ✨\n\n")
            sb.append("玩家：<@$userId> ($username)\n")
            sb.append("目前分數：**${pokerUser.score}** 分 🏆\n\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
            sb.append("🃏 **抽牌數量升級**\n")
            sb.append("• 目前等級：`${pokerUser.drawCount}` 抽 (上限 7 抽)\n")
            
            val nextCost = pokerUser.getNextDrawUpgradeCost()
            if (pokerUser.drawCount >= 7 || nextCost == null) {
                sb.append("• 升級花費：**已達最高等級** 👑\n")
            } else {
                sb.append("• 升級花費：**$nextCost** 分數\n")
            }
            return sb.toString()
        }
    }
}
