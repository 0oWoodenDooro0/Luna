package luna.chatter.schedule

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.ChatterChannelPort
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterFilterConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterInput
import luna.chatter.ChatterNoiseFilter
import luna.chatter.CircadianPersonaDrift
import luna.chatter.PreparedChatterContext
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Instant as KotlinInstant

class DeferredCatchUpScheduleEditTest {
    private val selfId = Snowflake(999999UL)
    private val guildId = Snowflake(500001UL)
    private val channelId = Snowflake(100001UL)
    private val channelId2 = Snowflake(100002UL)
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

    private val busyBlock =
        ScheduleBlock(
            startMinute = 900,
            endMinute = 1080,
            activityTitle = "趕稿中",
            activityDetail = "專心畫圖",
            statusText = "🎨 趕稿中",
            availability = AvailabilityState.BUSY,
        )

    private fun mockMessage(
        id: Long,
        content: String,
        channel: Snowflake = channelId,
    ): Message {
        val kord = mockk<Kord>(relaxed = true)
        every { kord.selfId } returns selfId
        val user = mockk<User>(relaxed = true)
        every { user.id } returns Snowflake(200001UL)
        every { user.username } returns "Alice"
        every { user.isBot } returns false

        val msg = mockk<Message>(relaxed = true)
        every { msg.id } returns Snowflake(id.toULong())
        every { msg.channelId } returns channel
        every { msg.content } returns content
        every { msg.author } returns user
        every { msg.type } returns MessageType.Default
        every { msg.timestamp } returns KotlinInstant.fromEpochMilliseconds(nowInstant.toEpochMilli() - 60_000L)
        every { msg.kord } returns kord
        every { msg.attachments } returns emptySet()
        every { msg.mentionedUserIds } returns setOf(selfId)
        every { msg.referencedMessage } returns null
        every { msg.data.guildId } returns OptionalSnowflake.Value(guildId)
        every { msg.data.author.id } returns Snowflake(200001UL)
        every { msg.data.author.username } returns "Alice"
        return msg
    }

    private fun watermark(
        channel: Snowflake,
        firstId: Long,
        lastId: Long,
    ): CatchUpWatermark =
        CatchUpWatermark(
            channelId = channel.toString(),
            guildId = guildId.toString(),
            firstPendingMessageId = firstId.toString(),
            firstPendingAtMillis = nowInstant.toEpochMilli() - 600_000L,
            lastPendingMessageId = lastId.toString(),
            lastPendingAtMillis = nowInstant.toEpochMilli() - 60_000L,
            updatedAtMillis = nowInstant.toEpochMilli() - 60_000L,
        )

    private fun createService(
        scheduleStore: DailyScheduleStore,
        blockProvider: () -> ScheduleBlock?,
        triageClient: DeferredCatchUpTriageClient,
        chatterGenerator: ChatterGenerator,
        channelPort: ChatterChannelPort,
        catchUpChannelPort: CatchUpChannelPort,
    ): DeferredCatchUpService {
        val scheduleProvider =
            object : DailyScheduleProvider {
                override fun isEnabled(): Boolean = true

                override fun currentSchedule(): DailySchedule? = null

                override fun currentBlock(): ScheduleBlock? = blockProvider()
            }
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
        val contextCollector = mockk<ChatterContextCollector>(relaxed = true)
        every { contextCollector.decisionMessageCount } returns 15
        every { contextCollector.historyMessageCount } returns 15
        coEvery { contextCollector.collectReplyContext(any(), any()) } returns
            PreparedChatterContext(transcript = "Alice: hello")

        return DeferredCatchUpService(
            scheduleStore = scheduleStore,
            scheduleProvider = scheduleProvider,
            triageClient = triageClient,
            chatterGenerator = chatterGenerator,
            filter = ChatterNoiseFilter(config = config.filter),
            contextCollector = contextCollector,
            channelPort = channelPort,
            catchUpChannelPort = catchUpChannelPort,
            burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 6_000L),
            personaStateProvider =
                CircadianPersonaDrift(
                    timezone = "Asia/Taipei",
                    enabled = false,
                    scheduleProvider = scheduleProvider,
                ),
            config = config,
            timing =
                CatchUpTimingPort(
                    clock = fixedClock,
                    randomProvider = { _, _ -> 0L },
                    staggerDelayFn = { },
                ),
        )
    }

    @Test
    fun `finishes sending current reply and aborts remaining items when conversation edit switches to BUSY`() =
        runTest {
            var currentBlock: ScheduleBlock = availableBlock
            val msg1 = mockMessage(1001L, "@Luna 要不要直接去忙報告？")
            val msg2 = mockMessage(1002L, "@Luna 第二則順便問一下")

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns listOf(watermark(channelId, 1001L, 1002L), watermark(channelId2, 2001L, 2001L))

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(msg1, msg2)

            val triageClient = mockk<DeferredCatchUpTriageClient>()
            coEvery { triageClient.triage(any(), any(), any(), any()) } returns
                Result.success(
                    listOf(
                        CatchUpTriageSelection("1001", CatchUpAction.REPLY),
                        CatchUpTriageSelection("1002", CatchUpAction.REPLY),
                    ),
                )

            val generator = mockk<ChatterGenerator>(relaxed = true)
            coEvery { generator.generateReply(any<ChatterInput>()) } coAnswers {
                currentBlock = busyBlock.copy(adjustedByConversation = true)
                "好那我先去忙報告，晚點再聊！"
            }

            val channelPort = mockk<ChatterChannelPort>(relaxed = true)
            coEvery { channelPort.fetchMessagesBefore(any(), any()) } returns emptyList()

            val service =
                createService(
                    scheduleStore = store,
                    blockProvider = { currentBlock },
                    triageClient = triageClient,
                    chatterGenerator = generator,
                    channelPort = channelPort,
                    catchUpChannelPort = catchUpPort,
                )

            service.onBecomeAvailable(AvailabilityState.SLEEPING, availableBlock)

            coVerify(exactly = 1) { channelPort.sendReply(msg1, "好那我先去忙報告，晚點再聊！", pingRepliedUser = true) }
            coVerify(exactly = 0) { channelPort.sendReply(msg2, any(), any()) }
            coVerify(exactly = 0) { catchUpPort.fetchMissedMessages(channelId2, any(), any()) }
            verify(exactly = 0) { store.clearCatchUpWatermark(channelId2.toString()) }
        }

    @Test
    fun `aborts current reply before dispatch when scheduled clock boundary rolls into non-adjusted BUSY`() =
        runTest {
            var currentBlock: ScheduleBlock = availableBlock
            val msg1 = mockMessage(1001L, "@Luna 剛剛問你的事")

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns listOf(watermark(channelId, 1001L, 1001L))

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(msg1)

            val triageClient = mockk<DeferredCatchUpTriageClient>()
            coEvery { triageClient.triage(any(), any(), any(), any()) } returns
                Result.success(listOf(CatchUpTriageSelection("1001", CatchUpAction.REPLY)))

            val generator = mockk<ChatterGenerator>(relaxed = true)
            coEvery { generator.generateReply(any<ChatterInput>()) } coAnswers {
                currentBlock = busyBlock
                "這則不應該在排程已進 BUSY 後送出"
            }

            val channelPort = mockk<ChatterChannelPort>(relaxed = true)
            coEvery { channelPort.fetchMessagesBefore(any(), any()) } returns emptyList()

            val service =
                createService(
                    scheduleStore = store,
                    blockProvider = { currentBlock },
                    triageClient = triageClient,
                    chatterGenerator = generator,
                    channelPort = channelPort,
                    catchUpChannelPort = catchUpPort,
                )

            service.onBecomeAvailable(AvailabilityState.SLEEPING, availableBlock)

            coVerify(exactly = 0) { channelPort.sendReply(any(), any(), any()) }
        }
}
