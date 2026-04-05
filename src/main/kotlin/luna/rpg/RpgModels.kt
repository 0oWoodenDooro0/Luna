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
    val rebirthCount: Int = 0,
    val rebirthPoints: Int = 0,
    val rebirthAtkLevel: Int = 0,
    val rebirthDefLevel: Int = 0,
    val rebirthSpdLevel: Int = 0,
    val rebirthRecoveryLevel: Int = 0,
    val rebirthHpLevel: Int = 0,
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

    /**
     * 檢查是否可以重生
     */
    fun canRebirth(): Boolean {
        return currentFloor >= RpgConfig.Rebirth.MIN_FLOOR
    }

    /**
     * 計算重生可獲得的點數
     */
    fun calculateEarnedPoints(): Int {
        if (!canRebirth()) return 0
        return (currentFloor - RpgConfig.Rebirth.MIN_FLOOR) / RpgConfig.Rebirth.MILESTONE_INTERVAL * RpgConfig.Rebirth.POINTS_PER_MILESTONE
    }

    /**
     * 執行重生重置
     */
    fun rebirthReset(earnedPoints: Int): Player {
        return this.copy(
            attributes = RpgAttributes(
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8
            ),
            wood = 0,
            stone = 0,
            metal = 0,
            weaponLevel = 0,
            shieldLevel = 0,
            armorLevel = 0,
            recoveryLevel = 0,
            recoveryStartAt = 0L,
            rebirthCount = rebirthCount + 1,
            rebirthPoints = rebirthPoints + earnedPoints,
            progression = PlayerProgression(1, 0, autoAdvance),
            currentMonster = null
        )
    }
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
