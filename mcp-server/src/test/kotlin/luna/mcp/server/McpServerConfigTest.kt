package luna.mcp.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerConfigTest {
    @Test
    fun `default values match canonical defaults`() {
        val config = McpServerConfig()

        assertEquals(8081, config.port)
        assertEquals("./data/urls.db", config.dbPath)
        assertEquals("https://woodendoor.website", config.publicBaseUrl)
        assertEquals("luna-mcp-client", config.clientId)
        assertNull(config.clientSecret)
        assertNull(config.staticAuthToken)
        assertFalse(config.requireAuth)
        assertFalse(config.isAuthEnabled)
        assertEquals("https://woodendoor.website", config.normalizedBaseUrl)
    }

    @Test
    fun `parses custom settings from map accurately`() {
        val map =
            mapOf(
                "MCP_PORT" to "9090",
                "MCP_DB_PATH" to "/tmp/test.db",
                "MCP_PUBLIC_BASE_URL" to "https://api.example.com/",
                "MCP_OAUTH_CLIENT_ID" to "custom-client",
                "MCP_OAUTH_CLIENT_SECRET" to "test-secret-123",
                "MCP_AUTH_TOKEN" to "test-token-456",
            )

        val config = McpServerConfig.fromMap(map)

        assertEquals(9090, config.port)
        assertEquals("/tmp/test.db", config.dbPath)
        assertEquals("https://api.example.com/", config.publicBaseUrl)
        assertEquals("https://api.example.com", config.normalizedBaseUrl)
        assertEquals("custom-client", config.clientId)
        assertEquals("test-secret-123", config.clientSecret)
        assertEquals("test-token-456", config.staticAuthToken)
        assertTrue(config.isAuthEnabled)
    }

    @Test
    fun `falls back to alternative property key names`() {
        val map =
            mapOf(
                "port" to 8085,
                "dbPath" to "./custom/urls.db",
                "publicBaseUrl" to "https://alt.example.com",
                "clientId" to "alt-client",
                "clientSecret" to "alt-secret",
                "staticAuthToken" to "alt-token",
            )

        val config = McpServerConfig.fromMap(map)

        assertEquals(8085, config.port)
        assertEquals("./custom/urls.db", config.dbPath)
        assertEquals("https://alt.example.com", config.publicBaseUrl)
        assertEquals("alt-client", config.clientId)
        assertEquals("alt-secret", config.clientSecret)
        assertEquals("alt-token", config.staticAuthToken)
    }

    @Test
    fun `throws validation exception when port is less than 1`() {
        val ex =
            assertThrows<McpConfigValidationException> {
                McpServerConfig(port = 0)
            }
        assertTrue(ex.message!!.contains("PORT"))
        assertTrue(ex.message!!.contains("0"))
    }

    @Test
    fun `throws validation exception when port is negative`() {
        val ex =
            assertThrows<McpConfigValidationException> {
                McpServerConfig(port = -8081)
            }
        assertTrue(ex.message!!.contains("PORT"))
    }

    @Test
    fun `throws validation exception when port is greater than 65535`() {
        val ex =
            assertThrows<McpConfigValidationException> {
                McpServerConfig(port = 70000)
            }
        assertTrue(ex.message!!.contains("PORT"))
        assertTrue(ex.message!!.contains("70000"))
    }

    @Test
    fun `accepts boundary port numbers 1 and 65535`() {
        val minConfig = McpServerConfig(port = 1)
        val maxConfig = McpServerConfig(port = 65535)

        assertEquals(1, minConfig.port)
        assertEquals(65535, maxConfig.port)
    }

    @Test
    fun `throws validation exception when port is non integer string in map`() {
        val map = mapOf("MCP_PORT" to "not-a-number")
        val ex =
            assertThrows<McpConfigValidationException> {
                McpServerConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("MCP_PORT"))
        assertTrue(ex.message!!.contains("not-a-number"))
    }

    @Test
    fun `falls back to default port when port string is empty or blank`() {
        val mapEmpty = mapOf("MCP_PORT" to "")
        val mapBlank = mapOf("MCP_PORT" to "   ")

        assertEquals(8081, McpServerConfig.fromMap(mapEmpty).port)
        assertEquals(8081, McpServerConfig.fromMap(mapBlank).port)
    }

    @Test
    fun `treats empty or whitespace secret and token as null`() {
        val map =
            mapOf(
                "MCP_OAUTH_CLIENT_SECRET" to "   ",
                "MCP_AUTH_TOKEN" to "",
            )
        val config = McpServerConfig.fromMap(map)

        assertNull(config.clientSecret)
        assertNull(config.staticAuthToken)
        assertFalse(config.isAuthEnabled)
    }

    @Test
    fun `throws validation exception when auth is expected but both secret and token are missing`() {
        val ex =
            assertThrows<McpConfigValidationException> {
                McpServerConfig.fromMap(
                    mapOf("MCP_REQUIRE_AUTH" to "true"),
                )
            }
        assertTrue(ex.message!!.contains("Authentication is required"))
    }

    @Test
    fun `throws validation exception when auth is expected via parameter and both are blank`() {
        val ex =
            assertThrows<McpConfigValidationException> {
                McpServerConfig.fromMap(
                    emptyMap(),
                    authExpected = true,
                )
            }
        assertTrue(ex.message!!.contains("Authentication is required"))
    }

    @Test
    fun `throws validation exception when requireAuth is true in constructor without credentials`() {
        val ex =
            assertThrows<McpConfigValidationException> {
                McpServerConfig(requireAuth = true)
            }
        assertTrue(ex.message!!.contains("Authentication is required"))
    }

    @Test
    fun `succeeds when auth is expected and client secret is configured`() {
        val config =
            McpServerConfig.fromMap(
                mapOf(
                    "MCP_REQUIRE_AUTH" to "true",
                    "MCP_OAUTH_CLIENT_SECRET" to "secret-key-1",
                ),
            )
        assertTrue(config.requireAuth)
        assertTrue(config.isAuthEnabled)
        assertEquals("secret-key-1", config.clientSecret)
    }

    @Test
    fun `succeeds when auth is expected and static token is configured`() {
        val config =
            McpServerConfig.fromMap(
                mapOf(
                    "MCP_REQUIRE_AUTH" to "1",
                    "MCP_AUTH_TOKEN" to "token-xyz",
                ),
            )
        assertTrue(config.requireAuth)
        assertTrue(config.isAuthEnabled)
        assertEquals("token-xyz", config.staticAuthToken)
    }

    @Test
    fun `parses dotenv string with comments quotes and exports`() {
        val dotenv =
            """
            # Database and port settings
            MCP_PORT=8888
            export MCP_DB_PATH="./custom/data/urls.db"
            MCP_PUBLIC_BASE_URL='https://woodendoor.site'
            
            # Auth credentials
            MCP_OAUTH_CLIENT_SECRET="dotenv-secret"
            MCP_AUTH_TOKEN="dotenv-token"
            """.trimIndent()

        val parsed = McpServerConfig.parseDotenv(dotenv)

        assertEquals("8888", parsed["MCP_PORT"])
        assertEquals("./custom/data/urls.db", parsed["MCP_DB_PATH"])
        assertEquals("https://woodendoor.site", parsed["MCP_PUBLIC_BASE_URL"])
        assertEquals("dotenv-secret", parsed["MCP_OAUTH_CLIENT_SECRET"])
        assertEquals("dotenv-token", parsed["MCP_AUTH_TOKEN"])
    }

    @Test
    fun `layering gives system env precedence over dotenv file`() {
        val dotenv =
            """
            MCP_PORT=8082
            MCP_PUBLIC_BASE_URL=https://from-dotenv.com
            MCP_OAUTH_CLIENT_ID=from-dotenv-client
            MCP_AUTH_TOKEN=dotenv-token
            """.trimIndent()

        val systemEnv =
            mapOf(
                "MCP_PORT" to "8089",
                "MCP_AUTH_TOKEN" to "system-env-token",
            )

        val config =
            McpServerConfig.loadFromSources(
                dotenvContent = dotenv,
                systemEnv = systemEnv,
            )

        // System env overrides dotenv
        assertEquals(8089, config.port)
        assertEquals("system-env-token", config.staticAuthToken)
        // Dotenv values retained when not overridden
        assertEquals("https://from-dotenv.com", config.publicBaseUrl)
        assertEquals("from-dotenv-client", config.clientId)
    }

    @Test
    fun `custom config overrides both system env and dotenv`() {
        val dotenv = "MCP_PORT=8082"
        val systemEnv = mapOf("MCP_PORT" to "8083")
        val customConfig = mapOf("MCP_PORT" to 8084)

        val config =
            McpServerConfig.loadFromSources(
                dotenvContent = dotenv,
                systemEnv = systemEnv,
                customConfig = customConfig,
            )

        assertEquals(8084, config.port)
    }

    @Test
    fun `detects dotenv file in current working directory`(
        @TempDir tempDir: File,
    ) {
        val envFile = File(tempDir, ".env")
        envFile.writeText("MCP_PORT=9191\nMCP_CLIENT_ID=test-current-dir")

        val detected = McpServerConfig.findDefaultEnvFile(tempDir)
        assertNotNull(detected)
        assertEquals(envFile.canonicalPath, detected.canonicalPath)

        val config = McpServerConfig.load(envFile = detected, systemEnv = emptyMap())
        assertEquals(9191, config.port)
        assertEquals("test-current-dir", config.clientId)
    }

    @Test
    fun `detects dotenv file in parent directory when current has none`(
        @TempDir tempDir: File,
    ) {
        val rootEnvFile = File(tempDir, ".env")
        rootEnvFile.writeText("MCP_PORT=9292\nMCP_DB_PATH=./parent/urls.db")

        val subprojectDir = File(tempDir, "mcp-server")
        subprojectDir.mkdirs()

        val detected = McpServerConfig.findDefaultEnvFile(subprojectDir)
        assertNotNull(detected)
        assertEquals(rootEnvFile.canonicalPath, detected.canonicalPath)

        val config = McpServerConfig.load(envFile = detected, systemEnv = emptyMap())
        assertEquals(9292, config.port)
        assertEquals("./parent/urls.db", config.dbPath)
    }

    @Test
    fun `returns null when no dotenv file exists in current or parent`(
        @TempDir tempDir: File,
    ) {
        val subDir = File(tempDir, "child")
        subDir.mkdirs()

        val detected = McpServerConfig.findDefaultEnvFile(subDir)
        assertNull(detected)
    }

    @Test
    fun `identifies sensitive keys accurately`() {
        assertTrue(McpServerConfig.isSensitiveKey("MCP_OAUTH_CLIENT_SECRET"))
        assertTrue(McpServerConfig.isSensitiveKey("MCP_AUTH_TOKEN"))
        assertTrue(McpServerConfig.isSensitiveKey("clientSecret"))
        assertTrue(McpServerConfig.isSensitiveKey("staticAuthToken"))
        assertTrue(McpServerConfig.isSensitiveKey("API_KEY"))
        assertTrue(McpServerConfig.isSensitiveKey("TOKEN"))
        assertTrue(McpServerConfig.isSensitiveKey("SECRET"))

        assertFalse(McpServerConfig.isSensitiveKey("MCP_PORT"))
        assertFalse(McpServerConfig.isSensitiveKey("MCP_DB_PATH"))
        assertFalse(McpServerConfig.isSensitiveKey("MCP_PUBLIC_BASE_URL"))
        assertFalse(McpServerConfig.isSensitiveKey("MCP_OAUTH_CLIENT_ID"))
    }

    @Test
    fun `masks sensitive configuration in map`() {
        val configMap =
            mapOf(
                "MCP_PORT" to 8081,
                "MCP_DB_PATH" to "./data/urls.db",
                "MCP_PUBLIC_BASE_URL" to "https://woodendoor.website",
                "MCP_OAUTH_CLIENT_ID" to "luna-mcp-client",
                "MCP_OAUTH_CLIENT_SECRET" to "super_secret_oauth_secret",
                "MCP_AUTH_TOKEN" to "bearer_token_12345",
            )

        val masked = McpServerConfig.maskConfig(configMap)

        assertEquals(8081, masked["MCP_PORT"])
        assertEquals("./data/urls.db", masked["MCP_DB_PATH"])
        assertEquals("https://woodendoor.website", masked["MCP_PUBLIC_BASE_URL"])
        assertEquals("luna-mcp-client", masked["MCP_OAUTH_CLIENT_ID"])
        assertEquals("******", masked["MCP_OAUTH_CLIENT_SECRET"])
        assertEquals("******", masked["MCP_AUTH_TOKEN"])
    }

    @Test
    fun `toMaskedMap masks clientSecret and staticAuthToken with asterisks`() {
        val config =
            McpServerConfig(
                port = 8081,
                clientSecret = "my-oauth-secret",
                staticAuthToken = "my-auth-token",
            )

        val masked = config.toMaskedMap()

        assertEquals(8081, masked["MCP_PORT"])
        assertEquals("******", masked["MCP_OAUTH_CLIENT_SECRET"])
        assertEquals("******", masked["MCP_AUTH_TOKEN"])
    }

    @Test
    fun `auditLog executes without error for both model and raw map`() {
        val logger = LoggerFactory.getLogger(McpServerConfigTest::class.java)
        val config =
            McpServerConfig(
                clientSecret = "secret1",
                staticAuthToken = "token1",
            )

        // Should not throw
        config.auditLog(logger)
        McpServerConfig.auditLog(config, logger)
        McpServerConfig.auditLog(config.toMaskedMap(), logger)
    }

    @Test
    fun `converts to compatible McpOAuthConfig accurately`() {
        val serverConfig =
            McpServerConfig(
                publicBaseUrl = "https://custom.woodendoor.website/",
                clientId = "custom-mcp-client",
                clientSecret = "test-secret",
                staticAuthToken = "test-token",
                requireAuth = true,
            )

        val oauthConfig = serverConfig.toOAuthConfig()

        assertEquals("https://custom.woodendoor.website", oauthConfig.publicBaseUrl)
        assertEquals("https://custom.woodendoor.website", oauthConfig.normalizedBaseUrl)
        assertEquals("custom-mcp-client", oauthConfig.clientId)
        assertEquals("test-secret", oauthConfig.clientSecret)
        assertEquals("test-token", oauthConfig.staticAuthToken)
        assertTrue(oauthConfig.requireAuth)
        assertTrue(oauthConfig.isAuthEnabled)
    }

    @Test
    fun `env example specification is discoverable and readable via classloader in mcp-server`() {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(".env.example")
        assertNotNull(stream, "Classpath resource '.env.example' must be discoverable in mcp-server")
        val content = stream.bufferedReader().use { it.readText() }
        assertTrue(content.isNotBlank(), ".env.example content must not be blank")

        assertTrue(content.contains("Section 5: Multi-Tenant Gemini MCP Server"), "Section 5 must be defined")
        assertTrue(content.contains("/mcp client create"), "Multi-tenant dynamic client create must be documented")
        assertTrue(content.contains("MCP_PORT=8081"), "MCP_PORT must be documented")
        assertTrue(content.contains("MCP_DB_PATH=./data/urls.db"), "MCP_DB_PATH must be documented")
        assertTrue(content.contains("MCP_PUBLIC_BASE_URL=https://woodendoor.website"), "MCP_PUBLIC_BASE_URL must be documented")
    }

    @Test
    fun `McpServerConfig getEnvExampleStream and loadEnvExample return authoritative content`() {
        val stream = McpServerConfig.getEnvExampleStream()
        assertNotNull(stream, "McpServerConfig.getEnvExampleStream() must return non-null stream")
        stream.close()

        val text = McpServerConfig.loadEnvExample()
        assertNotNull(text, "McpServerConfig.loadEnvExample() must return non-null string")
        assertTrue(text.contains("Section 5: Multi-Tenant Gemini MCP Server"))
    }

    @Test
    fun `audit drift rejects missing required variables with ConfigValidationException detailing missing keys`() {
        // Missing required key parameter rejection
        val exParam =
            assertThrows<ConfigValidationException> {
                McpServerConfig.auditDrift(
                    emptyMap(),
                    requiredKeys = setOf("MCP_MANDATORY_ENV"),
                )
            }
        assertTrue(exParam.message!!.contains("MCP_MANDATORY_ENV"), "Exception must detail missing MCP_MANDATORY_ENV")

        // Missing credentials when auth is expected
        val exAuth =
            assertThrows<ConfigValidationException> {
                McpServerConfig.auditDrift(
                    mapOf("MCP_REQUIRE_AUTH" to "true"),
                )
            }
        assertTrue(
            exAuth.message!!.contains("MCP_OAUTH_CLIENT_SECRET or MCP_AUTH_TOKEN"),
            "Exception must detail missing required auth credentials",
        )

        // Custom schema with required variable
        val customSchema =
            """
            # Section 5: Multi-Tenant Gemini MCP Server
            # [必填] MCP 伺服器自訂金鑰
            MCP_MANDATORY_SECRET=your_token
            """.trimIndent()
        val exSchema =
            assertThrows<ConfigValidationException> {
                McpServerConfig.auditDrift(
                    emptyMap(),
                    envExampleContent = customSchema,
                )
            }
        assertTrue(exSchema.message!!.contains("MCP_MANDATORY_SECRET"), "Exception must detail MCP_MANDATORY_SECRET")
    }

    @Test
    fun `audit drift logs structured info events for missing optional variables with applied defaults`() {
        val mcpLogger = LoggerFactory.getLogger(McpServerConfig::class.java) as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        mcpLogger.addAppender(listAppender)

        try {
            val result = McpServerConfig.auditDrift(emptyMap())

            // Assert applied defaults in missingOptional
            assertTrue(result.missingOptional.containsKey("MCP_PORT"), "MCP_PORT must be in missingOptional")
            assertEquals("8081", result.missingOptional["MCP_PORT"].toString())
            assertTrue(result.missingOptional.containsKey("MCP_DB_PATH"), "MCP_DB_PATH must be in missingOptional")
            assertEquals("./data/urls.db", result.missingOptional["MCP_DB_PATH"])
            assertTrue(result.missingOptional.containsKey("MCP_PUBLIC_BASE_URL"), "MCP_PUBLIC_BASE_URL must be in missingOptional")
            assertEquals("https://woodendoor.website", result.missingOptional["MCP_PUBLIC_BASE_URL"])
            assertTrue(result.missingOptional.containsKey("MCP_OAUTH_CLIENT_ID"), "MCP_OAUTH_CLIENT_ID must be in missingOptional")
            assertEquals("luna-mcp-client", result.missingOptional["MCP_OAUTH_CLIENT_ID"])
            assertTrue(result.missingOptional.containsKey("MCP_REQUIRE_AUTH"), "MCP_REQUIRE_AUTH must be in missingOptional")
            assertEquals("false", result.missingOptional["MCP_REQUIRE_AUTH"].toString())

            // Assert structured INFO events emitted
            val infoEvents =
                listAppender.list.filter { event ->
                    event.level == Level.INFO && event.message.contains("Layer:")
                }
            assertTrue(infoEvents.isNotEmpty(), "Audit must emit structured INFO logs for missing optional defaults")
        } finally {
            mcpLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `audit drift emits structured warn log for deprecated or unrecognized keys without terminating process`() {
        val mcpLogger = LoggerFactory.getLogger(McpServerConfig::class.java) as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        mcpLogger.addAppender(listAppender)

        try {
            val mapWithUnknown =
                mapOf(
                    "MCP_PORT" to 8081,
                    "DEPRECATED_MCP_KEY" to "legacy_val",
                    "UNKNOWN_MCP_OPTION" to "random_foo",
                )

            // Must NOT throw
            val result = McpServerConfig.auditDrift(mapWithUnknown)

            assertTrue(result.unknownKeys.contains("DEPRECATED_MCP_KEY"), "DEPRECATED_MCP_KEY must be in unknownKeys")
            assertTrue(result.unknownKeys.contains("UNKNOWN_MCP_OPTION"), "UNKNOWN_MCP_OPTION must be in unknownKeys")

            val warnEvent =
                listAppender.list.firstOrNull { event ->
                    event.level == Level.WARN && event.formattedMessage.contains("[CONFIG_DRIFT]")
                }
            assertNotNull(warnEvent, "Must emit structured WARN log containing [CONFIG_DRIFT]")
            val formatted = warnEvent.formattedMessage
            assertTrue(formatted.contains("[CONFIG_DRIFT] Unknown or deprecated environment keys:"), "Message must contain standard prefix")
            assertTrue(formatted.contains("DEPRECATED_MCP_KEY"), "Message must detail unrecognized key")
        } finally {
            mcpLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `audit drift does not emit false drift warnings for recognized alias keys`() {
        val mcpLogger = LoggerFactory.getLogger(McpServerConfig::class.java) as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        mcpLogger.addAppender(listAppender)

        try {
            val mapWithAliases =
                mapOf(
                    "PORT" to 8081, // alias for MCP_PORT
                    "dbPath" to "./data/urls.db", // alias for MCP_DB_PATH
                    "publicBaseUrl" to "https://woodendoor.website", // alias for MCP_PUBLIC_BASE_URL
                    "clientId" to "custom-client", // alias for MCP_OAUTH_CLIENT_ID
                    "clientSecret" to "sec-val", // alias for MCP_OAUTH_CLIENT_SECRET
                    "staticAuthToken" to "tok-val", // alias for MCP_AUTH_TOKEN
                    "requireAuth" to false, // alias for MCP_REQUIRE_AUTH
                    "curtly.baseUrl" to "https://woodendoor.website/s/", // cross-module alias
                    "BASE_URL" to "https://woodendoor.website/s/", // cross-module alias
                    "DISCORD_CLIENT_ID" to "cli-123", // host alias
                    "DISCORD_APP_ID" to "app-123", // host alias
                )

            val result = McpServerConfig.auditDrift(mapWithAliases)

            assertTrue(
                result.unknownKeys.isEmpty(),
                "Recognized aliases must not produce unknown drift keys, but got: ${result.unknownKeys}",
            )

            val warnEvents =
                listAppender.list.filter { event ->
                    event.level == Level.WARN && event.formattedMessage.contains("[CONFIG_DRIFT]")
                }
            assertTrue(warnEvents.isEmpty(), "No [CONFIG_DRIFT] warning should be emitted for recognized aliases")
        } finally {
            mcpLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `audit drift handles empty or corrupt schema safely and masks secrets during audit`() {
        // Corrupt schema fallback
        val corruptSchema = "### Corrupt schema without sections ###"
        val fallbackResult = McpServerConfig.auditDrift(emptyMap(), envExampleContent = corruptSchema)
        assertNotNull(fallbackResult, "Corrupt schema must fall back to default schema gracefully")
        assertTrue(fallbackResult.missingRequired.isEmpty(), "Default fallback schema without auth must not fail")

        // Secret masking verification
        val sensitiveConfig =
            mapOf(
                "MCP_PORT" to 8081,
                "MCP_OAUTH_CLIENT_SECRET" to "super_secret_mcp_oauth_secret",
                "MCP_AUTH_TOKEN" to "super_secret_mcp_static_token",
            )

        val result = McpServerConfig.auditDrift(sensitiveConfig)
        assertEquals("******", result.activeConfig["MCP_OAUTH_CLIENT_SECRET"], "MCP_OAUTH_CLIENT_SECRET must be masked")
        assertEquals("******", result.activeConfig["MCP_AUTH_TOKEN"], "MCP_AUTH_TOKEN must be masked")
    }
}
