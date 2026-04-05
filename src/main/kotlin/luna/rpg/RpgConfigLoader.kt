package luna.rpg

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.Tag
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter

class RpgConfigLoader(private val configPath: String = "config.yml") {

    fun load(): RpgConfigData {
        val configFile = File(configPath)
        if (!configFile.exists()) {
            val defaultConfig = RpgConfigData()
            save(defaultConfig)
            return defaultConfig
        }

        val yaml = Yaml()
        val inputStream = FileInputStream(configFile)
        val data = yaml.loadAs(inputStream, Map::class.java) as Map<String, Any>
        
        return parseConfig(data)
    }

    private fun save(config: RpgConfigData) {
        val configFile = File(configPath)
        val writer = PrintWriter(configFile)
        
        // Manual writing to include comments if needed, but for now just use SnakeYAML
        val options = DumperOptions()
        options.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        val yaml = Yaml(options)
        
        val data = mapOf(
            "exploration" to mapOf(
                "floor_size" to config.exploration.floorSize,
                "event_roll_resource_threshold" to config.exploration.eventRollResourceThreshold,
                "resource_min_amount" to config.exploration.resourceMinAmount,
                "resource_max_amount" to config.exploration.resourceMaxAmount,
                "resource_names" to config.exploration.resourceNames,
                "monster_names" to config.exploration.monsterNames
            ),
            "monster" to mapOf(
                "base_hp" to config.monster.baseHp,
                "hp_per_floor" to config.monster.hpPerFloor,
                "base_atk" to config.monster.baseAtk,
                "atk_per_floor" to config.monster.atkPerFloor,
                "base_def" to config.monster.baseDef,
                "def_per_floor" to config.monster.defPerFloor,
                "base_spd" to config.monster.baseSpd,
                "spd_per_floor" to config.monster.spdPerFloor
            ),
            "combat" to mapOf(
                "max_turns" to config.combat.maxTurns
            ),
            "economy" to mapOf(
                "upgrade_requirements" to config.economy.upgradeRequirements,
                "monster_reward_base_amount" to config.economy.monsterRewardBaseAmount,
                "monster_reward_scale_per_floor" to config.economy.monsterRewardScalePerFloor
            ),
            "upgrade" to mapOf(
                "weapon_atk_bonus" to config.upgrade.weaponAtkBonus,
                "shield_def_bonus" to config.upgrade.shieldDefBonus,
                "armor_hp_bonus" to config.upgrade.armorHpBonus,
                "recovery_reduction_seconds" to config.upgrade.recoveryReductionSeconds
            ),
            "recovery" to mapOf(
                "base_seconds_per_hp" to config.recovery.baseSecondsPerHp,
                "min_seconds" to config.recovery.minSeconds
            ),
            "rebirth" to mapOf(
                "min_floor" to config.rebirth.minFloor,
                "milestone_interval" to config.rebirth.milestoneInterval,
                "points_per_milestone" to config.rebirth.pointsPerMilestone,
                "base_upgrade_cost" to config.rebirth.baseUpgradeCost,
                "cost_increase_per_level" to config.rebirth.costIncreasePerLevel,
                "max_stat_level" to config.rebirth.maxStatLevel,
                "stat_bonus_per_level" to config.rebirth.statBonusPerLevel
            ),
            "player" to mapOf(
                "initial_hp" to config.player.initialHp,
                "initial_atk" to config.player.initialAtk,
                "initial_def" to config.player.initialDef,
                "initial_spd" to config.player.initialSpd,
                "initial_resources" to config.player.initialResources
            )
        )
        
        yaml.dump(data, writer)
        writer.close()
    }

    private fun parseConfig(data: Map<String, Any>): RpgConfigData {
        val exploration = data["exploration"] as? Map<String, Any>
        val monster = data["monster"] as? Map<String, Any>
        val combat = data["combat"] as? Map<String, Any>
        val economy = data["economy"] as? Map<String, Any>
        val upgrade = data["upgrade"] as? Map<String, Any>
        val recovery = data["recovery"] as? Map<String, Any>
        val rebirth = data["rebirth"] as? Map<String, Any>
        val player = data["player"] as? Map<String, Any>

        return RpgConfigData(
            exploration = ExplorationConfig(
                floorSize = exploration?.get("floor_size") as? Int ?: 5,
                eventRollResourceThreshold = exploration?.get("event_roll_resource_threshold") as? Int ?: 50,
                resourceMinAmount = exploration?.get("resource_min_amount") as? Int ?: 1,
                resourceMaxAmount = exploration?.get("resource_max_amount") as? Int ?: 5,
                resourceNames = exploration?.get("resource_names") as? List<String> ?: listOf("🪵 木頭", "🪨 石頭", "🔗 金屬"),
                monsterNames = exploration?.get("monster_names") as? List<String> ?: listOf("史萊姆", "哥布林", "小蝙蝠")
            ),
            monster = MonsterConfig(
                baseHp = monster?.get("base_hp") as? Int ?: 20,
                hpPerFloor = monster?.get("hp_per_floor") as? Int ?: 5,
                baseAtk = monster?.get("base_atk") as? Int ?: 5,
                atkPerFloor = monster?.get("atk_per_floor") as? Int ?: 2,
                baseDef = monster?.get("base_def") as? Int ?: 2,
                defPerFloor = monster?.get("def_per_floor") as? Int ?: 1,
                baseSpd = monster?.get("base_spd") as? Int ?: 3,
                spdPerFloor = monster?.get("spd_per_floor") as? Int ?: 1
            ),
            combat = CombatConfig(
                maxTurns = combat?.get("max_turns") as? Int ?: 20
            ),
            economy = EconomyConfig(
                upgradeRequirements = economy?.get("upgrade_requirements") as? Map<String, Map<String, Int>> ?: mapOf(
                    "weapon" to mapOf("wood" to 10, "metal" to 5),
                    "shield" to mapOf("stone" to 10, "metal" to 5),
                    "armor" to mapOf("wood" to 5, "stone" to 10),
                    "recovery" to mapOf("wood" to 10, "stone" to 10, "metal" to 10)
                ),
                monsterRewardBaseAmount = economy?.get("monster_reward_base_amount") as? Int ?: 2,
                monsterRewardScalePerFloor = economy?.get("monster_reward_scale_per_floor") as? Int ?: 1
            ),
            upgrade = UpgradeConfig(
                weaponAtkBonus = upgrade?.get("weapon_atk_bonus") as? Int ?: 5,
                shieldDefBonus = upgrade?.get("shield_def_bonus") as? Int ?: 5,
                armorHpBonus = upgrade?.get("armor_hp_bonus") as? Int ?: 5,
                recoveryReductionSeconds = (upgrade?.get("recovery_reduction_seconds") as? Number)?.toDouble() ?: 5.0
            ),
            recovery = RecoveryConfig(
                baseSecondsPerHp = (recovery?.get("base_seconds_per_hp") as? Number)?.toDouble() ?: 0.1,
                minSeconds = (recovery?.get("min_seconds") as? Number)?.toDouble() ?: 5.0
            ),
            rebirth = RebirthConfig(
                minFloor = rebirth?.get("min_floor") as? Int ?: 50,
                milestoneInterval = rebirth?.get("milestone_interval") as? Int ?: 10,
                pointsPerMilestone = rebirth?.get("points_per_milestone") as? Int ?: 1,
                baseUpgradeCost = rebirth?.get("base_upgrade_cost") as? Int ?: 1,
                costIncreasePerLevel = rebirth?.get("cost_increase_per_level") as? Int ?: 1,
                maxStatLevel = rebirth?.get("max_stat_level") as? Int ?: 10,
                statBonusPerLevel = (rebirth?.get("stat_bonus_per_level") as? Number)?.toDouble() ?: 0.05
            ),
            player = PlayerConfig(
                initialHp = player?.get("initial_hp") as? Int ?: 100,
                initialAtk = player?.get("initial_atk") as? Int ?: 10,
                initialDef = player?.get("initial_def") as? Int ?: 5,
                initialSpd = player?.get("initial_spd") as? Int ?: 8,
                initialResources = player?.get("initial_resources") as? Int ?: 0
            )
        )
    }
}

data class RpgConfigData(
    val exploration: ExplorationConfig = ExplorationConfig(),
    val monster: MonsterConfig = MonsterConfig(),
    val combat: CombatConfig = CombatConfig(),
    val economy: EconomyConfig = EconomyConfig(),
    val upgrade: UpgradeConfig = UpgradeConfig(),
    val recovery: RecoveryConfig = RecoveryConfig(),
    val rebirth: RebirthConfig = RebirthConfig(),
    val player: PlayerConfig = PlayerConfig()
)

data class ExplorationConfig(
    val floorSize: Int = 5,
    val eventRollResourceThreshold: Int = 50,
    val resourceMinAmount: Int = 1,
    val resourceMaxAmount: Int = 5,
    val resourceNames: List<String> = listOf("🪵 木頭", "🪨 石頭", "🔗 金屬"),
    val monsterNames: List<String> = listOf("史萊姆", "哥布林", "小蝙蝠")
)

data class MonsterConfig(
    val baseHp: Int = 20,
    val hpPerFloor: Int = 5,
    val baseAtk: Int = 5,
    val atkPerFloor: Int = 2,
    val baseDef: Int = 2,
    val defPerFloor: Int = 1,
    val baseSpd: Int = 3,
    val spdPerFloor: Int = 1
)

data class CombatConfig(
    val maxTurns: Int = 20
)

data class EconomyConfig(
    val upgradeRequirements: Map<String, Map<String, Int>> = mapOf(
        "weapon" to mapOf("wood" to 10, "metal" to 5),
        "shield" to mapOf("stone" to 10, "metal" to 5),
        "armor" to mapOf("wood" to 5, "stone" to 10),
        "recovery" to mapOf("wood" to 10, "stone" to 10, "metal" to 10)
    ),
    val monsterRewardBaseAmount: Int = 2,
    val monsterRewardScalePerFloor: Int = 1
)

data class UpgradeConfig(
    val weaponAtkBonus: Int = 5,
    val shieldDefBonus: Int = 5,
    val armorHpBonus: Int = 5,
    val recoveryReductionSeconds: Double = 5.0
)

data class RecoveryConfig(
    val baseSecondsPerHp: Double = 0.1,
    val minSeconds: Double = 5.0
)

data class RebirthConfig(
    val minFloor: Int = 50,
    val milestoneInterval: Int = 10,
    val pointsPerMilestone: Int = 1,
    val baseUpgradeCost: Int = 1,
    val costIncreasePerLevel: Int = 1,
    val maxStatLevel: Int = 10,
    val statBonusPerLevel: Double = 0.05
)

data class PlayerConfig(
    val initialHp: Int = 100,
    val initialAtk: Int = 10,
    val initialDef: Int = 5,
    val initialSpd: Int = 8,
    val initialResources: Int = 0
)
