package luna.summarizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiConfigTest {
    @Test
    fun `defaults model to gemini-3_5-flash-lite when not specified`() {
        val config = GeminiConfig(apiKey = "test-api-key")
        assertEquals("test-api-key", config.apiKey)
        assertEquals("gemini-3.5-flash-lite", config.model)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `fromEnv loads keys and defaults correctly`() {
        val env =
            mapOf(
                "GEMINI_API_KEY" to "env-key-123",
                "GEMINI_MODEL" to "gemini-1.5-pro",
            )
        val config = GeminiConfig.fromEnv(env)
        assertEquals("env-key-123", config.apiKey)
        assertEquals("gemini-1.5-pro", config.model)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `fromEnv falls back to default model when GEMINI_MODEL is absent or blank`() {
        val env = mapOf("GEMINI_API_KEY" to "env-key-123")
        val config = GeminiConfig.fromEnv(env)
        assertEquals("gemini-3.5-flash-lite", config.model)

        val envBlank = mapOf("GEMINI_API_KEY" to "env-key-123", "GEMINI_MODEL" to "  ")
        val configBlank = GeminiConfig.fromEnv(envBlank)
        assertEquals("gemini-3.5-flash-lite", configBlank.model)
    }

    @Test
    fun `isConfigured returns false when apiKey is blank or missing`() {
        val emptyConfig = GeminiConfig(apiKey = "")
        assertFalse(emptyConfig.isConfigured)

        val blankConfig = GeminiConfig(apiKey = "   ")
        assertFalse(blankConfig.isConfigured)

        val envConfig = GeminiConfig.fromEnv(emptyMap<String, String>())
        assertFalse(envConfig.isConfigured)
        assertEquals("", envConfig.apiKey)
        assertEquals("gemini-3.5-flash-lite", envConfig.model)
    }

    @Test
    fun `cleanModel and cleanBaseUrl sanitize models prefix and trailing v1beta path`() {
        val config =
            GeminiConfig(
                apiKey = "key",
                model = "models/gemini-3.5-flash-lite",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            )
        assertEquals("gemini-3.5-flash-lite", config.cleanModel)
        assertEquals("https://generativelanguage.googleapis.com", config.cleanBaseUrl)

        val fromEnvConfig =
            GeminiConfig.fromEnv(
                mapOf(
                    "GEMINI_API_KEY" to "key",
                    "GEMINI_MODEL" to "models/gemini-2.0-flash",
                    "GEMINI_BASE_URL" to "https://custom.endpoint.com/v1beta",
                ),
            )
        assertEquals("gemini-2.0-flash", fromEnvConfig.cleanModel)
        assertEquals("https://custom.endpoint.com", fromEnvConfig.cleanBaseUrl)
    }
}
