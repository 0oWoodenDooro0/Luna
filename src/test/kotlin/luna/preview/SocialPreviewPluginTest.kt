package luna.preview

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.event.Event
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.message.MessageCreateEvent
import io.mockk.coEvery
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

class SocialPreviewPluginTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:social_preview_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    private fun createMockKord(eventsFlow: MutableSharedFlow<Event>): Kord =
        mockk<Kord>(relaxed = true) {
            every { coroutineContext } returns EmptyCoroutineContext
            every { events } returns eventsFlow
        }

    @Test
    fun `plugin metadata has correct name`() {
        val plugin = SocialPreviewPlugin()
        assertEquals("social-preview", plugin.name)
    }

    @Test
    fun `plugin is discovered by ServiceLoader SPI`() {
        val loader = PluginLoader()
        val plugins = loader.loadPlugins()
        assertTrue(
            plugins.any { it is SocialPreviewPlugin },
            "Expected SocialPreviewPlugin to be discoverable via ServiceLoader SPI",
        )

        val directLoader = ServiceLoader.load(LunaPlugin::class.java)
        assertTrue(
            directLoader.any { it is SocialPreviewPlugin },
            "Expected ServiceLoader to directly discover SocialPreviewPlugin",
        )
    }

    @Test
    fun `initialize creates guild preview settings table in database`() =
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

            val plugin = SocialPreviewPlugin()
            plugin.initialize(context)

            assertNotNull(plugin.settingsStorage)
            assertNotNull(plugin.previewCommand)
            assertEquals("preview", plugin.previewCommand?.name)

            // Verify table was initialized in DB by querying and updating through storage and exposed
            val guildId = "guild_test_123"
            assertTrue(plugin.settingsStorage!!.isGuildEnabled(guildId))
            plugin.settingsStorage!!.setGuildEnabled(guildId, false)
            assertEquals(false, plugin.settingsStorage!!.isGuildEnabled(guildId))

            transaction(db) {
                val rows = GuildPreviewSettingsTable.selectAll().toList()
                assertEquals(1, rows.size)
                assertEquals(guildId, rows.first()[GuildPreviewSettingsTable.guildId])
                assertEquals(false, rows.first()[GuildPreviewSettingsTable.enabled])
            }
        }

    @Test
    fun `initialize registers preview slash command on kord`() =
        runTest {
            val db = createTestDb()
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventsFlow)

            val mockCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "preview"
                }

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = SocialPreviewPlugin(commandFactory = { mockCommand })
            plugin.initialize(context)

            assertEquals(mockCommand, plugin.previewCommand)
            coVerify(exactly = 1) { mockCommand.register(mockKord) }
        }

    @Test
    fun `initialize attaches slash command interaction handler for preview command`() =
        runTest {
            val db = createTestDb()
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventsFlow)

            val mockCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "preview"
                }

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = SocialPreviewPlugin(commandFactory = { mockCommand })
            plugin.initialize(context)

            val interaction =
                mockk<ChatInputCommandInteraction>(relaxed = true) {
                    every { command.rootName } returns "preview"
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
                    every { name } returns "preview"
                }

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                )

            val plugin = SocialPreviewPlugin(commandFactory = { mockCommand })
            plugin.initialize(context)

            val otherInteraction =
                mockk<ChatInputCommandInteraction>(relaxed = true) {
                    every { command.rootName } returns "shorten"
                }
            val event =
                mockk<ChatInputCommandInteractionCreateEvent>(relaxed = true) {
                    every { this@mockk.interaction } returns otherInteraction
                }

            eventsFlow.emit(event)

            coVerify(exactly = 0) { mockCommand.handle(any()) }
        }

    @Test
    fun `initialize attaches message create listener for social preview processing`() =
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

            val plugin = SocialPreviewPlugin()
            plugin.initialize(context)

            val event =
                mockk<MessageCreateEvent>(relaxed = true) {
                    every { message.author?.isBot } returns true
                }

            // Emitting message event should be safely consumed by the registered listener
            eventsFlow.emit(event)
        }
}
