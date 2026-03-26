package website.woodendoor.repository

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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

object PlayerRepository {

    fun isRegistered(targetUserId: String): Boolean {
        return transaction {
            Players.selectAll().where { Players.userId eq targetUserId }.count() > 0
        }
    }

    fun createPlayer(targetUserId: String) {
        transaction {
            Players.insert {
                it[userId] = targetUserId
                it[lastHuntTime] = 0L
                it[gold] = 0
            }
        }
    }

    fun getPlayer(targetUserId: String): PlayerData? {
        return transaction {
            val row = Players.selectAll().where { Players.userId eq targetUserId }.singleOrNull()

            if (row == null) {
                null
            } else {
                PlayerData(
                    userId = row[Players.userId],
                    level = row[Players.level],
                    xp = row[Players.xp],
                    gold = row[Players.gold],
                    lastHuntTime = row[Players.lastHuntTime]
                )
            }
        }
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
