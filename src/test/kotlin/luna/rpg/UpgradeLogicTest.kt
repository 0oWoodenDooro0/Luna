package luna.rpg

import luna.rpg.repository.PlayerRepository
import luna.rpg.repository.PlayersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpgradeLogicTest {
    @BeforeEach
    fun setup() {
        // Shared in-memory DB
        Database.connect("jdbc:sqlite:file:testdb?mode=memory&cache=shared", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(PlayersTable)
        }
    }

    @Test
    fun testResourceCostCalculation() {
        // Base amount 10
        assertEquals(10, PlayerRepository.getResourceCost(0, 10))
        assertEquals(20, PlayerRepository.getResourceCost(1, 10))

        // Base amount 5
        assertEquals(5, PlayerRepository.getResourceCost(0, 5))
        assertEquals(10, PlayerRepository.getResourceCost(1, 5))
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
            assertEquals(15, updatedPlayer.attributes.atk) // Base 10 + 5

            // Weapon costs (0+1)*10 wood and (0+1)*5 metal
            assertEquals(10, updatedPlayer.wood)
            assertEquals(15, updatedPlayer.metal)
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
            val missing = (result as PlayerRepository.UpgradeResult.InsufficientResources).missing
            assertEquals(1, missing.size)
            assertEquals("木頭", missing[0].name)
            assertEquals(10, missing[0].required)
        }
    }
}
