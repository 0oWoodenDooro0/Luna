package luna.rpg

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import luna.rpg.Monster
import luna.rpg.RpgAttributes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import luna.rpg.repository.PlayersTable
import luna.rpg.repository.PlayerRepository

class MonsterPersistenceTest {

    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun testSaveAndLoadMonsterState() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            val userId = "user123"
            PlayersTable.insertPlayer(
                id = userId,
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 1
            )

            val monster = Monster(
                name = "Slime",
                attributes = RpgAttributes(hp = 20, maxHp = 50, atk = 5, def = 2, spd = 10)
            )

            // Step 1: Save monster state (we'll implement this method in PlayerRepository)
            PlayerRepository.saveMonsterState(userId, monster)

            // Step 2: Load and verify
            val player = PlayersTable.fetchPlayer(userId)
            assertNotNull(player)
            assertNotNull(player.currentMonster)
            assertEquals("Slime", player.currentMonster!!.name)
            assertEquals(20, player.currentMonster!!.attributes.hp)
            assertEquals(50, player.currentMonster!!.attributes.maxHp)
            assertEquals(5, player.currentMonster!!.attributes.atk)
            assertEquals(2, player.currentMonster!!.attributes.def)
            assertEquals(10, player.currentMonster!!.attributes.spd)
        }
    }

    @Test
    fun testClearMonsterState() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            val userId = "user123"
            PlayersTable.insertPlayer(
                id = userId,
                hp = 100, maxHp = 100, atk = 10, def = 5, spd = 8,
                wood = 0, stone = 0, metal = 0, floor = 1
            )

            val monster = Monster(
                name = "Slime",
                attributes = RpgAttributes(hp = 20, maxHp = 50, atk = 5, def = 2, spd = 10)
            )

            PlayerRepository.saveMonsterState(userId, monster)
            
            // Clear monster state
            PlayerRepository.saveMonsterState(userId, null)

            val player = PlayersTable.fetchPlayer(userId)
            assertNotNull(player)
            assertNull(player.currentMonster)
        }
    }
}
