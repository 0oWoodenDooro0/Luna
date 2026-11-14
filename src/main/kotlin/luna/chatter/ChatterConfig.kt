package luna.chatter

import luna.plugin.PluginContext
import luna.plugin.config

/**
 * Configuration for Google Gemini Conversational Dialogue Generator (Chatter).
 *
 * @property apiKey Google Gemini API key.
 * @property model Model identifier to use (defaults to `gemini-3.5-flash-lite`).
 * @property baseUrl Base URL for Google Generative Language API.
 * @property maxOutputCharacters Maximum length of generated Discord response (defaults to 2000).
 * @property searchGroundingEnabled Whether autonomous Google Search Grounding is enabled (defaults to true).
 * @property urlContextEnabled Whether Gemini URL Context tool is enabled for general public web URLs (defaults to true).
 */
data class ChatterConfig(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val maxOutputCharacters: Int = DEFAULT_MAX_OUTPUT_CHARACTERS,
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
        const val DEFAULT_MAX_OUTPUT_CHARACTERS = 2000
        const val DEFAULT_SEARCH_GROUNDING_ENABLED = true
        const val DEFAULT_URL_CONTEXT_ENABLED = true

        fun fromEnv(env: Map<String, String> = System.getenv()): ChatterConfig {
            val key = env["GEMINI_API_KEY"]?.trim().orEmpty()

            val rawModel =
                env["CHATTER_GEMINI_MODEL"]?.trim()?.ifBlank { null }
                    ?: env["GEMINI_MODEL"]?.trim()
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

            return ChatterConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGroundingEnabled,
                urlContextEnabled = urlContextEnabled,
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

            val searchGroundingEnabled =
                (config["GEMINI_SEARCH_GROUNDING_ENABLED"] as? Boolean)
                    ?: (config["GEMINI_SEARCH_GROUNDING_ENABLED"] as? String)?.trim()?.toBooleanStrictOrNull()
                    ?: (config["gemini.searchGroundingEnabled"] as? Boolean)
                    ?: (config["gemini.searchGroundingEnabled"] as? String)?.trim()?.toBooleanStrictOrNull()
                    ?: DEFAULT_SEARCH_GROUNDING_ENABLED

            val urlContextEnabled =
                (config["GEMINI_URL_CONTEXT_ENABLED"] as? Boolean)
                    ?: (config["GEMINI_URL_CONTEXT_ENABLED"] as? String)?.trim()?.toBooleanStrictOrNull()
                    ?: (config["gemini.urlContextEnabled"] as? Boolean)
                    ?: (config["gemini.urlContextEnabled"] as? String)?.trim()?.toBooleanStrictOrNull()
                    ?: DEFAULT_URL_CONTEXT_ENABLED

            return ChatterConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGroundingEnabled,
                urlContextEnabled = urlContextEnabled,
            )
        }

        fun fromPluginContext(context: PluginContext): ChatterConfig {
            val key =
                context.config<String>("GEMINI_API_KEY")?.trim()?.ifBlank { null }
                    ?: context.config<String>("gemini.apiKey")?.trim()?.ifBlank { null }
                    ?: context.config<String>("chatter.apiKey")?.trim()?.ifBlank { null }
                    ?: ""

            val rawModel =
                context.config<String>("CHATTER_GEMINI_MODEL")?.trim()?.ifBlank { null }
                    ?: context.config<String>("chatter.gemini.model")?.trim()?.ifBlank { null }
                    ?: context.config<String>("chatter.model")?.trim()?.ifBlank { null }
                    ?: context.config<String>("GEMINI_MODEL")?.trim()?.ifBlank { null }
                    ?: context.config<String>("gemini.model")?.trim()?.ifBlank { null }
            val model = if (!rawModel.isNullOrBlank()) rawModel.removePrefix("models/") else DEFAULT_MODEL

            val rawBaseUrl =
                context.config<String>("GEMINI_BASE_URL")?.trim()?.ifBlank { null }
                    ?: context.config<String>("gemini.baseUrl")?.trim()?.ifBlank { null }
                    ?: context.config<String>("chatter.baseUrl")?.trim()?.ifBlank { null }
            val baseUrl = if (!rawBaseUrl.isNullOrBlank()) rawBaseUrl.trimEnd('/').removeSuffix("/v1beta") else DEFAULT_BASE_URL

            val searchGroundingEnabled =
                context.config<Boolean>("GEMINI_SEARCH_GROUNDING_ENABLED")
                    ?: context.config<String>("GEMINI_SEARCH_GROUNDING_ENABLED")?.trim()?.toBooleanStrictOrNull()
                    ?: context.config<Boolean>("gemini.searchGroundingEnabled")
                    ?: context.config<String>("gemini.searchGroundingEnabled")?.trim()?.toBooleanStrictOrNull()
                    ?: DEFAULT_SEARCH_GROUNDING_ENABLED

            val urlContextEnabled =
                context.config<Boolean>("GEMINI_URL_CONTEXT_ENABLED")
                    ?: context.config<String>("GEMINI_URL_CONTEXT_ENABLED")?.trim()?.toBooleanStrictOrNull()
                    ?: context.config<Boolean>("gemini.urlContextEnabled")
                    ?: context.config<String>("gemini.urlContextEnabled")?.trim()?.toBooleanStrictOrNull()
                    ?: DEFAULT_URL_CONTEXT_ENABLED

            return ChatterConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGroundingEnabled,
                urlContextEnabled = urlContextEnabled,
            )
        }
    }
}
