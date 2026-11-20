package luna.core

import dev.kord.common.entity.Snowflake
import luna.config.ConfigValidationException
import luna.config.EnvReader
import luna.config.EnvSchemaAuditor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorNotifyConfigTest {
    @Test
    fun `disabled by default when ERROR_NOTIFY_DISCORD_USER_IDS is unset or blank`() {
        val unset = ErrorNotifyConfig.from(EnvReader.of(emptyMap()))
        assertFalse(unset.isEnabled, "Expected ErrorNotifyConfig to be disabled when unset")
        assertTrue(unset.recipientUserIds.isEmpty(), "Expected empty recipientUserIds when unset")
        assertNull(unset.environment)
        assertEquals(ErrorNotifyConfig.DEFAULT_COOLDOWN_SECONDS, unset.cooldownSeconds)

        val blank =
            ErrorNotifyConfig.from(
                EnvReader.of(
                    mapOf(
                        "ERROR_NOTIFY_DISCORD_USER_IDS" to "   ,  ",
                        "ERROR_NOTIFY_ENV" to "  ",
                        "ERROR_NOTIFY_COOLDOWN_SECONDS" to "",
                    ),
                ),
            )
        assertFalse(blank.isEnabled, "Expected ErrorNotifyConfig to be disabled when blank")
        assertTrue(blank.recipientUserIds.isEmpty(), "Expected empty recipientUserIds when blank")
        assertNull(blank.environment)
        assertEquals(300, blank.cooldownSeconds)
    }

    @Test
    fun `parses valid comma-separated Discord user IDs, environment label, and cooldown`() {
        val config =
            ErrorNotifyConfig.from(
                EnvReader.of(
                    mapOf(
                        "ERROR_NOTIFY_DISCORD_USER_IDS" to " 123456789012345678 , 987654321098765432, 123456789012345678 ",
                        "ERROR_NOTIFY_ENV" to " production ",
                        "ERROR_NOTIFY_COOLDOWN_SECONDS" to "120",
                    ),
                ),
            )

        assertTrue(config.isEnabled, "Expected ErrorNotifyConfig to be enabled with valid user IDs")
        assertEquals(
            listOf(Snowflake(123456789012345678uL), Snowflake(987654321098765432uL)),
            config.recipientUserIds,
        )
        assertEquals("production", config.environment)
        assertEquals(120, config.cooldownSeconds)
    }

    @Test
    fun `fails fast with ConfigValidationException on malformed Discord user IDs`() {
        for (badIds in listOf("not-a-snowflake", "0", "-42", "111222333444555666,not-a-snowflake")) {
            val ex =
                assertThrows<ConfigValidationException> {
                    ErrorNotifyConfig.from(
                        EnvReader.of(
                            mapOf("ERROR_NOTIFY_DISCORD_USER_IDS" to badIds),
                        ),
                    )
                }
            assertTrue(
                ex.message?.contains("ERROR_NOTIFY_DISCORD_USER_IDS") == true,
                "Expected exception message to mention ERROR_NOTIFY_DISCORD_USER_IDS, got: ${ex.message}",
            )
        }
    }

    @Test
    fun `fails fast with ConfigValidationException when cooldown is non-positive, out of range, or non-integer`() {
        for (badValue in listOf("0", "-15", "abc", "2147483648")) {
            val ex =
                assertThrows<ConfigValidationException> {
                    ErrorNotifyConfig.from(
                        EnvReader.of(
                            mapOf(
                                "ERROR_NOTIFY_DISCORD_USER_IDS" to "123456789012345678",
                                "ERROR_NOTIFY_COOLDOWN_SECONDS" to badValue,
                            ),
                        ),
                    )
                }
            assertTrue(
                ex.message?.contains("ERROR_NOTIFY_COOLDOWN_SECONDS") == true,
                "Expected exception message to mention ERROR_NOTIFY_COOLDOWN_SECONDS for '$badValue', got: ${ex.message}",
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
