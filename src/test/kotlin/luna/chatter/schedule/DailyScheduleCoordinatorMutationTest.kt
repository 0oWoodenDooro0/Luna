package luna.chatter.schedule

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DailyScheduleCoordinatorMutationTest {
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    private class MutableClock(
        private var currentInstant: Instant,
        private val zoneId: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant
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
    fun `applyTodayPatches persists, wakes runLoop to recalculate boundary timer, and immediately syncs presence`() =
        runTest {
            val today = LocalDate.of(2026, 11, 24)
            // 10:00 AM
            val now = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)

            val morningBlock = block(0, 480, AvailabilityState.SLEEPING, "睡覺")
            val dayBlock = block(480, 1440, AvailabilityState.AVAILABLE, "自由時間", "✨ 閒晃中")
            val initial =
                DailySchedule(
                    date = today,
                    timezone = "Asia/Taipei",
                    blocks = listOf(morningBlock, dayBlock),
                    summary = "原訂計畫",
                    createdAtMillis = now.toEpochMilli(),
                )

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(today) } returns initial
            val generator = mockk<DailyScheduleGenerator>(relaxed = true)
            val presencePort = mockk<SchedulePresencePort>(relaxed = true)

            val recordedWaits = CopyOnWriteArrayList<Long>()
            val firstWaitReached = CompletableDeferred<Unit>()
            val secondWaitReached = CompletableDeferred<Unit>()
            val secondWaitGate = CompletableDeferred<Unit>()

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    presencePort = presencePort,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = { waitMs ->
                        recordedWaits.add(waitMs)
                        when (recordedWaits.size) {
                            1 -> {
                                firstWaitReached.complete(Unit)
                                // Park until woken by wakeSignal
                                CompletableDeferred<Unit>().await()
                            }

                            2 -> {
                                secondWaitReached.complete(Unit)
                                secondWaitGate.await()
                            }

                            else -> {
                                secondWaitGate.await()
                            }
                        }
                    },
                )

            coordinator.start(this)
            firstWaitReached.await()

            // Initial wait is from 10:00 to 24:00 (14 hours)
            assertEquals(14L * 60 * 60 * 1000L, recordedWaits[0])
            coVerify(exactly = 1) { presencePort.updatePresence(dayBlock) }

            // Mutate 10:00..11:30 (600..690) to BUSY
            val result =
                coordinator.applyTodayPatches(
                    listOf(
                        SchedulePatch(
                            startMinute = 600,
                            endMinute = 690,
                            activityTitle = "緊急趕稿",
                            activityDetail = "臨時接了一個急件",
                            statusText = "🎨 趕稿中",
                            availability = AvailabilityState.BUSY,
                        ),
                    ),
                )

            val success = assertIs<DailyScheduleSplicer.SpliceResult.Success>(result)
            secondWaitReached.await()

            // New wait should be 90 minutes (10:00 -> 11:30)
            assertEquals(90L * 60 * 1000L, recordedWaits[1])
            verify(exactly = 1) { store.saveSchedule(success.schedule) }

            val activeBlock = coordinator.currentBlock()
            assertNotNull(activeBlock)
            assertEquals("緊急趕稿", activeBlock.activityTitle)
            assertEquals(AvailabilityState.BUSY, activeBlock.availability)
            assertTrue(activeBlock.adjustedByConversation, "Mutated block should be marked adjustedByConversation")

            // Presence synced exactly once for the new active block (not duplicated by woken runLoop)
            coVerify(exactly = 1) { presencePort.updatePresence(activeBlock) }

            coordinator.shutdown()
        }

    @Test
    fun `applyTodayPatches for future block does not re-sync presence when active block is unchanged`() =
        runTest {
            val today = LocalDate.of(2026, 11, 24)
            val now = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)

            val dayBlock = block(0, 1440, AvailabilityState.AVAILABLE, "自由時間", "✨ 閒晃中")
            val initial =
                DailySchedule(
                    date = today,
                    timezone = "Asia/Taipei",
                    blocks = listOf(dayBlock),
                    summary = "自由日",
                    createdAtMillis = now.toEpochMilli(),
                )

            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(today) } returns initial
            val generator = mockk<DailyScheduleGenerator>(relaxed = true)
            val presencePort = mockk<SchedulePresencePort>(relaxed = true)

            val firstWaitReached = CompletableDeferred<Unit>()
            val secondWaitReached = CompletableDeferred<Unit>()
            var waitCount = 0

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = generator,
                    presencePort = presencePort,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                    delayFn = {
                        waitCount++
                        if (waitCount == 1) {
                            firstWaitReached.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        } else {
                            secondWaitReached.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }
                    },
                )

            coordinator.start(this)
            firstWaitReached.await()
            coVerify(exactly = 1) { presencePort.updatePresence(any()) }

            // Patch 14:00..16:00 (840..960), while current time is 10:00 (600)
            val result =
                coordinator.applyTodayPatches(
                    listOf(
                        SchedulePatch(
                            startMinute = 840,
                            endMinute = 960,
                            activityTitle = "下午打遊戲",
                            activityDetail = "約好下午玩",
                            statusText = "🎮 打遊戲",
                            availability = AvailabilityState.AVAILABLE,
                        ),
                    ),
                )
            assertIs<DailyScheduleSplicer.SpliceResult.Success>(result)
            secondWaitReached.await()

            // Active block changed endMinute (0..1440 -> 0..840), wait timer updated,
            // and current block is still 0..840 AVAILABLE
            assertEquals(840, coordinator.currentBlock()?.endMinute)
            coordinator.shutdown()
        }

    @Test
    fun `applyTodayPatches rejected validation does not persist or consume rate limit quota`() =
        runTest {
            val today = LocalDate.of(2026, 11, 24)
            val now = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, taipeiZone).toInstant()
            val clock = MutableClock(now, taipeiZone)

            val initial =
                DailySchedule(
                    date = today,
                    timezone = "Asia/Taipei",
                    blocks = listOf(block(0, 1440, AvailabilityState.AVAILABLE, "自由時間")),
                    summary = "自由日",
                    createdAtMillis = now.toEpochMilli(),
                )
            val store = mockk<DailyScheduleStore>(relaxed = true)
            every { store.getSchedule(today) } returns initial

            val coordinator =
                DailyScheduleCoordinator(
                    store = store,
                    generator = mockk(relaxed = true),
                    presencePort = null,
                    enabled = true,
                    timezone = taipeiZone,
                    clock = clock,
                )

            // 4 invalid past patches should all reject without consuming the 3/10m rate limit
            repeat(4) {
                val rejected =
                    coordinator.applyTodayPatches(
                        listOf(SchedulePatch(300, 400, "過去", "無效", "⏳", AvailabilityState.AVAILABLE)),
                    )
                assertIs<DailyScheduleSplicer.SpliceResult.Rejected>(rejected)
            }
            verify(exactly = 0) { store.saveSchedule(any()) }

            // 3 valid mutations should still succeed
            repeat(3) { idx ->
                val ok =
                    coordinator.applyTodayPatches(
                        listOf(
                            SchedulePatch(
                                startMinute = 700 + idx * 30,
                                endMinute = 730 + idx * 30,
                                activityTitle = "活動 $idx",
                                activityDetail = "細節 $idx",
                                statusText = "✨ $idx",
                                availability = AvailabilityState.AVAILABLE,
                            ),
                        ),
                    )
                assertIs<DailyScheduleSplicer.SpliceResult.Success>(ok)
            }

            // 4th valid mutation within 10m is rate-limited
            val rateLimited =
                coordinator.applyTodayPatches(
                    listOf(SchedulePatch(900, 930, "第四次", "超過頻率", "✨", AvailabilityState.AVAILABLE)),
                )
            assertIs<DailyScheduleSplicer.SpliceResult.Rejected>(rateLimited)
            verify(exactly = 3) { store.saveSchedule(any()) }
        }
}
