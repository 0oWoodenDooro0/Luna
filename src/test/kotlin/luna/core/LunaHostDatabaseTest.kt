package luna.core

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LunaHostDatabaseTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `initDatabase configures memory-bounded SQLite PRAGMAs and fails open when vec0 is missing`() {
        val dbPath = tempDir.resolve("test-luna.db").toString()
        val missingVecPath = tempDir.resolve("missing-vec0.so").toString()

        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = missingVecPath)

        transaction(db) {
            exec("PRAGMA journal_mode;") { rs ->
                rs.next()
                assertEquals("wal", rs.getString(1).lowercase())
            }
            exec("PRAGMA busy_timeout;") { rs ->
                rs.next()
                assertEquals(5000, rs.getInt(1))
            }
            exec("PRAGMA cache_size;") { rs ->
                rs.next()
                assertEquals(-8000, rs.getInt(1))
            }
            exec("PRAGMA mmap_size;") { rs ->
                rs.next()
                assertEquals(0L, rs.getLong(1))
            }
        }
    }
}
