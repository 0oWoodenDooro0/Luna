package luna.rpg

import luna.rpg.repository.PlayerRepository
import luna.rpg.repository.PlayersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressionEnforcementTest {
    @BeforeEach
    fun setup() {
        val testDbFile = "test_enforcement.db"
        java.io.File(testDbFile).delete()
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
        }
    }

    @AfterEach
    fun teardown() {
        java.io.File("test_enforcement.db").delete()
    }

    @Test
    fun `test updateProgression always advances even if autoAdvance is false`() {
        val userId = "test-user"
        PlayerRepository.getOrCreatePlayer(userId)

        // Manually set autoAdvance to false in the database
        transaction {
            PlayersTable.update({ PlayersTable.id eq userId }) {
                it[autoAdvance] = false
            }
        }

        // Mock progression to the end of floor 1 (floor size is 10)
        // Current floor 1, roomsExplored 9. Next exploration should trigger floor completion.
        val result = PlayerRepository.updateProgression(userId, 1, 9)

        // We EXPECT it to advance to floor 2 regardless of the setting
        assertEquals(0, result.finalRoomCount)
        assertTrue(result.message.contains("自動前往第 2 層"), "Message should indicate automatic advancement: ${result.message}")

        val player = PlayerRepository.getOrCreatePlayer(userId)
        assertEquals(2, player.currentFloor, "Player should have advanced to floor 2")
        assertEquals(0, player.roomsExplored)
    }
}
