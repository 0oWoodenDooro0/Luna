package website.woodendoor.repository

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import website.woodendoor.LevelingService
import website.woodendoor.Players

data class PlayerData(
    val userId: String,
    val level: Int,
    val xp: Int,
    val gold: Int,
    val lastHuntTime: Long
)

/**
 * Repository for managing [PlayerData].
 */
object PlayerRepository : ExposedRepository<PlayerData, String, Players>(Players) {

    override fun idColumn(): Column<String> = Players.userId

    override fun toEntity(row: ResultRow): PlayerData = PlayerData(
        userId = row[Players.userId],
        level = row[Players.level],
        xp = row[Players.xp],
        gold = row[Players.gold],
        lastHuntTime = row[Players.lastHuntTime]
    )

    override fun fromEntity(it: UpdateBuilder<*>, entity: PlayerData) {
        it[Players.userId] = entity.userId
        it[Players.level] = entity.level
        it[Players.xp] = entity.xp
        it[Players.gold] = entity.gold
        it[Players.lastHuntTime] = entity.lastHuntTime
    }

    fun isRegistered(targetUserId: String): Boolean {
        return getById(targetUserId) != null
    }

    fun createPlayer(targetUserId: String) {
        create(PlayerData(targetUserId, 1, 0, 0, 0L))
    }

    fun getPlayer(targetUserId: String): PlayerData? {
        return getById(targetUserId)
    }

    fun updateHuntResult(targetUserId: String, newGold: Int, newTime: Long) {
        transaction {
            Players.update({ Players.userId eq targetUserId }) {
                it[gold] = newGold
                it[lastHuntTime] = newTime
            }
        }
    }

    fun setLevel(targetUserId: String, newLevel: Int) {
        transaction {
            Players.update({ Players.userId eq targetUserId }) {
                it[level] = newLevel
            }
        }
    }

    fun addXp(targetUserId: String, xpToAdd: Int): LevelingService.LevelUpResult {
        return transaction {
            val player = getPlayer(targetUserId) ?: throw IllegalArgumentException("Player not found")
            val result = LevelingService.calculateLevelUp(player.level, player.xp, xpToAdd)
            Players.update({ Players.userId eq targetUserId }) {
                it[level] = result.newLevel
                it[xp] = result.newXp
            }
            result
        }
    }
}
