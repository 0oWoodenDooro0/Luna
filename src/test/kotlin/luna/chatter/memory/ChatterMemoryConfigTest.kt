package luna.chatter.memory

import luna.config.ConfigValidationException
import luna.config.EnvReader
import luna.config.EnvSchemaAuditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterMemoryConfigTest {
    @Test
    fun `ChatterMemoryConfig provides documented defaults`() {
        val config = ChatterMemoryConfig()
        assertEquals(10L, config.historyWindowMinutes)
        assertEquals(30, config.historyMessageCount)
        assertTrue(config.enabled, "Expected memory enabled by default")
        assertEquals("gemini-3.5-flash-lite", config.cleanDistillModel)
        assertEquals("gemini-embedding-001", config.cleanEmbeddingModel)
        assertEquals(5L, config.quietMinutes)
        assertEquals(5, config.minMessages)
        assertEquals(50, config.maxBatchMessages)
        assertEquals(30L, config.maxBatchAgeMinutes)
        assertEquals(10L, config.recencyExcludeMinutes)
        assertEquals(0.55, config.scoreCutoff)
        assertEquals(5, config.maxToolCalls)
        assertEquals(8, config.maxTurns)
        assertEquals(3000L, config.toolTimeoutMs)
        assertEquals(100, config.maxPerUser)
        assertEquals(300, config.maxPerGuild)
        assertEquals(15, config.maxUnderstandingsPerUser)
        assertEquals(20, config.maxUnderstandingsPerGuild)
        assertEquals(1500, config.understandingPreInjectMaxChars)
        assertEquals("/app/lib/vec0.so", config.sqliteVecExtensionPath)
    }

    @Test
    fun `ChatterMemoryConfig parses valid overrides from EnvReader`() {
        val env =
            EnvReader.of(
                "CHATTER_HISTORY_WINDOW_MINUTES" to "15",
                "CHATTER_HISTORY_MESSAGE_COUNT" to "45",
                "CHATTER_MEMORY_ENABLED" to "false",
                "CHATTER_MEMORY_DISTILL_MODEL" to "models/gemini-2.5-flash-lite",
                "CHATTER_MEMORY_EMBEDDING_MODEL" to "models/gemini-embedding-001",
                "CHATTER_MEMORY_QUIET_MINUTES" to "8",
                "CHATTER_MEMORY_MIN_MESSAGES" to "7",
                "CHATTER_MEMORY_MAX_BATCH_MESSAGES" to "80",
                "CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES" to "45",
                "CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES" to "12",
                "CHATTER_MEMORY_SCORE_CUTOFF" to "0.72",
                "CHATTER_MEMORY_MAX_TOOL_CALLS" to "3",
                "CHATTER_MEMORY_MAX_TURNS" to "9",
                "CHATTER_MEMORY_TOOL_TIMEOUT_MS" to "4500",
                "CHATTER_MEMORY_MAX_PER_USER" to "150",
                "CHATTER_MEMORY_MAX_PER_GUILD" to "450",
                "CHATTER_MEMORY_MAX_UNDERSTANDINGS_PER_USER" to "25",
                "CHATTER_MEMORY_MAX_UNDERSTANDINGS_PER_GUILD" to "35",
                "CHATTER_MEMORY_UNDERSTANDING_PRE_INJECT_MAX_CHARS" to "2000",
                "SQLITE_VEC_EXTENSION_PATH" to "/opt/sqlite/vec0.so",
            )

        val config = ChatterMemoryConfig.from(env)
        assertEquals(15L, config.historyWindowMinutes)
        assertEquals(45, config.historyMessageCount)
        assertFalse(config.enabled, "Expected enabled override to parse false")
        assertEquals("gemini-2.5-flash-lite", config.cleanDistillModel)
        assertEquals("gemini-embedding-001", config.cleanEmbeddingModel)
        assertEquals(8L, config.quietMinutes)
        assertEquals(7, config.minMessages)
        assertEquals(80, config.maxBatchMessages)
        assertEquals(45L, config.maxBatchAgeMinutes)
        assertEquals(12L, config.recencyExcludeMinutes)
        assertEquals(0.72, config.scoreCutoff)
        assertEquals(3, config.maxToolCalls)
        assertEquals(9, config.maxTurns)
        assertEquals(4500L, config.toolTimeoutMs)
        assertEquals(150, config.maxPerUser)
        assertEquals(450, config.maxPerGuild)
        assertEquals(25, config.maxUnderstandingsPerUser)
        assertEquals(35, config.maxUnderstandingsPerGuild)
        assertEquals(2000, config.understandingPreInjectMaxChars)
        assertEquals("/opt/sqlite/vec0.so", config.sqliteVecExtensionPath)
    }

    @Test
    fun `ChatterMemoryConfig treats blank values as defaults and ignores legacy or chat model keys`() {
        val config =
            ChatterMemoryConfig.from(
                EnvReader.of(
                    "CHATTER_MEMORY_DISTILL_MODEL" to "   ",
                    "CHATTER_MEMORY_EMBEDDING_MODEL" to "   ",
                    "CHATTER_GEMINI_MODEL" to "gemini-chat-model",
                    "GEMINI_MODEL" to "gemini-default-model",
                    "SQLITE_VEC_EXTENSION_PATH" to "   ",
                    "chatter.historyWindowMinutes" to "99",
                    "chatter.memory.enabled" to "false",
                ),
            )
        assertEquals(ChatterMemoryConfig.DEFAULT_DISTILL_MODEL, config.cleanDistillModel)
        assertEquals(ChatterMemoryConfig.DEFAULT_EMBEDDING_MODEL, config.cleanEmbeddingModel)
        assertEquals(ChatterMemoryConfig.DEFAULT_SQLITE_VEC_EXTENSION_PATH, config.sqliteVecExtensionPath)
        assertEquals(10L, config.historyWindowMinutes)
        assertTrue(config.enabled, "Expected memory enabled by default when legacy keys are ignored")
    }

    @Test
    fun `ChatterMemoryConfig fails fast on invalid types, out-of-range bounds, and maxTurns invariant`() {
        val invalidCases =
            listOf(
                "CHATTER_HISTORY_WINDOW_MINUTES" to "0",
                "CHATTER_HISTORY_MESSAGE_COUNT" to "-5",
                "CHATTER_HISTORY_MESSAGE_COUNT" to "250",
                "CHATTER_MEMORY_ENABLED" to "maybe",
                "CHATTER_MEMORY_QUIET_MINUTES" to "-1",
                "CHATTER_MEMORY_MIN_MESSAGES" to "0",
                "CHATTER_MEMORY_MAX_BATCH_MESSAGES" to "abc",
                "CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES" to "-10",
                "CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES" to "-3",
                "CHATTER_MEMORY_SCORE_CUTOFF" to "NaN",
                "CHATTER_MEMORY_SCORE_CUTOFF" to "-0.1",
                "CHATTER_MEMORY_SCORE_CUTOFF" to "1.01",
                "CHATTER_MEMORY_SCORE_CUTOFF" to "Infinity",
                "CHATTER_MEMORY_MAX_TOOL_CALLS" to "0",
                "CHATTER_MEMORY_MAX_TURNS" to "-2",
                "CHATTER_MEMORY_TOOL_TIMEOUT_MS" to "0",
                "CHATTER_MEMORY_MAX_PER_USER" to "-10",
                "CHATTER_MEMORY_MAX_PER_GUILD" to "0",
                "CHATTER_MEMORY_MAX_UNDERSTANDINGS_PER_USER" to "0",
                "CHATTER_MEMORY_MAX_UNDERSTANDINGS_PER_GUILD" to "-1",
                "CHATTER_MEMORY_UNDERSTANDING_PRE_INJECT_MAX_CHARS" to "0",
            )
        for ((key, value) in invalidCases) {
            val ex =
                assertFailsWith<ConfigValidationException>("Expected ConfigValidationException for $key=$value") {
                    ChatterMemoryConfig.from(EnvReader.of(key to value))
                }
            assertTrue(ex.message?.contains(key) == true, "Expected error message to mention $key: ${ex.message}")
        }

        // maxTurns < maxToolCalls + 2 throws ConfigValidationException
        val turnsEx1 =
            assertFailsWith<ConfigValidationException> {
                ChatterMemoryConfig.from(
                    EnvReader.of(
                        "CHATTER_MEMORY_MAX_TOOL_CALLS" to "6",
                        "CHATTER_MEMORY_MAX_TURNS" to "7",
                    ),
                )
            }
        assertTrue(
            turnsEx1.message?.contains("CHATTER_MEMORY_MAX_TURNS") == true,
            "Expected error message to mention CHATTER_MEMORY_MAX_TURNS: ${turnsEx1.message}",
        )

        val turnsEx2 =
            assertFailsWith<ConfigValidationException> {
                ChatterMemoryConfig.from(
                    EnvReader.of(
                        "CHATTER_MEMORY_MAX_TOOL_CALLS" to "5",
                        "CHATTER_MEMORY_MAX_TURNS" to "4",
                    ),
                )
            }
        assertTrue(
            turnsEx2.message?.contains("CHATTER_MEMORY_MAX_TURNS") == true,
            "Expected error message to mention CHATTER_MEMORY_MAX_TURNS: ${turnsEx2.message}",
        )
    }

    @Test
    fun `dot-env-example and EnvSchemaAuditor declare all Chatter memory and history keys`() {
        val expectedKeys =
            listOf(
                "CHATTER_HISTORY_WINDOW_MINUTES" to "10",
                "CHATTER_HISTORY_MESSAGE_COUNT" to "30",
                "CHATTER_MEMORY_ENABLED" to "true",
                "CHATTER_MEMORY_DISTILL_MODEL" to "gemini-3.5-flash-lite",
                "CHATTER_MEMORY_EMBEDDING_MODEL" to "gemini-embedding-001",
                "CHATTER_MEMORY_QUIET_MINUTES" to "5",
                "CHATTER_MEMORY_MIN_MESSAGES" to "5",
                "CHATTER_MEMORY_MAX_BATCH_MESSAGES" to "50",
                "CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES" to "30",
                "CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES" to "10",
                "CHATTER_MEMORY_SCORE_CUTOFF" to "0.55",
                "CHATTER_MEMORY_MAX_TOOL_CALLS" to "5",
                "CHATTER_MEMORY_MAX_TURNS" to "8",
                "CHATTER_MEMORY_TOOL_TIMEOUT_MS" to "3000",
                "CHATTER_MEMORY_MAX_PER_USER" to "100",
                "CHATTER_MEMORY_MAX_PER_GUILD" to "300",
                "CHATTER_MEMORY_MAX_UNDERSTANDINGS_PER_USER" to "15",
                "CHATTER_MEMORY_MAX_UNDERSTANDINGS_PER_GUILD" to "20",
                "CHATTER_MEMORY_UNDERSTANDING_PRE_INJECT_MAX_CHARS" to "1500",
                "SQLITE_VEC_EXTENSION_PATH" to "/app/lib/vec0.so",
            )

        val envExample = requireNotNull(EnvSchemaAuditor.loadDefaultEnvExample()) { ".env.example should be loadable" }
        val parsed = EnvSchemaAuditor.parseEnvExampleSchema(envExample).associateBy { it.name }
        val fallback = EnvSchemaAuditor.defaultFallbackSchema().associateBy { it.name }

        for ((key, expectedDefault) in expectedKeys) {
            val envEntry = parsed[key]
            assertTrue(envEntry != null, "Expected $key in .env.example")
            assertEquals(expectedDefault, envEntry.defaultValue, "Default mismatch for $key in .env.example")

            val fallbackEntry = fallback[key]
            assertTrue(fallbackEntry != null, "Expected $key in EnvSchemaAuditor.defaultFallbackSchema()")
            assertEquals(expectedDefault, fallbackEntry.defaultValue, "Default mismatch for $key in defaultFallbackSchema()")
        }

        val auditResult =
            EnvSchemaAuditor.auditDrift(
                config = expectedKeys.associate { (k, v) -> k to v },
                schema = parsed.values.toList(),
            )
        assertTrue(auditResult.unknownKeys.isEmpty(), "Unexpected unknownKeys: ${auditResult.unknownKeys}")
    }
}
