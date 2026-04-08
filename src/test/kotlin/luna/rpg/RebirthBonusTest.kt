package luna.rpg

import luna.rpg.repository.PlayerRepository
import luna.rpg.repository.PlayersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RebirthBonusTest {
    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite:file:testdb_rebirth?mode=memory&cache=shared", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(PlayersTable)
        }
    }

    @Test
    fun `test calculateMonsterReward applies Resourceful bonus`() {
        val playerWithBonus =
            Player(
                id = "bonus_user",
                name = "Bonus",
                attributes = RpgAttributes(100, 100, 10, 5, 8),
                rebirthResourceLevel = 5, // 5 * 0.05 = +25%
            )

        // Base for Floor 5 where base is 6. 6 * 1.25 = 7.5 -> 7 (Int)
        val (_, amount) = PlayerRepository.calculateMonsterReward(5, playerWithBonus)
        assertEquals(7, amount)
    }

    @Test
    fun `test player resource and efficiency bonuses`() {
        val player =
            Player(
                id = "test",
                name = "test",
                attributes = RpgAttributes(100, 100, 10, 5, 8),
                rebirthResourceLevel = 2, // 1 + 2 * 0.05 = 1.10
                rebirthEfficientLevel = 3, // 1 - 3 * 0.05 = 0.85
            )

        assertEquals(1.10, player.calculateResourceBonus(), 0.001)
        assertEquals(0.85, player.calculateEfficiencyBonus(), 0.001)
    }

    @Test
    fun `test getResourceCost applies Efficient bonus`() {
        val playerWithBonus =
            Player(
                id = "efficient_user",
                name = "Efficient",
                attributes = RpgAttributes(100, 100, 10, 5, 8),
                rebirthEfficientLevel = 4, // 4 * 0.05 = -20%
            )

        // Level 0, base amount 10. Base cost = (0+1)*10 = 10.
        // 10 * 0.8 = 8.
        assertEquals(8, PlayerRepository.getResourceCost(0, 10, playerWithBonus))

        // Level 4, base amount 10. Base cost = (4+1)*10 = 50.
        // 50 * 0.8 = 40.
        assertEquals(40, PlayerRepository.getResourceCost(4, 10, playerWithBonus))
    }

    @Test
    fun `test upgradeEquipment applies Efficient bonus`() {
        val userId = "efficient_upgrade_user"
        transaction {
            PlayerRepository.getOrCreatePlayer(userId)
            PlayersTable.update({ PlayersTable.id eq userId }) {
                it[wood] = 10
                it[metal] = 10
                it[rebirthEfficientLevel] = 4 // -20% cost
            }

            // Weapon costs at Lv.0: 10 wood, 5 metal.
            // With -20% bonus: 8 wood, 4 metal.
            val result = PlayerRepository.upgradeEquipment(userId, "weapon")
            assertTrue(result is PlayerRepository.UpgradeResult.Success)
            
            val updatedPlayer = (result as PlayerRepository.UpgradeResult.Success).player
            // 10 - 8 = 2
            assertEquals(2, updatedPlayer.wood)
            // 10 - 4 = 6
            assertEquals(6, updatedPlayer.metal)
        }
    }
}
