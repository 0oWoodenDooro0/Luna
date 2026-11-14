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

            val rawDir =
                (config["AI_TRACE_DIR"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["ai.trace.dir"] as? String)?.trim()?.ifBlank { null }
            val dir = rawDir ?: DEFAULT_DIR

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

        fun fromPluginContext(context: PluginContext): AiTraceConfig {
            val enabled =
                context.config<Boolean>("AI_TRACE_ENABLED")
                    ?: context.config<String>("AI_TRACE_ENABLED")?.trim()?.toBooleanStrictOrNull()
                    ?: context.config<Boolean>("ai.trace.enabled")
                    ?: context.config<String>("ai.trace.enabled")?.trim()?.toBooleanStrictOrNull()
                    ?: DEFAULT_ENABLED

            val rawDir =
                context.config<String>("AI_TRACE_DIR")?.trim()?.ifBlank { null }
                    ?: context.config<String>("ai.trace.dir")?.trim()?.ifBlank { null }
            val dir = rawDir ?: DEFAULT_DIR

            val maxDays =
                (
                    context.config<Int>("AI_TRACE_MAX_DAYS")
                        ?: context.config<String>("AI_TRACE_MAX_DAYS")?.trim()?.toIntOrNull()
                        ?: context.config<Int>("ai.trace.maxDays")
                        ?: context.config<String>("ai.trace.maxDays")?.trim()?.toIntOrNull()
                )?.takeIf { it > 0 }
                    ?: DEFAULT_MAX_DAYS

            val maxTotalMb =
                (
                    context.config<Int>("AI_TRACE_MAX_TOTAL_MB")
                        ?: context.config<String>("AI_TRACE_MAX_TOTAL_MB")?.trim()?.toIntOrNull()
                        ?: context.config<Int>("ai.trace.maxTotalMb")
                        ?: context.config<String>("ai.trace.maxTotalMb")?.trim()?.toIntOrNull()
                )?.takeIf { it > 0 }
                    ?: DEFAULT_MAX_TOTAL_MB

            return AiTraceConfig(
                enabled = enabled,
                dir = dir,
                maxDays = maxDays,
                maxTotalMb = maxTotalMb,
            )
        }

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
