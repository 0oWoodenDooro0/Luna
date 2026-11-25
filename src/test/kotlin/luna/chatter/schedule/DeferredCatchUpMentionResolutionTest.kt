package luna.chatter.schedule

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Member
import dev.kord.core.entity.Message
import dev.kord.core.entity.Role
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.GuildChannel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterFilterConfig
import luna.chatter.ChatterNoiseFilter
import luna.chatter.CircadianPersonaDrift
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.time.Instant as KotlinInstant

class DeferredCatchUpMentionResolutionTest {
    private val selfId = Snowflake(999999UL)
    private val guildId = Snowflake(500001UL)
    private val channelId = Snowflake(100001UL)
    private val nowInstant = Instant.parse("2026-11-24T12:00:00Z")
    private val fixedClock = Clock.fixed(nowInstant, ZoneOffset.UTC)

    private val availableBlock =
        ScheduleBlock(
            startMinute = 720,
            endMinute = 900,
            activityTitle = "午休滑手機",
            activityDetail = "剛忙完來看看群組",
            statusText = "📱 午休中",
            availability = AvailabilityState.AVAILABLE,
        )

    @Test
    fun `triage resolves mentions with includeMentionUids and unified display names`() =
        runTest {
            val kord =
                mockk<Kord>(relaxed = true).apply {
                    every { selfId } returns this@DeferredCatchUpMentionResolutionTest.selfId
                }
            val aliceMember =
                mockk<Member>(relaxed = true).apply {
                    every { id } returns Snowflake(200001UL)
                    every { username } returns "alice_raw"
                    every { globalName } returns "AliceGlobal"
                    every { effectiveName } returns "AliceNick"
                    every { isBot } returns false
                }
            val bobUser =
                mockk<User>(relaxed = true).apply {
                    every { id } returns Snowflake(200002UL)
                    every { username } returns "bob_raw"
                    every { globalName } returns "BobGlobal"
                    every { isBot } returns false
                }
            val modRole =
                mockk<Role>(relaxed = true).apply {
                    every { id } returns Snowflake(300001UL)
                    every { name } returns "Moderators"
                }
            val generalChannel =
                mockk<GuildChannel>(relaxed = true).apply {
                    every { id } returns Snowflake(400001UL)
                    every { name } returns "general"
                }

            val msg =
                mockk<Message>(relaxed = true).apply {
                    every { id } returns Snowflake(1001UL)
                    every { channelId } returns this@DeferredCatchUpMentionResolutionTest.channelId
                    every { content } returns "Hey <@!200002> and <@999999>, check <@&300001> in <#400001>"
                    every { author } returns aliceMember
                    every { type } returns MessageType.Default
                    every { timestamp } returns KotlinInstant.fromEpochMilliseconds(nowInstant.toEpochMilli() - 60_000L)
                    every { this@apply.kord } returns kord
                    every { attachments } returns emptySet()
                    every { mentionedUserIds } returns setOf(this@DeferredCatchUpMentionResolutionTest.selfId, Snowflake(200002UL))
                    every { mentionedUsers } returns flowOf(bobUser)
                    every { mentionedRoles } returns flowOf(modRole)
                    every { mentionedChannels } returns flowOf(generalChannel)
                    every { referencedMessage } returns null
                    every { data.guildId } returns OptionalSnowflake.Value(guildId)
                    every { data.author.id } returns Snowflake(200001UL)
                    every { data.author.username } returns "alice_raw"
                }

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns
                listOf(
                    CatchUpWatermark(
                        channelId = channelId.toString(),
                        guildId = guildId.toString(),
                        firstPendingMessageId = "1001",
                        firstPendingAtMillis = nowInstant.toEpochMilli() - 600_000L,
                        lastPendingMessageId = "1001",
                        lastPendingAtMillis = nowInstant.toEpochMilli() - 60_000L,
                        updatedAtMillis = nowInstant.toEpochMilli() - 60_000L,
                    ),
                )

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(msg)

            val capturedCandidates = slot<List<CatchUpCandidateMessage>>()
            val triageClient = mockk<DeferredCatchUpTriageClient>()
            coEvery {
                triageClient.triage(capture(capturedCandidates), AvailabilityState.BUSY, availableBlock, 2)
            } returns Result.success(emptyList())

            val config =
                ChatterConfig(
                    apiKey = "test-api-key",
                    filter = ChatterFilterConfig(enabled = true),
                    schedule =
                        ChatterScheduleConfig(
                            enabled = true,
                            catchUpMaxMessages = 30,
                            catchUpMaxAgeHours = 8,
                            catchUpMaxAmbient = 2,
                        ),
                )
            val scheduleProvider =
                object : DailyScheduleProvider {
                    override fun isEnabled(): Boolean = true

                    override fun currentSchedule(): DailySchedule? = null

                    override fun currentBlock(): ScheduleBlock? = availableBlock
                }

            val service =
                DeferredCatchUpService(
                    scheduleStore = store,
                    scheduleProvider = scheduleProvider,
                    triageClient = triageClient,
                    chatterGenerator = mockk(relaxed = true),
                    filter = ChatterNoiseFilter(config = config.filter),
                    contextCollector = mockk<ChatterContextCollector>(relaxed = true),
                    channelPort = mockk(relaxed = true),
                    catchUpChannelPort = catchUpPort,
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 6_000L),
                    personaStateProvider = CircadianPersonaDrift(timezone = "Asia/Taipei", enabled = false, scheduleProvider = scheduleProvider),
                    config = config,
                    timing =
                        CatchUpTimingPort(
                            clock = fixedClock,
                            randomProvider = { _, _ -> 0L },
                            staggerDelayFn = { },
                        ),
                )

            service.onBecomeAvailable(AvailabilityState.BUSY, availableBlock)

            assertEquals(1, capturedCandidates.captured.size)
            val candidate = capturedCandidates.captured.single()
            assertEquals("AliceNick", candidate.authorName)
            assertEquals(
                "Hey @BobGlobal (uid:200002) and @Luna [YOU] (uid:999999), check @Moderators (role) in #general",
                candidate.content,
            )
        }
}
