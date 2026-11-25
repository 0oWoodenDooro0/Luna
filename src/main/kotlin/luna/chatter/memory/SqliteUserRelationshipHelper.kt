package luna.chatter.memory

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.Logger
import java.sql.Connection
import java.sql.ResultSet

/**
 * Pure-relational SQL helpers for `chatter_relationships` (`User Relationship` records)
 * with guild isolation, score/delta/tone clamping, and asymmetric `Negative Affinity Decay`.
 */
internal object SqliteUserRelationshipHelper {
    fun getFailOpen(
        database: Database,
        guildId: String,
        userId: String,
        now: Long,
        logger: Logger,
    ): UserRelationship =
        runCatching {
            transaction(database) {
                get(
                    conn = TransactionManager.current().connection.connection as Connection,
                    guildId = guildId,
                    userId = userId,
                    now = now,
                )
            }
        }.onFailure { error ->
            logger.warn(
                "Failed to read UserRelationship for guild={} user={}; failing open to NEUTRAL: {}",
                guildId,
                userId,
                error.message,
            )
        }.getOrElse {
            UserRelationship.neutral(guildId = guildId, userId = userId, now = now)
        }

    fun listFailOpen(
        database: Database,
        guildId: String,
        userIds: Collection<String>,
        now: Long,
        logger: Logger,
    ): Map<String, UserRelationship> {
        if (userIds.isEmpty()) return emptyMap()
        return runCatching {
            transaction(database) {
                list(
                    conn = TransactionManager.current().connection.connection as Connection,
                    guildId = guildId,
                    userIds = userIds,
                    now = now,
                )
            }
        }.onFailure { error ->
            logger.warn(
                "Failed to batch-read UserRelationships for guild={} userCount={}; failing open to empty map: {}",
                guildId,
                userIds.size,
                error.message,
            )
        }.getOrElse {
            emptyMap()
        }
    }

    fun applyUpdateTx(
        database: Database,
        guildId: String,
        userId: String,
        affinityDelta: Int,
        toneStyle: String?,
        now: Long,
    ): UserRelationship =
        transaction(database) {
            applyUpdate(
                conn = TransactionManager.current().connection.connection as Connection,
                guildId = guildId,
                userId = userId,
                affinityDelta = affinityDelta,
                toneStyle = toneStyle,
                now = now,
            )
        }

    fun deleteTx(
        database: Database,
        guildId: String,
        userId: String,
    ): Boolean =
        transaction(database) {
            delete(
                conn = TransactionManager.current().connection.connection as Connection,
                guildId = guildId,
                userId = userId,
            )
        }

    fun get(
        conn: Connection,
        guildId: String,
        userId: String,
        now: Long,
    ): UserRelationship =
        conn
            .prepareStatement(
                """
                SELECT guild_id, user_id, affinity_score, tone_style, created_at, updated_at, last_interaction_at
                FROM chatter_relationships
                WHERE guild_id = ? AND user_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, guildId)
                ps.setString(2, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        readEffectiveRecord(rs, now)
                    } else {
                        UserRelationship.neutral(guildId = guildId, userId = userId, now = now)
                    }
                }
            }

    fun list(
        conn: Connection,
        guildId: String,
        userIds: Collection<String>,
        now: Long,
    ): Map<String, UserRelationship> {
        val distinctIds = userIds.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        val placeholders = distinctIds.joinToString(",") { "?" }
        return conn
            .prepareStatement(
                """
                SELECT guild_id, user_id, affinity_score, tone_style, created_at, updated_at, last_interaction_at
                FROM chatter_relationships
                WHERE guild_id = ? AND user_id IN ($placeholders)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, guildId)
                distinctIds.forEachIndexed { index, userId -> ps.setString(index + 2, userId) }
                ps.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val record = readEffectiveRecord(rs, now)
                            put(record.userId, record)
                        }
                    }
                }
            }
    }

    fun applyUpdate(
        conn: Connection,
        guildId: String,
        userId: String,
        affinityDelta: Int,
        toneStyle: String?,
        now: Long,
    ): UserRelationship {
        val existing =
            conn
                .prepareStatement(
                    """
                    SELECT guild_id, user_id, affinity_score, tone_style, created_at, updated_at, last_interaction_at
                    FROM chatter_relationships
                    WHERE guild_id = ? AND user_id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, guildId)
                    ps.setString(2, userId)
                    ps.executeQuery().use { rs -> if (rs.next()) readEffectiveRecord(rs, now) else null }
                }

        val clampedDelta = NegativeAffinityDecay.clampDelta(affinityDelta)
        val baseScore = existing?.affinityScore ?: 0
        val nextScore = NegativeAffinityDecay.clampScore(baseScore + clampedDelta)
        val nextToneStyle =
            if (toneStyle != null) {
                NegativeAffinityDecay.sanitizeToneStyle(toneStyle)
            } else {
                existing?.toneStyle.orEmpty()
            }
        val createdAt = existing?.createdAt ?: now

        conn
            .prepareStatement(
                """
                INSERT INTO chatter_relationships(
                    guild_id, user_id, affinity_score, tone_style, created_at, updated_at, last_interaction_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guild_id, user_id) DO UPDATE SET
                    affinity_score = excluded.affinity_score,
                    tone_style = excluded.tone_style,
                    updated_at = excluded.updated_at,
                    last_interaction_at = excluded.last_interaction_at
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, guildId)
                ps.setString(2, userId)
                ps.setInt(3, nextScore)
                ps.setString(4, nextToneStyle)
                ps.setLong(5, createdAt)
                ps.setLong(6, now)
                ps.setLong(7, now)
                ps.executeUpdate()
            }

        return UserRelationship(
            guildId = guildId,
            userId = userId,
            affinityScore = nextScore,
            toneStyle = nextToneStyle,
            createdAt = createdAt,
            updatedAt = now,
            lastInteractionAt = now,
        )
    }

    fun delete(
        conn: Connection,
        guildId: String,
        userId: String,
    ): Boolean =
        conn
            .prepareStatement("DELETE FROM chatter_relationships WHERE guild_id = ? AND user_id = ?")
            .use { ps ->
                ps.setString(1, guildId)
                ps.setString(2, userId)
                ps.executeUpdate() > 0
            }

    private fun readEffectiveRecord(
        rs: ResultSet,
        now: Long,
    ): UserRelationship {
        val lastInteractionAt = rs.getLong("last_interaction_at")
        val rawScore = rs.getInt("affinity_score")
        val effectiveScore =
            NegativeAffinityDecay.effectiveScore(
                rawScore = rawScore,
                lastInteractionAtMs = lastInteractionAt,
                nowMs = now,
            )
        return UserRelationship(
            guildId = rs.getString("guild_id"),
            userId = rs.getString("user_id"),
            affinityScore = effectiveScore,
            toneStyle = rs.getString("tone_style"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            lastInteractionAt = lastInteractionAt,
        )
    }
}
