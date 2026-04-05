package luna.rpg

import java.io.File
import java.util.*
import kotlin.math.max

object RpgConfig {
    private val props = Properties().apply {
        val configFile = File("rpg.properties")
        if (configFile.exists()) {
            configFile.inputStream().use { load(it) }
        }
    }

    object Exploration {
        val FLOOR_SIZE = props.getProperty("exploration.floorSize", "5").toInt()
        val EVENT_ROLL_RESOURCE_THRESHOLD = props.getProperty("exploration.eventRollResourceThreshold", "50").toInt()
        val RESOURCE_MIN_AMOUNT = props.getProperty("exploration.resourceMinAmount", "1").toInt()
        val RESOURCE_MAX_AMOUNT = props.getProperty("exploration.resourceMaxAmount", "5").toInt()
        val RESOURCE_NAMES = listOf("🪵 木頭", "🪨 石頭", "🔗 金屬")
        val MONSTER_NAMES = listOf("史萊姆", "哥布林", "小蝙蝠")
    }

    object Monster {
        val BASE_HP = props.getProperty("monster.baseHp", "20").toInt()
        val HP_PER_FLOOR = props.getProperty("monster.hpPerFloor", "5").toInt()
        val BASE_ATK = props.getProperty("monster.baseAtk", "5").toInt()
        val ATK_PER_FLOOR = props.getProperty("monster.atkPerFloor", "2").toInt()
        val BASE_DEF = props.getProperty("monster.baseDef", "2").toInt()
        val DEF_PER_FLOOR = props.getProperty("monster.defPerFloor", "1").toInt()
        val BASE_SPD = props.getProperty("monster.baseSpd", "3").toInt()
        val SPD_PER_FLOOR = props.getProperty("monster.spdPerFloor", "1").toInt()
    }

    object Combat {
        val MAX_TURNS = props.getProperty("combat.maxTurns", "20").toInt()
    }

    object Economy {
        val UPGRADE_REQUIREMENTS = mapOf(
            "weapon" to mapOf("wood" to 10, "metal" to 5),
            "shield" to mapOf("stone" to 10, "metal" to 5),
            "armor" to mapOf("wood" to 5, "stone" to 10),
            "recovery" to mapOf("wood" to 10, "stone" to 10, "metal" to 10)
        )
        val MONSTER_REWARD_BASE_AMOUNT = props.getProperty("economy.monsterRewardBaseAmount", "2").toInt()
        val MONSTER_REWARD_SCALE_PER_FLOOR = props.getProperty("economy.monsterRewardScalePerFloor", "1").toInt()
    }

    object Upgrade {
        val WEAPON_ATK_BONUS = props.getProperty("upgrade.weaponAtkBonus", "5").toInt()
        val SHIELD_DEF_BONUS = props.getProperty("upgrade.shieldDefBonus", "5").toInt()
        val ARMOR_HP_BONUS = props.getProperty("upgrade.armorHpBonus", "5").toInt()
        val RECOVERY_REDUCTION_SECONDS = props.getProperty("upgrade.recoveryReductionSeconds", "5.0").toDouble()
    }

    object Recovery {
        /**
         * 康復基礎冷卻時間 (每 HP 秒數)
         */
        val BASE_SECONDS_PER_HP = props.getProperty("recovery.baseSecondsPerHp", "0.1").toDouble()

        /**
         * 最小康復時間 (秒)
         */
        val MIN_SECONDS = props.getProperty("recovery.minSeconds", "5.0").toDouble()

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
        val minFloor = props.getProperty("rebirth.minFloor", "50").toInt()
        val milestoneInterval = props.getProperty("rebirth.milestoneInterval", "10").toInt()
        val pointsPerMilestone = props.getProperty("rebirth.pointsPerMilestone", "1").toInt()
        val baseUpgradeCost = props.getProperty("rebirth.baseUpgradeCost", "1").toInt()
        val costIncreasePerLevel = props.getProperty("rebirth.costIncreasePerLevel", "1").toInt()
        val maxStatLevel = props.getProperty("rebirth.maxStatLevel", "10").toInt()
        val statBonusPerLevel = props.getProperty("rebirth.statBonusPerLevel", "0.05").toDouble()

        // Still provide a way to parse from a map for testing if needed
        fun fromMap(data: Map<String, String>): Map<String, Any> {
            return mapOf(
                "minFloor" to (data["minFloor"]?.toInt() ?: 50),
                "milestoneInterval" to (data["milestoneInterval"]?.toInt() ?: 10),
                "pointsPerMilestone" to (data["pointsPerMilestone"]?.toInt() ?: 1),
                "baseUpgradeCost" to (data["baseUpgradeCost"]?.toInt() ?: 1),
                "costIncreasePerLevel" to (data["costIncreasePerLevel"]?.toInt() ?: 1),
                "maxStatLevel" to (data["maxStatLevel"]?.toInt() ?: 10),
                "statBonusPerLevel" to (data["statBonusPerLevel"]?.toDouble() ?: 0.05)
            )
        }
    }
}
