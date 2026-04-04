package luna.rpg

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
    val armorLevel: Int = 0,
    val recoveryLevel: Int = 0,
    val recoveryStartAt: Long = 0L,
    val currentMonster: Monster? = null,
    val progression: PlayerProgression = PlayerProgression(1, 0, true)
) {
    /**
     * 計算加成後的最終屬性 (目前直接從資料庫讀取已加成的數值)
     */
    val effectiveAttributes: RpgAttributes
        get() = attributes

    val currentFloor: Int get() = progression.currentFloor
    val roomsExplored: Int get() = progression.roomsExplored
    val autoAdvance: Boolean get() = progression.autoAdvance
}

/**
 * Represents the progression of a player.
 */
data class PlayerProgression(
    val currentFloor: Int,
    val roomsExplored: Int,
    val autoAdvance: Boolean
)

/**
 * Result of updating player progression.
 */
data class UpdateProgressionResult(
    val finalRoomCount: Int,
    val message: String
)

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
