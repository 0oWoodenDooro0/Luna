package luna.chatter.schedule

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.ChatterChannelPort
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterDecisionContext
import luna.chatter.ChatterFilterConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterInput
import luna.chatter.ChatterNoiseFilter
import luna.chatter.CircadianPersonaDrift
import luna.chatter.PreparedChatterContext
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant as KotlinInstant

class DeferredCatchUpServiceTest {
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

    private class CapturingTraceRecorder : AiTraceRecorder {
        val records = mutableListOf<AiTraceRecord>()

        override fun isEnabled(): Boolean = true

        override fun startSession(
            pipeline: luna.core.ai.AiTracePipeline,
            trigger: luna.core.ai.AiTraceTrigger,
            discord: luna.core.ai.AiTraceDiscordContext,
        ): AiTraceSession =
            AiTraceSession(
                recorder = this,
                pipeline = pipeline,
                trigger = trigger,
                discord = discord,
            )

        override fun record(trace: AiTraceRecord) {
            records.add(trace)
        }

        override suspend fun flushAndClose(timeoutMs: Long) = Unit
    }

    private fun mockMessage(
        id: Long,
        content: String,
        authorName: String = "Alice",
        authorId: Long = 200001L,
        channel: Snowflake = channelId,
        timestampMillis: Long = nowInstant.toEpochMilli() - 60_000L,
        isBot: Boolean = false,
        mentionsBot: Boolean = false,
    ): Message {
        val kord = mockk<Kord>(relaxed = true)
        every { kord.selfId } returns selfId
        val user = mockk<User>(relaxed = true)
        every { user.id } returns Snowflake(authorId.toULong())
        every { user.username } returns authorName
        every { user.isBot } returns isBot

        val msg = mockk<Message>(relaxed = true)
        every { msg.id } returns Snowflake(id.toULong())
        every { msg.channelId } returns channel
        every { msg.content } returns content
        every { msg.author } returns user
        every { msg.type } returns MessageType.Default
        every { msg.timestamp } returns KotlinInstant.fromEpochMilliseconds(timestampMillis)
        every { msg.kord } returns kord
        every { msg.attachments } returns emptySet()
        every { msg.mentionedUserIds } returns if (mentionsBot) setOf(selfId) else emptySet()
        every { msg.referencedMessage } returns null
        every { msg.data.guildId } returns OptionalSnowflake.Value(guildId)
        every { msg.data.author.id } returns Snowflake(authorId.toULong())
        every { msg.data.author.username } returns authorName
        return msg
    }

    private fun watermark(
        channel: Snowflake = channelId,
        firstId: Long = 1001L,
        lastId: Long = 1003L,
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

    private fun stubScheduleProvider(blockProvider: () -> ScheduleBlock?): DailyScheduleProvider =
        object : DailyScheduleProvider {
            override fun isEnabled(): Boolean = true

            override fun currentSchedule(): DailySchedule? = null

            override fun currentBlock(): ScheduleBlock? = blockProvider()
        }

    private fun createService(
        scheduleStore: DailyScheduleStore,
        scheduleProvider: DailyScheduleProvider = stubScheduleProvider { availableBlock },
        triageClient: DeferredCatchUpTriageClient,
        chatterGenerator: ChatterGenerator = mockk(relaxed = true),
        channelPort: ChatterChannelPort = mockk(relaxed = true),
        catchUpChannelPort: CatchUpChannelPort,
        burstCoordinator: BoundedChannelBurstCoordinator =
            BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 6_000L),
        traceRecorder: AiTraceRecorder = CapturingTraceRecorder(),
        config: ChatterConfig =
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
            ),
    ): DeferredCatchUpService {
        val noiseFilter = ChatterNoiseFilter(config = config.filter)
        val contextCollector = mockk<ChatterContextCollector>(relaxed = true)
        every { contextCollector.decisionMessageCount } returns 15
        every { contextCollector.historyMessageCount } returns 15
        coEvery { contextCollector.collectReplyContext(any(), any()) } returns
            PreparedChatterContext(transcript = "Alice: hello")
        coEvery { contextCollector.collectDecisionContext(any(), any()) } returns
            ChatterDecisionContext(transcript = "Bob: meme")

        val personaStateProvider =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                enabled = false,
                scheduleProvider = scheduleProvider,
            )

        return DeferredCatchUpService(
            scheduleStore = scheduleStore,
            scheduleProvider = scheduleProvider,
            triageClient = triageClient,
            chatterGenerator = chatterGenerator,
            filter = noiseFilter,
            contextCollector = contextCollector,
            channelPort = channelPort,
            catchUpChannelPort = catchUpChannelPort,
            burstCoordinator = burstCoordinator,
            personaStateProvider = personaStateProvider,
            traceRecorder = traceRecorder,
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
    fun `delivers REPLY via message reply and REACT via Silent Reaction Path, clears watermark, and records AI Trace`() =
        runTest {
            val mentionMsg = mockMessage(1002L, "@Luna 晚點打遊戲嗎", authorName = "Bob", authorId = 200002L, mentionsBot = true)
            val ambientMsg = mockMessage(1001L, "這個梗圖超好笑", authorName = "Alice", authorId = 200001L, mentionsBot = false)

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns listOf(watermark(channelId, 1001L, 1002L))

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(ambientMsg, mentionMsg)

            val triageClient = mockk<DeferredCatchUpTriageClient>()
            coEvery { triageClient.triage(any(), AvailabilityState.BUSY, availableBlock, 2) } returns
                Result.success(
                    listOf(
                        CatchUpTriageSelection("1001", CatchUpAction.REACT, "funny meme"),
                        CatchUpTriageSelection("1002", CatchUpAction.REPLY, "direct invite"),
                    ),
                )

            val generator = mockk<ChatterGenerator>(relaxed = true)
            coEvery { generator.generateReply(any<ChatterInput>()) } returns "好啊晚點上線！"
            coEvery { generator.selectReactionEmoji(any()) } returns "😂"

            val channelPort = mockk<ChatterChannelPort>(relaxed = true)
            coEvery { channelPort.fetchMessagesBefore(any(), any()) } returns emptyList()
            coEvery { channelPort.fetchMessagesAfter(any(), any()) } returns emptyList()

            val deliveredOrder = mutableListOf<String>()
            coEvery { channelPort.sendReply(mentionMsg, any()) } answers {
                deliveredOrder.add("REPLY:1002")
                Unit
            }
            coEvery { channelPort.addReaction(ambientMsg, any()) } answers {
                deliveredOrder.add("REACT:1001")
                Unit
            }

            val traceRecorder = CapturingTraceRecorder()
            val service =
                createService(
                    scheduleStore = store,
                    triageClient = triageClient,
                    chatterGenerator = generator,
                    channelPort = channelPort,
                    catchUpChannelPort = catchUpPort,
                    traceRecorder = traceRecorder,
                )

            service.onBecomeAvailable(AvailabilityState.BUSY, availableBlock)

            // Direct mention delivered first via sendReply (message.reply), then ambient via Silent Reaction Path
            assertEquals(listOf("REPLY:1002", "REACT:1001"), deliveredOrder)
            coVerify(exactly = 1) { channelPort.sendReply(mentionMsg, "好啊晚點上線！") }
            coVerify(exactly = 0) { channelPort.sendMessage(any(), any()) }
            coVerify(exactly = 1) { channelPort.addReaction(ambientMsg, ReactionEmoji.Unicode("😂")) }
            verify(exactly = 1) { store.clearCatchUpWatermark(channelId.toString()) }
            assertTrue(
                traceRecorder.records.any {
                    it.scheduleBlockTitle == availableBlock.activityTitle &&
                        it.scheduleAvailability == AvailabilityState.AVAILABLE.name &&
                        it.outcome == AiTraceOutcome.SUCCESS
                },
                "Expected AI Trace records with schedule metadata",
            )
        }

    @Test
    fun `excludes messages older than catchUpMaxAgeHours and skips triage when all are stale`() =
        runTest {
            val staleTimestamp = nowInstant.toEpochMilli() - (9 * 3_600_000L) // 9h ago (> 8h max)
            val staleMsg = mockMessage(1001L, "超久以前的訊息", timestampMillis = staleTimestamp)

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns listOf(watermark(channelId, 1001L, 1001L))

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(staleMsg)

            val triageClient = mockk<DeferredCatchUpTriageClient>(relaxed = true)
            val service =
                createService(
                    scheduleStore = store,
                    triageClient = triageClient,
                    catchUpChannelPort = catchUpPort,
                )

            service.onBecomeAvailable(AvailabilityState.SLEEPING, availableBlock)

            coVerify(exactly = 0) { triageClient.triage(any(), any(), any(), any()) }
            verify(exactly = 1) { store.clearCatchUpWatermark(channelId.toString()) }
        }

    @Test
    fun `caps messages passed to triage at catchUpMaxMessages keeping newest messages`() =
        runTest {
            val messages = (1L..5L).map { id -> mockMessage(1000L + id, "msg $id") }
            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns listOf(watermark(channelId, 1001L, 1005L))

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 3) } returns messages

            var capturedCandidates: List<CatchUpCandidateMessage> = emptyList()
            val triageClient = mockk<DeferredCatchUpTriageClient>()
            coEvery { triageClient.triage(any(), any(), any(), any()) } answers {
                capturedCandidates = firstArg()
                Result.success(emptyList())
            }

            val config =
                ChatterConfig(
                    apiKey = "test-api-key",
                    filter = ChatterFilterConfig(enabled = true),
                    schedule = ChatterScheduleConfig(enabled = true, catchUpMaxMessages = 3, catchUpMaxAgeHours = 8, catchUpMaxAmbient = 2),
                )
            val service =
                createService(
                    scheduleStore = store,
                    triageClient = triageClient,
                    catchUpChannelPort = catchUpPort,
                    config = config,
                )

            service.onBecomeAvailable(AvailabilityState.BUSY, availableBlock)

            assertEquals(listOf("1003", "1004", "1005"), capturedCandidates.map { it.messageId })
            verify(exactly = 1) { store.clearCatchUpWatermark(channelId.toString()) }
        }

    @Test
    fun `discards invalid triage messageIds and enforces catchUpMaxAmbient cap defensively`() =
        runTest {
            val msg1 = mockMessage(1001L, "ambient 1")
            val msg2 = mockMessage(1002L, "ambient 2")
            val msg3 = mockMessage(1003L, "ambient 3")

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns listOf(watermark(channelId, 1001L, 1003L))

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(msg1, msg2, msg3)

            val triageClient = mockk<DeferredCatchUpTriageClient>()
            coEvery { triageClient.triage(any(), any(), any(), 1) } returns
                Result.success(
                    listOf(
                        CatchUpTriageSelection("999999", CatchUpAction.REPLY), // out of range
                        CatchUpTriageSelection("1001", CatchUpAction.REPLY),
                        CatchUpTriageSelection("1002", CatchUpAction.REPLY), // exceeds maxAmbient=1
                    ),
                )

            val generator = mockk<ChatterGenerator>(relaxed = true)
            coEvery { generator.generateReply(any<ChatterInput>()) } returns "收到！"

            val channelPort = mockk<ChatterChannelPort>(relaxed = true)
            coEvery { channelPort.fetchMessagesBefore(any(), any()) } returns emptyList()

            val config =
                ChatterConfig(
                    apiKey = "test-api-key",
                    filter = ChatterFilterConfig(enabled = true),
                    schedule = ChatterScheduleConfig(enabled = true, catchUpMaxMessages = 30, catchUpMaxAgeHours = 8, catchUpMaxAmbient = 1),
                )

            val service =
                createService(
                    scheduleStore = store,
                    triageClient = triageClient,
                    chatterGenerator = generator,
                    channelPort = channelPort,
                    catchUpChannelPort = catchUpPort,
                    config = config,
                )

            service.onBecomeAvailable(AvailabilityState.BUSY, availableBlock)

            coVerify(exactly = 1) { channelPort.sendReply(msg1, "收到！") }
            coVerify(exactly = 0) { channelPort.sendReply(msg2, any()) }
            coVerify(exactly = 0) { channelPort.sendReply(msg3, any()) }
        }

    @Test
    fun `aborts remaining deliveries mid-batch when schedule transitions back to BUSY`() =
        runTest {
            var currentBlock: ScheduleBlock = availableBlock
            val msg1 = mockMessage(1001L, "@Luna 第一則", mentionsBot = true)
            val msg2 = mockMessage(1002L, "@Luna 第二則", mentionsBot = true)

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
            coEvery { generator.generateReply(any<ChatterInput>()) } returns "先回你這則！"

            val channelPort = mockk<ChatterChannelPort>(relaxed = true)
            coEvery { channelPort.fetchMessagesBefore(any(), any()) } returns emptyList()
            coEvery { channelPort.sendReply(msg1, any()) } answers {
                // Transition back to BUSY immediately after first reply
                currentBlock = busyBlock
                Unit
            }

            val service =
                createService(
                    scheduleStore = store,
                    scheduleProvider = stubScheduleProvider { currentBlock },
                    triageClient = triageClient,
                    chatterGenerator = generator,
                    channelPort = channelPort,
                    catchUpChannelPort = catchUpPort,
                )

            service.onBecomeAvailable(AvailabilityState.SLEEPING, availableBlock)

            coVerify(exactly = 1) { channelPort.sendReply(msg1, "先回你這則！") }
            coVerify(exactly = 0) { channelPort.sendReply(msg2, any()) }
            coVerify(exactly = 0) { catchUpPort.fetchMissedMessages(channelId2, any(), any()) }
            verify(exactly = 0) { store.clearCatchUpWatermark(channelId2.toString()) }
        }

    @Test
    fun `coordinates with BoundedChannelBurstCoordinator so live incoming chat never collides`() =
        runTest {
            val msg1 = mockMessage(1001L, "@Luna 剛剛問你的事", authorId = 200001L, mentionsBot = true)
            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns listOf(watermark(channelId, 1001L, 1001L))

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(msg1)

            val triageClient = mockk<DeferredCatchUpTriageClient>()
            coEvery { triageClient.triage(any(), any(), any(), any()) } returns
                Result.success(listOf(CatchUpTriageSelection("1001", CatchUpAction.REPLY)))

            val generator = mockk<ChatterGenerator>(relaxed = true)
            coEvery { generator.generateReply(any<ChatterInput>()) } returns "回來了！"

            val events = mutableListOf<String>()
            val liveDispatchStarted = CompletableDeferred<Unit>()
            val releaseLiveDispatch = CompletableDeferred<Unit>()

            val channelPort = mockk<ChatterChannelPort>(relaxed = true)
            coEvery { channelPort.fetchMessagesBefore(any(), any()) } returns emptyList()
            coEvery { channelPort.sendReply(msg1, any()) } answers {
                events.add("catchup_sent")
                Unit
            }

            val burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 6_000L)
            val service =
                createService(
                    scheduleStore = store,
                    triageClient = triageClient,
                    chatterGenerator = generator,
                    channelPort = channelPort,
                    catchUpChannelPort = catchUpPort,
                    burstCoordinator = burstCoordinator,
                )

            // Simulate live incoming message currently dispatching in the same channel
            val liveJob =
                launch {
                    burstCoordinator.executeWithDebounce(
                        channelId = channelId,
                        messageId = Snowflake(5001UL),
                        authorId = Snowflake(300001UL),
                        isDirectMention = true,
                    ) { _, markDispatching ->
                        assertTrue(markDispatching(), "Expected live chat dispatch lock to succeed")
                        events.add("live_dispatching")
                        liveDispatchStarted.complete(Unit)
                        releaseLiveDispatch.await()
                        events.add("live_done")
                        true
                    }
                }

            liveDispatchStarted.await()
            val catchUpJob =
                launch {
                    service.onBecomeAvailable(AvailabilityState.BUSY, availableBlock)
                }

            releaseLiveDispatch.complete(Unit)
            liveJob.join()
            catchUpJob.join()

            assertEquals(listOf("live_dispatching", "live_done", "catchup_sent"), events)
        }

    @Test
    fun `logs observable WARN on failed triage without crashing and continues to next channel`() =
        runTest {
            val logger = LoggerFactory.getLogger(DeferredCatchUpService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            try {
                val msg1 = mockMessage(1001L, "channel 1 msg", channel = channelId)
                val msg2 = mockMessage(2001L, "channel 2 msg", channel = channelId2)

                val store = mockk<DailyScheduleStore>(relaxed = true)
                every { store.listPendingCatchUps() } returns
                    listOf(watermark(channelId, 1001L, 1001L), watermark(channelId2, 2001L, 2001L))

                val catchUpPort = mockk<CatchUpChannelPort>()
                coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(msg1)
                coEvery { catchUpPort.fetchMissedMessages(channelId2, Snowflake(2001UL), 30) } returns listOf(msg2)

                val triageClient = mockk<DeferredCatchUpTriageClient>()
                coEvery {
                    triageClient.triage(match { it.firstOrNull()?.messageId == "1001" }, any(), any(), any())
                } returns Result.failure(IllegalStateException("Gemini 503"))
                coEvery {
                    triageClient.triage(match { it.firstOrNull()?.messageId == "2001" }, any(), any(), any())
                } returns Result.success(emptyList())

                val service =
                    createService(
                        scheduleStore = store,
                        triageClient = triageClient,
                        catchUpChannelPort = catchUpPort,
                    )

                service.onBecomeAvailable(AvailabilityState.BUSY, availableBlock)

                assertTrue(
                    appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("triage") },
                    "Expected observable WARN log on degraded/failed triage",
                )
                // Channel 2 still processed and watermark cleared despite channel 1 triage failure
                verify(exactly = 0) { store.clearCatchUpWatermark(channelId.toString()) }
                verify(exactly = 1) { store.clearCatchUpWatermark(channelId2.toString()) }
            } finally {
                logger.detachAppender(appender)
            }
        }
}
