package luna.mcp.server

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-1",
                clientId = "client-1",
                tag = "tag-1",
                guildId = "g-1",
                channelId = "c-1",
                webhookUrl = "https://discord.com/api/webhooks/111/aaa",
                isAutoProvisioned = true,
                createdAt = now,
            ),
        )
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-2",
                clientId = "client-2",
                tag = "tag-2",
                guildId = "g-2",
                channelId = "c-2",
                webhookUrl = "https://discord.com/api/webhooks/222/bbb",
                isAutoProvisioned = false,
                createdAt = now + 1000,
            ),
        )

        val webhooks = repository.getAllWebhooks()
        assertEquals(2, webhooks.size)
        assertEquals("https://discord.com/api/webhooks/111/aaa", webhooks[0].webhookUrl)
        assertEquals("https://discord.com/api/webhooks/222/bbb", webhooks[1].webhookUrl)
    }

    @Test
    fun `addWebhook inserts record and findByClientAndTag retrieves it`() {
        val now = System.currentTimeMillis()
        val record =
            McpWebhookRecord(
                id = "wh-100",
                clientId = "client-alpha",
                tag = "general",
                guildId = "g-100",
                channelId = "c-100",
                webhookUrl = "https://discord.com/api/webhooks/100/token",
                isAutoProvisioned = true,
                createdAt = now,
            )

        repository.addWebhook(record)

        val found = repository.findByClientAndTag("client-alpha", "general")
        assertNotNull(found)
        assertEquals("wh-100", found.id)
        assertEquals("client-alpha", found.clientId)
        assertEquals("general", found.tag)
        assertEquals("g-100", found.guildId)
        assertEquals("c-100", found.channelId)
        assertEquals("https://discord.com/api/webhooks/100/token", found.webhookUrl)
        assertTrue(found.isAutoProvisioned)
        assertEquals(now, found.createdAt)
    }

    @Test
    fun `findByClientAndTag returns null for non-matching tag or client`() {
        val now = System.currentTimeMillis()
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-101",
                clientId = "client-alpha",
                tag = "announcements",
                guildId = "g-100",
                channelId = "c-100",
                webhookUrl = "https://discord.com/api/webhooks/101/token",
                isAutoProvisioned = false,
                createdAt = now,
            ),
        )

        assertNull(repository.findByClientAndTag("client-alpha", "unknown-tag"))
        assertNull(repository.findByClientAndTag("wrong-client", "announcements"))
    }

    @Test
    fun `listByClientId returns only webhooks for specified client sorted by createdAt`() {
        val now = System.currentTimeMillis()
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-a1",
                clientId = "client-A",
                tag = "tag-2",
                guildId = "g-1",
                channelId = "c-1",
                webhookUrl = "https://discord.com/api/webhooks/a1/url",
                isAutoProvisioned = true,
                createdAt = now + 100,
            ),
        )
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-a2",
                clientId = "client-A",
                tag = "tag-1",
                guildId = "g-1",
                channelId = "c-2",
                webhookUrl = "https://discord.com/api/webhooks/a2/url",
                isAutoProvisioned = false,
                createdAt = now,
            ),
        )
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-b1",
                clientId = "client-B",
                tag = "tag-1",
                guildId = "g-2",
                channelId = "c-3",
                webhookUrl = "https://discord.com/api/webhooks/b1/url",
                isAutoProvisioned = true,
                createdAt = now + 50,
            ),
        )

        val listA = repository.listByClientId("client-A")
        assertEquals(2, listA.size)
        assertEquals("wh-a2", listA[0].id)
        assertEquals("wh-a1", listA[1].id)

        val listB = repository.listByClientId("client-B")
        assertEquals(1, listB.size)
        assertEquals("wh-b1", listB[0].id)

        val listC = repository.listByClientId("client-C")
        assertTrue(listC.isEmpty())
    }

    @Test
    fun `inserting duplicate tag for same client_id throws unique constraint violation`() {
        val now = System.currentTimeMillis()
        val record1 =
            McpWebhookRecord(
                id = "wh-dup-1",
                clientId = "client-dup",
                tag = "alerts",
                guildId = "g-1",
                channelId = "c-1",
                webhookUrl = "https://discord.com/api/webhooks/dup1/url",
                isAutoProvisioned = true,
                createdAt = now,
            )
        val record2 =
            McpWebhookRecord(
                id = "wh-dup-2",
                clientId = "client-dup",
                tag = "alerts",
                guildId = "g-1",
                channelId = "c-2",
                webhookUrl = "https://discord.com/api/webhooks/dup2/url",
                isAutoProvisioned = false,
                createdAt = now + 100,
            )

        repository.addWebhook(record1)

        assertThrows<Exception> {
            repository.addWebhook(record2)
        }
    }

    @Test
    fun `different clients can register the same tag without constraint violation`() {
        val now = System.currentTimeMillis()
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-user1",
                clientId = "client-1",
                tag = "general",
                guildId = "g-1",
                channelId = "c-1",
                webhookUrl = "https://discord.com/api/webhooks/u1/url",
                isAutoProvisioned = true,
                createdAt = now,
            ),
        )
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-user2",
                clientId = "client-2",
                tag = "general",
                guildId = "g-2",
                channelId = "c-2",
                webhookUrl = "https://discord.com/api/webhooks/u2/url",
                isAutoProvisioned = true,
                createdAt = now + 10,
            ),
        )

        assertNotNull(repository.findByClientAndTag("client-1", "general"))
        assertNotNull(repository.findByClientAndTag("client-2", "general"))
    }

    @Test
    fun `removeByTag removes specific webhook for client and returns true`() {
        val now = System.currentTimeMillis()
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-rem-1",
                clientId = "client-rem",
                tag = "tag-to-delete",
                guildId = "g-1",
                channelId = "c-1",
                webhookUrl = "https://discord.com/api/webhooks/rem1/url",
                isAutoProvisioned = true,
                createdAt = now,
            ),
        )

        val removed = repository.removeByTag("client-rem", "tag-to-delete")
        assertTrue(removed)
        assertNull(repository.findByClientAndTag("client-rem", "tag-to-delete"))
    }

    @Test
    fun `removeByTag returns false when tag or client does not match`() {
        val removed = repository.removeByTag("non-existent-client", "tag")
        assertFalse(removed)
    }

    @Test
    fun `deleteByClientId removes all webhooks for client and returns count`() {
        val now = System.currentTimeMillis()
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-del-1",
                clientId = "client-bulk-del",
                tag = "tag-1",
                guildId = "g-1",
                channelId = "c-1",
                webhookUrl = "https://discord.com/api/webhooks/d1/url",
                isAutoProvisioned = true,
                createdAt = now,
            ),
        )
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-del-2",
                clientId = "client-bulk-del",
                tag = "tag-2",
                guildId = "g-1",
                channelId = "c-2",
                webhookUrl = "https://discord.com/api/webhooks/d2/url",
                isAutoProvisioned = false,
                createdAt = now + 10,
            ),
        )
        repository.addWebhook(
            McpWebhookRecord(
                id = "wh-other",
                clientId = "client-other",
                tag = "tag-1",
                guildId = "g-2",
                channelId = "c-3",
                webhookUrl = "https://discord.com/api/webhooks/other/url",
                isAutoProvisioned = true,
                createdAt = now + 20,
            ),
        )

        val count = repository.deleteByClientId("client-bulk-del")
        assertEquals(2, count)
        assertTrue(repository.listByClientId("client-bulk-del").isEmpty())
        assertEquals(1, repository.listByClientId("client-other").size)
    }
}
