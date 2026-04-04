package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RpgConfigRefactorTest {
    @Test
    fun testExplorationConfig() {
        // These will fail to compile or run until RpgConfig is updated
        assertEquals(5, RpgConfig.Exploration.FLOOR_SIZE)
        assertEquals(50, RpgConfig.Exploration.EVENT_ROLL_RESOURCE_THRESHOLD)
    }

    @Test
    fun testMonsterConfig() {
        assertEquals(20, RpgConfig.Monster.BASE_HP)
        assertEquals(5, RpgConfig.Monster.HP_PER_FLOOR)
    }

    @Test
    fun testCombatConfig() {
        assertEquals(20, RpgConfig.Combat.MAX_TURNS)
    }

    @Test
    fun testEconomyConfig() {
        assertNotNull(RpgConfig.Economy.UPGRADE_REQUIREMENTS)
    }
}
