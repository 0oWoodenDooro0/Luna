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
                        Triple("resource", "🪵 物資豐富 (Resourceful%)", player.rebirthResourceLevel),
                        Triple("efficient", "🛠️ 升級效率 (Efficient%)", player.rebirthEfficientLevel),
                    )

                for ((key, displayName, level) in types) {
                    val maxLevel =
                        when (key) {
                            "resource" -> RpgConfig.Rebirth.MAX_RESOURCE_LEVEL
                            "efficient" -> RpgConfig.Rebirth.MAX_EFFICIENT_LEVEL
                            else -> RpgConfig.Rebirth.MAX_STAT_LEVEL
                        }

                    val cost =
                        if (level >= maxLevel) {
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

                    val bonusPerLevelPct =
                        when (key) {
                            "resource" -> (RpgConfig.Rebirth.RESOURCE_BONUS_PER_LEVEL * 100).toInt()
                            "efficient" -> (RpgConfig.Rebirth.EFFICIENT_BONUS_PER_LEVEL * 100).toInt()
                            else -> (RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL * 100).toInt()
                        }

                    val currentBonus = level * bonusPerLevelPct
                    val nextBonus = currentBonus + bonusPerLevelPct

                    val effectDesc =
                        when (key) {
                            "efficient" -> "成本減少：-$currentBonus% -> **-$nextBonus%**"
                            else -> "效果：+$currentBonus% -> **+$nextBonus%**"
                        }

                    field {
                        name = "$displayName (Lv.$level -> Lv.${if (cost == -1) level else level + 1})"
                        value =
                            """
                            $effectDesc
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
