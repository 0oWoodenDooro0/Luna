package website.woodendoor.rpg

import kotlin.math.max

object RpgConfig {
    object Exploration {
        const val FLOOR_SIZE = 5
        const val EVENT_ROLL_RESOURCE_THRESHOLD = 50
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
        const val EQUIPMENT_BONUS_PER_LEVEL = 5
    }

    object Recovery {
        /**
         * 康復基礎冷卻時間 (每 HP 秒數)
         */
        const val BASE_SECONDS_PER_HP = 0.1

        /**
         * 康復速度升級每次減少的冷卻時間 (秒)
         */
        const val UPGRADE_REDUCTION_SECONDS = 5.0

        /**
         * 最小康復時間 (秒)
         */
        const val MIN_SECONDS = 5.0

        /**
         * 計算康復所需時間 (秒)
         */
        fun calculateCooldown(maxHp: Int, recoveryLevel: Int): Long {
            val base = maxHp * BASE_SECONDS_PER_HP
            val reduction = recoveryLevel * UPGRADE_REDUCTION_SECONDS
            return max(MIN_SECONDS, base - reduction).toLong()
        }
    }

    // --- Deprecated / Legacy Support (to be removed in Task 2) ---
    @Deprecated("Use Exploration.FLOOR_SIZE", ReplaceWith("Exploration.FLOOR_SIZE"))
    const val FLOOR_SIZE = Exploration.FLOOR_SIZE

    @Deprecated("Use Recovery.calculateCooldown", ReplaceWith("Recovery.calculateCooldown(maxHp, recoveryLevel)"))
    fun calculateRecoveryCooldown(maxHp: Int, recoveryLevel: Int) = Recovery.calculateCooldown(maxHp, recoveryLevel)

    @Deprecated("Use Economy.UPGRADE_REQUIREMENTS", ReplaceWith("Economy.UPGRADE_REQUIREMENTS"))
    val UPGRADE_REQUIREMENTS = Economy.UPGRADE_REQUIREMENTS

    @Deprecated("Use Economy.EQUIPMENT_BONUS_PER_LEVEL", ReplaceWith("Economy.EQUIPMENT_BONUS_PER_LEVEL"))
    const val EQUIPMENT_BONUS_PER_LEVEL = Economy.EQUIPMENT_BONUS_PER_LEVEL
}
