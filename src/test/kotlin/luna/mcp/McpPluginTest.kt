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
import luna.plugin.DefaultPluginContext
import luna.plugin.LunaPlugin
import luna.plugin.PluginLoader
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `plugin metadata has correct name mcp`() {
        val plugin = McpPlugin()
        assertEquals("mcp", plugin.name)
    }

    @Test
    fun `plugin is discovered by ServiceLoader SPI`() {
        val loader = PluginLoader()
        val plugins = loader.loadPlugins()
        assertTrue(
            plugins.any { it is McpPlugin },
            "Expected McpPlugin to be discoverable via PluginLoader",
        )

        val directLoader = ServiceLoader.load(LunaPlugin::class.java)
        assertTrue(
            directLoader.any { it is McpPlugin },
            "Expected McpPlugin to be discoverable via ServiceLoader",
        )
    }

    @Test
    fun `initialize creates mcp webhooks table in database`() =
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

            assertNotNull(plugin.storage)
            assertNotNull(plugin.webhookCommand)
            assertEquals("mcp-webhook", plugin.webhookCommand?.name)

            // Verify table was initialized in DB by querying and adding through storage
            val guildId = "guild-test"
            val channelId = "channel-test"
            val webhookUrl = "https://discord.com/api/webhooks/123/abc"

            plugin.storage!!.addWebhook(guildId, channelId, webhookUrl)

            transaction(db) {
                val rows = McpWebhooksTable.selectAll().toList()
                assertEquals(1, rows.size)
                assertEquals(guildId, rows.first()[McpWebhooksTable.guildId])
                assertEquals(channelId, rows.first()[McpWebhooksTable.channelId])
                assertEquals(webhookUrl, rows.first()[McpWebhooksTable.webhookUrl])
            }
        }

    @Test
    fun `initialize registers mcp-webhook command on kord`() =
        runTest {
            val db = createTestDb()
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventsFlow)

            val mockCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "mcp-webhook"
                }

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = McpPlugin(commandFactory = { mockCommand })
            plugin.initialize(context)

            assertEquals(mockCommand, plugin.webhookCommand)
            coVerify(exactly = 1) { mockCommand.register(mockKord) }
        }

    @Test
    fun `initialize attaches slash command interaction handler for mcp-webhook`() =
        runTest {
            val db = createTestDb()
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventsFlow)

            val mockCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "mcp-webhook"
                }

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = McpPlugin(commandFactory = { mockCommand })
            plugin.initialize(context)

            val interaction =
                mockk<ChatInputCommandInteraction>(relaxed = true) {
                    every { command.rootName } returns "mcp-webhook"
                    every { user.id } returns Snowflake(123456789L)
                }
            val event =
                mockk<ChatInputCommandInteractionCreateEvent>(relaxed = true) {
                    every { this@mockk.interaction } returns interaction
                }

            eventsFlow.emit(event)

            coVerify(exactly = 1) { mockCommand.handle(interaction) }
        }

    @Test
    fun `initialize ignores slash command interaction with different command root name`() =
        runTest {
            val db = createTestDb()
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventsFlow)

            val mockCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "mcp-webhook"
                }

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = McpPlugin(commandFactory = { mockCommand })
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

            coVerify(exactly = 0) { mockCommand.handle(any()) }
        }
}
