package luna.chatter.memory

import luna.plugin.PluginContext
import luna.plugin.config
import org.slf4j.LoggerFactory

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
        private val logger = LoggerFactory.getLogger(ChatterMemoryConfig::class.java)

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

        fun fromEnv(env: Map<String, String> = System.getenv()): ChatterMemoryConfig =
            fromConfig(
                mapOf(
                    "CHATTER_HISTORY_WINDOW_MINUTES" to env["CHATTER_HISTORY_WINDOW_MINUTES"],
                    "CHATTER_HISTORY_MESSAGE_COUNT" to env["CHATTER_HISTORY_MESSAGE_COUNT"],
                    "CHATTER_MEMORY_ENABLED" to env["CHATTER_MEMORY_ENABLED"],
                    "CHATTER_MEMORY_DISTILL_MODEL" to env["CHATTER_MEMORY_DISTILL_MODEL"],
                    "CHATTER_MEMORY_EMBEDDING_MODEL" to env["CHATTER_MEMORY_EMBEDDING_MODEL"],
                    "CHATTER_MEMORY_QUIET_MINUTES" to env["CHATTER_MEMORY_QUIET_MINUTES"],
                    "CHATTER_MEMORY_MIN_MESSAGES" to env["CHATTER_MEMORY_MIN_MESSAGES"],
                    "CHATTER_MEMORY_MAX_BATCH_MESSAGES" to env["CHATTER_MEMORY_MAX_BATCH_MESSAGES"],
                    "CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES" to env["CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES"],
                    "CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES" to env["CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES"],
                    "CHATTER_MEMORY_SCORE_CUTOFF" to env["CHATTER_MEMORY_SCORE_CUTOFF"],
                    "CHATTER_MEMORY_MAX_TOOL_CALLS" to env["CHATTER_MEMORY_MAX_TOOL_CALLS"],
                    "CHATTER_MEMORY_MAX_TURNS" to env["CHATTER_MEMORY_MAX_TURNS"],
                    "CHATTER_MEMORY_TOOL_TIMEOUT_MS" to env["CHATTER_MEMORY_TOOL_TIMEOUT_MS"],
                    "CHATTER_MEMORY_MAX_PER_USER" to env["CHATTER_MEMORY_MAX_PER_USER"],
                    "CHATTER_MEMORY_MAX_PER_CHANNEL" to env["CHATTER_MEMORY_MAX_PER_CHANNEL"],
                    "SQLITE_VEC_EXTENSION_PATH" to env["SQLITE_VEC_EXTENSION_PATH"],
                ),
            )

        fun fromConfig(config: Map<String, Any?>): ChatterMemoryConfig = buildFromLookup { key -> config[key] }

        fun fromPluginContext(context: PluginContext): ChatterMemoryConfig =
            buildFromLookup { key ->
                context.config<Any>(key)
            }

        private fun buildFromLookup(lookup: (String) -> Any?): ChatterMemoryConfig {
            val historyWindowMinutes =
                normalizePositiveLong(
                    readLong(lookup, "CHATTER_HISTORY_WINDOW_MINUTES", "chatter.historyWindowMinutes", "chatter.history_window_minutes"),
                    DEFAULT_HISTORY_WINDOW_MINUTES,
                    "CHATTER_HISTORY_WINDOW_MINUTES",
                )
            val historyMessageCount =
                normalizeBoundedInt(
                    readLong(lookup, "CHATTER_HISTORY_MESSAGE_COUNT", "chatter.historyMessageCount", "chatter.history_message_count"),
                    DEFAULT_HISTORY_MESSAGE_COUNT,
                    MIN_HISTORY_MESSAGE_COUNT,
                    MAX_HISTORY_MESSAGE_COUNT,
                    "CHATTER_HISTORY_MESSAGE_COUNT",
                )
            val enabled =
                readBoolean(lookup, "CHATTER_MEMORY_ENABLED", "chatter.memory.enabled", "chatter.memoryEnabled")
                    ?: DEFAULT_ENABLED
            val distillModel =
                readString(lookup, "CHATTER_MEMORY_DISTILL_MODEL", "chatter.memory.distillModel", "chatter.memory.distill_model")
                    ?.removePrefix("models/")
                    ?.ifBlank { null }
                    ?: DEFAULT_DISTILL_MODEL
            val embeddingModel =
                readString(lookup, "CHATTER_MEMORY_EMBEDDING_MODEL", "chatter.memory.embeddingModel", "chatter.memory.embedding_model")
                    ?.removePrefix("models/")
                    ?.ifBlank { null }
                    ?: DEFAULT_EMBEDDING_MODEL
            val quietMinutes =
                normalizePositiveLong(
                    readLong(lookup, "CHATTER_MEMORY_QUIET_MINUTES", "chatter.memory.quietMinutes", "chatter.memory.quiet_minutes"),
                    DEFAULT_QUIET_MINUTES,
                    "CHATTER_MEMORY_QUIET_MINUTES",
                )
            val minMessages =
                normalizePositiveInt(
                    readLong(lookup, "CHATTER_MEMORY_MIN_MESSAGES", "chatter.memory.minMessages", "chatter.memory.min_messages"),
                    DEFAULT_MIN_MESSAGES,
                    "CHATTER_MEMORY_MIN_MESSAGES",
                )
            val maxBatchMessages =
                normalizePositiveInt(
                    readLong(lookup, "CHATTER_MEMORY_MAX_BATCH_MESSAGES", "chatter.memory.maxBatchMessages", "chatter.memory.max_batch_messages"),
                    DEFAULT_MAX_BATCH_MESSAGES,
                    "CHATTER_MEMORY_MAX_BATCH_MESSAGES",
                )
            val maxBatchAgeMinutes =
                normalizePositiveLong(
                    readLong(lookup, "CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES", "chatter.memory.maxBatchAgeMinutes", "chatter.memory.max_batch_age_minutes"),
                    DEFAULT_MAX_BATCH_AGE_MINUTES,
                    "CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES",
                )
            val recencyExcludeMinutes =
                normalizeNonNegativeLong(
                    readLong(
                        lookup,
                        "CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES",
                        "chatter.memory.recencyExcludeMinutes",
                        "chatter.memory.recency_exclude_minutes",
                    ),
                    DEFAULT_RECENCY_EXCLUDE_MINUTES,
                    "CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES",
                )
            val scoreCutoff =
                normalizeScoreCutoff(
                    readDouble(lookup, "CHATTER_MEMORY_SCORE_CUTOFF", "chatter.memory.scoreCutoff", "chatter.memory.score_cutoff"),
                )
            val maxToolCalls =
                normalizePositiveInt(
                    readLong(lookup, "CHATTER_MEMORY_MAX_TOOL_CALLS", "chatter.memory.maxToolCalls", "chatter.memory.max_tool_calls"),
                    DEFAULT_MAX_TOOL_CALLS,
                    "CHATTER_MEMORY_MAX_TOOL_CALLS",
                )
            val rawMaxTurns =
                normalizePositiveInt(
                    readLong(lookup, "CHATTER_MEMORY_MAX_TURNS", "chatter.memory.maxTurns", "chatter.memory.max_turns"),
                    DEFAULT_MAX_TURNS,
                    "CHATTER_MEMORY_MAX_TURNS",
                )
            val minRequiredTurns = maxToolCalls + 2
            val maxTurns =
                if (rawMaxTurns < minRequiredTurns) {
                    logger.warn(
                        "CHATTER_MEMORY_MAX_TURNS ({}) must be >= CHATTER_MEMORY_MAX_TOOL_CALLS + 2 ({}); adjusting to {}",
                        rawMaxTurns,
                        minRequiredTurns,
                        minRequiredTurns,
                    )
                    minRequiredTurns
                } else {
                    rawMaxTurns
                }
            val toolTimeoutMs =
                normalizePositiveLong(
                    readLong(lookup, "CHATTER_MEMORY_TOOL_TIMEOUT_MS", "chatter.memory.toolTimeoutMs", "chatter.memory.tool_timeout_ms"),
                    DEFAULT_TOOL_TIMEOUT_MS,
                    "CHATTER_MEMORY_TOOL_TIMEOUT_MS",
                )
            val maxPerUser =
                normalizePositiveInt(
                    readLong(lookup, "CHATTER_MEMORY_MAX_PER_USER", "chatter.memory.maxPerUser", "chatter.memory.max_per_user"),
                    DEFAULT_MAX_PER_USER,
                    "CHATTER_MEMORY_MAX_PER_USER",
                )
            val maxPerChannel =
                normalizePositiveInt(
                    readLong(lookup, "CHATTER_MEMORY_MAX_PER_CHANNEL", "chatter.memory.maxPerChannel", "chatter.memory.max_per_channel"),
                    DEFAULT_MAX_PER_CHANNEL,
                    "CHATTER_MEMORY_MAX_PER_CHANNEL",
                )
            val sqliteVecExtensionPath =
                readString(lookup, "SQLITE_VEC_EXTENSION_PATH", "sqlite.vecExtensionPath", "sqlite.vec_extension_path")
                    ?: DEFAULT_SQLITE_VEC_EXTENSION_PATH

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

        private fun readString(
            lookup: (String) -> Any?,
            vararg keys: String,
        ): String? = keys.firstNotNullOfOrNull { key -> (lookup(key) as? String)?.trim()?.ifBlank { null } }

        private fun readBoolean(
            lookup: (String) -> Any?,
            keyName: String,
            vararg keys: String,
        ): Boolean? =
            (listOf(keyName) + keys.toList()).firstNotNullOfOrNull { key ->
                when (val value = lookup(key)) {
                    is Boolean -> {
                        value
                    }

                    is String -> {
                        val trimmed = value.trim().ifBlank { null } ?: return@firstNotNullOfOrNull null
                        trimmed.toBooleanStrictOrNull().also { parsed ->
                            if (parsed == null) {
                                logger.warn("Unparseable boolean value '{}' for {}; using default", trimmed, keyName)
                            }
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

        private fun readLong(
            lookup: (String) -> Any?,
            keyName: String,
            vararg keys: String,
        ): Long? =
            (listOf(keyName) + keys.toList()).firstNotNullOfOrNull { key ->
                when (val value = lookup(key)) {
                    is Number -> {
                        value.toLong()
                    }

                    is String -> {
                        val trimmed = value.trim().ifBlank { null } ?: return@firstNotNullOfOrNull null
                        trimmed.toLongOrNull().also { parsed ->
                            if (parsed == null) {
                                logger.warn("Unparseable integer value '{}' for {}; using default", trimmed, keyName)
                            }
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

        private fun readDouble(
            lookup: (String) -> Any?,
            keyName: String,
            vararg keys: String,
        ): Double? =
            (listOf(keyName) + keys.toList()).firstNotNullOfOrNull { key ->
                when (val value = lookup(key)) {
                    is Number -> {
                        value.toDouble()
                    }

                    is String -> {
                        val trimmed = value.trim().ifBlank { null } ?: return@firstNotNullOfOrNull null
                        trimmed.toDoubleOrNull().also { parsed ->
                            if (parsed == null) {
                                logger.warn("Unparseable numeric value '{}' for {}; using default", trimmed, keyName)
                            }
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

        private fun normalizePositiveLong(
            raw: Long?,
            defaultValue: Long,
            keyName: String,
        ): Long {
            if (raw == null) return defaultValue
            if (raw <= 0L) {
                logger.warn("Invalid {} '{}'; falling back to default {}", keyName, raw, defaultValue)
                return defaultValue
            }
            return raw
        }

        private fun normalizeNonNegativeLong(
            raw: Long?,
            defaultValue: Long,
            keyName: String,
        ): Long {
            if (raw == null) return defaultValue
            if (raw < 0L) {
                logger.warn("Invalid {} '{}'; falling back to default {}", keyName, raw, defaultValue)
                return defaultValue
            }
            return raw
        }

        private fun normalizePositiveInt(
            raw: Long?,
            defaultValue: Int,
            keyName: String,
        ): Int {
            if (raw == null) return defaultValue
            if (raw <= 0L || raw > Int.MAX_VALUE) {
                logger.warn("Invalid {} '{}'; falling back to default {}", keyName, raw, defaultValue)
                return defaultValue
            }
            return raw.toInt()
        }

        private fun normalizeBoundedInt(
            raw: Long?,
            defaultValue: Int,
            min: Int,
            max: Int,
            keyName: String,
        ): Int {
            if (raw == null) return defaultValue
            if (raw < min.toLong()) {
                logger.warn("Invalid {} '{}'; falling back to default {}", keyName, raw, defaultValue)
                return defaultValue
            }
            val clamped = raw.coerceAtMost(max.toLong()).toInt()
            if (clamped.toLong() != raw) {
                logger.warn("Out-of-range {} '{}'; clamped to {} (max {})", keyName, raw, clamped, max)
            }
            return clamped
        }

        private fun normalizeScoreCutoff(raw: Double?): Double {
            if (raw == null) return DEFAULT_SCORE_CUTOFF
            if (raw.isNaN() || raw.isInfinite() || raw < MIN_SCORE_CUTOFF || raw > MAX_SCORE_CUTOFF) {
                logger.warn(
                    "Invalid CHATTER_MEMORY_SCORE_CUTOFF '{}'; falling back to default {}",
                    raw,
                    DEFAULT_SCORE_CUTOFF,
                )
                return DEFAULT_SCORE_CUTOFF
            }
            return raw
        }
    }
}
