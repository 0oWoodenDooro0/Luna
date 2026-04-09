package luna.rpg.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerMapRepositoryTest {
    @BeforeEach
    fun setup() {
        val testDbFile = "test_map_repo.db"
        java.io.File(testDbFile).delete()
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable, PlayerMapsTable)
            // Create a test player
            PlayersTable.insertPlayer("user123", 100, 100, 10, 5, 8, 100, 100, 100, 1)
        }
    }

    @AfterEach
    fun teardown() {
        val testDbFile = "test_map_repo.db"
        java.io.File(testDbFile).delete()
    }

    @Test
    fun testCreateAndGetMaps() {
        val mapId = PlayerMapRepository.createMap("user123", 1, 1.2)
        assertTrue(mapId != null && mapId > 0)

        val maps = PlayerMapRepository.getMaps("user123")
        assertEquals(1, maps.size)
        assertEquals(1, maps[0].layer)
        assertEquals(1.2, maps[0].dropRate)
    }

    @Test
    fun testCreateMapWithResourceDeduction() {
        val mapId = PlayerMapRepository.createMap("user123", 1, 1.2, woodCost = 50, stoneCost = 30, metalCost = 10)
        assertTrue(mapId != null && mapId > 0)

        val player = transaction { PlayersTable.fetchPlayer("user123") }
        assertTrue(player != null)
        assertEquals(50, player!!.wood) // 100 - 50
        assertEquals(70, player!!.stone) // 100 - 30
        assertEquals(90, player!!.metal) // 100 - 10
    }

    @Test
    fun testSetActiveMap() {
        val mapId1 = PlayerMapRepository.createMap("user123", 1, 1.0)!!
        val mapId2 = PlayerMapRepository.createMap("user123", 2, 1.5)!!

        PlayerMapRepository.setActiveMap("user123", mapId2)

        val activeMap = PlayerMapRepository.getActiveMap("user123")
        assertTrue(activeMap != null)
        assertEquals(mapId2, activeMap!!.id)
        assertTrue(activeMap!!.isActive)

        val maps = PlayerMapRepository.getMaps("user123")
        val map1 = maps.find { it.id == mapId1 }
        assertTrue(map1 != null)
        assertTrue(!map1!!.isActive)
    }

    @Test
    fun testUpdateProgress() {
        val mapId = PlayerMapRepository.createMap("user123", 1, 1.0)!!

        PlayerMapRepository.updateProgress(mapId, 10)

        val maps = PlayerMapRepository.getMaps("user123")
        assertEquals(10, maps[0].currentRoom)
    }

    @Test
    fun testDeleteMap() {
        val mapId = PlayerMapRepository.createMap("user123", 1, 1.0)!!

        PlayerMapRepository.deleteMap("user123", mapId)

        val maps = PlayerMapRepository.getMaps("user123")
        assertTrue(maps.isEmpty())
    }

    @Test
    fun testCreateMapWithInsufficientResources() {
        val mapId = PlayerMapRepository.createMap("user123", 1, 1.2, woodCost = 200) // User has 100
        assertNull(mapId)

        val player = transaction { PlayersTable.fetchPlayer("user123") }
        assertEquals(100, player!!.wood) // No deduction
    }
}
