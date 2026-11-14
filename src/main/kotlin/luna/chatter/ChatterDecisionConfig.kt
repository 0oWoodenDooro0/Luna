package luna.chatter

import luna.plugin.PluginContext
import luna.plugin.config

/**
 * Configuration for Google Gemini Decision 1 participation evaluation (`POST /v1beta/decisions`).
 *
 * @property apiKey Google Gemini API key.
 * @property model Decision model identifier (defaults to `gemini-decision-1`).
 * @property baseUrl Base URL for Google Generative Language API (defaults to `https://generativelanguage.googleapis.com`).
 * @property threshold Minimum calibrated `noul` probability required for participation (clamped to `0.0..1.0`, default `0.7`).
 * @property timeoutMillis HTTP request/socket/connect timeout in milliseconds (default `10_000L`).
 * @property instructions Decision evaluation prompt instructions sent to `gemini-decision-1` (defaults to [GeminiDecisionClient.DEFAULT_INSTRUCTIONS]).
 * @property decisionMessageCount Maximum number of dialogue turns passed to `gemini-decision-1` (bounded to `1..100`, default `15`).
 */
data class ChatterDecisionConfig(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val threshold: Double = DEFAULT_THRESHOLD,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val instructions: String = GeminiDecisionClient.DEFAULT_INSTRUCTIONS,
    val decisionMessageCount: Int = DEFAULT_DECISION_MESSAGE_COUNT,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    val cleanModel: String
        get() = model.trim().removePrefix("models/")

    val cleanBaseUrl: String
        get() = baseUrl.trim().trimEnd('/').removeSuffix("/v1beta")

    val decisionEndpoint: String
        get() = "$cleanBaseUrl/v1beta/decisions"

    companion object {
        const val DEFAULT_MODEL = "gemini-decision-1"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_THRESHOLD = 0.7
        const val DEFAULT_TIMEOUT_SECONDS = 10L
        const val DEFAULT_TIMEOUT_MILLIS = DEFAULT_TIMEOUT_SECONDS * 1000L
        const val DEFAULT_DECISION_MESSAGE_COUNT = 15
        const val MIN_DECISION_MESSAGE_COUNT = 1
        const val MAX_DECISION_MESSAGE_COUNT = 100

        private fun normalizeModel(raw: String?): String = raw?.trim()?.ifBlank { null }?.removePrefix("models/") ?: DEFAULT_MODEL

        private fun normalizeBaseUrl(raw: String?): String =
            raw
                ?.trim()
                ?.ifBlank { null }
                ?.trimEnd('/')
                ?.removeSuffix("/v1beta") ?: DEFAULT_BASE_URL

        private fun normalizeThreshold(raw: Double?): Double =
            if (raw == null || raw.isNaN() || raw.isInfinite()) {
                DEFAULT_THRESHOLD
            } else {
                raw.coerceIn(0.0, 1.0)
            }

        private fun normalizeTimeoutMillis(timeoutSeconds: Long?): Long = timeoutSeconds?.takeIf { it > 0L }?.let { it * 1000L } ?: DEFAULT_TIMEOUT_MILLIS

        private fun normalizeInstructions(raw: String?): String = raw?.trim()?.ifBlank { null } ?: GeminiDecisionClient.DEFAULT_INSTRUCTIONS

        private fun normalizeDecisionMessageCount(raw: Long?): Int =
            if (raw == null || raw <= 0L) {
                DEFAULT_DECISION_MESSAGE_COUNT
            } else {
                raw.coerceIn(MIN_DECISION_MESSAGE_COUNT.toLong(), MAX_DECISION_MESSAGE_COUNT.toLong()).toInt()
            }

        private fun readStringConfig(
            config: Map<String, Any?>,
            vararg keys: String,
        ): String? = keys.firstNotNullOfOrNull { key -> (config[key] as? String)?.trim()?.ifBlank { null } }

        private fun readDoubleConfig(
            config: Map<String, Any?>,
            vararg keys: String,
        ): Double? =
            keys.firstNotNullOfOrNull { key ->
                when (val value = config[key]) {
                    is Number -> value.toDouble()
                    is String -> value.trim().toDoubleOrNull()
                    else -> null
                }
            }

        private fun readLongConfig(
            config: Map<String, Any?>,
            vararg keys: String,
        ): Long? =
            keys.firstNotNullOfOrNull { key ->
                when (val value = config[key]) {
                    is Number -> value.toLong()
                    is String -> value.trim().toLongOrNull()
                    else -> null
                }
            }

        private fun readStringConfig(
            context: PluginContext,
            vararg keys: String,
        ): String? = keys.firstNotNullOfOrNull { key -> context.config<String>(key)?.trim()?.ifBlank { null } }

        private fun readDoubleConfig(
            context: PluginContext,
            vararg keys: String,
        ): Double? =
            keys.firstNotNullOfOrNull { key ->
                context.config<Double>(key)
                    ?: context.config<String>(key)?.trim()?.toDoubleOrNull()
            }

        private fun readLongConfig(
            context: PluginContext,
            vararg keys: String,
        ): Long? =
            keys.firstNotNullOfOrNull { key ->
                context.config<Long>(key)
                    ?: context.config<Int>(key)?.toLong()
                    ?: context.config<String>(key)?.trim()?.toLongOrNull()
            }

        fun fromEnv(env: Map<String, String> = System.getenv()): ChatterDecisionConfig =
            ChatterDecisionConfig(
                apiKey = env["GEMINI_API_KEY"]?.trim().orEmpty(),
                model = normalizeModel(env["CHATTER_DECISION_MODEL"]),
                baseUrl = normalizeBaseUrl(env["GEMINI_BASE_URL"]),
                threshold = normalizeThreshold(env["CHATTER_DECISION_THRESHOLD"]?.trim()?.toDoubleOrNull()),
                timeoutMillis = normalizeTimeoutMillis(env["CHATTER_DECISION_TIMEOUT_SECONDS"]?.trim()?.toLongOrNull()),
                instructions = normalizeInstructions(env["CHATTER_DECISION_INSTRUCTIONS"]),
                decisionMessageCount = normalizeDecisionMessageCount(env["CHATTER_DECISION_MESSAGE_COUNT"]?.trim()?.toLongOrNull()),
            )

        fun fromConfig(config: Map<String, Any?>): ChatterDecisionConfig =
            ChatterDecisionConfig(
                apiKey = readStringConfig(config, "GEMINI_API_KEY", "gemini.apiKey", "chatter.apiKey").orEmpty(),
                model =
                    normalizeModel(
                        readStringConfig(config, "CHATTER_DECISION_MODEL", "chatter.decision.model", "chatter.decisionModel"),
                    ),
                baseUrl = normalizeBaseUrl(readStringConfig(config, "GEMINI_BASE_URL", "gemini.baseUrl", "chatter.baseUrl")),
                threshold =
                    normalizeThreshold(
                        readDoubleConfig(
                            config,
                            "CHATTER_DECISION_THRESHOLD",
                            "chatter.decision.threshold",
                            "chatter.decisionThreshold",
                        ),
                    ),
                timeoutMillis =
                    normalizeTimeoutMillis(
                        readLongConfig(
                            config,
                            "CHATTER_DECISION_TIMEOUT_SECONDS",
                            "chatter.decision.timeoutSeconds",
                            "chatter.decisionTimeoutSeconds",
                        ),
                    ),
                instructions =
                    normalizeInstructions(
                        readStringConfig(
                            config,
                            "CHATTER_DECISION_INSTRUCTIONS",
                            "chatter.decision.instructions",
                            "chatter.decisionInstructions",
                        ),
                    ),
                decisionMessageCount =
                    normalizeDecisionMessageCount(
                        readLongConfig(
                            config,
                            "CHATTER_DECISION_MESSAGE_COUNT",
                            "chatter.decision.messageCount",
                            "chatter.decisionMessageCount",
                        ),
                    ),
            )

        fun fromPluginContext(context: PluginContext): ChatterDecisionConfig =
            ChatterDecisionConfig(
                apiKey = readStringConfig(context, "GEMINI_API_KEY", "gemini.apiKey", "chatter.apiKey").orEmpty(),
                model =
                    normalizeModel(
                        readStringConfig(context, "CHATTER_DECISION_MODEL", "chatter.decision.model", "chatter.decisionModel"),
                    ),
                baseUrl = normalizeBaseUrl(readStringConfig(context, "GEMINI_BASE_URL", "gemini.baseUrl", "chatter.baseUrl")),
                threshold =
                    normalizeThreshold(
                        readDoubleConfig(
                            context,
                            "CHATTER_DECISION_THRESHOLD",
                            "chatter.decision.threshold",
                            "chatter.decisionThreshold",
                        ),
                    ),
                timeoutMillis =
                    normalizeTimeoutMillis(
                        readLongConfig(
                            context,
                            "CHATTER_DECISION_TIMEOUT_SECONDS",
                            "chatter.decision.timeoutSeconds",
                            "chatter.decisionTimeoutSeconds",
                        ),
                    ),
                instructions =
                    normalizeInstructions(
                        readStringConfig(
                            context,
                            "CHATTER_DECISION_INSTRUCTIONS",
                            "chatter.decision.instructions",
                            "chatter.decisionInstructions",
                        ),
                    ),
                decisionMessageCount =
                    normalizeDecisionMessageCount(
                        readLongConfig(
                            context,
                            "CHATTER_DECISION_MESSAGE_COUNT",
                            "chatter.decision.messageCount",
                            "chatter.decisionMessageCount",
                        ),
                    ),
            )
    }
}
