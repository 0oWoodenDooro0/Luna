package luna.rpg.repository

import luna.rpg.PlayerMap
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

object PlayerMapsTable : Table("player_maps") {
    val id = integer("id").autoIncrement()
    val playerId = varchar("player_id", 64)
    val layer = integer("layer")
    val dropRate = double("drop_rate")
    val rooms = integer("rooms").default(20)
    val currentRoom = integer("current_room").default(0)
    val isActive = bool("is_active").default(false)

    override val primaryKey = PrimaryKey(id)

    fun insertMap(
        playerId: String,
        layer: Int,
        dropRate: Double,
        rooms: Int = 20,
        currentRoom: Int = 0,
        isActive: Boolean = false,
    ): Int =
        insert {
            it[this.playerId] = playerId
            it[this.layer] = layer
            it[this.dropRate] = dropRate
            it[this.rooms] = rooms
            it[this.currentRoom] = currentRoom
            it[this.isActive] = isActive
        } get id

    fun fetchMaps(playerId: String): List<PlayerMap> =
        selectAll()
            .where { PlayerMapsTable.playerId eq playerId }
            .map { it.toPlayerMap() }

    fun fetchActiveMap(playerId: String): PlayerMap? =
        selectAll()
            .where { (PlayerMapsTable.playerId eq playerId) and (isActive eq true) }
            .map { it.toPlayerMap() }
            .singleOrNull()

    private fun ResultRow.toPlayerMap(): PlayerMap =
        PlayerMap(
            id = this[id],
            playerId = this[playerId],
            layer = this[layer],
            dropRate = this[dropRate],
            rooms = this[rooms],
            currentRoom = this[currentRoom],
            isActive = this[isActive],
        )
}
