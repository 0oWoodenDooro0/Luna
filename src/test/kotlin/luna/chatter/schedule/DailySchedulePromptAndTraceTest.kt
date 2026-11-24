package luna.chatter.schedule

import luna.chatter.ChatterAiTraceTestSupport
import luna.chatter.ChatterPromptBuilder
import luna.chatter.PersonaMood
import luna.chatter.PersonaState
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceTrigger
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailySchedulePromptAndTraceTest {
    private val sampleBlocks =
        listOf(
            ScheduleBlock(0, 120, "深夜剪片", "修片跟調色", "剪片中", AvailabilityState.BUSY),
            ScheduleBlock(120, 600, "睡覺", "補眠時間", "睡著了", AvailabilityState.SLEEPING),
            ScheduleBlock(600, 900, "早午餐與補番", "邊吃吐司邊看動畫", "吃早午餐", AvailabilityState.AVAILABLE),
            ScheduleBlock(900, 930, "喝水伸展", "起來走走順便滑手機", "休息一下", AvailabilityState.BREAK),
            ScheduleBlock(930, 1440, "自由接案與閒聊", "畫圖配 Discord 聊天", "畫圖掛網中", AvailabilityState.AVAILABLE),
        )

    private val sampleSchedule =
        DailySchedule(
            date = LocalDate.of(2026, 11, 19),
            timezone = "Asia/Taipei",
            blocks = sampleBlocks,
            summary = "凌晨剪片後補眠，下午自由接案",
            createdAtMillis = 1000L,
        )

    @Test
    fun `buildSystemInstruction and buildReactionSystemInstruction inject active ScheduleBlock and compact today outline`() {
        val state =
            PersonaState(
                driftEnabled = true,
                timezone = "Asia/Taipei",
                localTime = ZonedDateTime.parse("2026-11-19T11:30:00+08:00"),
                formattedWeekday = "星期四",
                formattedTime = "11:30",
                circadianPeriod = null,
                mood = PersonaMood.CHATTY,
                baseThreshold = 0.70,
                effectiveThreshold = 0.66,
                scheduleBlock = sampleBlocks[2],
                dailySchedule = sampleSchedule,
            )

        val systemInstruction = ChatterPromptBuilder.buildSystemInstruction(state, memoryToolsEnabled = false)
        assertTrue(systemInstruction.contains("早午餐與補番"), "Expected active block title in system instruction")
        assertTrue(systemInstruction.contains("邊吃吐司邊看動畫"), "Expected active block detail in system instruction")
        assertTrue(systemInstruction.contains("10:00–15:00"), "Expected active block time range in system instruction")
        assertTrue(systemInstruction.contains("深夜剪片"), "Expected compact today's schedule outline")
        assertTrue(systemInstruction.contains("自由接案與閒聊"), "Expected compact today's schedule outline")
        assertTrue(systemInstruction.contains("CHATTY"), "Expected mood label")
        assertFalse(systemInstruction.contains("時段狀態（"), "Should omit fixed CircadianPeriod label when schedule is active")

        val reactionInstruction = ChatterPromptBuilder.buildReactionSystemInstruction(personaState = state)
        assertTrue(reactionInstruction.contains("早午餐與補番"), "Expected active block in reaction instruction")
        assertTrue(reactionInstruction.contains("CHATTY"), "Expected mood in reaction instruction")
    }

    @Test
    fun `buildSystemInstruction includes quick-break cue during BREAK block`() {
        val state =
            PersonaState(
                driftEnabled = true,
                timezone = "Asia/Taipei",
                localTime = ZonedDateTime.parse("2026-11-19T15:10:00+08:00"),
                formattedWeekday = "星期四",
                formattedTime = "15:10",
                circadianPeriod = null,
                mood = PersonaMood.STEADY,
                baseThreshold = 0.70,
                effectiveThreshold = 0.76,
                scheduleBlock = sampleBlocks[3],
                dailySchedule = sampleSchedule,
            )

        val systemInstruction = ChatterPromptBuilder.buildSystemInstruction(state, memoryToolsEnabled = false)
        assertTrue(systemInstruction.contains("喝水伸展"), "Expected BREAK block title in system instruction")
        assertTrue(systemInstruction.contains("BREAK"), "Expected BREAK availability in system instruction")
        assertTrue(
            systemInstruction.contains("休息") || systemInstruction.contains("抽空"),
            "Expected quick-break cue in system instruction",
        )
    }

    @Test
    fun `AiTraceSession records and redacts scheduleBlockTitle and scheduleAvailability`(
        @TempDir tempDir: Path,
    ) {
        val recorder =
            JsonlAiTraceRecorder(
                traceDir = tempDir,
                clock = Clock.fixed(Instant.parse("2026-11-19T03:30:00Z"), ZoneId.of("UTC")),
            )
        val session =
            recorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.AMBIENT,
                discord = AiTraceDiscordContext(guildId = "1", channelId = "2", messageId = "3"),
            )
        session.registerSecret("top-secret-token")
        session.recordScheduleState(
            scheduleBlockTitle = "剪片 top-secret-token",
            scheduleAvailability = AvailabilityState.BUSY.name,
        )
        session.skip("Deferred by DailySchedule")
        session.finish()

        val records = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        val parsed = records.single()
        assertTrue(parsed.scheduleBlockTitle?.contains("剪片") == true, "Expected scheduleBlockTitle to retain non-secret text")
        assertFalse(parsed.scheduleBlockTitle?.contains("top-secret-token") == true, "Expected secret redaction")
        assertTrue(parsed.scheduleAvailability == "BUSY", "Expected scheduleAvailability to be BUSY")
    }
}
