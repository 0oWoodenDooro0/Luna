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
import dev.kord.rest.request.Request
import dev.kord.rest.request.RequestHandler
import dev.kord.rest.service.InteractionService
import dev.kord.rest.service.RestClient
import dev.kord.rest.service.WebhookService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import luna.mcp.data.McpClientRecord
import luna.mcp.data.McpClientRepository
import luna.mcp.data.McpWebhookRepository
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID

internal class McpWebhookTestSupport {
    val testUserIdSnowflake = Snowflake(987654321012345678L)
    val guildIdSnowflake = Snowflake(111222333L)
    val channelIdSnowflake = Snowflake(444555666L)
    val testClientId = "luna_cli_testclient123"

    lateinit var database: Database
    lateinit var clientRepo: McpClientRepository
    lateinit var webhookRepo: McpWebhookRepository
    lateinit var command: McpWebhookCommand
    lateinit var mockRequestHandler: RequestHandler

    fun setUp() {
        val dbName = "mcp_webhook_cmd_test_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        clientRepo = McpClientRepository(database)
        webhookRepo = McpWebhookRepository(database)
        command = McpWebhookCommand(clientRepo, webhookRepo)
        mockRequestHandler = mockk(relaxed = true)
    }

    fun registerTestClient(
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

    fun setupInteraction(
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

        mockRequestHandler = mockk<RequestHandler>(relaxed = true)
        coEvery {
            mockRequestHandler.handle(any<Request<*, *>>())
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
}
