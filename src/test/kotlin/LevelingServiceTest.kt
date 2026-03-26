package website.woodendoor

import kotlin.test.*

class LevelingServiceTest {

    @Test
    fun `test getXpThreshold`() {
        assertEquals(100, LevelingService.getXpThreshold(1))
        assertEquals(200, LevelingService.getXpThreshold(2))
        assertEquals(500, LevelingService.getXpThreshold(5))
    }

    @Test
    fun `test calculateLevelUp with no level up`() {
        val result = LevelingService.calculateLevelUp(level = 1, xp = 50, addedXp = 30)
        assertEquals(1, result.newLevel)
        assertEquals(80, result.newXp)
        assertFalse(result.leveledUp)
    }

    @Test
    fun `test calculateLevelUp with one level up`() {
        val result = LevelingService.calculateLevelUp(level = 1, xp = 50, addedXp = 60)
        assertEquals(2, result.newLevel)
        assertEquals(10, result.newXp)
        assertTrue(result.leveledUp)
    }

    @Test
    fun `test calculateLevelUp with multiple level ups`() {
        // Level 1 -> 2 needs 100.
        // Level 2 -> 3 needs 200.
        // Total needed to reach level 3: 300.
        val result = LevelingService.calculateLevelUp(level = 1, xp = 50, addedXp = 300)
        assertEquals(3, result.newLevel)
        assertEquals(50, result.newXp)
        assertTrue(result.leveledUp)
    }
}
