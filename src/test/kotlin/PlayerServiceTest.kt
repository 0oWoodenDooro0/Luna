package website.woodendoor

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.repository.PlayerRepository
import java.io.File
import kotlin.test.*

class PlayerServiceTest {

    private val testDbFile = "test_player_service.db"

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
    fun `test registerPlayer`() {
        val userId = "newPlayer"
        
        // First registration should succeed
        assertTrue(PlayerService.registerPlayer(userId))
        
        // Second registration should fail
        assertFalse(PlayerService.registerPlayer(userId))
        
        val player = PlayerService.getPlayer(userId)
        assertNotNull(player)
        assertEquals(userId, player.userId)
    }
}
