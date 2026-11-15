package luna.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import luna.config.EnvSchemaAuditor
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorNotifyConfigTest {
    private fun captureWarnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(ErrorNotifyConfig::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `disabled by default when ERROR_NOTIFY_DISCORD_USER_IDS is unset or blank`() {
        val unset = ErrorNotifyConfig.fromMap(emptyMap())
        assertFalse(unset.isEnabled)
        assertTrue(unset.recipientUserIds.isEmpty())
        assertNull(unset.environment)
        assertEquals(ErrorNotifyConfig.DEFAULT_COOLDOWN_SECONDS, unset.cooldownSeconds)

        val blank =
            ErrorNotifyConfig.fromMap(
                mapOf(
                    "ERROR_NOTIFY_DISCORD_USER_IDS" to "   ,  ",
                    "ERROR_NOTIFY_ENV" to "  ",
                    "ERROR_NOTIFY_COOLDOWN_SECONDS" to "",
                ),
            )
        assertFalse(blank.isEnabled)
        assertTrue(blank.recipientUserIds.isEmpty())
        assertNull(blank.environment)
        assertEquals(300, blank.cooldownSeconds)
    }

    @Test
    fun `parses valid comma-separated Discord user IDs, environment label, and cooldown`() {
        lateinit var config: ErrorNotifyConfig
        val warnings =
            captureWarnings {
                config =
                    ErrorNotifyConfig.fromMap(
                        mapOf(
                            "ERROR_NOTIFY_DISCORD_USER_IDS" to " 123456789012345678 , 987654321098765432, 123456789012345678 ",
                            "ERROR_NOTIFY_ENV" to " production ",
                            "ERROR_NOTIFY_COOLDOWN_SECONDS" to "120",
                        ),
                    )
            }

        assertTrue(config.isEnabled)
        assertEquals(
            listOf(Snowflake(123456789012345678uL), Snowflake(987654321098765432uL)),
            config.recipientUserIds,
        )
        assertEquals("production", config.environment)
        assertEquals(120, config.cooldownSeconds)
        assertTrue(warnings.isEmpty(), "Valid config must not emit warnings")
    }

    @Test
    fun `skips malformed Discord user IDs with warning log without crashing`() {
        lateinit var config: ErrorNotifyConfig
        val warnings =
            captureWarnings {
                config =
                    ErrorNotifyConfig.fromMap(
                        mapOf(
                            "ERROR_NOTIFY_DISCORD_USER_IDS" to "not-a-snowflake, 0, -42, 111222333444555666",
                        ),
                    )
            }

        assertTrue(config.isEnabled)
        assertEquals(listOf(Snowflake(111222333444555666uL)), config.recipientUserIds)
        val warnMessages = warnings.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertEquals(3, warnMessages.size, "Expected warnings for 'not-a-snowflake', '0', and '-42'")
        assertTrue(warnMessages.any { it.contains("not-a-snowflake") })
    }

    @Test
    fun `falls back to 300 seconds with warning log when cooldown is non-positive or unparseable`() {
        for (badValue in listOf("0", "-15", "abc", 0, -5)) {
            lateinit var config: ErrorNotifyConfig
            val warnings =
                captureWarnings {
                    config =
                        ErrorNotifyConfig.fromMap(
                            mapOf(
                                "ERROR_NOTIFY_DISCORD_USER_IDS" to "123456789012345678",
                                "ERROR_NOTIFY_COOLDOWN_SECONDS" to badValue,
                            ),
                        )
                }
            assertEquals(300, config.cooldownSeconds, "Cooldown '$badValue' must fall back to 300")
            assertTrue(
                warnings.any { it.level == Level.WARN && it.formattedMessage.contains("ERROR_NOTIFY_COOLDOWN_SECONDS") },
                "Cooldown '$badValue' must emit a local WARN log",
            )
        }
    }

    @Test
    fun `EnvSchemaAuditor defaultFallbackSchema and dot-env-example declare all error notify keys`() {
        val fallbackKeys = EnvSchemaAuditor.defaultFallbackSchema().associateBy { it.name }
        assertTrue(fallbackKeys.containsKey("ERROR_NOTIFY_DISCORD_USER_IDS"))
        assertTrue(fallbackKeys.containsKey("ERROR_NOTIFY_ENV"))
        assertTrue(fallbackKeys.containsKey("ERROR_NOTIFY_COOLDOWN_SECONDS"))
        assertEquals("300", fallbackKeys["ERROR_NOTIFY_COOLDOWN_SECONDS"]?.defaultValue)
        assertFalse(fallbackKeys["ERROR_NOTIFY_DISCORD_USER_IDS"]!!.isRequired)
        assertEquals(1, fallbackKeys["ERROR_NOTIFY_DISCORD_USER_IDS"]?.section)

        val envExample = ConfigLoader.loadEnvExample()
        assertTrue(!envExample.isNullOrBlank())
        val parsed = EnvSchemaAuditor.parseEnvExampleSchema(envExample).associateBy { it.name }
        assertTrue(parsed.containsKey("ERROR_NOTIFY_DISCORD_USER_IDS"))
        assertTrue(parsed.containsKey("ERROR_NOTIFY_ENV"))
        assertTrue(parsed.containsKey("ERROR_NOTIFY_COOLDOWN_SECONDS"))
        assertEquals("300", parsed["ERROR_NOTIFY_COOLDOWN_SECONDS"]?.defaultValue)
    }
}
