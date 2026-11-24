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

            val port = KordSchedulePresencePort(kord)

            port.updatePresence(sampleBlock(AvailabilityState.AVAILABLE, "📺 追番中"))
            port.updatePresence(sampleBlock(AvailabilityState.BREAK, "☕ 泡咖啡"))
            port.updatePresence(sampleBlock(AvailabilityState.BUSY, "🎨 趕稿中"))
            port.updatePresence(sampleBlock(AvailabilityState.SLEEPING, "💤 睡覺中"))

            assertEquals(
                listOf<Pair<PresenceStatus, String?>>(
                    PresenceStatus.Online to "📺 追番中",
                    PresenceStatus.Online to "☕ 泡咖啡",
                    PresenceStatus.DoNotDisturb to "🎨 趕稿中",
                    PresenceStatus.Idle to "💤 睡覺中",
                ),
                captured.map { update ->
                    val customActivity = update.activities.firstOrNull { it.type == ActivityType.Custom }
                    update.status to customActivity?.state?.value
                },
            )
            coVerify(exactly = 4) { gateway.sendAll(any()) }
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

                val port = KordSchedulePresencePort(kord)
                port.updatePresence(sampleBlock(AvailabilityState.AVAILABLE, "📺 追番中"))

                assertTrue(
                    appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("gateway offline") },
                    "Expected observable WARN log when editPresence fails",
                )
            } finally {
                portLogger.detachAppender(appender)
            }
        }

    private fun sampleBlock(
        availability: AvailabilityState,
        statusText: String,
    ): ScheduleBlock =
        ScheduleBlock(
            startMinute = 0,
            endMinute = 1440,
            activityTitle = "測試活動",
            activityDetail = "測試細節",
            statusText = statusText,
            availability = availability,
        )
}
