package luna.mcp

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.DeferredEphemeralMessageInteractionResponseBehavior
import dev.kord.core.cache.data.InteractionData
import dev.kord.core.entity.Member
import dev.kord.core.entity.User
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.rest.json.request.MultipartInteractionResponseModifyRequest
import dev.kord.rest.service.InteractionService
import dev.kord.rest.service.RestClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpWebhookCommandTest {
    private val guildIdSnowflake = Snowflake(111222333L)
    private val channelIdSnowflake = Snowflake(444555666L)

    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:mcp_cmd_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    private fun setupInteraction(
        subcommandName: String,
        options: Map<String, String> = emptyMap(),
        isGuild: Boolean = true,
        userPermissions: Set<Permission> = setOf(Permission.ManageGuild),
    ): Triple<ChatInputCommandInteraction, DeferredEphemeralMessageInteractionResponseBehavior, () -> String?> {
        val mockData =
            mockk<InteractionData>(relaxed = true) {
                every { guildId } returns if (isGuild) OptionalSnowflake.Value(guildIdSnowflake) else OptionalSnowflake.Missing
            }

        val mockSubCommand =
            mockk<SubCommand>(relaxed = true) {
                every { name } returns subcommandName
                every { strings } returns options
            }

        val mockMember =
            mockk<Member>(relaxed = true) {
                coEvery { getPermissions() } returns Permissions(userPermissions)
            }

        val mockUser =
            mockk<User>(relaxed = true) {
                coEvery { asMemberOrNull(guildIdSnowflake) } returns if (isGuild) mockMember else null
            }

        val capturedRequest = slot<MultipartInteractionResponseModifyRequest>()
        var respondedContent: String? = null

        val mockInteractionService = mockk<InteractionService>(relaxed = true)
        coEvery {
            mockInteractionService.modifyInteractionResponse(any(), any(), capture(capturedRequest))
        } answers {
            respondedContent = capturedRequest.captured.request.content.value
            mockk(relaxed = true)
        }

        val mockRestClient =
            mockk<RestClient>(relaxed = true) {
                every { interaction } returns mockInteractionService
            }

        val mockKord =
            mockk<Kord>(relaxed = true) {
                every { rest } returns mockRestClient
            }

        val mockResponse =
            mockk<DeferredEphemeralMessageInteractionResponseBehavior>(relaxed = true) {
                every { kord } returns mockKord
                every { applicationId } returns Snowflake(1)
                every { token } returns "test-token"
            }

        val mockInteraction =
            mockk<ChatInputCommandInteraction>(relaxed = true) {
                every { data } returns mockData
                every { command } returns mockSubCommand
                every { user } returns mockUser
                every { channelId } returns channelIdSnowflake
                coEvery { deferEphemeralResponse() } returns mockResponse
            }

        return Triple(mockInteraction, mockResponse) { respondedContent }
    }

    @Test
    fun `command metadata has correct name and description`() {
        val db = createTestDb()
        val storage = McpWebhookStorage(db)
        val command = McpWebhookCommand(storage)

        assertEquals("mcp-webhook", command.name)
        assertTrue(command.description.isNotBlank())
    }

    @Test
    fun `rejects interaction when invoked outside a guild`() =
        runTest {
            val db = createTestDb()
            val storage = McpWebhookStorage(db)
            val command = McpWebhookCommand(storage)

            val (interaction, response, getResponseContent) =
                setupInteraction("add", mapOf("webhook_url" to "https://discord.com/api/webhooks/123/token"), isGuild = false)

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
            val responseText = getResponseContent()
            assertNotNull(responseText)
            assertTrue(responseText.contains("server", ignoreCase = true))
        }

    @Test
    fun `rejects interaction when member lacks manage guild or admin permission`() =
        runTest {
            val db = createTestDb()
            val storage = McpWebhookStorage(db)
            val command = McpWebhookCommand(storage)

            val (interaction, response, getResponseContent) =
                setupInteraction(
                    "add",
                    mapOf("webhook_url" to "https://discord.com/api/webhooks/123/token"),
                    userPermissions = setOf(Permission.SendMessages),
                )

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
            val responseText = getResponseContent()
            assertEquals("You must have Manage Server permission to manage MCP webhooks.", responseText)
            assertTrue(storage.getAllWebhooks().isEmpty())
        }

    @Test
    fun `allows member with administrator permission`() =
        runTest {
            val db = createTestDb()
            val storage = McpWebhookStorage(db)
            val command = McpWebhookCommand(storage)

            val validUrl = "https://discord.com/api/webhooks/123456789/valid-token-abc"
            val (interaction, _, getResponseContent) =
                setupInteraction(
                    "add",
                    mapOf("webhook_url" to validUrl),
                    userPermissions = setOf(Permission.Administrator),
                )

            command.handle(interaction)

            val webhooks = storage.getWebhooksByChannel(guildIdSnowflake.toString(), channelIdSnowflake.toString())
            assertEquals(1, webhooks.size)
            assertEquals(validUrl, webhooks.first().webhookUrl)
            val responseText = getResponseContent()
            assertNotNull(responseText)
            assertFalse(responseText.contains("Manage Server"))
        }

    @Test
    fun `add subcommand validates webhook url format and rejects invalid formats`() =
        runTest {
            val db = createTestDb()
            val storage = McpWebhookStorage(db)
            val command = McpWebhookCommand(storage)

            val invalidUrls =
                listOf(
                    "https://example.com/api/webhooks/123/token",
                    "https://discord.com/api/webhooks/abc/token",
                    "http://discord.com/api/webhooks/123/token",
                    "https://discord.com/api/webhooks/123/",
                    "not-a-url",
                )

            for (invalidUrl in invalidUrls) {
                val (interaction, _, getResponseContent) =
                    setupInteraction("add", mapOf("webhook_url" to invalidUrl))

                command.handle(interaction)

                val responseText = getResponseContent()
                assertNotNull(responseText, "Expected response for $invalidUrl")
                assertTrue(
                    responseText.contains("Invalid Discord webhook URL", ignoreCase = true),
                    "Expected invalid url message for $invalidUrl, got: $responseText",
                )
                assertTrue(
                    responseText.contains("discord.com/api/webhooks"),
                    "Expected expected format in response, got: $responseText",
                )
            }

            assertTrue(storage.getAllWebhooks().isEmpty())
        }

    @Test
    fun `add subcommand accepts valid standard and canary discord webhook urls`() =
        runTest {
            val db = createTestDb()
            val storage = McpWebhookStorage(db)
            val command = McpWebhookCommand(storage)

            val standardUrl = "https://discord.com/api/webhooks/123456789/token_abc-123"
            val canaryUrl = "https://canary.discord.com/api/webhooks/987654321/canary_token-456"

            val (interaction1, _, getResponseContent1) =
                setupInteraction("add", mapOf("webhook_url" to standardUrl))
            command.handle(interaction1)
            assertTrue(getResponseContent1()!!.contains("success", ignoreCase = true))

            val (interaction2, _, getResponseContent2) =
                setupInteraction("add", mapOf("webhook_url" to canaryUrl))
            command.handle(interaction2)
            assertTrue(getResponseContent2()!!.contains("success", ignoreCase = true))

            val stored = storage.getWebhooksByChannel(guildIdSnowflake.toString(), channelIdSnowflake.toString())
            assertEquals(2, stored.size)
        }

    @Test
    fun `remove subcommand with specific url removes matching webhook`() =
        runTest {
            val db = createTestDb()
            val storage = McpWebhookStorage(db)
            val command = McpWebhookCommand(storage)

            val targetUrl = "https://discord.com/api/webhooks/123/target-token"
            val otherUrl = "https://discord.com/api/webhooks/123/other-token"
            storage.addWebhook(guildIdSnowflake.toString(), channelIdSnowflake.toString(), targetUrl)
            storage.addWebhook(guildIdSnowflake.toString(), channelIdSnowflake.toString(), otherUrl)

            val (interaction, _, getResponseContent) =
                setupInteraction("remove", mapOf("webhook_url" to targetUrl))

            command.handle(interaction)

            assertTrue(getResponseContent()!!.contains("success", ignoreCase = true))
            val remaining = storage.getWebhooksByChannel(guildIdSnowflake.toString(), channelIdSnowflake.toString())
            assertEquals(1, remaining.size)
            assertEquals(otherUrl, remaining.first().webhookUrl)
        }

    @Test
    fun `remove subcommand without url removes all webhooks for channel`() =
        runTest {
            val db = createTestDb()
            val storage = McpWebhookStorage(db)
            val command = McpWebhookCommand(storage)

            val url1 = "https://discord.com/api/webhooks/123/url1"
            val url2 = "https://discord.com/api/webhooks/123/url2"
            storage.addWebhook(guildIdSnowflake.toString(), channelIdSnowflake.toString(), url1)
            storage.addWebhook(guildIdSnowflake.toString(), channelIdSnowflake.toString(), url2)

            val (interaction, _, getResponseContent) =
                setupInteraction("remove", emptyMap())

            command.handle(interaction)

            assertTrue(getResponseContent()!!.contains("success", ignoreCase = true))
            val remaining = storage.getWebhooksByChannel(guildIdSnowflake.toString(), channelIdSnowflake.toString())
            assertTrue(remaining.isEmpty())
        }
}
