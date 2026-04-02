package website.woodendoor.rpg

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.Command
import website.woodendoor.repository.PlayersTable
import kotlin.math.max
import kotlin.random.Random

class ExploreCommand : Command {
    override val name = "explore"
    override val description = "在目前的樓層進行探索"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val username = interaction.user.username

        val player = transaction {
            PlayersTable.fetchPlayer(userId) ?: run {
                PlayersTable.insertPlayer(userId, 100, 100, 10, 5, 8, 0, 0, 0, 1)
                PlayersTable.fetchPlayer(userId)!!
            }
        }

        val eventRoll = Random.nextInt(100)
        
        if (eventRoll < 50) {
            val response = interaction.deferPublicResponse()
            val resources = listOf("🪵 木頭", "🪨 石頭", "🔗 金屬")
            val foundResource = resources.random()
            val amount = Random.nextInt(1, 6)

            transaction {
                val current = PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()
                val newAmount = when (foundResource) {
                    "🪵 木頭" -> current[PlayersTable.wood] + amount
                    "🪨 石頭" -> current[PlayersTable.stone] + amount
                    "🔗 金屬" -> current[PlayersTable.metal] + amount
                    else -> 0
                }

                PlayersTable.update({ PlayersTable.id eq userId }) {
                    when (foundResource) {
                        "🪵 木頭" -> it[wood] = newAmount
                        "🪨 石頭" -> it[stone] = newAmount
                        "🔗 金屬" -> it[metal] = newAmount
                    }
                }
            }

            response.respond {
                embed {
                    title = "探索結果：發現資源！"
                    description = "$username 在探索中發現了 $foundResource x $amount！"
                    color = dev.kord.common.Color(0x00FF00) // Green
                }
            }
        } else {
            val response = interaction.deferPublicResponse()
            val monsterName = listOf("史萊姆", "哥布林", "小蝙蝠").random()
            val floor = transaction {
                PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()[PlayersTable.currentFloor]
            }
            
            val monsterAttr = RpgAttributes(
                hp = 20 + (floor * 5),
                maxHp = 20 + (floor * 5),
                atk = 5 + (floor * 2),
                def = 2 + floor,
                spd = 3 + floor
            )
            val monster = Monster(monsterName, monsterAttr)

            val combatLog = mutableListOf<String>()
            var playerHP = player.attributes.hp
            var monsterHP = monster.attributes.hp

            combatLog.add("⚔️ 遭遇了 $monsterName (HP: $monsterHP)！")

            val entities = if (player.attributes.spd >= monster.attributes.spd) {
                listOf("Player", "Monster")
            } else {
                listOf("Monster", "Player")
            }

            var turn = 1
            while (playerHP > 0 && monsterHP > 0 && turn <= 20) {
                for (entity in entities) {
                    if (entity == "Player") {
                        val dmg = max(1, player.attributes.atk - monster.attributes.def)
                        monsterHP -= dmg
                        combatLog.add("回合 $turn: $username 攻擊 $monsterName，造成 $dmg 傷害！($monsterName HP: ${max(0, monsterHP)})")
                        if (monsterHP <= 0) break
                    } else {
                        val dmg = max(1, monster.attributes.atk - player.attributes.def)
                        playerHP -= dmg
                        combatLog.add("回合 $turn: $monsterName 攻擊 $username，造成 $dmg 傷害！($username HP: ${max(0, playerHP)})")
                        if (playerHP <= 0) break
                    }
                }
                turn++
            }

            val won = monsterHP <= 0
            if (won) {
                val autoAdvance = transaction {
                    PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()[PlayersTable.autoAdvance]
                }

                if (autoAdvance) {
                    combatLog.add("🏆 勝利！你擊敗了 $monsterName，前往下一層！")
                    transaction {
                        PlayersTable.update({ PlayersTable.id eq userId }) {
                            it[hp] = playerHP
                            it[currentFloor] = floor + 1
                        }
                    }
                } else {
                    combatLog.add("🏆 勝利！你擊敗了 $monsterName。你可以手動使用 /next_floor 前往下一層。")
                    transaction {
                        PlayersTable.update({ PlayersTable.id eq userId }) {
                            it[hp] = playerHP
                        }
                    }
                }
            } else {

                combatLog.add("💀 戰敗... $monsterName 擊敗了你。")
                transaction {
                    PlayersTable.update({ PlayersTable.id eq userId }) {
                        it[hp] = 0 // Player died
                    }
                }
            }

            response.respond {
                embed {
                    title = if (won) "探索結果：戰鬥勝利！" else "探索結果：戰鬥失敗"
                    description = combatLog.joinToString("\n")
                    color = if (won) dev.kord.common.Color(0x00FF00) else dev.kord.common.Color(0xFF0000)
                }
            }
        }
    }
}
