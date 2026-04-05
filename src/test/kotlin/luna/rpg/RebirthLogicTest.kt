package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RebirthLogicTest {

    @Test
    fun testCalculateRebirthPoints() {
        // Defaults: MIN_FLOOR=50, MILESTONE_INTERVAL=10, POINTS_PER_MILESTONE=1
        val playerAt49 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(49, 0, true))
        val playerAt50 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(50, 0, true))
        val playerAt59 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(59, 0, true))
        val playerAt60 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(60, 0, true))
        val playerAt69 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(69, 0, true))
        val playerAt70 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(70, 0, true))
        val playerAt100 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(100, 0, true))
        
        assertEquals(0, playerAt49.calculateEarnedPoints())
        assertEquals(0, playerAt50.calculateEarnedPoints())
        assertEquals(0, playerAt59.calculateEarnedPoints())
        assertEquals(1, playerAt60.calculateEarnedPoints())
        assertEquals(1, playerAt69.calculateEarnedPoints())
        assertEquals(2, playerAt70.calculateEarnedPoints())
        assertEquals(5, playerAt100.calculateEarnedPoints())
    }

    @Test
    fun testCanRebirth() {
        // Default MIN_FLOOR=50
        val playerAt49 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(49, 0, true))
        val playerAt50 = Player("test", "Test", RpgAttributes(100, 100, 10, 5, 8), progression = PlayerProgression(50, 0, true))
        
        assertFalse(playerAt49.canRebirth())
        assertTrue(playerAt50.canRebirth())
    }

    @Test
    fun testHardReset() {
        val player = Player(
            id = "test",
            name = "Test",
            attributes = RpgAttributes(50, 150, 20, 15, 12),
            wood = 100,
            stone = 100,
            metal = 100,
            weaponLevel = 5,
            shieldLevel = 5,
            armorLevel = 5,
            recoveryLevel = 5,
            progression = PlayerProgression(60, 2, true),
            rebirthCount = 1,
            rebirthPoints = 2
        )

        val resetPlayer = player.rebirthReset(earnedPoints = 1)
        
        assertEquals(2, resetPlayer.rebirthCount) // 1 + 1
        assertEquals(3, resetPlayer.rebirthPoints) // 2 + 1
        assertEquals(1, resetPlayer.currentFloor)
        assertEquals(0, resetPlayer.roomsExplored)
        assertEquals(0, resetPlayer.wood)
        assertEquals(0, resetPlayer.stone)
        assertEquals(0, resetPlayer.metal)
        assertEquals(0, resetPlayer.weaponLevel)
        assertEquals(0, resetPlayer.shieldLevel)
        assertEquals(0, resetPlayer.armorLevel)
        assertEquals(0, resetPlayer.recoveryLevel)
        
        // Base values: 100 HP, 10 ATK, 5 DEF, 8 SPD
        assertEquals(100, resetPlayer.attributes.maxHp)
        assertEquals(100, resetPlayer.attributes.hp)
        assertEquals(10, resetPlayer.attributes.atk)
        assertEquals(5, resetPlayer.attributes.def)
        assertEquals(8, resetPlayer.attributes.spd)
    }
}
