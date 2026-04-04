package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import website.woodendoor.rpg.RpgConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerRepositoryProgressionTest {

    @BeforeEach
    fun setup() {
        val testDbFile = "test.db"
        java.io.File(testDbFile).delete()
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
        }
    }

    @AfterEach
    fun teardown() {
        java.io.File("test.db").delete()
    }

    @Test
    fun testGetProgression() {
        val userId = "test-user"
        val progression = PlayerRepository.getProgression(userId)
        
        assertEquals(1, progression.currentFloor)
        assertEquals(0, progression.roomsExplored)
        assertTrue(progression.autoAdvance)
    }

    @Test
    fun testAddResources() {
        val userId = "test-user"
        PlayerRepository.getOrCreatePlayer(userId)
        
        PlayerRepository.addResources(userId, "🪵 木頭", 10)
        PlayerRepository.addResources(userId, "🪨 石頭", 5)
        PlayerRepository.addResources(userId, "🔗 金屬", 2)
        
        val player = PlayerRepository.getOrCreatePlayer(userId)
        assertEquals(10, player.wood)
        assertEquals(5, player.stone)
        assertEquals(2, player.metal)
    }

    @Test
    fun testUpdateProgressionNormal() {
        val userId = "test-user"
        PlayerRepository.getOrCreatePlayer(userId)
        
        val result = PlayerRepository.updateProgression(userId, 1, 0)
        assertEquals(1, result.finalRoomCount)
        assertEquals("", result.message)
        
        val player = PlayerRepository.getOrCreatePlayer(userId)
        assertEquals(1, player.currentFloor)
        assertEquals(1, player.roomsExplored)
    }

    @Test
    fun testUpdateProgressionFloorCompleteAutoAdvance() {
        val userId = "test-user"
        PlayerRepository.getOrCreatePlayer(userId)
        
        // Floor size is 10 by default
        val result = PlayerRepository.updateProgression(userId, 1, 9)
        assertEquals(0, result.finalRoomCount)
        assertTrue(result.message.contains("自動前往第 2 層"))
        
        val player = PlayerRepository.getOrCreatePlayer(userId)
        assertEquals(2, player.currentFloor)
        assertEquals(0, player.roomsExplored)
    }

    @Test
    fun testUpdateProgressionFloorCompleteNoAutoAdvance() {
        val userId = "test-user"
        PlayerRepository.getOrCreatePlayer(userId)
        PlayerRepository.updateAutoAdvance(userId, false)
        
        val result = PlayerRepository.updateProgression(userId, 1, 9)
        assertEquals(0, result.finalRoomCount)
        assertTrue(result.message.contains("保留在第 1 層"))
        
        val player = PlayerRepository.getOrCreatePlayer(userId)
        assertEquals(1, player.currentFloor)
        assertEquals(0, player.roomsExplored)
    }

    @Test
    fun testUpdateAutoAdvance() {
        val userId = "test-user"
        PlayerRepository.getOrCreatePlayer(userId)
        
        PlayerRepository.updateAutoAdvance(userId, false)
        var player = PlayerRepository.getOrCreatePlayer(userId)
        assertFalse(player.autoAdvance)
        
        PlayerRepository.updateAutoAdvance(userId, true)
        player = PlayerRepository.getOrCreatePlayer(userId)
        assertTrue(player.autoAdvance)
    }
}
