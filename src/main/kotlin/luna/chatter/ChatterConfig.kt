package luna.chatter

import luna.config.ConfigValidationException
import luna.config.EnvReader
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
 * @property debounceMaxSeconds Maximum cumulative burst debounce window ceiling in seconds, in `[6.0, 8.0]` (defaults to 7.0).
 * @property maxInterrupts Maximum number of pre-send cancellations/restarts allowed per round before locking in delivery (defaults to 2).
 * @property timezone IANA timezone ID for `Circadian Persona Drift` (defaults to `Asia/Taipei`).
 * @property driftEnabled Whether `Circadian Persona Drift` tone cues and threshold offsets are enabled (defaults to `true`).
 * @property reactionThreshold Minimum `noul` probability required for `Silent Reaction Path` (in `[0.0, 1.0]`, defaults to `0.45`).
 * @property emojiVisionModel Optional Gemini Vision model for `Custom Emoji Meaning` resolution (strictly opt-in, no fallback).
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
    val reactionThreshold: Double = DEFAULT_REACTION_THRESHOLD,
    val emojiVisionModel: String? = null,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    val cleanModel: String
        get() = model.trim().removePrefix("models/")

    val cleanEmojiVisionModel: String?
        get() = emojiVisionModel?.trim()?.removePrefix("models/")?.ifBlank { null }

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

    val normalizedReactionThreshold: Double
        get() = normalizeReactionThreshold(reactionThreshold)

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
        const val DEFAULT_REACTION_THRESHOLD = 0.45
        const val MIN_REACTION_THRESHOLD = 0.0
        const val MAX_REACTION_THRESHOLD = 1.0

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

        private fun normalizeReactionThreshold(raw: Double?): Double {
            if (raw == null) return DEFAULT_REACTION_THRESHOLD
            if (raw.isNaN() || raw.isInfinite()) {
                logger.warn(
                    "Invalid CHATTER_REACTION_THRESHOLD '{}'; falling back to default {}",
                    raw,
                    DEFAULT_REACTION_THRESHOLD,
                )
                return DEFAULT_REACTION_THRESHOLD
            }
            val clamped = raw.coerceIn(MIN_REACTION_THRESHOLD, MAX_REACTION_THRESHOLD)
            if (clamped != raw) {
                logger.warn(
                    "Out-of-range CHATTER_REACTION_THRESHOLD '{}'; clamped to {} (allowed [{}, {}])",
                    raw,
                    clamped,
                    MIN_REACTION_THRESHOLD,
                    MAX_REACTION_THRESHOLD,
                )
            }
            return clamped
        }

        private fun requirePositive(
            key: String,
            value: Double,
        ): Double {
            if (value <= 0.0) {
                throw ConfigValidationException(
                    "Invalid configuration for $key: expected a positive finite number, but got '$value'",
                )
            }
            return value
        }

        /** Parses [ChatterConfig] from [env] with strict fail-fast validation. */
        fun from(env: EnvReader): ChatterConfig {
            val key = env.string("GEMINI_API_KEY", "")
            val model =
                env
                    .firstString("CHATTER_GEMINI_MODEL", "GEMINI_MODEL")
                    ?.removePrefix("models/")
                    ?.ifBlank { null }
                    ?: DEFAULT_MODEL
            val baseUrl =
                env
                    .string("GEMINI_BASE_URL")
                    ?.trimEnd('/')
                    ?.removeSuffix("/v1beta")
                    ?.ifBlank { null }
                    ?: DEFAULT_BASE_URL
            val searchGroundingEnabled = env.boolean("GEMINI_SEARCH_GROUNDING_ENABLED", DEFAULT_SEARCH_GROUNDING_ENABLED)
            val urlContextEnabled = env.boolean("GEMINI_URL_CONTEXT_ENABLED", DEFAULT_URL_CONTEXT_ENABLED)
            val debounceSeconds =
                requirePositive(
                    "CHATTER_DEBOUNCE_SECONDS",
                    env.double("CHATTER_DEBOUNCE_SECONDS", DEFAULT_DEBOUNCE_SECONDS),
                )
            val mentionDebounceSeconds =
                requirePositive(
                    "CHATTER_MENTION_DEBOUNCE_SECONDS",
                    env.double("CHATTER_MENTION_DEBOUNCE_SECONDS", DEFAULT_MENTION_DEBOUNCE_SECONDS),
                )
            val debounceMaxSeconds =
                env.double(
                    "CHATTER_DEBOUNCE_MAX_SECONDS",
                    DEFAULT_DEBOUNCE_MAX_SECONDS,
                    MIN_DEBOUNCE_MAX_SECONDS..MAX_DEBOUNCE_MAX_SECONDS,
                )
            val maxInterrupts = env.int("CHATTER_MAX_INTERRUPTS", DEFAULT_MAX_INTERRUPTS, 0..Int.MAX_VALUE)
            val timezone = env.string("CHATTER_TIMEZONE", DEFAULT_TIMEZONE)
            val driftEnabled = env.boolean("CHATTER_DRIFT_ENABLED", DEFAULT_DRIFT_ENABLED)
            val reactionThreshold =
                env.double(
                    "CHATTER_REACTION_THRESHOLD",
                    DEFAULT_REACTION_THRESHOLD,
                    MIN_REACTION_THRESHOLD..MAX_REACTION_THRESHOLD,
                )
            val emojiVisionModel =
                env
                    .string("CHATTER_EMOJI_VISION_MODEL")
                    ?.removePrefix("models/")
                    ?.ifBlank { null }

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
                reactionThreshold = reactionThreshold,
                emojiVisionModel = emojiVisionModel,
            )
        }
    }
}
