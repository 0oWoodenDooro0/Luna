package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals

class MapConfigTest {
    @Test
    fun testCalculateMapCost() {
        // Default: wood=100, stone=100, metal=50, scale=1.0
        
        // Case 1: Layer 1, dropRate 1.0
        val cost1 = RpgConfig.Map.calculateCost(1, 1.0)
        assertEquals(100, cost1.first)  // wood
        assertEquals(100, cost1.second) // stone
        assertEquals(50, cost1.third)   // metal

        // Case 2: Layer 2, dropRate 1.2
        val cost2 = RpgConfig.Map.calculateCost(2, 1.2)
        // scale = 2 * 1.0 * 1.2 = 2.4
        // wood = 100 * 2.4 = 240
        // stone = 100 * 2.4 = 240
        // metal = 50 * 2.4 = 120
        assertEquals(240, cost2.first)
        assertEquals(240, cost2.second)
        assertEquals(120, cost2.third)
        
        // Case 3: Layer 5, dropRate 0.6
        val cost3 = RpgConfig.Map.calculateCost(5, 0.6)
        // scale = 5 * 1.0 * 0.6 = 3.0
        // wood = 100 * 3.0 = 300
        // stone = 100 * 3.0 = 300
        // metal = 50 * 3.0 = 150
        assertEquals(300, cost3.first)
        assertEquals(300, cost3.second)
        assertEquals(150, cost3.third)
    }
}
