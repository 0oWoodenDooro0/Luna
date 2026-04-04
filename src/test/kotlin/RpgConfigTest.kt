package website.woodendoor.rpg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpgConfigTest {
    @Test
    fun testCalculateRecoveryCooldown() {
        // Base: 100 HP * 0.1 = 10s. Level 0: 10s - 0 = 10s.
        assertEquals(10L, RpgConfig.Recovery.calculateCooldown(100, 0))
        
        // Base: 200 HP * 0.1 = 20s. Level 1: 20s - 5 = 15s.
        assertEquals(15L, RpgConfig.Recovery.calculateCooldown(200, 1))
        
        // Min: 50 HP * 0.1 = 5s. Level 0: 5s.
        assertEquals(5L, RpgConfig.Recovery.calculateCooldown(50, 0))
        
        // Min threshold: 100 HP * 0.1 = 10s. Level 2: 10s - 10 = 0s -> should be RECOVERY_MIN_SECONDS (5s).
        assertEquals(5L, RpgConfig.Recovery.calculateCooldown(100, 2))
    }
}
