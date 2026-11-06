package luna.mcp

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpWebhookStorageTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:mcp_storage_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    @Test
    fun `storage initializes mcp_webhooks table and allows adding webhooks`() {
        val db = createTestDb()
        val storage = McpWebhookStorage(db)

        val webhook =
            storage.addWebhook(
                guildId = "guild-1",
                channelId = "channel-1",
                webhookUrl = "https://discord.com/api/webhooks/123/token-abc",
            )

        assertNotNull(webhook.id)
        assertEquals("guild-1", webhook.guildId)
        assertEquals("channel-1", webhook.channelId)
        assertEquals("https://discord.com/api/webhooks/123/token-abc", webhook.webhookUrl)
        assertTrue(webhook.createdAt > 0)

        // Verify row exists directly in McpWebhooksTable
        transaction(db) {
            val rows = McpWebhooksTable.selectAll().toList()
            assertEquals(1, rows.size)
            assertEquals("guild-1", rows.first()[McpWebhooksTable.guildId])
            assertEquals("channel-1", rows.first()[McpWebhooksTable.channelId])
            assertEquals("https://discord.com/api/webhooks/123/token-abc", rows.first()[McpWebhooksTable.webhookUrl])
        }
    }

    @Test
    fun `getWebhooksByChannel returns webhooks scoped to guild and channel`() {
        val db = createTestDb()
        val storage = McpWebhookStorage(db)

        storage.addWebhook("guild-1", "channel-1", "https://discord.com/api/webhooks/101/token-1")
        storage.addWebhook("guild-1", "channel-1", "https://discord.com/api/webhooks/102/token-2")
        storage.addWebhook("guild-1", "channel-2", "https://discord.com/api/webhooks/103/token-3")
        storage.addWebhook("guild-2", "channel-1", "https://discord.com/api/webhooks/104/token-4")

        val channel1Webhooks = storage.getWebhooksByChannel("guild-1", "channel-1")
        assertEquals(2, channel1Webhooks.size)
        assertTrue(channel1Webhooks.any { it.webhookUrl == "https://discord.com/api/webhooks/101/token-1" })
        assertTrue(channel1Webhooks.any { it.webhookUrl == "https://discord.com/api/webhooks/102/token-2" })

        val channel2Webhooks = storage.getWebhooksByChannel("guild-1", "channel-2")
        assertEquals(1, channel2Webhooks.size)
        assertEquals("https://discord.com/api/webhooks/103/token-3", channel2Webhooks.first().webhookUrl)

        val guild2Webhooks = storage.getWebhooksByChannel("guild-2", "channel-1")
        assertEquals(1, guild2Webhooks.size)
        assertEquals("https://discord.com/api/webhooks/104/token-4", guild2Webhooks.first().webhookUrl)

        val emptyWebhooks = storage.getWebhooksByChannel("guild-1", "non-existent")
        assertTrue(emptyWebhooks.isEmpty())
    }

    @Test
    fun `getAllWebhooks returns all recorded webhooks across all guilds and channels`() {
        val db = createTestDb()
        val storage = McpWebhookStorage(db)

        storage.addWebhook("guild-1", "channel-1", "https://discord.com/api/webhooks/101/token-1")
        storage.addWebhook("guild-2", "channel-2", "https://discord.com/api/webhooks/102/token-2")

        val all = storage.getAllWebhooks()
        assertEquals(2, all.size)
    }

    @Test
    fun `removeWebhook with specific url removes only matching webhook`() {
        val db = createTestDb()
        val storage = McpWebhookStorage(db)

        val url1 = "https://discord.com/api/webhooks/101/token-1"
        val url2 = "https://discord.com/api/webhooks/102/token-2"
        storage.addWebhook("guild-1", "channel-1", url1)
        storage.addWebhook("guild-1", "channel-1", url2)

        val removed = storage.removeWebhook("guild-1", "channel-1", url1)
        assertTrue(removed)

        val remaining = storage.getWebhooksByChannel("guild-1", "channel-1")
        assertEquals(1, remaining.size)
        assertEquals(url2, remaining.first().webhookUrl)

        val removeNonExistent = storage.removeWebhook("guild-1", "channel-1", "https://discord.com/api/webhooks/999/token-x")
        assertFalse(removeNonExistent)
    }

    @Test
    fun `removeWebhook with null url removes all webhooks for channel`() {
        val db = createTestDb()
        val storage = McpWebhookStorage(db)

        storage.addWebhook("guild-1", "channel-1", "https://discord.com/api/webhooks/101/token-1")
        storage.addWebhook("guild-1", "channel-1", "https://discord.com/api/webhooks/102/token-2")
        storage.addWebhook("guild-1", "channel-2", "https://discord.com/api/webhooks/103/token-3")

        val removed = storage.removeWebhook("guild-1", "channel-1", null)
        assertTrue(removed)

        val channel1Webhooks = storage.getWebhooksByChannel("guild-1", "channel-1")
        assertTrue(channel1Webhooks.isEmpty())

        val channel2Webhooks = storage.getWebhooksByChannel("guild-1", "channel-2")
        assertEquals(1, channel2Webhooks.size)

        val removedAgain = storage.removeWebhook("guild-1", "channel-1", null)
        assertFalse(removedAgain)
    }
}
