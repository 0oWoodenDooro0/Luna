package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatUpgradeTest {
    @Test
    fun testCalculateUpgradeCost() {
        // Defaults: BASE_UPGRADE_COST=1, COST_INCREASE_PER_LEVEL=1
        val player = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8))
        assertEquals(1, player.calculateStatUpgradeCost(0))
        assertEquals(2, player.calculateStatUpgradeCost(1))
        assertEquals(3, player.calculateStatUpgradeCost(2))
        assertEquals(10, player.calculateStatUpgradeCost(9))
    }

    @Test
    fun testCanUpgradeStat() {
        // Defaults: MAX_STAT_LEVEL=10
        val player = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), rebirthPoints = 5)

        // Level 0 -> 1: cost 1
        assertTrue(player.canUpgradeStat(player.rebirthAtkLevel))

        // Level 9 -> 10: cost 10, but only has 5 points
        val playerLowPoints = player.copy(rebirthAtkLevel = 9)
        assertFalse(playerLowPoints.canUpgradeStat(playerLowPoints.rebirthAtkLevel))

        // Level 10 -> 11: already at max cap
        val playerAtCap = player.copy(rebirthAtkLevel = 10, rebirthPoints = 100)
        assertFalse(playerAtCap.canUpgradeStat(playerAtCap.rebirthAtkLevel))
    }

    @Test
    fun testEffectiveAttributes() {
        // Base stats: 100 HP, 10 ATK, 5 DEF, 8 SPD
        // Rebirth bonuses: 5% per level (STAT_BONUS_PER_LEVEL=0.05)

        val baseAttr = RpgAttributes(100, 100, 10, 5, 8)

        // Level 2 ATK (10% bonus) -> 10 * 1.10 = 11
        val playerAtkLvl2 = Player("test", "Test", baseAttr, rebirthAtkLevel = 2)
        assertEquals(11, playerAtkLvl2.effectiveAttributes.atk)

        // Level 2 DEF (10% bonus) -> 5 * 1.10 = 5.5 -> 5 (floor)
        val playerDefLvl2 = Player("test", "Test", baseAttr, rebirthDefLevel = 2)
        assertEquals(5, playerDefLvl2.effectiveAttributes.def)

        // Level 2 SPD (10% bonus) -> 8 * 1.10 = 8.8 -> 8 (floor)
        val playerSpdLvl2 = Player("test", "Test", baseAttr, rebirthSpdLevel = 2)
        assertEquals(8, playerSpdLvl2.effectiveAttributes.spd)

        // Level 2 HP (10% bonus) -> 100 * 1.10 = 110
        val playerHpLvl2 = Player("test", "Test", baseAttr, rebirthHpLevel = 2)
        assertEquals(110, playerHpLvl2.effectiveAttributes.maxHp)
    }
}
