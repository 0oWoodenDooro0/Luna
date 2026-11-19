package luna.chatter.emoji

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock

/**
 * Global SQLite table storing cached semantic meanings for Discord custom emojis keyed by [emojiId].
 *
 * Kept global across guilds so shared or external Nitro emojis are analyzed at most once.
 * [isManualOverride] is reserved for future administrative overrides.
 */
object CustomEmojiMeaningsTable : Table("custom_emoji_meanings") {
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
 * Persisted semantic cache entry for a single Discord custom emoji.
 */
data class CustomEmojiMeaningRecord(
    val emojiId: String,
    val name: String,
    val isAnimated: Boolean,
    val meaning: String? = null,
    val isManualOverride: Boolean = false,
    val updatedAt: Long,
    val failedUntil: Long? = null,
)

/**
 * Repository contract for reading and updating cached `Custom Emoji Meaning` records.
 */
interface CustomEmojiMeaningRepository {
    fun findById(emojiId: String): CustomEmojiMeaningRecord?

    fun findByIds(emojiIds: Collection<String>): Map<String, CustomEmojiMeaningRecord>

    fun findMeaningMap(emojiIds: Collection<String>): Map<String, String> =
        findByIds(emojiIds)
            .mapNotNull { (id, record) ->
                val cleanMeaning = record.meaning?.trim()?.takeIf { it.isNotEmpty() }
                if (cleanMeaning != null) id to cleanMeaning else null
            }.toMap()

    fun upsertResolvedMeaning(
        emojiId: String,
        name: String,
        isAnimated: Boolean,
        meaning: String,
    )

    fun updateMetadataIfChanged(
        emojiId: String,
        name: String,
        isAnimated: Boolean,
    )

    fun recordFailureCooldown(
        emojiId: String,
        name: String,
        isAnimated: Boolean,
        failedUntilEpochMs: Long,
    )
}

/**
 * Exposed SQLite implementation of [CustomEmojiMeaningRepository] backed by `PluginContext.database`.
 */
class ExposedCustomEmojiMeaningRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : CustomEmojiMeaningRepository {
    init {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(CustomEmojiMeaningsTable)
        }
    }

    override fun findById(emojiId: String): CustomEmojiMeaningRecord? =
        transaction(database) {
            CustomEmojiMeaningsTable
                .selectAll()
                .where(CustomEmojiMeaningsTable.emojiId eq emojiId)
                .singleOrNull()
                ?.toRecord()
        }

    override fun findByIds(emojiIds: Collection<String>): Map<String, CustomEmojiMeaningRecord> {
        val uniqueIds = emojiIds.filter { it.isNotBlank() }.distinct()
        if (uniqueIds.isEmpty()) return emptyMap()

        return transaction(database) {
            CustomEmojiMeaningsTable
                .selectAll()
                .where(CustomEmojiMeaningsTable.emojiId inList uniqueIds)
                .associate { row ->
                    val record = row.toRecord()
                    record.emojiId to record
                }
        }
    }

    override fun upsertResolvedMeaning(
        emojiId: String,
        name: String,
        isAnimated: Boolean,
        meaning: String,
    ) {
        val now = clock.millis()
        val cleanMeaning = meaning.trim()
        transaction(database) {
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

    override fun updateMetadataIfChanged(
        emojiId: String,
        name: String,
        isAnimated: Boolean,
    ) {
        val now = clock.millis()
        transaction(database) {
            val existing =
                CustomEmojiMeaningsTable
                    .selectAll()
                    .where(CustomEmojiMeaningsTable.emojiId eq emojiId)
                    .singleOrNull()
                    ?: return@transaction

            if (existing[CustomEmojiMeaningsTable.name] == name &&
                existing[CustomEmojiMeaningsTable.isAnimated] == isAnimated
            ) {
                return@transaction
            }

            CustomEmojiMeaningsTable.update({ CustomEmojiMeaningsTable.emojiId eq emojiId }) {
                it[CustomEmojiMeaningsTable.name] = name
                it[CustomEmojiMeaningsTable.isAnimated] = isAnimated
                it[CustomEmojiMeaningsTable.updatedAt] = now
            }
        }
    }

    override fun recordFailureCooldown(
        emojiId: String,
        name: String,
        isAnimated: Boolean,
        failedUntilEpochMs: Long,
    ) {
        val now = clock.millis()
        transaction(database) {
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
}
