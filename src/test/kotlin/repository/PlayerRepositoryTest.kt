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
        assertEquals(0, player.xp, "New player should have 0 xp") // This will fail to compile if field is 'exp'
    }
}
