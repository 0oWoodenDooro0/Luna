package luna.chatter.memory

/**
 * Storage and hybrid retrieval contract for Chatter `Long-Term Memory` and `Distillation Cursor` state.
 */
interface ChatterMemoryStore {
    val isVectorSearchEnabled: Boolean

    fun getCursor(channelId: String): ChatterMemoryCursor?

    fun upsertCursor(
        channelId: String,
        guildId: String,
        lastSummarizedMessageId: String,
    ): ChatterMemoryCursor

    fun insertMemory(
        guildId: String,
        scope: MemoryScope,
        targetUserId: String? = null,
        content: String,
        embedding: FloatArray? = null,
        expiresAt: Long? = null,
    ): ChatterMemoryRecord

    fun updateMemory(
        id: Long,
        guildId: String,
        content: String,
        embedding: FloatArray? = null,
        expiresAt: Long? = null,
    ): Boolean

    fun deleteMemory(
        id: Long,
        guildId: String,
    ): Boolean

    fun applyReconciliation(
        mutations: List<MemoryMutation>,
        cursorUpdate: ChatterMemoryCursor? = null,
    )

    fun listUserMemories(
        guildId: String,
        userId: String,
    ): List<ChatterMemoryRecord>

    fun deleteOwnedUserMemory(
        id: Long,
        guildId: String,
        userId: String,
    ): Boolean

    fun deleteAllOwnedUserMemories(
        guildId: String,
        userId: String,
    ): Int

    fun purgeExpiredMemories(nowMs: Long): Int

    fun search(
        guildId: String,
        queryText: String,
        queryEmbedding: FloatArray? = null,
        scope: SearchScope = SearchScope.BOTH,
        targetUserId: String? = null,
        limit: Int = 5,
        recencyExcludeMinutes: Int? = null,
        maxUpdatedAtMs: Long? = null,
        scoreCutoff: Double? = null,
        updateLastAccessed: Boolean = true,
    ): List<ChatterMemorySearchHit>
}
