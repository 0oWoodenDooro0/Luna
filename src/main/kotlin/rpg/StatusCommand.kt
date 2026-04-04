package website.woodendoor.rpg

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.Command
import website.woodendoor.repository.PlayerRepository
import website.woodendoor.repository.PlayersTable

class StatusCommand : Command {
    override val name = "status"
    override val description = "查看你的角色屬性與資源"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val username = interaction.user.username

        PlayerRepository.restoreHpIfRecovered(userId)
        val player = PlayerRepository.getOrCreatePlayer(userId)

        val extraInfo = transaction {
            val row = PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()
            Pair(
                row[PlayersTable.currentFloor],
                row[PlayersTable.roomsExplored]
            )
        }

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
                    value = """
                        層數：第 ${extraInfo.first} 層
                        房間：${extraInfo.second} / ${RpgConfig.Exploration.FLOOR_SIZE}
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
            }
        }
    }
}
