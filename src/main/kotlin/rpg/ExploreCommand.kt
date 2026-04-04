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
import website.woodendoor.repository.PlayerRepository
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

        PlayerRepository.restoreHpIfRecovered(userId)
        val player = PlayerRepository.getOrCreatePlayer(userId)
        
        val remaining = PlayerRepository.getRemainingRecoveryTime(player)
        if (remaining > 0) {
            interaction.deferPublicResponse().respond {
                embed {
                    title = "探索失敗"
                    description = "❤️ 你正在康復中... 剩餘時間: $remaining 秒。"
                    color = dev.kord.common.Color(0xFF0000)
                }
            }
            return
        }

        val floorInfo = transaction {
            val row = PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()
            Pair(row[PlayersTable.currentFloor], row[PlayersTable.roomsExplored])
        }

        val eventRoll = Random.nextInt(100)
        
        if (eventRoll < 50) {
            val resources = listOf("🪵 木頭", "🪨 石頭", "🔗 金屬")
            val foundResource = resources.random()
            val amount = Random.nextInt(1, 6)

            val (newRoomCount, floorMsg) = updateProgression(userId, floorInfo)

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

            interaction.deferPublicResponse().respond {
                embed {
                    title = "探索結果：發現資源！"
                    description = """
                        $username 在第 ${floorInfo.first} 層探索中發現了 $foundResource x $amount！
                        
                        進度：$newRoomCount / ${RpgConfig.FLOOR_SIZE} 房間
                        $floorMsg
                    """.trimIndent()
                    color = dev.kord.common.Color(0x00FF00)
                }
            }
        } else {
            val monsterName = listOf("史萊姆", "哥布林", "小蝙蝠").random()
            val floor = floorInfo.first
            
            val monsterAttr = RpgAttributes(
                hp = 20 + (floor * 5),
                maxHp = 20 + (floor * 5),
                atk = 5 + (floor * 2),
                def = 2 + floor,
                spd = 3 + floor
            )
            val monster = Monster(monsterName, monsterAttr)

            val combatLog = mutableListOf<String>()
            val effective = player.effectiveAttributes
            var playerHP = effective.hp
            var monsterHP = monster.attributes.hp

            combatLog.add("⚔️ 遭遇了 $monsterName (HP: $monsterHP)！")

            val entities = if (effective.spd >= monster.attributes.spd) {
                listOf("Player", "Monster")
            } else {
                listOf("Monster", "Player")
            }

            var turn = 1
            while (playerHP > 0 && monsterHP > 0 && turn <= 20) {
                for (entity in entities) {
                    if (entity == "Player") {
                        val dmg = max(1, effective.atk - monster.attributes.def)
                        monsterHP -= dmg
                        combatLog.add("回合 $turn: $username 攻擊 $monsterName，造成 $dmg 傷害！($monsterName HP: ${max(0, monsterHP)})")
                        if (monsterHP <= 0) break
                    } else {
                        val dmg = max(1, monster.attributes.atk - effective.def)
                        playerHP -= dmg
                        combatLog.add("回合 $turn: $monsterName 攻擊 $username，造成 $dmg 傷害！($username HP: ${max(0, playerHP)})")
                        if (playerHP <= 0) break
                    }
                }
                turn++
            }

            val won = monsterHP <= 0
            val (newRoomCount, floorMsg) = if (won) {
                updateProgression(userId, floorInfo)
            } else {
                floorInfo.second to ""
            }

            PlayerRepository.recordCombatResult(userId, if (won) playerHP else 0, max(0, monsterHP), monster)

            interaction.deferPublicResponse().respond {
                embed {
                    title = if (won) "探索結果：戰鬥勝利！" else "探索結果：戰鬥失敗"
                    description = """
                        ${combatLog.joinToString("\n")}
                        
                        ${if (won) "✨ 你擊敗了 $monsterName！" else "💀 你被打敗了... 但你設法在同一個房間裡甦醒。"}
                        
                        進度：$newRoomCount / ${RpgConfig.FLOOR_SIZE} 房間
                        $floorMsg
                    """.trimIndent()
                    color = if (won) dev.kord.common.Color(0x00FF00) else dev.kord.common.Color(0xFF0000)
                }
            }
        }
    }

    private fun updateProgression(userId: String, floorInfo: Pair<Int, Int>): Pair<Int, String> {
        val currentFloor = floorInfo.first
        val roomsExplored = floorInfo.second
        val floorSize = RpgConfig.FLOOR_SIZE

        val nextRoomCount = roomsExplored + 1
        var message = ""
        var finalRoomCount = nextRoomCount

        transaction {
            val autoAdvance = PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()[PlayersTable.autoAdvance]
            
            if (nextRoomCount >= floorSize) {
                if (autoAdvance) {
                    PlayersTable.update({ PlayersTable.id eq userId }) {
                        it[PlayersTable.currentFloor] = currentFloor + 1
                        it[this.roomsExplored] = 0
                    }
                    message = "✨ 此層已探索完成！自動前往第 ${currentFloor + 1} 層。"
                    finalRoomCount = 0
                } else {
                    PlayersTable.update({ PlayersTable.id eq userId }) {
                        it[this.roomsExplored] = 0
                    }
                    message = "📍 此層已探索完成！保留在第 $currentFloor 層農資源。"
                    finalRoomCount = 0
                }
            } else {
                PlayersTable.update({ PlayersTable.id eq userId }) {
                    it[this.roomsExplored] = nextRoomCount
                }
            }
        }
        return finalRoomCount to message
    }
}
