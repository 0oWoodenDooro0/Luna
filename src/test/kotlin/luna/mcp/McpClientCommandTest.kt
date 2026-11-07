package luna.mcp

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.User
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.GroupCommand
import dev.kord.rest.json.request.MultipartInteractionResponseCreateRequest
import dev.kord.rest.request.RequestHandler
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

class McpClientCommandTest {
    private val testUserIdSnowflake = Snowflake(987654321012345678L)
    private val testBaseUrl = "https://test.luna.mcp"
    private lateinit var database: Database
    private lateinit var clientRepo: McpClientRepository
    private lateinit var webhookRepo: McpWebhookRepository
    private lateinit var command: McpClientCommand
    private lateinit var mockRequestHandler: RequestHandler

    @BeforeEach
    fun setUp() {
        val dbName = "mcp_client_cmd_test_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        clientRepo = McpClientRepository(database)
        webhookRepo = McpWebhookRepository(database)
        command = McpClientCommand(clientRepo, webhookRepo, publicBaseUrl = testBaseUrl)
        mockRequestHandler = mockk(relaxed = true)
    }

    private fun setupInteraction(
        groupName: String = "client",
        subcommandName: String,
        options: Map<String, String> = emptyMap(),
        userId: Snowflake = testUserIdSnowflake,
    ): Pair<ChatInputCommandInteraction, () -> String?> {
        val mockGroupCommand =
            mockk<GroupCommand>(relaxed = true) {
                every { this@mockk.groupName } returns groupName
                every { this@mockk.name } returns subcommandName
                every { this@mockk.strings } returns options
            }

        val mockUser =
            mockk<User>(relaxed = true) {
                every { id } returns userId
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
                every { command } returns mockGroupCommand
                every { user } returns mockUser
                every { applicationId } returns Snowflake(111L)
                every { token } returns "interaction-token-abc"
            }

        return Pair(mockInteraction) { respondedContent }
    }

    @Test
    fun `command metadata has name mcp and non-empty description`() {
        assertEquals("mcp", command.name)
        assertTrue(command.description.isNotBlank())
    }

    @Test
    fun `create subcommand successfully provisions client in database and responds ephemerally with plain secret and setup info`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "create",
                    options = mapOf("name" to "Alice Gemini Client"),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content, "Expected an ephemeral response message")

            // Verify response contains plain secret warning and setup instructions
            assertTrue(content.contains("⚠️ 密鑰僅會顯示這一次，請妥善保存，切勿分享他人！"))
            assertTrue(content.contains("Client ID"))
            assertTrue(content.contains("Client Secret"))
            assertTrue(content.contains("$testBaseUrl/mcp/sse"))
            assertTrue(content.contains("$testBaseUrl/oauth/authorize"))
            assertTrue(content.contains("$testBaseUrl/oauth/token"))

            // Verify client created in database
            val created = clientRepo.findByUserId(testUserIdSnowflake.toString())
            assertNotNull(created)
            assertEquals("Alice Gemini Client", created.clientName)
            assertTrue(created.clientId.startsWith("luna_cli_"))
            assertTrue(content.contains(created.clientId))
        }

    @Test
    fun `create subcommand stores salted sha256 hash in database and never stores plain secret`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(
                    subcommandName = "create",
                    options = emptyMap(),
                )

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)

            val created = clientRepo.findByUserId(testUserIdSnowflake.toString())
            assertNotNull(created)

            // Database hash is 64 hex characters
            assertEquals(64, created.secretHash.length)
            assertTrue(created.secretHash.matches(Regex("^[0-9a-f]{64}$")))
            assertFalse(created.secretSalt.isBlank())

            // Plain secret from message is not stored as-is in secretHash or secretSalt
            assertFalse(content.contains(created.secretHash), "Plain message must not contain raw secret hash")
            // Verify hashing the plain secret with the salt equals the stored hash
            val secretMatch = Regex("""Client Secret\*\*:\s*`([^`]+)`""").find(content)
            assertNotNull(secretMatch, "Expected plain client secret in markdown code block")
            val plainSecret = secretMatch.groupValues[1]

            val computedHash = McpSecurity.hashSecret(plainSecret, created.secretSalt)
            assertEquals(created.secretHash, computedHash)
            assertFalse(created.secretHash.contains(plainSecret))
        }

    @Test
    fun `create subcommand second attempt by same user rejects with 1-client limit and shows existing client ID`() =
        runTest {
            val (firstInteraction, getFirstResponse) =
                setupInteraction(
                    subcommandName = "create",
                    options = mapOf("name" to "First Client"),
                )
            command.handle(firstInteraction)

            val existing = clientRepo.findByUserId(testUserIdSnowflake.toString())
            assertNotNull(existing)

            val (secondInteraction, getSecondResponse) =
                setupInteraction(
                    subcommandName = "create",
                    options = mapOf("name" to "Second Attempt"),
                )
            command.handle(secondInteraction)

            val secondContent = getSecondResponse()
            assertNotNull(secondContent)

            // Must state 1-client limit and show existing client_id
            assertTrue(secondContent.contains(existing.clientId))
            assertTrue(secondContent.contains("1") && secondContent.contains("Client"))
            assertTrue(secondContent.contains("/mcp client show") || secondContent.contains("/mcp client reset-secret"))

            // Verify database record was NOT overwritten
            val current = clientRepo.findByUserId(testUserIdSnowflake.toString())
            assertNotNull(current)
            assertEquals(existing.clientId, current.clientId)
            assertEquals(existing.clientName, current.clientName)
            assertEquals(existing.secretHash, current.secretHash)
        }

    @Test
    fun `show subcommand when client exists displays client ID name creation date and quota usage without exposing secret`() =
        runTest {
            val now = System.currentTimeMillis()
            val record =
                McpClientRecord(
                    clientId = "luna_cli_test_show_123",
                    userId = testUserIdSnowflake.toString(),
                    clientName = "Production Client",
                    secretHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
                    secretSalt = "salt123",
                    createdAt = now,
                    updatedAt = now,
                )
            clientRepo.createClient(record)

            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "show")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)

            assertTrue(content.contains(record.clientId))
            assertTrue(content.contains("Production Client"))
            assertTrue(content.contains("0/10"), "Expected 0/10 quota usage when no webhooks exist")
            assertFalse(content.contains(record.secretHash))
            assertFalse(content.contains(record.secretSalt))
        }

    @Test
    fun `show subcommand reflects actual registered webhook quota count`() =
        runTest {
            val now = System.currentTimeMillis()
            val clientId = "luna_cli_quota_test"
            val record =
                McpClientRecord(
                    clientId = clientId,
                    userId = testUserIdSnowflake.toString(),
                    clientName = "Quota Client",
                    secretHash = "hash123456",
                    secretSalt = "salt123456",
                    createdAt = now,
                    updatedAt = now,
                )
            clientRepo.createClient(record)

            // Add 2 webhooks under this client
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-1",
                    clientId = clientId,
                    tag = "alerts",
                    guildId = "g-1",
                    channelId = "c-1",
                    webhookUrl = "https://discord.com/api/webhooks/1/token1",
                    createdAt = now,
                ),
            )
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-2",
                    clientId = clientId,
                    tag = "logs",
                    guildId = "g-1",
                    channelId = "c-2",
                    webhookUrl = "https://discord.com/api/webhooks/2/token2",
                    createdAt = now,
                ),
            )

            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "show")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("2/10"), "Expected 2/10 quota usage, got: $content")
        }

    @Test
    fun `show subcommand when client does not exist returns guidance message prompting create`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "show")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("/mcp client create"))
        }

    @Test
    fun `interaction with unknown subcommand responds with guidance message`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "unknown-command")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("/mcp client create") || content.contains("/mcp client show"))
        }

    @Test
    fun `reset-secret subcommand updates hash and salt in database and returns new secret`() =
        runTest {
            val now = System.currentTimeMillis()
            val oldSecret = "old_plain_secret_value_12345678"
            val oldSalt = "old_salt_value"
            val oldHash = McpSecurity.hashSecret(oldSecret, oldSalt)
            val clientId = "luna_cli_reset_test"

            clientRepo.createClient(
                McpClientRecord(
                    clientId = clientId,
                    userId = testUserIdSnowflake.toString(),
                    clientName = "Reset Test Client",
                    secretHash = oldHash,
                    secretSalt = oldSalt,
                    createdAt = now - 10000,
                    updatedAt = now - 10000,
                ),
            )

            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "reset-secret")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("密鑰已重設！舊密鑰與現有連線已全數失效。新密鑰僅顯示這一次，請在 Gemini 端重新填入設定！"))
            assertTrue(content.contains(clientId))

            val secretMatch = Regex("""Client Secret\*\*:\s*`([^`]+)`""").find(content)
            assertNotNull(secretMatch)
            val newPlainSecret = secretMatch.groupValues[1]

            val updatedClient = clientRepo.findByClientId(clientId)
            assertNotNull(updatedClient)
            assertTrue(updatedClient.secretHash != oldHash)
            assertTrue(updatedClient.secretSalt != oldSalt)
            assertTrue(McpSecurity.verifySecret(newPlainSecret, updatedClient.secretSalt, updatedClient.secretHash))
            assertFalse(McpSecurity.verifySecret(oldSecret, updatedClient.secretSalt, updatedClient.secretHash))
        }

    @Test
    fun `reset-secret subcommand when client does not exist returns guidance message prompting create`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "reset-secret")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("/mcp client create"))
        }

    @Test
    fun `revoke subcommand deletes all webhooks deletes auto-provisioned discord webhooks via api and removes client from database`() =
        runTest {
            val now = System.currentTimeMillis()
            val clientId = "luna_cli_revoke_test"
            clientRepo.createClient(
                McpClientRecord(
                    clientId = clientId,
                    userId = testUserIdSnowflake.toString(),
                    clientName = "Revoke Client",
                    secretHash = "hash123",
                    secretSalt = "salt123",
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            // Add auto-provisioned webhook
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-auto-1",
                    clientId = clientId,
                    tag = "auto-wh",
                    guildId = "g-1",
                    channelId = "c-1",
                    webhookUrl = "https://discord.com/api/webhooks/777888999/auto-token-xyz",
                    isAutoProvisioned = true,
                    createdAt = now,
                ),
            )

            // Add custom url webhook
            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-custom-1",
                    clientId = clientId,
                    tag = "custom-wh",
                    guildId = "g-1",
                    channelId = "c-2",
                    webhookUrl = "https://discord.com/api/webhooks/123/custom-token",
                    isAutoProvisioned = false,
                    createdAt = now + 100,
                ),
            )

            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "revoke")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已成功撤銷 Client ID (`$clientId`) 及所有綁定之 Webhook。"))

            coVerify(exactly = 1) {
                mockRequestHandler.handle(
                    match<dev.kord.rest.request.Request<*, *>> {
                        it.route == dev.kord.rest.route.Route.WebhookByTokenDelete
                    },
                )
            }

            assertTrue(webhookRepo.listByClientId(clientId).isEmpty())
            assertEquals(null, clientRepo.findByClientId(clientId))
        }

    @Test
    fun `revoke subcommand when discord rest throws exception logs degraded warning and still removes client and webhooks from database`() =
        runTest {
            val now = System.currentTimeMillis()
            val clientId = "luna_cli_revoke_err_test"
            clientRepo.createClient(
                McpClientRecord(
                    clientId = clientId,
                    userId = testUserIdSnowflake.toString(),
                    clientName = "Revoke Client",
                    secretHash = "hash123",
                    secretSalt = "salt123",
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            webhookRepo.addWebhook(
                McpWebhookRecord(
                    id = "wh-auto-err",
                    clientId = clientId,
                    tag = "auto-wh",
                    guildId = "g-1",
                    channelId = "c-1",
                    webhookUrl = "https://discord.com/api/webhooks/777888999/auto-token-xyz",
                    isAutoProvisioned = true,
                    createdAt = now,
                ),
            )

            coEvery {
                mockRequestHandler.handle(
                    match<dev.kord.rest.request.Request<*, *>> {
                        it.route == dev.kord.rest.route.Route.WebhookByTokenDelete
                    },
                )
            } throws RuntimeException("Discord 404 Not Found")

            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "revoke")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("已成功撤銷 Client ID (`$clientId`) 及所有綁定之 Webhook。"))

            assertTrue(webhookRepo.listByClientId(clientId).isEmpty())
            assertEquals(null, clientRepo.findByClientId(clientId))
        }

    @Test
    fun `revoke subcommand when client does not exist returns guidance message prompting create`() =
        runTest {
            val (interaction, getResponseContent) =
                setupInteraction(subcommandName = "revoke")

            command.handle(interaction)

            val content = getResponseContent()
            assertNotNull(content)
            assertTrue(content.contains("/mcp client create"))
        }
}
