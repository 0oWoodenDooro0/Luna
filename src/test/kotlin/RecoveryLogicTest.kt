package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import website.woodendoor.rpg.RpgConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryLogicTest {

    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun testIsRecovering() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val maxHp = 100
            val cooldown = RpgConfig.calculateRecoveryCooldown(maxHp, 0) // 10s
            
            // CASE 1: HP > 0 -> Not recovering
            val healthyPlayer = website.woodendoor.rpg.Player(
                id = "healthy", name = "Healthy",
                attributes = website.woodendoor.rpg.RpgAttributes(100, 100, 10, 5, 8)
            )
            assertFalse(PlayerRepository.isRecovering(healthyPlayer))
            
            // CASE 2: HP = 0, just started -> Recovering
            val deadPlayer = website.woodendoor.rpg.Player(
                id = "dead", name = "Dead",
                attributes = website.woodendoor.rpg.RpgAttributes(0, 100, 10, 5, 8),
                recoveryStartAt = System.currentTimeMillis()
            )
            assertTrue(PlayerRepository.isRecovering(deadPlayer))
            val remaining = PlayerRepository.getRemainingRecoveryTime(deadPlayer)
            assertTrue(remaining in (cooldown - 2)..cooldown)
            
            // CASE 3: HP = 0, long ago -> Not recovering
            val recoveredPlayer = website.woodendoor.rpg.Player(
                id = "recovered", name = "Recovered",
                attributes = website.woodendoor.rpg.RpgAttributes(0, 100, 10, 5, 8),
                recoveryStartAt = System.currentTimeMillis() - 20000 // 20s ago
            )
            assertFalse(PlayerRepository.isRecovering(recoveredPlayer))
            assertEquals(0L, PlayerRepository.getRemainingRecoveryTime(recoveredPlayer))
        }
    }

    @Test
    fun testRestoreHpIfRecovered() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val userId = "test_user"
            PlayersTable.insertPlayer(
                id = userId, hp = 0, maxHp = 100, atk = 10, def = 5, spd = 8,
                wood = 0, stone = 0, metal = 0, floor = 1,
                recoveryStartAt = System.currentTimeMillis() - 20000 // 20s ago (cooldown is 10s)
            )
            
            val player = PlayerRepository.restoreHpIfRecovered(userId)
            assertTrue(player != null)
            assertEquals(100, player!!.attributes.hp)
            
            // Verify in DB
            val dbPlayer = PlayerRepository.getOrCreatePlayer(userId)
            assertEquals(100, dbPlayer.attributes.hp)
        }
    }

    @Test
    fun testDoNotRestoreIfStillRecovering() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val userId = "still_dead"
            PlayersTable.insertPlayer(
                id = userId, hp = 0, maxHp = 100, atk = 10, def = 5, spd = 8,
                wood = 0, stone = 0, metal = 0, floor = 1,
                recoveryStartAt = System.currentTimeMillis() // just now
            )
            
            val player = PlayerRepository.restoreHpIfRecovered(userId)
            assertTrue(player != null)
            assertEquals(0, player!!.attributes.hp)
        }
    }

    @Test
    fun testUpgradeRecoverySpeed() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val userId = "upgrade_user"
            PlayersTable.insertPlayer(
                id = userId, hp = 100, maxHp = 100, atk = 10, def = 5, spd = 8,
                wood = 100, stone = 100, metal = 100, floor = 1
            )
            
            val result = PlayerRepository.upgradeEquipment(userId, "recovery")
            assertTrue(result is PlayerRepository.UpgradeResult.Success)
            val player = (result as PlayerRepository.UpgradeResult.Success).player
            assertEquals(1, player.recoveryLevel)

            // Verify resources deducted (Cost for level 0 is 10 wood, 10 stone, 10 metal)
            assertEquals(90, player.wood)
            assertEquals(90, player.stone)
            assertEquals(90, player.metal)
        }
    }
}
