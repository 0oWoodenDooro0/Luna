package luna.chatter

/**
 * Configuration for Google Gemini Conversational Dialogue Generator (Chatter).
 *
 * @property apiKey Google Gemini API key.
 * @property model Model identifier to use (defaults to `gemini-3.5-flash-lite`).
 * @property baseUrl Base URL for Google Generative Language API.
 * @property maxOutputCharacters Maximum length of generated Discord response (defaults to 2000).
 */
data class ChatterConfig(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val maxOutputCharacters: Int = DEFAULT_MAX_OUTPUT_CHARACTERS,
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
        const val DEFAULT_MAX_OUTPUT_CHARACTERS = 2000

        fun fromEnv(env: Map<String, String> = System.getenv()): ChatterConfig {
            val key = env["GEMINI_API_KEY"]?.trim().orEmpty()

            val rawModel =
                env["CHATTER_GEMINI_MODEL"]?.trim()?.ifBlank { null }
                    ?: env["GEMINI_MODEL"]?.trim()
            val model = if (!rawModel.isNullOrBlank()) rawModel.removePrefix("models/") else DEFAULT_MODEL

            val rawBaseUrl = env["GEMINI_BASE_URL"]?.trim()
            val baseUrl = if (!rawBaseUrl.isNullOrBlank()) rawBaseUrl.trimEnd('/').removeSuffix("/v1beta") else DEFAULT_BASE_URL

            return ChatterConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
            )
        }

        fun fromConfig(config: Map<String, Any?>): ChatterConfig {
            val key =
                (config["GEMINI_API_KEY"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["gemini.apiKey"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["chatter.apiKey"] as? String)?.trim()?.ifBlank { null }
                    ?: ""

            val rawModel =
                (config["CHATTER_GEMINI_MODEL"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["chatter.gemini.model"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["chatter.model"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["GEMINI_MODEL"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["gemini.model"] as? String)?.trim()?.ifBlank { null }
            val model = if (!rawModel.isNullOrBlank()) rawModel.removePrefix("models/") else DEFAULT_MODEL

            val rawBaseUrl =
                (config["GEMINI_BASE_URL"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["gemini.baseUrl"] as? String)?.trim()?.ifBlank { null }
                    ?: (config["chatter.baseUrl"] as? String)?.trim()?.ifBlank { null }
            val baseUrl = if (!rawBaseUrl.isNullOrBlank()) rawBaseUrl.trimEnd('/').removeSuffix("/v1beta") else DEFAULT_BASE_URL

            return ChatterConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
            )
        }
    }
}
