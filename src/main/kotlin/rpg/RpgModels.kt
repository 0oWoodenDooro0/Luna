package website.woodendoor.rpg

/**
 * Represents the core attributes of an entity in the RPG.
 *
 * @property hp Current Health Points.
 * @property maxHp Maximum Health Points.
 * @property atk Attack power.
 * @property def Defense power.
 * @property spd Speed (determines turn order).
 */
data class RpgAttributes(
    val hp: Int,
    val maxHp: Int,
    val atk: Int,
    val def: Int,
    val spd: Int
)

/**
 * Represents a player in the RPG.
 *
 * @property id The unique Discord ID of the player.
 * @property name The display name of the player.
 * @property attributes The player's RPG stats.
 */
data class Player(
    val id: String,
    val name: String,
    val attributes: RpgAttributes,
    val wood: Int = 0,
    val stone: Int = 0,
    val metal: Int = 0,
    val weaponLevel: Int = 0,
    val shieldLevel: Int = 0,
    val armorLevel: Int = 0
) {
    /**
     * 計算加成後的最終屬性
     */
    val effectiveAttributes: RpgAttributes
        get() = RpgAttributes(
            maxHp = attributes.maxHp + armorLevel * RpgConfig.EQUIPMENT_BONUS_PER_LEVEL,
            hp = attributes.hp + armorLevel * RpgConfig.EQUIPMENT_BONUS_PER_LEVEL, // 同步增加當前血量
            atk = attributes.atk + weaponLevel * RpgConfig.EQUIPMENT_BONUS_PER_LEVEL,
            def = attributes.def + shieldLevel * RpgConfig.EQUIPMENT_BONUS_PER_LEVEL,
            spd = attributes.spd
        )
}

/**
 * Represents a monster in the RPG.
 *
 * @property name The name of the monster.
 * @property attributes The monster's RPG stats.
 */
data class Monster(
    val name: String,
    val attributes: RpgAttributes
)
