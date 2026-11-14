package luna.mcp

import dev.kord.common.entity.DiscordWebhook
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.Optional
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Member
import dev.kord.core.entity.User
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.GroupCommand
import dev.kord.rest.json.request.MultipartInteractionResponseCreateRequest
import dev.kord.rest.service.InteractionService
import dev.kord.rest.service.RestClient
import dev.kord.rest.service.WebhookService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import luna.mcp.data.McpClientRecord
import luna.mcp.data.McpClientRepository
import luna.mcp.data.McpWebhookRecord
import luna.mcp.data.McpWebhookRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpWebhookCommandTest {
    private val testUserIdSnowflake = Snowflake(987654321012345678L)
    private val guildIdSnowflake = Snowflake(111222333L)
    private val channelIdSnowflake = Snowflake(444555666L)
    private val testClientId = "luna_cli_testclient123"

    private lateinit var database: Database
    private lateinit var clientRepo: McpClientRepository
    private lateinit var webhookRepo: McpWebhookRepository
    private lateinit var command: McpWebhookCommand
    private lateinit var mockRequestHandler: dev.kord.rest.request.RequestHandler

    @BeforeEach
    fun setUp() {
        val dbName = "mcp_webhook_cmd_test_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        clientRepo = McpClientRepository(database)
        webhookRepo = McpWebhookRepository(database)
        command = McpWebhookCommand(clientRepo, webhookRepo)

        mockRequestHandler = mockk(relaxed = true)
    }

    private fun registerTestClient(
        userId: Snowflake = testUserIdSnowflake,
        clientId: String = testClientId,
    ) {
        val now = System.currentTimeMillis()
        clientRepo.createClient(
            McpClientRecord(
                clientId = clientId,
                userId = userId.toString(),
                clientName = "Test Client",
                secretHash = "secret_hash_value",
                secretSalt = "secret_salt_value",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun setupInteraction(
        groupName: String = "webhook",
        subcommandName: String,
        options: Map<String, String> = emptyMap(),
        userId: Snowflake = testUserIdSnowflake,
        isGuild: Boolean = true,
        userPermissions: Set<Permission> = setOf(Permission.ManageWebhooks),
        mockCreatedWebhook: DiscordWebhook? = null,
    ): Pair<ChatInputCommandInteraction, () -> String?> {
        val mockData =
            mockk<dev.kord.core.cache.data.InteractionData>(relaxed = true) {
                every { guildId } returns if (isGuild) OptionalSnowflake.Value(guildIdSnowflake) else OptionalSnowflake.Missing
            }

        val mockGroupCommand =
            mockk<GroupCommand>(relaxed = true) {
                every { this@mockk.groupName } returns groupName
                every { this@mockk.name } returns subcommandName
                every { this@mockk.strings } returns options
            }

        val mockMember =
            mockk<Member>(relaxed = true) {
                coEvery { getPermissions() } returns Permissions(userPermissions)
            }

        val mockUser =
            mockk<User>(relaxed = true) {
                every { id } returns userId
                coEvery { asMemberOrNull(guildIdSnowflake) } returns if (isGuild) mockMember else null
            }

        val capturedRequest = slot<MultipartInteractionResponseCreateRequest>()
        var respondedContent: String? = null

        val mockInteractionService = mockk<InteractionService>(relaxed = true)
        coEvery {
            mockInteractionService.createInteractionResponse(any(), any(), capture(capturedRequest))
        } answers {
            respondedContent =
                capturedRequest.captured.request.data.value
                    ?.content
                    ?.value
            Unit
        }

        val defaultCreatedWebhook =
            mockCreatedWebhook ?: mockk<DiscordWebhook>(relaxed = true) {
                every { id } returns Snowflake(777888999L)
                every { token } returns Optional.Value("auto-token-xyz")
            }

        mockRequestHandler = mockk<dev.kord.rest.request.RequestHandler>(relaxed = true)
        coEvery {
            mockRequestHandler.handle(any<dev.kord.rest.request.Request<*, *>>())
        } returns defaultCreatedWebhook

        val webhookService = WebhookService(mockRequestHandler)

        val mockRestClient =
            mockk<RestClient>(relaxed = true) {
                every { interaction } returns mockInteractionService
                every { webhook } returns webhookService
            }

        val mockKord =
            mockk<Kord>(relaxed = true) {
                every { rest } returns mockRestClient
            }

        val mockInteraction =
            mockk<ChatInputCommandInteraction>(relaxed = true) {
                every { kord } returns mockKord
                every { data } returns mockData
                every { command } returns mockGroupCommand
                every { user } returns mockUser
                every { channelId } returns channelIdSnowflake
                every { applicationId } returns Snowflake(111L)
                every { token } returns "interaction-token-abc"
            }

        return Pair(mockInteraction) { respondedContent }
    }

    @Test
    fun `add rejects interaction when user has not created client ID`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "alerts"),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("您尚未建立 Client ID"))
            assertTrue(content.contains("/mcp client create"))
            assertTrue(webhookRepo.listByClientId(testClientId).isEmpty())
        }

    @Test
    fun `add validates valid alphanumeric and Chinese tags`() =
        runTest {
            registerTestClient()

            val validTags = listOf("alerts", "ops_log-1", "中文標籤", "A1_b2-c3")
            for ((index, tag) in validTags.withIndex()) {
                val (interaction, getResponseContent) =
                    setupInteraction(
                        subcommandName = "add",
                        options = mapOf("tag" to tag),
                        mockCreatedWebhook =
                            mockk(relaxed = true) {
                                every { id } returns Snowflake(1000L + index)
                                every { token } returns Optional.Value("token-$index")
                            },
                    )

                command.handle(interaction)

                val content = getResponseContent()
                assertNotNull(content)
                assertTrue(content.contains("成功") || content.contains("綁定"))
                val stored = webhookRepo.findByClientAndTag(testClientId, tag)
                assertNotNull(stored, "Expected tag $tag to be persisted")
            }
        }

    @Test
    fun `add rejects invalid tags with spaces or special characters or empty`() =
        runTest {
            registerTestClient()

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
                    setupInteraction(
                        subcommandName = "add",
                        options = mapOf("tag" to invalidTag),
                    )

                command.handle(interaction)

                val content = getResponseContent()
                assertNotNull(content, "Expected error response for tag: '$invalidTag'")
                assertTrue(
                    content.contains("標籤格式不正確"),
                    "Expected tag error message for '$invalidTag', got: $content",
                )
                val stored = webhookRepo.findByClientAndTag(testClientId, invalidTag)
                assertEquals(null, stored, "Tag '$invalidTag' should not be persisted")
            }
        }

    @Test
    fun `add rejects auto-provisioning when user lacks manage webhooks or admin permission`() =
        runTest {
            registerTestClient()

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "alerts"),
                    userPermissions = setOf(Permission.SendMessages, Permission.ViewChannel),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("沒有「管理 Webhook」權限"))
            coVerify(exactly = 0) { mockRequestHandler.handle(any<dev.kord.rest.request.Request<*, *>>()) }
            assertTrue(webhookRepo.listByClientId(testClientId).isEmpty())
        }

    @Test
    fun `add auto-provisions channel webhook when user has administrator permission`() =
        runTest {
            registerTestClient()

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "admin-tag"),
                    userPermissions = setOf(Permission.Administrator),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("成功"))
            coVerify(
                exactly = 1,
            ) { mockRequestHandler.handle(match<dev.kord.rest.request.Request<*, *>> { it.route == dev.kord.rest.route.Route.WebhookPost }) }

            val stored = webhookRepo.findByClientAndTag(testClientId, "admin-tag")
            assertNotNull(stored)
            assertTrue(stored.isAutoProvisioned)
            assertEquals("https://discord.com/api/webhooks/777888999/auto-token-xyz", stored.webhookUrl)
        }

    @Test
    fun `add auto-provisions webhook via kord rest client and saves record with is_auto_provisioned true`() =
        runTest {
            registerTestClient()

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "ops_alerts"),
                    userPermissions = setOf(Permission.ManageWebhooks),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("成功"))
            assertTrue(content.contains("ops_alerts"))

            coVerify(
                exactly = 1,
            ) { mockRequestHandler.handle(match<dev.kord.rest.request.Request<*, *>> { it.route == dev.kord.rest.route.Route.WebhookPost }) }

            val record = webhookRepo.findByClientAndTag(testClientId, "ops_alerts")
            assertNotNull(record)
            assertEquals(testClientId, record.clientId)
            assertEquals("ops_alerts", record.tag)
            assertEquals(guildIdSnowflake.toString(), record.guildId)
            assertEquals(channelIdSnowflake.toString(), record.channelId)
            assertEquals("https://discord.com/api/webhooks/777888999/auto-token-xyz", record.webhookUrl)
            assertTrue(record.isAutoProvisioned)
        }

    @Test
    fun `add accepts custom url and saves record with is_auto_provisioned false without checking manage webhooks`() =
        runTest {
            registerTestClient()

            val customWebhookUrl = "https://discord.com/api/webhooks/123456789/custom-token-abc"
            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "add",
                    options =
                        mapOf(
                            "tag" to "custom_wh",
                            "url" to customWebhookUrl,
                        ),
                    userPermissions = setOf(Permission.SendMessages), // lacks ManageWebhooks
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("成功"))

            coVerify(exactly = 0) { mockRequestHandler.handle(any<dev.kord.rest.request.Request<*, *>>()) }

            val record = webhookRepo.findByClientAndTag(testClientId, "custom_wh")
            assertNotNull(record)
            assertEquals(customWebhookUrl, record.webhookUrl)
            assertFalse(record.isAutoProvisioned)
        }

    @Test
    fun `add accepts canary discord webhook url for custom url`() =
        runTest {
            registerTestClient()

            val canaryUrl = "https://canary.discord.com/api/webhooks/987654321/canary-token-xyz"
            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "add",
                    options =
                        mapOf(
                            "tag" to "canary_wh",
                            "url" to canaryUrl,
                        ),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("成功"))

            val record = webhookRepo.findByClientAndTag(testClientId, "canary_wh")
            assertNotNull(record)
            assertEquals(canaryUrl, record.webhookUrl)
            assertFalse(record.isAutoProvisioned)
        }

    @Test
    fun `add rejects invalid custom url not starting with discord webhook endpoints`() =
        runTest {
            registerTestClient()

            val invalidUrls =
                listOf(
                    "https://example.com/api/webhooks/123/token",
                    "http://discord.com/api/webhooks/123/token",
                    "https://discord.com/webhooks/123/token",
                    "not-a-url",
                )

            for (invalidUrl in invalidUrls) {
                val (interaction, getResponseContent) =
                    setupInteraction(
                        subcommandName = "add",
                        options =
                            mapOf(
                                "tag" to "test_tag",
                                "url" to invalidUrl,
                            ),
                    )

                command.handle(interaction)

                val content = getResponseContent()
                assertNotNull(content)
                assertTrue(content.contains("無效的 Discord Webhook URL"))
            }

            assertTrue(webhookRepo.listByClientId(testClientId).isEmpty())
        }

    @Test
    fun `add rejects duplicate tag for same client with conflict error`() =
        runTest {
            registerTestClient()

            val now = System.currentTimeMillis()
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-existing",
                    clientId = testClientId,
                    tag = "alerts",
                    guildId = guildIdSnowflake.toString(),
                    channelId = channelIdSnowflake.toString(),
                    webhookUrl = "https://discord.com/api/webhooks/111/token",
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "alerts"),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("標籤 `alerts` 已存在"))
            assertTrue(content.contains("同一 Client 標籤不可重複"))
            coVerify(exactly = 0) { mockRequestHandler.handle(any<dev.kord.rest.request.Request<*, *>>()) }
        }

    @Test
    fun `add rejects 11th webhook when quota of 10 is reached`() =
        runTest {
            registerTestClient()

            val now = System.currentTimeMillis()
            for (i in 1..10) {
                webhookRepo.addWebhook(
                    McpWebhookRecord(
                        id = "wh-$i",
                        clientId = testClientId,
                        tag = "tag-$i",
                        guildId = guildIdSnowflake.toString(),
                        channelId = channelIdSnowflake.toString(),
                        webhookUrl = "https://discord.com/api/webhooks/$i/token-$i",
                        createdAt = now + i,
                    ),
                )
            }
            assertEquals(10, webhookRepo.listByClientId(testClientId).size)

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "add",
                    options = mapOf("tag" to "tag-11"),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已達 Webhook 綁定上限 (10/10)"))
            coVerify(exactly = 0) { mockRequestHandler.handle(any<dev.kord.rest.request.Request<*, *>>()) }
            assertEquals(10, webhookRepo.listByClientId(testClientId).size)
        }

    @Test
    fun `list prompts user to add webhooks when none are registered`() =
        runTest {
            registerTestClient()

            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "list")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("/mcp webhook add"))
        }

    @Test
    fun `list displays all registered tags and quota status`() =
        runTest {
            registerTestClient()

            val now = System.currentTimeMillis()
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-1",
                    clientId = testClientId,
                    tag = "prod-alerts",
                    guildId = guildIdSnowflake.toString(),
                    channelId = "999888777",
                    webhookUrl = "https://discord.com/api/webhooks/1/token1",
                    isAutoProvisioned = true,
                    createdAt = now,
                ),
            )
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-2",
                    clientId = testClientId,
                    tag = "dev-logs",
                    guildId = guildIdSnowflake.toString(),
                    channelId = "666555444",
                    webhookUrl = "https://discord.com/api/webhooks/2/token2",
                    isAutoProvisioned = false,
                    createdAt = now + 1000,
                ),
            )

            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "list")

            command.handle(interaction)

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
                setupInteraction(subcommandName = "list")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("您尚未建立 Client ID"))
            assertTrue(content.contains("/mcp client create"))
        }

    @Test
    fun `interaction with unknown subcommand responds with guidance message`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "unknown")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("/mcp webhook add") || content.contains("/mcp webhook list"))
        }

    @Test
    fun `remove with auto-provisioned webhook deletes via kord rest and removes from database`() =
        runTest {
            registerTestClient()

            val now = System.currentTimeMillis()
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-remove-auto",
                    clientId = testClientId,
                    tag = "alerts",
                    guildId = guildIdSnowflake.toString(),
                    channelId = channelIdSnowflake.toString(),
                    webhookUrl = "https://discord.com/api/webhooks/777888999/auto-token-xyz",
                    isAutoProvisioned = true,
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "alerts"),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已成功移除標籤為 `alerts` 的 Webhook"))

            coVerify(exactly = 1) {
                mockRequestHandler.handle(
                    match<dev.kord.rest.request.Request<*, *>> {
                        it.route == dev.kord.rest.route.Route.WebhookByTokenDelete
                    },
                )
            }

            val stored = webhookRepo.findByClientAndTag(testClientId, "alerts")
            assertEquals(null, stored)
        }

    @Test
    fun `remove with custom url only deletes from database without kord webhook deletion call`() =
        runTest {
            registerTestClient()

            val now = System.currentTimeMillis()
            val customUrl = "https://discord.com/api/webhooks/123456789/custom-token"
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-remove-custom",
                    clientId = testClientId,
                    tag = "custom_alerts",
                    guildId = guildIdSnowflake.toString(),
                    channelId = channelIdSnowflake.toString(),
                    webhookUrl = customUrl,
                    isAutoProvisioned = false,
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "custom_alerts"),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已成功移除標籤為 `custom_alerts` 的 Webhook"))

            coVerify(exactly = 0) {
                mockRequestHandler.handle(
                    match<dev.kord.rest.request.Request<*, *>> {
                        it.route == dev.kord.rest.route.Route.WebhookByTokenDelete
                    },
                )
            }

            val stored = webhookRepo.findByClientAndTag(testClientId, "custom_alerts")
            assertEquals(null, stored)
        }

    @Test
    fun `remove when discord rest throws exception logs degraded warning and still removes from database`() =
        runTest {
            registerTestClient()

            val now = System.currentTimeMillis()
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-remove-err",
                    clientId = testClientId,
                    tag = "failing_wh",
                    guildId = guildIdSnowflake.toString(),
                    channelId = channelIdSnowflake.toString(),
                    webhookUrl = "https://discord.com/api/webhooks/777888999/auto-token-xyz",
                    isAutoProvisioned = true,
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "failing_wh"),
                )

            coEvery {
                mockRequestHandler.handle(
                    match<dev.kord.rest.request.Request<*, *>> {
                        it.route == dev.kord.rest.route.Route.WebhookByTokenDelete
                    },
                )
            } throws RuntimeException("Discord 404 Not Found")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已成功移除標籤為 `failing_wh` 的 Webhook"))

            val stored = webhookRepo.findByClientAndTag(testClientId, "failing_wh")
            assertEquals(null, stored)
        }

    @Test
    fun `remove with nonexistent tag returns error message`() =
        runTest {
            registerTestClient()

            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "nonexistent_tag"),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("找不到標籤為 `nonexistent_tag` 的 Webhook"))
        }

    @Test
    fun `remove rejects interaction when user has not created client ID`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "remove",
                    options = mapOf("tag" to "any_tag"),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("您尚未建立 Client ID"))
            assertTrue(content.contains("/mcp client create"))
        }
}
