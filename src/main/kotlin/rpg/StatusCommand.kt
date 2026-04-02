package website.woodendoor.rpg

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.Command

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

        val player = transaction {
            val existing = PlayersTable.fetchPlayer(userId)
            if (existing == null) {
                // Initialize new player if not found
                PlayersTable.insertPlayer(
                    id = userId,
                    hp = 100,
                    maxHp = 100,
                    atk = 10,
                    def = 5,
                    spd = 8,
                    wood = 0,
                    stone = 0,
                    metal = 0,
                    floor = 1
                )
                PlayersTable.fetchPlayer(userId)
            } else {
                existing
            }
        }

        if (player == null) {
            val response = interaction.deferEphemeralResponse()
            response.respond { content = "無法讀取或初始化你的角色資料。" }
            return
        }

        val resources = transaction {
            val row = PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()
            mapOf(
                "🪵 木頭" to row[PlayersTable.wood],
                "🪨 石頭" to row[PlayersTable.stone],
                "🔗 金屬" to row[PlayersTable.metal]
            )
        }
        
        val floor = transaction {
            PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()[PlayersTable.currentFloor]
        }

        val response = interaction.deferPublicResponse()
        response.respond {
            embed {
                title = "$username 的角色狀態"
                field {
                    name = "基礎屬性"
                    value = """
                        ❤️ 血量 (HP): ${player.attributes.hp} / ${player.attributes.maxHp}
                        ⚔️ 攻擊 (ATK): ${player.attributes.atk}
                        🛡️ 防禦 (DEF): ${player.attributes.def}
                        ⚡ 速度 (SPD): ${player.attributes.spd}
                    """.trimIndent()
                    inline = true
                }
                field {
                    name = "目前層數"
                    value = "第 $floor 層"
                    inline = true
                }
                field {
                    name = "擁有資源"
                    value = resources.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    inline = false
                }
            }
        }
    }
}
