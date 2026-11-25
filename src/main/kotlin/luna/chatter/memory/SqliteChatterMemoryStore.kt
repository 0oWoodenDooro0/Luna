package luna.chatter.memory

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Statement
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * SQLite-backed hybrid (`sqlite-vec` cosine + `FTS5 trigram` / `LIKE` fallback) implementation
 * of [ChatterMemoryStore] with guild isolation, recency exclusion, and per-guild LRU eviction.
 */
class SqliteChatterMemoryStore(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val defaultRecencyExcludeMinutes: Int = DEFAULT_RECENCY_EXCLUDE_MINUTES,
    private val defaultScoreCutoff: Double = DEFAULT_SCORE_CUTOFF,
    private val maxPerUser: Int = DEFAULT_MAX_PER_USER,
    private val maxPerGuild: Int = DEFAULT_MAX_PER_GUILD,
    private val embeddingDimension: Int = DEFAULT_EMBEDDING_DIMENSION,
    private val maxUnderstandingsPerUser: Int = DEFAULT_MAX_UNDERSTANDINGS_PER_USER,
    private val maxUnderstandingsPerGuild: Int = DEFAULT_MAX_UNDERSTANDINGS_PER_GUILD,
) : ChatterMemoryStore {
    private val logger = LoggerFactory.getLogger(SqliteChatterMemoryStore::class.java)

    override val isVectorSearchEnabled: Boolean

    init {
        require(defaultRecencyExcludeMinutes >= 0) { "defaultRecencyExcludeMinutes must be >= 0" }
        require(defaultScoreCutoff in 0.0..1.0) { "defaultScoreCutoff must be in 0.0..1.0" }
        require(maxPerUser >= 1) { "maxPerUser must be >= 1" }
        require(maxPerGuild >= 1) { "maxPerGuild must be >= 1" }
        require(embeddingDimension >= 1) { "embeddingDimension must be >= 1" }
        require(maxUnderstandingsPerUser >= 1) { "maxUnderstandingsPerUser must be >= 1" }
        require(maxUnderstandingsPerGuild >= 1) { "maxUnderstandingsPerGuild must be >= 1" }

        isVectorSearchEnabled =
            transaction(database) {
                val conn = currentJdbcConnection()
                val migrated = ChatterMemorySchemaMigrator.initializeAndMigrateBaseSchema(conn)
                val vecEnabled = initializeVectorSchemaFailOpen(conn)
                if (migrated) {
                    evictAllGuildOverflowAfterMigration(conn, vecEnabled)
                }
                vecEnabled
            }
    }

    override fun getCursor(channelId: String): ChatterMemoryCursor? =
        transaction(database) {
            val conn = currentJdbcConnection()
            conn
                .prepareStatement(
                    """
                    SELECT channel_id, guild_id, last_summarized_message_id, updated_at
                    FROM chatter_memory_cursors
                    WHERE channel_id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, channelId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            ChatterMemoryCursor(
                                channelId = rs.getString("channel_id"),
                                guildId = rs.getString("guild_id"),
                                lastSummarizedMessageId = rs.getString("last_summarized_message_id"),
                                updatedAt = rs.getLong("updated_at"),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override fun upsertCursor(
        channelId: String,
        guildId: String,
        lastSummarizedMessageId: String,
    ): ChatterMemoryCursor =
        transaction(database) {
            val now = clock.millis()
            val cursor =
                ChatterMemoryCursor(
                    channelId = channelId,
                    guildId = guildId,
                    lastSummarizedMessageId = lastSummarizedMessageId,
                    updatedAt = now,
                )
            upsertCursorInternal(currentJdbcConnection(), cursor)
            cursor
        }

    override fun insertMemory(
        guildId: String,
        scope: MemoryScope,
        targetUserId: String?,
        content: String,
        embedding: FloatArray?,
        expiresAt: Long?,
        importance: Int,
        keywords: String,
    ): ChatterMemoryRecord =
        transaction(database) {
            insertMemoryInternal(
                conn = currentJdbcConnection(),
                guildId = guildId,
                scope = scope,
                targetUserId = targetUserId,
                content = content,
                embedding = embedding,
                expiresAt = expiresAt,
                importance = importance,
                keywords = keywords,
                now = clock.millis(),
            )
        }

    override fun updateMemory(
        id: Long,
        guildId: String,
        content: String,
        embedding: FloatArray?,
        expiresAt: Long?,
        importance: Int,
        keywords: String,
    ): Boolean =
        transaction(database) {
            updateMemoryInternal(
                conn = currentJdbcConnection(),
                id = id,
                guildId = guildId,
                content = content,
                embedding = embedding,
                expiresAt = expiresAt,
                importance = importance,
                keywords = keywords,
                now = clock.millis(),
            )
        }

    override fun deleteMemory(
        id: Long,
        guildId: String,
    ): Boolean =
        transaction(database) {
            deleteMemoryInternal(currentJdbcConnection(), id = id, guildId = guildId)
        }

    override fun applyReconciliation(
        mutations: List<MemoryMutation>,
        cursorUpdate: ChatterMemoryCursor?,
    ) {
        transaction(database) {
            val conn = currentJdbcConnection()
            val now = clock.millis()
            for (mutation in mutations) {
                when (mutation) {
                    is MemoryMutation.Insert -> {
                        insertMemoryInternal(
                            conn = conn,
                            guildId = mutation.guildId,
                            scope = mutation.scope,
                            targetUserId = mutation.targetUserId,
                            content = mutation.content,
                            embedding = mutation.embedding,
                            expiresAt = mutation.expiresAt,
                            importance = mutation.importance,
                            keywords = mutation.keywords,
                            now = now,
                        )
                    }

                    is MemoryMutation.Update -> {
                        updateMemoryInternal(
                            conn = conn,
                            id = mutation.id,
                            guildId = mutation.guildId,
                            content = mutation.content,
                            embedding = mutation.embedding,
                            expiresAt = mutation.expiresAt,
                            importance = mutation.importance,
                            keywords = mutation.keywords,
                            now = now,
                        )
                    }

                    is MemoryMutation.Delete -> {
                        deleteMemoryInternal(
                            conn = conn,
                            id = mutation.id,
                            guildId = mutation.guildId,
                        )
                    }
                }
            }
            if (cursorUpdate != null) {
                upsertCursorInternal(conn, cursorUpdate)
            }
        }
    }

    override fun listUserMemories(
        guildId: String,
        userId: String,
    ): List<ChatterMemoryRecord> =
        transaction(database) {
            val conn = currentJdbcConnection()
            val now = clock.millis()
            conn
                .prepareStatement(
                    """
                    SELECT id, guild_id, scope, target_user_id, content, created_at, updated_at, last_accessed_at, expires_at, importance, keywords
                    FROM chatter_memories
                    WHERE guild_id = ? AND scope = 'user' AND target_user_id = ?
                      AND (expires_at IS NULL OR expires_at > ?)
                    ORDER BY updated_at DESC, id DESC
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, guildId)
                    ps.setString(2, userId)
                    ps.setLong(3, now)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(SqliteMemorySearchHelper.readMemoryRecord(rs))
                            }
                        }
                    }
                }
        }

    override fun deleteOwnedUserMemory(
        id: Long,
        guildId: String,
        userId: String,
    ): Boolean =
        transaction(database) {
            val conn = currentJdbcConnection()
            val deleted =
                conn
                    .prepareStatement(
                        """
                        DELETE FROM chatter_memories
                        WHERE id = ? AND guild_id = ? AND scope = 'user' AND target_user_id = ?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setLong(1, id)
                        ps.setString(2, guildId)
                        ps.setString(3, userId)
                        ps.executeUpdate() > 0
                    }
            if (deleted && isVectorSearchEnabled) {
                SqliteMemorySearchHelper.deleteVectorRow(conn, id)
            }
            deleted
        }

    override fun deleteAllOwnedUserMemories(
        guildId: String,
        userId: String,
    ): Int =
        deleteMemoriesWhere("guild_id = ? AND scope = 'user' AND target_user_id = ?") { ps ->
            ps.setString(1, guildId)
            ps.setString(2, userId)
        }

    override fun purgeExpiredMemories(nowMs: Long): Int =
        deleteMemoriesWhere("expires_at IS NOT NULL AND expires_at <= ?") { ps ->
            ps.setLong(1, nowMs)
        }

    private fun deleteMemoriesWhere(
        whereClause: String,
        bindParams: (java.sql.PreparedStatement) -> Unit,
    ): Int =
        transaction(database) {
            val conn = currentJdbcConnection()
            val targetIds =
                conn.prepareStatement("SELECT id FROM chatter_memories WHERE $whereClause").use { ps ->
                    bindParams(ps)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getLong(1)) }
                    }
                }
            if (targetIds.isEmpty()) return@transaction 0

            val deletedCount =
                conn.prepareStatement("DELETE FROM chatter_memories WHERE $whereClause").use { ps ->
                    bindParams(ps)
                    ps.executeUpdate()
                }
            if (isVectorSearchEnabled) {
                targetIds.forEach { SqliteMemorySearchHelper.deleteVectorRow(conn, it) }
            }
            deletedCount
        }

    override fun search(
        guildId: String,
        queryText: String,
        queryEmbedding: FloatArray?,
        scope: SearchScope,
        targetUserId: String?,
        limit: Int,
        recencyExcludeMinutes: Int?,
        maxUpdatedAtMs: Long?,
        scoreCutoff: Double?,
        updateLastAccessed: Boolean,
    ): List<ChatterMemorySearchHit> {
        if (limit <= 0) return emptyList()
        if (queryEmbedding != null) {
            require(queryEmbedding.size == embeddingDimension) {
                "Expected $embeddingDimension-dimensional queryEmbedding, got ${queryEmbedding.size}"
            }
        }

        val scopeFilter = SqliteMemorySearchHelper.buildScopeFilter(scope = scope, targetUserId = targetUserId) ?: return emptyList()
        val effectiveRecencyMinutes = (recencyExcludeMinutes ?: defaultRecencyExcludeMinutes).coerceAtLeast(0)
        val effectiveCutoff = (scoreCutoff ?: defaultScoreCutoff).coerceIn(0.0, 1.0)

        return transaction(database) {
            val conn = currentJdbcConnection()
            val now = clock.millis()
            val maxUpdatedAt =
                when {
                    recencyExcludeMinutes == 0 -> null
                    maxUpdatedAtMs != null -> maxUpdatedAtMs
                    effectiveRecencyMinutes > 0 -> now - TimeUnit.MINUTES.toMillis(effectiveRecencyMinutes.toLong())
                    else -> null
                }

            val candidateLimit = maxOf(limit * 5, 40)
            val lexicalCandidates =
                SqliteMemorySearchHelper.searchLexical(
                    conn = conn,
                    guildId = guildId,
                    scopeFilter = scopeFilter,
                    now = now,
                    maxUpdatedAt = maxUpdatedAt,
                    queryText = queryText,
                    limit = candidateLimit,
                )
            val vectorCandidates =
                if (isVectorSearchEnabled && queryEmbedding != null) {
                    SqliteMemorySearchHelper.searchVector(
                        conn = conn,
                        guildId = guildId,
                        scopeFilter = scopeFilter,
                        now = now,
                        maxUpdatedAt = maxUpdatedAt,
                        queryEmbedding = queryEmbedding,
                        limit = candidateLimit,
                    )
                } else {
                    emptyList()
                }

            val fused =
                ChatterMemoryRrfRanker.fuseCandidatesWithRrf(
                    lexicalCandidates = lexicalCandidates,
                    vectorCandidates = vectorCandidates,
                    scoreCutoff = effectiveCutoff,
                    limit = limit,
                    nowMs = now,
                )

            if (updateLastAccessed && fused.isNotEmpty()) {
                SqliteMemorySearchHelper.updateLastAccessedAt(conn, fused.map { it.memory.id }, now)
            }
            fused
        }
    }

    override fun insertUnderstanding(
        guildId: String,
        scope: MemoryScope,
        targetUserId: String?,
        subject: String,
        content: String,
    ): SocialUnderstandingRecord =
        transaction(database) {
            SqliteSocialUnderstandingHelper.insert(
                conn = currentJdbcConnection(),
                guildId = guildId,
                scope = scope,
                targetUserId = targetUserId,
                subject = subject,
                content = content,
                now = clock.millis(),
                maxPerUser = maxUnderstandingsPerUser,
                maxPerGuild = maxUnderstandingsPerGuild,
            )
        }

    override fun updateUnderstanding(
        id: Long,
        guildId: String,
        subject: String,
        content: String,
    ): Boolean =
        transaction(database) {
            SqliteSocialUnderstandingHelper.update(
                conn = currentJdbcConnection(),
                id = id,
                guildId = guildId,
                subject = subject,
                content = content,
                now = clock.millis(),
            )
        }

    override fun reaffirmUnderstanding(
        id: Long,
        guildId: String,
    ): Boolean =
        transaction(database) {
            SqliteSocialUnderstandingHelper.reaffirm(
                conn = currentJdbcConnection(),
                id = id,
                guildId = guildId,
                now = clock.millis(),
            )
        }

    override fun deleteUnderstanding(
        id: Long,
        guildId: String,
    ): Boolean =
        transaction(database) {
            SqliteSocialUnderstandingHelper.delete(currentJdbcConnection(), id = id, guildId = guildId)
        }

    override fun deleteOwnedUserUnderstanding(
        id: Long,
        guildId: String,
        userId: String,
    ): Boolean =
        transaction(database) {
            SqliteSocialUnderstandingHelper.deleteOwnedUser(
                conn = currentJdbcConnection(),
                id = id,
                guildId = guildId,
                userId = userId,
            )
        }

    override fun deleteAllOwnedUserUnderstandings(
        guildId: String,
        userId: String,
    ): Int =
        transaction(database) {
            SqliteSocialUnderstandingHelper.deleteAllOwnedUser(
                conn = currentJdbcConnection(),
                guildId = guildId,
                userId = userId,
            )
        }

    override fun listUserUnderstandings(
        guildId: String,
        userId: String,
        limit: Int,
    ): List<SocialUnderstandingRecord> =
        transaction(database) {
            SqliteSocialUnderstandingHelper.listUser(
                conn = currentJdbcConnection(),
                guildId = guildId,
                userId = userId,
                limit = limit,
            )
        }

    override fun listGuildUnderstandings(
        guildId: String,
        limit: Int,
    ): List<SocialUnderstandingRecord> =
        transaction(database) {
            SqliteSocialUnderstandingHelper.listGuild(
                conn = currentJdbcConnection(),
                guildId = guildId,
                limit = limit,
            )
        }

    override fun touchUnderstandings(
        guildId: String,
        ids: List<Long>,
    ) {
        if (ids.isEmpty()) return
        transaction(database) {
            SqliteSocialUnderstandingHelper.touch(
                conn = currentJdbcConnection(),
                guildId = guildId,
                ids = ids,
                now = clock.millis(),
            )
        }
    }

    override fun applyUnderstandingMutations(mutations: List<UnderstandingMutation>) {
        if (mutations.isEmpty()) return
        transaction(database) {
            SqliteSocialUnderstandingHelper.applyMutations(
                conn = currentJdbcConnection(),
                mutations = mutations,
                now = clock.millis(),
                maxPerUser = maxUnderstandingsPerUser,
                maxPerGuild = maxUnderstandingsPerGuild,
            )
        }
    }

    override fun getUserRelationship(
        guildId: String,
        userId: String,
        now: Long?,
    ): UserRelationship = SqliteUserRelationshipHelper.getFailOpen(database, guildId, userId, now ?: clock.millis(), logger)

    override fun listUserRelationships(
        guildId: String,
        userIds: Collection<String>,
        now: Long?,
    ): Map<String, UserRelationship> = SqliteUserRelationshipHelper.listFailOpen(database, guildId, userIds, now ?: clock.millis(), logger)

    override fun applyRelationshipUpdate(
        guildId: String,
        userId: String,
        affinityDelta: Int,
        toneStyle: String?,
        now: Long?,
    ): UserRelationship = SqliteUserRelationshipHelper.applyUpdateTx(database, guildId, userId, affinityDelta, toneStyle, now ?: clock.millis())

    override fun deleteUserRelationship(
        guildId: String,
        userId: String,
    ): Boolean = SqliteUserRelationshipHelper.deleteTx(database, guildId, userId)

    private fun currentJdbcConnection(): Connection = TransactionManager.current().connection.connection as Connection

    private fun evictAllGuildOverflowAfterMigration(
        conn: Connection,
        vecEnabled: Boolean,
    ) {
        if (vecEnabled) {
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM chatter_memories_vec WHERE memory_id NOT IN (SELECT id FROM chatter_memories);")
            }
        }
        val guildIds =
            conn
                .prepareStatement("SELECT DISTINCT guild_id FROM chatter_memories WHERE scope = 'guild'")
                .use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.getString("guild_id"))
                            }
                        }
                    }
                }
        for (guildId in guildIds) {
            evictLruOverflow(
                conn = conn,
                guildId = guildId,
                scope = MemoryScope.GUILD,
                targetUserId = null,
                vecEnabled = vecEnabled,
            )
        }
    }

    private fun initializeVectorSchemaFailOpen(conn: Connection): Boolean {
        val vecAvailable =
            runCatching {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT vec_version();").use { rs -> rs.next() }
                }
                true
            }.getOrElse { error ->
                logger.warn(
                    "sqlite-vec is unavailable on this SQLite connection; SqliteChatterMemoryStore running in lexical FTS5/LIKE fallback mode: {}",
                    error.message,
                )
                false
            }
        if (!vecAvailable) return false

        val existingVecSql =
            conn
                .prepareStatement(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chatter_memories_vec'",
                ).use { ps ->
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString("sql") else null }
                }
        if (existingVecSql != null && !isVectorDimensionCompatible(existingVecSql, embeddingDimension, logger)) {
            return false
        }

        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS chatter_memories_vec USING vec0(
                    memory_id INTEGER PRIMARY KEY,
                    embedding float[$embeddingDimension] distance_metric=cosine
                );
                """.trimIndent(),
            )
        }
        return true
    }

    private fun upsertCursorInternal(
        conn: Connection,
        cursor: ChatterMemoryCursor,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO chatter_memory_cursors(channel_id, guild_id, last_summarized_message_id, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(channel_id) DO UPDATE SET
                    guild_id = excluded.guild_id,
                    last_summarized_message_id = excluded.last_summarized_message_id,
                    updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, cursor.channelId)
                ps.setString(2, cursor.guildId)
                ps.setString(3, cursor.lastSummarizedMessageId)
                ps.setLong(4, cursor.updatedAt)
                ps.executeUpdate()
            }
    }

    private fun insertMemoryInternal(
        conn: Connection,
        guildId: String,
        scope: MemoryScope,
        targetUserId: String?,
        content: String,
        embedding: FloatArray?,
        expiresAt: Long?,
        importance: Int,
        keywords: String,
        now: Long,
    ): ChatterMemoryRecord {
        val effectiveTargetUserId =
            if (scope == MemoryScope.USER) {
                require(!targetUserId.isNullOrBlank()) { "targetUserId is required for USER scope memories" }
                targetUserId
            } else {
                null
            }
        if (embedding != null) {
            require(embedding.size == embeddingDimension) {
                "Expected $embeddingDimension-dimensional embedding, got ${embedding.size}"
            }
        }

        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Memory content must not be blank" }
        val cleanImportance = sanitizeImportance(importance)
        val cleanKeywords = sanitizeKeywords(keywords)

        val insertedId =
            conn
                .prepareStatement(
                    """
                    INSERT INTO chatter_memories(
                        guild_id, scope, target_user_id, content, created_at, updated_at, last_accessed_at, expires_at, importance, keywords
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.setString(1, guildId)
                    ps.setString(2, scope.dbValue)
                    ps.setString(3, effectiveTargetUserId)
                    ps.setString(4, cleanContent)
                    ps.setLong(5, now)
                    ps.setLong(6, now)
                    ps.setLong(7, now)
                    if (expiresAt != null) {
                        ps.setLong(8, expiresAt)
                    } else {
                        ps.setNull(8, java.sql.Types.INTEGER)
                    }
                    ps.setInt(9, cleanImportance)
                    ps.setString(10, cleanKeywords)
                    ps.executeUpdate()
                    ps.generatedKeys.use { keys ->
                        check(keys.next()) { "SQLite did not return generated row ID for chatter_memories insert" }
                        keys.getLong(1)
                    }
                }

        if (isVectorSearchEnabled && embedding != null) {
            SqliteMemorySearchHelper.insertVectorRow(conn, insertedId, embedding)
        }

        evictLruOverflow(
            conn = conn,
            guildId = guildId,
            scope = scope,
            targetUserId = effectiveTargetUserId,
        )

        return ChatterMemoryRecord(
            id = insertedId,
            guildId = guildId,
            scope = scope,
            targetUserId = effectiveTargetUserId,
            content = cleanContent,
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = now,
            expiresAt = expiresAt,
            importance = cleanImportance,
            keywords = cleanKeywords,
        )
    }

    private fun updateMemoryInternal(
        conn: Connection,
        id: Long,
        guildId: String,
        content: String,
        embedding: FloatArray?,
        expiresAt: Long?,
        importance: Int,
        keywords: String,
        now: Long,
    ): Boolean {
        if (embedding != null) {
            require(embedding.size == embeddingDimension) {
                "Expected $embeddingDimension-dimensional embedding, got ${embedding.size}"
            }
        }
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Memory content must not be blank" }
        val cleanImportance = sanitizeImportance(importance)
        val cleanKeywords = sanitizeKeywords(keywords)

        val updated =
            conn
                .prepareStatement(
                    """
                    UPDATE chatter_memories
                    SET content = ?, updated_at = ?, last_accessed_at = ?, expires_at = ?, importance = ?, keywords = ?
                    WHERE id = ? AND guild_id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, cleanContent)
                    ps.setLong(2, now)
                    ps.setLong(3, now)
                    if (expiresAt != null) {
                        ps.setLong(4, expiresAt)
                    } else {
                        ps.setNull(4, java.sql.Types.INTEGER)
                    }
                    ps.setInt(5, cleanImportance)
                    ps.setString(6, cleanKeywords)
                    ps.setLong(7, id)
                    ps.setString(8, guildId)
                    ps.executeUpdate() > 0
                }
        if (updated && isVectorSearchEnabled && embedding != null) {
            SqliteMemorySearchHelper.deleteVectorRow(conn, id)
            SqliteMemorySearchHelper.insertVectorRow(conn, id, embedding)
        }
        return updated
    }

    private fun deleteMemoryInternal(
        conn: Connection,
        id: Long,
        guildId: String,
        vecEnabled: Boolean = isVectorSearchEnabled,
    ): Boolean {
        val deleted =
            conn
                .prepareStatement(
                    """
                    DELETE FROM chatter_memories
                    WHERE id = ? AND guild_id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setLong(1, id)
                    ps.setString(2, guildId)
                    ps.executeUpdate() > 0
                }
        if (deleted && vecEnabled) {
            SqliteMemorySearchHelper.deleteVectorRow(conn, id)
        }
        return deleted
    }

    private fun evictLruOverflow(
        conn: Connection,
        guildId: String,
        scope: MemoryScope,
        targetUserId: String?,
        vecEnabled: Boolean = isVectorSearchEnabled,
    ) {
        val cap = if (scope == MemoryScope.USER) maxPerUser else maxPerGuild
        val scopeWhere =
            if (scope == MemoryScope.USER) {
                "guild_id = ? AND scope = 'user' AND target_user_id = ?"
            } else {
                "guild_id = ? AND scope = 'guild'"
            }
        val params =
            if (scope == MemoryScope.USER) {
                listOf(guildId, requireNotNull(targetUserId))
            } else {
                listOf(guildId)
            }

        val totalCount =
            conn.prepareStatement("SELECT COUNT(*) FROM chatter_memories WHERE $scopeWhere").use { ps ->
                params.forEachIndexed { index, param -> ps.setString(index + 1, param) }
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }

        val excess = totalCount - cap
        if (excess <= 0) return

        val victimIds =
            conn
                .prepareStatement(
                    """
                    SELECT id FROM chatter_memories
                    WHERE $scopeWhere
                    ORDER BY CASE WHEN importance >= 4 THEN 1 ELSE 0 END ASC, importance ASC, MAX(updated_at, last_accessed_at) ASC, id ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    params.forEachIndexed { index, param -> ps.setString(index + 1, param) }
                    ps.setInt(params.size + 1, excess)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.getLong(1))
                            }
                        }
                    }
                }

        for (victimId in victimIds) {
            deleteMemoryInternal(conn, id = victimId, guildId = guildId, vecEnabled = vecEnabled)
        }
    }

    internal data class VectorCandidate(
        val record: ChatterMemoryRecord,
        val cosineSimilarity: Double,
    )

    companion object {
        const val DEFAULT_RECENCY_EXCLUDE_MINUTES = 10
        const val DEFAULT_SCORE_CUTOFF = 0.55
        const val DEFAULT_MAX_PER_USER = 100
        const val DEFAULT_MAX_PER_GUILD = 300
        const val DEFAULT_EMBEDDING_DIMENSION = 768
        const val DEFAULT_MAX_UNDERSTANDINGS_PER_USER = 15
        const val DEFAULT_MAX_UNDERSTANDINGS_PER_GUILD = 20
        private val WHITESPACE_REGEX = Regex("""\s+""")

        internal fun sanitizeImportance(importance: Int): Int = importance.coerceIn(ChatterMemoryStore.MIN_IMPORTANCE, ChatterMemoryStore.MAX_IMPORTANCE)

        internal fun sanitizeKeywords(keywords: String): String {
            val normalized = keywords.trim().replace(WHITESPACE_REGEX, " ")
            if (normalized.isEmpty()) return ""
            return normalized.take(ChatterMemoryStore.MAX_KEYWORDS_CHARS).trim()
        }

        internal fun isVectorDimensionCompatible(
            existingTableSql: String,
            expectedDimension: Int,
            logger: org.slf4j.Logger,
        ): Boolean = ChatterMemorySchemaMigrator.isVectorDimensionCompatible(existingTableSql, expectedDimension, logger)

        internal fun buildVectorSearchSql(
            scopeClause: String,
            hasRecencyFilter: Boolean,
        ): String = SqliteMemorySearchHelper.buildVectorSearchSql(scopeClause, hasRecencyFilter)
    }
}
