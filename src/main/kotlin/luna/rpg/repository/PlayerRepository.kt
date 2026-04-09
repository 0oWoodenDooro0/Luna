package luna.rpg.repository

import luna.rpg.Player
import luna.rpg.PlayerProgression
import luna.rpg.RpgConfig
import luna.rpg.UpdateProgressionResult
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

object PlayerRepository {
    fun getOrCreatePlayer(userId: String): Player =
        transaction {
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
                    floor = 1,
                )
                PlayersTable.fetchPlayer(userId)!!
            }
        }

    fun getProgression(userId: String): PlayerProgression =
        transaction {
            val player = getOrCreatePlayer(userId)
            player.progression
        }

    fun addResources(
        userId: String,
        resourceName: String,
        amount: Int,
    ) {
        transaction {
            val current = PlayersTable.selectAll().where { PlayersTable.id eq userId }.single()
            PlayersTable.update({ PlayersTable.id eq userId }) {
                when (resourceName) {
                    "🪵 木頭" -> it[wood] = current[PlayersTable.wood] + amount
                    "🪨 石頭" -> it[stone] = current[PlayersTable.stone] + amount
                    "🔗 金屬" -> it[metal] = current[PlayersTable.metal] + amount
                }
            }
        }
    }

    fun updateProgression(
        userId: String,
        currentFloor: Int,
        roomsExplored: Int,
    ): UpdateProgressionResult {
        val floorSize = RpgConfig.Exploration.FLOOR_SIZE

        val nextRoomCount = roomsExplored + 1
        var message = ""
        var finalRoomCount = nextRoomCount

        return transaction {
            if (nextRoomCount >= floorSize) {
                PlayersTable.update({ PlayersTable.id eq userId }) {
                    it[this.currentFloor] = currentFloor + 1
                    it[this.roomsExplored] = 0
                }
                message = "✨ 此層已探索完成！自動前往第 ${currentFloor + 1} 層。"
                finalRoomCount = 0
            } else {
                PlayersTable.update({ PlayersTable.id eq userId }) {
                    it[this.roomsExplored] = nextRoomCount
                }
            }
            UpdateProgressionResult(finalRoomCount, message)
        }
    }

    fun getRemainingRecoveryTime(player: Player): Long {
        if (player.attributes.hp > 0) return 0L
        val cooldownMs = player.calculateRecoveryCooldown() * 1000L
        val elapsedMs = System.currentTimeMillis() - player.recoveryStartAt
        return Math.max(0L, (cooldownMs - elapsedMs + 999) / 1000) // Use ceil-like division for display
    }

    /**
     * 是否正在康復中 (精確到毫秒)
     */
    fun isRecovering(player: luna.rpg.Player): Boolean {
        if (player.attributes.hp > 0) return false
        val cooldownMs = player.calculateRecoveryCooldown() * 1000L
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
    fun getResourceCost(
        currentLevel: Int,
        baseAmount: Int,
        player: Player? = null,
    ): Int {
        val baseCost = (currentLevel + 1) * baseAmount
        val bonus = player?.calculateEfficiencyBonus() ?: 1.0
        return (baseCost * bonus).toInt()
    }

    /**
     * 計算擊敗怪物後的獎勵
     */
    fun calculateMonsterReward(
        floor: Int,
        player: Player? = null,
    ): Pair<String, Int> {
        val resourceName = RpgConfig.Exploration.RESOURCE_NAMES.random()
        val baseAmount = RpgConfig.Economy.MONSTER_REWARD_BASE_AMOUNT + (floor - 1) * RpgConfig.Economy.MONSTER_REWARD_SCALE_PER_FLOOR
        val bonus = player?.calculateResourceBonus() ?: 1.0
        val finalAmount = (baseAmount * bonus).toInt()
        return resourceName to finalAmount
    }

    data class MissingResource(
        val name: String,
        val required: Int,
        val current: Int,
    )

    sealed class UpgradeResult {
        data class Success(
            val player: Player,
        ) : UpgradeResult()

        data class InsufficientResources(
            val missing: List<MissingResource>,
        ) : UpgradeResult()

        object Error : UpgradeResult()
    }

    fun upgradeEquipment(
        userId: String,
        type: String,
    ): UpgradeResult {
        val typeKey = type.lowercase()
        val requirements = RpgConfig.Economy.UPGRADE_REQUIREMENTS[typeKey] ?: return UpgradeResult.Error

        return transaction {
            val player = PlayersTable.fetchPlayer(userId) ?: return@transaction UpgradeResult.Error

            val currentLevel =
                when (typeKey) {
                    "weapon" -> player.weaponLevel
                    "shield" -> player.shieldLevel
                    "armor" -> player.armorLevel
                    "recovery" -> player.recoveryLevel
                    else -> return@transaction UpgradeResult.Error
                }

            val missing = mutableListOf<MissingResource>()
            // Check resources dynamically based on config
            for ((resourceName, baseAmount) in requirements) {
                val cost = getResourceCost(currentLevel, baseAmount, player)
                val (currentValue, displayName) =
                    when (resourceName) {
                        "wood" -> player.wood to "木頭"
                        "stone" -> player.stone to "石頭"
                        "metal" -> player.metal to "金屬"
                        else -> 0 to "未知資源"
                    }

                if (currentValue < cost) {
                    missing.add(MissingResource(displayName, cost, currentValue))
                }
            }

            if (missing.isNotEmpty()) {
                return@transaction UpgradeResult.InsufficientResources(missing)
            }

            // Deduct resources and upgrade
            PlayersTable.update({ PlayersTable.id eq userId }) {
                for ((resourceName, baseAmount) in requirements) {
                    val cost = getResourceCost(currentLevel, baseAmount, player)
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

    private fun saveMonsterStateInternal(
        userId: String,
        monster: luna.rpg.Monster?,
    ) {
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

    fun saveMonsterState(
        userId: String,
        monster: luna.rpg.Monster?,
    ) {
        transaction {
            saveMonsterStateInternal(userId, monster)
        }
    }

    fun loadMonsterState(userId: String): luna.rpg.Monster? =
        transaction {
            PlayersTable.fetchPlayer(userId)?.currentMonster
        }

    sealed class RebirthUpgradeResult {
        data class Success(
            val player: Player,
        ) : RebirthUpgradeResult()

        data class InsufficientPoints(
            val required: Int,
            val current: Int,
        ) : RebirthUpgradeResult()

        object MaxLevelReached : RebirthUpgradeResult()

        object Error : RebirthUpgradeResult()
    }

    fun upgradeRebirthStat(
        userId: String,
        statType: String,
    ): RebirthUpgradeResult {
        val type = statType.uppercase()
        return transaction {
            val player = PlayersTable.fetchPlayer(userId) ?: return@transaction RebirthUpgradeResult.Error

            val currentLevel =
                when (type) {
                    "ATK" -> player.rebirthAtkLevel
                    "DEF" -> player.rebirthDefLevel
                    "SPD" -> player.rebirthSpdLevel
                    "RECOVERY" -> player.rebirthRecoveryLevel
                    "HP" -> player.rebirthHpLevel
                    "RESOURCE" -> player.rebirthResourceLevel
                    "EFFICIENT" -> player.rebirthEfficientLevel
                    else -> return@transaction RebirthUpgradeResult.Error
                }

            val maxLevel =
                when (type) {
                    "RESOURCE" -> RpgConfig.Rebirth.MAX_RESOURCE_LEVEL
                    "EFFICIENT" -> RpgConfig.Rebirth.MAX_EFFICIENT_LEVEL
                    else -> RpgConfig.Rebirth.MAX_STAT_LEVEL
                }

            if (currentLevel >= maxLevel) {
                return@transaction RebirthUpgradeResult.MaxLevelReached
            }

            val cost = player.calculateStatUpgradeCost(currentLevel)
            if (player.rebirthPoints < cost) {
                return@transaction RebirthUpgradeResult.InsufficientPoints(cost, player.rebirthPoints)
            }

            PlayersTable.update({ PlayersTable.id eq userId }) {
                it[rebirthPoints] = player.rebirthPoints - cost
                when (type) {
                    "ATK" -> it[rebirthAtkLevel] = currentLevel + 1
                    "DEF" -> it[rebirthDefLevel] = currentLevel + 1
                    "SPD" -> it[rebirthSpdLevel] = currentLevel + 1
                    "RECOVERY" -> it[rebirthRecoveryLevel] = currentLevel + 1
                    "HP" -> it[rebirthHpLevel] = currentLevel + 1
                    "RESOURCE" -> it[rebirthResourceLevel] = currentLevel + 1
                    "EFFICIENT" -> it[rebirthEfficientLevel] = currentLevel + 1
                }
            }

            RebirthUpgradeResult.Success(PlayersTable.fetchPlayer(userId)!!)
        }
    }

    /**
     * Performs a rebirth for the player.
     * Resets level, equipment, and resources, and grants rebirth points.
     */
    fun rebirthPlayer(userId: String): Player? {
        return transaction {
            val player = PlayersTable.fetchPlayer(userId) ?: return@transaction null
            if (!player.canRebirth()) return@transaction player

            val earnedPoints = player.calculateEarnedPoints()
            val resetPlayer = player.rebirthReset(earnedPoints)

            PlayersTable.update({ PlayersTable.id eq userId }) {
                it[hp] = resetPlayer.attributes.hp
                it[maxHp] = resetPlayer.attributes.maxHp
                it[atk] = resetPlayer.attributes.atk
                it[def] = resetPlayer.attributes.def
                it[spd] = resetPlayer.attributes.spd
                it[wood] = resetPlayer.wood
                it[stone] = resetPlayer.stone
                it[metal] = resetPlayer.metal
                it[currentFloor] = resetPlayer.currentFloor
                it[roomsExplored] = resetPlayer.roomsExplored
                it[weaponLevel] = resetPlayer.weaponLevel
                it[shieldLevel] = resetPlayer.shieldLevel
                it[armorLevel] = resetPlayer.armorLevel
                it[recoveryLevel] = resetPlayer.recoveryLevel
                it[recoveryStartAt] = resetPlayer.recoveryStartAt
                it[rebirthCount] = resetPlayer.rebirthCount
                it[rebirthPoints] = resetPlayer.rebirthPoints
                it[rebirthAtkLevel] = resetPlayer.rebirthAtkLevel
                it[rebirthDefLevel] = resetPlayer.rebirthDefLevel
                it[rebirthSpdLevel] = resetPlayer.rebirthSpdLevel
                it[rebirthRecoveryLevel] = resetPlayer.rebirthRecoveryLevel
                it[rebirthHpLevel] = resetPlayer.rebirthHpLevel
                it[rebirthResourceLevel] = resetPlayer.rebirthResourceLevel
                it[rebirthEfficientLevel] = resetPlayer.rebirthEfficientLevel
                it[monsterName] = null
                it[monsterHp] = 0
            }

            PlayersTable.fetchPlayer(userId)
        }
    }

    fun recordCombatResult(
        userId: String,
        playerHP: Int,
        monsterHP: Int,
        monster: luna.rpg.Monster,
        reward: Pair<String, Int>? = null,
    ) {
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
