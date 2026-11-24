package luna.chatter.memory

import luna.core.LunaHost
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteSocialUnderstandingStoreTest {
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
        maxUnderstandingsPerUser: Int = 15,
        maxUnderstandingsPerGuild: Int = 20,
    ): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("understanding-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(
            database = db,
            clock = clock,
            maxUnderstandingsPerUser = maxUnderstandingsPerUser,
            maxUnderstandingsPerGuild = maxUnderstandingsPerGuild,
        )
    }

    @Test
    fun `CRUD operations enforce guild isolation, user ownership, and input validation`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        assertFailsWith<IllegalArgumentException> {
            store.insertUnderstanding("g1", MemoryScope.USER, targetUserId = null, subject = "tone", content = "playful")
        }
        assertFailsWith<IllegalArgumentException> {
            store.insertUnderstanding("g1", MemoryScope.USER, targetUserId = "u1", subject = "  ", content = "playful")
        }
        assertFailsWith<IllegalArgumentException> {
            store.insertUnderstanding("g1", MemoryScope.GUILD, targetUserId = null, subject = "vibe", content = "   ")
        }

        val u1 =
            store.insertUnderstanding(
                guildId = "g1",
                scope = MemoryScope.USER,
                targetUserId = "u1",
                subject = "humor",
                content = "Uses dry sarcasm",
            )
        assertNull(store.insertUnderstanding("g1", MemoryScope.GUILD, targetUserId = "ignored", subject = "norm", content = "Late-night memes").targetUserId)
        store.insertUnderstanding("g2", MemoryScope.USER, targetUserId = "u1", subject = "humor", content = "Formal in guild 2")

        assertEquals(1, store.listUserUnderstandings("g1", "u1").size)
        assertEquals(1, store.listGuildUnderstandings("g1").size)
        assertEquals(1, store.listUserUnderstandings("g2", "u1").size)

        clock.advanceMinutes(10)
        assertTrue(store.updateUnderstanding(u1.id, "g1", subject = "humor", content = "Uses dry sarcasm and puns"), "Same-guild update must succeed")
        assertFalse(store.updateUnderstanding(u1.id, "g2", subject = "humor", content = "Cross-guild update must fail"), "Cross-guild update must fail")
        assertEquals("Uses dry sarcasm and puns", store.listUserUnderstandings("g1", "u1").single().content)

        // Ownership checks
        assertFalse(store.deleteOwnedUserUnderstanding(u1.id, "g2", "u1"), "Must reject cross-guild delete")
        assertFalse(store.deleteOwnedUserUnderstanding(u1.id, "g1", "u2"), "Must reject non-owner delete")
        assertTrue(store.deleteOwnedUserUnderstanding(u1.id, "g1", "u1"), "Owner delete must succeed")
        assertTrue(store.listUserUnderstandings("g1", "u1").isEmpty(), "Deleted understanding should no longer appear in list")
    }

    @Test
    fun `LRU eviction enforces maxUnderstandingsPerUser and maxUnderstandingsPerGuild using MAX(updated_at, last_accessed_at)`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock, maxUnderstandingsPerUser = 2, maxUnderstandingsPerGuild = 2)

        val first = store.insertUnderstanding("g1", MemoryScope.USER, "u1", "sub1", "First user impression")
        clock.advanceMinutes(10)
        val second = store.insertUnderstanding("g1", MemoryScope.USER, "u1", "sub2", "Second user impression")
        clock.advanceMinutes(10)

        // Touch 'first' so its last_accessed_at becomes newer than 'second'
        store.touchUnderstandings("g1", listOf(first.id))
        clock.advanceMinutes(5)

        val third = store.insertUnderstanding("g1", MemoryScope.USER, "u1", "sub3", "Third user impression")
        val remainingUserIds = store.listUserUnderstandings("g1", "u1").map { it.id }.toSet()
        assertEquals(setOf(first.id, third.id), remainingUserIds)
        assertFalse(remainingUserIds.contains(second.id), "Least recently accessed user understanding must be evicted")

        // Guild cap = 2, test reaffirmUnderstanding keeps item fresh
        val gFirst = store.insertUnderstanding("g1", MemoryScope.GUILD, null, "g1-s1", "Guild norm 1")
        clock.advanceMinutes(10)
        val gSecond = store.insertUnderstanding("g1", MemoryScope.GUILD, null, "g1-s2", "Guild norm 2")
        clock.advanceMinutes(10)
        assertTrue(store.reaffirmUnderstanding(gFirst.id, "g1"), "Reaffirm in owning guild must succeed")
        clock.advanceMinutes(5)
        val gThird = store.insertUnderstanding("g1", MemoryScope.GUILD, null, "g1-s3", "Guild norm 3")

        val remainingGuildIds = store.listGuildUnderstandings("g1").map { it.id }.toSet()
        assertEquals(setOf(gFirst.id, gThird.id), remainingGuildIds)
        assertFalse(remainingGuildIds.contains(gSecond.id), "Non-reaffirmed older guild understanding must be evicted")
    }

    @Test
    fun `applyUnderstandingMutations executes Add, Update, Reaffirm, Delete atomically and deleteAllOwnedUserUnderstandings clears user scope`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = createStore(clock)

        val existing = store.insertUnderstanding("g1", MemoryScope.USER, "u1", "style", "Concise")
        val toDelete = store.insertUnderstanding("g1", MemoryScope.USER, "u1", "old", "Stale")
        val toReaffirm = store.insertUnderstanding("g1", MemoryScope.GUILD, null, "banter", "Friendly teasing")
        clock.advanceMinutes(10)

        // Atomic rollback check
        assertFailsWith<IllegalArgumentException> {
            store.applyUnderstandingMutations(
                listOf(
                    UnderstandingMutation.Add("g1", MemoryScope.USER, "u1", "new", "Valid"),
                    UnderstandingMutation.Add("g1", MemoryScope.USER, "u1", "bad", "   "),
                ),
            )
        }
        assertEquals(2, store.listUserUnderstandings("g1", "u1").size, "Failed batch must roll back")

        // Valid batch
        store.applyUnderstandingMutations(
            listOf(
                UnderstandingMutation.Update(existing.id, "g1", "style", "Concise and technical"),
                UnderstandingMutation.Delete(toDelete.id, "g1"),
                UnderstandingMutation.Reaffirm(toReaffirm.id, "g1"),
                UnderstandingMutation.Add("g1", MemoryScope.USER, "u1", "schedule", "Night owl"),
            ),
        )
        val u1List = store.listUserUnderstandings("g1", "u1")
        assertEquals(2, u1List.size)
        assertTrue(u1List.any { it.id == existing.id && it.content == "Concise and technical" }, "Expected updated understanding in list")
        assertTrue(u1List.any { it.subject == "schedule" && it.content == "Night owl" }, "Expected added understanding in list")
        assertEquals(clock.instant().toEpochMilli(), store.listGuildUnderstandings("g1").single().updatedAt)

        // Clear owned user understandings in g1 without affecting guild understandings
        val cleared = store.deleteAllOwnedUserUnderstandings("g1", "u1")
        assertEquals(2, cleared)
        assertTrue(store.listUserUnderstandings("g1", "u1").isEmpty(), "All owned user understandings must be cleared")
        assertEquals(1, store.listGuildUnderstandings("g1").size)
    }
}
