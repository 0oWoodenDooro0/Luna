package luna.chatter.schedule

import luna.chatter.ChatterConfig
import luna.chatter.ChatterPromptBuilder
import luna.chatter.CircadianPersonaDrift
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterSchedulePromptTest {
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    @Test
    fun `buildTools registers both schedule tools when enabled and omits them when disabled`() {
        val config = ChatterConfig(searchGroundingEnabled = false, urlContextEnabled = false)

        val enabledTools =
            ChatterPromptBuilder.buildTools(
                config = config,
                webUrls = emptyList(),
                includeMemoryTool = false,
                includeScheduleTools = true,
            )
        val enabledNames = enabledTools.mapNotNull { it.name }.toSet()
        assertTrue(
            ChatterScheduleToolHandler.TODAY_TOOL_NAME in enabledNames,
            "Expected ${ChatterScheduleToolHandler.TODAY_TOOL_NAME} in $enabledNames",
        )
        assertTrue(
            ChatterScheduleToolHandler.COMMITMENT_TOOL_NAME in enabledNames,
            "Expected ${ChatterScheduleToolHandler.COMMITMENT_TOOL_NAME} in $enabledNames",
        )

        val disabledTools =
            ChatterPromptBuilder.buildTools(
                config = config,
                webUrls = emptyList(),
                includeMemoryTool = false,
                includeScheduleTools = false,
            )
        val disabledNames = disabledTools.mapNotNull { it.name }.toSet()
        assertFalse(
            ChatterScheduleToolHandler.TODAY_TOOL_NAME in disabledNames,
            "Did not expect ${ChatterScheduleToolHandler.TODAY_TOOL_NAME} in $disabledNames",
        )
        assertFalse(
            ChatterScheduleToolHandler.COMMITMENT_TOOL_NAME in disabledNames,
            "Did not expect ${ChatterScheduleToolHandler.COMMITMENT_TOOL_NAME} in $disabledNames",
        )
    }

    @Test
    fun `buildSystemInstruction injects upcoming 7-day commitments and persona guidance when present`() {
        val today = LocalDate.of(2026, 11, 25)
        val schedule =
            DailySchedule(
                date = today,
                timezone = "Asia/Taipei",
                blocks =
                    listOf(
                        ScheduleBlock(
                            startMinute = 0,
                            endMinute = 1440,
                            activityTitle = "自由時間",
                            activityDetail = "在群組聊天",
                            statusText = "在線上",
                            availability = AvailabilityState.AVAILABLE,
                        ),
                    ),
                summary = "普通的一天",
                createdAtMillis = 1_000L,
            )
        val commitment =
            ScheduleCommitment(
                id = "commit-movie-1",
                targetDate = LocalDate.of(2026, 11, 26),
                startMinute = 20 * 60,
                endMinute = 22 * 60,
                activityTitle = "語音房看電影",
                activityDetail = "跟群友一起看動畫電影",
                statusText = "看電影中",
                availability = AvailabilityState.AVAILABLE,
                createdAtMillis = 1_000L,
            )
        val provider =
            object : DailyScheduleProvider {
                override fun isEnabled(): Boolean = true

                override fun currentSchedule(): DailySchedule = schedule

                override fun currentBlock(): ScheduleBlock = schedule.blocks.first()

                override fun upcomingCommitments(): List<ScheduleCommitment> = listOf(commitment)
            }

        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                enabled = true,
                clock = Clock.fixed(Instant.parse("2026-11-25T06:00:00Z"), taipeiZone),
                scheduleProvider = provider,
            )
        val personaState = drift.currentState()

        val systemInstruction =
            ChatterPromptBuilder.buildSystemInstruction(
                personaState = personaState,
                memoryToolsEnabled = false,
                scheduleToolsEnabled = true,
            )

        assertContains(systemInstruction, "2026-11-26")
        assertContains(systemInstruction, "20:00–22:00")
        assertContains(systemInstruction, "語音房看電影")
        assertContains(systemInstruction, "commit-movie-1")
        assertContains(systemInstruction, ChatterScheduleToolHandler.TODAY_TOOL_NAME)
        assertContains(systemInstruction, ChatterScheduleToolHandler.COMMITMENT_TOOL_NAME)
        assertContains(systemInstruction, "惡搞")
    }

    @Test
    fun `buildSystemInstruction omits upcoming commitments section when none exist`() {
        val today = LocalDate.of(2026, 11, 25)
        val schedule =
            DailySchedule(
                date = today,
                timezone = "Asia/Taipei",
                blocks =
                    listOf(
                        ScheduleBlock(
                            startMinute = 0,
                            endMinute = 1440,
                            activityTitle = "自由時間",
                            activityDetail = "在群組聊天",
                            statusText = "在線上",
                            availability = AvailabilityState.AVAILABLE,
                        ),
                    ),
                summary = "普通的一天",
                createdAtMillis = 1_000L,
            )
        val provider =
            object : DailyScheduleProvider {
                override fun isEnabled(): Boolean = true

                override fun currentSchedule(): DailySchedule = schedule

                override fun currentBlock(): ScheduleBlock = schedule.blocks.first()

                override fun upcomingCommitments(): List<ScheduleCommitment> = emptyList()
            }

        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                enabled = false,
                clock = Clock.fixed(Instant.parse("2026-11-25T06:00:00Z"), taipeiZone),
                scheduleProvider = provider,
            )
        val systemInstruction =
            ChatterPromptBuilder.buildSystemInstruction(
                personaState = drift.currentState(),
                memoryToolsEnabled = false,
                scheduleToolsEnabled = false,
            )

        assertFalse(systemInstruction.contains("近期跨日約定"), "Expected upcoming commitments section to be omitted")
        assertFalse(
            systemInstruction.contains(ChatterScheduleToolHandler.TODAY_TOOL_NAME),
            "Expected schedule tool instructions to be omitted when disabled",
        )
    }
}
