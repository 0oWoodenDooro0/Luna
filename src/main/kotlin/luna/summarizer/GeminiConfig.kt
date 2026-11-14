package luna.summarizer

/**
 * Configuration for Google Gemini Summarizer API.
 *
 * @property apiKey Google Gemini API key.
 * @property model Model identifier to use for summarization (defaults to `gemini-3.5-flash-lite`).
 * @property baseUrl Base URL for Google Generative Language API.
 * @property searchGroundingEnabled Whether autonomous Google Search Grounding is enabled (defaults to true).
 * @property urlContextEnabled Whether Gemini URL Context tool is enabled for general public web URLs (defaults to true).
 */
data class GeminiConfig(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val searchGroundingEnabled: Boolean = DEFAULT_SEARCH_GROUNDING_ENABLED,
    val urlContextEnabled: Boolean = DEFAULT_URL_CONTEXT_ENABLED,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    val cleanModel: String
        get() = model.trim().removePrefix("models/")

    val cleanBaseUrl: String
        get() = baseUrl.trim().trimEnd('/').removeSuffix("/v1beta")

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_SEARCH_GROUNDING_ENABLED = true
        const val DEFAULT_URL_CONTEXT_ENABLED = true

        fun fromEnv(env: Map<String, String> = emptyMap()): GeminiConfig {
            val key = env["GEMINI_API_KEY"]?.trim().orEmpty()
            val rawModel = env["GEMINI_MODEL"]?.trim()
            val model = if (!rawModel.isNullOrBlank()) rawModel.removePrefix("models/") else DEFAULT_MODEL
            val rawBaseUrl = env["GEMINI_BASE_URL"]?.trim()
            val baseUrl = if (!rawBaseUrl.isNullOrBlank()) rawBaseUrl.trimEnd('/').removeSuffix("/v1beta") else DEFAULT_BASE_URL
            val searchGroundingEnabled =
                env["GEMINI_SEARCH_GROUNDING_ENABLED"]
                    ?.trim()
                    ?.toBooleanStrictOrNull()
                    ?: DEFAULT_SEARCH_GROUNDING_ENABLED
            val urlContextEnabled =
                env["GEMINI_URL_CONTEXT_ENABLED"]
                    ?.trim()
                    ?.toBooleanStrictOrNull()
                    ?: DEFAULT_URL_CONTEXT_ENABLED
            return GeminiConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGroundingEnabled,
                urlContextEnabled = urlContextEnabled,
            )
        }
    }
}
