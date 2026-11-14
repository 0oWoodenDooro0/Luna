package luna.undercover

import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.DeferredEphemeralMessageInteractionResponseBehavior
import dev.kord.core.cache.data.InteractionData
import dev.kord.core.entity.interaction.SelectMenuInteraction
import dev.kord.core.event.Event
import dev.kord.core.event.interaction.SelectMenuInteractionCreateEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import luna.core.Command
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UndercoverPluginTest {
    private val guildId = Snowflake(123456789L)
    private val player1Id = Snowflake(1001L)
    private val player2Id = Snowflake(1002L)
    private val player3Id = Snowflake(1003L)

    private var testJob = Job()

    private fun createTestDb(): Database = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        testJob = Job()
        UndercoverManager.activeGames.clear()
    }

    @AfterEach
    fun tearDown() {
        testJob.cancel()
        UndercoverManager.activeGames.clear()
    }

    private fun createMockKord(eventFlow: MutableSharedFlow<Event>): Kord =
        mockk<Kord>(relaxed = true) {
            every { events } returns eventFlow
            every { coroutineContext } returns Dispatchers.Unconfined + testJob
        }

    @Test
    fun `initialize registers undercover and reveal commands to kord`() =
        runTest {
            val mockUndercoverCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "undercover"
                }
            val mockRevealCommand =
                mockk<Command>(relaxed = true) {
                    every { name } returns "reveal"
                }

            val eventFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventFlow)

            val plugin =
                UndercoverPlugin(
                    undercoverCommand = mockUndercoverCommand,
                    revealCommand = mockRevealCommand,
                )

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = createTestDb(),
                )

            plugin.initialize(context)

            coVerify(exactly = 1) { mockUndercoverCommand.register(mockKord) }
            coVerify(exactly = 1) { mockRevealCommand.register(mockKord) }
        }

    @Test
    fun `voting listener responds with warning when no active game exists`() =
        runTest {
            val eventFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventFlow)

            val plugin = UndercoverPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = createTestDb(),
                )
            plugin.initialize(context)

            val mockData =
                mockk<InteractionData>(relaxed = true) {
                    every { guildId } returns OptionalSnowflake.Value(this@UndercoverPluginTest.guildId)
                }
            val mockResponse = mockk<DeferredEphemeralMessageInteractionResponseBehavior>(relaxed = true)
            val mockInteraction =
                mockk<SelectMenuInteraction>(relaxed = true) {
                    every { componentId } returns UndercoverPlugin.VOTE_COMPONENT_ID
                    every { data } returns mockData
                    every { user.id } returns player1Id
                    every { values } returns listOf(player2Id.toString())
                }
            coEvery { mockInteraction.deferEphemeralResponse() } returns mockResponse

            val event =
                mockk<SelectMenuInteractionCreateEvent>(relaxed = true) {
                    every { interaction } returns mockInteraction
                }

            eventFlow.emit(event)

            coVerify(exactly = 1) { mockInteraction.deferEphemeralResponse() }
        }

    @Test
    fun `voting listener responds with warning when user is not a player in active game`() =
        runTest {
            UndercoverManager.activeGames[guildId] =
                UndercoverGame(
                    players = mapOf(player1Id to "Alice", player2Id to "Bob"),
                    spy = player2Id,
                    votes = mutableMapOf(),
                )

            val nonPlayerId = Snowflake(9999L)
            val eventFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventFlow)

            val plugin = UndercoverPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = createTestDb(),
                )
            plugin.initialize(context)

            val mockData =
                mockk<InteractionData>(relaxed = true) {
                    every { guildId } returns OptionalSnowflake.Value(this@UndercoverPluginTest.guildId)
                }
            val mockResponse = mockk<DeferredEphemeralMessageInteractionResponseBehavior>(relaxed = true)
            val mockInteraction =
                mockk<SelectMenuInteraction>(relaxed = true) {
                    every { componentId } returns UndercoverPlugin.VOTE_COMPONENT_ID
                    every { data } returns mockData
                    every { user.id } returns nonPlayerId
                    every { values } returns listOf(player2Id.toString())
                }
            coEvery { mockInteraction.deferEphemeralResponse() } returns mockResponse

            val event =
                mockk<SelectMenuInteractionCreateEvent>(relaxed = true) {
                    every { interaction } returns mockInteraction
                }

            eventFlow.emit(event)

            val game = UndercoverManager.activeGames[guildId]
            assertNotNull(game)
            assertTrue(game.votes.isEmpty())
            coVerify(exactly = 1) { mockInteraction.deferEphemeralResponse() }
        }

    @Test
    fun `voting listener records vote when player in active game submits valid vote`() =
        runTest {
            UndercoverManager.activeGames[guildId] =
                UndercoverGame(
                    players =
                        mapOf(
                            player1Id to "Alice",
                            player2Id to "Bob",
                            player3Id to "Charlie",
                        ),
                    spy = player2Id,
                    votes = mutableMapOf(),
                )

            val eventFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventFlow)

            val plugin = UndercoverPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = createTestDb(),
                )
            plugin.initialize(context)

            val mockData =
                mockk<InteractionData>(relaxed = true) {
                    every { guildId } returns OptionalSnowflake.Value(this@UndercoverPluginTest.guildId)
                }
            val mockResponse = mockk<DeferredEphemeralMessageInteractionResponseBehavior>(relaxed = true)
            val mockInteraction =
                mockk<SelectMenuInteraction>(relaxed = true) {
                    every { componentId } returns UndercoverPlugin.VOTE_COMPONENT_ID
                    every { data } returns mockData
                    every { user.id } returns player1Id
                    every { values } returns listOf(player2Id.toString())
                }
            coEvery { mockInteraction.deferEphemeralResponse() } returns mockResponse

            val event =
                mockk<SelectMenuInteractionCreateEvent>(relaxed = true) {
                    every { interaction } returns mockInteraction
                }

            eventFlow.emit(event)

            val game = UndercoverManager.activeGames[guildId]
            assertNotNull(game)
            assertEquals(player2Id, game.votes[player1Id])
            coVerify(exactly = 1) { mockInteraction.deferEphemeralResponse() }
        }

    @Test
    fun `voting listener ignores events with different componentId`() =
        runTest {
            val eventFlow = MutableSharedFlow<Event>()
            val mockKord = createMockKord(eventFlow)

            val plugin = UndercoverPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = createTestDb(),
                )
            plugin.initialize(context)

            val mockInteraction =
                mockk<SelectMenuInteraction>(relaxed = true) {
                    every { componentId } returns "other_component"
                }
            val event =
                mockk<SelectMenuInteractionCreateEvent>(relaxed = true) {
                    every { interaction } returns mockInteraction
                }

            eventFlow.emit(event)

            coVerify(exactly = 0) { mockInteraction.deferEphemeralResponse() }
        }

    @Test
    fun `shutdown clears active games`() =
        runTest {
            UndercoverManager.activeGames[guildId] =
                UndercoverGame(
                    players = mapOf(player1Id to "Alice"),
                    spy = player1Id,
                    votes = mutableMapOf(),
                )

            val plugin = UndercoverPlugin()
            plugin.shutdown()

            assertTrue(UndercoverManager.activeGames.isEmpty())
        }
}
