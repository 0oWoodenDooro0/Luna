package luna.chatter.memory

import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.DeferredEphemeralMessageInteractionResponseBehavior
import dev.kord.core.cache.data.InteractionData
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
import luna.chatter.ChatterPlugin
import luna.core.LunaHost
import luna.plugin.DefaultPluginContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createStore(clock: Clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC)): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("memory-cmd-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    private fun setupInteraction(
        subcommandName: String,
        guildId: Snowflake? = Snowflake(100L),
        userId: Snowflake = Snowflake(200L),
        integers: Map<String, Long> = emptyMap(),
    ): Pair<ChatInputCommandInteraction, () -> String?> {
        val capturedModify = slot<MultipartInteractionResponseModifyRequest>()
        var respondedContent: String? = null

        val mockInteractionService = mockk<InteractionService>(relaxed = true)
        coEvery {
            mockInteractionService.modifyInteractionResponse(any(), any(), capture(capturedModify))
        } answers {
            respondedContent = capturedModify.captured.request.content.value
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
                every { applicationId } returns Snowflake(999L)
                every { token } returns "interaction-token"
            }

        val mockData =
            mockk<InteractionData>(relaxed = true) {
                every { this@mockk.guildId } returns
                    if (guildId != null) {
                        OptionalSnowflake.Value(guildId)
                    } else {
                        OptionalSnowflake.Missing
                    }
            }

        val mockUser =
            mockk<User>(relaxed = true) {
                every { id } returns userId
            }

        val mockSubCommand =
            mockk<SubCommand>(relaxed = true) {
                every { name } returns subcommandName
                every { this@mockk.integers } returns integers
            }

        val mockInteraction =
            mockk<ChatInputCommandInteraction>(relaxed = true) {
                every { data } returns mockData
                every { user } returns mockUser
                every { command } returns mockSubCommand
                coEvery { deferEphemeralResponse() } returns mockResponse
            }

        return mockInteraction to { respondedContent }
    }

    @Test
    fun `list responds ephemerally with only invoking user memories in current guild across channels`() =
        runTest {
            val store = createStore()
            val guild1 = "100"
            val guild2 = "101"
            val user1 = "200"
            val user2 = "201"

            val memA =
                store.insertMemory(
                    guildId = guild1,
                    scope = MemoryScope.USER,
                    targetUserId = user1,
                    channelId = "10",
                    content = "Likes pour-over coffee",
                )
            val memB =
                store.insertMemory(
                    guildId = guild1,
                    scope = MemoryScope.USER,
                    targetUserId = user1,
                    channelId = "20",
                    content = "Lives in Taipei",
                )
            // Noise that must NOT appear:
            store.insertMemory(
                guildId = guild1,
                scope = MemoryScope.USER,
                targetUserId = user2,
                channelId = "10",
                content = "Other user secret",
            )
            store.insertMemory(
                guildId = guild1,
                scope = MemoryScope.CHANNEL,
                targetUserId = null,
                channelId = "10",
                content = "Channel-scoped fact",
            )
            store.insertMemory(
                guildId = guild2,
                scope = MemoryScope.USER,
                targetUserId = user1,
                channelId = "30",
                content = "Other guild fact",
            )

            val command = MemoryCommand(store)
            val (interaction, getContent) = setupInteraction(subcommandName = "list")

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
            val content = getContent()
            assertNotNull(content)
            assertTrue(content.contains("[#${memA.id}] Likes pour-over coffee (updated 2026-11-20)"), "Expected memA in list")
            assertTrue(content.contains("[#${memB.id}] Lives in Taipei (updated 2026-11-20)"), "Expected memB from other channel in list")
            assertFalse(content.contains("Other user secret"), "Must not include other user's memory")
            assertFalse(content.contains("Channel-scoped fact"), "Must not include channel-scoped memory")
            assertFalse(content.contains("Other guild fact"), "Must not include other guild memory")
        }

    @Test
    fun `list truncates cleanly below 2000 characters with trailing ellipsis when long`() =
        runTest {
            val store = createStore()
            repeat(40) { idx ->
                store.insertMemory(
                    guildId = "100",
                    scope = MemoryScope.USER,
                    targetUserId = "200",
                    channelId = "10",
                    content = "Memory entry $idx: " + "x".repeat(60),
                )
            }

            val command = MemoryCommand(store)
            val (interaction, getContent) = setupInteraction(subcommandName = "list")

            command.handle(interaction)

            val content = getContent()
            assertNotNull(content)
            assertTrue(
                content.length <= MemoryCommand.DISCORD_MAX_MESSAGE_LENGTH,
                "Response length ${content.length} must be <= ${MemoryCommand.DISCORD_MAX_MESSAGE_LENGTH}",
            )
            assertTrue(content.endsWith("..."), "Truncated response must end with ellipsis")
        }

    @Test
    fun `list responds ephemerally with empty notice when user has no memories`() =
        runTest {
            val store = createStore()
            val command = MemoryCommand(store)
            val (interaction, getContent) = setupInteraction(subcommandName = "list")

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
            val content = getContent()
            assertNotNull(content)
            assertTrue(content.contains("尚未有任何長期記憶"), "Expected empty-memory notice")
        }

    @Test
    fun `delete responds ephemerally and hard-deletes owned user memory`() =
        runTest {
            val store = createStore()
            val owned =
                store.insertMemory(
                    guildId = "100",
                    scope = MemoryScope.USER,
                    targetUserId = "200",
                    channelId = "10",
                    content = "Plays Elden Ring on weekends",
                )

            val command = MemoryCommand(store)
            val (interaction, getContent) =
                setupInteraction(
                    subcommandName = "delete",
                    integers = mapOf("id" to owned.id),
                )

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
            val content = getContent()
            assertNotNull(content)
            assertTrue(content.contains("已永久刪除"), "Expected deletion confirmation")
            assertTrue(content.contains("#${owned.id}"), "Expected deleted memory ID in confirmation")

            assertTrue(store.listUserMemories(guildId = "100", userId = "200").isEmpty(), "Owned memory must be deleted")
            val searchHits =
                store.search(
                    guildId = "100",
                    channelId = "10",
                    queryText = "Elden Ring",
                    scope = SearchScope.BOTH,
                    targetUserId = "200",
                    recencyExcludeMinutes = 0,
                )
            assertTrue(searchHits.isEmpty(), "Deleted memory should be purged from FTS5 and base table")
        }

    @Test
    fun `delete refuses other user, channel-scoped, and cross-guild memories without leaking existence`() =
        runTest {
            val store = createStore()
            val otherUserMem =
                store.insertMemory(
                    guildId = "100",
                    scope = MemoryScope.USER,
                    targetUserId = "201",
                    channelId = "10",
                    content = "Other user's fact",
                )
            val channelMem =
                store.insertMemory(
                    guildId = "100",
                    scope = MemoryScope.CHANNEL,
                    targetUserId = null,
                    channelId = "10",
                    content = "Channel-scoped fact",
                )
            val otherGuildMem =
                store.insertMemory(
                    guildId = "101",
                    scope = MemoryScope.USER,
                    targetUserId = "200",
                    channelId = "30",
                    content = "Other guild fact",
                )

            val command = MemoryCommand(store)

            val responses =
                listOf(otherUserMem.id, channelMem.id, otherGuildMem.id, 999999L).map { targetId ->
                    val (interaction, getContent) =
                        setupInteraction(
                            subcommandName = "delete",
                            guildId = Snowflake(100L),
                            userId = Snowflake(200L),
                            integers = mapOf("id" to targetId),
                        )
                    command.handle(interaction)
                    val content = getContent()
                    assertNotNull(content)
                    assertTrue(
                        content.contains("找不到記憶") || content.contains("沒有權限"),
                        "Expected non-leaking refusal message for targetId=$targetId",
                    )
                    // Normalize the echoed ID to verify all four refusals produce the exact same non-leaking message
                    content.replace("#$targetId", "#ID")
                }

            assertEquals(1, responses.toSet().size, "Refusal messages must not leak whether an ID exists elsewhere")
            assertEquals(1, store.listUserMemories(guildId = "100", userId = "201").size)
            assertEquals(1, store.listUserMemories(guildId = "101", userId = "200").size)
            val channelHits =
                store.search(
                    guildId = "100",
                    channelId = "10",
                    queryText = "Channel-scoped",
                    scope = SearchScope.CHANNEL,
                    recencyExcludeMinutes = 0,
                )
            assertEquals(1, channelHits.size)
        }

    @Test
    fun `commands invoked outside a guild return ephemeral guild-only notice without throwing`() =
        runTest {
            val store = createStore()
            val command = MemoryCommand(store)

            for (subcommand in listOf("list", "delete")) {
                val (interaction, getContent) =
                    setupInteraction(
                        subcommandName = subcommand,
                        guildId = null,
                        integers = if (subcommand == "delete") mapOf("id" to 1L) else emptyMap(),
                    )

                command.handle(interaction)

                coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
                val content = getContent()
                assertNotNull(content)
                assertTrue(content.contains("只能在伺服器中使用"), "Expected guild-only notice for $subcommand")
            }
        }

    @Test
    fun `ChatterPlugin initializes MemoryStore and registers MemoryCommand`() =
        runTest {
            val dbPath = tempDir.resolve("plugin-mem-${System.nanoTime()}.db").toString()
            val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())

            val plugin = ChatterPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = db,
                )
            plugin.initialize(context)
            assertNotNull(plugin.memoryStore)
            assertNotNull(plugin.memoryCommand)
            plugin.shutdown()
        }
}
