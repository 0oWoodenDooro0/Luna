package luna.core.ai

import luna.config.EnvReader

/**
 * Configuration for dedicated JSONL AI Trace diagnostic logging.
 *
 * @property enabled Whether AI Trace JSONL recording is enabled (default `true`).
 * @property dir Directory where daily `ai-YYYY-MM-DD.jsonl` files are written (default `data/logs/ai`).
 * @property maxDays Maximum retention age in days before trace files are pruned (default `14`).
 * @property maxTotalMb Maximum total directory size in megabytes before oldest trace files are pruned (default `200`).
 */
data class AiTraceConfig(
    val enabled: Boolean = DEFAULT_ENABLED,
    val dir: String = DEFAULT_DIR,
    val maxDays: Int = DEFAULT_MAX_DAYS,
    val maxTotalMb: Int = DEFAULT_MAX_TOTAL_MB,
) {
    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_DIR = "data/logs/ai"
        const val DEFAULT_MAX_DAYS = 14
        const val DEFAULT_MAX_TOTAL_MB = 200

        fun fromMap(config: Map<String, Any?>): AiTraceConfig {
            val enabled =
                parseBoolean(config["AI_TRACE_ENABLED"])
                    ?: parseBoolean(config["ai.trace.enabled"])
                    ?: DEFAULT_ENABLED

            val dir =
                parseNonBlankString(config["AI_TRACE_DIR"])
                    ?: parseNonBlankString(config["ai.trace.dir"])
                    ?: DEFAULT_DIR

            val maxDays =
                (
                    parseInt(config["AI_TRACE_MAX_DAYS"])
                        ?: parseInt(config["ai.trace.maxDays"])
                        ?: parseInt(config["ai.trace.max_days"])
                )?.takeIf { it > 0 }
                    ?: DEFAULT_MAX_DAYS

            val maxTotalMb =
                (
                    parseInt(config["AI_TRACE_MAX_TOTAL_MB"])
                        ?: parseInt(config["ai.trace.maxTotalMb"])
                        ?: parseInt(config["ai.trace.max_total_mb"])
                )?.takeIf { it > 0 }
                    ?: DEFAULT_MAX_TOTAL_MB

            return AiTraceConfig(
                enabled = enabled,
                dir = dir,
                maxDays = maxDays,
                maxTotalMb = maxTotalMb,
            )
        }

        fun from(env: EnvReader): AiTraceConfig =
            AiTraceConfig(
                enabled = env.boolean("AI_TRACE_ENABLED", DEFAULT_ENABLED),
                dir = env.string("AI_TRACE_DIR", DEFAULT_DIR),
                maxDays = env.int("AI_TRACE_MAX_DAYS", DEFAULT_MAX_DAYS, 1..Int.MAX_VALUE),
                maxTotalMb = env.int("AI_TRACE_MAX_TOTAL_MB", DEFAULT_MAX_TOTAL_MB, 1..Int.MAX_VALUE),
            )

        private fun parseNonBlankString(raw: Any?): String? = (raw as? String)?.trim()?.ifBlank { null }

        private fun parseBoolean(raw: Any?): Boolean? =
            when (raw) {
                is Boolean -> raw
                is String -> raw.trim().toBooleanStrictOrNull()
                else -> null
            }

        private fun parseInt(raw: Any?): Int? =
            when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
    }
}
