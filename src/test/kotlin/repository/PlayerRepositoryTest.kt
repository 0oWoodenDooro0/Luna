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

    @Test
    fun `test addXp triggers level up`() {
        val userId = "testUser3"
        PlayerRepository.createPlayer(userId)

        // Add enough XP to level up (threshold for lvl 1 is 100)
        PlayerRepository.addXp(userId, 110)

        val player = PlayerRepository.getPlayer(userId)!!
        assertEquals(2, player.level, "Player should have leveled up to 2")
        assertEquals(10, player.xp, "Player should have 10 xp remaining")
    }

    @Test
    fun `test generic CRUD on PlayerRepository`() {
        val userId = "genericUser1"
        val player = PlayerData(userId, 1, 0, 0, 0L)
        
        PlayerRepository.create(player)
        assertEquals(player, PlayerRepository.getById(userId))
        
        val updated = player.copy(gold = 100)
        PlayerRepository.update(userId, updated)
        assertEquals(updated, PlayerRepository.getById(userId))
        
        PlayerRepository.delete(userId)
        assertNull(PlayerRepository.getById(userId))
    }
}
