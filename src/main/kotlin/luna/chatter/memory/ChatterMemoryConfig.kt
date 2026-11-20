package luna.chatter.memory

import luna.config.ConfigValidationException
import luna.config.EnvReader

/**
 * Configuration for Chatter `Working Memory Window`, `Long-Term Memory` (SQLite + `sqlite-vec` + `FTS5`),
 * background `Memory Distillation`, and `Agentic Memory Retrieval`.
 */
data class ChatterMemoryConfig(
    val historyWindowMinutes: Long = DEFAULT_HISTORY_WINDOW_MINUTES,
    val historyMessageCount: Int = DEFAULT_HISTORY_MESSAGE_COUNT,
    val enabled: Boolean = DEFAULT_ENABLED,
    val distillModel: String = DEFAULT_DISTILL_MODEL,
    val embeddingModel: String = DEFAULT_EMBEDDING_MODEL,
    val quietMinutes: Long = DEFAULT_QUIET_MINUTES,
    val minMessages: Int = DEFAULT_MIN_MESSAGES,
    val maxBatchMessages: Int = DEFAULT_MAX_BATCH_MESSAGES,
    val maxBatchAgeMinutes: Long = DEFAULT_MAX_BATCH_AGE_MINUTES,
    val recencyExcludeMinutes: Long = DEFAULT_RECENCY_EXCLUDE_MINUTES,
    val scoreCutoff: Double = DEFAULT_SCORE_CUTOFF,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val maxTurns: Int = DEFAULT_MAX_TURNS,
    val toolTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
    val maxPerUser: Int = DEFAULT_MAX_PER_USER,
    val maxPerChannel: Int = DEFAULT_MAX_PER_CHANNEL,
    val sqliteVecExtensionPath: String = DEFAULT_SQLITE_VEC_EXTENSION_PATH,
) {
    val cleanDistillModel: String
        get() = distillModel.trim().removePrefix("models/").ifBlank { DEFAULT_DISTILL_MODEL }

    val cleanEmbeddingModel: String
        get() = embeddingModel.trim().removePrefix("models/").ifBlank { DEFAULT_EMBEDDING_MODEL }

    companion object {
        const val DEFAULT_HISTORY_WINDOW_MINUTES = 10L
        const val MIN_HISTORY_WINDOW_MINUTES = 1L
        const val DEFAULT_HISTORY_MESSAGE_COUNT = 30
        const val MIN_HISTORY_MESSAGE_COUNT = 1
        const val MAX_HISTORY_MESSAGE_COUNT = 100
        const val DEFAULT_ENABLED = true
        const val DEFAULT_DISTILL_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_EMBEDDING_MODEL = "gemini-embedding-001"
        const val DEFAULT_QUIET_MINUTES = 5L
        const val DEFAULT_MIN_MESSAGES = 5
        const val DEFAULT_MAX_BATCH_MESSAGES = 50
        const val DEFAULT_MAX_BATCH_AGE_MINUTES = 30L
        const val DEFAULT_RECENCY_EXCLUDE_MINUTES = 10L
        const val DEFAULT_SCORE_CUTOFF = 0.55
        const val MIN_SCORE_CUTOFF = 0.0
        const val MAX_SCORE_CUTOFF = 1.0
        const val DEFAULT_MAX_TOOL_CALLS = 5
        const val DEFAULT_MAX_TURNS = 8
        const val DEFAULT_TOOL_TIMEOUT_MS = 3000L
        const val DEFAULT_MAX_PER_USER = 100
        const val DEFAULT_MAX_PER_CHANNEL = 200
        const val DEFAULT_SQLITE_VEC_EXTENSION_PATH = "/app/lib/vec0.so"

        /** Parses [ChatterMemoryConfig] from [env] with strict fail-fast validation. */
        fun from(env: EnvReader): ChatterMemoryConfig {
            val historyWindowMinutes =
                env.long(
                    "CHATTER_HISTORY_WINDOW_MINUTES",
                    DEFAULT_HISTORY_WINDOW_MINUTES,
                    MIN_HISTORY_WINDOW_MINUTES..Long.MAX_VALUE,
                )
            val historyMessageCount =
                env.int(
                    "CHATTER_HISTORY_MESSAGE_COUNT",
                    DEFAULT_HISTORY_MESSAGE_COUNT,
                    MIN_HISTORY_MESSAGE_COUNT..MAX_HISTORY_MESSAGE_COUNT,
                )
            val enabled = env.boolean("CHATTER_MEMORY_ENABLED", DEFAULT_ENABLED)
            val distillModel =
                env
                    .string("CHATTER_MEMORY_DISTILL_MODEL")
                    ?.removePrefix("models/")
                    ?.ifBlank { null }
                    ?: DEFAULT_DISTILL_MODEL
            val embeddingModel =
                env
                    .string("CHATTER_MEMORY_EMBEDDING_MODEL")
                    ?.removePrefix("models/")
                    ?.ifBlank { null }
                    ?: DEFAULT_EMBEDDING_MODEL
            val quietMinutes = env.long("CHATTER_MEMORY_QUIET_MINUTES", DEFAULT_QUIET_MINUTES, 1L..Long.MAX_VALUE)
            val minMessages = env.int("CHATTER_MEMORY_MIN_MESSAGES", DEFAULT_MIN_MESSAGES, 1..Int.MAX_VALUE)
            val maxBatchMessages = env.int("CHATTER_MEMORY_MAX_BATCH_MESSAGES", DEFAULT_MAX_BATCH_MESSAGES, 1..Int.MAX_VALUE)
            val maxBatchAgeMinutes =
                env.long("CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES", DEFAULT_MAX_BATCH_AGE_MINUTES, 1L..Long.MAX_VALUE)
            val recencyExcludeMinutes =
                env.long("CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES", DEFAULT_RECENCY_EXCLUDE_MINUTES, 0L..Long.MAX_VALUE)
            val scoreCutoff =
                env.double("CHATTER_MEMORY_SCORE_CUTOFF", DEFAULT_SCORE_CUTOFF, MIN_SCORE_CUTOFF..MAX_SCORE_CUTOFF)
            val maxToolCalls = env.int("CHATTER_MEMORY_MAX_TOOL_CALLS", DEFAULT_MAX_TOOL_CALLS, 1..Int.MAX_VALUE)
            val maxTurns = env.int("CHATTER_MEMORY_MAX_TURNS", DEFAULT_MAX_TURNS, 1..Int.MAX_VALUE)
            val minRequiredTurns = maxToolCalls + 2
            if (maxTurns < minRequiredTurns) {
                throw ConfigValidationException(
                    "Invalid configuration for CHATTER_MEMORY_MAX_TURNS: " +
                        "must be >= CHATTER_MEMORY_MAX_TOOL_CALLS + 2 ($minRequiredTurns), but got '$maxTurns'",
                )
            }
            val toolTimeoutMs = env.long("CHATTER_MEMORY_TOOL_TIMEOUT_MS", DEFAULT_TOOL_TIMEOUT_MS, 1L..Long.MAX_VALUE)
            val maxPerUser = env.int("CHATTER_MEMORY_MAX_PER_USER", DEFAULT_MAX_PER_USER, 1..Int.MAX_VALUE)
            val maxPerChannel = env.int("CHATTER_MEMORY_MAX_PER_CHANNEL", DEFAULT_MAX_PER_CHANNEL, 1..Int.MAX_VALUE)
            val sqliteVecExtensionPath = env.string("SQLITE_VEC_EXTENSION_PATH", DEFAULT_SQLITE_VEC_EXTENSION_PATH)

            return ChatterMemoryConfig(
                historyWindowMinutes = historyWindowMinutes,
                historyMessageCount = historyMessageCount,
                enabled = enabled,
                distillModel = distillModel,
                embeddingModel = embeddingModel,
                quietMinutes = quietMinutes,
                minMessages = minMessages,
                maxBatchMessages = maxBatchMessages,
                maxBatchAgeMinutes = maxBatchAgeMinutes,
                recencyExcludeMinutes = recencyExcludeMinutes,
                scoreCutoff = scoreCutoff,
                maxToolCalls = maxToolCalls,
                maxTurns = maxTurns,
                toolTimeoutMs = toolTimeoutMs,
                maxPerUser = maxPerUser,
                maxPerChannel = maxPerChannel,
                sqliteVecExtensionPath = sqliteVecExtensionPath,
            )
        }
    }
}
