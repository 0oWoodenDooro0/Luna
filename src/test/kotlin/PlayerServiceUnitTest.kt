package website.woodendoor

import io.mockk.*
import website.woodendoor.repository.PlayerData
import website.woodendoor.repository.PlayerRepository
import kotlin.test.*

class PlayerServiceUnitTest {

    @BeforeTest
    fun setup() {
        mockkObject(PlayerRepository)
    }

    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `test registerPlayer when already registered`() {
        val userId = "registeredUser"
        every { PlayerRepository.getById(userId) } returns PlayerData(userId, 1, 0, 0, 0L)
        
        val result = PlayerService.registerPlayer(userId)
        assertFalse(result)
        
        verify(exactly = 0) { PlayerRepository.create(any()) }
    }

    @Test
    fun `test registerPlayer when new`() {
        val userId = "newUser"
        every { PlayerRepository.getById(userId) } returns null
        every { PlayerRepository.create(any()) } just runs
        
        val result = PlayerService.registerPlayer(userId)
        assertTrue(result)
        
        verify { 
            PlayerRepository.create(match { it.userId == userId }) 
        }
    }
}
