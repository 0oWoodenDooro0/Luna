package luna.rpg.repository

import luna.rpg.PlayerMap
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.deleteWhere

object PlayerMapRepository {
    fun createMap(
        playerId: String,
        layer: Int,
        dropRate: Double,
        woodCost: Int = 0,
        stoneCost: Int = 0,
        metalCost: Int = 0
    ): Int? {
        return transaction {
            // Deduct resources from player
            val currentResources = PlayersTable.selectAll()
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

    fun getMaps(playerId: String): List<PlayerMap> {
        return transaction {
            PlayerMapsTable.fetchMaps(playerId)
        }
    }

    fun getActiveMap(playerId: String): PlayerMap? {
        return transaction {
            PlayerMapsTable.fetchActiveMap(playerId)
        }
    }

    fun setActiveMap(playerId: String, mapId: Int) {
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

    fun updateProgress(mapId: Int, currentRoom: Int) {
        transaction {
            PlayerMapsTable.update({ PlayerMapsTable.id eq mapId }) {
                it[PlayerMapsTable.currentRoom] = currentRoom
            }
        }
    }

    fun deleteMap(playerId: String, mapId: Int) {
        transaction {
            PlayerMapsTable.deleteWhere { (PlayerMapsTable.playerId eq playerId) and (PlayerMapsTable.id eq mapId) }
        }
    }
}
