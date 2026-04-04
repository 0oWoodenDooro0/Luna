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
        val cooldownMs = RpgConfig.Recovery.calculateCooldown(player.attributes.maxHp, player.recoveryLevel) * 1000L
        val elapsedMs = System.currentTimeMillis() - player.recoveryStartAt
        return Math.max(0L, (cooldownMs - elapsedMs + 999) / 1000) // Use ceil-like division for display
    }

    /**
     * 是否正在康復中 (精確到毫秒)
     */
    fun isRecovering(player: website.woodendoor.rpg.Player): Boolean {
        if (player.attributes.hp > 0) return false
        val cooldownMs = RpgConfig.Recovery.calculateCooldown(player.attributes.maxHp, player.recoveryLevel) * 1000L
        val elapsedMs = System.currentTimeMillis() - player.recoveryStartAt
        return elapsedMs < cooldownMs
    }

    /**
     * 如果康復時間已到且血量為 0，則恢復滿血
     */
    fun restoreHpIfRecovered(userId: String): Player? {
        return transaction {
            val player = PlayersTable.fetchPlayer(userId) ?: return@transaction null
            if (player.attributes.hp == 0 && !isRecovering(player)) {
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

    /**
     * 計算擊敗怪物後的獎勵
     */
    fun calculateMonsterReward(floor: Int): Pair<String, Int> {
        val resourceName = RpgConfig.Exploration.RESOURCE_NAMES.random()
        val amount = RpgConfig.Economy.MONSTER_REWARD_BASE_AMOUNT + (floor - 1) * RpgConfig.Economy.MONSTER_REWARD_SCALE_PER_FLOOR
        return resourceName to amount
    }

    sealed class UpgradeResult {
        data class Success(val player: Player) : UpgradeResult()
        data class InsufficientResources(val missingResource: String, val required: Int, val current: Int) : UpgradeResult()
        object Error : UpgradeResult()
    }

    fun upgradeEquipment(userId: String, type: String): UpgradeResult {
        val typeKey = type.lowercase()
        val requirements = RpgConfig.Economy.UPGRADE_REQUIREMENTS[typeKey] ?: return UpgradeResult.Error

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
                        it[PlayersTable.atk] = player.attributes.atk + RpgConfig.Upgrade.WEAPON_ATK_BONUS
                    }
                    "shield" -> {
                        it[PlayersTable.shieldLevel] = currentLevel + 1
                        it[PlayersTable.def] = player.attributes.def + RpgConfig.Upgrade.SHIELD_DEF_BONUS
                    }
                    "armor" -> {
                        it[PlayersTable.armorLevel] = currentLevel + 1
                        it[PlayersTable.maxHp] = player.attributes.maxHp + RpgConfig.Upgrade.ARMOR_HP_BONUS
                        it[PlayersTable.hp] = player.attributes.hp + RpgConfig.Upgrade.ARMOR_HP_BONUS // Heal as well
                    }
                    "recovery" -> {
                        it[PlayersTable.recoveryLevel] = currentLevel + 1
                    }
                }
            }
            
            UpgradeResult.Success(PlayersTable.fetchPlayer(userId)!!)
        }
    }

    private fun saveMonsterStateInternal(userId: String, monster: website.woodendoor.rpg.Monster?) {
        PlayersTable.update({ PlayersTable.id eq userId }) {
            if (monster != null) {
                it[PlayersTable.monsterName] = monster.name
                it[PlayersTable.monsterHp] = monster.attributes.hp
                it[PlayersTable.monsterMaxHp] = monster.attributes.maxHp
                it[PlayersTable.monsterAtk] = monster.attributes.atk
                it[PlayersTable.monsterDef] = monster.attributes.def
                it[PlayersTable.monsterSpd] = monster.attributes.spd
            } else {
                it[PlayersTable.monsterName] = null
                it[PlayersTable.monsterHp] = 0
                it[PlayersTable.monsterMaxHp] = 0
                it[PlayersTable.monsterAtk] = 0
                it[PlayersTable.monsterDef] = 0
                it[PlayersTable.monsterSpd] = 0
            }
        }
    }

    fun saveMonsterState(userId: String, monster: website.woodendoor.rpg.Monster?) {
        transaction {
            saveMonsterStateInternal(userId, monster)
        }
    }

    fun loadMonsterState(userId: String): website.woodendoor.rpg.Monster? {
        return transaction {
            PlayersTable.fetchPlayer(userId)?.currentMonster
        }
    }

    fun recordCombatResult(userId: String, playerHP: Int, monsterHP: Int, monster: website.woodendoor.rpg.Monster, reward: Pair<String, Int>? = null) {
        val won = monsterHP <= 0
        transaction {
            PlayersTable.update({ PlayersTable.id eq userId }) {
                it[hp] = playerHP
                if (!won) {
                    it[recoveryStartAt] = System.currentTimeMillis()
                }
                
                if (won && reward != null) {
                    val current = PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()
                    val (resourceName, amount) = reward
                    when (resourceName) {
                        "🪵 木頭" -> it[wood] = current[PlayersTable.wood] + amount
                        "🪨 石頭" -> it[stone] = current[PlayersTable.stone] + amount
                        "🔗 金屬" -> it[metal] = current[PlayersTable.metal] + amount
                    }
                }
            }
            if (!won) {
                // Save monster state with REMAINING hp
                saveMonsterStateInternal(userId, monster.copy(attributes = monster.attributes.copy(hp = monsterHP)))
            } else {
                // Clear monster state on victory
                saveMonsterStateInternal(userId, null)
            }
        }
    }
}
