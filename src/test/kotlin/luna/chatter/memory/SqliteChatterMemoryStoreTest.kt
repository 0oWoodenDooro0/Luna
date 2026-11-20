package luna.chatter.memory

import luna.core.LunaHost
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteChatterMemoryStoreTest {
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

    private fun createStore(clock: Clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("memory-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    @Test
    fun `initializes schema cleanly and fails open to lexical FTS5 when sqlite-vec is absent`() {
        val store = createStore()
        assertFalse(store.isVectorSearchEnabled, "Vector search should be disabled when vec0 is missing")
    }

    @Test
    fun `getCursor and upsertCursor persist and update per-channel cursor`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        assertNull(store.getCursor("chan-1"))

        val created = store.upsertCursor(channelId = "chan-1", guildId = "guild-1", lastSummarizedMessageId = "100")
        assertEquals("chan-1", created.channelId)
        assertEquals("guild-1", created.guildId)
        assertEquals("100", created.lastSummarizedMessageId)

        clock.advanceMinutes(5)
        val updated = store.upsertCursor(channelId = "chan-1", guildId = "guild-1", lastSummarizedMessageId = "250")
        assertEquals("250", updated.lastSummarizedMessageId)
        assertEquals("250", store.getCursor("chan-1")?.lastSummarizedMessageId)
    }

    @Test
    fun `CJK queries at least 3 chars use FTS5 trigram and shorter queries use LIKE fallback`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        store.insertMemory(
            guildId = "guild-1",
            scope = MemoryScope.USER,
            targetUserId = "user-1",
            channelId = "chan-a",
            content = "Alice 每天早上都喜歡喝冰美式咖啡，不加糖。",
        )
        store.insertMemory(
            guildId = "guild-1",
            scope = MemoryScope.USER,
            targetUserId = "user-1",
            channelId = "chan-a",
            content = "Alice 週末常去爬象山看夜景。",
        )
        clock.advanceMinutes(15)

        val trigramHits =
            store.search(
                guildId = "guild-1",
                channelId = "chan-a",
                queryText = "喜歡喝冰美式",
                scope = SearchScope.USER,
                targetUserId = "user-1",
            )
        assertEquals(1, trigramHits.size)
        assertTrue(trigramHits.first().matchedByFts, "Trigram query should match via FTS5")
        assertTrue(
            trigramHits
                .first()
                .memory.content
                .contains("冰美式"),
            "Matched memory should contain query term",
        )

        val shortQueryHits =
            store.search(
                guildId = "guild-1",
                channelId = "chan-a",
                queryText = "象山",
                scope = SearchScope.USER,
                targetUserId = "user-1",
            )
        assertEquals(1, shortQueryHits.size)
        assertTrue(shortQueryHits.first().matchedByFts, "Short query should match via LIKE fallback")
        assertTrue(
            shortQueryHits
                .first()
                .memory.content
                .contains("象山"),
            "Matched memory should contain short query term",
        )
    }

    @Test
    fun `user memories are shared across channels in same guild and isolated across guilds`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        store.insertMemory(
            guildId = "guild-1",
            scope = MemoryScope.USER,
            targetUserId = "user-42",
            channelId = "chan-a",
            content = "Bob prefers dark roast espresso.",
        )
        store.insertMemory(
            guildId = "guild-2",
            scope = MemoryScope.USER,
            targetUserId = "user-42",
            channelId = "chan-a",
            content = "Bob is allergic to peanuts in guild 2.",
        )
        clock.advanceMinutes(15)

        // Searching from chan-b in guild-1 finds the user memory created in chan-a
        val sameGuildOtherChannel =
            store.search(
                guildId = "guild-1",
                channelId = "chan-b",
                queryText = "espresso",
                scope = SearchScope.USER,
                targetUserId = "user-42",
            )
        assertEquals(1, sameGuildOtherChannel.size)
        assertEquals("guild-1", sameGuildOtherChannel.first().memory.guildId)

        // Negative test: guild-2 search never returns guild-1's memory
        val crossGuildHits =
            store.search(
                guildId = "guild-2",
                channelId = "chan-a",
                queryText = "espresso",
                scope = SearchScope.BOTH,
                targetUserId = "user-42",
            )
        assertTrue(crossGuildHits.isEmpty(), "Guild 2 must never see Guild 1 memories")
    }

    @Test
    fun `scope filters USER vs CHANNEL vs BOTH accurately`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        store.insertMemory(
            guildId = "guild-1",
            scope = MemoryScope.USER,
            targetUserId = "user-1",
            channelId = "chan-a",
            content = "User preference about TypeScript projects.",
        )
        store.insertMemory(
            guildId = "guild-1",
            scope = MemoryScope.CHANNEL,
            targetUserId = null,
            channelId = "chan-a",
            content = "Channel rule for TypeScript code reviews.",
        )
        clock.advanceMinutes(15)

        val userOnly =
            store.search(
                guildId = "guild-1",
                channelId = "chan-a",
                queryText = "TypeScript",
                scope = SearchScope.USER,
                targetUserId = "user-1",
            )
        assertEquals(1, userOnly.size)
        assertEquals(MemoryScope.USER, userOnly.first().memory.scope)

        val channelOnly =
            store.search(
                guildId = "guild-1",
                channelId = "chan-a",
                queryText = "TypeScript",
                scope = SearchScope.CHANNEL,
                targetUserId = "user-1",
            )
        assertEquals(1, channelOnly.size)
        assertEquals(MemoryScope.CHANNEL, channelOnly.first().memory.scope)

        val both =
            store.search(
                guildId = "guild-1",
                channelId = "chan-a",
                queryText = "TypeScript",
                scope = SearchScope.BOTH,
                targetUserId = "user-1",
            )
        assertEquals(2, both.size)
        assertNotNull(both.firstOrNull { it.memory.scope == MemoryScope.USER })
        assertNotNull(both.firstOrNull { it.memory.scope == MemoryScope.CHANNEL })
    }
}
