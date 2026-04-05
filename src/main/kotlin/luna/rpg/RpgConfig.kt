package luna.rpg

import kotlin.math.max

object RpgConfig {
    var configPath: String = "config.yml"
        set(value) {
            field = value
            loader = RpgConfigLoader(value)
            reload()
        }

    private var loader = RpgConfigLoader(configPath)
    private var data = loader.load()

    fun reload() {
        data = loader.load()
    }

    object Exploration {
        val FLOOR_SIZE get() = data.exploration.floorSize
        val EVENT_ROLL_RESOURCE_THRESHOLD get() = data.exploration.eventRollResourceThreshold
        val RESOURCE_MIN_AMOUNT get() = data.exploration.resourceMinAmount
        val RESOURCE_MAX_AMOUNT get() = data.exploration.resourceMaxAmount
        val RESOURCE_NAMES get() = data.exploration.resourceNames
        val MONSTER_NAMES get() = data.exploration.monsterNames
    }

    object Monster {
        val BASE_HP get() = data.monster.baseHp
        val HP_PER_FLOOR get() = data.monster.hpPerFloor
        val BASE_ATK get() = data.monster.baseAtk
        val ATK_PER_FLOOR get() = data.monster.atkPerFloor
        val BASE_DEF get() = data.monster.baseDef
        val DEF_PER_FLOOR get() = data.monster.defPerFloor
        val BASE_SPD get() = data.monster.baseSpd
        val SPD_PER_FLOOR get() = data.monster.spdPerFloor
    }

    object Combat {
        val MAX_TURNS get() = data.combat.maxTurns
    }

    object Economy {
        val UPGRADE_REQUIREMENTS get() = data.economy.upgradeRequirements
        val MONSTER_REWARD_BASE_AMOUNT get() = data.economy.monsterRewardBaseAmount
        val MONSTER_REWARD_SCALE_PER_FLOOR get() = data.economy.monsterRewardScalePerFloor
    }

    object Upgrade {
        val WEAPON_ATK_BONUS get() = data.upgrade.weaponAtkBonus
        val SHIELD_DEF_BONUS get() = data.upgrade.shieldDefBonus
        val ARMOR_HP_BONUS get() = data.upgrade.armorHpBonus
        val RECOVERY_REDUCTION_SECONDS get() = data.upgrade.recoveryReductionSeconds
    }

    object Recovery {
        /**
         * 康復基礎冷卻時間 (每 HP 秒數)
         */
        val BASE_SECONDS_PER_HP get() = data.recovery.baseSecondsPerHp

        /**
         * 最小康復時間 (秒)
         */
        val MIN_SECONDS get() = data.recovery.minSeconds
    }

    object Rebirth {
        val MIN_FLOOR get() = data.rebirth.minFloor
        val MILESTONE_INTERVAL get() = data.rebirth.milestoneInterval
        val POINTS_PER_MILESTONE get() = data.rebirth.pointsPerMilestone
        val BASE_UPGRADE_COST get() = data.rebirth.baseUpgradeCost
        val COST_INCREASE_PER_LEVEL get() = data.rebirth.costIncreasePerLevel
        val MAX_STAT_LEVEL get() = data.rebirth.maxStatLevel
        val STAT_BONUS_PER_LEVEL get() = data.rebirth.statBonusPerLevel
    }

    object Player {
        val INITIAL_ATTRIBUTES get() = RpgAttributes(
            hp = data.player.initialHp,
            maxHp = data.player.initialHp,
            atk = data.player.initialAtk,
            def = data.player.initialDef,
            spd = data.player.initialSpd
        )
        val INITIAL_RESOURCES get() = data.player.initialResources
    }
}
