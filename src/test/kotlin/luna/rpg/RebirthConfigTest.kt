package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals

class RebirthConfigTest {
    @Test
    fun testRebirthConfigDefaults() {
        val config = RpgConfig.Rebirth
        assertEquals(50, config.minFloor)
        assertEquals(10, config.milestoneInterval)
        assertEquals(1, config.pointsPerMilestone)
        assertEquals(1, config.baseUpgradeCost)
        assertEquals(1, config.costIncreasePerLevel)
        assertEquals(10, config.maxStatLevel)
        assertEquals(0.05, config.statBonusPerLevel)
    }

    @Test
    fun testRebirthConfigFromMap() {
        val data = mapOf(
            "minFloor" to "100",
            "milestoneInterval" to "20",
            "pointsPerMilestone" to "2",
            "baseUpgradeCost" to "5",
            "costIncreasePerLevel" to "2",
            "maxStatLevel" to "50",
            "statBonusPerLevel" to "0.1"
        )
        val config = RpgConfig.Rebirth.fromMap(data)
        assertEquals(100, config["minFloor"])
        assertEquals(20, config["milestoneInterval"])
        assertEquals(2, config["pointsPerMilestone"])
        assertEquals(5, config["baseUpgradeCost"])
        assertEquals(2, config["costIncreasePerLevel"])
        assertEquals(50, config["maxStatLevel"])
        assertEquals(0.1, config["statBonusPerLevel"])
    }
}
