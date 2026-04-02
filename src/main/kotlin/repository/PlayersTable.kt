package website.woodendoor.repository

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.core.eq
import website.woodendoor.rpg.Player
import website.woodendoor.rpg.RpgAttributes

object PlayersTable : Table("players") {
    val id = varchar("id", 64)
    val hp = integer("hp")
    val maxHp = integer("max_hp")
    val atk = integer("atk")
    val def = integer("def")
    val spd = integer("spd")
    val wood = integer("wood")
    val stone = integer("stone")
    val metal = integer("metal")
    val currentFloor = integer("current_floor")

    override val primaryKey = PrimaryKey(id)

    fun insertPlayer(
        id: String,
        hp: Int,
        maxHp: Int,
        atk: Int,
        def: Int,
        spd: Int,
        wood: Int,
        stone: Int,
        metal: Int,
        floor: Int
    ) {
        insert {
            it[this.id] = id
            it[this.hp] = hp
            it[this.maxHp] = maxHp
            it[this.atk] = atk
            it[this.def] = def
            it[this.spd] = spd
            it[this.wood] = wood
            it[this.stone] = stone
            it[this.metal] = metal
            it[this.currentFloor] = floor
        }
    }

    fun fetchPlayer(id: String): Player? {
        return selectAll().where { PlayersTable.id eq id }
            .map { it.toPlayer() }
            .singleOrNull()
    }

    private fun ResultRow.toPlayer(): Player {
        val attributes = RpgAttributes(
            hp = this[hp],
            maxHp = this[maxHp],
            atk = this[atk],
            def = this[def],
            spd = this[spd]
        )
        return Player(
            id = this[id],
            name = "Player",
            attributes = attributes
        )
    }
}
