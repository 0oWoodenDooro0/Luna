package luna.chatter.memory

import luna.core.LunaHost
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteChatterMemoryStoreExpirationTest {
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

        fun advanceDays(days: Long) {
            current = current.plusSeconds(days * 86_400)
        }
    }

    private fun createStoreWithDb(clock: Clock): Pair<SqliteChatterMemoryStore, Database> {
        val dbPath = tempDir.resolve("expiration-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock) to db
    }

    private fun createStore(clock: Clock): SqliteChatterMemoryStore = createStoreWithDb(clock).first

    @Test
    fun `search and listUserMemories exclude expired rows immediately while keeping durable and unexpired rows`() {
        val clock = MutableClock(Instant.parse("2026-11-20T00:00:00Z"))
        val store = createStore(clock)

        val nowMs = clock.millis()
        val oneDayMs = 86_400_000L

        val durable =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "Alice likes matcha latte.",
                expiresAt = null,
            )
        val expiringSoon =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "Alice has a matcha tasting tomorrow.",
                expiresAt = nowMs + oneDayMs,
            )
        val expiringLater =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.GUILD,
                targetUserId = null,
                content = "Guild matcha festival next week.",
                expiresAt = nowMs + 7 * oneDayMs,
            )

        clock.advanceMinutes(15)
        val beforeExpiryHits =
            store.search(
                guildId = "guild-1",
                queryText = "matcha",
                scope = SearchScope.BOTH,
                targetUserId = "user-1",
            )
        assertEquals(3, beforeExpiryHits.size)
        assertEquals(2, store.listUserMemories("guild-1", "user-1").size)

        // Advance to exact expiration boundary (expires_at <= now -> expired)
        clock.advanceDays(1)
        val afterExpiryHits =
            store.search(
                guildId = "guild-1",
                queryText = "matcha",
                scope = SearchScope.BOTH,
                targetUserId = "user-1",
            )
        assertEquals(setOf(durable.id, expiringLater.id), afterExpiryHits.map { it.memory.id }.toSet())
        assertFalse(afterExpiryHits.any { it.memory.id == expiringSoon.id }, "Expired memory must be excluded from search")

        val listedAfterExpiry = store.listUserMemories("guild-1", "user-1")
        assertEquals(listOf(durable.id), listedAfterExpiry.map { it.id })
        assertTrue(listedAfterExpiry.none { it.id == expiringSoon.id }, "Expired memory must be excluded from listUserMemories")
    }

    @Test
    fun `purgeExpiredMemories hard-deletes expired rows from base table and FTS5 while keeping durable and unexpired rows`() {
        val clock = MutableClock(Instant.parse("2026-11-20T00:00:00Z"))
        val (store, db) = createStoreWithDb(clock)
        val nowMs = clock.millis()
        val oneDayMs = 86_400_000L

        val durable =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "Alice is vegetarian.",
                expiresAt = null,
            )
        val expired1 =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "Alice has a dentist appointment tomorrow.",
                expiresAt = nowMs + oneDayMs,
            )
        val expired2 =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.GUILD,
                targetUserId = null,
                content = "Guild movie night is tonight.",
                expiresAt = nowMs + oneDayMs,
            )
        val unexpired =
            store.insertMemory(
                guildId = "guild-1",
                scope = MemoryScope.USER,
                targetUserId = "user-1",
                content = "Alice is visiting Kyoto next month.",
                expiresAt = nowMs + 30 * oneDayMs,
            )

        assertEquals(0, store.purgeExpiredMemories(nowMs))

        val purgeAtMs = nowMs + oneDayMs
        val deletedCount = store.purgeExpiredMemories(purgeAtMs)
        assertEquals(2, deletedCount)

        val remainingIds =
            transaction(db) {
                val conn = TransactionManager.current().connection.connection as Connection
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT id FROM chatter_memories ORDER BY id").use { rs ->
                        buildList {
                            while (rs.next()) add(rs.getLong(1))
                        }
                    }
                }
            }
        assertEquals(listOf(durable.id, unexpired.id), remainingIds)

        val ftsCount =
            transaction(db) {
                val conn = TransactionManager.current().connection.connection as Connection
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM chatter_memories_fts").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        assertEquals(2, ftsCount, "FTS5 index must stay synced after purge")
        assertFalse(remainingIds.contains(expired1.id), "Expired user memory must be hard-deleted")
        assertFalse(remainingIds.contains(expired2.id), "Expired guild memory must be hard-deleted")
    }
}
