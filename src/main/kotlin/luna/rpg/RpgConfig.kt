package luna.rpg

import kotlin.math.max

object RpgConfig {
    object Exploration {
        const val FLOOR_SIZE = 5
        const val EVENT_ROLL_RESOURCE_THRESHOLD = 50
        const val RESOURCE_MIN_AMOUNT = 1
        const val RESOURCE_MAX_AMOUNT = 5
        val RESOURCE_NAMES = listOf("🪵 木頭", "🪨 石頭", "🔗 金屬")
        val MONSTER_NAMES = listOf("史萊姆", "哥布林", "小蝙蝠")
    }

    object Monster {
        const val BASE_HP = 20
        const val HP_PER_FLOOR = 5
        const val BASE_ATK = 5
        const val ATK_PER_FLOOR = 2
        const val BASE_DEF = 2
        const val DEF_PER_FLOOR = 1
        const val BASE_SPD = 3
        const val SPD_PER_FLOOR = 1
    }

    object Combat {
        const val MAX_TURNS = 20
    }

    object Economy {
        val UPGRADE_REQUIREMENTS = mapOf(
            "weapon" to mapOf("wood" to 10, "metal" to 5),
            "shield" to mapOf("stone" to 10, "metal" to 5),
            "armor" to mapOf("wood" to 5, "stone" to 10),
            "recovery" to mapOf("wood" to 10, "stone" to 10, "metal" to 10)
        )
        const val MONSTER_REWARD_BASE_AMOUNT = 2
        const val MONSTER_REWARD_SCALE_PER_FLOOR = 1
    }

    object Upgrade {
        const val WEAPON_ATK_BONUS = 5
        const val SHIELD_DEF_BONUS = 5
        const val ARMOR_HP_BONUS = 5
        const val RECOVERY_REDUCTION_SECONDS = 5.0
    }

    object Recovery {
        /**
         * 康復基礎冷卻時間 (每 HP 秒數)
         */
        const val BASE_SECONDS_PER_HP = 0.1

        /**
         * 最小康復時間 (秒)
         */
        const val MIN_SECONDS = 5.0

        /**
         * 計算康復所需時間 (秒)
         */
        fun calculateCooldown(maxHp: Int, recoveryLevel: Int): Long {
            val base = maxHp * BASE_SECONDS_PER_HP
            val reduction = recoveryLevel * Upgrade.RECOVERY_REDUCTION_SECONDS
            return max(MIN_SECONDS, base - reduction).toLong()
        }
    }

    data class RebirthConfig(
        val minFloor: Int = 50,
        val milestoneInterval: Int = 10,
        val pointsPerMilestone: Int = 1,
        val baseUpgradeCost: Int = 1,
        val costIncreasePerLevel: Int = 1,
        val maxStatLevel: Int = 10,
        val statBonusPerLevel: Double = 0.05
    ) {
        companion object {
            fun fromMap(data: Map<String, String>): RebirthConfig {
                return RebirthConfig(
                    minFloor = data["minFloor"]?.toInt() ?: 50,
                    milestoneInterval = data["milestoneInterval"]?.toInt() ?: 10,
                    pointsPerMilestone = data["pointsPerMilestone"]?.toInt() ?: 1,
                    baseUpgradeCost = data["baseUpgradeCost"]?.toInt() ?: 1,
                    costIncreasePerLevel = data["costIncreasePerLevel"]?.toInt() ?: 1,
                    maxStatLevel = data["maxStatLevel"]?.toInt() ?: 10,
                    statBonusPerLevel = data["statBonusPerLevel"]?.toDouble() ?: 0.05
                )
            }
        }
    }

    val Rebirth = RebirthConfig()
}
