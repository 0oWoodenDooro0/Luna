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

        fun from(env: EnvReader): AiTraceConfig =
            AiTraceConfig(
                enabled = env.boolean("AI_TRACE_ENABLED", DEFAULT_ENABLED),
                dir = env.string("AI_TRACE_DIR", DEFAULT_DIR),
                maxDays = env.int("AI_TRACE_MAX_DAYS", DEFAULT_MAX_DAYS, 1..Int.MAX_VALUE),
                maxTotalMb = env.int("AI_TRACE_MAX_TOTAL_MB", DEFAULT_MAX_TOTAL_MB, 1..Int.MAX_VALUE),
            )
    }
}
