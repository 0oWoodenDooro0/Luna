package luna.mcp

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.Optional
import dev.kord.rest.request.Request
import dev.kord.rest.route.Route
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.mcp.data.McpWebhookRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpWebhookCommandTest {
    private lateinit var support: McpWebhookTestSupport

    @BeforeEach
    fun setUp() {
        support = McpWebhookTestSupport()
        support.setUp()
    }

    @Test
    fun `add rejects interaction when user has not created client ID`() =
        runTest {
            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "alerts"),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("您尚未建立 Client ID"))
            assertTrue(content.contains("/mcp client create"))
            assertTrue(support.webhookRepo.listByClientId(support.testClientId).isEmpty())
        }

    @Test
    fun `add validates valid alphanumeric and Chinese tags`() =
        runTest {
            support.registerTestClient()

            val validTags = listOf("alerts", "ops_log-1", "中文標籤", "A1_b2-c3")
            for ((index, tag) in validTags.withIndex()) {
                val (interaction, getResponseContent) =
                    support.setupInteraction(
                        subcommandName = "add",
                        options = mapOf("tag" to tag),
                        mockCreatedWebhook =
                            mockk(relaxed = true) {
                                every { id } returns Snowflake(1000L + index)
                                every { token } returns Optional.Value("token-$index")
                            },
                    )

                support.command.handle(interaction)

                val content = getResponseContent()
                assertNotNull(content)
                assertTrue(content.contains("成功") || content.contains("綁定"))
                val stored = support.webhookRepo.findByClientAndTag(support.testClientId, tag)
                assertNotNull(stored, "Expected tag $tag to be persisted")
            }
        }

    @Test
    fun `add rejects invalid tags with spaces or special characters or empty`() =
        runTest {
            support.registerTestClient()

            val invalidTags =
                listOf(
                    "",
                    "tag with space",
                    "tag!alert",
                    "tag@symbol",
                    "tag#channel",
                    "tag\$money",
                    "tag%percent",
                    "tag^carets",
                    "tag&and",
                    "tag*star",
                    "tag(parenthesis)",
                    "a".repeat(33), // exceeds 32 chars
                )

            for (invalidTag in invalidTags) {
                val (interaction, getResponseContent) =
                    support.setupInteraction(
                        subcommandName = "add",
                        options = mapOf("tag" to invalidTag),
                    )

                support.command.handle(interaction)

                val content = getResponseContent()
                assertNotNull(content, "Expected error response for tag: '$invalidTag'")
                assertTrue(
                    content.contains("標籤格式不正確"),
                    "Expected tag error message for '$invalidTag', got: $content",
                )
                val stored = support.webhookRepo.findByClientAndTag(support.testClientId, invalidTag)
                assertEquals(null, stored, "Tag '$invalidTag' should not be persisted")
            }
        }

    @Test
    fun `add rejects auto-provisioning when user lacks manage webhooks or admin permission`() =
        runTest {
            support.registerTestClient()

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "alerts"),
                    userPermissions = setOf(Permission.SendMessages, Permission.ViewChannel),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("沒有「管理 Webhook」權限"))
            coVerify(exactly = 0) { support.mockRequestHandler.handle(any<Request<*, *>>()) }
            assertTrue(support.webhookRepo.listByClientId(support.testClientId).isEmpty())
        }

    @Test
    fun `add auto-provisions channel webhook when user has administrator permission`() =
        runTest {
            support.registerTestClient()

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "admin-tag"),
                    userPermissions = setOf(Permission.Administrator),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("成功"))
            coVerify(
                exactly = 1,
            ) { support.mockRequestHandler.handle(match<Request<*, *>> { it.route == Route.WebhookPost }) }

            val stored = support.webhookRepo.findByClientAndTag(support.testClientId, "admin-tag")
            assertNotNull(stored)
            assertTrue(stored.isAutoProvisioned)
            assertEquals("https://discord.com/api/webhooks/777888999/auto-token-xyz", stored.webhookUrl)
        }

    @Test
    fun `add auto-provisions webhook via kord rest client and saves record with is_auto_provisioned true`() =
        runTest {
            support.registerTestClient()

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "ops_alerts"),
                    userPermissions = setOf(Permission.ManageWebhooks),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("成功"))
            assertTrue(content.contains("ops_alerts"))

            coVerify(
                exactly = 1,
            ) { support.mockRequestHandler.handle(match<Request<*, *>> { it.route == Route.WebhookPost }) }

            val record = support.webhookRepo.findByClientAndTag(support.testClientId, "ops_alerts")
            assertNotNull(record)
            assertEquals(support.testClientId, record.clientId)
            assertEquals("ops_alerts", record.tag)
            assertEquals(support.guildIdSnowflake.toString(), record.guildId)
            assertEquals(support.channelIdSnowflake.toString(), record.channelId)
            assertEquals("https://discord.com/api/webhooks/777888999/auto-token-xyz", record.webhookUrl)
            assertTrue(record.isAutoProvisioned)
        }

    @Test
    fun `add accepts custom url and saves record with is_auto_provisioned false without checking manage webhooks`() =
        runTest {
            support.registerTestClient()

            val customWebhookUrl = "https://discord.com/api/webhooks/123456789/custom-token-abc"
            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "add",
                    options =
                        mapOf(
                            "tag" to "custom_wh",
                            "url" to customWebhookUrl,
                        ),
                    userPermissions = setOf(Permission.SendMessages), // lacks ManageWebhooks
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("成功"))

            coVerify(exactly = 0) { support.mockRequestHandler.handle(any<Request<*, *>>()) }

            val record = support.webhookRepo.findByClientAndTag(support.testClientId, "custom_wh")
            assertNotNull(record)
            assertEquals(customWebhookUrl, record.webhookUrl)
            assertFalse(record.isAutoProvisioned)
        }

    @Test
    fun `add accepts canary discord webhook url for custom url`() =
        runTest {
            support.registerTestClient()

            val canaryUrl = "https://canary.discord.com/api/webhooks/987654321/canary-token-xyz"
            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "add",
                    options =
                        mapOf(
                            "tag" to "canary_wh",
                            "url" to canaryUrl,
                        ),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("成功"))

            val record = support.webhookRepo.findByClientAndTag(support.testClientId, "canary_wh")
            assertNotNull(record)
            assertEquals(canaryUrl, record.webhookUrl)
            assertFalse(record.isAutoProvisioned)
        }

    @Test
    fun `add rejects invalid custom url not starting with discord webhook endpoints`() =
        runTest {
            support.registerTestClient()

            val invalidUrls =
                listOf(
                    "https://example.com/api/webhooks/123/token",
                    "http://discord.com/api/webhooks/123/token",
                    "https://discord.com/webhooks/123/token",
                    "not-a-url",
                )

            for (invalidUrl in invalidUrls) {
                val (interaction, getResponseContent) =
                    support.setupInteraction(
                        subcommandName = "add",
                        options =
                            mapOf(
                                "tag" to "test_tag",
                                "url" to invalidUrl,
                            ),
                    )

                support.command.handle(interaction)

                val content = getResponseContent()
                assertNotNull(content)
                assertTrue(content.contains("無效的 Discord Webhook URL"))
            }

            assertTrue(support.webhookRepo.listByClientId(support.testClientId).isEmpty())
        }

    @Test
    fun `add rejects duplicate tag for same client with conflict error`() =
        runTest {
            support.registerTestClient()

            val now = System.currentTimeMillis()
            support.webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-existing",
                    clientId = support.testClientId,
                    tag = "alerts",
                    guildId = support.guildIdSnowflake.toString(),
                    channelId = support.channelIdSnowflake.toString(),
                    webhookUrl = "https://discord.com/api/webhooks/111/token",
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "alerts"),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("標籤 `alerts` 已存在"))
            assertTrue(content.contains("同一 Client 標籤不可重複"))
            coVerify(exactly = 0) { support.mockRequestHandler.handle(any<Request<*, *>>()) }
        }

    @Test
    fun `add rejects 11th webhook when quota of 10 is reached`() =
        runTest {
            support.registerTestClient()

            val now = System.currentTimeMillis()
            for (i in 1..10) {
                support.webhookRepo.addWebhook(
                    McpWebhookRecord(
                        id = "wh-$i",
                        clientId = support.testClientId,
                        tag = "tag-$i",
                        guildId = support.guildIdSnowflake.toString(),
                        channelId = support.channelIdSnowflake.toString(),
                        webhookUrl = "https://discord.com/api/webhooks/$i/token-$i",
                        createdAt = now + i,
                    ),
                )
            }
            assertEquals(10, support.webhookRepo.listByClientId(support.testClientId).size)

            val (interaction, getResponseContent) =
                support.setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "tag-11"),
                )

            support.command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已達 Webhook 綁定上限 (10/10)"))
            coVerify(exactly = 0) { support.mockRequestHandler.handle(any<Request<*, *>>()) }
            assertEquals(10, support.webhookRepo.listByClientId(support.testClientId).size)
        }
}
