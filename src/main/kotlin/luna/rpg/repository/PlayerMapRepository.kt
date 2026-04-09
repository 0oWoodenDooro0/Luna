package luna.rpg.repository

import luna.rpg.PlayerMap
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

object PlayerMapRepository {
    fun createMap(
        playerId: String,
        layer: Int,
        dropRate: Double,
        woodCost: Int = 0,
        stoneCost: Int = 0,
        metalCost: Int = 0,
    ): Int? {
        return transaction {
            // Deduct resources from player
            val currentResources =
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.id eq playerId }
                    .singleOrNull()

            if (currentResources == null) return@transaction null

            val currentWood = currentResources[PlayersTable.wood]
            val currentStone = currentResources[PlayersTable.stone]
            val currentMetal = currentResources[PlayersTable.metal]

            if (currentWood < woodCost || currentStone < stoneCost || currentMetal < metalCost) {
                return@transaction null
            }

            PlayersTable.update({ PlayersTable.id eq playerId }) {
                it[wood] = currentWood - woodCost
                it[stone] = currentStone - stoneCost
                it[metal] = currentMetal - metalCost
            }

            PlayerMapsTable.insertMap(playerId, layer, dropRate)
        }
    }

    fun getMaps(playerId: String): List<PlayerMap> =
        transaction {
            PlayerMapsTable.fetchMaps(playerId)
        }

    fun getActiveMap(playerId: String): PlayerMap? =
        transaction {
            PlayerMapsTable.fetchActiveMap(playerId)
        }

    fun setActiveMap(
        playerId: String,
        mapId: Int,
    ) {
        transaction {
            // Deactivate all maps for this player
            PlayerMapsTable.update({ PlayerMapsTable.playerId eq playerId }) {
                it[isActive] = false
            }
            // Activate the specific map
            PlayerMapsTable.update({ (PlayerMapsTable.playerId eq playerId) and (PlayerMapsTable.id eq mapId) }) {
                it[isActive] = true
            }
        }
    }

    fun updateProgress(
        mapId: Int,
        currentRoom: Int,
    ) {
        transaction {
            PlayerMapsTable.update({ PlayerMapsTable.id eq mapId }) {
                it[PlayerMapsTable.currentRoom] = currentRoom
            }
        }
    }

    fun deleteMap(
        playerId: String,
        mapId: Int,
    ) {
        transaction {
            PlayerMapsTable.deleteWhere { (PlayerMapsTable.playerId eq playerId) and (PlayerMapsTable.id eq mapId) }
        }
    }

    private fun saveMonsterStateInternal(
        mapId: Int,
        monster: luna.rpg.Monster?,
    ) {
        PlayerMapsTable.update({ PlayerMapsTable.id eq mapId }) {
            if (monster != null) {
                it[PlayerMapsTable.monsterName] = monster.name
                it[PlayerMapsTable.monsterHp] = monster.attributes.hp
                it[PlayerMapsTable.monsterMaxHp] = monster.attributes.maxHp
                it[PlayerMapsTable.monsterAtk] = monster.attributes.atk
                it[PlayerMapsTable.monsterDef] = monster.attributes.def
                it[PlayerMapsTable.monsterSpd] = monster.attributes.spd
            } else {
                it[PlayerMapsTable.monsterName] = null
                it[PlayerMapsTable.monsterHp] = 0
                it[PlayerMapsTable.monsterMaxHp] = 0
                it[PlayerMapsTable.monsterAtk] = 0
                it[PlayerMapsTable.monsterDef] = 0
                it[PlayerMapsTable.monsterSpd] = 0
            }
        }
    }

    fun saveMonsterState(
        mapId: Int,
        monster: luna.rpg.Monster?,
    ) {
        transaction {
            saveMonsterStateInternal(mapId, monster)
        }
    }

    fun recordCombatResult(
        playerId: String,
        mapId: Int,
        playerHP: Int,
        monsterHP: Int,
        monster: luna.rpg.Monster,
        reward: Pair<String, Int>? = null,
    ) {
        val won = monsterHP <= 0
        transaction {
            // Update player HP and recovery
            PlayersTable.update({ PlayersTable.id eq playerId }) {
                it[PlayersTable.hp] = playerHP
                if (!won) {
                    it[PlayersTable.recoveryStartAt] = System.currentTimeMillis()
                }

                if (won && reward != null) {
                    val current = PlayersTable.selectAll().where { PlayersTable.id eq playerId }.single()
                    val (resourceName, amount) = reward
                    when (resourceName) {
                        "🪵 木頭" -> it[PlayersTable.wood] = current[PlayersTable.wood] + amount
                        "🪨 石頭" -> it[PlayersTable.stone] = current[PlayersTable.stone] + amount
                        "🔗 金屬" -> it[PlayersTable.metal] = current[PlayersTable.metal] + amount
                    }
                }
            }

            // Update map monster state
            if (!won) {
                saveMonsterStateInternal(mapId, monster.copy(attributes = monster.attributes.copy(hp = monsterHP)))
            } else {
                saveMonsterStateInternal(mapId, null)
            }
        }
    }
}
