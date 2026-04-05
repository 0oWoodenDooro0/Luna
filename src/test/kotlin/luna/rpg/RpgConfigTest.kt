package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpgConfigTest {
    @Test
    fun testCalculateRecoveryCooldown() {
        val baseAttr = RpgAttributes(0, 100, 10, 5, 8)
        
        // Base: 100 HP * 0.1 = 10s. Level 0: 10s - 0 = 10s.
        val player1 = Player("test", "Test", baseAttr, recoveryLevel = 0)
        assertEquals(10L, player1.calculateRecoveryCooldown())
        
        // Base: 200 HP * 0.1 = 20s. Level 1: 20s - 5 = 15s.
        val player2 = Player("test", "Test", RpgAttributes(0, 200, 10, 5, 8), recoveryLevel = 1)
        assertEquals(15L, player2.calculateRecoveryCooldown())
        
        // Min: 50 HP * 0.1 = 5s. Level 0: 5s.
        val player3 = Player("test", "Test", RpgAttributes(0, 50, 10, 5, 8), recoveryLevel = 0)
        assertEquals(5L, player3.calculateRecoveryCooldown())
        
        // Rebirth bonus: Level 2 (10% reduction) -> 10s * 0.9 = 9s
        val player4 = Player("test", "Test", baseAttr, recoveryLevel = 0, rebirthRecoveryLevel = 2)
        assertEquals(9L, player4.calculateRecoveryCooldown())
    }
}
