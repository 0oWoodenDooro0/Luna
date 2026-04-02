package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import website.woodendoor.rpg.Player
import website.woodendoor.rpg.RpgConfig

object PlayerRepository {
    fun getOrCreatePlayer(userId: String): Player {
        return transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            PlayersTable.fetchPlayer(userId) ?: run {
                PlayersTable.insertPlayer(
                    id = userId,
                    hp = 100,
                    maxHp = 100,
                    atk = 10,
                    def = 5,
                    spd = 8,
                    wood = 0,
                    stone = 0,
                    metal = 0,
                    floor = 1
                )
                PlayersTable.fetchPlayer(userId)!!
            }
        }
    }

    /**
     * Calculates the cost of an upgrade based on the current level.
     */
    fun getUpgradeCost(currentLevel: Int): Int {
        return (currentLevel + 1) * RpgConfig.UPGRADE_BASE_COST
    }

    sealed class UpgradeResult {
        data class Success(val player: Player) : UpgradeResult()
        data class InsufficientResources(val missingResource: String, val required: Int, val current: Int) : UpgradeResult()
        object Error : UpgradeResult()
    }

    fun upgradeEquipment(userId: String, type: String): UpgradeResult {
        val typeKey = type.lowercase()
        val requirements = RpgConfig.UPGRADE_REQUIREMENTS[typeKey] ?: return UpgradeResult.Error

        return transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val player = PlayersTable.fetchPlayer(userId) ?: return@transaction UpgradeResult.Error
            
            val currentLevel = when (typeKey) {
                "weapon" -> player.weaponLevel
                "shield" -> player.shieldLevel
                "armor" -> player.armorLevel
                else -> return@transaction UpgradeResult.Error
            }

            val cost = getUpgradeCost(currentLevel)
            
            // Check resources dynamically based on config
            for (resourceName in requirements) {
                val (currentValue, displayName) = when (resourceName) {
                    "wood" -> player.wood to "木頭"
                    "stone" -> player.stone to "石頭"
                    "metal" -> player.metal to "金屬"
                    else -> 0 to "未知資源"
                }
                
                if (currentValue < cost) {
                    return@transaction UpgradeResult.InsufficientResources(displayName, cost, currentValue)
                }
            }

            // Deduct resources and upgrade
            PlayersTable.update({ PlayersTable.id eq userId }) {
                for (resourceName in requirements) {
                    when (resourceName) {
                        "wood" -> it[PlayersTable.wood] = player.wood - cost
                        "stone" -> it[PlayersTable.stone] = player.stone - cost
                        "metal" -> it[PlayersTable.metal] = player.metal - cost
                    }
                }
                
                when (typeKey) {
                    "weapon" -> it[PlayersTable.weaponLevel] = currentLevel + 1
                    "shield" -> it[PlayersTable.shieldLevel] = currentLevel + 1
                    "armor" -> it[PlayersTable.armorLevel] = currentLevel + 1
                }
            }
            
            UpgradeResult.Success(PlayersTable.fetchPlayer(userId)!!)
        }
    }
}
