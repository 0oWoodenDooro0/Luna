package luna.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvReaderTest {
    @Test
    fun `string and requiredString trim values and treat unset or blank as unset`() {
        val env =
            EnvReader.of(
                mapOf(
                    "PRESENT" to "  hello world  ",
                    "EMPTY" to "",
                    "BLANK" to "   \t  ",
                ),
            )

        assertEquals("hello world", env.string("PRESENT"))
        assertEquals("hello world", env.string("PRESENT", "fallback"))
        assertEquals("hello world", env.requiredString("PRESENT"))

        assertNull(env.string("MISSING"))
        assertNull(env.string("EMPTY"))
        assertNull(env.string("BLANK"))

        assertEquals("fallback", env.string("MISSING", "fallback"))
        assertEquals("fallback", env.string("EMPTY", "fallback"))
        assertEquals("fallback", env.string("BLANK", "fallback"))

        val missingEx = assertThrows<ConfigValidationException> { env.requiredString("MISSING") }
        assertTrue(
            missingEx.message?.contains("MISSING") == true,
            "Expected missing key name in message, got: ${missingEx.message}",
        )
        val blankEx = assertThrows<ConfigValidationException> { env.requiredString("BLANK") }
        assertTrue(
            blankEx.message?.contains("BLANK") == true,
            "Expected blank key name in message, got: ${blankEx.message}",
        )
    }

    @Test
    fun `firstString returns first non-blank key in order or null and default when all unset`() {
        val env =
            EnvReader.of(
                mapOf(
                    "PRIMARY" to "   ",
                    "SECONDARY" to "  second-val  ",
                    "TERTIARY" to "third-val",
                ),
            )

        assertEquals("second-val", env.firstString("MISSING", "PRIMARY", "SECONDARY", "TERTIARY"))
        assertEquals("second-val", env.firstString("PRIMARY", "SECONDARY", default = "fallback"))
        assertNull(env.firstString("MISSING", "PRIMARY"))
        assertEquals("fallback", env.firstString("MISSING", "PRIMARY", default = "fallback"))
    }

    @Test
    fun `int, long, double, boolean, csv, and port parse valid values and defaults`() {
        val env =
            EnvReader.of(
                mapOf(
                    "INT_VAL" to " 42 ",
                    "LONG_VAL" to "9876543210",
                    "DOUBLE_VAL" to "0.75",
                    "BOOL_TRUE" to "TRUE",
                    "BOOL_FALSE" to "False",
                    "CSV_VAL" to " 123 , , 456 , 789 ",
                    "PORT_VAL" to "8081",
                    "BLANK_NUM" to "   ",
                ),
            )

        assertEquals(42, env.int("INT_VAL", 10, 1..100))
        assertEquals(10, env.int("BLANK_NUM", 10, 1..100))
        assertEquals(10, env.int("MISSING", 10))

        assertEquals(9876543210L, env.long("LONG_VAL", 5L, 1L..10_000_000_000L))
        assertEquals(5L, env.long("MISSING", 5L))

        assertEquals(0.75, env.double("DOUBLE_VAL", 0.5, 0.0..1.0))
        assertEquals(0.5, env.double("MISSING", 0.5))

        assertTrue(env.boolean("BOOL_TRUE", false), "Expected TRUE to parse as true")
        assertFalse(env.boolean("BOOL_FALSE", true), "Expected False to parse as false")
        assertTrue(env.boolean("MISSING", true), "Expected unset boolean to return default")

        assertEquals(listOf("123", "456", "789"), env.csv("CSV_VAL"))
        assertEquals(emptyList(), env.csv("MISSING"))
        assertEquals(emptyList(), env.csv("BLANK_NUM"))

        assertEquals(8081, env.port("PORT_VAL", 8080))
        assertEquals(8080, env.port("MISSING", 8080))
    }

    @Test
    fun `strict validation throws ConfigValidationException on malformed types and out-of-range numbers`() {
        val env =
            EnvReader.of(
                mapOf(
                    "BAD_INT" to "abc",
                    "OUT_OF_RANGE_INT" to "101",
                    "BAD_LONG" to "12.34",
                    "OUT_OF_RANGE_LONG" to "0",
                    "BAD_DOUBLE" to "NaN",
                    "OUT_OF_RANGE_DOUBLE" to "1.5",
                    "BOOL_YES" to "yes",
                    "BOOL_ONE" to "1",
                    "PORT_ZERO" to "0",
                    "PORT_HIGH" to "65536",
                    "PORT_BAD" to "not-a-port",
                ),
            )

        assertThrows<ConfigValidationException> { env.int("BAD_INT", 10) }
        assertThrows<ConfigValidationException> { env.int("OUT_OF_RANGE_INT", 10, 1..100) }
        assertThrows<ConfigValidationException> { env.long("BAD_LONG", 10L) }
        assertThrows<ConfigValidationException> { env.long("OUT_OF_RANGE_LONG", 10L, 1L..100L) }
        assertThrows<ConfigValidationException> { env.double("BAD_DOUBLE", 0.5) }
        assertThrows<ConfigValidationException> { env.double("OUT_OF_RANGE_DOUBLE", 0.5, 0.0..1.0) }
        assertThrows<ConfigValidationException> { env.boolean("BOOL_YES", false) }
        assertThrows<ConfigValidationException> { env.boolean("BOOL_ONE", false) }
        assertThrows<ConfigValidationException> { env.port("PORT_ZERO", 8080) }
        assertThrows<ConfigValidationException> { env.port("PORT_HIGH", 8080) }
        assertThrows<ConfigValidationException> { env.port("PORT_BAD", 8080) }
    }

    @Test
    fun `sensitive key validation error masks secret value via SecretMasker`() {
        val secretValue = "super-secret-value-123"
        val env =
            EnvReader.of(
                mapOf(
                    "DISCORD_TOKEN" to secretValue,
                    "GEMINI_API_KEY" to secretValue,
                    "CUSTOM_SECRET" to secretValue,
                ),
            )

        val tokenEx = assertThrows<ConfigValidationException> { env.int("DISCORD_TOKEN", 1) }
        assertTrue(tokenEx.message?.contains("DISCORD_TOKEN") == true, "Expected key name in message")
        assertTrue(tokenEx.message?.contains("******") == true, "Expected masked value in message")
        assertFalse(tokenEx.message?.contains(secretValue) == true, "Secret must not leak in message")

        val apiEx = assertThrows<ConfigValidationException> { env.boolean("GEMINI_API_KEY", false) }
        assertTrue(apiEx.message?.contains("GEMINI_API_KEY") == true, "Expected key name in message")
        assertFalse(apiEx.message?.contains(secretValue) == true, "Secret must not leak in message")

        val customEx = assertThrows<ConfigValidationException> { env.double("CUSTOM_SECRET", 1.0) }
        assertTrue(customEx.message?.contains("CUSTOM_SECRET") == true, "Expected key name in message")
        assertFalse(customEx.message?.contains(secretValue) == true, "Secret must not leak in message")
    }

    @Test
    fun `load merges dotenv, systemEnv, and overrides in strict precedence and audits drift`(
        @TempDir tempDir: File,
    ) {
        val envFile = File(tempDir, ".env")
        envFile.writeText(
            """
            PORT=8080
            DISCORD_TOKEN=dotenv-token
            BASE_URL=http://dotenv.example.com
            UNKNOWN_KEY=stale
            """.trimIndent(),
        )

        val schema =
            listOf(
                EnvVarSchema(name = "DISCORD_TOKEN", isRequired = true),
                EnvVarSchema(name = "PORT", isRequired = false, defaultValue = "8080"),
                EnvVarSchema(name = "BASE_URL", isRequired = false, defaultValue = "http://localhost:8080"),
            )

        val reader =
            EnvReader.load(
                envFile = envFile,
                systemEnv = mapOf("PORT" to "9090", "DISCORD_TOKEN" to "sys-token"),
                overrides = mapOf("PORT" to "7070"),
                auditDrift = true,
                schema = schema,
            )

        assertEquals(7070, reader.port("PORT", 8080))
        assertEquals("sys-token", reader.requiredString("DISCORD_TOKEN"))
        assertEquals("http://dotenv.example.com", reader.string("BASE_URL"))

        val audit = reader.driftAudit
        assertNotNull(audit)
        assertEquals(listOf("UNKNOWN_KEY"), audit.unknownKeys)
        assertEquals("******", audit.activeConfig["DISCORD_TOKEN"])

        val noAuditReader =
            EnvReader.load(
                envFile = envFile,
                systemEnv = emptyMap(),
                auditDrift = false,
            )
        assertNull(noAuditReader.driftAudit)
        assertEquals(8080, noAuditReader.port("PORT", 3000))
    }
}
