package luna.rpg.repository

import luna.rpg.Monster
import luna.rpg.Player
import luna.rpg.PlayerProgression
import luna.rpg.RpgAttributes
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

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
    val recoveryLevel = integer("recovery_level").default(0)
    val recoveryStartAt = long("recovery_start_at").default(0L)
    val rebirthCount = integer("rebirth_count").default(0)
    val rebirthPoints = integer("rebirth_points").default(0)
    val rebirthAtkLevel = integer("rebirth_atk_level").default(0)
    val rebirthDefLevel = integer("rebirth_def_level").default(0)
    val rebirthSpdLevel = integer("rebirth_spd_level").default(0)
    val rebirthRecoveryLevel = integer("rebirth_recovery_level").default(0)
    val rebirthHpLevel = integer("rebirth_hp_level").default(0)
    val monsterName = varchar("monster_name", 64).nullable()
    val monsterHp = integer("monster_hp").default(0)
    val monsterMaxHp = integer("monster_max_hp").default(0)
    val monsterAtk = integer("monster_atk").default(0)
    val monsterDef = integer("monster_def").default(0)
    val monsterSpd = integer("monster_spd").default(0)

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
        armorLevel: Int = 0,
        recoveryLevel: Int = 0,
        recoveryStartAt: Long = 0L,
        rebirthCount: Int = 0,
        rebirthPoints: Int = 0,
        rebirthAtkLevel: Int = 0,
        rebirthDefLevel: Int = 0,
        rebirthSpdLevel: Int = 0,
        rebirthRecoveryLevel: Int = 0,
        rebirthHpLevel: Int = 0,
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
            it[this.recoveryLevel] = recoveryLevel
            it[this.recoveryStartAt] = recoveryStartAt
            it[this.rebirthCount] = rebirthCount
            it[this.rebirthPoints] = rebirthPoints
            it[this.rebirthAtkLevel] = rebirthAtkLevel
            it[this.rebirthDefLevel] = rebirthDefLevel
            it[this.rebirthSpdLevel] = rebirthSpdLevel
            it[this.rebirthRecoveryLevel] = rebirthRecoveryLevel
            it[this.rebirthHpLevel] = rebirthHpLevel
        }
    }

    fun fetchPlayer(id: String): Player? =
        selectAll()
            .where { PlayersTable.id eq id }
            .map { it.toPlayer() }
            .singleOrNull()

    private fun ResultRow.toPlayer(): Player {
        val attributes =
            RpgAttributes(
                hp = this[hp],
                maxHp = this[maxHp],
                atk = this[atk],
                def = this[def],
                spd = this[spd],
            )
        val monster =
            this[monsterName]?.let { name ->
                Monster(
                    name = name,
                    attributes =
                        RpgAttributes(
                            hp = this[monsterHp],
                            maxHp = this[monsterMaxHp],
                            atk = this[monsterAtk],
                            def = this[monsterDef],
                            spd = this[monsterSpd],
                        ),
                )
            }
        return Player(
            id = this[id],
            name = "Player",
            attributes = attributes,
            wood = this[wood],
            stone = this[stone],
            metal = this[metal],
            weaponLevel = this[weaponLevel],
            shieldLevel = this[shieldLevel],
            armorLevel = this[armorLevel],
            recoveryLevel = this[recoveryLevel],
            recoveryStartAt = this[recoveryStartAt],
            rebirthCount = this[rebirthCount],
            rebirthPoints = this[rebirthPoints],
            rebirthAtkLevel = this[rebirthAtkLevel],
            rebirthDefLevel = this[rebirthDefLevel],
            rebirthSpdLevel = this[rebirthSpdLevel],
            rebirthRecoveryLevel = this[rebirthRecoveryLevel],
            rebirthHpLevel = this[rebirthHpLevel],
            currentMonster = monster,
            progression =
                PlayerProgression(
                    currentFloor = this[currentFloor],
                    roomsExplored = this[roomsExplored],
                    autoAdvance = this[autoAdvance],
                ),
        )
    }
}
