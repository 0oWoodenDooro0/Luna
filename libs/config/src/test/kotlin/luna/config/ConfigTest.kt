package luna.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `DotenvParser parses standard, exported, quoted, and commented variables`() {
        val raw =
            """
            # Database and API keys
            PORT=8080
            export DISCORD_TOKEN="my-secret-token"
            BASE_URL='http://localhost:8080/s/'
            export GEMINI_MODEL=gemini-3.5-flash-lite
            
            # Trailing comment
            UNKNOWN_KEY=hello world
            """.trimIndent()

        val parsed = DotenvParser.parse(raw)
        assertEquals("8080", parsed["PORT"])
        assertEquals("my-secret-token", parsed["DISCORD_TOKEN"])
        assertEquals("http://localhost:8080/s/", parsed["BASE_URL"])
        assertEquals("gemini-3.5-flash-lite", parsed["GEMINI_MODEL"])
        assertEquals("hello world", parsed["UNKNOWN_KEY"])
        assertFalse(parsed.containsKey("# Database and API keys"))
    }

    @Test
    fun `SecretMasker identifies and masks sensitive keys and nested maps`() {
        assertTrue(SecretMasker.isSensitiveKey("DISCORD_TOKEN"))
        assertTrue(SecretMasker.isSensitiveKey("DISCORD_CLIENT_SECRET"))
        assertTrue(SecretMasker.isSensitiveKey("GEMINI_API_KEY"))
        assertTrue(SecretMasker.isSensitiveKey("curtly.clientSecret"))
        assertTrue(SecretMasker.isSensitiveKey("MY_SECRET"))
        assertTrue(SecretMasker.isSensitiveKey("APP_TOKEN"))
        assertTrue(SecretMasker.isSensitiveKey("API_KEY"))
        assertFalse(SecretMasker.isSensitiveKey("PORT"))
        assertFalse(SecretMasker.isSensitiveKey("BASE_URL"))
        assertFalse(SecretMasker.isSensitiveKey("SUMMARY_MAX_HISTORY_MESSAGES"))

        val rawConfig: Map<String, Any?> =
            mapOf(
                "DISCORD_TOKEN" to "super-secret-token",
                "PORT" to 8080,
                "nested" to
                    mapOf(
                        "apiKey" to "gemini-12345",
                        "publicPort" to 9090,
                    ),
            )

        val masked = SecretMasker.maskConfig(rawConfig)
        assertEquals("******", masked["DISCORD_TOKEN"])
        assertEquals(8080, masked["PORT"])

        val nested = masked["nested"] as Map<*, *>
        assertEquals("******", nested["apiKey"])
        assertEquals(9090, nested["publicPort"])
    }

    @Test
    fun `PlaceholderResolver resolves variables and defaults`() {
        val config = mapOf("HOST" to "api.luna.com", "PORT" to "8080")

        assertEquals("api.luna.com", PlaceholderResolver.resolve("\${HOST}", config))
        assertEquals("api.luna.com", PlaceholderResolver.resolve("\${?HOST}", config))
        assertEquals("8080", PlaceholderResolver.resolve("\${PORT:9000}", config))
        assertEquals("fallback", PlaceholderResolver.resolve("\${MISSING:fallback}", config))
        assertEquals("http://api.luna.com:8080/v1", PlaceholderResolver.resolve("http://\${HOST}:\${PORT}/v1", config))
        assertEquals("plain text", PlaceholderResolver.resolve("plain text", config))
    }

    @Test
    fun `EnvSchemaAuditor parses schema and executes drift audit`() {
        val schemaContent =
            """
            # Section 1: Core
            # [必填] Discord 機器人 Token
            DISCORD_TOKEN=your_token_here
            
            # [選填] 通訊埠
            # 預設值：8080
            PORT=8080
            """.trimIndent()

        val schemas = EnvSchemaAuditor.parseEnvExampleSchema(schemaContent)
        assertEquals(2, schemas.size)
        val tokenSchema = schemas.first { it.name == "DISCORD_TOKEN" }
        assertTrue(tokenSchema.isRequired)
        assertEquals(1, tokenSchema.section)

        val portSchema = schemas.first { it.name == "PORT" }
        assertFalse(portSchema.isRequired)
        assertEquals("8080", portSchema.defaultValue)

        // Test drift audit with missing required key
        val emptyConfig = emptyMap<String, Any?>()
        val drift1 = EnvSchemaAuditor.auditDrift(emptyConfig, schemas)
        assertTrue(drift1.missingRequired.contains("DISCORD_TOKEN"))
        assertEquals("8080", drift1.missingOptional["PORT"])

        // Test drift audit with satisfied config and unknown key
        val satisfiedConfig =
            mapOf(
                "DISCORD_TOKEN" to "token_123",
                "PORT" to "8080",
                "COMPLETELY_RANDOM_KEY" to "value",
            )
        val drift2 = EnvSchemaAuditor.auditDrift(satisfiedConfig, schemas)
        assertTrue(drift2.missingRequired.isEmpty())
        assertTrue(drift2.unknownKeys.contains("COMPLETELY_RANDOM_KEY"))
    }

    @Test
    fun `ConfigFinder discovers direct and parent dotenv files`(
        @TempDir tempDir: File,
    ) {
        val subDir = File(tempDir, "submodule").apply { mkdirs() }
        val envFile = File(tempDir, ".env").apply { writeText("PORT=8080\n") }

        val foundFromSub = ConfigFinder.findDefaultEnvFile(subDir)
        assertNotNull(foundFromSub)
        assertEquals(envFile.canonicalPath, foundFromSub.canonicalPath)

        val foundFromRoot = ConfigFinder.findDefaultEnvFile(tempDir)
        assertNotNull(foundFromRoot)
        assertEquals(envFile.canonicalPath, foundFromRoot.canonicalPath)
    }

    @Test
    fun `EnvSchemaAuditor recognizes AI_TRACE keys and ai trace aliases`() {
        val fallbackKeys = EnvSchemaAuditor.defaultFallbackSchema().map { it.name }.toSet()
        assertTrue(fallbackKeys.contains("AI_TRACE_ENABLED"))
        assertTrue(fallbackKeys.contains("AI_TRACE_DIR"))
        assertTrue(fallbackKeys.contains("AI_TRACE_MAX_DAYS"))
        assertTrue(fallbackKeys.contains("AI_TRACE_MAX_TOTAL_MB"))

        assertTrue(EnvSchemaAuditor.isRecognizedKey("AI_TRACE_ENABLED", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("ai.trace.enabled", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("ai.trace.dir", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("ai.trace.maxDays", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("ai.trace.maxTotalMb", fallbackKeys))

        val aliasConfig =
            mapOf(
                "ai.trace.enabled" to "false",
                "ai.trace.dir" to "/tmp/traces",
                "ai.trace.maxDays" to "7",
                "ai.trace.maxTotalMb" to "64",
            )
        assertEquals("false", EnvSchemaAuditor.resolveVariableValue("AI_TRACE_ENABLED", aliasConfig))
        assertEquals("/tmp/traces", EnvSchemaAuditor.resolveVariableValue("AI_TRACE_DIR", aliasConfig))
        assertEquals("7", EnvSchemaAuditor.resolveVariableValue("AI_TRACE_MAX_DAYS", aliasConfig))
        assertEquals("64", EnvSchemaAuditor.resolveVariableValue("AI_TRACE_MAX_TOTAL_MB", aliasConfig))
    }

    @Test
    fun `EnvSchemaAuditor recognizes CHATTER debounce keys and chatter aliases`() {
        val fallbackKeys = EnvSchemaAuditor.defaultFallbackSchema().map { it.name }.toSet()
        assertTrue(fallbackKeys.contains("CHATTER_DEBOUNCE_SECONDS"))
        assertTrue(fallbackKeys.contains("CHATTER_MENTION_DEBOUNCE_SECONDS"))
        assertTrue(fallbackKeys.contains("CHATTER_DEBOUNCE_MAX_SECONDS"))
        assertTrue(fallbackKeys.contains("CHATTER_MAX_INTERRUPTS"))

        assertTrue(EnvSchemaAuditor.isRecognizedKey("CHATTER_DEBOUNCE_SECONDS", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("chatter.debounceSeconds", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("chatter.mentionDebounceSeconds", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("chatter.debounceMaxSeconds", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("chatter.maxInterrupts", fallbackKeys))

        val aliasConfig =
            mapOf(
                "chatter.debounceSeconds" to "2.5",
                "chatter.mentionDebounceSeconds" to "1.2",
                "chatter.debounceMaxSeconds" to "7.0",
                "chatter.maxInterrupts" to "2",
            )
        assertEquals("2.5", EnvSchemaAuditor.resolveVariableValue("CHATTER_DEBOUNCE_SECONDS", aliasConfig))
        assertEquals("1.2", EnvSchemaAuditor.resolveVariableValue("CHATTER_MENTION_DEBOUNCE_SECONDS", aliasConfig))
        assertEquals("7.0", EnvSchemaAuditor.resolveVariableValue("CHATTER_DEBOUNCE_MAX_SECONDS", aliasConfig))
        assertEquals("2", EnvSchemaAuditor.resolveVariableValue("CHATTER_MAX_INTERRUPTS", aliasConfig))
    }

    @Test
    fun `EnvSchemaAuditor recognizes CHATTER_TIMEZONE, CHATTER_DRIFT_ENABLED, and chatter aliases`() {
        val fallbackKeys = EnvSchemaAuditor.defaultFallbackSchema().map { it.name }.toSet()
        assertTrue(fallbackKeys.contains("CHATTER_TIMEZONE"))
        assertTrue(fallbackKeys.contains("CHATTER_DRIFT_ENABLED"))

        assertTrue(EnvSchemaAuditor.isRecognizedKey("CHATTER_TIMEZONE", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("CHATTER_DRIFT_ENABLED", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("chatter.timezone", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("chatter.driftEnabled", fallbackKeys))

        val aliasConfig =
            mapOf(
                "chatter.timezone" to "Asia/Tokyo",
                "chatter.driftEnabled" to "false",
            )
        assertEquals("Asia/Tokyo", EnvSchemaAuditor.resolveVariableValue("CHATTER_TIMEZONE", aliasConfig))
        assertEquals("false", EnvSchemaAuditor.resolveVariableValue("CHATTER_DRIFT_ENABLED", aliasConfig))
    }
}
