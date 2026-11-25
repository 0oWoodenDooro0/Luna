package luna.core.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Member
import dev.kord.core.entity.Message
import dev.kord.core.entity.Role
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.GuildChannel
import dev.kord.core.supplier.EntitySupplyStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import luna.core.BoundedSummaryRegistry
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscordMentionResolutionTest {
    private val selfId = Snowflake(999L)
    private val summaryRegistry = BoundedSummaryRegistry()
    private val engine = DiscordDialogueContextEngine(summaryRegistry = summaryRegistry)

    private fun mockUser(
        id: Long,
        username: String,
        globalName: String? = null,
        isBot: Boolean = false,
    ): User {
        val user = mockk<User>(relaxed = true)
        every { user.id } returns Snowflake(id)
        every { user.username } returns username
        every { user.globalName } returns globalName
        every { user.isBot } returns isBot
        return user
    }

    private fun mockMember(
        id: Long,
        username: String,
        globalName: String? = null,
        effectiveName: String = globalName ?: username,
        isBot: Boolean = false,
    ): Member {
        val member = mockk<Member>(relaxed = true)
        every { member.id } returns Snowflake(id)
        every { member.username } returns username
        every { member.globalName } returns globalName
        every { member.effectiveName } returns effectiveName
        every { member.isBot } returns isBot
        return member
    }

    @Test
    fun `formatDialogueMessages resolves user, self, role, and channel mentions per policy including reply excerpts`() =
        runTest {
            val alice = mockUser(id = 101L, username = "alice_user", globalName = "Alice")
            val bob = mockUser(id = 202L, username = "bob_user", globalName = "Bob")
            val modRole =
                mockk<Role>(relaxed = true).apply {
                    every { id } returns Snowflake(303L)
                    every { name } returns "Moderators"
                }
            val generalChannel =
                mockk<GuildChannel>(relaxed = true).apply {
                    every { id } returns Snowflake(404L)
                    every { name } returns "general-chat"
                }

            val guild =
                mockk<Guild>(relaxed = true).apply {
                    coEvery { getRoleOrNull(Snowflake(303L)) } returns modRole
                    coEvery { getChannelOrNull(Snowflake(404L)) } returns generalChannel
                }

            val refMsg =
                mockk<Message>(relaxed = true).apply {
                    every { id } returns Snowflake(10L)
                    every { author } returns bob
                    every { content } returns "Ask <@101> and <@!999> in <#404>"
                    every { mentionedUsers } returns flowOf(alice)
                    every { mentionedRoles } returns flowOf()
                    every { mentionedChannels } returns flowOf(generalChannel)
                    every { kord.selfId } returns selfId
                    coEvery { getGuildOrNull() } returns guild
                }

            val mainMsg =
                mockk<Message>(relaxed = true).apply {
                    every { id } returns Snowflake(11L)
                    every { author } returns alice
                    every { content } returns "Hey <@!202>, ping <@&303> and <@999> in <#404>"
                    every { mentionedUsers } returns flowOf(bob)
                    every { mentionedRoles } returns flowOf(modRole)
                    every { mentionedChannels } returns flowOf(generalChannel)
                    every { kord.selfId } returns selfId
                    coEvery { getGuildOrNull() } returns guild
                }

            val chatterResult =
                engine.formatDialogueMessages(
                    messages = listOf(mainMsg),
                    policy = DialogueContextPolicy.chatter(),
                    replyReferencesByMessage = mapOf(mainMsg.id to refMsg),
                    kordSelfId = selfId,
                    formatSpeakerHeader = { "${it.name} (uid:${it.authorId})" },
                )

            assertEquals(
                "Alice (uid:101) 回覆 Bob (uid:202)「Ask @Alice (uid:101) and @Luna [YOU] (uid:999) in #general-chat」: " +
                    "Hey @Bob (uid:202), ping @Moderators (role) and @Luna [YOU] (uid:999) in #general-chat",
                chatterResult.transcript,
            )

            val summaryResult =
                engine.formatDialogueMessages(
                    messages = listOf(mainMsg),
                    policy = DialogueContextPolicy.summary(),
                    replyReferencesByMessage = mapOf(mainMsg.id to refMsg),
                    kordSelfId = selfId,
                    formatSpeakerHeader = { it.name },
                )

            assertEquals(
                "[Alice 回覆 Bob「Ask @Alice and @Luna in #general-chat」]: " +
                    "Hey @Bob, ping @Moderators and @Luna in #general-chat",
                summaryResult.transcript,
            )

            // Raw message.content must remain unmutated
            assertEquals("Hey <@!202>, ping <@&303> and <@999> in <#404>", mainMsg.content)
        }

    @Test
    fun `display identity prioritizes effectiveName then globalName then username for speakers and mentions`() =
        runTest {
            val memberAuthor = mockMember(id = 101L, username = "alice_raw", globalName = "AliceGlobal", effectiveName = "AliceNick")
            val globalUserAuthor = mockUser(id = 202L, username = "bob_raw", globalName = "BobGlobal")
            val plainUserAuthor = mockUser(id = 303L, username = "charlie_raw", globalName = null)
            val cachedMemberForPlain = mockMember(id = 303L, username = "charlie_raw", globalName = null, effectiveName = "CharlieCacheNick")

            val cachedGuild =
                mockk<Guild>(relaxed = true).apply {
                    coEvery { getMemberOrNull(Snowflake(303L)) } returns cachedMemberForPlain
                }

            val msg1 =
                mockk<Message>(relaxed = true).apply {
                    every { id } returns Snowflake(1L)
                    every { author } returns memberAuthor
                    every { content } returns "Hi <@202> and <@303>"
                    every { mentionedUsers } returns flowOf(globalUserAuthor)
                    every { mentionedRoles } returns flowOf()
                    every { mentionedChannels } returns flowOf()
                    every { kord.selfId } returns selfId
                    every { withStrategy(EntitySupplyStrategy.cache) } returns
                        mockk<Message>(relaxed = true).apply {
                            coEvery { getGuildOrNull() } returns cachedGuild
                        }
                }
            val msg2 =
                mockk<Message>(relaxed = true).apply {
                    every { id } returns Snowflake(2L)
                    every { author } returns globalUserAuthor
                    every { content } returns "Hello!"
                    every { mentionedUsers } returns flowOf()
                    every { mentionedRoles } returns flowOf()
                    every { mentionedChannels } returns flowOf()
                    every { kord.selfId } returns selfId
                }
            val msg3 =
                mockk<Message>(relaxed = true).apply {
                    every { id } returns Snowflake(3L)
                    every { author } returns plainUserAuthor
                    every { content } returns "Hey!"
                    every { mentionedUsers } returns flowOf()
                    every { mentionedRoles } returns flowOf()
                    every { mentionedChannels } returns flowOf()
                    every { kord.selfId } returns selfId
                }

            val result =
                engine.formatDialogueMessages(
                    messages = listOf(msg1, msg2, msg3),
                    policy = DialogueContextPolicy.chatter(),
                    kordSelfId = selfId,
                    formatSpeakerHeader = { "${it.name} (uid:${it.authorId})" },
                )

            assertEquals(
                listOf(
                    "AliceNick (uid:101): Hi @BobGlobal (uid:202) and @CharlieCacheNick (uid:303)",
                    "BobGlobal (uid:202): Hello!",
                    "CharlieCacheNick (uid:303): Hey!",
                ),
                result.turns,
            )

            // Synchronous resolveSpeaker on plain User falls back to globalName -> username
            assertEquals("AliceNick", engine.resolveSpeaker(msg1, selfId).name)
            assertEquals("BobGlobal", engine.resolveSpeaker(msg2, selfId).name)
            assertEquals("charlie_raw", engine.resolveSpeaker(msg3, selfId).name)
        }

    @Test
    fun `mention resolution deduplicates per slice, caps REST fallbacks, and logs warnings on cap exhaustion or failure`() =
        runTest {
            val logger = LoggerFactory.getLogger(DiscordMentionResolver::class.java) as Logger
            val previousLevel = logger.level
            val listAppender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.WARN
            logger.addAppender(listAppender)

            try {
                val alice = mockUser(id = 101L, username = "alice", globalName = "Alice")
                val bob = mockMember(id = 202L, username = "bob", globalName = "Bob", effectiveName = "BobNick")
                val carol = mockUser(id = 303L, username = "carol", globalName = "Carol")

                val guild =
                    mockk<Guild>(relaxed = true).apply {
                        coEvery { getMemberOrNull(Snowflake(202L)) } returns bob
                        coEvery { getMemberOrNull(Snowflake(303L)) } returns null
                        coEvery { getMemberOrNull(Snowflake(404L)) } throws RuntimeException("Missing Access")
                    }
                val kordMock =
                    mockk<Kord>(relaxed = true).apply {
                        every { selfId } returns this@DiscordMentionResolutionTest.selfId
                        coEvery { getUser(Snowflake(202L), any()) } returns null
                        coEvery { getUser(Snowflake(303L), any()) } returns null
                        coEvery { getUser(Snowflake(404L), any()) } returns null
                    }

                val msg1 =
                    mockk<Message>(relaxed = true).apply {
                        every { id } returns Snowflake(1L)
                        every { author } returns alice
                        every { content } returns "Ping <@202> and <@202> again, plus <@303>"
                        every { mentionedUsers } returns flowOf()
                        every { mentionedRoles } returns flowOf()
                        every { mentionedChannels } returns flowOf()
                        every { kord } returns kordMock
                        coEvery { getGuildOrNull() } returns guild
                    }
                val msg2 =
                    mockk<Message>(relaxed = true).apply {
                        every { id } returns Snowflake(2L)
                        every { author } returns alice
                        every { content } returns "Also <@202> and <@404>"
                        every { mentionedUsers } returns flowOf()
                        every { mentionedRoles } returns flowOf()
                        every { mentionedChannels } returns flowOf()
                        every { kord } returns kordMock
                        coEvery { getGuildOrNull() } returns guild
                    }

                // Cap = 1: 202 resolves once via REST (deduplicated across 3 occurrences), 303 hits cap exhaustion
                val cappedResult =
                    engine.formatDialogueMessages(
                        messages = listOf(msg1, msg2),
                        policy = DialogueContextPolicy.chatter(maxMentionRestFallbacks = 1),
                        kordSelfId = selfId,
                        formatSpeakerHeader = { "${it.name} (uid:${it.authorId})" },
                    )

                assertEquals(
                    listOf(
                        "Alice (uid:101): Ping @BobNick (uid:202) and @BobNick (uid:202) again, plus <@303>",
                        "Alice (uid:101): Also @BobNick (uid:202) and <@404>",
                    ),
                    cappedResult.turns,
                )
                coVerify(exactly = 1) { guild.getMemberOrNull(Snowflake(202L)) }
                coVerify(exactly = 0) { guild.getMemberOrNull(Snowflake(303L)) }
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("cap exhausted")
                    },
                    "Expected WARN log on REST fallback cap exhaustion",
                )

                listAppender.list.clear()

                // Cap = 0 disables REST fallback completely
                val disabledResult =
                    engine.formatDialogueMessages(
                        messages = listOf(msg1),
                        policy = DialogueContextPolicy.chatter(maxMentionRestFallbacks = 0),
                        kordSelfId = selfId,
                        formatSpeakerHeader = { "${it.name} (uid:${it.authorId})" },
                    )
                assertEquals(
                    "Alice (uid:101): Ping <@202> and <@202> again, plus <@303>",
                    disabledResult.transcript,
                )
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("REST fallback disabled")
                    },
                    "Expected WARN log when REST fallback is disabled",
                )

                listAppender.list.clear()

                // Cap = 3: 404 throws -> degrades gracefully to raw token with WARN log
                val errorResult =
                    engine.formatDialogueMessages(
                        messages = listOf(msg2),
                        policy = DialogueContextPolicy.chatter(maxMentionRestFallbacks = 3),
                        kordSelfId = selfId,
                        formatSpeakerHeader = { "${it.name} (uid:${it.authorId})" },
                    )
                assertEquals("Alice (uid:101): Also @BobNick (uid:202) and <@404>", errorResult.transcript)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("Missing Access")
                    },
                    "Expected WARN log on REST lookup failure",
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
                logger.level = previousLevel
            }
        }
}
