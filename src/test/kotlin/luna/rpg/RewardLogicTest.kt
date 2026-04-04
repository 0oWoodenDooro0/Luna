package luna.rpg

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import luna.rpg.repository.PlayerRepository
import luna.rpg.repository.PlayersTable
import luna.rpg.Monster
import luna.rpg.RpgAttributes
import luna.rpg.RpgConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewardLogicTest {

    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun testCalculateMonsterReward() {
        // Floor 1: Base (2) + (1-1)*1 = 2
        val (resource1, amount1) = PlayerRepository.calculateMonsterReward(1)
        assertTrue(RpgConfig.Exploration.RESOURCE_NAMES.contains(resource1))
        assertEquals(2, amount1)

        // Floor 5: Base (2) + (5-1)*1 = 6
        val (resource5, amount5) = PlayerRepository.calculateMonsterReward(5)
        assertTrue(RpgConfig.Exploration.RESOURCE_NAMES.contains(resource5))
        assertEquals(6, amount5)
    }

    @Test
    fun testRecordCombatResult_WithReward() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            val userId = "user_reward"
            PlayersTable.insertPlayer(
                id = userId,
                hp = 100, maxHp = 100, atk = 10, def = 5, spd = 8,
                wood = 10, stone = 10, metal = 10, floor = 1
            )

            val monster = Monster("Slime", RpgAttributes(20, 20, 5, 2, 3))
            val reward = "🪵 木頭" to 5

            // Record victory with reward
            PlayerRepository.recordCombatResult(userId, 80, 0, monster, reward)

            val player = PlayersTable.fetchPlayer(userId)!!
            assertEquals(15, player.wood) // 10 + 5
            assertEquals(10, player.stone)
            assertEquals(10, player.metal)
        }
    }
}
