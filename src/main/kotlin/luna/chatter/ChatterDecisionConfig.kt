package luna.chatter

import luna.config.EnvReader

/**
 * Configuration for Google Gemini Decision 1 participation evaluation (`POST /v1beta/decisions`).
 *
 * @property apiKey Google Gemini API key.
 * @property model Decision model identifier (defaults to `gemini-decision-1`).
 * @property baseUrl Base URL for Google Generative Language API (defaults to `https://generativelanguage.googleapis.com`).
 * @property threshold Minimum calibrated `noul` probability required for participation (in `0.0..1.0`, default `0.7`).
 * @property timeoutMillis HTTP request/socket/connect timeout in milliseconds (default `15_000L`).
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
        const val DEFAULT_TIMEOUT_SECONDS = 15L
        const val DEFAULT_TIMEOUT_MILLIS = DEFAULT_TIMEOUT_SECONDS * 1000L
        const val DEFAULT_DECISION_MESSAGE_COUNT = 15
        const val MIN_DECISION_MESSAGE_COUNT = 1
        const val MAX_DECISION_MESSAGE_COUNT = 100

        private fun normalizeModel(raw: String?): String =
            raw
                ?.trim()
                ?.removePrefix("models/")
                ?.ifBlank { null }
                ?: DEFAULT_MODEL

        private fun normalizeBaseUrl(raw: String?): String =
            raw
                ?.trim()
                ?.trimEnd('/')
                ?.removeSuffix("/v1beta")
                ?.ifBlank { null }
                ?: DEFAULT_BASE_URL

        /** Parses [ChatterDecisionConfig] from [env] with strict fail-fast validation. */
        fun from(env: EnvReader): ChatterDecisionConfig {
            val apiKey = env.string("GEMINI_API_KEY", "")
            val model = normalizeModel(env.string("CHATTER_DECISION_MODEL"))
            val baseUrl = normalizeBaseUrl(env.string("GEMINI_BASE_URL"))
            val threshold = env.double("CHATTER_DECISION_THRESHOLD", DEFAULT_THRESHOLD, 0.0..1.0)
            val timeoutSeconds = env.long("CHATTER_DECISION_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS, 1L..Long.MAX_VALUE)
            val instructions = env.string("CHATTER_DECISION_INSTRUCTIONS", GeminiDecisionClient.DEFAULT_INSTRUCTIONS)
            val decisionMessageCount =
                env.int(
                    "CHATTER_DECISION_MESSAGE_COUNT",
                    DEFAULT_DECISION_MESSAGE_COUNT,
                    MIN_DECISION_MESSAGE_COUNT..MAX_DECISION_MESSAGE_COUNT,
                )

            return ChatterDecisionConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                threshold = threshold,
                timeoutMillis = timeoutSeconds * 1000L,
                instructions = instructions,
                decisionMessageCount = decisionMessageCount,
            )
        }
    }
}
