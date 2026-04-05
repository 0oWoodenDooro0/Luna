package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals

class RebirthConfigTest {
    @Test
    fun testRebirthConfigDefaults() {
        val config = RpgConfig.Rebirth
        assertEquals(50, RpgConfig.Rebirth.MIN_FLOOR)
        assertEquals(10, RpgConfig.Rebirth.MILESTONE_INTERVAL)
        assertEquals(1, RpgConfig.Rebirth.POINTS_PER_MILESTONE)
        assertEquals(1, RpgConfig.Rebirth.BASE_UPGRADE_COST)
        assertEquals(1, RpgConfig.Rebirth.COST_INCREASE_PER_LEVEL)
        assertEquals(10, RpgConfig.Rebirth.MAX_STAT_LEVEL)
        assertEquals(0.05, RpgConfig.Rebirth.STAT_BONUS_PER_LEVEL)
    }
}
