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
    fun `EnvSchemaAuditor recognizes canonical schema keys and PLUGINS_NAME_ENABLED only`() {
        val fallbackSchema = EnvSchemaAuditor.defaultFallbackSchema()
        val fallbackKeys = fallbackSchema.map { it.name }.toSet()
        assertTrue(fallbackKeys.contains("AI_TRACE_ENABLED"))
        assertTrue(fallbackKeys.contains("AI_TRACE_DIR"))
        assertTrue(fallbackKeys.contains("AI_TRACE_MAX_DAYS"))
        assertTrue(fallbackKeys.contains("AI_TRACE_MAX_TOTAL_MB"))
        assertTrue(fallbackKeys.contains("CHATTER_DEBOUNCE_SECONDS"))
        assertTrue(fallbackKeys.contains("CHATTER_MENTION_DEBOUNCE_SECONDS"))
        assertTrue(fallbackKeys.contains("CHATTER_DEBOUNCE_MAX_SECONDS"))
        assertTrue(fallbackKeys.contains("CHATTER_MAX_INTERRUPTS"))
        assertTrue(fallbackKeys.contains("CHATTER_TIMEZONE"))
        assertTrue(fallbackKeys.contains("CHATTER_DRIFT_ENABLED"))
        assertTrue(fallbackKeys.contains("CHATTER_REACTION_THRESHOLD"))

        assertTrue(EnvSchemaAuditor.isRecognizedKey("AI_TRACE_ENABLED", fallbackKeys))
        assertTrue(EnvSchemaAuditor.isRecognizedKey("PLUGINS_CHATTER_ENABLED", fallbackKeys))

        // Legacy aliases and removed keys must NOT be recognized
        assertFalse(EnvSchemaAuditor.isRecognizedKey("PLUGINS_DISABLED", fallbackKeys))
        assertFalse(EnvSchemaAuditor.isRecognizedKey("ai.trace.enabled", fallbackKeys))
        assertFalse(EnvSchemaAuditor.isRecognizedKey("chatter.debounceSeconds", fallbackKeys))
        assertFalse(EnvSchemaAuditor.isRecognizedKey("curtly.baseUrl", fallbackKeys))
        assertFalse(EnvSchemaAuditor.isRecognizedKey("DISCORD_APP_ID", fallbackKeys))
        assertFalse(EnvSchemaAuditor.isRecognizedKey("PLUGINS_CURTLY_BASE_URL", fallbackKeys))
    }

    @Test
    fun `EnvSchemaAuditor ignores Docker system env keys while flagging removed legacy alias keys as unknown`() {
        val fallbackSchema = EnvSchemaAuditor.defaultFallbackSchema()
        val timeoutSchema = fallbackSchema.firstOrNull { it.name == "CHATTER_DECISION_TIMEOUT_SECONDS" }
        assertNotNull(timeoutSchema)
        assertEquals("15", timeoutSchema.defaultValue)

        val config =
            mapOf(
                "DISCORD_TOKEN" to "tok",
                "JAVA_VERSION" to "jdk-17.0.14+7",
                "LANGUAGE" to "en_US:en",
                "PLUGINS_DISABLED" to "undercover",
                "curtly.baseUrl" to "http://localhost:8080",
                "DISCORD_APP_ID" to "legacy-app-id",
                "PLUGINS_CURTLY_BASE_URL" to "http://localhost:8080",
                "legacy.unknownKey" to "stale",
            )
        val result = EnvSchemaAuditor.auditDrift(config, fallbackSchema)
        assertFalse(
            result.unknownKeys.contains("JAVA_VERSION"),
            "JAVA_VERSION must not be flagged as unknown, got ${result.unknownKeys}",
        )
        assertFalse(
            result.unknownKeys.contains("LANGUAGE"),
            "LANGUAGE must not be flagged as unknown, got ${result.unknownKeys}",
        )
        assertEquals(
            listOf("PLUGINS_DISABLED", "curtly.baseUrl", "DISCORD_APP_ID", "PLUGINS_CURTLY_BASE_URL", "legacy.unknownKey"),
            result.unknownKeys,
        )
    }
}
