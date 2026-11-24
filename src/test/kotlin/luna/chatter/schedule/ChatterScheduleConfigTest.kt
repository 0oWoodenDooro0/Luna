package luna.chatter.schedule

import luna.chatter.ChatterConfig
import luna.config.ConfigValidationException
import luna.config.EnvReader
import luna.config.EnvSchemaAuditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterScheduleConfigTest {
    @Test
    fun `ChatterScheduleConfig provides documented defaults and integrates into ChatterConfig`() {
        val config = ChatterConfig()
        assertTrue(config.schedule.enabled, "Expected schedule enabled by default")
        assertEquals(30, config.schedule.catchUpMaxMessages)
        assertEquals(8, config.schedule.catchUpMaxAgeHours)
        assertEquals(2, config.schedule.catchUpMaxAmbient)
    }

    @Test
    fun `ChatterConfig parses valid schedule overrides from EnvReader`() {
        val env =
            EnvReader.of(
                "CHATTER_SCHEDULE_ENABLED" to "false",
                "CHATTER_SCHEDULE_CATCHUP_MAX_MESSAGES" to "50",
                "CHATTER_SCHEDULE_CATCHUP_MAX_AGE_HOURS" to "12",
                "CHATTER_SCHEDULE_CATCHUP_MAX_AMBIENT" to "0",
            )
        val config = ChatterConfig.from(env)
        assertFalse(config.schedule.enabled, "Expected schedule enabled override to parse false")
        assertEquals(50, config.schedule.catchUpMaxMessages)
        assertEquals(12, config.schedule.catchUpMaxAgeHours)
        assertEquals(0, config.schedule.catchUpMaxAmbient)
    }

    @Test
    fun `ChatterScheduleConfig fails fast on invalid types and out-of-range bounds`() {
        assertFailsWith<ConfigValidationException> {
            ChatterScheduleConfig.from(EnvReader.of("CHATTER_SCHEDULE_ENABLED" to "maybe"))
        }
        assertFailsWith<ConfigValidationException> {
            ChatterScheduleConfig.from(EnvReader.of("CHATTER_SCHEDULE_CATCHUP_MAX_MESSAGES" to "0"))
        }
        assertFailsWith<ConfigValidationException> {
            ChatterScheduleConfig.from(EnvReader.of("CHATTER_SCHEDULE_CATCHUP_MAX_MESSAGES" to "101"))
        }
        assertFailsWith<ConfigValidationException> {
            ChatterScheduleConfig.from(EnvReader.of("CHATTER_SCHEDULE_CATCHUP_MAX_AGE_HOURS" to "0"))
        }
        assertFailsWith<ConfigValidationException> {
            ChatterScheduleConfig.from(EnvReader.of("CHATTER_SCHEDULE_CATCHUP_MAX_AMBIENT" to "-1"))
        }
        assertFailsWith<ConfigValidationException> {
            ChatterScheduleConfig.from(EnvReader.of("CHATTER_SCHEDULE_CATCHUP_MAX_AMBIENT" to "11"))
        }
    }

    @Test
    fun `dot-env-example and EnvSchemaAuditor declare all Chatter schedule keys`() {
        val expectedKeys =
            listOf(
                "CHATTER_SCHEDULE_ENABLED" to "true",
                "CHATTER_SCHEDULE_CATCHUP_MAX_MESSAGES" to "30",
                "CHATTER_SCHEDULE_CATCHUP_MAX_AGE_HOURS" to "8",
                "CHATTER_SCHEDULE_CATCHUP_MAX_AMBIENT" to "2",
            )

        val envExample = requireNotNull(EnvSchemaAuditor.loadDefaultEnvExample()) { ".env.example should be loadable" }
        val parsed = EnvSchemaAuditor.parseEnvExampleSchema(envExample).associateBy { it.name }
        val fallback = EnvSchemaAuditor.defaultFallbackSchema().associateBy { it.name }

        for ((key, expectedDefault) in expectedKeys) {
            val envEntry = parsed[key]
            assertTrue(envEntry != null, "Expected $key in .env.example")
            assertEquals(expectedDefault, envEntry.defaultValue, "Default mismatch for $key in .env.example")

            val fallbackEntry = fallback[key]
            assertTrue(fallbackEntry != null, "Expected $key in EnvSchemaAuditor.defaultFallbackSchema()")
            assertEquals(expectedDefault, fallbackEntry.defaultValue, "Default mismatch for $key in defaultFallbackSchema()")
        }

        val auditResult =
            EnvSchemaAuditor.auditDrift(
                config = expectedKeys.associate { (k, v) -> k to v },
                schema = parsed.values.toList(),
            )
        assertTrue(auditResult.unknownKeys.isEmpty(), "Unexpected unknownKeys: ${auditResult.unknownKeys}")
    }
}
