package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import website.woodendoor.rpg.RpgConfig
import website.woodendoor.rpg.RpgAttributes
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.core.eq
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
            val cooldown = RpgConfig.Recovery.calculateCooldown(maxHp, 0) // 10s
            
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

    @Test
    fun testCooldownReductionAfterUpgrade() {
        val maxHp = 100 // Base cooldown 10s
        
        val playerLvl0 = website.woodendoor.rpg.Player(
            id = "lvl0", name = "Lvl0",
            attributes = website.woodendoor.rpg.RpgAttributes(0, maxHp, 10, 5, 8),
            recoveryLevel = 0,
            recoveryStartAt = System.currentTimeMillis()
        )
        val cooldownLvl0 = PlayerRepository.getRemainingRecoveryTime(playerLvl0)
        assertTrue(cooldownLvl0 in 9..10)
        
        val playerLvl1 = website.woodendoor.rpg.Player(
            id = "lvl1", name = "Lvl1",
            attributes = website.woodendoor.rpg.RpgAttributes(0, maxHp, 10, 5, 8),
            recoveryLevel = 1,
            recoveryStartAt = System.currentTimeMillis()
        )
        val cooldownLvl1 = PlayerRepository.getRemainingRecoveryTime(playerLvl1)
        // Lvl 1 should reduce by 5s. 10 - 5 = 5s.
        assertTrue(cooldownLvl1 in 4..5)
    }

    @Test
    fun testRecordCombatResult_Death() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val userId = "death_user"
            PlayersTable.insertPlayer(
                id = userId, hp = 100, maxHp = 100, atk = 10, def = 5, spd = 8,
                wood = 0, stone = 0, metal = 0, floor = 1, roomsExplored = 5
            )

            val monster = website.woodendoor.rpg.Monster(
                name = "Slime",
                attributes = website.woodendoor.rpg.RpgAttributes(10, 50, 5, 2, 10)
            )

            // When player dies (playerHP = 0, monsterHP = 10)
            PlayerRepository.recordCombatResult(userId, playerHP = 0, monsterHP = 10, monster = monster)

            val player = PlayerRepository.getOrCreatePlayer(userId)
            assertEquals(0, player.attributes.hp)
            assertTrue(player.recoveryStartAt > 0)
            
            // Progress should NOT be incremented
            assertEquals(5, transaction { PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()[PlayersTable.roomsExplored] })

            // Monster state SHOULD be saved
            assertNotNull(player.currentMonster)
            assertEquals(10, player.currentMonster!!.attributes.hp)
            assertEquals("Slime", player.currentMonster!!.name)
        }
    }

    @Test
    fun testCombatResumption() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val userId = "resume_user"
            
            // 1. Setup a player with a saved monster (Slime with 10 HP)
            val savedMonster = website.woodendoor.rpg.Monster(
                name = "Slime",
                attributes = website.woodendoor.rpg.RpgAttributes(10, 50, 5, 2, 10)
            )
            PlayersTable.insertPlayer(
                id = userId, hp = 100, maxHp = 100, atk = 10, def = 5, spd = 8,
                wood = 0, stone = 0, metal = 0, floor = 1, roomsExplored = 5
            )
            PlayerRepository.saveMonsterState(userId, savedMonster)

            // 2. Fetch player and verify monster is there
            val player = PlayerRepository.getOrCreatePlayer(userId)
            assertNotNull(player.currentMonster)
            assertEquals(10, player.currentMonster!!.attributes.hp)

            // 3. Simulate resuming combat (using the saved monster)
            val result = website.woodendoor.rpg.CombatEngine.simulate(player, player.currentMonster!!)
            
            // 4. Record result (Victory this time!)
            PlayerRepository.recordCombatResult(userId, result.playerFinalHP, result.monsterFinalHP, player.currentMonster!!)

            // 5. Verify monster state is cleared and player HP is updated
            val updatedPlayer = PlayerRepository.getOrCreatePlayer(userId)
            assertNull(updatedPlayer.currentMonster)
            assertEquals(result.playerFinalHP, updatedPlayer.attributes.hp)
            assertTrue(result.won)
        }
    }

    @Test
    fun testStillRevivingCheck() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val userId = "reviving_user"
            PlayersTable.insertPlayer(
                id = userId, hp = 50, maxHp = 100, atk = 10, def = 5, spd = 8,
                wood = 0, stone = 0, metal = 0, floor = 1, roomsExplored = 5,
                recoveryStartAt = System.currentTimeMillis()
            )
            
            val player = PlayerRepository.getOrCreatePlayer(userId)
            
            // Should be considered "not ready" if HP < maxHP
            // Note: We need to implement this check in ExploreCommand or PlayerRepository
            assertTrue(player.attributes.hp < player.attributes.maxHp)
        }
    }
}
