package luna.rpg.repository

import luna.rpg.PlayerMap
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerMapsTableTest {
    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun testPlayerMapsTableExists() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayerMapsTable)
            assertTrue(SchemaUtils.listTables().any { it.equals(PlayerMapsTable.tableName, ignoreCase = true) })
        }
    }

    @Test
    fun testInsertAndFetchMap() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayerMapsTable)

            val mapId =
                PlayerMapsTable.insertMap(
                    playerId = "user123",
                    layer = 1,
                    dropRate = 1.2,
                    rooms = 20,
                    currentRoom = 5,
                    isActive = true,
                )

            assertTrue(mapId > 0)

            val maps = PlayerMapsTable.fetchMaps("user123")
            assertEquals(1, maps.size)
            val map = maps[0]
            assertEquals(mapId, map.id)
            assertEquals("user123", map.playerId)
            assertEquals(1, map.layer)
            assertEquals(1.2, map.dropRate)
            assertEquals(20, map.rooms)
            assertEquals(5, map.currentRoom)
            assertTrue(map.isActive)
        }
    }

    @Test
    fun testFetchActiveMap() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayerMapsTable)

            PlayerMapsTable.insertMap("user123", 1, 1.0, isActive = false)
            PlayerMapsTable.insertMap("user123", 2, 1.5, isActive = true)

            val activeMap = PlayerMapsTable.fetchActiveMap("user123")
            assertTrue(activeMap != null)
            assertEquals(2, activeMap!!.layer)
            assertEquals(1.5, activeMap!!.dropRate)
            assertTrue(activeMap!!.isActive)
        }
    }
}
