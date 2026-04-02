package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpgradeLogicTest {

    @BeforeEach
    fun setup() {
        DatabaseManager.init("jdbc:sqlite:file:testdb?mode=memory&cache=shared")
    }

    @Test
    fun testUpgradeCostCalculation() {
        assertEquals(10, PlayerRepository.getUpgradeCost(0))
        assertEquals(20, PlayerRepository.getUpgradeCost(1))
        assertEquals(30, PlayerRepository.getUpgradeCost(2))
    }

    @Test
    fun testSuccessfulWeaponUpgrade() {
        val userId = "user1"
        transaction {
            PlayerRepository.getOrCreatePlayer(userId)
            PlayersTable.update({ PlayersTable.id eq userId }) {
                it[wood] = 20
                it[metal] = 20
            }
            
            val result = PlayerRepository.upgradeEquipment(userId, "weapon")
            assertTrue(result is PlayerRepository.UpgradeResult.Success)
            val updatedPlayer = (result as PlayerRepository.UpgradeResult.Success).player
            assertEquals(1, updatedPlayer.weaponLevel)
            assertEquals(10, updatedPlayer.wood)
            assertEquals(10, updatedPlayer.metal)
        }
    }

    @Test
    fun testInsufficientResourcesUpgrade() {
        val userId = "user2"
        transaction {
            PlayerRepository.getOrCreatePlayer(userId)
            PlayersTable.update({ PlayersTable.id eq userId }) {
                it[wood] = 5
                it[metal] = 20
            }
            
            val result = PlayerRepository.upgradeEquipment(userId, "weapon")
            assertTrue(result is PlayerRepository.UpgradeResult.InsufficientResources)
            assertEquals("木頭", (result as PlayerRepository.UpgradeResult.InsufficientResources).missingResource)
        }
    }
}
