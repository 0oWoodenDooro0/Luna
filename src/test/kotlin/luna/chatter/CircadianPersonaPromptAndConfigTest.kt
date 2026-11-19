package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import io.mockk.mockk
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircadianPersonaPromptAndConfigTest {
    @Test
    fun `ChatterConfig resolves CHATTER_TIMEZONE and CHATTER_DRIFT_ENABLED defaults, aliases, and invalid boolean fallback`() {
        val defaults = ChatterConfig()
        assertEquals("Asia/Taipei", defaults.timezone)
        assertTrue(defaults.driftEnabled)

        val envDefaults = ChatterConfig.fromEnv(emptyMap())
        assertEquals("Asia/Taipei", envDefaults.timezone)
        assertTrue(envDefaults.driftEnabled)

        val envCustom =
            ChatterConfig.fromEnv(
                mapOf(
                    "CHATTER_TIMEZONE" to "  America/Los_Angeles  ",
                    "CHATTER_DRIFT_ENABLED" to "false",
                ),
            )
        assertEquals("America/Los_Angeles", envCustom.timezone)
        assertFalse(envCustom.driftEnabled)

        val configAliases =
            ChatterConfig.fromConfig(
                mapOf(
                    "chatter.timezone" to "Asia/Tokyo",
                    "chatter.driftEnabled" to false,
                ),
            )
        assertEquals("Asia/Tokyo", configAliases.timezone)
        assertFalse(configAliases.driftEnabled)

        val contextSnake =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap =
                    mapOf(
                        "chatter.time_zone" to "Europe/London",
                        "chatter.drift_enabled" to "false",
                    ),
            )
        val fromCtx = ChatterConfig.fromPluginContext(contextSnake)
        assertEquals("Europe/London", fromCtx.timezone)
        assertFalse(fromCtx.driftEnabled)

        val configLogger = LoggerFactory.getLogger(ChatterConfig::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        configLogger.addAppender(appender)
        try {
            val invalidEnv =
                ChatterConfig.fromEnv(
                    mapOf(
                        "CHATTER_TIMEZONE" to "   ",
                        "CHATTER_DRIFT_ENABLED" to "not-a-boolean",
                    ),
                )
            assertEquals("Asia/Taipei", invalidEnv.timezone)
            assertTrue(invalidEnv.driftEnabled)
            assertTrue(
                appender.list.any {
                    it.level == Level.WARN && it.formattedMessage.contains("CHATTER_DRIFT_ENABLED")
                },
                "Expected warning log on unparseable CHATTER_DRIFT_ENABLED",
            )
        } finally {
            configLogger.detachAppender(appender)
        }
    }

    @Test
    fun `ChatterPromptBuilder injects weekday, local HH_mm, circadian cue, and mood cue into system instruction across all four windows`() {
        val windowTimestamps =
            listOf(
                "2026-11-19T02:15:00+08:00" to CircadianPeriod.LATE_NIGHT,
                "2026-11-19T08:45:00+08:00" to CircadianPeriod.MORNING,
                "2026-11-19T14:20:00+08:00" to CircadianPeriod.DAYTIME,
                "2026-11-19T21:05:00+08:00" to CircadianPeriod.EVENING,
            )

        for ((iso, expectedPeriod) in windowTimestamps) {
            val instant = OffsetDateTime.parse(iso).toInstant()
            val drift =
                CircadianPersonaDrift(
                    timezone = "Asia/Taipei",
                    clock = Clock.fixed(instant, ZoneId.of("UTC")),
                    random = kotlin.random.Random(7),
                )
            val state = drift.currentState(0.7)
            assertEquals(expectedPeriod, state.circadianPeriod)

            val systemInstruction = ChatterPromptBuilder.buildSystemInstruction(state)
            assertContains(systemInstruction, ChatterPromptBuilder.SYSTEM_INSTRUCTION)
            assertContains(systemInstruction, state.formattedWeekday)
            assertContains(systemInstruction, state.formattedTime)
            assertContains(systemInstruction, expectedPeriod.promptCue)
            assertContains(systemInstruction, state.mood!!.promptCue)

            val request =
                ChatterPromptBuilder.buildInteractionRequest(
                    input = ChatterInput(transcript = "[Alice]: 早安", personaState = state),
                )
            assertEquals(systemInstruction, request.systemInstruction)
        }
    }

    @Test
    fun `ChatterPromptBuilder preserves baseline SYSTEM_INSTRUCTION when personaState is null or drift is disabled`() {
        assertEquals(ChatterPromptBuilder.SYSTEM_INSTRUCTION, ChatterPromptBuilder.buildSystemInstruction(null))

        val disabledDrift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                enabled = false,
                clock = Clock.fixed(Instant.parse("2026-11-19T08:30:00Z"), ZoneId.of("UTC")),
            )
        val disabledState = disabledDrift.currentState(0.7)
        assertEquals(ChatterPromptBuilder.SYSTEM_INSTRUCTION, ChatterPromptBuilder.buildSystemInstruction(disabledState))

        val request =
            ChatterPromptBuilder.buildInteractionRequest(
                input = ChatterInput(transcript = "[Alice]: hello", personaState = disabledState),
            )
        assertEquals(ChatterPromptBuilder.SYSTEM_INSTRUCTION, request.systemInstruction)
    }
}
