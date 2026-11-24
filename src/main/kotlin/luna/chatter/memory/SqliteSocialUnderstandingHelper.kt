package luna.chatter.memory

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * Pure-relational SQL helpers for `chatter_understandings` (`Social Understanding` records)
 * with guild isolation and `MAX(updated_at, last_accessed_at)` LRU eviction.
 */
internal object SqliteSocialUnderstandingHelper {
    fun insert(
        conn: Connection,
        guildId: String,
        scope: MemoryScope,
        targetUserId: String?,
        subject: String,
        content: String,
        now: Long,
        maxPerUser: Int,
        maxPerGuild: Int,
    ): SocialUnderstandingRecord {
        val effectiveTargetUserId =
            if (scope == MemoryScope.USER) {
                require(!targetUserId.isNullOrBlank()) { "targetUserId is required for USER scope understandings" }
                targetUserId
            } else {
                null
            }
        val cleanSubject = subject.trim()
        val cleanContent = content.trim()
        require(cleanSubject.isNotEmpty()) { "Understanding subject must not be blank" }
        require(cleanContent.isNotEmpty()) { "Understanding content must not be blank" }

        val insertedId =
            conn
                .prepareStatement(
                    """
                    INSERT INTO chatter_understandings(
                        guild_id, scope, target_user_id, subject, content, created_at, updated_at, last_accessed_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.setString(1, guildId)
                    ps.setString(2, scope.dbValue)
                    ps.setString(3, effectiveTargetUserId)
                    ps.setString(4, cleanSubject)
                    ps.setString(5, cleanContent)
                    ps.setLong(6, now)
                    ps.setLong(7, now)
                    ps.setLong(8, now)
                    ps.executeUpdate()
                    ps.generatedKeys.use { keys ->
                        check(keys.next()) { "SQLite did not return generated row ID for chatter_understandings insert" }
                        keys.getLong(1)
                    }
                }

        evictLruOverflow(
            conn = conn,
            guildId = guildId,
            scope = scope,
            targetUserId = effectiveTargetUserId,
            maxPerUser = maxPerUser,
            maxPerGuild = maxPerGuild,
        )

        return SocialUnderstandingRecord(
            id = insertedId,
            guildId = guildId,
            scope = scope,
            targetUserId = effectiveTargetUserId,
            subject = cleanSubject,
            content = cleanContent,
            createdAt = now,
            updatedAt = now,
            lastAccessedAt = now,
        )
    }

    fun update(
        conn: Connection,
        id: Long,
        guildId: String,
        subject: String,
        content: String,
        now: Long,
    ): Boolean {
        val cleanSubject = subject.trim()
        val cleanContent = content.trim()
        require(cleanSubject.isNotEmpty()) { "Understanding subject must not be blank" }
        require(cleanContent.isNotEmpty()) { "Understanding content must not be blank" }

        return conn
            .prepareStatement(
                """
                UPDATE chatter_understandings
                SET subject = ?, content = ?, updated_at = ?, last_accessed_at = ?
                WHERE id = ? AND guild_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, cleanSubject)
                ps.setString(2, cleanContent)
                ps.setLong(3, now)
                ps.setLong(4, now)
                ps.setLong(5, id)
                ps.setString(6, guildId)
                ps.executeUpdate() > 0
            }
    }

    fun reaffirm(
        conn: Connection,
        id: Long,
        guildId: String,
        now: Long,
    ): Boolean =
        conn
            .prepareStatement(
                """
                UPDATE chatter_understandings
                SET updated_at = ?, last_accessed_at = ?
                WHERE id = ? AND guild_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, now)
                ps.setLong(2, now)
                ps.setLong(3, id)
                ps.setString(4, guildId)
                ps.executeUpdate() > 0
            }

    fun delete(
        conn: Connection,
        id: Long,
        guildId: String,
    ): Boolean =
        conn
            .prepareStatement("DELETE FROM chatter_understandings WHERE id = ? AND guild_id = ?")
            .use { ps ->
                ps.setLong(1, id)
                ps.setString(2, guildId)
                ps.executeUpdate() > 0
            }

    fun deleteOwnedUser(
        conn: Connection,
        id: Long,
        guildId: String,
        userId: String,
    ): Boolean =
        conn
            .prepareStatement(
                """
                DELETE FROM chatter_understandings
                WHERE id = ? AND guild_id = ? AND scope = 'user' AND target_user_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, id)
                ps.setString(2, guildId)
                ps.setString(3, userId)
                ps.executeUpdate() > 0
            }

    fun deleteAllOwnedUser(
        conn: Connection,
        guildId: String,
        userId: String,
    ): Int =
        conn
            .prepareStatement(
                """
                DELETE FROM chatter_understandings
                WHERE guild_id = ? AND scope = 'user' AND target_user_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, guildId)
                ps.setString(2, userId)
                ps.executeUpdate()
            }

    fun listUser(
        conn: Connection,
        guildId: String,
        userId: String,
        limit: Int,
    ): List<SocialUnderstandingRecord> {
        if (limit <= 0) return emptyList()
        return conn
            .prepareStatement(
                """
                SELECT id, guild_id, scope, target_user_id, subject, content, created_at, updated_at, last_accessed_at
                FROM chatter_understandings
                WHERE guild_id = ? AND scope = 'user' AND target_user_id = ?
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, guildId)
                ps.setString(2, userId)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(readRecord(rs))
                    }
                }
            }
    }

    fun listGuild(
        conn: Connection,
        guildId: String,
        limit: Int,
    ): List<SocialUnderstandingRecord> {
        if (limit <= 0) return emptyList()
        return conn
            .prepareStatement(
                """
                SELECT id, guild_id, scope, target_user_id, subject, content, created_at, updated_at, last_accessed_at
                FROM chatter_understandings
                WHERE guild_id = ? AND scope = 'guild'
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, guildId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(readRecord(rs))
                    }
                }
            }
    }

    fun touch(
        conn: Connection,
        guildId: String,
        ids: List<Long>,
        now: Long,
    ) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        conn
            .prepareStatement(
                "UPDATE chatter_understandings SET last_accessed_at = ? WHERE guild_id = ? AND id IN ($placeholders)",
            ).use { ps ->
                ps.setLong(1, now)
                ps.setString(2, guildId)
                ids.forEachIndexed { index, id -> ps.setLong(index + 3, id) }
                ps.executeUpdate()
            }
    }

    fun applyMutations(
        conn: Connection,
        mutations: List<UnderstandingMutation>,
        now: Long,
        maxPerUser: Int,
        maxPerGuild: Int,
    ) {
        for (mutation in mutations) {
            when (mutation) {
                is UnderstandingMutation.Add -> {
                    insert(
                        conn = conn,
                        guildId = mutation.guildId,
                        scope = mutation.scope,
                        targetUserId = mutation.targetUserId,
                        subject = mutation.subject,
                        content = mutation.content,
                        now = now,
                        maxPerUser = maxPerUser,
                        maxPerGuild = maxPerGuild,
                    )
                }

                is UnderstandingMutation.Update -> {
                    update(
                        conn = conn,
                        id = mutation.id,
                        guildId = mutation.guildId,
                        subject = mutation.subject,
                        content = mutation.content,
                        now = now,
                    )
                }

                is UnderstandingMutation.Reaffirm -> {
                    reaffirm(
                        conn = conn,
                        id = mutation.id,
                        guildId = mutation.guildId,
                        now = now,
                    )
                }

                is UnderstandingMutation.Delete -> {
                    delete(
                        conn = conn,
                        id = mutation.id,
                        guildId = mutation.guildId,
                    )
                }
            }
        }
    }

    private fun evictLruOverflow(
        conn: Connection,
        guildId: String,
        scope: MemoryScope,
        targetUserId: String?,
        maxPerUser: Int,
        maxPerGuild: Int,
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
            conn.prepareStatement("SELECT COUNT(*) FROM chatter_understandings WHERE $scopeWhere").use { ps ->
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
                    SELECT id FROM chatter_understandings
                    WHERE $scopeWhere
                    ORDER BY MAX(updated_at, last_accessed_at) ASC, id ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    params.forEachIndexed { index, param -> ps.setString(index + 1, param) }
                    ps.setInt(params.size + 1, excess)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(rs.getLong(1))
                        }
                    }
                }

        for (victimId in victimIds) {
            delete(conn, id = victimId, guildId = guildId)
        }
    }

    private fun readRecord(rs: ResultSet): SocialUnderstandingRecord =
        SocialUnderstandingRecord(
            id = rs.getLong("id"),
            guildId = rs.getString("guild_id"),
            scope = MemoryScope.fromDbValue(rs.getString("scope")),
            targetUserId = rs.getString("target_user_id"),
            subject = rs.getString("subject"),
            content = rs.getString("content"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            lastAccessedAt = rs.getLong("last_accessed_at"),
        )
}
