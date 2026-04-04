package website.woodendoor.rpg

import website.woodendoor.repository.PlayerRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewardLogicTest {
    @Test
    fun testCalculateMonsterReward() {
        // Floor 1: Base (2) + (1-1)*1 = 2
        val (resource1, amount1) = PlayerRepository.calculateMonsterReward(1)
        assertTrue(RpgConfig.Exploration.RESOURCE_NAMES.contains(resource1))
        assertEquals(2, amount1)

        // Floor 5: Base (2) + (5-1)*1 = 6
        val (resource5, amount5) = PlayerRepository.calculateMonsterReward(5)
        assertTrue(RpgConfig.Exploration.RESOURCE_NAMES.contains(resource5))
        assertEquals(6, amount5)
    }
}
