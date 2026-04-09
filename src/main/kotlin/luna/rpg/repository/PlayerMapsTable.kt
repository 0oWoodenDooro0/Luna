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
    val monsterName = varchar("monster_name", 64).nullable()
    val monsterHp = integer("monster_hp").default(0)
    val monsterMaxHp = integer("monster_max_hp").default(0)
    val monsterAtk = integer("monster_atk").default(0)
    val monsterDef = integer("monster_def").default(0)
    val monsterSpd = integer("monster_spd").default(0)

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

    private fun ResultRow.toPlayerMap(): PlayerMap {
        val monster =
            this[monsterName]?.let { name ->
                luna.rpg.Monster(
                    name = name,
                    attributes =
                        luna.rpg.RpgAttributes(
                            hp = this[monsterHp],
                            maxHp = this[monsterMaxHp],
                            atk = this[monsterAtk],
                            def = this[monsterDef],
                            spd = this[monsterSpd],
                        ),
                )
            }
        return PlayerMap(
            id = this[id],
            playerId = this[playerId],
            layer = this[layer],
            dropRate = this[dropRate],
            rooms = this[rooms],
            currentRoom = this[currentRoom],
            isActive = this[isActive],
            currentMonster = monster,
        )
    }
}
