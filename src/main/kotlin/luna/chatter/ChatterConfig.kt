package luna.chatter

import luna.plugin.PluginContext
import luna.plugin.config
import org.slf4j.LoggerFactory
import kotlin.math.roundToLong

/**
 * Configuration for Google Gemini Conversational Dialogue Generator (Chatter).
 *
 * @property apiKey Google Gemini API key.
 * @property model Model identifier to use (defaults to `gemini-3.5-flash-lite`).
 * @property baseUrl Base URL for Google Generative Language API.
 * @property maxOutputCharacters Maximum length of generated Discord response (defaults to 2000).
 * @property searchGroundingEnabled Whether autonomous Google Search Grounding is enabled (defaults to true).
 * @property urlContextEnabled Whether Gemini URL Context tool is enabled for general public web URLs (defaults to true).
 * @property debounceSeconds Burst debounce window duration for ambient messages in seconds (defaults to 2.5).
 * @property mentionDebounceSeconds Burst debounce window duration for direct `@Luna` mentions in seconds (defaults to 1.2).
 * @property debounceMaxSeconds Maximum cumulative burst debounce window ceiling in seconds, clamped to `[6.0, 8.0]` (defaults to 7.0).
 * @property maxInterrupts Maximum number of pre-send cancellations/restarts allowed per round before locking in delivery (defaults to 2).
 * @property timezone IANA timezone ID for `Circadian Persona Drift` (defaults to `Asia/Taipei`).
 * @property driftEnabled Whether `Circadian Persona Drift` tone cues and threshold offsets are enabled (defaults to `true`).
 */
data class ChatterConfig(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val maxOutputCharacters: Int = DEFAULT_MAX_OUTPUT_CHARACTERS,
    val searchGroundingEnabled: Boolean = DEFAULT_SEARCH_GROUNDING_ENABLED,
    val urlContextEnabled: Boolean = DEFAULT_URL_CONTEXT_ENABLED,
    val debounceSeconds: Double = DEFAULT_DEBOUNCE_SECONDS,
    val mentionDebounceSeconds: Double = DEFAULT_MENTION_DEBOUNCE_SECONDS,
    val debounceMaxSeconds: Double = DEFAULT_DEBOUNCE_MAX_SECONDS,
    val maxInterrupts: Int = DEFAULT_MAX_INTERRUPTS,
    val timezone: String = DEFAULT_TIMEZONE,
    val driftEnabled: Boolean = DEFAULT_DRIFT_ENABLED,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    val cleanModel: String
        get() = model.trim().removePrefix("models/")

    val cleanBaseUrl: String
        get() = baseUrl.trim().trimEnd('/').removeSuffix("/v1beta")

    val debounceMillis: Long
        get() = (normalizePositiveSeconds(debounceSeconds, DEFAULT_DEBOUNCE_SECONDS, "CHATTER_DEBOUNCE_SECONDS") * 1000.0).roundToLong()

    val mentionDebounceMillis: Long
        get() =
            (
                normalizePositiveSeconds(
                    mentionDebounceSeconds,
                    DEFAULT_MENTION_DEBOUNCE_SECONDS,
                    "CHATTER_MENTION_DEBOUNCE_SECONDS",
                ) * 1000.0
            ).roundToLong()

    val debounceMaxMillis: Long
        get() = (normalizeDebounceMaxSeconds(debounceMaxSeconds) * 1000.0).roundToLong()

    val normalizedMaxInterrupts: Int
        get() = normalizeMaxInterrupts(maxInterrupts.toLong())

    companion object {
        private val logger = LoggerFactory.getLogger(ChatterConfig::class.java)

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MAX_OUTPUT_CHARACTERS = 2000
        const val DEFAULT_SEARCH_GROUNDING_ENABLED = true
        const val DEFAULT_URL_CONTEXT_ENABLED = true
        const val DEFAULT_DEBOUNCE_SECONDS = 2.5
        const val DEFAULT_MENTION_DEBOUNCE_SECONDS = 1.2
        const val DEFAULT_DEBOUNCE_MAX_SECONDS = 7.0
        const val MIN_DEBOUNCE_MAX_SECONDS = 6.0
        const val MAX_DEBOUNCE_MAX_SECONDS = 8.0
        const val DEFAULT_MAX_INTERRUPTS = 2
        const val DEFAULT_TIMEZONE = "Asia/Taipei"
        const val DEFAULT_DRIFT_ENABLED = true

        const val DEFAULT_DEBOUNCE_MILLIS = 2_500L
        const val DEFAULT_MENTION_DEBOUNCE_MILLIS = 1_200L
        const val DEFAULT_DEBOUNCE_MAX_MILLIS = 7_000L
        const val MIN_DEBOUNCE_MAX_MILLIS = 6_000L
        const val MAX_DEBOUNCE_MAX_MILLIS = 8_000L

        private fun normalizePositiveSeconds(
            raw: Double?,
            defaultValue: Double,
            keyName: String,
        ): Double {
            if (raw == null) return defaultValue
            if (raw.isNaN() || raw.isInfinite() || raw <= 0.0) {
                logger.warn("Invalid {} '{}'; falling back to default {}s", keyName, raw, defaultValue)
                return defaultValue
            }
            return raw
        }

        private fun normalizeDebounceMaxSeconds(raw: Double?): Double {
            if (raw == null) return DEFAULT_DEBOUNCE_MAX_SECONDS
            if (raw.isNaN() || raw.isInfinite() || raw <= 0.0) {
                logger.warn(
                    "Invalid CHATTER_DEBOUNCE_MAX_SECONDS '{}'; falling back to default {}s",
                    raw,
                    DEFAULT_DEBOUNCE_MAX_SECONDS,
                )
                return DEFAULT_DEBOUNCE_MAX_SECONDS
            }
            val clamped = raw.coerceIn(MIN_DEBOUNCE_MAX_SECONDS, MAX_DEBOUNCE_MAX_SECONDS)
            if (clamped != raw) {
                logger.warn(
                    "Out-of-range CHATTER_DEBOUNCE_MAX_SECONDS '{}'; clamped to {}s (allowed [{}, {}])",
                    raw,
                    clamped,
                    MIN_DEBOUNCE_MAX_SECONDS,
                    MAX_DEBOUNCE_MAX_SECONDS,
                )
            }
            return clamped
        }

        private fun normalizeMaxInterrupts(raw: Long?): Int {
            if (raw == null) return DEFAULT_MAX_INTERRUPTS
            if (raw < 0L || raw > Int.MAX_VALUE) {
                logger.warn(
                    "Invalid CHATTER_MAX_INTERRUPTS '{}'; falling back to default {}",
                    raw,
                    DEFAULT_MAX_INTERRUPTS,
                )
                return DEFAULT_MAX_INTERRUPTS
            }
            return raw.toInt()
        }

        private fun parseDoubleOrNull(
            raw: String?,
            keyName: String,
        ): Double? {
            val trimmed = raw?.trim()?.ifBlank { null } ?: return null
            val parsed = trimmed.toDoubleOrNull()
            if (parsed == null) {
                logger.warn("Unparseable numeric value '{}' for {}; using default", trimmed, keyName)
            }
            return parsed
        }

        private fun parseLongOrNull(
            raw: String?,
            keyName: String,
        ): Long? {
            val trimmed = raw?.trim()?.ifBlank { null } ?: return null
            val parsed = trimmed.toLongOrNull()
            if (parsed == null) {
                logger.warn("Unparseable integer value '{}' for {}; using default", trimmed, keyName)
            }
            return parsed
        }

        private fun parseBooleanOrNull(
            raw: String?,
            keyName: String,
        ): Boolean? {
            val trimmed = raw?.trim()?.ifBlank { null } ?: return null
            val parsed = trimmed.toBooleanStrictOrNull()
            if (parsed == null) {
                logger.warn("Unparseable boolean value '{}' for {}; using default", trimmed, keyName)
            }
            return parsed
        }

        private fun readStringConfig(
            config: Map<String, Any?>,
            vararg keys: String,
        ): String? = keys.firstNotNullOfOrNull { key -> (config[key] as? String)?.trim()?.ifBlank { null } }

        private fun readBooleanConfig(
            config: Map<String, Any?>,
            keyName: String,
            vararg keys: String,
        ): Boolean? =
            keys.firstNotNullOfOrNull { key ->
                when (val value = config[key]) {
                    is Boolean -> value
                    is String -> parseBooleanOrNull(value, keyName)
                    else -> null
                }
            }

        private fun readStringConfig(
            context: PluginContext,
            vararg keys: String,
        ): String? = keys.firstNotNullOfOrNull { key -> context.config<String>(key)?.trim()?.ifBlank { null } }

        private fun readBooleanConfig(
            context: PluginContext,
            keyName: String,
            vararg keys: String,
        ): Boolean? =
            keys.firstNotNullOfOrNull { key ->
                context.config<Boolean>(key)
                    ?: parseBooleanOrNull(context.config<String>(key), keyName)
            }

        private fun readDoubleConfig(
            config: Map<String, Any?>,
            keyName: String,
            vararg keys: String,
        ): Double? =
            keys.firstNotNullOfOrNull { key ->
                when (val value = config[key]) {
                    is Number -> value.toDouble()
                    is String -> parseDoubleOrNull(value, keyName)
                    else -> null
                }
            }

        private fun readLongConfig(
            config: Map<String, Any?>,
            keyName: String,
            vararg keys: String,
        ): Long? =
            keys.firstNotNullOfOrNull { key ->
                when (val value = config[key]) {
                    is Number -> value.toLong()
                    is String -> parseLongOrNull(value, keyName)
                    else -> null
                }
            }

        private fun readDoubleConfig(
            context: PluginContext,
            keyName: String,
            vararg keys: String,
        ): Double? =
            keys.firstNotNullOfOrNull { key ->
                context.config<Double>(key)
                    ?: context.config<Number>(key)?.toDouble()
                    ?: parseDoubleOrNull(context.config<String>(key), keyName)
            }

        private fun readLongConfig(
            context: PluginContext,
            keyName: String,
            vararg keys: String,
        ): Long? =
            keys.firstNotNullOfOrNull { key ->
                context.config<Long>(key)
                    ?: context.config<Int>(key)?.toLong()
                    ?: parseLongOrNull(context.config<String>(key), keyName)
            }

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

            val debounceSeconds =
                normalizePositiveSeconds(
                    parseDoubleOrNull(env["CHATTER_DEBOUNCE_SECONDS"], "CHATTER_DEBOUNCE_SECONDS"),
                    DEFAULT_DEBOUNCE_SECONDS,
                    "CHATTER_DEBOUNCE_SECONDS",
                )
            val mentionDebounceSeconds =
                normalizePositiveSeconds(
                    parseDoubleOrNull(env["CHATTER_MENTION_DEBOUNCE_SECONDS"], "CHATTER_MENTION_DEBOUNCE_SECONDS"),
                    DEFAULT_MENTION_DEBOUNCE_SECONDS,
                    "CHATTER_MENTION_DEBOUNCE_SECONDS",
                )
            val debounceMaxSeconds =
                normalizeDebounceMaxSeconds(
                    parseDoubleOrNull(env["CHATTER_DEBOUNCE_MAX_SECONDS"], "CHATTER_DEBOUNCE_MAX_SECONDS"),
                )
            val maxInterrupts =
                normalizeMaxInterrupts(
                    parseLongOrNull(env["CHATTER_MAX_INTERRUPTS"], "CHATTER_MAX_INTERRUPTS"),
                )
            val timezone = env["CHATTER_TIMEZONE"]?.trim()?.ifBlank { null } ?: DEFAULT_TIMEZONE
            val driftEnabled =
                parseBooleanOrNull(env["CHATTER_DRIFT_ENABLED"], "CHATTER_DRIFT_ENABLED")
                    ?: DEFAULT_DRIFT_ENABLED

            return ChatterConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGroundingEnabled,
                urlContextEnabled = urlContextEnabled,
                debounceSeconds = debounceSeconds,
                mentionDebounceSeconds = mentionDebounceSeconds,
                debounceMaxSeconds = debounceMaxSeconds,
                maxInterrupts = maxInterrupts,
                timezone = timezone,
                driftEnabled = driftEnabled,
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

            val debounceSeconds =
                normalizePositiveSeconds(
                    readDoubleConfig(
                        config,
                        "CHATTER_DEBOUNCE_SECONDS",
                        "CHATTER_DEBOUNCE_SECONDS",
                        "chatter.debounceSeconds",
                        "chatter.debounce_seconds",
                    ),
                    DEFAULT_DEBOUNCE_SECONDS,
                    "CHATTER_DEBOUNCE_SECONDS",
                )
            val mentionDebounceSeconds =
                normalizePositiveSeconds(
                    readDoubleConfig(
                        config,
                        "CHATTER_MENTION_DEBOUNCE_SECONDS",
                        "CHATTER_MENTION_DEBOUNCE_SECONDS",
                        "chatter.mentionDebounceSeconds",
                        "chatter.mention_debounce_seconds",
                    ),
                    DEFAULT_MENTION_DEBOUNCE_SECONDS,
                    "CHATTER_MENTION_DEBOUNCE_SECONDS",
                )
            val debounceMaxSeconds =
                normalizeDebounceMaxSeconds(
                    readDoubleConfig(
                        config,
                        "CHATTER_DEBOUNCE_MAX_SECONDS",
                        "CHATTER_DEBOUNCE_MAX_SECONDS",
                        "chatter.debounceMaxSeconds",
                        "chatter.debounce_max_seconds",
                    ),
                )
            val maxInterrupts =
                normalizeMaxInterrupts(
                    readLongConfig(
                        config,
                        "CHATTER_MAX_INTERRUPTS",
                        "CHATTER_MAX_INTERRUPTS",
                        "chatter.maxInterrupts",
                        "chatter.max_interrupts",
                    ),
                )
            val timezone =
                readStringConfig(
                    config,
                    "CHATTER_TIMEZONE",
                    "chatter.timezone",
                    "chatter.timeZone",
                    "chatter.time_zone",
                ) ?: DEFAULT_TIMEZONE
            val driftEnabled =
                readBooleanConfig(
                    config,
                    "CHATTER_DRIFT_ENABLED",
                    "CHATTER_DRIFT_ENABLED",
                    "chatter.driftEnabled",
                    "chatter.drift_enabled",
                ) ?: DEFAULT_DRIFT_ENABLED

            return ChatterConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGroundingEnabled,
                urlContextEnabled = urlContextEnabled,
                debounceSeconds = debounceSeconds,
                mentionDebounceSeconds = mentionDebounceSeconds,
                debounceMaxSeconds = debounceMaxSeconds,
                maxInterrupts = maxInterrupts,
                timezone = timezone,
                driftEnabled = driftEnabled,
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

            val debounceSeconds =
                normalizePositiveSeconds(
                    readDoubleConfig(
                        context,
                        "CHATTER_DEBOUNCE_SECONDS",
                        "CHATTER_DEBOUNCE_SECONDS",
                        "chatter.debounceSeconds",
                        "chatter.debounce_seconds",
                    ),
                    DEFAULT_DEBOUNCE_SECONDS,
                    "CHATTER_DEBOUNCE_SECONDS",
                )
            val mentionDebounceSeconds =
                normalizePositiveSeconds(
                    readDoubleConfig(
                        context,
                        "CHATTER_MENTION_DEBOUNCE_SECONDS",
                        "CHATTER_MENTION_DEBOUNCE_SECONDS",
                        "chatter.mentionDebounceSeconds",
                        "chatter.mention_debounce_seconds",
                    ),
                    DEFAULT_MENTION_DEBOUNCE_SECONDS,
                    "CHATTER_MENTION_DEBOUNCE_SECONDS",
                )
            val debounceMaxSeconds =
                normalizeDebounceMaxSeconds(
                    readDoubleConfig(
                        context,
                        "CHATTER_DEBOUNCE_MAX_SECONDS",
                        "CHATTER_DEBOUNCE_MAX_SECONDS",
                        "chatter.debounceMaxSeconds",
                        "chatter.debounce_max_seconds",
                    ),
                )
            val maxInterrupts =
                normalizeMaxInterrupts(
                    readLongConfig(
                        context,
                        "CHATTER_MAX_INTERRUPTS",
                        "CHATTER_MAX_INTERRUPTS",
                        "chatter.maxInterrupts",
                        "chatter.max_interrupts",
                    ),
                )
            val timezone =
                readStringConfig(
                    context,
                    "CHATTER_TIMEZONE",
                    "chatter.timezone",
                    "chatter.timeZone",
                    "chatter.time_zone",
                ) ?: DEFAULT_TIMEZONE
            val driftEnabled =
                readBooleanConfig(
                    context,
                    "CHATTER_DRIFT_ENABLED",
                    "CHATTER_DRIFT_ENABLED",
                    "chatter.driftEnabled",
                    "chatter.drift_enabled",
                ) ?: DEFAULT_DRIFT_ENABLED

            return ChatterConfig(
                apiKey = key,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGroundingEnabled,
                urlContextEnabled = urlContextEnabled,
                debounceSeconds = debounceSeconds,
                mentionDebounceSeconds = mentionDebounceSeconds,
                debounceMaxSeconds = debounceMaxSeconds,
                maxInterrupts = maxInterrupts,
                timezone = timezone,
                driftEnabled = driftEnabled,
            )
        }
    }
}
