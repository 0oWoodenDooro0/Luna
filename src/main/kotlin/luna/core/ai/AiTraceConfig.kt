package luna.core.ai

import luna.plugin.PluginContext
import luna.plugin.config

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

        fun fromPluginContext(context: PluginContext): AiTraceConfig =
            fromMap(
                mapOf(
                    "AI_TRACE_ENABLED" to (context.config<Boolean>("AI_TRACE_ENABLED") ?: context.config<String>("AI_TRACE_ENABLED")),
                    "ai.trace.enabled" to (context.config<Boolean>("ai.trace.enabled") ?: context.config<String>("ai.trace.enabled")),
                    "AI_TRACE_DIR" to context.config<String>("AI_TRACE_DIR"),
                    "ai.trace.dir" to context.config<String>("ai.trace.dir"),
                    "AI_TRACE_MAX_DAYS" to (context.config<Int>("AI_TRACE_MAX_DAYS") ?: context.config<String>("AI_TRACE_MAX_DAYS")),
                    "ai.trace.maxDays" to (context.config<Int>("ai.trace.maxDays") ?: context.config<String>("ai.trace.maxDays")),
                    "ai.trace.max_days" to (context.config<Int>("ai.trace.max_days") ?: context.config<String>("ai.trace.max_days")),
                    "AI_TRACE_MAX_TOTAL_MB" to
                        (context.config<Int>("AI_TRACE_MAX_TOTAL_MB") ?: context.config<String>("AI_TRACE_MAX_TOTAL_MB")),
                    "ai.trace.maxTotalMb" to (context.config<Int>("ai.trace.maxTotalMb") ?: context.config<String>("ai.trace.maxTotalMb")),
                    "ai.trace.max_total_mb" to
                        (context.config<Int>("ai.trace.max_total_mb") ?: context.config<String>("ai.trace.max_total_mb")),
                ),
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
