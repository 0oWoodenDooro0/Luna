package luna.rpg

import luna.rpg.repository.PlayerMapRepository
import luna.rpg.repository.PlayerRepository
import luna.rpg.repository.PlayersTable
import luna.rpg.repository.PlayerMapsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapServiceTest {
    @BeforeEach
    fun setup() {
        val testDbFile = "test_map_service.db"
        java.io.File(testDbFile).delete()
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable, PlayerMapsTable)
            PlayerRepository.getOrCreatePlayer("user123")
        }
    }

    @AfterEach
    fun teardown() {
        val testDbFile = "test_map_service.db"
        java.io.File(testDbFile).delete()
    }

    @Test
    fun testCreateMapSuccess() {
        // Give player enough resources
        transaction {
            PlayersTable.update({ PlayersTable.id eq "user123" }) {
                it[wood] = 1000
                it[stone] = 1000
                it[metal] = 1000
            }
        }
        
        val result = MapService.createMap("user123", 1, 1.0)
        assertTrue(result is MapService.CreateMapResult.Success)
        
        val maps = PlayerMapRepository.getMaps("user123")
        assertEquals(1, maps.size)
        
        val player = PlayerRepository.getOrCreatePlayer("user123")
        assertEquals(900, player.wood) // 1000 - 100
    }

    @Test
    fun testCreateMapInsufficientResources() {
        // Player has 0 resources by default
        val result = MapService.createMap("user123", 1, 1.0)
        assertTrue(result is MapService.CreateMapResult.InsufficientResources)
    }

    @Test
    fun testCreateMapInvalidDropRate() {
        val result = MapService.createMap("user123", 1, 0.5) // Below 0.6
        assertTrue(result is MapService.CreateMapResult.InvalidParameters)
    }
}
