package luna.chatter.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.ResultSet

/**
 * Lexical (`FTS5 trigram` / `LIKE`) and vector (`sqlite-vec`) SQL helpers for `chatter_memories`.
 */
internal object SqliteMemorySearchHelper {
    private val WHITESPACE_REGEX = Regex("""\s+""")

    data class ScopeFilterSql(
        val clause: String,
        val params: List<String>,
    )

    fun buildScopeFilter(
        scope: SearchScope,
        targetUserId: String?,
    ): ScopeFilterSql? =
        when (scope) {
            SearchScope.USER -> {
                targetUserId?.takeIf { it.isNotBlank() }?.let { uid ->
                    ScopeFilterSql("(m.scope = 'user' AND m.target_user_id = ?)", listOf(uid))
                }
            }

            SearchScope.GUILD -> {
                ScopeFilterSql("(m.scope = 'guild')", emptyList())
            }

            SearchScope.BOTH -> {
                val uid = targetUserId?.takeIf { it.isNotBlank() }
                if (uid != null) {
                    ScopeFilterSql(
                        "((m.scope = 'user' AND m.target_user_id = ?) OR (m.scope = 'guild'))",
                        listOf(uid),
                    )
                } else {
                    ScopeFilterSql("(m.scope = 'guild')", emptyList())
                }
            }
        }

    fun searchLexical(
        conn: Connection,
        guildId: String,
        scopeFilter: ScopeFilterSql,
        now: Long,
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
                SELECT m.id, m.guild_id, m.scope, m.target_user_id, m.content, m.created_at, m.updated_at, m.last_accessed_at, m.expires_at, m.importance, m.keywords
                FROM chatter_memories_fts f
                JOIN chatter_memories m ON m.id = f.rowid
                WHERE chatter_memories_fts MATCH ?
                  AND m.guild_id = ?
                  AND ${scopeFilter.clause}
                  AND (m.expires_at IS NULL OR m.expires_at > ?)
                  $recencySql
                ORDER BY bm25(chatter_memories_fts), m.updated_at DESC
                LIMIT ?
                """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, matchQuery)
                ps.setString(idx++, guildId)
                for (p in scopeFilter.params) ps.setString(idx++, p)
                ps.setLong(idx++, now)
                if (maxUpdatedAt != null) ps.setLong(idx++, maxUpdatedAt)
                ps.setInt(idx, limit)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(readMemoryRecord(rs)) }
                }
            }
        } else {
            val likeClauses = tokens.joinToString(" OR ") { "(m.content LIKE ? ESCAPE '\\' OR m.keywords LIKE ? ESCAPE '\\')" }
            val sql =
                """
                SELECT m.id, m.guild_id, m.scope, m.target_user_id, m.content, m.created_at, m.updated_at, m.last_accessed_at, m.expires_at, m.importance, m.keywords
                FROM chatter_memories m
                WHERE m.guild_id = ?
                  AND ${scopeFilter.clause}
                  AND (m.expires_at IS NULL OR m.expires_at > ?)
                  $recencySql
                  AND ($likeClauses)
                ORDER BY m.updated_at DESC, m.id DESC
                LIMIT ?
                """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, guildId)
                for (p in scopeFilter.params) ps.setString(idx++, p)
                ps.setLong(idx++, now)
                if (maxUpdatedAt != null) ps.setLong(idx++, maxUpdatedAt)
                for (token in tokens) {
                    val pattern = "%${escapeLikePattern(token)}%"
                    ps.setString(idx++, pattern)
                    ps.setString(idx++, pattern)
                }
                ps.setInt(idx, limit)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(readMemoryRecord(rs)) }
                }
            }
        }
    }

    fun searchVector(
        conn: Connection,
        guildId: String,
        scopeFilter: ScopeFilterSql,
        now: Long,
        maxUpdatedAt: Long?,
        queryEmbedding: FloatArray,
        limit: Int,
    ): List<SqliteChatterMemoryStore.VectorCandidate> {
        val sql = buildVectorSearchSql(scopeClause = scopeFilter.clause, hasRecencyFilter = maxUpdatedAt != null)
        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setBytes(idx++, floatArrayToLittleEndianBytes(queryEmbedding))
            ps.setInt(idx++, limit)
            ps.setString(idx++, guildId)
            for (p in scopeFilter.params) ps.setString(idx++, p)
            ps.setLong(idx++, now)
            if (maxUpdatedAt != null) ps.setLong(idx++, maxUpdatedAt)
            ps.setInt(idx, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(SqliteChatterMemoryStore.VectorCandidate(readMemoryRecord(rs), 1.0 - rs.getDouble("distance")))
                    }
                }
            }
        }
    }

    fun insertVectorRow(
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

    fun deleteVectorRow(
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

    fun updateLastAccessedAt(
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

    fun readMemoryRecord(rs: ResultSet): ChatterMemoryRecord =
        ChatterMemoryRecord(
            id = rs.getLong("id"),
            guildId = rs.getString("guild_id"),
            scope = MemoryScope.fromDbValue(rs.getString("scope")),
            targetUserId = rs.getString("target_user_id"),
            content = rs.getString("content"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            lastAccessedAt = rs.getLong("last_accessed_at"),
            expiresAt = rs.getObject("expires_at")?.let { (it as Number).toLong() },
            importance = rs.getInt("importance"),
            keywords = rs.getString("keywords") ?: "",
        )

    fun buildVectorSearchSql(
        scopeClause: String,
        hasRecencyFilter: Boolean,
    ): String {
        val prefilterScopeClause = scopeClause.replace("m.", "m2.")
        val prefilterRecencySql = if (hasRecencyFilter) "AND m2.updated_at <= ?" else ""
        return """
            SELECT m.id, m.guild_id, m.scope, m.target_user_id, m.content, m.created_at, m.updated_at, m.last_accessed_at, m.expires_at, m.importance, m.keywords, v.distance
            FROM chatter_memories_vec v
            JOIN chatter_memories m ON m.id = v.memory_id
            WHERE v.embedding MATCH ? AND v.k = ?
              AND v.memory_id IN (
                  SELECT m2.id FROM chatter_memories m2
                  WHERE m2.guild_id = ? AND $prefilterScopeClause AND (m2.expires_at IS NULL OR m2.expires_at > ?) $prefilterRecencySql
              )
            ORDER BY v.distance ASC
            LIMIT ?
            """.trimIndent()
    }

    private fun escapeLikePattern(token: String): String = token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun floatArrayToLittleEndianBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }
}
