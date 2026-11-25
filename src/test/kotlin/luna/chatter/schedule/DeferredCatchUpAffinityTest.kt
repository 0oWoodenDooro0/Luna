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
import dev.kord.core.entity.User
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.ChatterChannelPort
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterFilterConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterNoiseFilter
import luna.chatter.CircadianPersonaDrift
import luna.chatter.memory.AffinityTier
import luna.chatter.memory.ChatterMemoryStore
import luna.chatter.memory.UserRelationship
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant as KotlinInstant

class DeferredCatchUpAffinityTest {
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

    private fun mockMessage(
        id: Long,
        content: String,
        authorName: String = "Alice",
        authorId: Long = 200001L,
    ): Message {
        val kord = mockk<Kord>(relaxed = true)
        every { kord.selfId } returns selfId
        val user = mockk<User>(relaxed = true)
        every { user.id } returns Snowflake(authorId.toULong())
        every { user.username } returns authorName
        every { user.isBot } returns false

        val msg = mockk<Message>(relaxed = true)
        every { msg.id } returns Snowflake(id.toULong())
        every { msg.channelId } returns channelId
        every { msg.content } returns content
        every { msg.author } returns user
        every { msg.type } returns MessageType.Default
        every { msg.timestamp } returns KotlinInstant.fromEpochMilliseconds(nowInstant.toEpochMilli() - 60_000L)
        every { msg.kord } returns kord
        every { msg.attachments } returns emptySet()
        every { msg.mentionedUserIds } returns emptySet()
        every { msg.referencedMessage } returns null
        every { msg.data.guildId } returns OptionalSnowflake.Value(guildId)
        every { msg.data.author.id } returns Snowflake(authorId.toULong())
        every { msg.data.author.username } returns authorName
        return msg
    }

    private fun watermark(): CatchUpWatermark =
        CatchUpWatermark(
            channelId = channelId.toString(),
            guildId = guildId.toString(),
            firstPendingMessageId = "1001",
            firstPendingAtMillis = nowInstant.toEpochMilli() - 600_000L,
            lastPendingMessageId = "1003",
            lastPendingAtMillis = nowInstant.toEpochMilli() - 60_000L,
            updatedAtMillis = nowInstant.toEpochMilli() - 60_000L,
        )

    private fun createService(
        scheduleStore: DailyScheduleStore,
        triageClient: DeferredCatchUpTriageClient,
        catchUpChannelPort: CatchUpChannelPort,
        memoryStore: ChatterMemoryStore?,
    ): DeferredCatchUpService {
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

        return DeferredCatchUpService(
            scheduleStore = scheduleStore,
            scheduleProvider = scheduleProvider,
            triageClient = triageClient,
            chatterGenerator = mockk<ChatterGenerator>(relaxed = true),
            filter = ChatterNoiseFilter(config = config.filter),
            contextCollector = mockk<ChatterContextCollector>(relaxed = true),
            channelPort = mockk<ChatterChannelPort>(relaxed = true),
            catchUpChannelPort = catchUpChannelPort,
            burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 6_000L),
            personaStateProvider = CircadianPersonaDrift(timezone = "Asia/Taipei", enabled = false, scheduleProvider = scheduleProvider),
            memoryStore = memoryStore,
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
    fun `resolves unique author affinity tiers from memoryStore and passes them to triageClient`() =
        runTest {
            val msg1 = mockMessage(1001L, "fav 1", authorName = "Alice", authorId = 200001L)
            val msg2 = mockMessage(1002L, "fav 2", authorName = "Alice", authorId = 200001L)
            val msg3 = mockMessage(1003L, "disliked", authorName = "Bob", authorId = 200002L)

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.listPendingCatchUps() } returns listOf(watermark())

            val catchUpPort = mockk<CatchUpChannelPort>()
            coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(msg1, msg2, msg3)

            val memoryStore = mockk<ChatterMemoryStore>()
            var capturedUserIds: Collection<String> = emptyList()
            every {
                memoryStore.listUserRelationships(
                    guildId = guildId.toString(),
                    userIds = any(),
                    now = nowInstant.toEpochMilli(),
                )
            } answers {
                capturedUserIds = secondArg()
                mapOf(
                    "200001" to UserRelationship(guildId.toString(), "200001", affinityScore = 60),
                    "200002" to UserRelationship(guildId.toString(), "200002", affinityScore = -50),
                )
            }

            var capturedCandidates: List<CatchUpCandidateMessage> = emptyList()
            var capturedAffinities: Map<String, AffinityTier> = emptyMap()
            val triageClient = mockk<DeferredCatchUpTriageClient>()
            coEvery {
                triageClient.triage(any(), AvailabilityState.BUSY, availableBlock, 2, any())
            } answers {
                capturedCandidates = firstArg()
                capturedAffinities = arg(4)
                Result.success(emptyList())
            }

            val service =
                createService(
                    scheduleStore = store,
                    triageClient = triageClient,
                    catchUpChannelPort = catchUpPort,
                    memoryStore = memoryStore,
                )

            service.onBecomeAvailable(AvailabilityState.BUSY, availableBlock)

            assertEquals(setOf("200001", "200002"), capturedUserIds.toSet())
            assertEquals(listOf("200001", "200001", "200002"), capturedCandidates.map { it.authorId })
            assertEquals(
                mapOf("200001" to AffinityTier.FAVORITE, "200002" to AffinityTier.DISLIKED),
                capturedAffinities,
            )
        }

    @Test
    fun `fails open to empty affinity map with observable WARN log when memoryStore throws`() =
        runTest {
            val logger = LoggerFactory.getLogger(DeferredCatchUpService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            try {
                val msg1 = mockMessage(1001L, "hello", authorName = "Alice", authorId = 200001L)

                val store = mockk<DailyScheduleStore>(relaxed = true)
                every { store.listPendingCatchUps() } returns listOf(watermark())

                val catchUpPort = mockk<CatchUpChannelPort>()
                coEvery { catchUpPort.fetchMissedMessages(channelId, Snowflake(1001UL), 30) } returns listOf(msg1)

                val memoryStore = mockk<ChatterMemoryStore>()
                every {
                    memoryStore.listUserRelationships(guildId.toString(), any(), any())
                } throws IllegalStateException("sqlite busy")

                var capturedAffinities: Map<String, AffinityTier>? = null
                val triageClient = mockk<DeferredCatchUpTriageClient>()
                coEvery {
                    triageClient.triage(any(), AvailabilityState.BUSY, availableBlock, 2, any())
                } answers {
                    capturedAffinities = arg(4)
                    Result.success(emptyList())
                }

                val service =
                    createService(
                        scheduleStore = store,
                        triageClient = triageClient,
                        catchUpChannelPort = catchUpPort,
                        memoryStore = memoryStore,
                    )

                service.onBecomeAvailable(AvailabilityState.BUSY, availableBlock)

                assertEquals(emptyMap(), capturedAffinities)
                assertTrue(
                    appender.list.any {
                        it.level == Level.WARN &&
                            it.formattedMessage.contains("relationships") &&
                            it.formattedMessage.contains("sqlite busy")
                    },
                    "Expected observable WARN log when memoryStore.listUserRelationships throws",
                )
            } finally {
                logger.detachAppender(appender)
            }
        }
}
