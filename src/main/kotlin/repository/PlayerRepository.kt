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
     * 計算剩餘康復時間 (秒)
     */
    fun getRemainingRecoveryTime(player: Player): Long {
        if (player.attributes.hp > 0) return 0L
        val cooldown = RpgConfig.calculateRecoveryCooldown(player.attributes.maxHp, player.recoveryLevel)
        val elapsed = (System.currentTimeMillis() - player.recoveryStartAt) / 1000
        return Math.max(0L, cooldown - elapsed)
    }

    /**
     * 是否正在康復中
     */
    fun isRecovering(player: Player): Boolean {
        return getRemainingRecoveryTime(player) > 0L
    }

    /**
     * 如果康復時間已到且血量為 0，則恢復滿血
     */
    fun restoreHpIfRecovered(userId: String): Player? {
        return transaction {
            val player = PlayersTable.fetchPlayer(userId) ?: return@transaction null
            if (player.attributes.hp == 0 && getRemainingRecoveryTime(player) == 0L) {
                PlayersTable.update({ PlayersTable.id eq userId }) {
                    it[hp] = player.attributes.maxHp
                }
                PlayersTable.fetchPlayer(userId)!!
            } else {
                player
            }
        }
    }

    /**
     * Calculates the cost of a specific resource for an upgrade.
     */
    fun getResourceCost(currentLevel: Int, baseAmount: Int): Int {
        return (currentLevel + 1) * baseAmount
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
            val player = PlayersTable.fetchPlayer(userId) ?: return@transaction UpgradeResult.Error
            
            val currentLevel = when (typeKey) {
                "weapon" -> player.weaponLevel
                "shield" -> player.shieldLevel
                "armor" -> player.armorLevel
                "recovery" -> player.recoveryLevel
                else -> return@transaction UpgradeResult.Error
            }

            // Check resources dynamically based on config
            for ((resourceName, baseAmount) in requirements) {
                val cost = getResourceCost(currentLevel, baseAmount)
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
                for ((resourceName, baseAmount) in requirements) {
                    val cost = getResourceCost(currentLevel, baseAmount)
                    when (resourceName) {
                        "wood" -> it[PlayersTable.wood] = player.wood - cost
                        "stone" -> it[PlayersTable.stone] = player.stone - cost
                        "metal" -> it[PlayersTable.metal] = player.metal - cost
                    }
                }
                
                when (typeKey) {
                    "weapon" -> {
                        it[PlayersTable.weaponLevel] = currentLevel + 1
                        it[PlayersTable.atk] = player.attributes.atk + RpgConfig.EQUIPMENT_BONUS_PER_LEVEL
                    }
                    "shield" -> {
                        it[PlayersTable.shieldLevel] = currentLevel + 1
                        it[PlayersTable.def] = player.attributes.def + RpgConfig.EQUIPMENT_BONUS_PER_LEVEL
                    }
                    "armor" -> {
                        it[PlayersTable.armorLevel] = currentLevel + 1
                        it[PlayersTable.maxHp] = player.attributes.maxHp + RpgConfig.EQUIPMENT_BONUS_PER_LEVEL
                        it[PlayersTable.hp] = player.attributes.hp + RpgConfig.EQUIPMENT_BONUS_PER_LEVEL // Heal as well
                    }
                    "recovery" -> {
                        it[PlayersTable.recoveryLevel] = currentLevel + 1
                    }
                }
            }
            
            UpgradeResult.Success(PlayersTable.fetchPlayer(userId)!!)
        }
    }
}
