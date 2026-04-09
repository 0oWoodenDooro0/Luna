package luna.rpg.command

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
import kotlin.test.assertTrue

class MapCommandTest {
    @BeforeEach
    fun setup() {
        val testDbFile = "test_map_command.db"
        java.io.File(testDbFile).delete()
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable, PlayerMapsTable)
            PlayerRepository.getOrCreatePlayer("user_map_cmd")
        }
    }

    @AfterEach
    fun teardown() {
        val testDbFile = "test_map_command.db"
        java.io.File(testDbFile).delete()
    }

    @Test
    fun testMapCommandInstantiation() {
        val command = MapCommand()
        assertEquals("map", command.name)
        assertTrue(command.description.contains("地圖"))
    }
}
