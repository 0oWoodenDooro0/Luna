package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.Players
import java.io.File
import kotlin.test.*

class PlayerRepositoryTest {

    private val testDbFile = "test.db"

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
    fun `test creating a player and retrieving their level and xp`() {
        val userId = "testUser1"
        PlayerRepository.createPlayer(userId)

        val player = PlayerRepository.getPlayer(userId)
        assertNotNull(player)
        assertEquals(userId, player.userId)
        assertEquals(1, player.level, "New player should be level 1")
        assertEquals(0, player.xp, "New player should have 0 xp")
    }

    @Test
    fun `test updating a player's level and xp`() {
        val userId = "testUser2"
        PlayerRepository.createPlayer(userId)

        // Initial check
        var player = PlayerRepository.getPlayer(userId)!!
        assertEquals(1, player.level)
        assertEquals(0, player.xp)

        // Update level
        PlayerRepository.setLevel(userId, 5)
        player = PlayerRepository.getPlayer(userId)!!
        assertEquals(5, player.level, "Player level should be updated to 5")

        // Update XP (add)
        PlayerRepository.addXp(userId, 150)
        player = PlayerRepository.getPlayer(userId)!!
        assertEquals(150, player.xp, "Player xp should be updated to 150")

        // Add more XP
        PlayerRepository.addXp(userId, 50)
        player = PlayerRepository.getPlayer(userId)!!
        assertEquals(200, player.xp, "Player xp should be increased to 200")
    }
}
