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
    val autoAdvance = bool("auto_advance").default(true)
    val roomsExplored = integer("rooms_explored").default(0)
    val weaponLevel = integer("weapon_level").default(0)
    val shieldLevel = integer("shield_level").default(0)
    val armorLevel = integer("armor_level").default(0)

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
        floor: Int,
        autoAdvance: Boolean = true,
        roomsExplored: Int = 0,
        weaponLevel: Int = 0,
        shieldLevel: Int = 0,
        armorLevel: Int = 0
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
            it[this.autoAdvance] = autoAdvance
            it[this.roomsExplored] = roomsExplored
            it[this.weaponLevel] = weaponLevel
            it[this.shieldLevel] = shieldLevel
            it[this.armorLevel] = armorLevel
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
            attributes = attributes,
            weaponLevel = this[weaponLevel],
            shieldLevel = this[shieldLevel],
            armorLevel = this[armorLevel]
        )
    }
}
