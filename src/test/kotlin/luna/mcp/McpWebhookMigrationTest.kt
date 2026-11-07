package luna.mcp

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class McpWebhookMigrationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `migration succeeds when mcp_webhooks table already exists with legacy schema and rows`() {
        val dbFile = File(tempDir, "test_legacy.db")
        val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

        // Create legacy table and insert a row using raw JDBC
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE mcp_webhooks (
                        id VARCHAR(64) NOT NULL PRIMARY KEY,
                        guild_id VARCHAR(32) NOT NULL,
                        channel_id VARCHAR(32) NOT NULL,
                        webhook_url TEXT NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO mcp_webhooks (id, guild_id, channel_id, webhook_url, created_at)
                    VALUES ('existing-id-1', 'guild-1', 'channel-1', 'https://discord.com/api/webhooks/123/token-abc', 1234567890)
                    """.trimIndent(),
                )
            }
        }

        // Now initialize McpWebhookRepository on this existing SQLite database
        val repo = McpWebhookRepository.create(dbFile.absolutePath)
        val all = repo.getAllWebhooks()
        assertEquals(1, all.size)
        val existing = all.first()
        assertEquals("existing-id-1", existing.id)
        assertEquals("luna-mcp-client", existing.clientId)
        assertEquals("default", existing.tag)
    }
}
