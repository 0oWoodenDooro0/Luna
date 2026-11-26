package luna.chatter.schedule

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.ActivityType
import dev.kord.common.entity.PresenceStatus
import dev.kord.core.Kord
import dev.kord.core.gateway.MasterGateway
import dev.kord.gateway.UpdateStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulePresencePortTest {
    @Test
    fun `KordSchedulePresencePort maps AvailabilityState to Discord PresenceStatus and statusText`() =
        runTest {
            val captured = mutableListOf<UpdateStatus>()
            val gateway = mockk<MasterGateway>(relaxed = true)
            coEvery { gateway.sendAll(any()) } coAnswers {
                captured += firstArg<UpdateStatus>()
            }
            val kord = mockk<Kord>(relaxed = true)
            every { kord.gateway } returns gateway

            val port = KordSchedulePresencePort(kord, initiallyReady = true)

            port.updatePresence(sampleBlock(AvailabilityState.AVAILABLE, "📺 追番中"))
            port.updatePresence(sampleBlock(AvailabilityState.BREAK, "☕ 泡咖啡"))
            port.updatePresence(sampleBlock(AvailabilityState.BUSY, "🎨 趕稿中"))
            port.updatePresence(sampleBlock(AvailabilityState.SLEEPING, "💤 睡覺中", PresenceActivityType.PLAYING))

            assertEquals(
                listOf<Triple<PresenceStatus, String?, Int>>(
                    Triple(PresenceStatus.Online, "📺 追番中", 1),
                    Triple(PresenceStatus.Online, "☕ 泡咖啡", 1),
                    Triple(PresenceStatus.DoNotDisturb, "🎨 趕稿中", 1),
                    Triple(PresenceStatus.Invisible, null, 0),
                ),
                captured.map { update ->
                    val customActivity = update.activities.firstOrNull { it.type == ActivityType.Custom }
                    Triple(update.status, customActivity?.state?.value, update.activities.size)
                },
            )
            coVerify(exactly = 4) { gateway.sendAll(any()) }
        }

    @Test
    fun `KordSchedulePresencePort maps native PresenceActivityType without setting state`() =
        runTest {
            val captured = mutableListOf<UpdateStatus>()
            val gateway = mockk<MasterGateway>(relaxed = true)
            coEvery { gateway.sendAll(any()) } coAnswers {
                captured += firstArg<UpdateStatus>()
            }
            val kord = mockk<Kord>(relaxed = true)
            every { kord.gateway } returns gateway

            val port = KordSchedulePresencePort(kord, initiallyReady = true)

            port.updatePresence(sampleBlock(AvailabilityState.AVAILABLE, "☕ 泡咖啡", PresenceActivityType.CUSTOM))
            port.updatePresence(sampleBlock(AvailabilityState.AVAILABLE, "薩爾達傳說", PresenceActivityType.PLAYING))
            port.updatePresence(sampleBlock(AvailabilityState.BREAK, "Lo-Fi 歌單", PresenceActivityType.LISTENING))
            port.updatePresence(sampleBlock(AvailabilityState.BUSY, "深夜新番", PresenceActivityType.WATCHING))

            assertEquals(4, captured.size)

            val custom = captured[0].activities.single()
            assertEquals(ActivityType.Custom, custom.type)
            assertEquals("☕ 泡咖啡", custom.state.value)

            val playing = captured[1].activities.single()
            assertEquals(ActivityType.Game, playing.type)
            assertEquals("薩爾達傳說", playing.name)
            assertEquals(null, playing.state.value)

            val listening = captured[2].activities.single()
            assertEquals(ActivityType.Listening, listening.type)
            assertEquals("Lo-Fi 歌單", listening.name)
            assertEquals(null, listening.state.value)

            val watching = captured[3].activities.single()
            assertEquals(ActivityType.Watching, watching.type)
            assertEquals("深夜新番", watching.name)
            assertEquals(null, watching.state.value)
        }

    @Test
    fun `KordSchedulePresencePort logs WARN and fails open when editPresence throws`() =
        runTest {
            val portLogger = LoggerFactory.getLogger(KordSchedulePresencePort::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            portLogger.addAppender(appender)

            try {
                val gateway = mockk<MasterGateway>(relaxed = true)
                coEvery { gateway.sendAll(any()) } throws IllegalStateException("gateway offline")
                val kord = mockk<Kord>(relaxed = true)
                every { kord.gateway } returns gateway

                val port = KordSchedulePresencePort(kord, initiallyReady = true)
                port.updatePresence(sampleBlock(AvailabilityState.AVAILABLE, "📺 追番中"))

                assertTrue(
                    appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("gateway offline") },
                    "Expected observable WARN log when editPresence fails",
                )
            } finally {
                portLogger.detachAppender(appender)
            }
        }

    @Test
    fun `KordSchedulePresencePort defers presence sync until gateway is ready and flushes latest block on ReadyEvent`() =
        runTest {
            val portLogger = LoggerFactory.getLogger(KordSchedulePresencePort::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            portLogger.addAppender(appender)

            try {
                val captured = mutableListOf<UpdateStatus>()
                val gateway = mockk<MasterGateway>(relaxed = true)
                var socketInitialized = false
                coEvery { gateway.sendAll(any()) } coAnswers {
                    if (!socketInitialized) {
                        throw UninitializedPropertyAccessException("lateinit property socket has not been initialized")
                    }
                    captured += firstArg<UpdateStatus>()
                }
                val kord = mockk<Kord>(relaxed = true)
                every { kord.gateway } returns gateway

                val port = KordSchedulePresencePort(kord)

                // Before ReadyEvent, updatePresence must defer instead of hitting uninitialized gateway socket
                port.updatePresence(sampleBlock(AvailabilityState.BUSY, "🎨 趕稿中"))
                port.updatePresence(sampleBlock(AvailabilityState.AVAILABLE, "🎮 深夜遊戲馬拉松"))

                assertTrue(captured.isEmpty(), "Expected no gateway.sendAll calls before ReadyEvent")
                assertTrue(
                    appender.list.none { it.level == Level.WARN },
                    "Expected no WARN logs while deferring pre-ReadyEvent presence sync",
                )

                // Once gateway connects and ReadyEvent fires, only the latest deferred block is synced
                socketInitialized = true
                port.onGatewayReady()

                assertEquals(1, captured.size)
                val firstActivity = captured.single().activities.firstOrNull { it.type == ActivityType.Custom }
                assertEquals(PresenceStatus.Online, captured.single().status)
                assertEquals("🎮 深夜遊戲馬拉松", firstActivity?.state?.value)

                // Subsequent reconnect ReadyEvent re-syncs the latest block
                port.onGatewayReady()
                assertEquals(2, captured.size)
            } finally {
                portLogger.detachAppender(appender)
            }
        }

    private fun sampleBlock(
        availability: AvailabilityState,
        statusText: String,
        activityType: PresenceActivityType = PresenceActivityType.CUSTOM,
    ): ScheduleBlock =
        ScheduleBlock(
            startMinute = 0,
            endMinute = 1440,
            activityTitle = "測試活動",
            activityDetail = "測試細節",
            statusText = statusText,
            availability = availability,
            activityType = activityType,
        )
}
