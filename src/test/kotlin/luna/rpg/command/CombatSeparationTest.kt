package luna.rpg.command

import luna.rpg.Monster
import luna.rpg.RpgAttributes
import luna.rpg.repository.PlayerMapRepository
import luna.rpg.repository.PlayerMapsTable
import luna.rpg.repository.PlayerRepository
import luna.rpg.repository.PlayersTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CombatSeparationTest {
    @BeforeEach
    fun setup() {
        val testDbFile = "test_separation.db"
        java.io.File(testDbFile).delete()
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable, PlayerMapsTable)
            PlayerRepository.getOrCreatePlayer("user_sep")
        }
    }

    @AfterEach
    fun teardown() {
        val testDbFile = "test_separation.db"
        java.io.File(testDbFile).delete()
    }

    @Test
    fun `test combat states are independent between main floor and maps`() {
        val userId = "user_sep"
        val monsterMain = Monster("Main Monster", RpgAttributes(100, 100, 10, 10, 10))
        val monsterMap = Monster("Map Monster", RpgAttributes(200, 200, 20, 20, 20))

        // 1. Save main floor monster
        PlayerRepository.saveMonsterState(userId, monsterMain)
        
        // 2. Create map and save map monster
        val mapId = PlayerMapRepository.createMap(userId, 5, 1.0)!!
        PlayerMapRepository.setActiveMap(userId, mapId)
        PlayerMapRepository.saveMonsterState(mapId, monsterMap)

        // 3. Verify both are saved independently
        val loadedMain = PlayerRepository.loadMonsterState(userId)
        val loadedMap = PlayerMapRepository.getActiveMap(userId)?.currentMonster

        assertNotNull(loadedMain)
        assertEquals("Main Monster", loadedMain.name)
        
        assertNotNull(loadedMap)
        assertEquals("Map Monster", loadedMap.name)

        // 4. Record combat result for main floor (victory)
        PlayerRepository.recordCombatResult(userId, 100, 0, monsterMain, "🪵 木頭" to 10)
        
        // 5. Verify main floor monster is cleared but map monster remains
        assertNull(PlayerRepository.loadMonsterState(userId))
        assertNotNull(PlayerMapRepository.getActiveMap(userId)?.currentMonster)
        assertEquals("Map Monster", PlayerMapRepository.getActiveMap(userId)?.currentMonster?.name)

        // 6. Record combat result for map (failure)
        PlayerMapRepository.recordCombatResult(userId, mapId, 0, 50, monsterMap)

        // 7. Verify map monster is updated (saved with 50 hp) but main floor monster is still null
        assertNull(PlayerRepository.loadMonsterState(userId))
        val updatedMapMonster = PlayerMapRepository.getActiveMap(userId)?.currentMonster
        assertNotNull(updatedMapMonster)
        assertEquals(50, updatedMapMonster.attributes.hp)
    }
}
