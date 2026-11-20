package luna.chatter.memory

/**
 * Scope of a stored [ChatterMemoryRecord].
 */
enum class MemoryScope(
    val dbValue: String,
) {
    USER("user"),
    CHANNEL("channel"),
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
    CHANNEL,
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
    val channelId: String?,
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
        val channelId: String?,
        val content: String,
        val embedding: FloatArray?,
    ) : MemoryMutation()

    data class Update(
        val id: Long,
        val guildId: String,
        val content: String,
        val embedding: FloatArray?,
    ) : MemoryMutation()

    data class Delete(
        val id: Long,
        val guildId: String,
    ) : MemoryMutation()
}
