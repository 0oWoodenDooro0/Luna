package website.woodendoor

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.repository.PlayerData
import website.woodendoor.repository.PlayerRepository
import java.io.File
import kotlin.test.*

class LevelingServiceTest {

    private val testDbFile = "test_leveling_service.db"

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.drop(Players)
            SchemaUtils.create(Players)
        }
    }

    @AfterTest
    fun cleanup() {
        File(testDbFile).delete()
    }

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
        val result = LevelingService.calculateLevelUp(level = 1, xp = 50, addedXp = 300)
        assertEquals(3, result.newLevel)
        assertEquals(50, result.newXp)
        assertTrue(result.leveledUp)
    }

    @Test
    fun `test addXp triggers level up in database`() {
        val userId = "levelUpUser"
        PlayerRepository.create(PlayerData(userId, 1, 0, 0, 0L))

        // Threshold for level 1 is 100
        val result = LevelingService.addXp(userId, 110)
        assertTrue(result.leveledUp)
        assertEquals(2, result.newLevel)
        assertEquals(10, result.newXp)

        val player = PlayerRepository.getById(userId)!!
        assertEquals(2, player.level)
        assertEquals(10, player.xp)
    }
}
