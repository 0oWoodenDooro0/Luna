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
     * Formula: (level + 1) * 10
     */
    fun getUpgradeCost(currentLevel: Int): Int {
        return (currentLevel + 1) * 10
    }

    sealed class UpgradeResult {
        data class Success(val player: Player) : UpgradeResult()
        data class InsufficientResources(val missingResource: String, val required: Int, val current: Int) : UpgradeResult()
        object Error : UpgradeResult()
    }

    fun upgradeEquipment(userId: String, type: String): UpgradeResult {
        return transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            val player = PlayersTable.fetchPlayer(userId) ?: run {
                println("UPGRADE FAILED: Player not found for $userId")
                return@transaction UpgradeResult.Error
            }
            
            val currentLevel = when (type.lowercase()) {
                "weapon" -> player.weaponLevel
                "shield" -> player.shieldLevel
                "armor" -> player.armorLevel
                else -> return@transaction UpgradeResult.Error
            }

            val cost = getUpgradeCost(currentLevel)
            
            val wood = player.wood
            val stone = player.stone
            val metal = player.metal

            // Requirements:
            // Weapon: Wood + Metal
            // Shield: Stone + Metal
            // Armor: Wood + Stone
            
            when (type.lowercase()) {
                "weapon" -> {
                    if (wood < cost) return@transaction UpgradeResult.InsufficientResources("木頭", cost, wood)
                    if (metal < cost) return@transaction UpgradeResult.InsufficientResources("金屬", cost, metal)
                    
                    PlayersTable.update({ PlayersTable.id eq userId }) {
                        it[PlayersTable.wood] = wood - cost
                        it[PlayersTable.metal] = metal - cost
                        it[PlayersTable.weaponLevel] = currentLevel + 1
                    }
                }
                "shield" -> {
                    if (stone < cost) return@transaction UpgradeResult.InsufficientResources("石頭", cost, stone)
                    if (metal < cost) return@transaction UpgradeResult.InsufficientResources("金屬", cost, metal)
                    
                    PlayersTable.update({ PlayersTable.id eq userId }) {
                        it[PlayersTable.stone] = stone - cost
                        it[PlayersTable.metal] = metal - cost
                        it[PlayersTable.shieldLevel] = currentLevel + 1
                    }
                }
                "armor" -> {
                    if (wood < cost) return@transaction UpgradeResult.InsufficientResources("木頭", cost, wood)
                    if (stone < cost) return@transaction UpgradeResult.InsufficientResources("石頭", cost, stone)
                    
                    PlayersTable.update({ PlayersTable.id eq userId }) {
                        it[PlayersTable.wood] = wood - cost
                        it[PlayersTable.stone] = stone - cost
                        it[PlayersTable.armorLevel] = currentLevel + 1
                    }
                }
            }
            
            UpgradeResult.Success(PlayersTable.fetchPlayer(userId)!!)
        }
    }
}
