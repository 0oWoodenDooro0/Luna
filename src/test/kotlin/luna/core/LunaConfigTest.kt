package luna.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
                "MCP_AUTH_TOKEN" to "mcp_token_202",
                "MCP_OAUTH_CLIENT_SECRET" to "oauth_secret_303",
                "NORMAL_CONFIG" to "safe_value",
            )

        val masked = ConfigLoader.maskConfig(sensitiveConfig)
        assertEquals("******", masked["DISCORD_TOKEN"])
        assertEquals("******", masked["DISCORD_CLIENT_SECRET"])
        assertEquals("******", masked["GEMINI_API_KEY"])
        assertEquals("******", masked["YOUTUBE_API_KEY"])
        assertEquals("******", masked["MCP_AUTH_TOKEN"])
        assertEquals("******", masked["MCP_OAUTH_CLIENT_SECRET"])
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
    fun `env example specification is discoverable and readable via classloader resource`() {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(".env.example")
        assertNotNull(stream, "Classpath resource '.env.example' must be discoverable")
        val content = stream.bufferedReader().use { it.readText() }
        assertTrue(content.isNotBlank(), ".env.example content must not be blank")

        // Assert all five sections are present
        assertTrue(content.contains("Section 1: Luna Host Core"), "Section 1 must be defined")
        assertTrue(content.contains("Section 2: Curtly URL Shortener"), "Section 2 must be defined")
        assertTrue(content.contains("Section 3: Summarizer & Multimodal Pipeline"), "Section 3 must be defined")
        assertTrue(content.contains("Section 4: Plugin Lifecycle & Management"), "Section 4 must be defined")
        assertTrue(content.contains("Section 5: Multi-Tenant Gemini MCP Server"), "Section 5 must be defined")

        // Assert multi-tenant workflow and quota limits
        assertTrue(content.contains("/mcp client create"), "Multi-tenant dynamic client create must be documented")
        assertTrue(content.contains("/mcp webhook add tag:<tag>"), "Tagged webhook addition must be documented")
        assertTrue(content.contains("10"), "Webhook quota limit of 10 must be documented")

        // Assert legacy single-tenant compatibility fallbacks
        assertTrue(content.contains("MCP_OAUTH_CLIENT_SECRET"), "Legacy MCP_OAUTH_CLIENT_SECRET must be documented")
        assertTrue(content.contains("MCP_AUTH_TOKEN"), "Legacy MCP_AUTH_TOKEN must be documented")
        assertTrue(content.contains("MCP_REQUIRE_AUTH"), "Legacy MCP_REQUIRE_AUTH must be documented")
        assertTrue(content.contains("Legacy Single-Tenant Compatibility Fallbacks"), "Legacy compatibility fallback section must be labeled")
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
}
