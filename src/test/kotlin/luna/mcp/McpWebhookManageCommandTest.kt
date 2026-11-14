package luna.mcp

import dev.kord.rest.request.Request
import dev.kord.rest.route.Route
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import luna.mcp.data.McpWebhookRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpWebhookManageCommandTest {
    private lateinit var support: McpWebhookTestSupport

    @BeforeEach
    fun setUp() {
        support = McpWebhookTestSupport()
        support.setUp()
    }

    @Test
    fun `list prompts user to add webhooks when none are registered`() =
        runTest {
            support.registerTestClient()

            val (interaction, getResponseContent) =
                support.setupInteraction(subcommandName = "list")

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("/mcp webhook add"))
        }

    @Test
    fun `list displays all registered tags and quota status`() =
        runTest {
            support.registerTestClient()

            val now = System.currentTimeMillis()
            support.webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-1",
                    clientId = support.testClientId,
                    tag = "prod-alerts",
                    guildId = support.guildIdSnowflake.toString(),
                    channelId = "999888777",
                    webhookUrl = "https://discord.com/api/webhooks/1/token1",
                    isAutoProvisioned = true,
                    createdAt = now,
                ),
            )
            support.webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-2",
                    clientId = support.testClientId,
                    tag = "dev-logs",
                    guildId = support.guildIdSnowflake.toString(),
                    channelId = "666555444",
                    webhookUrl = "https://discord.com/api/webhooks/2/token2",
                    isAutoProvisioned = false,
                    createdAt = now + 1000,
                ),
            )

            val (interaction, getResponseContent) =
                support.setupInteraction(subcommandName = "list")

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)

            // Tags
            assertTrue(content.contains("prod-alerts"))
            assertTrue(content.contains("dev-logs"))

            // Channel ID for auto-provisioned
            assertTrue(content.contains("999888777"))

            // URLs
            assertTrue(content.contains("https://discord.com/api/webhooks/1/token1"))
            assertTrue(content.contains("https://discord.com/api/webhooks/2/token2"))

            // Quota
            assertTrue(content.contains("2/10"))
        }

    @Test
    fun `list rejects interaction when user has not created client ID`() =
        runTest {
            val (interaction, getResponseContent) =
                support.setupInteraction(subcommandName = "list")

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("您尚未建立 Client ID"))
            assertTrue(content.contains("/mcp client create"))
        }

    @Test
    fun `interaction with unknown subcommand responds with guidance message`() =
        runTest {
            val (interaction, getResponseContent) =
                support.setupInteraction(subcommandName = "unknown")

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("/mcp webhook add") || content.contains("/mcp webhook list"))
        }

    @Test
    fun `remove with auto-provisioned webhook deletes via kord rest and removes from database`() =
        runTest {
            support.registerTestClient()

            val now = System.currentTimeMillis()
            support.webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-remove-auto",
                    clientId = support.testClientId,
                    tag = "alerts",
                    guildId = support.guildIdSnowflake.toString(),
                    channelId = support.channelIdSnowflake.toString(),
                    webhookUrl = "https://discord.com/api/webhooks/777888999/auto-token-xyz",
                    isAutoProvisioned = true,
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "alerts"),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已成功移除標籤為 `alerts` 的 Webhook"))

            coVerify(exactly = 1) {
                support.mockRequestHandler.handle(
                    match<Request<*, *>> {
                        it.route == Route.WebhookByTokenDelete
                    },
                )
            }

            val stored = support.webhookRepo.findByClientAndTag(support.testClientId, "alerts")
            assertEquals(null, stored)
        }

    @Test
    fun `remove with custom url only deletes from database without kord webhook deletion call`() =
        runTest {
            support.registerTestClient()

            val now = System.currentTimeMillis()
            val customUrl = "https://discord.com/api/webhooks/123456789/custom-token"
            support.webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-remove-custom",
                    clientId = support.testClientId,
                    tag = "custom_alerts",
                    guildId = support.guildIdSnowflake.toString(),
                    channelId = support.channelIdSnowflake.toString(),
                    webhookUrl = customUrl,
                    isAutoProvisioned = false,
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "custom_alerts"),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已成功移除標籤為 `custom_alerts` 的 Webhook"))

            coVerify(exactly = 0) {
                support.mockRequestHandler.handle(
                    match<Request<*, *>> {
                        it.route == Route.WebhookByTokenDelete
                    },
                )
            }

            val stored = support.webhookRepo.findByClientAndTag(support.testClientId, "custom_alerts")
            assertEquals(null, stored)
        }

    @Test
    fun `remove when discord rest throws exception logs degraded warning and still removes from database`() =
        runTest {
            support.registerTestClient()

            val now = System.currentTimeMillis()
            support.webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-remove-err",
                    clientId = support.testClientId,
                    tag = "failing_wh",
                    guildId = support.guildIdSnowflake.toString(),
                    channelId = support.channelIdSnowflake.toString(),
                    webhookUrl = "https://discord.com/api/webhooks/777888999/auto-token-xyz",
                    isAutoProvisioned = true,
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "failing_wh"),
                )

            coEvery {
                support.mockRequestHandler.handle(
                    match<Request<*, *>> {
                        it.route == Route.WebhookByTokenDelete
                    },
                )
            } throws RuntimeException("Discord 404 Not Found")

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已成功移除標籤為 `failing_wh` 的 Webhook"))

            val stored = support.webhookRepo.findByClientAndTag(support.testClientId, "failing_wh")
            assertEquals(null, stored)
        }

    @Test
    fun `remove with nonexistent tag returns error message`() =
        runTest {
            support.registerTestClient()

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "nonexistent_tag"),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("找不到標籤為 `nonexistent_tag` 的 Webhook"))
        }

    @Test
    fun `remove rejects interaction when user has not created client ID`() =
        runTest {
            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "any_tag"),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("您尚未建立 Client ID"))
            assertTrue(content.contains("/mcp client create"))
        }
}
