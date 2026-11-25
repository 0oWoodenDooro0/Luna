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
import luna.core.LunaHost
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

class MemoryCommandRelationshipTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createStore(clock: Clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC)): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("memory-cmd-rel-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    private fun setupInteraction(
        subcommandName: String,
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
                every { guildId } returns OptionalSnowflake.Value(Snowflake(100L))
            }
        val mockUser =
            mockk<User>(relaxed = true) {
                every { id } returns Snowflake(200L)
            }
        val mockSubCommand =
            mockk<SubCommand>(relaxed = true) {
                every { name } returns subcommandName
                every { this@mockk.integers } returns emptyMap()
                every { this@mockk.booleans } returns booleans
                every { this@mockk.strings } returns emptyMap()
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
    fun `list keeps User Relationship hidden even when invoking user has non-neutral affinity`() =
        runTest {
            val store = createStore()
            store.applyRelationshipUpdate("100", "200", affinityDelta = -15, toneStyle = "curt")
            store.applyRelationshipUpdate("100", "200", affinityDelta = -15, toneStyle = "curt")

            val command = MemoryCommand(store)
            val (interaction, getContent) = setupInteraction(subcommandName = "list")
            command.handle(interaction)

            coVerify(exactly = 1) { interaction.deferEphemeralResponse() }
            val content = getContent()
            assertNotNull(content)
            assertTrue(content.contains("尚未有任何長期記憶"), "Relationship must remain hidden from /memory list, got: $content")
            assertFalse(content.contains("-30"), "Raw affinity score must not appear in /memory list")
            assertFalse(content.contains("COOL"), "AffinityTier must not appear in /memory list")
            assertFalse(content.contains("curt"), "Relationship toneStyle must not appear in /memory list")
        }

    @Test
    fun `clear with confirm true resets invoking user relationship in current guild alongside facts and understandings`() =
        runTest {
            val store = createStore()
            store.insertMemory("100", MemoryScope.USER, "200", "Fact one")
            store.insertUnderstanding("100", MemoryScope.USER, "200", "風格", "Understanding one")
            store.applyRelationshipUpdate("100", "200", affinityDelta = -15, toneStyle = "distant")
            store.applyRelationshipUpdate("100", "201", affinityDelta = 15, toneStyle = "warm")
            store.applyRelationshipUpdate("101", "200", affinityDelta = 15, toneStyle = "playful")

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
            assertTrue(store.listUserMemories("100", "200").isEmpty(), "User facts in current guild must be deleted")
            assertTrue(store.listUserUnderstandings("100", "200").isEmpty(), "User understandings in current guild must be deleted")
            assertEquals(0, store.getUserRelationship("100", "200").affinityScore)
            assertEquals("", store.getUserRelationship("100", "200").toneStyle)
            assertEquals(AffinityTier.NEUTRAL, store.getUserRelationship("100", "200").tier)
            assertEquals(15, store.getUserRelationship("100", "201").affinityScore)
            assertEquals(15, store.getUserRelationship("101", "200").affinityScore)
        }
}
