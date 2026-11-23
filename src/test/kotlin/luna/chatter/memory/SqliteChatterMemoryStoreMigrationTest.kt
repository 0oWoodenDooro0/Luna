package luna.chatter.memory

import luna.core.LunaHost
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteChatterMemoryStoreMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createLegacyDatabase(dbPath: String): org.jetbrains.exposed.v1.jdbc.Database {
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        transaction(db) {
            val conn = TransactionManager.current().connection.connection as Connection
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE chatter_memory_cursors (
                        channel_id TEXT PRIMARY KEY,
                        guild_id TEXT NOT NULL,
                        last_summarized_message_id TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE chatter_memories (
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
                    CREATE INDEX idx_chatter_memories_guild_user
                        ON chatter_memories(guild_id, scope, target_user_id, updated_at, last_accessed_at);
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE INDEX idx_chatter_memories_guild_channel
                        ON chatter_memories(guild_id, scope, channel_id, updated_at, last_accessed_at);
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE VIRTUAL TABLE chatter_memories_fts USING fts5(
                        content,
                        content='chatter_memories',
                        content_rowid='id',
                        tokenize='trigram'
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TRIGGER chatter_memories_ai AFTER INSERT ON chatter_memories BEGIN
                        INSERT INTO chatter_memories_fts(rowid, content) VALUES (new.id, new.content);
                    END;
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TRIGGER chatter_memories_ad AFTER DELETE ON chatter_memories BEGIN
                        INSERT INTO chatter_memories_fts(chatter_memories_fts, rowid, content) VALUES('delete', old.id, old.content);
                    END;
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TRIGGER chatter_memories_au AFTER UPDATE ON chatter_memories BEGIN
                        INSERT INTO chatter_memories_fts(chatter_memories_fts, rowid, content) VALUES('delete', old.id, old.content);
                        INSERT INTO chatter_memories_fts(rowid, content) VALUES (new.id, new.content);
                    END;
                    """.trimIndent(),
                )
            }
        }
        return db
    }

    @Test
    fun `migrates legacy channel-scoped schema idempotently to guild scope with expires_at and preserves FTS5 search`() {
        val dbPath = tempDir.resolve("legacy-migrate-${System.nanoTime()}.db").toString()
        val db = createLegacyDatabase(dbPath)
        val clock = Clock.fixed(Instant.parse("2026-01-01T01:00:00Z"), ZoneOffset.UTC)
        val t0 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

        transaction(db) {
            val conn = TransactionManager.current().connection.connection as Connection
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO chatter_memory_cursors(channel_id, guild_id, last_summarized_message_id, updated_at) VALUES ('chan-1', 'guild-1', '500', $t0);",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-1', 'user', 'user-1', 'chan-1', 'Alice likes pour-over coffee', $t0, $t0, $t0);",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-1', 'channel', NULL, 'chan-1', 'Server Friday movie nights', $t0, $t0, $t0);",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-1', 'channel', NULL, 'chan-2', 'Server Sunday board games', $t0, $t0, $t0);",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-2', 'channel', NULL, 'chan-1', 'Other guild trivia nights', $t0, $t0, $t0);",
                )
            }
        }

        // First startup runs migration
        val store = SqliteChatterMemoryStore(database = db, clock = clock, maxPerGuild = 300)

        // Verify schema columns and indexes
        transaction(db) {
            val conn = TransactionManager.current().connection.connection as Connection
            val columns =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA table_info(chatter_memories)").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString("name")) }
                    }
                }
            assertFalse(columns.contains("channel_id"), "Legacy channel_id column must be removed")
            assertTrue(columns.contains("expires_at"), "expires_at column must be added")

            val tableSql =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'chatter_memories'").use { rs ->
                        assertTrue(rs.next(), "Expected chatter_memories table in sqlite_master")
                        rs.getString("sql")
                    }
                }
            assertTrue(tableSql.contains("'guild'") && !tableSql.contains("'channel'"), "CHECK constraint must enforce user|guild: $tableSql")

            val indexNames =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'chatter_memories'").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString("name")) }
                    }
                }
            assertTrue(indexNames.contains("idx_chatter_memories_expires_at"), "Expected expires_at index: $indexNames")
        }

        // Cursor remains per-channel
        assertEquals("500", store.getCursor("chan-1")?.lastSummarizedMessageId)

        // Both legacy channel-1 and channel-2 rows are now guild-scoped in guild-1
        val guild1Hits = store.search(guildId = "guild-1", queryText = "Server", scope = SearchScope.GUILD, limit = 10)
        assertEquals(2, guild1Hits.size)
        assertTrue(
            guild1Hits.all { it.memory.scope == MemoryScope.GUILD && it.memory.targetUserId == null && it.memory.expiresAt == null },
            "Expected all migrated channel memories to have guild scope, null targetUserId, and null expiresAt",
        )

        // Guild 2 remains isolated
        val guild2Hits = store.search(guildId = "guild-2", queryText = "nights", scope = SearchScope.GUILD, limit = 10)
        assertEquals(1, guild2Hits.size)
        assertEquals("Other guild trivia nights", guild2Hits.first().memory.content)

        // User memory preserved
        val userMems = store.listUserMemories(guildId = "guild-1", userId = "user-1")
        assertEquals(1, userMems.size)
        assertNull(userMems.first().expiresAt)

        // Second startup is idempotent
        val storeAgain = SqliteChatterMemoryStore(database = db, clock = clock, maxPerGuild = 300)
        val guild1HitsAgain = storeAgain.search(guildId = "guild-1", queryText = "Server", scope = SearchScope.GUILD, limit = 10)
        assertEquals(2, guild1HitsAgain.size)
    }

    @Test
    fun `migration evicts guild LRU overflow against maxPerGuild and keeps FTS5 in sync`() {
        val dbPath = tempDir.resolve("legacy-lru-${System.nanoTime()}.db").toString()
        val db = createLegacyDatabase(dbPath)
        val clock = Clock.fixed(Instant.parse("2026-01-01T02:00:00Z"), ZoneOffset.UTC)
        val t0 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

        transaction(db) {
            val conn = TransactionManager.current().connection.connection as Connection
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-1', 'channel', NULL, 'chan-1', 'Legacy fact oldest', $t0, ${t0 + 1000}, ${t0 + 1000});",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-1', 'channel', NULL, 'chan-2', 'Legacy fact second', $t0, ${t0 + 2000}, ${t0 + 2000});",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-1', 'channel', NULL, 'chan-1', 'Legacy fact third', $t0, ${t0 + 3000}, ${t0 + 3000});",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-1', 'channel', NULL, 'chan-2', 'Legacy fact newest', $t0, ${t0 + 4000}, ${t0 + 4000});",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-2', 'channel', NULL, 'chan-1', 'Guild 2 fact alpha', $t0, ${t0 + 1000}, ${t0 + 1000});",
                )
                stmt.execute(
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, channel_id, content, created_at, updated_at, last_accessed_at) VALUES ('guild-2', 'channel', NULL, 'chan-2', 'Guild 2 fact beta', $t0, ${t0 + 2000}, ${t0 + 2000});",
                )
            }
        }

        val store = SqliteChatterMemoryStore(database = db, clock = clock, maxPerGuild = 2)

        val guild1Hits = store.search(guildId = "guild-1", queryText = "Legacy fact", scope = SearchScope.GUILD, limit = 10)
        assertEquals(2, guild1Hits.size)
        val contents = guild1Hits.map { it.memory.content }.toSet()
        assertEquals(setOf("Legacy fact third", "Legacy fact newest"), contents)

        val evictedSearch = store.search(guildId = "guild-1", queryText = "oldest", scope = SearchScope.GUILD, limit = 10)
        assertTrue(evictedSearch.isEmpty(), "Evicted legacy row must also be removed from FTS5")

        val guild2Hits = store.search(guildId = "guild-2", queryText = "Guild 2 fact", scope = SearchScope.GUILD, limit = 10)
        assertEquals(2, guild2Hits.size)
        assertNotNull(guild2Hits.firstOrNull { it.memory.content == "Guild 2 fact alpha" })
    }
}
