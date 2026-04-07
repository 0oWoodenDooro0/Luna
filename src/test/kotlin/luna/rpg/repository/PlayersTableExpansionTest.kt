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

    @Test
    fun `test rebirthPlayer preserves new rebirth levels in DB`() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            PlayersTable.insertPlayer(
                id = "user_rebirth_test",
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 60, // Eligible
                rebirthResourceLevel = 3,
                rebirthEfficientLevel = 2
            )
            
            PlayerRepository.rebirthPlayer("user_rebirth_test")
            
            val player = PlayersTable.fetchPlayer("user_rebirth_test")
            assertNotNull(player)
            assertEquals(1, player?.rebirthCount)
            assertEquals(3, player?.rebirthResourceLevel)
            assertEquals(2, player?.rebirthEfficientLevel)
        }
    }

    @Test
    fun `test upgradeRebirthStat for new types`() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            PlayersTable.insertPlayer(
                id = "user_upgrade_test",
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 1,
                rebirthPoints = 10,
                rebirthResourceLevel = 0,
                rebirthEfficientLevel = 0
            )
            
            val result1 = PlayerRepository.upgradeRebirthStat("user_upgrade_test", "RESOURCE")
            assert(result1 is PlayerRepository.RebirthUpgradeResult.Success)
            assertEquals(1, (result1 as PlayerRepository.RebirthUpgradeResult.Success).player.rebirthResourceLevel)
            
            val result2 = PlayerRepository.upgradeRebirthStat("user_upgrade_test", "EFFICIENT")
            assert(result2 is PlayerRepository.RebirthUpgradeResult.Success)
            assertEquals(1, (result2 as PlayerRepository.RebirthUpgradeResult.Success).player.rebirthEfficientLevel)
            
            val player = PlayersTable.fetchPlayer("user_upgrade_test")
            assertEquals(1, player?.rebirthResourceLevel)
            assertEquals(1, player?.rebirthEfficientLevel)
            assertEquals(8, player?.rebirthPoints) // 10 - 1 - 1 = 8
        }
    }
}
