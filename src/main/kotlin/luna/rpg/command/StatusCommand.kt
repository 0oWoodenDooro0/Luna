package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import luna.core.Command
import luna.rpg.*
import luna.rpg.repository.PlayerRepository

class StatusCommand : Command {
    override val name = "status"
    override val description = "查看你的角色屬性與資源"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val username = interaction.user.username

        val player = PlayerRepository.restoreHpIfRecovered(userId) ?: PlayerRepository.getOrCreatePlayer(userId)

        val effective = player.effectiveAttributes

        val response = interaction.deferPublicResponse()
        response.respond {
            embed {
                title = "$username 的角色狀態"
                field {
                    name = "基礎屬性 (含加成)"
                    value = """
                        ❤️ 血量 (HP): ${effective.hp} / ${effective.maxHp}
                        ⚔️ 攻擊 (ATK): ${effective.atk}
                        🛡️ 防禦 (DEF): ${effective.def}
                        ⚡ 速度 (SPD): ${effective.spd}
                    """.trimIndent()
                    inline = true
                }
                
                val remaining = PlayerRepository.getRemainingRecoveryTime(player)
                if (remaining > 0) {
                    field {
                        name = "❤️ 康復中"
                        value = "剩餘時間: $remaining 秒"
                        inline = true
                    }
                }

                field {
                    name = "目前進度"
                    val nextMilestone = if (player.currentFloor < RpgConfig.Rebirth.MIN_FLOOR) {
                        RpgConfig.Rebirth.MIN_FLOOR
                    } else {
                        ((player.currentFloor - RpgConfig.Rebirth.MIN_FLOOR) / RpgConfig.Rebirth.MILESTONE_INTERVAL + 1) * RpgConfig.Rebirth.MILESTONE_INTERVAL + RpgConfig.Rebirth.MIN_FLOOR
                    }
                    val floorsLeft = nextMilestone - player.currentFloor
                    val milestoneType = if (player.currentFloor < RpgConfig.Rebirth.MIN_FLOOR) "解鎖重生" else "下一個重生點數"
                    
                    value = """
                        層數：第 ${player.currentFloor} 層
                        房間：${player.roomsExplored} / ${RpgConfig.Exploration.FLOOR_SIZE}
                        進度：距離 $milestoneType 還差 $floorsLeft 層
                    """.trimIndent()
                    inline = true
                }
                field {
                    name = "擁有資源"
                    value = """
                        🪵 木頭: ${player.wood}
                        🪨 石頭: ${player.stone}
                        🔗 金屬: ${player.metal}
                    """.trimIndent()
                    inline = false
                }
                field {
                    name = "裝備等級"
                    value = """
                        ⚔️ 武器 (Weapon): Lv.${player.weaponLevel}
                        🛡️ 盾牌 (Shield): Lv.${player.shieldLevel}
                        👕 護甲 (Armor): Lv.${player.armorLevel}
                        ❤️ 康復速度 (Recovery): Lv.${player.recoveryLevel}
                    """.trimIndent()
                    inline = false
                }

                if (player.rebirthCount > 0 || player.rebirthPoints > 0) {
                    field {
                        name = "✨ 重生狀態 (Rebirth)"
                        value = """
                            重生次數：${player.rebirthCount}
                            重生點數：${player.rebirthPoints}
                            
                            永久強化：
                            ⚔️ ATK: +${(player.rebirthAtkLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL * 100).toInt()}% (Lv.${player.rebirthAtkLevel})
                            🛡️ DEF: +${(player.rebirthDefLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL * 100).toInt()}% (Lv.${player.rebirthDefLevel})
                            ⚡ SPD: +${(player.rebirthSpdLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL * 100).toInt()}% (Lv.${player.rebirthSpdLevel})
                            ❤️ REC: +${(player.rebirthRecoveryLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL * 100).toInt()}% (Lv.${player.rebirthRecoveryLevel})
                            HP: +${(player.rebirthHpLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL * 100).toInt()}% (Lv.${player.rebirthHpLevel})
                        """.trimIndent()
                        inline = false
                    }
                }
            }
        }
    }
}
