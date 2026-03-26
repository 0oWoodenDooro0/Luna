package website.woodendoor.repository

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
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
}
