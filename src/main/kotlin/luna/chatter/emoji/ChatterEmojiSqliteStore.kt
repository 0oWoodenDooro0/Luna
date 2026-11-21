package luna.chatter.emoji

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
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
 * Global SQLite table storing cached semantic meanings for Discord custom emojis keyed by [emojiId].
 *
 * Kept global across guilds so shared or external Nitro emojis are analyzed at most once.
 * [isManualOverride] is reserved for future administrative overrides.
 */
internal object CustomEmojiMeaningsTable : Table("custom_emoji_meanings") {
    val emojiId = varchar("emoji_id", 64)
    val name = varchar("name", 128)
    val isAnimated = bool("is_animated")
    val meaning = text("meaning").nullable()
    val isManualOverride = bool("is_manual_override").default(false)
    val updatedAt = long("updated_at")
    val failedUntil = long("failed_until").nullable()

    override val primaryKey = PrimaryKey(emojiId)
}

/**
 * Per-guild daily custom emoji usage aggregation table keyed by `(guild_id, emoji_id, day)`.
 */
internal object EmojiUsageDailyTable : Table("emoji_usage_daily") {
    val guildId = varchar("guild_id", 64)
    val emojiId = varchar("emoji_id", 64)
    val day = long("day")
    val usageCount = long("usage_count")

    override val primaryKey = PrimaryKey(guildId, emojiId, day)
}

/**
 * Persisted semantic cache entry for a single Discord custom emoji.
 */
internal data class CustomEmojiMeaningRecord(
    val emojiId: String,
    val name: String,
    val isAnimated: Boolean,
    val meaning: String? = null,
    val isManualOverride: Boolean = false,
    val updatedAt: Long,
    val failedUntil: Long? = null,
)

/**
 * Unified internal SQLite store owning both `custom_emoji_meanings` and `emoji_usage_daily`
 * behind a single [Mutex] on [Dispatchers.IO] so concurrent reads/writes never bypass serialization.
 */
internal class ChatterEmojiSqliteStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dbMutex = Mutex()
    private var lastPrunedDay: Long? = null

    init {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(CustomEmojiMeaningsTable, EmojiUsageDailyTable)
        }
    }

    private suspend fun <T> withDb(block: () -> T): T =
        dbMutex.withLock {
            withContext(Dispatchers.IO) {
                transaction(database) {
                    block()
                }
            }
        }

    suspend fun findMeaningById(emojiId: String): CustomEmojiMeaningRecord? =
        withDb {
            CustomEmojiMeaningsTable
                .selectAll()
                .where(CustomEmojiMeaningsTable.emojiId eq emojiId)
                .singleOrNull()
                ?.toRecord()
        }

    suspend fun findMeaningsByIds(emojiIds: Collection<String>): Map<String, CustomEmojiMeaningRecord> {
        val uniqueIds = emojiIds.filter { it.isNotBlank() }.distinct()
        if (uniqueIds.isEmpty()) return emptyMap()

        return withDb {
            CustomEmojiMeaningsTable
                .selectAll()
                .where(CustomEmojiMeaningsTable.emojiId inList uniqueIds)
                .associate { row ->
                    val record = row.toRecord()
                    record.emojiId to record
                }
        }
    }

    suspend fun findMeaningMap(emojiIds: Collection<String>): Map<String, String> =
        findMeaningsByIds(emojiIds)
            .mapNotNull { (id, record) ->
                val cleanMeaning = record.meaning?.trim()?.takeIf { it.isNotEmpty() }
                if (cleanMeaning != null) id to cleanMeaning else null
            }.toMap()

    suspend fun upsertResolvedMeaning(
        emojiId: String,
        name: String,
        isAnimated: Boolean,
        meaning: String,
    ) {
        val now = clock.millis()
        val cleanMeaning = meaning.trim()
        withDb {
            val existing =
                CustomEmojiMeaningsTable
                    .selectAll()
                    .where(CustomEmojiMeaningsTable.emojiId eq emojiId)
                    .singleOrNull()

            if (existing == null) {
                CustomEmojiMeaningsTable.insert {
                    it[CustomEmojiMeaningsTable.emojiId] = emojiId
                    it[CustomEmojiMeaningsTable.name] = name
                    it[CustomEmojiMeaningsTable.isAnimated] = isAnimated
                    it[CustomEmojiMeaningsTable.meaning] = cleanMeaning
                    it[CustomEmojiMeaningsTable.isManualOverride] = false
                    it[CustomEmojiMeaningsTable.updatedAt] = now
                    it[CustomEmojiMeaningsTable.failedUntil] = null
                }
            } else {
                val manualOverride = existing[CustomEmojiMeaningsTable.isManualOverride]
                CustomEmojiMeaningsTable.update({ CustomEmojiMeaningsTable.emojiId eq emojiId }) {
                    it[CustomEmojiMeaningsTable.name] = name
                    it[CustomEmojiMeaningsTable.isAnimated] = isAnimated
                    it[CustomEmojiMeaningsTable.updatedAt] = now
                    if (!manualOverride) {
                        it[CustomEmojiMeaningsTable.meaning] = cleanMeaning
                        it[CustomEmojiMeaningsTable.failedUntil] = null
                    }
                }
            }
        }
    }

    suspend fun syncMetadataIfChanged(targets: List<EmojiVisionRequest>) {
        if (targets.isEmpty()) return
        val uniqueIds = targets.map { it.id }.filter { it.isNotBlank() }.distinct()
        if (uniqueIds.isEmpty()) return

        val now = clock.millis()
        withDb {
            val existingById =
                CustomEmojiMeaningsTable
                    .selectAll()
                    .where(CustomEmojiMeaningsTable.emojiId inList uniqueIds)
                    .associate { row ->
                        val record = row.toRecord()
                        record.emojiId to record
                    }
            for (target in targets) {
                val existing = existingById[target.id] ?: continue
                if (existing.name == target.name && existing.isAnimated == target.isAnimated) continue
                CustomEmojiMeaningsTable.update({ CustomEmojiMeaningsTable.emojiId eq target.id }) {
                    it[CustomEmojiMeaningsTable.name] = target.name
                    it[CustomEmojiMeaningsTable.isAnimated] = target.isAnimated
                    it[CustomEmojiMeaningsTable.updatedAt] = now
                }
            }
        }
    }

    suspend fun recordFailureCooldown(
        emojiId: String,
        name: String,
        isAnimated: Boolean,
        failedUntilEpochMs: Long,
    ) {
        val now = clock.millis()
        withDb {
            val existing =
                CustomEmojiMeaningsTable
                    .selectAll()
                    .where(CustomEmojiMeaningsTable.emojiId eq emojiId)
                    .singleOrNull()

            if (existing == null) {
                CustomEmojiMeaningsTable.insert {
                    it[CustomEmojiMeaningsTable.emojiId] = emojiId
                    it[CustomEmojiMeaningsTable.name] = name
                    it[CustomEmojiMeaningsTable.isAnimated] = isAnimated
                    it[CustomEmojiMeaningsTable.meaning] = null
                    it[CustomEmojiMeaningsTable.isManualOverride] = false
                    it[CustomEmojiMeaningsTable.updatedAt] = now
                    it[CustomEmojiMeaningsTable.failedUntil] = failedUntilEpochMs
                }
            } else if (!existing[CustomEmojiMeaningsTable.isManualOverride]) {
                CustomEmojiMeaningsTable.update({ CustomEmojiMeaningsTable.emojiId eq emojiId }) {
                    it[CustomEmojiMeaningsTable.name] = name
                    it[CustomEmojiMeaningsTable.isAnimated] = isAnimated
                    it[CustomEmojiMeaningsTable.updatedAt] = now
                    it[CustomEmojiMeaningsTable.failedUntil] = failedUntilEpochMs
                }
            }
        }
    }

    suspend fun recordUsage(
        scopeKey: String,
        emojiIds: Collection<String>,
    ) {
        if (scopeKey.isBlank()) return
        val countsByEmoji = emojiIds.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        if (countsByEmoji.isEmpty()) return

        val today = currentEpochDay()
        withDb {
            maybePruneExpiredInTx(today, DEFAULT_WINDOW_DAYS)
            for ((emojiId, delta) in countsByEmoji) {
                val existing =
                    EmojiUsageDailyTable
                        .selectAll()
                        .where(
                            (EmojiUsageDailyTable.guildId eq scopeKey) and
                                (EmojiUsageDailyTable.emojiId eq emojiId) and
                                (EmojiUsageDailyTable.day eq today),
                        ).singleOrNull()

                if (existing == null) {
                    EmojiUsageDailyTable.insert {
                        it[EmojiUsageDailyTable.guildId] = scopeKey
                        it[EmojiUsageDailyTable.emojiId] = emojiId
                        it[EmojiUsageDailyTable.day] = today
                        it[EmojiUsageDailyTable.usageCount] = delta.toLong()
                    }
                } else {
                    val updatedCount = existing[EmojiUsageDailyTable.usageCount] + delta.toLong()
                    EmojiUsageDailyTable.update({
                        (EmojiUsageDailyTable.guildId eq scopeKey) and
                            (EmojiUsageDailyTable.emojiId eq emojiId) and
                            (EmojiUsageDailyTable.day eq today)
                    }) {
                        it[EmojiUsageDailyTable.usageCount] = updatedCount
                    }
                }
            }
        }
    }

    suspend fun findRollingUsageCounts(
        scopeKey: String,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): Map<String, Long> {
        if (scopeKey.isBlank() || windowDays <= 0) return emptyMap()
        val today = currentEpochDay()
        val cutoff = cutoffEpochDay(today, windowDays)

        return withDb {
            maybePruneExpiredInTx(today, windowDays)
            EmojiUsageDailyTable
                .selectAll()
                .where(
                    (EmojiUsageDailyTable.guildId eq scopeKey) and
                        (EmojiUsageDailyTable.day greaterEq cutoff),
                ).groupingBy { it[EmojiUsageDailyTable.emojiId] }
                .fold(0L) { acc, row -> acc + row[EmojiUsageDailyTable.usageCount] }
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

    private fun ResultRow.toRecord(): CustomEmojiMeaningRecord =
        CustomEmojiMeaningRecord(
            emojiId = this[CustomEmojiMeaningsTable.emojiId],
            name = this[CustomEmojiMeaningsTable.name],
            isAnimated = this[CustomEmojiMeaningsTable.isAnimated],
            meaning = this[CustomEmojiMeaningsTable.meaning],
            isManualOverride = this[CustomEmojiMeaningsTable.isManualOverride],
            updatedAt = this[CustomEmojiMeaningsTable.updatedAt],
            failedUntil = this[CustomEmojiMeaningsTable.failedUntil],
        )

    companion object {
        const val DEFAULT_WINDOW_DAYS = 30
    }
}
