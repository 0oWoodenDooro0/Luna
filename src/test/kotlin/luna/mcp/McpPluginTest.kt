package luna.mcp

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.event.Event
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import luna.core.Command
import luna.mcp.data.McpWebhookRecord
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class McpPluginTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:mcp_plugin_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    private fun createMockKord(eventsFlow: MutableSharedFlow<Event>): Kord =
        mockk<Kord>(relaxed = true) {
            every { coroutineContext } returns EmptyCoroutineContext
            every { events } returns eventsFlow
        }

    @Test
    fun `initialize creates mcp tables in database`() =
        runTest {
            val db = createTestDb()
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventsFlow)

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = McpPlugin()
            plugin.initialize(context)

            assertNotNull(plugin.clientRepository)
            assertNotNull(plugin.webhookRepository)
            assertNotNull(plugin.clientCommand)
            assertEquals("mcp", plugin.clientCommand?.name)

            val webhookRecord =
                McpWebhookRecord(
                    id = "wh-1",
                    clientId = "cli-1",
                    tag = "general",
                    webhookUrl = "https://discord.com/api/webhooks/123/abc",
                    channelId = "channel-test",
                    guildId = "guild-test",
                    isAutoProvisioned = true,
                    createdAt = System.currentTimeMillis(),
                )
            plugin.webhookRepository!!.addWebhook(webhookRecord)

            val stored = plugin.webhookRepository!!.findByClientAndTag("cli-1", "general")
            assertNotNull(stored)
            assertEquals("wh-1", stored.id)
            assertEquals("general", stored.tag)
            assertEquals(1, plugin.webhookRepository!!.getAllWebhooks().size)
        }

    @Test
    fun `initialize registers mcp command on kord and attaches interaction handler`() =
        runTest {
            val db = createTestDb()
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventsFlow)

            val mockClientCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "mcp"
                }

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = McpPlugin(clientCommandFactory = { _, _, _ -> mockClientCommand })
            plugin.initialize(context)

            assertNotNull(plugin.clientRepository)
            assertNotNull(plugin.webhookRepository)
            assertEquals(mockClientCommand, plugin.clientCommand)
            coVerify(exactly = 1) { mockClientCommand.register(mockKord) }

            val interaction =
                mockk<ChatInputCommandInteraction>(relaxed = true) {
                    every { command.rootName } returns "mcp"
                    every { user.id } returns Snowflake(456789123L)
                }
            val event =
                mockk<ChatInputCommandInteractionCreateEvent>(relaxed = true) {
                    every { this@mockk.interaction } returns interaction
                }

            eventsFlow.emit(event)

            coVerify(timeout = 3000, exactly = 1) { mockClientCommand.handle(interaction) }
        }

    @Test
    fun `initialize ignores slash command interaction with different command root name`() =
        runTest {
            val db = createTestDb()
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventsFlow)

            val mockClientCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "mcp"
                }

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = McpPlugin(clientCommandFactory = { _, _, _ -> mockClientCommand })
            plugin.initialize(context)

            val otherInteraction =
                mockk<ChatInputCommandInteraction>(relaxed = true) {
                    every { command.rootName } returns "preview"
                }
            val event =
                mockk<ChatInputCommandInteractionCreateEvent>(relaxed = true) {
                    every { this@mockk.interaction } returns otherInteraction
                }

            eventsFlow.emit(event)

            coVerify(exactly = 0) { mockClientCommand.handle(any()) }
        }
}
