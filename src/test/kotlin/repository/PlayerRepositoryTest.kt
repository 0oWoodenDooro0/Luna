package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.Players
import java.io.File
import kotlin.test.*

class PlayerRepositoryTest {

    private val testDbFile = "test_player_repo.db"

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
    fun `test generic CRUD on PlayerRepository`() {
        val userId = "genericUser1"
        val player = PlayerData(userId, 1, 0, 0, 0L)
        
        PlayerRepository.create(player)
        assertEquals(player, PlayerRepository.getById(userId))
        
        val updated = player.copy(gold = 100)
        PlayerRepository.update(userId, updated)
        assertEquals(updated, PlayerRepository.getById(userId))
        
        val all = PlayerRepository.getAll()
        assertEquals(1, all.size)
        assertEquals(updated, all[0])
        
        PlayerRepository.delete(userId)
        assertNull(PlayerRepository.getById(userId))
    }
}
