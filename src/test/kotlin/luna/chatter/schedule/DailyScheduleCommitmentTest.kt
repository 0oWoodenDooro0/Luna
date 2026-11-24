package luna.chatter.schedule

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import luna.core.LunaHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DailyScheduleCommitmentTest {
    @TempDir
    lateinit var tempDir: Path

    private val taipeiZone = ZoneId.of("Asia/Taipei")

    private class MutableClock(
        private var currentInstant: Instant,
        private val zoneId: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant

        fun advanceMinutes(minutes: Long) {
            currentInstant = currentInstant.plusSeconds(minutes * 60)
        }
    }

    private fun newCoordinator(
        clock: Clock,
        generator: DailyScheduleGenerator = io.mockk.mockk(relaxed = true),
    ): Pair<SqliteDailyScheduleStore, DailyScheduleCoordinator> {
        val dbPath = tempDir.resolve("coord-commit-${System.nanoTime()}.db").toString()
        val vecPath = tempDir.resolve("missing-vec0.so").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = vecPath)
        val store = SqliteDailyScheduleStore(database = db, clock = clock, timezone = taipeiZone)
        val coordinator =
            DailyScheduleCoordinator(
                store = store,
                generator = generator,
                enabled = true,
                timezone = taipeiZone,
                clock = clock,
            )
        return store to coordinator
    }

    @Test
    fun `upserts, updates in-place, lists upcoming, and cancels ScheduleCommitments via coordinator`() =
        runTest {
            val now = ZonedDateTime.of(2026, 11, 24, 15, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)
            val (_, coordinator) = newCoordinator(clock)

            val tomorrow = LocalDate.of(2026, 11, 25)
            val createResult =
                coordinator.upsertCommitment(
                    targetDate = tomorrow,
                    startMinute = 1140,
                    endMinute = 1260,
                    activityTitle = "打瓦",
                    activityDetail = "跟群友五排",
                    statusText = "🎮 打瓦中",
                    availability = AvailabilityState.AVAILABLE,
                )
            val created = assertIs<DailyScheduleCoordinator.CommitmentResult.Upserted>(createResult).commitment
            assertEquals(tomorrow, created.targetDate)
            assertEquals(1, coordinator.upcomingCommitments().size)

            // Update existing commitment in-place
            val updateResult =
                coordinator.upsertCommitment(
                    id = created.id,
                    targetDate = tomorrow,
                    startMinute = 1200,
                    endMinute = 1320,
                    activityTitle = "打 APEX",
                    activityDetail = "改打 APEX",
                    statusText = "🎮 APEX",
                    availability = AvailabilityState.AVAILABLE,
                )
            val updated = assertIs<DailyScheduleCoordinator.CommitmentResult.Upserted>(updateResult).commitment
            assertEquals(created.id, updated.id)
            assertEquals("打 APEX", updated.activityTitle)
            assertEquals(1, coordinator.upcomingCommitments().size)

            val cancelResult = coordinator.cancelCommitment(created.id)
            assertIs<DailyScheduleCoordinator.CommitmentResult.Cancelled>(cancelResult)
            assertTrue(coordinator.upcomingCommitments().isEmpty(), "Cancelled commitment must no longer appear in upcomingCommitments()")

            val cancelMissing = coordinator.cancelCommitment(created.id)
            assertIs<DailyScheduleCoordinator.CommitmentResult.Rejected>(cancelMissing)
        }

    @Test
    fun `rejects commitments for today, past dates, or more than 7 days ahead`() =
        runTest {
            val now = ZonedDateTime.of(2026, 11, 24, 15, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)
            val (_, coordinator) = newCoordinator(clock)

            val today = LocalDate.of(2026, 11, 24)
            val past = LocalDate.of(2026, 11, 23)
            val day7 = LocalDate.of(2026, 12, 1)
            val day8 = LocalDate.of(2026, 12, 2)

            val todayRes =
                coordinator.upsertCommitment(
                    targetDate = today,
                    startMinute = 1200,
                    endMinute = 1260,
                    activityTitle = "今天",
                    activityDetail = "今天不行",
                    statusText = "✨",
                    availability = AvailabilityState.AVAILABLE,
                )
            assertIs<DailyScheduleCoordinator.CommitmentResult.Rejected>(todayRes)

            val pastRes =
                coordinator.upsertCommitment(
                    targetDate = past,
                    startMinute = 1200,
                    endMinute = 1260,
                    activityTitle = "昨天",
                    activityDetail = "昨天不行",
                    statusText = "✨",
                    availability = AvailabilityState.AVAILABLE,
                )
            assertIs<DailyScheduleCoordinator.CommitmentResult.Rejected>(pastRes)

            val day8Res =
                coordinator.upsertCommitment(
                    targetDate = day8,
                    startMinute = 1200,
                    endMinute = 1260,
                    activityTitle = "太遠",
                    activityDetail = "超過七天",
                    statusText = "✨",
                    availability = AvailabilityState.AVAILABLE,
                )
            assertIs<DailyScheduleCoordinator.CommitmentResult.Rejected>(day8Res)

            // Exactly +7 days is allowed
            val day7Res =
                coordinator.upsertCommitment(
                    targetDate = day7,
                    startMinute = 1200,
                    endMinute = 1260,
                    activityTitle = "第七天",
                    activityDetail = "剛好七天",
                    statusText = "✨",
                    availability = AvailabilityState.AVAILABLE,
                )
            assertIs<DailyScheduleCoordinator.CommitmentResult.Upserted>(day7Res)
        }

    @Test
    fun `rejects commitments exceeding BUSY 4h or SLEEPING 8h caps`() =
        runTest {
            val now = ZonedDateTime.of(2026, 11, 24, 15, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)
            val (_, coordinator) = newCoordinator(clock)
            val tomorrow = LocalDate.of(2026, 11, 25)

            val busyTooLong =
                coordinator.upsertCommitment(
                    targetDate = tomorrow,
                    startMinute = 600,
                    endMinute = 600 + 241,
                    activityTitle = "超長忙碌",
                    activityDetail = "超過四小時",
                    statusText = "🎨",
                    availability = AvailabilityState.BUSY,
                )
            assertIs<DailyScheduleCoordinator.CommitmentResult.Rejected>(busyTooLong)

            val sleepingTooLong =
                coordinator.upsertCommitment(
                    targetDate = tomorrow,
                    startMinute = 0,
                    endMinute = 481,
                    activityTitle = "睡太久",
                    activityDetail = "超過八小時",
                    statusText = "💤",
                    availability = AvailabilityState.SLEEPING,
                )
            assertIs<DailyScheduleCoordinator.CommitmentResult.Rejected>(sleepingTooLong)
        }

    @Test
    fun `enforces 10 active commitments cap while allowing in-place update of existing commitment`() =
        runTest {
            val now = ZonedDateTime.of(2026, 11, 24, 15, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)
            val (store, coordinator) = newCoordinator(clock)

            val ids = mutableListOf<String>()
            // Seed 9 commitments directly in store so we don't exhaust the 10/day rate limiter during setup
            for (i in 0 until 9) {
                val id = "seed-$i"
                store.upsertCommitment(
                    ScheduleCommitment(
                        id = id,
                        targetDate = LocalDate.of(2026, 11, 25).plusDays((i % 7).toLong()),
                        startMinute = 600 + (i * 30),
                        endMinute = 630 + (i * 30),
                        activityTitle = "約定 $i",
                        activityDetail = "細節 $i",
                        statusText = "✨ $i",
                        availability = AvailabilityState.AVAILABLE,
                        createdAtMillis = clock.millis(),
                    ),
                )
                ids.add(id)
            }

            // 10th via coordinator succeeds
            val tenth =
                coordinator.upsertCommitment(
                    targetDate = LocalDate.of(2026, 11, 25),
                    startMinute = 900,
                    endMinute = 930,
                    activityTitle = "第 10 個",
                    activityDetail = "第十個",
                    statusText = "✨ 10",
                    availability = AvailabilityState.AVAILABLE,
                )
            assertIs<DailyScheduleCoordinator.CommitmentResult.Upserted>(tenth)

            // 11th is rejected due to 10-commitment cap
            val eleventh =
                coordinator.upsertCommitment(
                    targetDate = LocalDate.of(2026, 11, 26),
                    startMinute = 1000,
                    endMinute = 1060,
                    activityTitle = "第 11 個",
                    activityDetail = "應該被拒絕",
                    statusText = "❌",
                    availability = AvailabilityState.AVAILABLE,
                )
            val rejected = assertIs<DailyScheduleCoordinator.CommitmentResult.Rejected>(eleventh)
            assertTrue(rejected.reason.contains("10"), "Expected rejection reason to mention 10-commitment cap: ${rejected.reason}")

            // Updating an existing commitment by id still succeeds at cap
            val updateExisting =
                coordinator.upsertCommitment(
                    id = ids.first(),
                    targetDate = LocalDate.of(2026, 11, 25),
                    startMinute = 600,
                    endMinute = 645,
                    activityTitle = "更新第一個",
                    activityDetail = "更新細節",
                    statusText = "✨ 更新",
                    availability = AvailabilityState.AVAILABLE,
                )
            assertIs<DailyScheduleCoordinator.CommitmentResult.Upserted>(updateExisting)
        }

    @Test
    fun `rejects commitment mutations exceeding 3 per 10 minutes rate limit`() =
        runTest {
            val now = ZonedDateTime.of(2026, 11, 24, 15, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)
            val (_, coordinator) = newCoordinator(clock)
            val tomorrow = LocalDate.of(2026, 11, 25)

            repeat(3) { i ->
                val res =
                    coordinator.upsertCommitment(
                        targetDate = tomorrow,
                        startMinute = 600 + i * 60,
                        endMinute = 630 + i * 60,
                        activityTitle = "約定 $i",
                        activityDetail = "細節 $i",
                        statusText = "✨ $i",
                        availability = AvailabilityState.AVAILABLE,
                    )
                assertIs<DailyScheduleCoordinator.CommitmentResult.Upserted>(res)
            }

            val fourth =
                coordinator.upsertCommitment(
                    targetDate = tomorrow,
                    startMinute = 900,
                    endMinute = 930,
                    activityTitle = "第四次",
                    activityDetail = "超過頻率",
                    statusText = "❌",
                    availability = AvailabilityState.AVAILABLE,
                )
            val rejected = assertIs<DailyScheduleCoordinator.CommitmentResult.Rejected>(fourth)
            assertTrue(
                rejected.reason.contains("rate limit", ignoreCase = true),
                "Expected rejection reason to mention rate limit: ${rejected.reason}",
            )
        }

    @Test
    fun `rollover generation injects commitments into generator and deterministically splices them post-generation`() =
        runTest {
            val today = LocalDate.of(2026, 11, 25)
            val now = ZonedDateTime.of(2026, 11, 25, 0, 5, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)

            val commitment =
                ScheduleCommitment(
                    id = "c-rollover",
                    targetDate = today,
                    startMinute = 1140,
                    endMinute = 1260,
                    activityTitle = "跟群友打瓦",
                    activityDetail = "約好晚上七點五排",
                    statusText = "🎮 打瓦中",
                    availability = AvailabilityState.BUSY,
                    createdAtMillis = now.minusSeconds(3600).toEpochMilli(),
                )

            // Generator completely ignores the commitment and outputs a single 00:00..24:00 AVAILABLE block
            val ignoredLlmSchedule =
                DailySchedule(
                    date = today,
                    timezone = "Asia/Taipei",
                    blocks =
                        listOf(
                            ScheduleBlock(
                                startMinute = 0,
                                endMinute = 1440,
                                activityTitle = "整天耍廢",
                                activityDetail = "自由時間",
                                statusText = "✨ 耍廢",
                                availability = AvailabilityState.AVAILABLE,
                            ),
                        ),
                    summary = "自由自在的一天",
                    createdAtMillis = now.toEpochMilli(),
                )

            val generator = mockk<DailyScheduleGenerator>()
            coEvery { generator.generate(today, any(), any()) } returns Result.success(ignoredLlmSchedule)

            val (store, coordinator) = newCoordinator(clock, generator)
            store.upsertCommitment(commitment)

            val firstWaitEntered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coord =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = {
                        firstWaitEntered.complete(Unit)
                        gate.await()
                    },
                )

            coord.start(this)
            firstWaitEntered.await()

            coVerify(exactly = 1) {
                generator.generate(today, null, listOf(commitment))
            }

            val persisted = store.getSchedule(today)
            assertNotNull(persisted)
            assertEquals(3, persisted.blocks.size)
            val anchored = persisted.blocks[1]
            assertEquals(1140, anchored.startMinute)
            assertEquals(1260, anchored.endMinute)
            assertEquals("跟群友打瓦", anchored.activityTitle)
            assertEquals(AvailabilityState.BUSY, anchored.availability)
            assertTrue(anchored.adjustedByConversation, "Anchored commitment block must be marked adjustedByConversation = true")
            assertEquals(false, persisted.blocks[0].adjustedByConversation)
            assertEquals(false, persisted.blocks[2].adjustedByConversation)

            gate.complete(Unit)
            coord.shutdown()
        }

    @Test
    fun `degraded path logs warning and continues generation when loading commitments fails`() =
        runTest {
            val today = LocalDate.of(2026, 11, 25)
            val now = ZonedDateTime.of(2026, 11, 25, 0, 5, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(today) } returns null
            every { store.getPreviousDaySummary(today) } returns null
            every { store.listCommitmentsForDate(today) } throws IllegalStateException("SQLite disk busy")

            val fallbackSchedule =
                DailySchedule(
                    date = today,
                    timezone = "Asia/Taipei",
                    blocks =
                        listOf(
                            ScheduleBlock(0, 1440, "自由時間", "正常生成", "✨", AvailabilityState.AVAILABLE),
                        ),
                    summary = "正常生成",
                    createdAtMillis = now.toEpochMilli(),
                )
            val generator = mockk<DailyScheduleGenerator>()
            coEvery { generator.generate(today, null, emptyList()) } returns Result.success(fallbackSchedule)

            val logger = LoggerFactory.getLogger(DailyScheduleCoordinator::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            val firstWaitEntered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coord =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = {
                        firstWaitEntered.complete(Unit)
                        gate.await()
                    },
                )

            try {
                coord.start(this)
                firstWaitEntered.await()

                val current = coord.currentSchedule()
                assertNotNull(current)
                assertEquals("正常生成", current.summary)
                assertTrue(
                    appender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("ScheduleCommitments")
                    },
                    "Expected WARN log when commitment loading fails",
                )
            } finally {
                logger.detachAppender(appender)
                gate.complete(Unit)
                coord.shutdown()
            }
        }
}
