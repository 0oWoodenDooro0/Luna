package luna.chatter.memory

import luna.core.LunaHost
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteChatterMemoryStoreLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceMinutes(minutes: Long) {
            current = current.plusSeconds(minutes * 60)
        }
    }

    private fun createStore(
        clock: Clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
        maxPerUser: Int = 100,
        maxPerGuild: Int = 300,
    ): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("lifecycle-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(
            database = db,
            clock = clock,
            maxPerUser = maxPerUser,
            maxPerGuild = maxPerGuild,
        )
    }

    @Test
    fun `recency exclusion hides memories updated within 10 minutes unless disabled`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        store.insertMemory(
            guildId = "guild-1",
            scope = MemoryScope.USER,
            targetUserId = "user-1",
            content = "Alice just adopted a golden retriever.",
        )

        clock.advanceMinutes(5)
        val withinWindow =
            store.search(
                guildId = "guild-1",
                queryText = "golden retriever",
                scope = SearchScope.USER,
                targetUserId = "user-1",
            )
        assertTrue(withinWindow.isEmpty(), "Memories updated within 10 minutes should be excluded")

        val bypassWindow =
            store.search(
                guildId = "guild-1",
                queryText = "golden retriever",
                scope = SearchScope.USER,
                targetUserId = "user-1",
                recencyExcludeMinutes = 0,
                updateLastAccessed = false,
            )
        assertEquals(1, bypassWindow.size)

        clock.advanceMinutes(6)
        val afterWindow =
            store.search(
                guildId = "guild-1",
                queryText = "golden retriever",
                scope = SearchScope.USER,
                targetUserId = "user-1",
            )
        assertEquals(1, afterWindow.size)
    }

    @Test
    fun `RRF fusion rejects low-similarity non-FTS candidates below scoreCutoff without padding`() {
        val now = 1_700_000_000_000L
        val recordHigh =
            ChatterMemoryRecord(1L, "g1", MemoryScope.USER, "u1", "High vector match", now, now, now)
        val recordLow =
            ChatterMemoryRecord(2L, "g1", MemoryScope.USER, "u1", "Low vector match", now, now, now)
        val recordFtsOnly =
            ChatterMemoryRecord(3L, "g1", MemoryScope.USER, "u1", "FTS keyword match", now, now, now)
        val recordDual =
            ChatterMemoryRecord(4L, "g1", MemoryScope.USER, "u1", "Dual FTS and vector", now, now, now)

        val hits =
            SqliteChatterMemoryStore.fuseCandidatesWithRrf(
                lexicalCandidates = listOf(recordFtsOnly, recordDual),
                vectorCandidates =
                    listOf(
                        SqliteChatterMemoryStore.VectorCandidate(recordDual, cosineSimilarity = 0.82),
                        SqliteChatterMemoryStore.VectorCandidate(recordHigh, cosineSimilarity = 0.60),
                        SqliteChatterMemoryStore.VectorCandidate(recordLow, cosineSimilarity = 0.42),
                    ),
                scoreCutoff = 0.55,
                limit = 5,
            )

        assertEquals(listOf(4L, 3L, 1L), hits.map { it.memory.id })
        assertFalse(hits.any { it.memory.id == 2L }, "Vector candidate below 0.55 cutoff must be rejected")
        assertTrue(hits.first().matchedByFts && hits.first().matchedByVector, "Top hit should match both FTS and vector")
    }

    @Test
    fun `LRU eviction removes least-recently updated or accessed entry and syncs FTS5`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock = clock, maxPerUser = 3, maxPerGuild = 2)

        val first =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "First memory alpha token",
            )
        clock.advanceMinutes(15)
        val second =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "Second memory beta token",
            )
        clock.advanceMinutes(15)
        // Access 'first' via search so its last_accessed_at becomes newer than 'second'
        val accessed =
            store.search(
                guildId = "guild-1",
                queryText = "alpha",
                scope = SearchScope.USER,
                targetUserId = "user-1",
            )
        assertEquals(listOf(first.id), accessed.map { it.memory.id })

        clock.advanceMinutes(1)
        val third =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "Third memory gamma token",
            )
        clock.advanceMinutes(1)
        val fourth =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "Fourth memory delta token",
            )

        val remainingIds = store.listUserMemories("guild-1", "user-1").map { it.id }.toSet()
        assertEquals(setOf(first.id, third.id, fourth.id), remainingIds)
        assertFalse(remainingIds.contains(second.id), "Least recently accessed memory should be evicted")

        // Verify FTS5 trigger deleted 'second' from FTS index too
        clock.advanceMinutes(15)
        val evictedSearch =
            store.search(
                guildId = "guild-1",
                queryText = "beta",
                scope = SearchScope.USER,
                targetUserId = "user-1",
            )
        assertTrue(evictedSearch.isEmpty(), "Evicted memory should be removed from FTS5 index")

        // Verify guild cap (maxPerGuild = 2)
        store.insertMemory("guild-1", MemoryScope.GUILD, null, "Guild fact one")
        clock.advanceMinutes(1)
        store.insertMemory("guild-1", MemoryScope.GUILD, null, "Guild fact two")
        clock.advanceMinutes(1)
        store.insertMemory("guild-1", MemoryScope.GUILD, null, "Guild fact three")
        clock.advanceMinutes(15)

        val guildHits =
            store.search(
                guildId = "guild-1",
                queryText = "Guild fact",
                scope = SearchScope.GUILD,
                limit = 10,
            )
        assertEquals(2, guildHits.size)
        assertFalse(guildHits.any { it.memory.content == "Guild fact one" }, "Oldest guild memory should be evicted")
    }

    @Test
    fun `applyReconciliation rolls back all mutations and cursor on failure`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        assertFailsWith<IllegalArgumentException> {
            store.applyReconciliation(
                mutations =
                    listOf(
                        MemoryMutation.Insert("guild-1", MemoryScope.USER, "user-1", "Valid fact", null),
                        MemoryMutation.Insert("guild-1", MemoryScope.USER, "user-1", "   ", null),
                    ),
                cursorUpdate = ChatterMemoryCursor("chan-1", "guild-1", "999", clock.millis()),
            )
        }

        assertTrue(store.listUserMemories("guild-1", "user-1").isEmpty(), "Failed batch should roll back inserts")
        assertNull(store.getCursor("chan-1"))
    }

    @Test
    fun `deleteOwnedUserMemory enforces guild, user ownership, and user scope`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        val userMem =
            store.insertMemory("guild-1", MemoryScope.USER, "user-1", "Owned user memory")
        val otherUserMem =
            store.insertMemory("guild-1", MemoryScope.USER, "user-2", "Other user memory")
        val guildMem =
            store.insertMemory("guild-1", MemoryScope.GUILD, null, "Guild memory")

        assertFalse(store.deleteOwnedUserMemory(userMem.id, guildId = "guild-2", userId = "user-1"), "Must reject cross-guild delete")
        assertFalse(store.deleteOwnedUserMemory(otherUserMem.id, guildId = "guild-1", userId = "user-1"), "Must reject deleting another user's memory")
        assertFalse(store.deleteOwnedUserMemory(guildMem.id, guildId = "guild-1", userId = "user-1"), "Must reject deleting guild-scoped memory")
        assertTrue(store.deleteOwnedUserMemory(userMem.id, guildId = "guild-1", userId = "user-1"), "Owner should be able to delete own user memory")
        assertTrue(store.listUserMemories("guild-1", "user-1").isEmpty(), "Deleted memory should no longer appear in list")
    }

    @Test
    fun `maxUpdatedAtMs overrides relative recencyExcludeMinutes and buildVectorSearchSql pre-filters memory_id`() {
        val clock = MutableClock(Instant.parse("2026-01-01T10:05:00Z"))
        val store = createStore(clock)

        store.insertMemory(
            guildId = "guild-1",
            scope = MemoryScope.USER,
            targetUserId = "user-1",
            content = "Alice likes pour-over coffee.",
        )

        clock.advanceMinutes(6) // 10:11 (within default 10m recency window)
        val hitsWithDynamicBoundary =
            store.search(
                guildId = "guild-1",
                queryText = "pour-over",
                scope = SearchScope.USER,
                targetUserId = "user-1",
                recencyExcludeMinutes = 10,
                maxUpdatedAtMs = Instant.parse("2026-01-01T10:11:00Z").toEpochMilli(),
            )
        assertEquals(1, hitsWithDynamicBoundary.size)

        val hitsBeforeDistill =
            store.search(
                guildId = "guild-1",
                queryText = "pour-over",
                scope = SearchScope.USER,
                targetUserId = "user-1",
                recencyExcludeMinutes = 10,
                maxUpdatedAtMs = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli(),
            )
        assertTrue(hitsBeforeDistill.isEmpty(), "Memory updated after maxUpdatedAtMs must be excluded")

        val sql =
            SqliteChatterMemoryStore.buildVectorSearchSql(
                scopeClause = "(m.scope = 'user' AND m.target_user_id = ?)",
                hasRecencyFilter = true,
            )
        assertTrue(
            sql.contains("v.memory_id IN (") && sql.contains("m2.guild_id = ?") && sql.contains("m2.target_user_id = ?"),
            "Vector KNN query must pre-filter memory_id by guild and scope before ranking: $sql",
        )
        assertTrue(
            sql.contains("AND m2.updated_at <= ?"),
            "Vector KNN pre-filter subquery must include recency filter when enabled: $sql",
        )
    }
}
