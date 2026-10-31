package luna.summarizer

/**
 * Configuration for Google Gemini Summarizer API.
 *
 * @property apiKey Google Gemini API key.
 * @property model Model identifier to use for summarization (defaults to `gemini-1.5-flash`).
 * @property baseUrl Base URL for Google Generative Language API.
 */
data class GeminiConfig(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "gemini-1.5-flash"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"

        fun fromEnv(env: Map<String, String> = System.getenv()): GeminiConfig {
            val key = env["GEMINI_API_KEY"]?.trim().orEmpty()
            val rawModel = env["GEMINI_MODEL"]?.trim()
            val model = if (!rawModel.isNullOrBlank()) rawModel else DEFAULT_MODEL
            return GeminiConfig(apiKey = key, model = model)
        }
    }
}
