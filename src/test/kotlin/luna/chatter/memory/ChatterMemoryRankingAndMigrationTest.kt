package luna.chatter.memory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import luna.core.LunaHost
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterMemoryRankingAndMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = current

        fun advanceMinutes(minutes: Long) {
            current = current.plusSeconds(minutes * 60)
        }
    }

    private fun createStore(
        clock: Clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
        maxPerUser: Int = 100,
        maxPerGuild: Int = 300,
        embeddingDimension: Int = 768,
    ): Pair<SqliteChatterMemoryStore, org.jetbrains.exposed.v1.jdbc.Database> {
        val dbPath = tempDir.resolve("rank-migrate-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        val store =
            SqliteChatterMemoryStore(
                database = db,
                clock = clock,
                maxPerUser = maxPerUser,
                maxPerGuild = maxPerGuild,
                embeddingDimension = embeddingDimension,
            )
        return store to db
    }

    @Test
    fun `dual-signal recency decay blends updatedAt and lastAccessedAt with 0_7 floor and linear importance multiplier`() {
        val nowMs = Instant.parse("2026-11-23T00:00:00Z").toEpochMilli()
        val sixtyDaysAgoMs = nowMs - 60L * 86_400_000L

        // Same updatedAt (60d ago), but one was recalled recently (0d ago) and one was never recalled (60d ago)
        val recalledRecord =
            ChatterMemoryRecord(
                id = 1L,
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                content = "Frequently recalled fact",
                createdAt = sixtyDaysAgoMs,
                updatedAt = sixtyDaysAgoMs,
                lastAccessedAt = nowMs,
                importance = 3,
            )
        val staleRecord =
            ChatterMemoryRecord(
                id = 2L,
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                content = "Never recalled fact",
                createdAt = sixtyDaysAgoMs,
                updatedAt = sixtyDaysAgoMs,
                lastAccessedAt = sixtyDaysAgoMs,
                importance = 3,
            )

        // Both have identical vector similarity and rank? Let's test via lexicalCandidates + vectorCandidates
        // Give staleRecord rank 1 in FTS and recalledRecord rank 1 in vector so both have identical RRF = 1/61
        val hits =
            ChatterMemoryRrfRanker.fuseCandidatesWithRrf(
                lexicalCandidates = listOf(staleRecord),
                vectorCandidates =
                    listOf(
                        SqliteChatterMemoryStore.VectorCandidate(recalledRecord, cosineSimilarity = 0.80),
                    ),
                scoreCutoff = 0.55,
                limit = 5,
                nowMs = nowMs,
            )

        assertEquals(listOf(1L, 2L), hits.map { it.memory.id })
        val expectedRrf = 1.0 / 61.0
        val expectedRecalledDecay = 0.7 + 0.3 * (0.7 * exp(-1.0) + 0.3 * 1.0)
        val expectedStaleDecay = 0.7 + 0.3 * (0.7 * exp(-1.0) + 0.3 * exp(-1.0))
        assertEquals(expectedRrf * expectedRecalledDecay, hits[0].score, 1e-12)
        assertEquals(expectedRrf * expectedStaleDecay, hits[1].score, 1e-12)

        // Test importance multiplier (0.85..1.15 for 1..5) at nowMs (decay = 1.0)
        val lowImportance =
            ChatterMemoryRecord(10L, "g1", MemoryScope.USER, "u1", "Trivia", nowMs, nowMs, nowMs, importance = 1)
        val highImportance =
            ChatterMemoryRecord(11L, "g1", MemoryScope.USER, "u1", "Core", nowMs, nowMs, nowMs, importance = 5)
        val clampedOutOfRange =
            ChatterMemoryRecord(12L, "g1", MemoryScope.USER, "u1", "Clamped", nowMs, nowMs, nowMs, importance = 99)

        val impHits =
            ChatterMemoryRrfRanker.fuseCandidatesWithRrf(
                lexicalCandidates = listOf(lowImportance),
                vectorCandidates =
                    listOf(
                        SqliteChatterMemoryStore.VectorCandidate(highImportance, cosineSimilarity = 0.80),
                    ),
                scoreCutoff = 0.55,
                limit = 5,
                nowMs = nowMs,
            )
        assertEquals(listOf(11L, 10L), impHits.map { it.memory.id })
        assertEquals(expectedRrf * 1.15, impHits[0].score, 1e-12)
        assertEquals(expectedRrf * 0.85, impHits[1].score, 1e-12)

        val clampedHits =
            ChatterMemoryRrfRanker.fuseCandidatesWithRrf(
                lexicalCandidates = listOf(clampedOutOfRange),
                vectorCandidates = emptyList(),
                scoreCutoff = 0.55,
                limit = 5,
                nowMs = nowMs,
            )
        assertEquals(expectedRrf * 1.15, clampedHits.single().score, 1e-12)
        assertTrue(impHits[0].score > impHits[1].score, "High importance must outrank low importance at equal RRF")
    }

    @Test
    fun `importance clamping, keyword sanitization, FTS5 and LIKE keyword search work end-to-end`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val (store, _) = createStore(clock = clock)

        val lowClamped =
            store.insertMemory(
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                content = "Alice drank oat milk latte today.",
                importance = 0,
                keywords = "  咖啡   拿鐵   espresso  ",
            )
        assertEquals(1, lowClamped.importance)
        assertEquals("咖啡 拿鐵 espresso", lowClamped.keywords)

        val oversizedKeywords = "k".repeat(200)
        val highClamped =
            store.insertMemory(
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                content = "Alice is allergic to shellfish.",
                importance = 99,
                keywords = oversizedKeywords,
            )
        assertEquals(5, highClamped.importance)
        assertEquals(ChatterMemoryStore.MAX_KEYWORDS_CHARS, highClamped.keywords.length)

        clock.advanceMinutes(15)

        // FTS5 trigram search matches keyword even when not in content
        val ftsByKeyword =
            store.search(
                guildId = "g1",
                queryText = "拿鐵",
                scope = SearchScope.USER,
                targetUserId = "u1",
            )
        // 2 chars triggers LIKE fallback; also test 3+ chars for FTS5 trigram
        assertEquals(listOf(lowClamped.id), ftsByKeyword.map { it.memory.id })

        val trigramByKeyword =
            store.search(
                guildId = "g1",
                queryText = "espresso",
                scope = SearchScope.USER,
                targetUserId = "u1",
            )
        assertEquals(listOf(lowClamped.id), trigramByKeyword.map { it.memory.id })
        assertTrue(trigramByKeyword.single().matchedByFts, "Expected keyword query to match via FTS5")
    }

    @Test
    fun `importance-aware LRU eviction evicts lower-importance stale facts before core facts with importance ge 4`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val (store, _) = createStore(clock = clock, maxPerUser = 3)

        // Oldest fact is high importance (5)
        val coreFact =
            store.insertMemory(
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                content = "User has severe peanut allergy",
                importance = 5,
            )
        clock.advanceMinutes(15)

        // Newer fact is low importance (2)
        val lowFact =
            store.insertMemory(
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                content = "User ate a sandwich",
                importance = 2,
            )
        clock.advanceMinutes(15)

        // Even newer fact is normal importance (3)
        val normalFact =
            store.insertMemory(
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                content = "User plays RPGs",
                importance = 3,
            )
        clock.advanceMinutes(15)

        // Insert 4th fact -> cap is 3 -> lowFact (importance=2) must be evicted before older coreFact (importance=5)
        val newestFact =
            store.insertMemory(
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                content = "User likes jazz",
                importance = 3,
            )

        val remainingIds = store.listUserMemories("g1", "u1").map { it.id }.toSet()
        assertEquals(setOf(coreFact.id, normalFact.id, newestFact.id), remainingIds)
        assertFalse(remainingIds.contains(lowFact.id), "Lower-importance fact must be evicted before high-importance core fact")
    }

    @Test
    fun `migrates existing guild-scoped chatter_memories without importance or keywords idempotently and creates chatter_understandings`() {
        val dbPath = tempDir.resolve("guild-migrate-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        val t0 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

        transaction(db) {
            val conn = TransactionManager.current().connection.connection as Connection
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
                    "INSERT INTO chatter_memories(guild_id, scope, target_user_id, content, created_at, updated_at, last_accessed_at, expires_at) VALUES ('g1', 'user', 'u1', 'Existing durable fact', $t0, $t0, $t0, NULL);",
                )
            }
        }

        val clock = MutableClock(Instant.parse("2026-01-01T01:00:00Z"))
        val store = SqliteChatterMemoryStore(database = db, clock = clock)

        val listed = store.listUserMemories("g1", "u1")
        assertEquals(1, listed.size)
        assertEquals(3, listed.single().importance)
        assertEquals("", listed.single().keywords)

        // Verify chatter_understandings table exists without vec0/FTS5/keywords/expires_at
        transaction(db) {
            val conn = TransactionManager.current().connection.connection as Connection
            val cols =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA table_info(chatter_understandings)").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString("name")) }
                    }
                }
            assertEquals(
                setOf("id", "guild_id", "scope", "target_user_id", "subject", "content", "created_at", "updated_at", "last_accessed_at"),
                cols,
            )
            val ftsCols =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA table_info(chatter_memories_fts)").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString("name")) }
                    }
                }
            assertEquals(setOf("content", "keywords"), ftsCols)
        }

        // Idempotent second startup
        val store2 = SqliteChatterMemoryStore(database = db, clock = clock)
        assertEquals(1, store2.listUserMemories("g1", "u1").size)
    }

    @Test
    fun `detects chatter_memories_vec dimension mismatch and degrades fail-open with warning log`() {
        val dbPath = tempDir.resolve("vec-mismatch-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())

        // Simulate an existing vec0 table definition in sqlite_master? Wait: without vec0.so loaded,
        // SELECT vec_version() fails before checking sqlite_master.
        // We can test the dimension mismatch check helper directly or with a mock/table if vec0 is absent,
        // OR test both: checkVectorDimensionCompatibility(existingSql, expectedDimension) AND log output!
        val logger = LoggerFactory.getLogger(SqliteChatterMemoryStore::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val compatible =
                SqliteChatterMemoryStore.isVectorDimensionCompatible(
                    existingTableSql =
                        "CREATE VIRTUAL TABLE chatter_memories_vec USING vec0(" +
                            "memory_id INTEGER PRIMARY KEY, embedding float[1536] distance_metric=cosine)",
                    expectedDimension = 768,
                    logger = logger,
                )
            assertFalse(compatible, "Mismatched vec0 dimension must return false and degrade fail-open")
            assertTrue(
                appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("dimension mismatch") },
                "Expected WARN log on vec0 dimension mismatch",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }
}
