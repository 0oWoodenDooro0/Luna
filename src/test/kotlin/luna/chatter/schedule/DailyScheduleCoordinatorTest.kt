package luna.chatter.schedule

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyScheduleCoordinatorTest {
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    private class MutableClock(
        private var currentInstant: Instant,
        private val zoneId: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant

        fun advanceByMillis(millis: Long) {
            currentInstant = currentInstant.plusMillis(millis)
        }
    }

    private fun block(
        startMinute: Int,
        endMinute: Int,
        availability: AvailabilityState,
        title: String = "活動 $startMinute-$endMinute",
        statusText: String = "狀態 $availability",
    ): ScheduleBlock =
        ScheduleBlock(
            startMinute = startMinute,
            endMinute = endMinute,
            activityTitle = title,
            activityDetail = "細節 $title",
            statusText = statusText,
            availability = availability,
        )

    @Test
    fun `start loads persisted today schedule without calling generator and syncs presence`() =
        runTest {
            val today = LocalDate.of(2026, 11, 24)
            val now = ZonedDateTime.of(2026, 11, 24, 10, 30, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)

            val morningBlock = block(0, 720, AvailabilityState.SLEEPING, "早上補眠", "💤 補眠中")
            val afternoonBlock = block(720, 1440, AvailabilityState.AVAILABLE, "下午追番", "📺 追番中")
            val persisted =
                DailySchedule(
                    date = today,
                    timezone = "Asia/Taipei",
                    blocks = listOf(morningBlock, afternoonBlock),
                    summary = "睡飽再看番",
                    createdAtMillis = now.toEpochMilli(),
                )

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(today) } returns persisted
            val generator = mockk<DailyScheduleGenerator>(relaxed = true)
            val presencePort = mockk<SchedulePresencePort>(relaxed = true)
            val firstWaitReached = CompletableDeferred<Unit>()
            val parkGate = CompletableDeferred<Unit>()

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    presencePort = presencePort,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = {
                        firstWaitReached.complete(Unit)
                        parkGate.await()
                    },
                )

            coordinator.start(this)
            firstWaitReached.await()

            assertTrue(coordinator.isEnabled(), "Coordinator should report enabled when configured true")
            assertEquals(persisted, coordinator.currentSchedule())
            assertEquals(morningBlock, coordinator.currentBlock())
            coVerify(exactly = 0) { generator.generate(any(), any()) }
            verify(exactly = 0) { store.saveSchedule(any()) }
            coVerify(exactly = 1) { presencePort.updatePresence(morningBlock) }

            coordinator.shutdown()
        }

    @Test
    fun `start generates and persists today schedule when absent passing previous day summary`() =
        runTest {
            val today = LocalDate.of(2026, 11, 24)
            val now = ZonedDateTime.of(2026, 11, 24, 14, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)

            val fullDay = block(0, 1440, AvailabilityState.AVAILABLE, "自由時間", "🎮 打電動")
            val generated =
                DailySchedule(
                    date = today,
                    timezone = "Asia/Taipei",
                    blocks = listOf(fullDay),
                    summary = "輕鬆打電動",
                    createdAtMillis = now.toEpochMilli(),
                )

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(today) } returns null
            every { store.getPreviousDaySummary(today) } returns "昨天趕稿到很晚"
            val generator = mockk<DailyScheduleGenerator>()
            coEvery { generator.generate(today, "昨天趕稿到很晚") } returns Result.success(generated)
            val presencePort = mockk<SchedulePresencePort>(relaxed = true)
            val firstWaitReached = CompletableDeferred<Unit>()
            val parkGate = CompletableDeferred<Unit>()

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    presencePort = presencePort,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = {
                        firstWaitReached.complete(Unit)
                        parkGate.await()
                    },
                )

            coordinator.start(this)
            firstWaitReached.await()

            assertEquals(generated, coordinator.currentSchedule())
            assertEquals(fullDay, coordinator.currentBlock())
            coVerify(exactly = 1) { generator.generate(today, "昨天趕稿到很晚") }
            verify(exactly = 1) { store.saveSchedule(generated) }
            coVerify(exactly = 1) { presencePort.updatePresence(fullDay) }

            coordinator.shutdown()
        }

    @Test
    fun `generation failure emits ERROR log, retries with exponential backoff capped at 15m, and returns null schedule`() =
        runTest {
            val logger = LoggerFactory.getLogger(DailyScheduleCoordinator::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            try {
                val today = LocalDate.of(2026, 11, 24)
                val now = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, taipeiZone).toInstant()
                val clock = MutableClock(now, taipeiZone)

                val recoveredBlock = block(0, 1440, AvailabilityState.AVAILABLE, "恢復", "✨ 恢復上線")
                val recoveredSchedule =
                    DailySchedule(
                        date = today,
                        timezone = "Asia/Taipei",
                        blocks = listOf(recoveredBlock),
                        summary = "恢復正常",
                        createdAtMillis = now.toEpochMilli(),
                    )

                val store = mockk<DailyScheduleStore>(relaxed = true)
                every { store.getSchedule(today) } returns null
                every { store.getPreviousDaySummary(today) } returns null

                var attempts = 0
                val generator = mockk<DailyScheduleGenerator>()
                coEvery { generator.generate(today, null) } coAnswers {
                    attempts++
                    if (attempts <= 5) {
                        Result.failure(IllegalStateException("Gemini 503"))
                    } else {
                        Result.success(recoveredSchedule)
                    }
                }

                val presencePort = mockk<SchedulePresencePort>(relaxed = true)
                val recordedDelays = mutableListOf<Long>()
                val recoveredGate = CompletableDeferred<Unit>()
                val parkGate = CompletableDeferred<Unit>()
                var coordinator: DailyScheduleCoordinator? = null

                coordinator =
                    DailyScheduleCoordinator(
                        store = store,
                        generator = generator,
                        presencePort = presencePort,
                        enabled = true,
                        timezone = taipeiZone,
                        clock = clock,
                        delayFn = { ms ->
                            if (coordinator?.currentSchedule() == null) {
                                recordedDelays += ms
                                assertNull(coordinator?.currentBlock(), "Must not fabricate fallback block on failure")
                                clock.advanceByMillis(1_000L)
                            } else {
                                recoveredGate.complete(Unit)
                                parkGate.await()
                            }
                        },
                    )

                coordinator.start(this)
                recoveredGate.await()

                assertEquals(
                    listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L),
                    recordedDelays,
                )
                assertTrue(
                    appender.list.any { it.level == Level.ERROR && it.formattedMessage.contains("DailySchedule") },
                    "Expected ERROR log on generation failure for OwnerErrorNotifier",
                )
                assertEquals(recoveredSchedule, coordinator.currentSchedule())
                coVerify(exactly = 1) { presencePort.updatePresence(recoveredBlock) }

                coordinator.shutdown()
            } finally {
                logger.detachAppender(appender)
            }
        }

    @Test
    fun `block transitions update presence at exact boundaries and invoke onBecomeAvailable only from BUSY or SLEEPING into BREAK or AVAILABLE`() =
        runTest {
            val today = LocalDate.of(2026, 11, 24)
            val start = ZonedDateTime.of(2026, 11, 24, 7, 50, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(start, taipeiZone)

            val b0Sleep = block(0, 480, AvailabilityState.SLEEPING, "睡覺", "💤 睡覺")
            val b1Break = block(480, 540, AvailabilityState.BREAK, "早餐", "🥐 吃早餐")
            val b2Busy = block(540, 720, AvailabilityState.BUSY, "趕稿", "🎨 趕稿中")
            val b3Avail = block(720, 780, AvailabilityState.AVAILABLE, "午休聊天", "💬 聊天")
            val b4Break = block(780, 840, AvailabilityState.BREAK, "點心", "🧋 喝珍奶")
            val b5Busy = block(840, 1440, AvailabilityState.BUSY, "外出", "🚶 外出")

            val schedule =
                DailySchedule(
                    date = today,
                    timezone = "Asia/Taipei",
                    blocks = listOf(b0Sleep, b1Break, b2Busy, b3Avail, b4Break, b5Busy),
                    summary = "忙碌的一天",
                    createdAtMillis = start.toEpochMilli(),
                )

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(today) } returns schedule
            val generator = mockk<DailyScheduleGenerator>(relaxed = true)
            val presenceUpdates = mutableListOf<ScheduleBlock>()
            val presencePort =
                object : SchedulePresencePort {
                    override suspend fun updatePresence(block: ScheduleBlock) {
                        presenceUpdates += block
                    }
                }

            val transitions = mutableListOf<Pair<AvailabilityState, ScheduleBlock>>()
            val waitDurations = mutableListOf<Long>()
            val allTransitionsDone = CompletableDeferred<Unit>()
            val parkGate = CompletableDeferred<Unit>()

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    presencePort = presencePort,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = { ms ->
                        waitDurations += ms
                        if (waitDurations.size < 6) {
                            clock.advanceByMillis(ms)
                        } else {
                            allTransitionsDone.complete(Unit)
                            parkGate.await()
                        }
                    },
                    onBecomeAvailable = { prev, cur ->
                        transitions += prev to cur
                    },
                )

            coordinator.start(this)
            allTransitionsDone.await()

            assertEquals(
                listOf(b0Sleep, b1Break, b2Busy, b3Avail, b4Break, b5Busy),
                presenceUpdates,
            )
            assertEquals(
                listOf(
                    10 * 60_000L, // 07:50 -> 08:00
                    60 * 60_000L, // 08:00 -> 09:00
                    180 * 60_000L, // 09:00 -> 12:00
                    60 * 60_000L, // 12:00 -> 13:00
                    60 * 60_000L, // 13:00 -> 14:00
                    600 * 60_000L, // 14:00 -> 24:00
                ),
                waitDurations,
            )
            assertEquals(
                listOf(
                    AvailabilityState.SLEEPING to b1Break,
                    AvailabilityState.BUSY to b3Avail,
                ),
                transitions,
            )

            coordinator.shutdown()
        }

    @Test
    fun `midnight rollover generates new day schedule using previous day summary and syncs presence`() =
        runTest {
            val day1 = LocalDate.of(2026, 11, 24)
            val day2 = LocalDate.of(2026, 11, 25)
            val start = ZonedDateTime.of(2026, 11, 24, 23, 50, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(start, taipeiZone)

            val day1Block = block(0, 1440, AvailabilityState.AVAILABLE, "Day1", "🌙 Day1")
            val day1Schedule = DailySchedule(day1, "Asia/Taipei", listOf(day1Block), "Day1 摘要", start.toEpochMilli())

            val day2Block = block(0, 1440, AvailabilityState.SLEEPING, "Day2", "💤 Day2")
            val day2Schedule = DailySchedule(day2, "Asia/Taipei", listOf(day2Block), "Day2 摘要", start.toEpochMilli())

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(day1) } returns day1Schedule
            every { store.getSchedule(day2) } returns null
            every { store.getPreviousDaySummary(day2) } returns "Day1 摘要"

            val generator = mockk<DailyScheduleGenerator>()
            coEvery { generator.generate(day2, "Day1 摘要") } returns Result.success(day2Schedule)

            val presencePort = mockk<SchedulePresencePort>(relaxed = true)
            val day2Loaded = CompletableDeferred<Unit>()
            val parkGate = CompletableDeferred<Unit>()
            var rolloverCount = 0

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    presencePort = presencePort,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = { ms ->
                        rolloverCount++
                        if (rolloverCount == 1) {
                            assertEquals(10 * 60_000L, ms)
                            clock.advanceByMillis(ms)
                        } else {
                            day2Loaded.complete(Unit)
                            parkGate.await()
                        }
                    },
                )

            coordinator.start(this)
            day2Loaded.await()

            assertEquals(day2Schedule, coordinator.currentSchedule())
            assertEquals(day2Block, coordinator.currentBlock())
            coVerify(exactly = 1) { generator.generate(day2, "Day1 摘要") }
            verify(exactly = 1) { store.saveSchedule(day2Schedule) }
            coVerify(exactly = 1) { presencePort.updatePresence(day1Block) }
            coVerify(exactly = 1) { presencePort.updatePresence(day2Block) }

            coordinator.shutdown()
        }

    @Test
    fun `disabled coordinator does not start or return schedule`() =
        runTest {
            val store = mockk<DailyScheduleStore>(relaxed = true)
            val generator = mockk<DailyScheduleGenerator>(relaxed = true)
            val presencePort = mockk<SchedulePresencePort>(relaxed = true)

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    presencePort = presencePort,
                    enabled = false,
                    timezone = taipeiZone,
                )

            val job = coordinator.start(this)

            assertNull(job)
            assertFalse(coordinator.isEnabled(), "Coordinator should report disabled when configured false")
            assertNull(coordinator.currentSchedule())
            assertNull(coordinator.currentBlock())
            verify(exactly = 0) { store.getSchedule(any()) }
            coVerify(exactly = 0) { generator.generate(any(), any()) }
            coVerify(exactly = 0) { presencePort.updatePresence(any()) }
        }

    @Test
    fun `applyTodayPatches re-syncs presence when only activityType changes on active block`() =
        runTest {
            val today = LocalDate.of(2026, 11, 26)
            val clock = Clock.fixed(ZonedDateTime.of(today, LocalTime.of(14, 0), taipeiZone).toInstant(), taipeiZone)
            val morningBlock =
                ScheduleBlock(
                    startMinute = 0,
                    endMinute = 840,
                    activityTitle = "上午補眠",
                    activityDetail = "睡覺",
                    statusText = "💤 補眠中",
                    availability = AvailabilityState.SLEEPING,
                )
            val initialBlock =
                ScheduleBlock(
                    startMinute = 840,
                    endMinute = 1440,
                    activityTitle = "打遊戲",
                    activityDetail = "探索海拉魯",
                    statusText = "薩爾達傳說",
                    availability = AvailabilityState.AVAILABLE,
                    activityType = PresenceActivityType.CUSTOM,
                )
            val schedule =
                DailySchedule(
                    date = today,
                    timezone = taipeiZone.id,
                    blocks = listOf(morningBlock, initialBlock),
                    summary = "遊戲日",
                    createdAtMillis = clock.millis(),
                )
            val store = mockk<DailyScheduleStore>(relaxed = true)
            val generator = mockk<DailyScheduleGenerator>(relaxed = true)
            val presencePort = mockk<SchedulePresencePort>(relaxed = true)
            val syncedInitial = CompletableDeferred<Unit>()
            every { store.getSchedule(today) } returns schedule
            coEvery { presencePort.updatePresence(any()) } answers {
                if (!syncedInitial.isCompleted) syncedInitial.complete(Unit)
            }

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    presencePort = presencePort,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = { awaitCancellation() },
                )

            coordinator.start(this)
            syncedInitial.await()

            val result =
                coordinator.applyTodayPatches(
                    listOf(
                        SchedulePatch(
                            startMinute = 840,
                            endMinute = 1440,
                            activityTitle = "打遊戲",
                            activityDetail = "探索海拉魯",
                            statusText = "薩爾達傳說",
                            availability = AvailabilityState.AVAILABLE,
                            activityType = PresenceActivityType.PLAYING,
                        ),
                    ),
                )
            assertTrue(
                result is DailyScheduleSplicer.SpliceResult.Success,
                "Expected activityType-only patch on active block to succeed",
            )
            coVerify(exactly = 1) {
                presencePort.updatePresence(match { it.activityType == PresenceActivityType.CUSTOM })
            }
            coVerify(exactly = 1) {
                presencePort.updatePresence(match { it.activityType == PresenceActivityType.PLAYING })
            }

            coordinator.shutdown()
        }

    @Test
    fun `shutdown awaits in-flight blocking store load and skips subsequent store queries`() =
        runBlocking {
            val today = LocalDate.of(2026, 4, 21)
            val clock = MutableClock(ZonedDateTime.of(today, LocalTime.of(10, 0), taipeiZone).toInstant(), taipeiZone)
            val enteredStore = CountDownLatch(1)
            val releaseStore = CountDownLatch(1)
            val storeReturned = AtomicBoolean(false)

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(today) } answers {
                enteredStore.countDown()
                releaseStore.await(2, TimeUnit.SECONDS)
                storeReturned.set(true)
                null
            }
            val generator = mockk<DailyScheduleGenerator>(relaxed = true)
            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = { awaitCancellation() },
                )

            val bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val loopJob = checkNotNull(coordinator.start(bgScope))
                assertTrue(enteredStore.await(2, TimeUnit.SECONDS), "Expected runLoop to enter store.getSchedule")

                val releaser =
                    launch(Dispatchers.IO) {
                        Thread.sleep(50)
                        releaseStore.countDown()
                    }

                coordinator.shutdown()
                assertTrue(storeReturned.get(), "Expected shutdown() to wait until in-flight store call finishes")
                assertTrue(loopJob.isCompleted, "Expected scheduler loopJob to be completed before shutdown() returns")
                releaser.join()

                verify(exactly = 0) { store.getPreviousDaySummary(any()) }
                verify(exactly = 0) { store.listCommitmentsForDate(any()) }
            } finally {
                releaseStore.countDown()
                bgScope.cancel()
            }
        }
}
