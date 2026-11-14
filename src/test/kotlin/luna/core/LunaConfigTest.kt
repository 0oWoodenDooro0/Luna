package luna.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LunaConfigTest {
    private fun validBaseMap(): MutableMap<String, Any?> =
        mutableMapOf(
            "DISCORD_TOKEN" to "test_bot_token",
            "DISCORD_CLIENT_ID" to "1234567890",
            "DISCORD_CLIENT_SECRET" to "test_client_secret",
            "PORT" to 8080,
        )

    @Test
    fun `throws validation exception when discord token is missing`() {
        val map = validBaseMap().apply { remove("DISCORD_TOKEN") }
        val ex =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("DISCORD_TOKEN"))
    }

    @Test
    fun `throws validation exception when discord token is blank`() {
        val map = validBaseMap().apply { put("DISCORD_TOKEN", "   ") }
        val ex =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("DISCORD_TOKEN"))
    }

    @Test
    fun `throws validation exception when client id is missing`() {
        val map = validBaseMap().apply { remove("DISCORD_CLIENT_ID") }
        val ex =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("DISCORD_CLIENT_ID"))
    }

    @Test
    fun `throws validation exception when client id is blank`() {
        val map = validBaseMap().apply { put("DISCORD_CLIENT_ID", "") }
        val ex =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("DISCORD_CLIENT_ID"))
    }

    @Test
    fun `succeeds when client id is missing but discord app id fallback is present`() {
        val map =
            validBaseMap().apply {
                remove("DISCORD_CLIENT_ID")
                put("DISCORD_APP_ID", "app_id_999")
            }
        val config = LunaCoreConfig.fromMap(map)
        assertEquals("app_id_999", config.clientId)
    }

    @Test
    fun `throws validation exception when client secret is missing`() {
        val map = validBaseMap().apply { remove("DISCORD_CLIENT_SECRET") }
        val ex =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("DISCORD_CLIENT_SECRET"))
    }

    @Test
    fun `throws validation exception when client secret is blank`() {
        val map = validBaseMap().apply { put("DISCORD_CLIENT_SECRET", " ") }
        val ex =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("DISCORD_CLIENT_SECRET"))
    }

    @Test
    fun `throws validation exception when port is not a valid integer`() {
        val map = validBaseMap().apply { put("PORT", "not-a-number") }
        val ex =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("PORT"))
    }

    @Test
    fun `throws validation exception when port is less than 1`() {
        val mapNegative = validBaseMap().apply { put("PORT", -1) }
        val exNegative =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(mapNegative)
            }
        assertTrue(exNegative.message!!.contains("PORT"))

        val mapZero = validBaseMap().apply { put("PORT", 0) }
        val exZero =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(mapZero)
            }
        assertTrue(exZero.message!!.contains("PORT"))
    }

    @Test
    fun `throws validation exception when port is greater than 65535`() {
        val map = validBaseMap().apply { put("PORT", 70000) }
        val ex =
            assertThrows<ConfigValidationException> {
                LunaCoreConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("PORT"))
    }

    @Test
    fun `defaults port to 8080 and baseUrl to http localhost 8080 s when omitted`() {
        val map = validBaseMap().apply { remove("PORT") }
        val config = LunaCoreConfig.fromMap(map)
        assertEquals(8080, config.port)
        assertEquals("http://localhost:8080/s/", config.baseUrl)
        assertEquals("http://localhost:8080/api/auth/discord/callback", config.redirectUri)
    }

    @Test
    fun `defaults redirectUri derived from custom baseUrl`() {
        val map = validBaseMap().apply { put("BASE_URL", "https://luna.example.com/s/") }
        val config = LunaCoreConfig.fromMap(map)
        assertEquals("https://luna.example.com/api/auth/discord/callback", config.redirectUri)

        val mapWithoutSlash = validBaseMap().apply { put("BASE_URL", "https://luna.example.com") }
        val configWithoutSlash = LunaCoreConfig.fromMap(mapWithoutSlash)
        assertEquals("https://luna.example.com/api/auth/discord/callback", configWithoutSlash.redirectUri)
    }

    @Test
    fun `uses custom redirectUri when explicitly provided`() {
        val map = validBaseMap().apply { put("DISCORD_REDIRECT_URI", "https://custom.callback/discord") }
        val config = LunaCoreConfig.fromMap(map)
        assertEquals("https://custom.callback/discord", config.redirectUri)
    }

    @Test
    fun `precedence resolution where system env overrides dotenv`() {
        val dotenv =
            """
            PORT=7000
            COMMON_KEY=from_dotenv
            ONLY_DOTENV=dotenv_val
            """.trimIndent()
        val systemEnv =
            mapOf(
                "COMMON_KEY" to "from_system_env",
                "ONLY_SYSTEM" to "system_val",
            )
        val loaded =
            ConfigLoader.loadConfigFromSources(
                dotenvContent = dotenv,
                systemEnv = systemEnv,
            )

        assertEquals("from_system_env", loaded["COMMON_KEY"])
        assertEquals("dotenv_val", loaded["ONLY_DOTENV"])
        assertEquals("system_val", loaded["ONLY_SYSTEM"])
        assertEquals("7000", loaded["PORT"])
    }

    @Test
    fun `precedence resolution where yaml placeholder resolves system env over dotenv`() {
        val dotenv =
            """
            BASE_URL=http://dotenv.local:8080
            """.trimIndent()
        val systemEnv =
            mapOf(
                "BASE_URL" to "http://env.local:8080",
            )
        val yaml =
            """
            curtly:
              baseUrl: "${'$'}{BASE_URL:http://default:8080}"
            """.trimIndent()
        val loaded =
            ConfigLoader.loadConfigFromSources(
                dotenvContent = dotenv,
                systemEnv = systemEnv,
                yamlContent = yaml,
            )

        assertEquals("http://env.local:8080", loaded["curtly.baseUrl"])
    }

    @Test
    fun `precedence resolution where yaml placeholder resolves dotenv when system env absent`() {
        val dotenv =
            """
            BASE_URL=http://dotenv.local:8080
            """.trimIndent()
        val yaml =
            """
            curtly:
              baseUrl: "${'$'}{BASE_URL:http://default:8080}"
            """.trimIndent()
        val loaded =
            ConfigLoader.loadConfigFromSources(
                dotenvContent = dotenv,
                systemEnv = emptyMap(),
                yamlContent = yaml,
            )

        assertEquals("http://dotenv.local:8080", loaded["curtly.baseUrl"])
    }

    @Test
    fun `precedence resolution where explicit yaml value overrides env and dotenv`() {
        val dotenv =
            """
            PORT=7000
            """.trimIndent()
        val systemEnv =
            mapOf(
                "PORT" to "8000",
            )
        val yaml =
            """
            PORT: 9000
            """.trimIndent()
        val loaded =
            ConfigLoader.loadConfigFromSources(
                dotenvContent = dotenv,
                systemEnv = systemEnv,
                yamlContent = yaml,
            )

        assertEquals(9000, loaded["PORT"])
    }

    @Test
    fun `audit masking masks sensitive explicit keys with asterisks`() {
        val sensitiveConfig =
            mapOf(
                "DISCORD_TOKEN" to "secret_token_123",
                "DISCORD_CLIENT_SECRET" to "secret_secret_456",
                "GEMINI_API_KEY" to "ai_key_789",
                "YOUTUBE_API_KEY" to "yt_key_101",
                "NORMAL_CONFIG" to "safe_value",
            )

        val masked = ConfigLoader.maskConfig(sensitiveConfig)
        assertEquals("******", masked["DISCORD_TOKEN"])
        assertEquals("******", masked["DISCORD_CLIENT_SECRET"])
        assertEquals("******", masked["GEMINI_API_KEY"])
        assertEquals("******", masked["YOUTUBE_API_KEY"])
        assertEquals("safe_value", masked["NORMAL_CONFIG"])
    }

    @Test
    fun `audit masking masks keys ending in secret token or key`() {
        val customKeys =
            mapOf(
                "CUSTOM_SERVICE_SECRET" to "super_secret",
                "THIRD_PARTY_AUTH_TOKEN" to "token_val",
                "AWS_ACCESS_KEY" to "access_key_val",
                "PUBLIC_PORT" to 8080,
                "PUBLIC_HOST" to "localhost",
            )

        val masked = ConfigLoader.maskConfig(customKeys)
        assertEquals("******", masked["CUSTOM_SERVICE_SECRET"])
        assertEquals("******", masked["THIRD_PARTY_AUTH_TOKEN"])
        assertEquals("******", masked["AWS_ACCESS_KEY"])
        assertEquals(8080, masked["PUBLIC_PORT"])
        assertEquals("localhost", masked["PUBLIC_HOST"])
    }

    @Test
    fun `LunaHost fails fast with ConfigValidationException on invalid config`() {
        val invalidMap =
            mapOf(
                "DISCORD_TOKEN" to "dummy_token",
                "PORT" to -5,
            )
        assertThrows<ConfigValidationException> {
            LunaHost(customConfig = invalidMap)
        }
    }

    @Test
    fun `ConfigLoader getEnvExampleStream and loadEnvExample return authoritative content`() {
        val stream = ConfigLoader.getEnvExampleStream()
        assertNotNull(stream, "ConfigLoader.getEnvExampleStream() must return non-null stream")
        stream.close()

        val text = ConfigLoader.loadEnvExample()
        assertNotNull(text, "ConfigLoader.loadEnvExample() must return non-null string")
        assertTrue(text.contains("Luna 統一環境變數設定檔範例 (.env.example)"))
    }

    @Test
    fun `audit drift rejects missing required variables with ConfigValidationException detailing missing keys`() {
        // Missing all required variables
        val exAll =
            assertThrows<ConfigValidationException> {
                ConfigLoader.auditDrift(emptyMap())
            }
        assertTrue(exAll.message!!.contains("DISCORD_TOKEN"), "Exception must detail DISCORD_TOKEN")
        assertTrue(exAll.message!!.contains("DISCORD_CLIENT_ID"), "Exception must detail DISCORD_CLIENT_ID")
        assertTrue(exAll.message!!.contains("DISCORD_CLIENT_SECRET"), "Exception must detail DISCORD_CLIENT_SECRET")

        // Missing single required variable
        val exSingle =
            assertThrows<ConfigValidationException> {
                ConfigLoader.auditDrift(
                    mapOf(
                        "DISCORD_TOKEN" to "token_123",
                        "DISCORD_CLIENT_SECRET" to "secret_456",
                    ),
                )
            }
        assertTrue(exSingle.message!!.contains("DISCORD_CLIENT_ID"), "Exception must detail missing DISCORD_CLIENT_ID")

        // Explicit required key parameter rejection
        val exParam =
            assertThrows<ConfigValidationException> {
                ConfigLoader.auditDrift(
                    validBaseMap(),
                    requiredKeys = setOf("CUSTOM_MANDATORY_ENV"),
                )
            }
        assertTrue(exParam.message!!.contains("CUSTOM_MANDATORY_ENV"), "Exception must detail CUSTOM_MANDATORY_ENV")
    }

    @Test
    fun `audit drift logs structured info events for missing optional variables with applied defaults`() {
        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        jsonLogger.addAppender(listAppender)

        try {
            val result = ConfigLoader.auditDrift(validBaseMap().apply { remove("PORT") })

            // Verify missing optional entries recorded with defaults
            assertTrue(result.missingOptional.containsKey("PORT"), "PORT must be recorded in missingOptional")
            assertEquals("8080", result.missingOptional["PORT"].toString())
            assertTrue(result.missingOptional.containsKey("BASE_URL"), "BASE_URL must be recorded in missingOptional")
            assertEquals("http://localhost:8080/s/", result.missingOptional["BASE_URL"])
            assertTrue(result.missingOptional.containsKey("GEMINI_MODEL"), "GEMINI_MODEL must be recorded in missingOptional")
            assertEquals("gemini-3.5-flash-lite", result.missingOptional["GEMINI_MODEL"])

            // Verify INFO structured log was emitted
            val infoLogs =
                listAppender.list.filter { event ->
                    event.level == Level.INFO && event.message.contains("Layer:")
                }
            assertTrue(infoLogs.isNotEmpty(), "Startup audit must emit structured INFO logs for missing optional defaults")
        } finally {
            jsonLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `audit drift emits structured warn log for deprecated or unrecognized keys without terminating process`() {
        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        jsonLogger.addAppender(listAppender)

        try {
            val configWithUnknown =
                validBaseMap().apply {
                    put("DEPRECATED_LEGACY_FLAG", "old_val")
                    put("UNKNOWN_OPERATOR_CONFIG", "random_123")
                }

            // Must NOT throw
            val result = ConfigLoader.auditDrift(configWithUnknown)

            assertTrue(result.unknownKeys.contains("DEPRECATED_LEGACY_FLAG"), "DEPRECATED_LEGACY_FLAG must be flagged")
            assertTrue(result.unknownKeys.contains("UNKNOWN_OPERATOR_CONFIG"), "UNKNOWN_OPERATOR_CONFIG must be flagged")

            // Verify structured WARN log with [CONFIG_DRIFT] prefix was emitted
            val warnEvent =
                listAppender.list.firstOrNull { event ->
                    event.level == Level.WARN && event.formattedMessage.contains("[CONFIG_DRIFT]")
                }
            assertNotNull(warnEvent, "Must emit structured WARN log containing [CONFIG_DRIFT]")
            val formatted = warnEvent.formattedMessage
            assertTrue(formatted.contains("[CONFIG_DRIFT] Unknown or deprecated environment keys:"), "Message must contain standard prefix")
            assertTrue(formatted.contains("DEPRECATED_LEGACY_FLAG"), "Message must detail unrecognized key")
        } finally {
            jsonLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `audit drift does not emit false drift warnings for recognized alias keys and dual namespace properties`() {
        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        jsonLogger.addAppender(listAppender)

        try {
            val configWithAliases =
                mutableMapOf<String, Any?>(
                    "DISCORD_TOKEN" to "token_val",
                    "DISCORD_APP_ID" to "app_id_val", // alias for DISCORD_CLIENT_ID
                    "clientSecret" to "secret_val", // alias for DISCORD_CLIENT_SECRET
                    "port" to 8080, // alias for PORT
                    "MCP_PORT" to 8081, // cross-subproject alias
                    "curtly.baseUrl" to "https://short.custom/s/", // dual-namespace plugin key
                    "summarizer.emoji" to "✨", // dual-namespace plugin key
                    "summary.maxHistoryMessages" to 60, // dual-namespace plugin key
                    "gemini.apiKey" to "ai_key", // dual-namespace plugin key
                    "PLUGINS_CURTLY_ENABLED" to "true", // plugin lifecycle toggle
                )

            val result = ConfigLoader.auditDrift(configWithAliases)

            assertTrue(
                result.unknownKeys.isEmpty(),
                "Aliases and dual-namespace keys must not produce unknown drift keys, but got: ${result.unknownKeys}",
            )

            val warnEvents =
                listAppender.list.filter { event ->
                    event.level == Level.WARN && event.formattedMessage.contains("[CONFIG_DRIFT]")
                }
            assertTrue(warnEvents.isEmpty(), "No [CONFIG_DRIFT] warning should be emitted for recognized aliases")
        } finally {
            jsonLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `audit drift handles empty or corrupt schema safely and masks secrets during audit`() {
        // Negative boundary: corrupt schema string fallback
        val corruptSchema = "### Corrupt non-schema content without sections or variables ###"
        val fallbackResult = ConfigLoader.auditDrift(validBaseMap(), envExampleContent = corruptSchema)
        assertNotNull(fallbackResult, "Corrupt schema must fall back to default schema gracefully")
        assertTrue(fallbackResult.missingRequired.isEmpty(), "Valid config must satisfy fallback schema")

        // Secret masking verification
        val sensitiveConfig =
            validBaseMap().apply {
                put("DISCORD_TOKEN", "super_secret_discord_bot_token")
                put("DISCORD_CLIENT_SECRET", "super_secret_discord_oauth_secret")
                put("GEMINI_API_KEY", "super_secret_gemini_api_key")
                put("YOUTUBE_API_KEY", "super_secret_youtube_data_api_key")
                put("SESSION_SECRET", "super_secret_session_secret_key")
            }

        val result = ConfigLoader.auditDrift(sensitiveConfig)
        assertEquals("******", result.activeConfig["DISCORD_TOKEN"], "DISCORD_TOKEN must be masked")
        assertEquals("******", result.activeConfig["DISCORD_CLIENT_SECRET"], "DISCORD_CLIENT_SECRET must be masked")
        assertEquals("******", result.activeConfig["GEMINI_API_KEY"], "GEMINI_API_KEY must be masked")
        assertEquals("******", result.activeConfig["YOUTUBE_API_KEY"], "YOUTUBE_API_KEY must be masked")
        assertEquals("******", result.activeConfig["SESSION_SECRET"], "SESSION_SECRET must be masked")
    }

    @Test
    fun `AiTraceConfig resolves defaults and overrides from map without secret masking`() {
        val defaults =
            luna.core.ai.AiTraceConfig
                .fromMap(emptyMap())
        assertEquals(true, defaults.enabled)
        assertEquals("data/logs/ai", defaults.dir)
        assertEquals(14, defaults.maxDays)
        assertEquals(200, defaults.maxTotalMb)

        val configured =
            luna.core.ai.AiTraceConfig.fromMap(
                mapOf(
                    "AI_TRACE_ENABLED" to "false",
                    "AI_TRACE_DIR" to "/var/log/luna-ai",
                    "AI_TRACE_MAX_DAYS" to "7",
                    "AI_TRACE_MAX_TOTAL_MB" to "50",
                ),
            )
        assertEquals(false, configured.enabled)
        assertEquals("/var/log/luna-ai", configured.dir)
        assertEquals(7, configured.maxDays)
        assertEquals(50, configured.maxTotalMb)

        // Negative boundary: non-positive numbers fall back to defaults
        val invalidBounds =
            luna.core.ai.AiTraceConfig.fromMap(
                mapOf(
                    "AI_TRACE_MAX_DAYS" to "0",
                    "AI_TRACE_MAX_TOTAL_MB" to "-10",
                ),
            )
        assertEquals(14, invalidBounds.maxDays)
        assertEquals(200, invalidBounds.maxTotalMb)

        val snakeAliases =
            luna.core.ai.AiTraceConfig.fromMap(
                mapOf(
                    "ai.trace.enabled" to false,
                    "ai.trace.dir" to "/tmp/ai-map-snake",
                    "ai.trace.max_days" to 10,
                    "ai.trace.max_total_mb" to 80,
                ),
            )
        assertEquals(false, snakeAliases.enabled)
        assertEquals("/tmp/ai-map-snake", snakeAliases.dir)
        assertEquals(10, snakeAliases.maxDays)
        assertEquals(80, snakeAliases.maxTotalMb)

        // Verify drift audit recognizes AI_TRACE_* and ai.trace.* and does not mask them
        val audit =
            ConfigLoader.auditDrift(
                validBaseMap().apply {
                    put("AI_TRACE_ENABLED", "true")
                    put("AI_TRACE_DIR", "data/logs/ai")
                    put("AI_TRACE_MAX_DAYS", "14")
                    put("AI_TRACE_MAX_TOTAL_MB", "200")
                    put("ai.trace.enabled", "true")
                },
            )
        assertTrue(audit.unknownKeys.isEmpty(), "AI_TRACE_* and ai.trace.* must be recognized, got unknown=${audit.unknownKeys}")
        assertEquals("data/logs/ai", audit.activeConfig["AI_TRACE_DIR"])
    }
}
