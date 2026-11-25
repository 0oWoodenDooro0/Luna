package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Member
import dev.kord.core.entity.Message
import dev.kord.core.entity.Role
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.GuildChannel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import luna.core.BoundedSummaryRegistry
import luna.core.LunaWebhookRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationSliceMentionTest {
    private val selfId = Snowflake(999L)
    private val summaryRegistry = BoundedSummaryRegistry()

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        summaryRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        LunaWebhookRegistry.clear()
        summaryRegistry.clear()
    }

    @Test
    fun `collectSlice resolves mentions cleanly without uids for both multi-message and single-message slices`() =
        runTest {
            val alice =
                mockk<Member>(relaxed = true).apply {
                    every { id } returns Snowflake(101L)
                    every { username } returns "alice_raw"
                    every { globalName } returns "AliceGlobal"
                    every { effectiveName } returns "AliceNick"
                    every { isBot } returns false
                }
            val bob =
                mockk<User>(relaxed = true).apply {
                    every { id } returns Snowflake(202L)
                    every { username } returns "bob_raw"
                    every { globalName } returns "BobGlobal"
                    every { isBot } returns false
                }
            val devRole =
                mockk<Role>(relaxed = true).apply {
                    every { id } returns Snowflake(303L)
                    every { name } returns "Devs"
                }
            val devChannel =
                mockk<GuildChannel>(relaxed = true).apply {
                    every { id } returns Snowflake(404L)
                    every { name } returns "dev-talk"
                }
            val guild =
                mockk<Guild>(relaxed = true).apply {
                    coEvery { getRoleOrNull(Snowflake(303L)) } returns devRole
                    coEvery { getChannelOrNull(Snowflake(404L)) } returns devChannel
                }

            val anchorMsg =
                mockk<Message>(relaxed = true).apply {
                    every { id } returns Snowflake(10L)
                    every { type } returns MessageType.Default
                    every { author } returns alice
                    every { webhookId } returns null
                    every { content } returns "Ask <@202> and <@&303> in <#404>"
                    every { timestamp } returns Instant.fromEpochMilliseconds(1_000_000L)
                    every { mentionedUsers } returns flowOf(bob)
                    every { mentionedRoles } returns flowOf(devRole)
                    every { mentionedChannels } returns flowOf(devChannel)
                    every { kord.selfId } returns selfId
                    coEvery { getGuildOrNull() } returns guild
                }

            val kordMock =
                mockk<Kord>(relaxed = true).apply {
                    every { selfId } returns this@ConversationSliceMentionTest.selfId
                }

            val collector =
                ConversationSliceCollector(
                    noiseFilter = DialogueNoiseFilter(kord = kordMock, summaryRegistry = summaryRegistry),
                    historyFetcher = { _, _ -> emptyList() },
                    forwardFetcher = { _, _ -> emptyList() },
                    referenceFetcher = { null },
                    peekResolver = { null },
                )

            val singleSlice = collector.collectSlice(anchorMsg)
            assertNotNull(singleSlice)
            assertEquals("Ask @BobGlobal and @Devs in #dev-talk", singleSlice.summaryInput.content)
            assertFalse(singleSlice.summaryInput.content.contains("uid:"), "Summary single-message slice must omit uid")

            val secondMsg =
                mockk<Message>(relaxed = true).apply {
                    every { id } returns Snowflake(11L)
                    every { type } returns MessageType.Default
                    every { author } returns bob
                    every { webhookId } returns null
                    every { content } returns "On it <@101>!"
                    every { timestamp } returns Instant.fromEpochMilliseconds(1_001_000L)
                    every { mentionedUsers } returns flowOf(alice)
                    every { mentionedRoles } returns flowOf()
                    every { mentionedChannels } returns flowOf()
                    every { kord.selfId } returns selfId
                    coEvery { getGuildOrNull() } returns guild
                }

            val multiCollector =
                ConversationSliceCollector(
                    noiseFilter = DialogueNoiseFilter(kord = kordMock, summaryRegistry = summaryRegistry),
                    historyFetcher = { _, _ -> emptyList() },
                    forwardFetcher = { _, _ -> listOf(secondMsg) },
                    referenceFetcher = { null },
                    peekResolver = { null },
                )

            val multiSlice = multiCollector.collectSlice(anchorMsg)
            assertNotNull(multiSlice)
            assertTrue(
                multiSlice.summaryInput.content.contains("[AliceNick]: Ask @BobGlobal and @Devs in #dev-talk"),
                "Summary multi-message slice should include AliceNick turn with resolved mentions",
            )
            assertTrue(
                multiSlice.summaryInput.content.contains("[BobGlobal]: On it @AliceNick!"),
                "Summary multi-message slice should include BobGlobal turn with resolved mention",
            )
            assertFalse(multiSlice.summaryInput.content.contains("uid:"), "Summary multi-message slice must omit uid")
        }
}
