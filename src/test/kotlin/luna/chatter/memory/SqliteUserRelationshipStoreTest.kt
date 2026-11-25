package luna.chatter.memory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import luna.core.LunaHost
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteUserRelationshipStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId) = this

        override fun instant(): Instant = current

        fun advanceDays(days: Long) {
            current = current.plusSeconds(TimeUnit.DAYS.toSeconds(days))
        }
    }

    private fun createStoreWithDb(clock: Clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))): Pair<SqliteChatterMemoryStore, Database> {
        val dbPath = tempDir.resolve("relationship-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock) to db
    }

    @Test
    fun `migration creates chatter_relationships idempotently without vec0 or FTS5 indexes`() {
        val dbPath = tempDir.resolve("rel-migrate-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

        SqliteChatterMemoryStore(database = db, clock = clock)
        SqliteChatterMemoryStore(database = db, clock = clock)

        transaction(db) {
            val conn = TransactionManager.current().connection.connection as Connection
            val columns =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA table_info(chatter_relationships)").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString("name")) }
                    }
                }
            assertEquals(
                setOf("guild_id", "user_id", "affinity_score", "tone_style", "created_at", "updated_at", "last_interaction_at"),
                columns,
            )

            val virtualAndFtsTables =
                conn.createStatement().use { stmt ->
                    stmt
                        .executeQuery(
                            "SELECT name FROM sqlite_master WHERE name LIKE '%chatter_relationships%' AND (sql LIKE '%fts5%' OR sql LIKE '%vec0%')",
                        ).use { rs ->
                            buildList { while (rs.next()) add(rs.getString("name")) }
                        }
                }
            assertTrue(virtualAndFtsTables.isEmpty(), "chatter_relationships must have zero vec0 or FTS5 indexes")
        }
    }

    @Test
    fun `relationship CRUD enforces guild isolation, delta and score clamping, toneStyle bounding, and delete`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val (store, _) = createStoreWithDb(clock)

        // Default missing user returns NEUTRAL (score 0)
        val missing = store.getUserRelationship("g1", "u1")
        assertEquals(0, missing.affinityScore)
        assertEquals(AffinityTier.NEUTRAL, missing.tier)
        assertEquals("", missing.toneStyle)

        // Delta clamped to +15, toneStyle trimmed & bounded to 120 chars
        val oversizedTone = "  " + "x".repeat(150) + "  "
        val first = store.applyRelationshipUpdate("g1", "u1", affinityDelta = 50, toneStyle = oversizedTone)
        assertEquals(15, first.affinityScore)
        assertEquals(AffinityTier.WARM, first.tier)
        assertEquals(120, first.toneStyle.length)

        // Null toneStyle preserves existing toneStyle; score clamps at +100
        clock.advanceDays(1)
        repeat(7) {
            store.applyRelationshipUpdate("g1", "u1", affinityDelta = 15, toneStyle = null)
        }
        val maxed = store.getUserRelationship("g1", "u1")
        assertEquals(100, maxed.affinityScore)
        assertEquals(AffinityTier.FAVORITE, maxed.tier)
        assertEquals(120, maxed.toneStyle.length)

        // Guild isolation: same user in g2 is still NEUTRAL
        val otherGuild = store.getUserRelationship("g2", "u1")
        assertEquals(0, otherGuild.affinityScore)
        assertEquals(AffinityTier.NEUTRAL, otherGuild.tier)

        // Negative clamping to -100
        repeat(8) {
            store.applyRelationshipUpdate("g2", "u1", affinityDelta = -30, toneStyle = "curt")
        }
        val disliked = store.getUserRelationship("g2", "u1")
        assertEquals(-100, disliked.affinityScore)
        assertEquals(AffinityTier.DISLIKED, disliked.tier)
        assertEquals("curt", disliked.toneStyle)

        // Delete removes only the target guild+user row
        assertTrue(store.deleteUserRelationship("g2", "u1"), "Deleting existing relationship must return true")
        assertFalse(store.deleteUserRelationship("g2", "u1"), "Deleting already-removed relationship must return false")
        assertEquals(0, store.getUserRelationship("g2", "u1").affinityScore)
        assertEquals(100, store.getUserRelationship("g1", "u1").affinityScore)
    }

    @Test
    fun `NegativeAffinityDecay applies on read and update after 7-day cooldown while positive scores do not decay`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val (store, _) = createStoreWithDb(clock)

        // u1 becomes DISLIKED (-45) via 3 batches of -15
        repeat(3) {
            store.applyRelationshipUpdate("g1", "u1", affinityDelta = -15, toneStyle = "distant")
        }
        // u2 becomes FAVORITE (+60) via 4 batches of +15
        repeat(4) {
            store.applyRelationshipUpdate("g1", "u2", affinityDelta = 15, toneStyle = "playful")
        }

        // At 7 days: still in cooldown, no decay
        clock.advanceDays(7)
        assertEquals(-45, store.getUserRelationship("g1", "u1").affinityScore)
        assertEquals(60, store.getUserRelationship("g1", "u2").affinityScore)

        // At 21 days (14 days past cooldown = 2 steps = +10): u1 decays to -35 (COOL), u2 stays 60
        clock.advanceDays(14)
        val decayedU1 = store.getUserRelationship("g1", "u1")
        assertEquals(-35, decayedU1.affinityScore)
        assertEquals(AffinityTier.COOL, decayedU1.tier)
        assertEquals(60, store.getUserRelationship("g1", "u2").affinityScore)

        // Batch lookup reflects the same decayed values
        val batch = store.listUserRelationships("g1", listOf("u1", "u2", "u-missing"))
        assertEquals(-35, batch["u1"]?.affinityScore)
        assertEquals(60, batch["u2"]?.affinityScore)
        assertFalse(batch.containsKey("u-missing"), "Missing user must not appear in batch result")

        // Applying a new update builds on the decayed score (-35 + 5 = -30) and resets lastInteractionAt
        val updatedU1 = store.applyRelationshipUpdate("g1", "u1", affinityDelta = 5, toneStyle = null)
        assertEquals(-30, updatedU1.affinityScore)
        assertEquals(clock.instant().toEpochMilli(), updatedU1.lastInteractionAt)
    }

    @Test
    fun `getUserRelationship and listUserRelationships fail open to NEUTRAL with WARN log on SQLite error`() {
        val (store, db) = createStoreWithDb()

        // Break the table to trigger a real SQLite error on read
        transaction(db) {
            val conn = TransactionManager.current().connection.connection as Connection
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE chatter_relationships;")
            }
        }

        val storeLogger = LoggerFactory.getLogger(SqliteChatterMemoryStore::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        storeLogger.addAppender(appender)

        try {
            val single = store.getUserRelationship("g1", "u1")
            assertEquals(AffinityTier.NEUTRAL, single.tier)
            assertEquals(0, single.affinityScore)

            val batch = store.listUserRelationships("g1", listOf("u1", "u2"))
            assertTrue(batch.isEmpty(), "Batch lookup must fail open to empty map on SQLite error")

            val warnEvents = appender.list.filter { it.level == Level.WARN }
            assertTrue(warnEvents.size >= 2, "Expected WARN logs on SQLite errors, got ${warnEvents.size}")
        } finally {
            storeLogger.detachAppender(appender)
        }
    }
}
