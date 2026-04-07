package luna.rpg.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlayersTableExpansionTest {

    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun `test PlayersTable includes new rebirth levels`() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            PlayersTable.insertPlayer(
                id = "user1",
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 1,
                rebirthResourceLevel = 7,
                rebirthEfficientLevel = 4
            )
            
            val player = PlayersTable.fetchPlayer("user1")
            assertNotNull(player)
            assertEquals(7, player?.rebirthResourceLevel)
            assertEquals(4, player?.rebirthEfficientLevel)
        }
    }
}
