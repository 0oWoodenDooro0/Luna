package luna.mcp.server

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpWebhookRepositoryTest {
    private lateinit var database: Database
    private lateinit var repository: McpWebhookRepository

    @BeforeTest
    fun setUp() {
        val dbName = "test_mcp_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        repository = McpWebhookRepository(database)
    }

    @AfterTest
    fun tearDown() {
        // Cleaned up with in-memory DB close
    }

    @Test
    fun `returns empty list when no webhooks are registered`() {
        val webhooks = repository.getAllWebhooks()
        assertTrue(webhooks.isEmpty())
    }

    @Test
    fun `returns all active webhooks sorted by creation time`() {
        val now = System.currentTimeMillis()
        transaction(database) {
            McpWebhooksTable.insert {
                it[id] = "wh-1"
                it[guildId] = "g-1"
                it[channelId] = "c-1"
                it[webhookUrl] = "https://discord.com/api/webhooks/111/aaa"
                it[createdAt] = now
            }
            McpWebhooksTable.insert {
                it[id] = "wh-2"
                it[guildId] = "g-2"
                it[channelId] = "c-2"
                it[webhookUrl] = "https://discord.com/api/webhooks/222/bbb"
                it[createdAt] = now + 1000
            }
        }

        val webhooks = repository.getAllWebhooks()
        assertEquals(2, webhooks.size)
        assertEquals("https://discord.com/api/webhooks/111/aaa", webhooks[0].webhookUrl)
        assertEquals("https://discord.com/api/webhooks/222/bbb", webhooks[1].webhookUrl)
    }
}
