package website.woodendoor

import io.mockk.*
import website.woodendoor.repository.PlayerData
import website.woodendoor.repository.PlayerRepository
import kotlin.test.*

class LevelingServiceUnitTest {

    @BeforeTest
    fun setup() {
        mockkObject(PlayerRepository)
    }

    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `test addXp handles level up and updates repository`() {
        val userId = "user1"
        val initialPlayer = PlayerData(userId, 1, 50, 0, 0L)
        
        every { PlayerRepository.getById(userId) } returns initialPlayer
        every { PlayerRepository.update(userId, any()) } just runs
        
        // Threshold for level 1 is 100. Adding 60 XP should level up to 2 with 10 XP remaining.
        val result = LevelingService.addXp(userId, 60)
        
        assertTrue(result.leveledUp)
        assertEquals(2, result.newLevel)
        assertEquals(10, result.newXp)
        
        verify { 
            PlayerRepository.update(userId, match { 
                it.userId == userId && it.level == 2 && it.xp == 10 
            }) 
        }
    }

    @Test
    fun `test addXp handles no level up`() {
        val userId = "user2"
        val initialPlayer = PlayerData(userId, 1, 10, 0, 0L)
        
        every { PlayerRepository.getById(userId) } returns initialPlayer
        every { PlayerRepository.update(userId, any()) } just runs
        
        val result = LevelingService.addXp(userId, 30)
        
        assertFalse(result.leveledUp)
        assertEquals(1, result.newLevel)
        assertEquals(40, result.newXp)
        
        verify { 
            PlayerRepository.update(userId, match { 
                it.userId == userId && it.level == 1 && it.xp == 40 
            }) 
        }
    }
}
