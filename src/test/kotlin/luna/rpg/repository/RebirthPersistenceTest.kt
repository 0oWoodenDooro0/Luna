package luna.rpg.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs

class RebirthPersistenceTest {

    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun testRebirthFieldsPersistence() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            // This will fail to compile until PlayersTable and Player are updated
            PlayersTable.insertPlayer(
                id = "user_rebirth",
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 1,
                rebirthCount = 2,
                rebirthPoints = 5,
                rebirthAtkLevel = 1,
                rebirthDefLevel = 2,
                rebirthSpdLevel = 3,
                rebirthRecoveryLevel = 4,
                rebirthHpLevel = 5
            )

            val player = PlayersTable.fetchPlayer("user_rebirth")
            assertNotNull(player)
            assertEquals(2, player.rebirthCount)
            assertEquals(5, player.rebirthPoints)
            assertEquals(1, player.rebirthAtkLevel)
            assertEquals(2, player.rebirthDefLevel)
            assertEquals(3, player.rebirthSpdLevel)
            assertEquals(4, player.rebirthRecoveryLevel)
            assertEquals(5, player.rebirthHpLevel)
        }
    }

    @Test
    fun testRebirthPlayerIntegration() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            // 1. Create a high-level player
            PlayersTable.insertPlayer(
                id = "rebirth_user",
                hp = 50,
                maxHp = 150,
                atk = 20,
                def = 15,
                spd = 12,
                wood = 100,
                stone = 100,
                metal = 100,
                floor = 60, // Eligible for rebirth (MIN_FLOOR=50)
                roomsExplored = 2,
                weaponLevel = 5,
                shieldLevel = 5,
                armorLevel = 5,
                recoveryLevel = 5
            )

            // 2. Perform rebirth
            // This will be implemented in PlayerRepository
            PlayerRepository.rebirthPlayer("rebirth_user")

            // 3. Verify reset state
            val player = PlayersTable.fetchPlayer("rebirth_user")
            assertNotNull(player)
            assertEquals(1, player.rebirthCount)
            assertEquals(1, player.rebirthPoints) // (60-50)/10 = 1 point
            assertEquals(1, player.currentFloor)
            assertEquals(0, player.roomsExplored)
            assertEquals(0, player.wood)
            assertEquals(100, player.attributes.hp)
            assertEquals(100, player.attributes.maxHp)
            assertEquals(0, player.weaponLevel)
        }
    }

    @Test
    fun testUpgradeRebirthStatIntegration() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            // 1. Create a player with rebirth points
            PlayersTable.insertPlayer(
                id = "upgrade_user",
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 1,
                rebirthPoints = 10
            )

            // 2. Upgrade ATK (Level 0 -> 1, cost 1)
            val result = PlayerRepository.upgradeRebirthStat("upgrade_user", "ATK")
            assertIs<PlayerRepository.RebirthUpgradeResult.Success>(result)
            assertEquals(1, result.player.rebirthAtkLevel)
            assertEquals(9, result.player.rebirthPoints)
            assertEquals(10, result.player.effectiveAttributes.atk) // 10 * 1.05 = 10.5 -> 10

            // 3. Upgrade again until level 2
            val result2 = PlayerRepository.upgradeRebirthStat("upgrade_user", "ATK")
            assertIs<PlayerRepository.RebirthUpgradeResult.Success>(result2)
            assertEquals(2, result2.player.rebirthAtkLevel)
            assertEquals(7, result2.player.rebirthPoints) // 9 - 2 = 7
            assertEquals(11, result2.player.effectiveAttributes.atk) // 10 * 1.10 = 11

            // 4. Verify in DB
            val player = PlayersTable.fetchPlayer("upgrade_user")
            assertNotNull(player)
            assertEquals(2, player.rebirthAtkLevel)
            assertEquals(7, player.rebirthPoints)
        }
    }
}
