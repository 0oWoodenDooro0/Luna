package luna.chatter.emoji

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposedEmojiUsageRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun openDatabase(): Database {
        val dbFile = tempDir.resolve("emoji-usage-test.db").toFile()
        return Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
    }

    private fun fixedClock(isoInstant: String): Clock = Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC)

    @Test
    fun `records daily emoji usage counts per guild and aggregates within 30-day rolling window`() {
        val db = openDatabase()
        val repoDay1 = ExposedEmojiUsageRankingRepository(database = db, clock = fixedClock("2026-11-01T10:00:00Z"))
        repoDay1.recordUsage(guildId = "guild-1", emojiIds = listOf("1001", "1001", "1002"))
        repoDay1.recordUsage(guildId = "guild-2", emojiIds = listOf("1002", "1003"))

        val repoDay10 = ExposedEmojiUsageRankingRepository(database = db, clock = fixedClock("2026-11-10T12:00:00Z"))
        repoDay10.recordUsage(guildId = "guild-1", emojiIds = listOf("1001", "1003"))

        val guild1Counts = repoDay10.findRollingUsageCounts("guild-1")
        assertEquals(mapOf("1001" to 3L, "1002" to 1L, "1003" to 1L), guild1Counts)

        val guild2Counts = repoDay10.findRollingUsageCounts("guild-2")
        assertEquals(mapOf("1002" to 1L, "1003" to 1L), guild2Counts)
    }

    @Test
    fun `excludes and prunes expired usage rows when clock advances past 30-day rolling window`() {
        val db = openDatabase()
        val repoDay1 = ExposedEmojiUsageRankingRepository(database = db, clock = fixedClock("2026-10-01T10:00:00Z"))
        repoDay1.recordUsage(guildId = "guild-1", emojiIds = listOf("1001", "1001"))

        val repoDay20 = ExposedEmojiUsageRankingRepository(database = db, clock = fixedClock("2026-10-20T10:00:00Z"))
        repoDay20.recordUsage(guildId = "guild-1", emojiIds = listOf("1002"))

        // Day 30 (Oct 30): Oct 1 is the 30th day inclusive, still included
        val repoDay30 = ExposedEmojiUsageRankingRepository(database = db, clock = fixedClock("2026-10-30T10:00:00Z"))
        assertEquals(mapOf("1001" to 2L, "1002" to 1L), repoDay30.findRollingUsageCounts("guild-1"))

        // Day 31 (Oct 31): Oct 1 is now outside the 30-day window [Oct 2..Oct 31] and must be pruned
        val repoDay31 = ExposedEmojiUsageRankingRepository(database = db, clock = fixedClock("2026-10-31T10:00:00Z"))
        assertEquals(mapOf("1002" to 1L), repoDay31.findRollingUsageCounts("guild-1"))

        val remainingDays =
            transaction(db) {
                EmojiUsageDailyTable.selectAll().map { it[EmojiUsageDailyTable.emojiId] }
            }
        assertEquals(listOf("1002"), remainingDays)
        assertTrue(
            remainingDays.none { it == "1001" },
            "Expected expired usage row for emoji 1001 to be pruned from SQLite",
        )
    }
}
