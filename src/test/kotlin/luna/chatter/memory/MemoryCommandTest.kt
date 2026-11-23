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
        booleans: Map<String, Boolean> = emptyMap(),
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
                every { this@mockk.booleans } returns booleans
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
                    content = "Likes pour-over coffee",
                )
            val memB =
                store.insertMemory(
                    guildId = guild1,
                    scope = MemoryScope.USER,
                    targetUserId = user1,
                    content = "Lives in Taipei",
                )
            // Noise that must NOT appear:
            store.insertMemory(
                guildId = guild1,
                scope = MemoryScope.USER,
                targetUserId = user2,
                content = "Other user secret",
            )
            store.insertMemory(
                guildId = guild1,
                scope = MemoryScope.GUILD,
                targetUserId = null,
                content = "Guild-scoped fact",
            )
            store.insertMemory(
                guildId = guild2,
                scope = MemoryScope.USER,
                targetUserId = user1,
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
            assertFalse(content.contains("Guild-scoped fact"), "Must not include guild-scoped memory")
            assertFalse(content.contains("Other guild fact"), "Must not include other guild memory")
        }

    @Test
    fun `list truncates cleanly below 2000 characters with trailing ellipsis when long`() =
        runTest {
            val store = createStore()
            repeat(10) { idx ->
                store.insertMemory(
                    guildId = "100",
                    scope = MemoryScope.USER,
                    targetUserId = "200",
                    content = "Memory entry $idx: " + "x".repeat(250),
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
    fun `list paginates 10 memories per page with Page X of Y metadata, total count, and expiry date`() =
        runTest {
            val store = createStore()
            val inserted =
                (1..15).map { idx ->
                    val expiresAt =
                        if (idx == 15) {
                            Instant.parse("2026-11-30T23:59:59Z").toEpochMilli()
                        } else {
                            null
                        }
                    store.insertMemory(
                        guildId = "100",
                        scope = MemoryScope.USER,
                        targetUserId = "200",
                        content = "Paginated fact #$idx",
                        expiresAt = expiresAt,
                    )
                }
            // Expired memory should be excluded from total count and pages
            val expired =
                store.insertMemory(
                    guildId = "100",
                    scope = MemoryScope.USER,
                    targetUserId = "200",
                    content = "Already expired fact",
                    expiresAt = Instant.parse("2026-11-19T00:00:00Z").toEpochMilli(),
                )

            val command = MemoryCommand(store)

            val (page1Interaction, getPage1) = setupInteraction(subcommandName = "list")
            command.handle(page1Interaction)
            val page1 = getPage1()
            assertNotNull(page1)
            assertTrue(page1.contains("1/2"), "Expected page 1/2 metadata, got: $page1")
            assertTrue(page1.contains("15"), "Expected total count 15, got: $page1")
            assertTrue(page1.contains("[#${inserted[14].id}] Paginated fact #15"), "Expected newest item on page 1")
            assertTrue(page1.contains("2026-11-30"), "Expected expiry date 2026-11-30 for expiring memory")
            assertFalse(page1.contains("[#${inserted[0].id}] Paginated fact #1"), "Oldest item must be on page 2")
            assertFalse(page1.contains("[#${expired.id}]"), "Expired memory must not appear")

            val (page2Interaction, getPage2) =
                setupInteraction(
                    subcommandName = "list",
                    integers = mapOf("page" to 2L),
                )
            command.handle(page2Interaction)
            val page2 = getPage2()
            assertNotNull(page2)
            assertTrue(page2.contains("2/2"), "Expected page 2/2 metadata, got: $page2")
            assertTrue(page2.contains("15"), "Expected total count 15, got: $page2")
            assertTrue(page2.contains("[#${inserted[0].id}] Paginated fact #1"), "Expected oldest item on page 2")
            assertFalse(page2.contains("[#${inserted[14].id}] Paginated fact #15"), "Page 1 item must not appear on page 2")
        }

    @Test
    fun `list returns clear ephemeral guidance for out-of-range page numbers`() =
        runTest {
            val store = createStore()
            repeat(12) { idx ->
                store.insertMemory(
                    guildId = "100",
                    scope = MemoryScope.USER,
                    targetUserId = "200",
                    content = "Fact $idx",
                )
            }

            val command = MemoryCommand(store)

            for (badPage in listOf(0L, -1L, 3L)) {
                val (interaction, getContent) =
                    setupInteraction(
                        subcommandName = "list",
                        integers = mapOf("page" to badPage),
                    )
                command.handle(interaction)
                coVerify(atLeast = 1) { interaction.deferEphemeralResponse() }
                val content = getContent()
                assertNotNull(content)
                assertTrue(content.contains("1") && content.contains("2"), "Expected valid page range 1..2 in guidance for page=$badPage, got: $content")
            }
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
                    content = "Other user's fact",
                )
            val guildMem =
                store.insertMemory(
                    guildId = "100",
                    scope = MemoryScope.GUILD,
                    targetUserId = null,
                    content = "Guild-scoped fact",
                )
            val otherGuildMem =
                store.insertMemory(
                    guildId = "101",
                    scope = MemoryScope.USER,
                    targetUserId = "200",
                    content = "Other guild fact",
                )

            val command = MemoryCommand(store)

            val responses =
                listOf(otherUserMem.id, guildMem.id, otherGuildMem.id, 999999L).map { targetId ->
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
            val guildHits =
                store.search(
                    guildId = "100",
                    queryText = "Guild-scoped",
                    scope = SearchScope.GUILD,
                    recencyExcludeMinutes = 0,
                )
            assertEquals(1, guildHits.size)
        }

    @Test
    fun `commands invoked outside a guild return ephemeral guild-only notice without throwing`() =
        runTest {
            val store = createStore()
            val command = MemoryCommand(store)

            for (subcommand in listOf("list", "delete", "clear")) {
                val (interaction, getContent) =
                    setupInteraction(
                        subcommandName = subcommand,
                        guildId = null,
                        integers = if (subcommand == "delete") mapOf("id" to 1L) else emptyMap(),
                        booleans = if (subcommand == "clear") mapOf("confirm" to true) else emptyMap(),
                    )

                command.handle(interaction)

                coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
                val content = getContent()
                assertNotNull(content)
                assertTrue(content.contains("只能在伺服器中使用"), "Expected guild-only notice for $subcommand")
            }
        }

    @Test
    fun `ChatterPlugin initializes ChatterMemoryService and registers MemoryCommand`() =
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
            assertNotNull(plugin.memoryService)
            assertNotNull(plugin.memoryService?.memoryStore)
            assertNotNull(plugin.memoryService?.memoryCommand)
            plugin.shutdown()
        }

    @Test
    fun `ChatterPlugin degrades fail-open with WARN log when ChatterMemoryService initialization fails`() =
        runTest {
            val pluginLogger = org.slf4j.LoggerFactory.getLogger(ChatterPlugin::class.java) as ch.qos.logback.classic.Logger
            val appender =
                ch.qos.logback.core.read
                    .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
                    .apply { start() }
            pluginLogger.addAppender(appender)

            try {
                val plugin = ChatterPlugin()
                val failingDb = mockk<org.jetbrains.exposed.v1.jdbc.Database>(relaxed = true)
                val context =
                    DefaultPluginContext(
                        kord = mockk(relaxed = true),
                        routing = mockk(relaxed = true),
                        database = failingDb,
                    )
                plugin.initialize(context)

                kotlin.test.assertNull(plugin.memoryService)
                assertNotNull(plugin.service)
                assertTrue(
                    appender.list.any { event ->
                        event.level == ch.qos.logback.classic.Level.WARN &&
                            event.formattedMessage.contains("ChatterMemoryService")
                    },
                    "Expected observable WARN log when ChatterMemoryService initialization fails",
                )
                plugin.shutdown()
            } finally {
                pluginLogger.detachAppender(appender)
            }
        }

    @Test
    fun `clear with confirm true ephemerally hard-deletes all invoking user memories in current guild and returns count`() =
        runTest {
            val store = createStore()
            store.insertMemory("100", MemoryScope.USER, "200", "User1 fact one")
            store.insertMemory("100", MemoryScope.USER, "200", "User1 fact two")
            store.insertMemory("100", MemoryScope.USER, "200", "User1 fact three")
            val otherUser = store.insertMemory("100", MemoryScope.USER, "201", "Other user fact")
            val guildMem = store.insertMemory("100", MemoryScope.GUILD, null, "Guild shared fact")
            val otherGuild = store.insertMemory("101", MemoryScope.USER, "200", "Other guild fact")

            val command = MemoryCommand(store)
            val (interaction, getContent) =
                setupInteraction(
                    subcommandName = "clear",
                    booleans = mapOf("confirm" to true),
                )

            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
            val content = getContent()
            assertNotNull(content)
            assertTrue(content.contains("3"), "Expected deleted count 3 in confirmation, got: $content")
            assertTrue(store.listUserMemories("100", "200").isEmpty(), "All invoking user memories in guild 100 must be deleted")
            assertTrue(
                store
                    .search(
                        guildId = "100",
                        queryText = "User1 fact",
                        scope = SearchScope.USER,
                        targetUserId = "200",
                        recencyExcludeMinutes = 0,
                    ).isEmpty(),
                "Cleared memories must be purged from FTS5",
            )
            assertEquals(listOf(otherUser.id), store.listUserMemories("100", "201").map { it.id })
            assertEquals(listOf(otherGuild.id), store.listUserMemories("101", "200").map { it.id })
            val guildHits =
                store.search(
                    guildId = "100",
                    queryText = "Guild shared",
                    scope = SearchScope.GUILD,
                    recencyExcludeMinutes = 0,
                )
            assertEquals(listOf(guildMem.id), guildHits.map { it.memory.id })
        }

    @Test
    fun `clear with confirm false or missing aborts without deleting and explains confirm true is required`() =
        runTest {
            val store = createStore()
            store.insertMemory("100", MemoryScope.USER, "200", "Keep fact one")
            store.insertMemory("100", MemoryScope.USER, "200", "Keep fact two")

            val command = MemoryCommand(store)

            for (booleans in listOf(mapOf("confirm" to false), emptyMap())) {
                val (interaction, getContent) =
                    setupInteraction(
                        subcommandName = "clear",
                        booleans = booleans,
                    )

                command.handle(interaction)

                coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
                val content = getContent()
                assertNotNull(content)
                assertTrue(content.contains("confirm:true"), "Expected guidance explaining confirm:true is required, got: $content")
                assertEquals(2, store.listUserMemories("100", "200").size, "Memories must not be deleted when confirm is not true")
            }
        }
}
