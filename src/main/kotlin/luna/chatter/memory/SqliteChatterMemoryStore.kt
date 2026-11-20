package luna.chatter.memory

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.ResultSet
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
    private val maxPerChannel: Int = DEFAULT_MAX_PER_CHANNEL,
    private val embeddingDimension: Int = DEFAULT_EMBEDDING_DIMENSION,
) : ChatterMemoryStore {
    private val logger = LoggerFactory.getLogger(SqliteChatterMemoryStore::class.java)

    override val isVectorSearchEnabled: Boolean

    init {
        require(defaultRecencyExcludeMinutes >= 0) { "defaultRecencyExcludeMinutes must be >= 0" }
        require(defaultScoreCutoff in 0.0..1.0) { "defaultScoreCutoff must be in 0.0..1.0" }
        require(maxPerUser >= 1) { "maxPerUser must be >= 1" }
        require(maxPerChannel >= 1) { "maxPerChannel must be >= 1" }
        require(embeddingDimension >= 1) { "embeddingDimension must be >= 1" }

        isVectorSearchEnabled =
            transaction(database) {
                val conn = currentJdbcConnection()
                initializeBaseSchema(conn)
                initializeVectorSchemaFailOpen(conn)
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
        channelId: String?,
        content: String,
        embedding: FloatArray?,
    ): ChatterMemoryRecord =
        transaction(database) {
            insertMemoryInternal(
                conn = currentJdbcConnection(),
                guildId = guildId,
                scope = scope,
                targetUserId = targetUserId,
                channelId = channelId,
                content = content,
                embedding = embedding,
                now = clock.millis(),
            )
        }

    override fun updateMemory(
        id: Long,
        guildId: String,
        content: String,
        embedding: FloatArray?,
    ): Boolean =
        transaction(database) {
            updateMemoryInternal(
                conn = currentJdbcConnection(),
                id = id,
                guildId = guildId,
                content = content,
                embedding = embedding,
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
                            channelId = mutation.channelId,
                            content = mutation.content,
                            embedding = mutation.embedding,
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
            conn
                .prepareStatement(
                    """
                    SELECT id, guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at
                    FROM chatter_memories
                    WHERE guild_id = ? AND scope = 'user' AND target_user_id = ?
                    ORDER BY updated_at DESC, id DESC
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, guildId)
                    ps.setString(2, userId)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(readMemoryRecord(rs))
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
                deleteVectorRow(conn, id)
            }
            deleted
        }

    override fun search(
        guildId: String,
        channelId: String?,
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

        val scopeFilter = buildScopeFilter(scope = scope, targetUserId = targetUserId, channelId = channelId) ?: return emptyList()
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
                searchLexical(
                    conn = conn,
                    guildId = guildId,
                    scopeFilter = scopeFilter,
                    maxUpdatedAt = maxUpdatedAt,
                    queryText = queryText,
                    limit = candidateLimit,
                )
            val vectorCandidates =
                if (isVectorSearchEnabled && queryEmbedding != null) {
                    searchVector(
                        conn = conn,
                        guildId = guildId,
                        scopeFilter = scopeFilter,
                        maxUpdatedAt = maxUpdatedAt,
                        queryEmbedding = queryEmbedding,
                        limit = candidateLimit,
                    )
                } else {
                    emptyList()
                }

            val fused =
                fuseCandidatesWithRrf(
                    lexicalCandidates = lexicalCandidates,
                    vectorCandidates = vectorCandidates,
                    scoreCutoff = effectiveCutoff,
                    limit = limit,
                )

            if (updateLastAccessed && fused.isNotEmpty()) {
                updateLastAccessedAt(conn, fused.map { it.memory.id }, now)
            }
            fused
        }
    }

    private fun currentJdbcConnection(): Connection = TransactionManager.current().connection.connection as Connection

    private fun initializeBaseSchema(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS chatter_memory_cursors (
                    channel_id TEXT PRIMARY KEY,
                    guild_id TEXT NOT NULL,
                    last_summarized_message_id TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS chatter_memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id TEXT NOT NULL,
                    scope TEXT NOT NULL CHECK(scope IN ('user', 'channel')),
                    target_user_id TEXT,
                    channel_id TEXT,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_accessed_at INTEGER NOT NULL
                );
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_chatter_memories_guild_user
                    ON chatter_memories(guild_id, scope, target_user_id, updated_at, last_accessed_at);
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_chatter_memories_guild_channel
                    ON chatter_memories(guild_id, scope, channel_id, updated_at, last_accessed_at);
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS chatter_memories_fts USING fts5(
                    content,
                    content='chatter_memories',
                    content_rowid='id',
                    tokenize='trigram'
                );
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS chatter_memories_ai AFTER INSERT ON chatter_memories BEGIN
                    INSERT INTO chatter_memories_fts(rowid, content) VALUES (new.id, new.content);
                END;
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS chatter_memories_ad AFTER DELETE ON chatter_memories BEGIN
                    INSERT INTO chatter_memories_fts(chatter_memories_fts, rowid, content) VALUES('delete', old.id, old.content);
                END;
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TRIGGER IF NOT EXISTS chatter_memories_au AFTER UPDATE ON chatter_memories BEGIN
                    INSERT INTO chatter_memories_fts(chatter_memories_fts, rowid, content) VALUES('delete', old.id, old.content);
                    INSERT INTO chatter_memories_fts(rowid, content) VALUES (new.id, new.content);
                END;
                """.trimIndent(),
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
        channelId: String?,
        content: String,
        embedding: FloatArray?,
        now: Long,
    ): ChatterMemoryRecord {
        if (scope == MemoryScope.USER) {
            require(!targetUserId.isNullOrBlank()) { "targetUserId is required for USER scope memories" }
        } else {
            require(!channelId.isNullOrBlank()) { "channelId is required for CHANNEL scope memories" }
        }
        if (embedding != null) {
            require(embedding.size == embeddingDimension) {
                "Expected $embeddingDimension-dimensional embedding, got ${embedding.size}"
            }
        }

        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Memory content must not be blank" }

        val insertedId =
            conn
                .prepareStatement(
                    """
                    INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.setString(1, guildId)
                    ps.setString(2, scope.dbValue)
                    ps.setString(3, targetUserId)
                    ps.setString(4, channelId)
                    ps.setString(5, cleanContent)
                    ps.setLong(6, now)
                    ps.setLong(7, now)
                    ps.setLong(8, now)
                    ps.executeUpdate()
                    ps.generatedKeys.use { keys ->
                        check(keys.next()) { "SQLite did not return generated row ID for chatter_memories insert" }
                        keys.getLong(1)
                    }
                }

        if (isVectorSearchEnabled && embedding != null) {
            insertVectorRow(conn, insertedId, embedding)
        }

        evictLruOverflow(
            conn = conn,
            guildId = guildId,
            scope = scope,
            targetUserId = targetUserId,
            channelId = channelId,
        )

        return ChatterMemoryRecord(
            id = insertedId,
            guildId = guildId,
            scope = scope,
            targetUserId = targetUserId,
            channelId = channelId,
            content = cleanContent,
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = now,
        )
    }

    private fun updateMemoryInternal(
        conn: Connection,
        id: Long,
        guildId: String,
        content: String,
        embedding: FloatArray?,
        now: Long,
    ): Boolean {
        if (embedding != null) {
            require(embedding.size == embeddingDimension) {
                "Expected $embeddingDimension-dimensional embedding, got ${embedding.size}"
            }
        }
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Memory content must not be blank" }

        val updated =
            conn
                .prepareStatement(
                    """
                    UPDATE chatter_memories
                    SET content = ?, updated_at = ?, last_accessed_at = ?
                    WHERE id = ? AND guild_id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, cleanContent)
                    ps.setLong(2, now)
                    ps.setLong(3, now)
                    ps.setLong(4, id)
                    ps.setString(5, guildId)
                    ps.executeUpdate() > 0
                }
        if (updated && isVectorSearchEnabled && embedding != null) {
            deleteVectorRow(conn, id)
            insertVectorRow(conn, id, embedding)
        }
        return updated
    }

    private fun deleteMemoryInternal(
        conn: Connection,
        id: Long,
        guildId: String,
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
        if (deleted && isVectorSearchEnabled) {
            deleteVectorRow(conn, id)
        }
        return deleted
    }

    private fun evictLruOverflow(
        conn: Connection,
        guildId: String,
        scope: MemoryScope,
        targetUserId: String?,
        channelId: String?,
    ) {
        val cap = if (scope == MemoryScope.USER) maxPerUser else maxPerChannel
        val scopeWhere =
            if (scope == MemoryScope.USER) {
                "guild_id = ? AND scope = 'user' AND target_user_id = ?"
            } else {
                "guild_id = ? AND scope = 'channel' AND channel_id = ?"
            }
        val keyParam = if (scope == MemoryScope.USER) requireNotNull(targetUserId) else requireNotNull(channelId)

        val totalCount =
            conn.prepareStatement("SELECT COUNT(*) FROM chatter_memories WHERE $scopeWhere").use { ps ->
                ps.setString(1, guildId)
                ps.setString(2, keyParam)
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
                    ORDER BY MAX(updated_at, last_accessed_at) ASC, id ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, guildId)
                    ps.setString(2, keyParam)
                    ps.setInt(3, excess)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.getLong(1))
                            }
                        }
                    }
                }

        for (victimId in victimIds) {
            deleteMemoryInternal(conn, id = victimId, guildId = guildId)
        }
    }

    private fun insertVectorRow(
        conn: Connection,
        memoryId: Long,
        embedding: FloatArray,
    ) {
        conn
            .prepareStatement("INSERT INTO chatter_memories_vec(memory_id, embedding) VALUES (?, ?)")
            .use { ps ->
                ps.setLong(1, memoryId)
                ps.setBytes(2, floatArrayToLittleEndianBytes(embedding))
                ps.executeUpdate()
            }
    }

    private fun deleteVectorRow(
        conn: Connection,
        memoryId: Long,
    ) {
        conn
            .prepareStatement("DELETE FROM chatter_memories_vec WHERE memory_id = ?")
            .use { ps ->
                ps.setLong(1, memoryId)
                ps.executeUpdate()
            }
    }

    private data class ScopeFilterSql(
        val clause: String,
        val params: List<String>,
    )

    private fun buildScopeFilter(
        scope: SearchScope,
        targetUserId: String?,
        channelId: String?,
    ): ScopeFilterSql? =
        when (scope) {
            SearchScope.USER -> {
                targetUserId?.takeIf { it.isNotBlank() }?.let { uid ->
                    ScopeFilterSql("(m.scope = 'user' AND m.target_user_id = ?)", listOf(uid))
                }
            }

            SearchScope.CHANNEL -> {
                channelId?.takeIf { it.isNotBlank() }?.let { cid ->
                    ScopeFilterSql("(m.scope = 'channel' AND m.channel_id = ?)", listOf(cid))
                }
            }

            SearchScope.BOTH -> {
                val uid = targetUserId?.takeIf { it.isNotBlank() }
                val cid = channelId?.takeIf { it.isNotBlank() }
                when {
                    uid != null && cid != null -> {
                        ScopeFilterSql(
                            "((m.scope = 'user' AND m.target_user_id = ?) OR (m.scope = 'channel' AND m.channel_id = ?))",
                            listOf(uid, cid),
                        )
                    }

                    uid != null -> {
                        ScopeFilterSql("(m.scope = 'user' AND m.target_user_id = ?)", listOf(uid))
                    }

                    cid != null -> {
                        ScopeFilterSql("(m.scope = 'channel' AND m.channel_id = ?)", listOf(cid))
                    }

                    else -> {
                        null
                    }
                }
            }
        }

    private fun searchLexical(
        conn: Connection,
        guildId: String,
        scopeFilter: ScopeFilterSql,
        maxUpdatedAt: Long?,
        queryText: String,
        limit: Int,
    ): List<ChatterMemoryRecord> {
        val tokens = queryText.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val trigramTokens = tokens.filter { it.length >= 3 }
        val recencySql = if (maxUpdatedAt != null) "AND m.updated_at <= ?" else ""

        return if (trigramTokens.isNotEmpty()) {
            val matchQuery =
                trigramTokens.joinToString(" OR ") { token ->
                    "\"${token.replace("\"", "\"\"")}\""
                }
            val sql =
                """
                SELECT m.id, m.guild_id, m.scope, m.target_user_id, m.channel_id, m.content, m.created_at, m.updated_at, m.last_accessed_at
                FROM chatter_memories_fts f
                JOIN chatter_memories m ON m.id = f.rowid
                WHERE chatter_memories_fts MATCH ?
                  AND m.guild_id = ?
                  AND ${scopeFilter.clause}
                  $recencySql
                ORDER BY bm25(chatter_memories_fts), m.updated_at DESC
                LIMIT ?
                """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, matchQuery)
                ps.setString(idx++, guildId)
                for (p in scopeFilter.params) {
                    ps.setString(idx++, p)
                }
                if (maxUpdatedAt != null) {
                    ps.setLong(idx++, maxUpdatedAt)
                }
                ps.setInt(idx, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(readMemoryRecord(rs))
                    }
                }
            }
        } else {
            val likeClauses = tokens.joinToString(" OR ") { "m.content LIKE ? ESCAPE '\\'" }
            val sql =
                """
                SELECT m.id, m.guild_id, m.scope, m.target_user_id, m.channel_id, m.content, m.created_at, m.updated_at, m.last_accessed_at
                FROM chatter_memories m
                WHERE m.guild_id = ?
                  AND ${scopeFilter.clause}
                  $recencySql
                  AND ($likeClauses)
                ORDER BY m.updated_at DESC, m.id DESC
                LIMIT ?
                """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, guildId)
                for (p in scopeFilter.params) {
                    ps.setString(idx++, p)
                }
                if (maxUpdatedAt != null) {
                    ps.setLong(idx++, maxUpdatedAt)
                }
                for (token in tokens) {
                    ps.setString(idx++, "%${escapeLikePattern(token)}%")
                }
                ps.setInt(idx, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(readMemoryRecord(rs))
                    }
                }
            }
        }
    }

    internal data class VectorCandidate(
        val record: ChatterMemoryRecord,
        val cosineSimilarity: Double,
    )

    private fun searchVector(
        conn: Connection,
        guildId: String,
        scopeFilter: ScopeFilterSql,
        maxUpdatedAt: Long?,
        queryEmbedding: FloatArray,
        limit: Int,
    ): List<VectorCandidate> {
        val sql = buildVectorSearchSql(scopeClause = scopeFilter.clause, hasRecencyFilter = maxUpdatedAt != null)
        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setBytes(idx++, floatArrayToLittleEndianBytes(queryEmbedding))
            ps.setInt(idx++, limit)
            ps.setString(idx++, guildId)
            for (p in scopeFilter.params) ps.setString(idx++, p)
            if (maxUpdatedAt != null) ps.setLong(idx++, maxUpdatedAt)
            ps.setInt(idx, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(VectorCandidate(readMemoryRecord(rs), 1.0 - rs.getDouble("distance")))
                    }
                }
            }
        }
    }

    private fun updateLastAccessedAt(
        conn: Connection,
        ids: List<Long>,
        now: Long,
    ) {
        val placeholders = ids.joinToString(",") { "?" }
        conn.prepareStatement("UPDATE chatter_memories SET last_accessed_at = ? WHERE id IN ($placeholders)").use { ps ->
            ps.setLong(1, now)
            ids.forEachIndexed { index, id -> ps.setLong(index + 2, id) }
            ps.executeUpdate()
        }
    }

    private fun readMemoryRecord(rs: ResultSet): ChatterMemoryRecord =
        ChatterMemoryRecord(
            id = rs.getLong("id"),
            guildId = rs.getString("guild_id"),
            scope = MemoryScope.fromDbValue(rs.getString("scope")),
            targetUserId = rs.getString("target_user_id"),
            channelId = rs.getString("channel_id"),
            content = rs.getString("content"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            lastAccessedAt = rs.getLong("last_accessed_at"),
        )

    companion object {
        const val DEFAULT_RECENCY_EXCLUDE_MINUTES = 10
        const val DEFAULT_SCORE_CUTOFF = 0.55
        const val DEFAULT_MAX_PER_USER = 100
        const val DEFAULT_MAX_PER_CHANNEL = 200
        const val DEFAULT_EMBEDDING_DIMENSION = 768
        private const val RRF_K = 60.0
        private val WHITESPACE_REGEX = Regex("""\s+""")

        private fun escapeLikePattern(token: String): String = token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        internal fun buildVectorSearchSql(
            scopeClause: String,
            hasRecencyFilter: Boolean,
        ): String {
            val prefilterScopeClause = scopeClause.replace("m.", "m2.")
            val prefilterRecencySql = if (hasRecencyFilter) "AND m2.updated_at <= ?" else ""
            return """
                SELECT m.id, m.guild_id, m.scope, m.target_user_id, m.channel_id, m.content, m.created_at, m.updated_at, m.last_accessed_at, v.distance
                FROM chatter_memories_vec v
                JOIN chatter_memories m ON m.id = v.memory_id
                WHERE v.embedding MATCH ? AND v.k = ?
                  AND v.memory_id IN (
                      SELECT m2.id FROM chatter_memories m2
                      WHERE m2.guild_id = ? AND $prefilterScopeClause $prefilterRecencySql
                  )
                ORDER BY v.distance ASC
                LIMIT ?
                """.trimIndent()
        }

        internal fun floatArrayToLittleEndianBytes(values: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) buffer.putFloat(v)
            return buffer.array()
        }

        internal fun fuseCandidatesWithRrf(
            lexicalCandidates: List<ChatterMemoryRecord>,
            vectorCandidates: List<VectorCandidate>,
            scoreCutoff: Double,
            limit: Int,
        ): List<ChatterMemorySearchHit> {
            val recordsById = LinkedHashMap<Long, ChatterMemoryRecord>()
            val lexicalRanks = HashMap<Long, Int>()
            val vectorRanks = HashMap<Long, Int>()
            val cosineById = HashMap<Long, Double>()

            lexicalCandidates.forEachIndexed { index, record ->
                recordsById.putIfAbsent(record.id, record)
                lexicalRanks[record.id] = index + 1
            }
            vectorCandidates.forEachIndexed { index, candidate ->
                recordsById.putIfAbsent(candidate.record.id, candidate.record)
                vectorRanks[candidate.record.id] = index + 1
                cosineById[candidate.record.id] = candidate.cosineSimilarity
            }

            return recordsById.values
                .mapNotNull { record ->
                    val ftsRank = lexicalRanks[record.id]
                    val vecRank = vectorRanks[record.id]
                    val cosine = cosineById[record.id]
                    val matchedByFts = ftsRank != null
                    val matchedByVector = vecRank != null
                    if (!matchedByFts && (cosine == null || cosine < scoreCutoff)) {
                        return@mapNotNull null
                    }
                    val rrfScore =
                        (if (ftsRank != null) 1.0 / (RRF_K + ftsRank) else 0.0) +
                            (if (vecRank != null) 1.0 / (RRF_K + vecRank) else 0.0)
                    ChatterMemorySearchHit(
                        memory = record,
                        score = rrfScore,
                        cosineSimilarity = cosine,
                        matchedByFts = matchedByFts,
                        matchedByVector = matchedByVector,
                    )
                }.sortedWith(
                    compareByDescending<ChatterMemorySearchHit> { it.score }
                        .thenByDescending { it.memory.updatedAt }
                        .thenByDescending { it.memory.id },
                ).take(limit)
        }
    }
}
