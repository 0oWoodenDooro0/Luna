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

    object Rebirth {
        const val MIN_FLOOR = 50
        const val MILESTONE_INTERVAL = 10
        const val POINTS_PER_MILESTONE = 1
        const val BASE_UPGRADE_COST = 1
        const val COST_INCREASE_PER_LEVEL = 1
        const val MAX_STAT_LEVEL = 10
        const val STAT_BONUS_PER_LEVEL = 0.05
    }
}
