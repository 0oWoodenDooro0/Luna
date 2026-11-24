package luna.chatter.memory

/**
 * Scope of a stored [ChatterMemoryRecord].
 */
enum class MemoryScope(
    val dbValue: String,
) {
    USER("user"),
    GUILD("guild"),
    ;

    companion object {
        fun fromDbValue(value: String): MemoryScope =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown memory scope: $value")
    }
}

/**
 * Query scope filter for [ChatterMemoryStore.search].
 */
enum class SearchScope {
    USER,
    GUILD,
    BOTH,
}

/**
 * Persisted self-contained `Atomic Fact` in `Long-Term Memory`.
 */
data class ChatterMemoryRecord(
    val id: Long,
    val guildId: String,
    val scope: MemoryScope,
    val targetUserId: String?,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long,
    val expiresAt: Long? = null,
    val importance: Int = ChatterMemoryStore.DEFAULT_IMPORTANCE,
    val keywords: String = "",
)

/**
 * Persisted pure-relational `Social Understanding` impression in `Long-Term Memory`.
 */
data class SocialUnderstandingRecord(
    val id: Long,
    val guildId: String,
    val scope: MemoryScope,
    val targetUserId: String?,
    val subject: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long,
)

/**
 * Ranked retrieval hit returned by [ChatterMemoryStore.search].
 */
data class ChatterMemorySearchHit(
    val memory: ChatterMemoryRecord,
    val score: Double,
    val cosineSimilarity: Double? = null,
    val matchedByFts: Boolean = false,
    val matchedByVector: Boolean = false,
)

/**
 * Per-channel `Distillation Cursor` tracking the last processed Discord message ID.
 */
data class ChatterMemoryCursor(
    val channelId: String,
    val guildId: String,
    val lastSummarizedMessageId: String,
    val updatedAt: Long,
)

/**
 * Transactional memory mutation applied during `Memory Distillation` reconciliation.
 */
sealed class MemoryMutation {
    data class Insert(
        val guildId: String,
        val scope: MemoryScope,
        val targetUserId: String?,
        val content: String,
        val embedding: FloatArray?,
        val expiresAt: Long? = null,
        val importance: Int = ChatterMemoryStore.DEFAULT_IMPORTANCE,
        val keywords: String = "",
    ) : MemoryMutation()

    data class Update(
        val id: Long,
        val guildId: String,
        val content: String,
        val embedding: FloatArray?,
        val expiresAt: Long? = null,
        val importance: Int = ChatterMemoryStore.DEFAULT_IMPORTANCE,
        val keywords: String = "",
    ) : MemoryMutation()

    data class Delete(
        val id: Long,
        val guildId: String,
    ) : MemoryMutation()
}

/**
 * Transactional `Social Understanding` mutation applied during `Memory Distillation` Stage 3.
 */
sealed class UnderstandingMutation {
    data class Add(
        val guildId: String,
        val scope: MemoryScope,
        val targetUserId: String?,
        val subject: String,
        val content: String,
    ) : UnderstandingMutation()

    data class Update(
        val id: Long,
        val guildId: String,
        val subject: String,
        val content: String,
    ) : UnderstandingMutation()

    data class Reaffirm(
        val id: Long,
        val guildId: String,
    ) : UnderstandingMutation()

    data class Delete(
        val id: Long,
        val guildId: String,
    ) : UnderstandingMutation()
}
