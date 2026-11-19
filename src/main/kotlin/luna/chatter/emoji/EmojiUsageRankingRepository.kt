package luna.chatter.emoji

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.ZoneOffset

/**
 * Per-guild daily custom emoji usage aggregation table keyed by `(guild_id, emoji_id, day)`.
 */
object EmojiUsageDailyTable : Table("emoji_usage_daily") {
    val guildId = varchar("guild_id", 64)
    val emojiId = varchar("emoji_id", 64)
    val day = long("day")
    val usageCount = long("usage_count")

    override val primaryKey = PrimaryKey(guildId, emojiId, day)
}

/**
 * Repository contract for recording and querying per-guild 30-day rolling `Emoji Usage Ranking`.
 */
interface EmojiUsageRankingRepository {
    fun recordUsage(
        guildId: String,
        emojiIds: Collection<String>,
    )

    fun findRollingUsageCounts(
        guildId: String,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): Map<String, Long>

    fun pruneExpired(windowDays: Int = DEFAULT_WINDOW_DAYS): Int

    companion object {
        const val DEFAULT_WINDOW_DAYS = 30
    }
}

/**
 * Exposed SQLite implementation of [EmojiUsageRankingRepository] backed by `PluginContext.database`.
 */
class ExposedEmojiUsageRankingRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : EmojiUsageRankingRepository {
    @Volatile
    private var lastPrunedDay: Long? = null

    init {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(EmojiUsageDailyTable)
        }
    }

    override fun recordUsage(
        guildId: String,
        emojiIds: Collection<String>,
    ) {
        if (guildId.isBlank()) return
        val countsByEmoji = emojiIds.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        if (countsByEmoji.isEmpty()) return

        val today = currentEpochDay()
        transaction(database) {
            maybePruneExpiredInTx(today, EmojiUsageRankingRepository.DEFAULT_WINDOW_DAYS)
            for ((emojiId, delta) in countsByEmoji) {
                val existing =
                    EmojiUsageDailyTable
                        .selectAll()
                        .where(
                            (EmojiUsageDailyTable.guildId eq guildId) and
                                (EmojiUsageDailyTable.emojiId eq emojiId) and
                                (EmojiUsageDailyTable.day eq today),
                        ).singleOrNull()

                if (existing == null) {
                    EmojiUsageDailyTable.insert {
                        it[EmojiUsageDailyTable.guildId] = guildId
                        it[EmojiUsageDailyTable.emojiId] = emojiId
                        it[EmojiUsageDailyTable.day] = today
                        it[EmojiUsageDailyTable.usageCount] = delta.toLong()
                    }
                } else {
                    val updatedCount = existing[EmojiUsageDailyTable.usageCount] + delta.toLong()
                    EmojiUsageDailyTable.update({
                        (EmojiUsageDailyTable.guildId eq guildId) and
                            (EmojiUsageDailyTable.emojiId eq emojiId) and
                            (EmojiUsageDailyTable.day eq today)
                    }) {
                        it[EmojiUsageDailyTable.usageCount] = updatedCount
                    }
                }
            }
        }
    }

    override fun findRollingUsageCounts(
        guildId: String,
        windowDays: Int,
    ): Map<String, Long> {
        if (guildId.isBlank() || windowDays <= 0) return emptyMap()
        val today = currentEpochDay()
        val cutoff = cutoffEpochDay(today, windowDays)

        return transaction(database) {
            maybePruneExpiredInTx(today, windowDays)
            EmojiUsageDailyTable
                .selectAll()
                .where(
                    (EmojiUsageDailyTable.guildId eq guildId) and
                        (EmojiUsageDailyTable.day greaterEq cutoff),
                ).groupingBy { it[EmojiUsageDailyTable.emojiId] }
                .fold(0L) { acc, row -> acc + row[EmojiUsageDailyTable.usageCount] }
        }
    }

    override fun pruneExpired(windowDays: Int): Int {
        val today = currentEpochDay()
        val cutoff = cutoffEpochDay(today, windowDays)
        return transaction(database) {
            lastPrunedDay = today
            EmojiUsageDailyTable.deleteWhere { EmojiUsageDailyTable.day less cutoff }
        }
    }

    private fun maybePruneExpiredInTx(
        today: Long,
        windowDays: Int,
    ) {
        if (lastPrunedDay == today) return
        val cutoff = cutoffEpochDay(today, windowDays)
        EmojiUsageDailyTable.deleteWhere { EmojiUsageDailyTable.day less cutoff }
        lastPrunedDay = today
    }

    private fun currentEpochDay(): Long =
        clock
            .instant()
            .atOffset(ZoneOffset.UTC)
            .toLocalDate()
            .toEpochDay()

    private fun cutoffEpochDay(
        todayEpochDay: Long,
        windowDays: Int,
    ): Long =
        if (windowDays <= 0) {
            todayEpochDay + 1L
        } else {
            todayEpochDay - (windowDays.toLong() - 1L)
        }
}
