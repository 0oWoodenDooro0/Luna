package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.string
import luna.core.Command
import luna.rpg.repository.PlayerRepository

class RebirthUpgradeCommand : Command {
    override val name = "rebirth_upgrade"
    override val description = "使用重生點數強化你的永久屬性"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            string("stat", "要強化的屬性") {
                required = true
                choice("攻擊力 (ATK%)", "atk")
                choice("防禦力 (DEF%)", "def")
                choice("速度 (SPD%)", "spd")
                choice("康復速度 (Recovery%)", "recovery")
                choice("最大血量 (HP%)", "hp")
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val stat = interaction.command.strings["stat"] ?: return
        
        val response = interaction.deferPublicResponse()
        
        val result = PlayerRepository.upgradeRebirthStat(userId, stat)
        
        when (result) {
            is PlayerRepository.RebirthUpgradeResult.Success -> {
                val player = result.player
                val newLevel = when (stat) {
                    "atk" -> player.rebirthAtkLevel
                    "def" -> player.rebirthDefLevel
                    "spd" -> player.rebirthSpdLevel
                    "recovery" -> player.rebirthRecoveryLevel
                    "hp" -> player.rebirthHpLevel
                    else -> 0
                }
                val statName = when (stat) {
                    "atk" -> "攻擊力"
                    "def" -> "防禦力"
                    "spd" -> "速度"
                    "recovery" -> "康復速度"
                    "hp" -> "最大血量"
                    else -> "屬性"
                }
                response.respond {
                    content = "✅ 強化成功！你的 **$statName** 已提升至 **Lv.$newLevel**！\n剩餘重生點數：**${player.rebirthPoints}**"
                }
            }
            is PlayerRepository.RebirthUpgradeResult.InsufficientPoints -> {
                response.respond {
                    content = "❌ 重生點數不足！升級需要 **${result.required}** 點，但你只有 **${result.current}** 點。"
                }
            }
            is PlayerRepository.RebirthUpgradeResult.MaxLevelReached -> {
                response.respond {
                    content = "❌ 該屬性已達到最高等級 (**Lv.${luna.rpg.RpgConfig.Rebirth.MAX_STAT_LEVEL}**)！"
                }
            }
            PlayerRepository.RebirthUpgradeResult.Error -> {
                response.respond {
                    content = "❌ 發生錯誤，無法進行強化。"
                }
            }
        }
    }
}
