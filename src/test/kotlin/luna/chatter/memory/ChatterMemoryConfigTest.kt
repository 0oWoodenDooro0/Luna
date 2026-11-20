package luna.chatter.memory

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import io.mockk.mockk
import luna.config.EnvSchemaAuditor
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(200, config.maxPerChannel)
        assertEquals("/app/lib/vec0.so", config.sqliteVecExtensionPath)
    }

    @Test
    fun `ChatterMemoryConfig parses valid overrides from env, config map, and PluginContext`() {
        val env =
            mapOf(
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
                "CHATTER_MEMORY_MAX_PER_CHANNEL" to "300",
                "SQLITE_VEC_EXTENSION_PATH" to "/opt/sqlite/vec0.so",
            )

        val fromEnv = ChatterMemoryConfig.fromEnv(env)
        assertEquals(15L, fromEnv.historyWindowMinutes)
        assertEquals(45, fromEnv.historyMessageCount)
        assertFalse(fromEnv.enabled, "Expected enabled override to parse false")
        assertEquals("gemini-2.5-flash-lite", fromEnv.cleanDistillModel)
        assertEquals("gemini-embedding-001", fromEnv.cleanEmbeddingModel)
        assertEquals(8L, fromEnv.quietMinutes)
        assertEquals(7, fromEnv.minMessages)
        assertEquals(80, fromEnv.maxBatchMessages)
        assertEquals(45L, fromEnv.maxBatchAgeMinutes)
        assertEquals(12L, fromEnv.recencyExcludeMinutes)
        assertEquals(0.72, fromEnv.scoreCutoff)
        assertEquals(3, fromEnv.maxToolCalls)
        assertEquals(9, fromEnv.maxTurns)
        assertEquals(4500L, fromEnv.toolTimeoutMs)
        assertEquals(150, fromEnv.maxPerUser)
        assertEquals(300, fromEnv.maxPerChannel)
        assertEquals("/opt/sqlite/vec0.so", fromEnv.sqliteVecExtensionPath)

        val fromMap = ChatterMemoryConfig.fromConfig(env)
        assertEquals(fromEnv, fromMap)

        val context =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = mockk<Database>(relaxed = true),
                configMap = env,
            )
        val fromCtx = ChatterMemoryConfig.fromPluginContext(context)
        assertEquals(fromEnv, fromCtx)
    }

    @Test
    fun `ChatterMemoryConfig rejects invalid bounds and enforces maxTurns invariant`() {
        val invalidEnv =
            mapOf(
                "CHATTER_HISTORY_WINDOW_MINUTES" to "0",
                "CHATTER_HISTORY_MESSAGE_COUNT" to "-5",
                "CHATTER_MEMORY_ENABLED" to "maybe",
                "CHATTER_MEMORY_QUIET_MINUTES" to "-1",
                "CHATTER_MEMORY_MIN_MESSAGES" to "0",
                "CHATTER_MEMORY_MAX_BATCH_MESSAGES" to "abc",
                "CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES" to "-10",
                "CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES" to "-3",
                "CHATTER_MEMORY_SCORE_CUTOFF" to "NaN",
                "CHATTER_MEMORY_MAX_TOOL_CALLS" to "0",
                "CHATTER_MEMORY_MAX_TURNS" to "-2",
                "CHATTER_MEMORY_TOOL_TIMEOUT_MS" to "0",
                "CHATTER_MEMORY_MAX_PER_USER" to "-10",
                "CHATTER_MEMORY_MAX_PER_CHANNEL" to "0",
                "SQLITE_VEC_EXTENSION_PATH" to "   ",
            )
        val config = ChatterMemoryConfig.fromEnv(invalidEnv)
        assertEquals(10L, config.historyWindowMinutes)
        assertEquals(30, config.historyMessageCount)
        assertTrue(config.enabled, "Expected memory enabled by default")
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
        assertEquals(200, config.maxPerChannel)
        assertEquals("/app/lib/vec0.so", config.sqliteVecExtensionPath)

        // Out-of-range scoreCutoff (< 0.0 and > 1.0)
        assertEquals(0.55, ChatterMemoryConfig.fromEnv(mapOf("CHATTER_MEMORY_SCORE_CUTOFF" to "-0.1")).scoreCutoff)
        assertEquals(0.55, ChatterMemoryConfig.fromEnv(mapOf("CHATTER_MEMORY_SCORE_CUTOFF" to "1.01")).scoreCutoff)
        assertEquals(0.55, ChatterMemoryConfig.fromEnv(mapOf("CHATTER_MEMORY_SCORE_CUTOFF" to "Infinity")).scoreCutoff)

        // historyMessageCount > 100 clamps to 100
        assertEquals(100, ChatterMemoryConfig.fromEnv(mapOf("CHATTER_HISTORY_MESSAGE_COUNT" to "250")).historyMessageCount)

        // maxTurns < maxToolCalls + 2 raises maxTurns to maxToolCalls + 2
        val adjustedTurns =
            ChatterMemoryConfig.fromEnv(
                mapOf(
                    "CHATTER_MEMORY_MAX_TOOL_CALLS" to "6",
                    "CHATTER_MEMORY_MAX_TURNS" to "7",
                ),
            )
        assertEquals(6, adjustedTurns.maxToolCalls)
        assertEquals(8, adjustedTurns.maxTurns)

        val adjustedFromDefault =
            ChatterMemoryConfig.fromEnv(
                mapOf(
                    "CHATTER_MEMORY_MAX_TOOL_CALLS" to "5",
                    "CHATTER_MEMORY_MAX_TURNS" to "4",
                ),
            )
        assertEquals(5, adjustedFromDefault.maxToolCalls)
        assertEquals(7, adjustedFromDefault.maxTurns)
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
                "CHATTER_MEMORY_MAX_PER_CHANNEL" to "200",
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
