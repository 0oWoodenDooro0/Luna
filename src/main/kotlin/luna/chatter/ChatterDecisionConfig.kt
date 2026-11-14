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
 */
data class ChatterDecisionConfig(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val threshold: Double = DEFAULT_THRESHOLD,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
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

        private fun normalizeThreshold(raw: Double?): Double =
            if (raw == null || raw.isNaN() || raw.isInfinite()) {
                DEFAULT_THRESHOLD
            } else {
                raw.coerceIn(0.0, 1.0)
            }

        private fun normalizeTimeoutMillis(timeoutSeconds: Long?): Long = timeoutSeconds?.takeIf { it > 0L }?.let { it * 1000L } ?: DEFAULT_TIMEOUT_MILLIS

        fun fromEnv(env: Map<String, String> = System.getenv()): ChatterDecisionConfig {
            val key = env["GEMINI_API_KEY"]?.trim().orEmpty()

            val rawModel = env["CHATTER_DECISION_MODEL"]?.trim()?.ifBlank { null }
            val model = if (!rawModel.isNullOrBlank()) rawModel.removePrefix("models/") else DEFAULT_MODEL

            val rawBaseUrl = env["GEMINI_BASE_URL"]?.trim()
            val baseUrl =
                if (!rawBaseUrl.isNullOrBlank()) {
                    rawBaseUrl.trimEnd('/').removeSuffix("/v1beta")
                } else {
                    DEFAULT_BASE_URL
                }

            val threshold = normalizeThreshold(env["CHATTER_DECISION_THRESHOLD"]?.trim()?.toDoubleOrNull())
            val timeoutSeconds = env["CHATTER_DECISION_TIMEOUT_SECONDS"]?.trim()?.toLongOrNull()

            return ChatterDecisionConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                threshold = threshold,
                timeoutMillis = normalizeTimeoutMillis(timeoutSeconds),
            )
        }

        fun fromConfig(config: Map<String, Any?>): ChatterDecisionConfig {
            val key =
                (config["GEMINI_API_KEY"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["gemini.apiKey"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["chatter.apiKey"] as? String)?.trim()?.ifBlank { null }
                    ?: ""

            val rawModel =
                (config["CHATTER_DECISION_MODEL"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["chatter.decision.model"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["chatter.decisionModel"] as? String)?.trim()?.ifBlank { null }
            val model = if (!rawModel.isNullOrBlank()) rawModel.removePrefix("models/") else DEFAULT_MODEL

            val rawBaseUrl =
                (config["GEMINI_BASE_URL"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["gemini.baseUrl"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["chatter.baseUrl"] as? String)?.trim()?.ifBlank { null }
            val baseUrl =
                if (!rawBaseUrl.isNullOrBlank()) {
                    rawBaseUrl.trimEnd('/').removeSuffix("/v1beta")
                } else {
                    DEFAULT_BASE_URL
                }

            val rawThreshold =
                (config["CHATTER_DECISION_THRESHOLD"] as? Number)?.toDouble()
                    ?: (config["CHATTER_DECISION_THRESHOLD"] as? String)?.trim()?.toDoubleOrNull()
                    ?: (config["chatter.decision.threshold"] as? Number)?.toDouble()
                    ?: (config["chatter.decision.threshold"] as? String)?.trim()?.toDoubleOrNull()
                    ?: (config["chatter.decisionThreshold"] as? Number)?.toDouble()
                    ?: (config["chatter.decisionThreshold"] as? String)?.trim()?.toDoubleOrNull()

            val timeoutSeconds =
                (config["CHATTER_DECISION_TIMEOUT_SECONDS"] as? Number)?.toLong()
                    ?: (config["CHATTER_DECISION_TIMEOUT_SECONDS"] as? String)?.trim()?.toLongOrNull()
                    ?: (config["chatter.decision.timeoutSeconds"] as? Number)?.toLong()
                    ?: (config["chatter.decision.timeoutSeconds"] as? String)?.trim()?.toLongOrNull()
                    ?: (config["chatter.decisionTimeoutSeconds"] as? Number)?.toLong()
                    ?: (config["chatter.decisionTimeoutSeconds"] as? String)?.trim()?.toLongOrNull()

            return ChatterDecisionConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                threshold = normalizeThreshold(rawThreshold),
                timeoutMillis = normalizeTimeoutMillis(timeoutSeconds),
            )
        }

        fun fromPluginContext(context: PluginContext): ChatterDecisionConfig {
            val key =
                context.config<String>("GEMINI_API_KEY")?.trim()?.ifBlank { null }
                    ?: context.config<String>("gemini.apiKey")?.trim()?.ifBlank { null }
                    ?: context.config<String>("chatter.apiKey")?.trim()?.ifBlank { null }
                    ?: ""

            val rawModel =
                context.config<String>("CHATTER_DECISION_MODEL")?.trim()?.ifBlank { null }
                    ?: context.config<String>("chatter.decision.model")?.trim()?.ifBlank { null }
                    ?: context.config<String>("chatter.decisionModel")?.trim()?.ifBlank { null }
            val model = if (!rawModel.isNullOrBlank()) rawModel.removePrefix("models/") else DEFAULT_MODEL

            val rawBaseUrl =
                context.config<String>("GEMINI_BASE_URL")?.trim()?.ifBlank { null }
                    ?: context.config<String>("gemini.baseUrl")?.trim()?.ifBlank { null }
                    ?: context.config<String>("chatter.baseUrl")?.trim()?.ifBlank { null }
            val baseUrl =
                if (!rawBaseUrl.isNullOrBlank()) {
                    rawBaseUrl.trimEnd('/').removeSuffix("/v1beta")
                } else {
                    DEFAULT_BASE_URL
                }

            val rawThreshold =
                context.config<Double>("CHATTER_DECISION_THRESHOLD")
                    ?: context.config<String>("CHATTER_DECISION_THRESHOLD")?.trim()?.toDoubleOrNull()
                    ?: context.config<Double>("chatter.decision.threshold")
                    ?: context.config<String>("chatter.decision.threshold")?.trim()?.toDoubleOrNull()
                    ?: context.config<Double>("chatter.decisionThreshold")
                    ?: context.config<String>("chatter.decisionThreshold")?.trim()?.toDoubleOrNull()

            val timeoutSeconds =
                context.config<Long>("CHATTER_DECISION_TIMEOUT_SECONDS")
                    ?: context.config<Int>("CHATTER_DECISION_TIMEOUT_SECONDS")?.toLong()
                    ?: context.config<String>("CHATTER_DECISION_TIMEOUT_SECONDS")?.trim()?.toLongOrNull()
                    ?: context.config<Long>("chatter.decision.timeoutSeconds")
                    ?: context.config<Int>("chatter.decision.timeoutSeconds")?.toLong()
                    ?: context.config<String>("chatter.decision.timeoutSeconds")?.trim()?.toLongOrNull()
                    ?: context.config<Long>("chatter.decisionTimeoutSeconds")
                    ?: context.config<Int>("chatter.decisionTimeoutSeconds")?.toLong()
                    ?: context.config<String>("chatter.decisionTimeoutSeconds")?.trim()?.toLongOrNull()

            return ChatterDecisionConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                threshold = normalizeThreshold(rawThreshold),
                timeoutMillis = normalizeTimeoutMillis(timeoutSeconds),
            )
        }
    }
}
