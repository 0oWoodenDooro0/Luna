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
    ): Int {
        return transaction {
            // Deduct resources from player
            val currentResources = PlayersTable.selectAll()
                .where { PlayersTable.id eq playerId }
                .singleOrNull()

            if (currentResources != null) {
                PlayersTable.update({ PlayersTable.id eq playerId }) {
                    it[wood] = currentResources[PlayersTable.wood] - woodCost
                    it[stone] = currentResources[PlayersTable.stone] - stoneCost
                    it[metal] = currentResources[PlayersTable.metal] - metalCost
                }
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
