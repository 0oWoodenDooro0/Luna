package luna.rpg.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RebirthPersistenceTest {

    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun testRebirthFieldsPersistence() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            // This will fail to compile until PlayersTable and Player are updated
            PlayersTable.insertPlayer(
                id = "user_rebirth",
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 1,
                rebirthCount = 2,
                rebirthPoints = 5,
                rebirthAtkLevel = 1,
                rebirthDefLevel = 2,
                rebirthSpdLevel = 3,
                rebirthRecoveryLevel = 4,
                rebirthHpLevel = 5
            )

            val player = PlayersTable.fetchPlayer("user_rebirth")
            assertNotNull(player)
            assertEquals(2, player.rebirthCount)
            assertEquals(5, player.rebirthPoints)
            assertEquals(1, player.rebirthAtkLevel)
            assertEquals(2, player.rebirthDefLevel)
            assertEquals(3, player.rebirthSpdLevel)
            assertEquals(4, player.rebirthRecoveryLevel)
            assertEquals(5, player.rebirthHpLevel)
        }
    }
}
