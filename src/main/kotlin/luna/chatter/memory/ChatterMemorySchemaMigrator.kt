package luna.chatter.memory

import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Handles SQLite schema initialization and idempotent table-rebuild migration
 * from legacy `channel`-scoped `chatter_memories` to `guild`-scoped `chatter_memories`
 * with nullable `expires_at` support.
 */
internal object ChatterMemorySchemaMigrator {
    private val logger = LoggerFactory.getLogger(ChatterMemorySchemaMigrator::class.java)

    /**
     * Ensures `chatter_memory_cursors`, `chatter_memories`, indexes, and FTS5 triggers exist,
     * migrating legacy `channel`-scoped tables if detected.
     *
     * @return `true` if a legacy table-rebuild migration was performed.
     */
    fun initializeAndMigrateBaseSchema(conn: Connection): Boolean {
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
        }

        val existingTableSql =
            conn
                .prepareStatement(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chatter_memories'",
                ).use { ps ->
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString("sql") else null }
                }

        if (existingTableSql == null) {
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE chatter_memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        guild_id TEXT NOT NULL,
                        scope TEXT NOT NULL CHECK(scope IN ('user', 'guild')),
                        target_user_id TEXT,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        last_accessed_at INTEGER NOT NULL,
                        expires_at INTEGER
                    );
                    """.trimIndent(),
                )
            }
            createMemoriesIndexesAndFts(conn)
            return false
        }

        val columns =
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA table_info(chatter_memories)").use { rs ->
                    buildSet {
                        while (rs.next()) {
                            add(rs.getString("name"))
                        }
                    }
                }
            }

        val hasChannelId = columns.contains("channel_id")
        val hasExpiresAt = columns.contains("expires_at")
        val hasChannelCheck = existingTableSql.contains("'channel'")

        if (!hasChannelId && hasExpiresAt && !hasChannelCheck) {
            createMemoriesIndexesAndFts(conn)
            return false
        }

        logger.info(
            "Migrating chatter_memories schema to guild scope with expires_at (hasChannelId={}, hasExpiresAt={}, hasChannelCheck={})",
            hasChannelId,
            hasExpiresAt,
            hasChannelCheck,
        )

        val expiresSelectExpr = if (hasExpiresAt) "expires_at" else "NULL"
        conn.createStatement().use { stmt ->
            stmt.execute("DROP TRIGGER IF EXISTS chatter_memories_ai;")
            stmt.execute("DROP TRIGGER IF EXISTS chatter_memories_ad;")
            stmt.execute("DROP TRIGGER IF EXISTS chatter_memories_au;")
            stmt.execute("DROP INDEX IF EXISTS idx_chatter_memories_guild_user;")
            stmt.execute("DROP INDEX IF EXISTS idx_chatter_memories_guild_channel;")
            stmt.execute("DROP INDEX IF EXISTS idx_chatter_memories_guild_scope;")
            stmt.execute("DROP INDEX IF EXISTS idx_chatter_memories_expires_at;")
            stmt.execute(
                """
                CREATE TABLE chatter_memories_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id TEXT NOT NULL,
                    scope TEXT NOT NULL CHECK(scope IN ('user', 'guild')),
                    target_user_id TEXT,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_accessed_at INTEGER NOT NULL,
                    expires_at INTEGER
                );
                """.trimIndent(),
            )
            stmt.execute(
                """
                INSERT INTO chatter_memories_new(id, guild_id, scope, target_user_id, content, created_at, updated_at, last_accessed_at, expires_at)
                SELECT
                    id,
                    guild_id,
                    CASE WHEN scope = 'channel' THEN 'guild' ELSE scope END,
                    CASE WHEN scope = 'channel' THEN NULL ELSE target_user_id END,
                    content,
                    created_at,
                    updated_at,
                    last_accessed_at,
                    $expiresSelectExpr
                FROM chatter_memories;
                """.trimIndent(),
            )
            stmt.execute("DROP TABLE chatter_memories;")
            stmt.execute("ALTER TABLE chatter_memories_new RENAME TO chatter_memories;")
        }

        createMemoriesIndexesAndFts(conn)
        conn.createStatement().use { stmt ->
            stmt.execute("INSERT INTO chatter_memories_fts(chatter_memories_fts) VALUES('rebuild');")
        }
        return true
    }

    private fun createMemoriesIndexesAndFts(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_chatter_memories_guild_user
                    ON chatter_memories(guild_id, scope, target_user_id, updated_at, last_accessed_at);
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_chatter_memories_guild_scope
                    ON chatter_memories(guild_id, scope, updated_at, last_accessed_at);
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_chatter_memories_expires_at
                    ON chatter_memories(expires_at)
                    WHERE expires_at IS NOT NULL;
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
}
