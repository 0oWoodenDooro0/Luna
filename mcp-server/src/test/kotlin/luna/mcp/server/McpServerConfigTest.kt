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
        assertEquals("./.data/urls.db", config.dbPath)
        assertEquals("https://woodendoor.website", config.publicBaseUrl)
        assertEquals("https://woodendoor.website", config.normalizedBaseUrl)
    }

    @Test
    fun `parses custom settings from map accurately`() {
        val map =
            mapOf(
                "MCP_PORT" to "9090",
                "MCP_DB_PATH" to "/tmp/test.db",
                "MCP_PUBLIC_BASE_URL" to "https://api.example.com/",
            )

        val config = McpServerConfig.fromMap(map)

        assertEquals(9090, config.port)
        assertEquals("/tmp/test.db", config.dbPath)
        assertEquals("https://api.example.com/", config.publicBaseUrl)
        assertEquals("https://api.example.com", config.normalizedBaseUrl)
    }

    @Test
    fun `falls back to alternative property key names`() {
        val map =
            mapOf(
                "port" to 8085,
                "dbPath" to "./custom/urls.db",
                "publicBaseUrl" to "https://alt.example.com",
            )

        val config = McpServerConfig.fromMap(map)

        assertEquals(8085, config.port)
        assertEquals("./custom/urls.db", config.dbPath)
        assertEquals("https://alt.example.com", config.publicBaseUrl)
    }

    @Test
    fun `validates port boundary lower and upper limits`() {
        assertThrows<McpConfigValidationException> {
            McpServerConfig(port = 0)
        }
        assertThrows<McpConfigValidationException> {
            McpServerConfig(port = -8081)
        }
        assertThrows<McpConfigValidationException> {
            McpServerConfig(port = 70000)
        }

        val minConfig = McpServerConfig(port = 1)
        val maxConfig = McpServerConfig(port = 65535)
        assertEquals(1, minConfig.port)
        assertEquals(65535, maxConfig.port)
    }

    @Test
    fun `throws when port string cannot be converted to integer`() {
        val map = mapOf("MCP_PORT" to "not-a-number")
        val ex =
            assertThrows<McpConfigValidationException> {
                McpServerConfig.fromMap(map)
            }
        assertTrue(ex.message!!.contains("MCP_PORT must be an integer"))
    }

    @Test
    fun `empty or blank port in map falls back to default 8081`() {
        val mapEmpty = mapOf("MCP_PORT" to "")
        val mapBlank = mapOf("MCP_PORT" to "   ")
        assertEquals(8081, McpServerConfig.fromMap(mapEmpty).port)
        assertEquals(8081, McpServerConfig.fromMap(mapBlank).port)
    }

    @Test
    fun `parses dotenv string with comments quotes and exports`() {
        val dotenv =
            """
            # Database and port settings
            MCP_PORT=8888
            export MCP_DB_PATH="./custom/data/urls.db"
            MCP_PUBLIC_BASE_URL='https://woodendoor.site'
            """.trimIndent()

        val parsed = McpServerConfig.parseDotenv(dotenv)

        assertEquals("8888", parsed["MCP_PORT"])
        assertEquals("./custom/data/urls.db", parsed["MCP_DB_PATH"])
        assertEquals("https://woodendoor.site", parsed["MCP_PUBLIC_BASE_URL"])
    }

    @Test
    fun `precedence order obeys dotenv overridden by system env`() {
        val dotenv =
            """
            MCP_PORT=8081
            MCP_PUBLIC_BASE_URL=https://from-dotenv.com
            """.trimIndent()

        val systemEnv =
            mapOf(
                "MCP_PORT" to "8089",
            )

        val config =
            McpServerConfig.loadFromSources(
                dotenvContent = dotenv,
                systemEnv = systemEnv,
            )

        // System env overrides dotenv
        assertEquals(8089, config.port)
        // Dotenv values retained when not overridden
        assertEquals("https://from-dotenv.com", config.publicBaseUrl)
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
        envFile.writeText("MCP_PORT=9191")

        val detected = McpServerConfig.findDefaultEnvFile(tempDir)
        assertNotNull(detected)
        assertEquals(envFile.canonicalPath, detected.canonicalPath)

        val config = McpServerConfig.load(envFile = detected, systemEnv = emptyMap())
        assertEquals(9191, config.port)
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
    fun `isSensitiveKey detects token and secret keys accurately`() {
        assertTrue(McpServerConfig.isSensitiveKey("API_KEY"))
        assertTrue(McpServerConfig.isSensitiveKey("TOKEN"))
        assertTrue(McpServerConfig.isSensitiveKey("SECRET"))

        assertFalse(McpServerConfig.isSensitiveKey("MCP_PORT"))
        assertFalse(McpServerConfig.isSensitiveKey("MCP_DB_PATH"))
        assertFalse(McpServerConfig.isSensitiveKey("MCP_PUBLIC_BASE_URL"))
    }

    @Test
    fun `toMaskedMap produces active configuration map`() {
        val config =
            McpServerConfig(
                port = 8081,
                dbPath = "./.data/urls.db",
                publicBaseUrl = "https://woodendoor.website",
            )

        val masked = config.toMaskedMap()

        assertEquals(8081, masked["MCP_PORT"])
        assertEquals("./.data/urls.db", masked["MCP_DB_PATH"])
        assertEquals("https://woodendoor.website", masked["MCP_PUBLIC_BASE_URL"])
    }

    @Test
    fun `auditLog executes without error for both model and raw map`() {
        val logger = LoggerFactory.getLogger(McpServerConfigTest::class.java)
        val config =
            McpServerConfig(
                port = 8081,
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
            )

        val oauthConfig = serverConfig.toOAuthConfig()

        assertEquals("https://custom.woodendoor.website", oauthConfig.publicBaseUrl)
        assertEquals("https://custom.woodendoor.website", oauthConfig.normalizedBaseUrl)
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
        assertTrue(content.contains("MCP_DB_PATH=./.data/urls.db"), "MCP_DB_PATH must be documented")
        assertTrue(content.contains("MCP_PUBLIC_BASE_URL=https://woodendoor.website"), "MCP_PUBLIC_BASE_URL must be documented")
    }

    @Test
    fun `audit drift throws on missing required variables in custom schema`() {
        val customSchemaWithRequired =
            """
            # Section 5: MCP
            # [必填] 必要的測試金鑰
            MCP_MANDATORY_SECRET=
            """.trimIndent()

        val exSchema =
            assertThrows<McpConfigValidationException> {
                McpServerConfig.auditDrift(
                    emptyMap(),
                    envExampleContent = customSchemaWithRequired,
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
            assertEquals("./.data/urls.db", result.missingOptional["MCP_DB_PATH"])
            assertTrue(result.missingOptional.containsKey("MCP_PUBLIC_BASE_URL"), "MCP_PUBLIC_BASE_URL must be in missingOptional")
            assertEquals("https://woodendoor.website", result.missingOptional["MCP_PUBLIC_BASE_URL"])

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
                    "dbPath" to "./.data/urls.db", // alias for MCP_DB_PATH
                    "publicBaseUrl" to "https://woodendoor.website", // alias for MCP_PUBLIC_BASE_URL
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
                "API_KEY" to "super_secret_api_key",
                "MY_SECRET" to "super_secret_token_val",
            )

        val result = McpServerConfig.auditDrift(sensitiveConfig)
        assertEquals("******", result.activeConfig["API_KEY"], "API_KEY must be masked")
        assertEquals("******", result.activeConfig["MY_SECRET"], "MY_SECRET must be masked")
    }
}
