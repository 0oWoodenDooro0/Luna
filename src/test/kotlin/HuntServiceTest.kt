package website.woodendoor

import io.mockk.*
import website.woodendoor.repository.PlayerData
import website.woodendoor.repository.PlayerRepository
import kotlin.test.*

class HuntServiceTest {

    @BeforeTest
    fun setup() {
        mockkObject(PlayerRepository)
    }

    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `test processHunt when not registered`() {
        val userId = "unregistered"
        every { PlayerRepository.getById(userId) } returns null
        
        val result = HuntService.processHunt(userId)
        assertTrue(result is HuntService.HuntResult.NotRegistered)
    }

    @Test
    fun `test processHunt on cooldown`() {
        val userId = "cooldownUser"
        val currentTime = System.currentTimeMillis()
        val lastHuntTime = currentTime - 30_000 // 30 seconds ago, cooldown is 60s
        
        val player = PlayerData(userId, 1, 0, 0, lastHuntTime)
        every { PlayerRepository.getById(userId) } returns player
        
        val result = HuntService.processHunt(userId)
        assertTrue(result is HuntService.HuntResult.OnCooldown)
        assertTrue((result as HuntService.HuntResult.OnCooldown).remainingSeconds in 25..35)
    }

    @Test
    fun `test processHunt success`() {
        val userId = "successUser"
        val lastHuntTime = System.currentTimeMillis() - 70_000 // 70 seconds ago
        
        val player = PlayerData(userId, 1, 50, 0, lastHuntTime)
        every { PlayerRepository.getById(userId) } returns player
        every { PlayerRepository.update(userId, any()) } just runs
        
        val result = HuntService.processHunt(userId)
        assertTrue(result is HuntService.HuntResult.Success)
        val successResult = result as HuntService.HuntResult.Success
        
        assertEquals(10, successResult.newGold)
        assertEquals(1, successResult.levelUpResult.newLevel)
        assertEquals(70, successResult.levelUpResult.newXp)
        
        verify { 
            PlayerRepository.update(userId, match { 
                it.userId == userId && it.level == 1 && it.xp == 70 && it.gold == 10 
            }) 
        }
    }
}
