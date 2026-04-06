package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import luna.core.Command
import luna.rpg.RpgConfig
import luna.rpg.repository.PlayerRepository

class RebirthListCommand : Command {
    override val name = "rebirth_list"
    override val description = "查看所有重生強化項目的成本與效果"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val player = PlayerRepository.getOrCreatePlayer(userId)

        val response = interaction.deferPublicResponse()
        response.respond {
            embed {
                title = "✨ 重生強化清單"
                description = "目前的重生點數：**${player.rebirthPoints}**"

                val types =
                    listOf(
                        Triple("atk", "⚔️ 攻擊 (ATK%)", player.rebirthAtkLevel),
                        Triple("def", "🛡️ 防禦 (DEF%)", player.rebirthDefLevel),
                        Triple("spd", "⚡ 速度 (SPD%)", player.rebirthSpdLevel),
                        Triple("recovery", "❤️ 康復速度 (Recovery%)", player.rebirthRecoveryLevel),
                        Triple("hp", "❤️ 最大血量 (HP%)", player.rebirthHpLevel),
                    )

                for ((key, displayName, level) in types) {
                    val cost =
                        if (level >= RpgConfig.Rebirth.MAX_STAT_LEVEL) {
                            -1
                        } else {
                            player.calculateStatUpgradeCost(level)
                        }

                    val statusIcon =
                        if (cost == -1) {
                            "💎"
                        } else if (player.rebirthPoints >= cost) {
                            "✅"
                        } else {
                            "❌"
                        }

                    val costText =
                        if (cost == -1) {
                            "已達上限 (MAX)"
                        } else {
                            "**$cost** 重生點數"
                        }

                    val bonusPerLevel = (RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL * 100).toInt()
                    val currentBonus = level * bonusPerLevel
                    val nextBonus = currentBonus + bonusPerLevel

                    field {
                        name = "$displayName (Lv.$level -> Lv.${if (cost == -1) level else level + 1})"
                        value =
                            """
                            效果：+$currentBonus% -> **+$nextBonus%**
                            成本：$statusIcon $costText
                            """.trimIndent()
                        inline = false
                    }
                }

                footer {
                    text = "使用 /rebirth_upgrade [屬性] 來進行強化"
                }
                color = dev.kord.common.Color(0x9B59B6)
            }
        }
    }
}
