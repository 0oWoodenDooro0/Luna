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
        importance: Int = DEFAULT_IMPORTANCE,
        keywords: String = "",
    ): ChatterMemoryRecord

    fun updateMemory(
        id: Long,
        guildId: String,
        content: String,
        embedding: FloatArray? = null,
        expiresAt: Long? = null,
        importance: Int = DEFAULT_IMPORTANCE,
        keywords: String = "",
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

    fun insertUnderstanding(
        guildId: String,
        scope: MemoryScope,
        targetUserId: String? = null,
        subject: String,
        content: String,
    ): SocialUnderstandingRecord

    fun updateUnderstanding(
        id: Long,
        guildId: String,
        subject: String,
        content: String,
    ): Boolean

    fun reaffirmUnderstanding(
        id: Long,
        guildId: String,
    ): Boolean

    fun deleteUnderstanding(
        id: Long,
        guildId: String,
    ): Boolean

    fun deleteOwnedUserUnderstanding(
        id: Long,
        guildId: String,
        userId: String,
    ): Boolean

    fun deleteAllOwnedUserUnderstandings(
        guildId: String,
        userId: String,
    ): Int

    fun listUserUnderstandings(
        guildId: String,
        userId: String,
        limit: Int = Int.MAX_VALUE,
    ): List<SocialUnderstandingRecord>

    fun listGuildUnderstandings(
        guildId: String,
        limit: Int = Int.MAX_VALUE,
    ): List<SocialUnderstandingRecord>

    fun touchUnderstandings(
        guildId: String,
        ids: List<Long>,
    )

    fun applyUnderstandingMutations(mutations: List<UnderstandingMutation>)

    fun getUserRelationship(
        guildId: String,
        userId: String,
        now: Long? = null,
    ): UserRelationship = UserRelationship.neutral(guildId = guildId, userId = userId, now = now ?: 0L)

    fun listUserRelationships(
        guildId: String,
        userIds: Collection<String>,
        now: Long? = null,
    ): Map<String, UserRelationship> = emptyMap()

    fun applyRelationshipUpdate(
        guildId: String,
        userId: String,
        affinityDelta: Int,
        toneStyle: String? = null,
        now: Long? = null,
    ): UserRelationship = UserRelationship.neutral(guildId = guildId, userId = userId, now = now ?: 0L)

    fun deleteUserRelationship(
        guildId: String,
        userId: String,
    ): Boolean = false

    companion object {
        const val DEFAULT_IMPORTANCE = 3
        const val MIN_IMPORTANCE = 1
        const val MAX_IMPORTANCE = 5
        const val MAX_KEYWORDS_CHARS = 120
    }
}
