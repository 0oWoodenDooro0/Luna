package luna.chatter.emoji

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExposedCustomEmojiMeaningRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun openDatabase(): Database {
        val dbFile = tempDir.resolve("emoji-meaning-test.db").toFile()
        return Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
    }

    private fun fixedClock(epochMs: Long): Clock = Clock.fixed(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)

    @Test
    fun `repository initializes table, persists records globally by emojiId, and survives re-instantiation`() {
        val db = openDatabase()
        val repo1 = ExposedCustomEmojiMeaningRepository(database = db, clock = fixedClock(1_000_000L))

        repo1.upsertResolvedMeaning(
            emojiId = "1001",
            name = "blob_clap",
            isAnimated = false,
            meaning = "開心拍手",
        )
        repo1.upsertResolvedMeaning(
            emojiId = "1002",
            name = "party_parrot",
            isAnimated = true,
            meaning = "興奮搖擺",
        )

        val repo2 = ExposedCustomEmojiMeaningRepository(database = openDatabase(), clock = fixedClock(2_000_000L))
        val records = repo2.findByIds(listOf("1001", "1002", "9999"))

        assertEquals(2, records.size, "Expected 2 persisted records after re-instantiation")

        val clap = records["1001"]
        assertNotNull(clap, "Expected record for emojiId 1001")
        assertEquals("blob_clap", clap.name)
        assertFalse(clap.isAnimated, "Expected isAnimated=false for static emoji")
        assertEquals("開心拍手", clap.meaning)
        assertFalse(clap.isManualOverride, "Expected isManualOverride to default to false")
        assertEquals(1_000_000L, clap.updatedAt)
        assertNull(clap.failedUntil)

        val parrot = records["1002"]
        assertNotNull(parrot, "Expected record for emojiId 1002")
        assertTrue(parrot.isAnimated, "Expected isAnimated=true for animated emoji")
        assertEquals("興奮搖擺", parrot.meaning)
        assertFalse(parrot.isManualOverride, "Expected isManualOverride to default to false")
    }

    @Test
    fun `repository preserves isManualOverride rows and updates rename metadata without clearing meaning`() {
        val db = openDatabase()
        val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = fixedClock(5_000L))

        transaction(db) {
            CustomEmojiMeaningsTable.insert {
                it[emojiId] = "2001"
                it[name] = "old_name"
                it[isAnimated] = false
                it[meaning] = "管理員自訂意思"
                it[isManualOverride] = true
                it[updatedAt] = 1_000L
                it[failedUntil] = null
            }
        }

        repo.upsertResolvedMeaning(
            emojiId = "2001",
            name = "new_name",
            isAnimated = false,
            meaning = "自動覆寫測試",
        )

        val manual = repo.findById("2001")
        assertNotNull(manual, "Expected manual override record to exist")
        assertEquals("管理員自訂意思", manual.meaning, "Manual override meaning must never be overwritten")
        assertTrue(manual.isManualOverride, "isManualOverride flag must remain true")
        assertEquals("new_name", manual.name, "Observed name metadata should still update")

        repo.upsertResolvedMeaning(
            emojiId = "2002",
            name = "before_rename",
            isAnimated = false,
            meaning = "無奈攤手",
        )
        repo.updateMetadataIfChanged(
            emojiId = "2002",
            name = "after_rename",
            isAnimated = false,
        )

        val renamed = repo.findById("2002")
        assertNotNull(renamed, "Expected renamed record to exist")
        assertEquals("after_rename", renamed.name)
        assertEquals("無奈攤手", renamed.meaning, "Rename metadata update must preserve cached meaning")
    }

    @Test
    fun `repository handles empty id queries and records failure cooldowns safely`() {
        val db = openDatabase()
        val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = fixedClock(10_000L))

        assertTrue(repo.findByIds(emptyList()).isEmpty(), "Empty query should return empty map")
        assertTrue(repo.findMeaningMap(emptyList()).isEmpty(), "Empty meaning query should return empty map")

        repo.recordFailureCooldown(
            emojiId = "3001",
            name = "broken_gif",
            isAnimated = true,
            failedUntilEpochMs = 3_610_000L,
        )

        val failed = repo.findById("3001")
        assertNotNull(failed, "Expected cooldown record to be stored")
        assertNull(failed.meaning, "Failed cooldown record should have null meaning")
        assertEquals(3_610_000L, failed.failedUntil)
        assertTrue(repo.findMeaningMap(listOf("3001")).isEmpty(), "Null meaning must not appear in findMeaningMap")
    }
}
