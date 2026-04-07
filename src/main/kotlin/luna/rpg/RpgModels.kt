package luna.rpg

import kotlin.math.max

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
    val spd: Int,
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
    val rebirthResourceLevel: Int = 0,
    val rebirthEfficientLevel: Int = 0,
    val currentMonster: Monster? = null,
    val progression: PlayerProgression = PlayerProgression(1, 0, true),
) {
    /**
     * 計算加成後的最終屬性
     */
    val effectiveAttributes: RpgAttributes
        get() {
            val atkBonus = 1.0 + (rebirthAtkLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL)
            val defBonus = 1.0 + (rebirthDefLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL)
            val spdBonus = 1.0 + (rebirthSpdLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL)
            val hpBonus = 1.0 + (rebirthHpLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL)

            return RpgAttributes(
                hp = attributes.hp, // Current HP doesn't get boosted, only maxHp
                maxHp = (attributes.maxHp * hpBonus).toInt(),
                atk = (attributes.atk * atkBonus).toInt(),
                def = (attributes.def * defBonus).toInt(),
                spd = (attributes.spd * spdBonus).toInt(),
            )
        }

    val currentFloor: Int get() = progression.currentFloor
    val roomsExplored: Int get() = progression.roomsExplored
    val autoAdvance: Boolean get() = progression.autoAdvance

    /**
     * 檢查是否可以重生
     */
    fun canRebirth(): Boolean = currentFloor >= RpgConfig.Rebirth.MIN_FLOOR

    /**
     * 計算物資獲得加成
     */
    fun calculateResourceBonus(): Double = 1.0 + (rebirthResourceLevel * RpgConfig.Rebirth.RESOURCE_BONUS_PER_LEVEL)

    /**
     * 計算升級效率加成 (成本減少)
     */
    fun calculateEfficiencyBonus(): Double = 1.0 - (rebirthEfficientLevel * RpgConfig.Rebirth.EFFICIENT_BONUS_PER_LEVEL)

    /**
     * 計算重生可獲得的點數
     */
    fun calculateEarnedPoints(): Int {
        if (!canRebirth()) return 0
        return ((currentFloor - RpgConfig.Rebirth.MIN_FLOOR) / RpgConfig.Rebirth.MILESTONE_INTERVAL + 1) *
            RpgConfig.Rebirth.POINTS_PER_MILESTONE
    }

    /**
     * 執行重生重置
     */
    fun rebirthReset(earnedPoints: Int): Player =
        this.copy(
            attributes = RpgConfig.Player.INITIAL_ATTRIBUTES,
            wood = RpgConfig.Player.INITIAL_RESOURCES,
            stone = RpgConfig.Player.INITIAL_RESOURCES,
            metal = RpgConfig.Player.INITIAL_RESOURCES,
            weaponLevel = 0,
            shieldLevel = 0,
            armorLevel = 0,
            recoveryLevel = 0,
            recoveryStartAt = 0L,
            rebirthCount = rebirthCount + 1,
            rebirthPoints = rebirthPoints + earnedPoints,
            progression = PlayerProgression(1, 0, autoAdvance),
            currentMonster = null,
        )

    /**
     * 計算康復所需時間 (秒)
     */
    fun calculateRecoveryCooldown(): Long {
        val base = effectiveAttributes.maxHp * RpgConfig.Recovery.BASE_SECONDS_PER_HP
        val reduction = recoveryLevel * RpgConfig.Upgrade.RECOVERY_REDUCTION_SECONDS
        val baseCooldown = max(RpgConfig.Recovery.MIN_SECONDS, base - reduction)

        // Apply rebirth bonus (percentage reduction)
        val rebirthBonus = 1.0 - (rebirthRecoveryLevel * RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL)
        return max(RpgConfig.Recovery.MIN_SECONDS, baseCooldown * rebirthBonus).toLong()
    }

    /**
     * 計算屬性升級成本
     */
    fun calculateStatUpgradeCost(currentLevel: Int): Int =
        RpgConfig.Rebirth.BASE_UPGRADE_COST + (currentLevel * RpgConfig.Rebirth.COST_INCREASE_PER_LEVEL)

    /**
     * 檢查是否可以升級特定屬性
     */
    fun canUpgradeStat(currentLevel: Int): Boolean {
        if (currentLevel >= RpgConfig.Rebirth.MAX_STAT_LEVEL) return false
        val cost = calculateStatUpgradeCost(currentLevel)
        return rebirthPoints >= cost
    }
}

/**
 * Represents the progression of a player.
 */
data class PlayerProgression(
    val currentFloor: Int,
    val roomsExplored: Int,
    val autoAdvance: Boolean,
)

/**
 * Result of updating player progression.
 */
data class UpdateProgressionResult(
    val finalRoomCount: Int,
    val message: String,
)

/**
 * Represents a monster in the RPG.
 *
 * @property name The name of the monster.
 * @property attributes The monster's RPG stats.
 */
data class Monster(
    val name: String,
    val attributes: RpgAttributes,
)
