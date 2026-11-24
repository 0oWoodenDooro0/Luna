package luna.chatter

import luna.chatter.memory.ChatterMemoryConfig
import luna.config.ConfigValidationException
import luna.config.EnvReader
import luna.config.SecretMasker
import java.time.DateTimeException
import java.time.ZoneId
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
 * @property maxImages Maximum number of image attachments processed per chatter context (defaults to `10`).
 * @property youtubeApiKey Optional YouTube Data API key for rich video context enrichment.
 * @property decision Nested configuration for `gemini-decision-1` participation evaluation.
 * @property filter Nested configuration for `ChatterNoiseFilter` (enable flag, channel whitelist, cooldown).
 * @property memory Nested configuration for `Working Memory Window` and `Long-Term Memory`.
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
    val maxImages: Int = ChatterContextCollector.DEFAULT_MAX_IMAGES,
    val youtubeApiKey: String? = null,
    val decision: Decision = Decision(),
    val filter: ChatterFilterConfig = ChatterFilterConfig(),
    val memory: ChatterMemoryConfig = ChatterMemoryConfig(),
) {
    /**
     * Configuration for `gemini-decision-1` participation evaluation (`POST /v1beta/decisions`).
     */
    data class Decision(
        val model: String = DEFAULT_DECISION_MODEL,
        val threshold: Double = DEFAULT_DECISION_THRESHOLD,
        val timeoutMillis: Long = DEFAULT_DECISION_TIMEOUT_MILLIS,
        val instructions: String = GeminiDecisionClient.DEFAULT_INSTRUCTIONS,
        val decisionMessageCount: Int = DEFAULT_DECISION_MESSAGE_COUNT,
    ) {
        val cleanModel: String
            get() = model.trim().removePrefix("models/")

        init {
            if (model.isBlank()) {
                throw validationError("CHATTER_DECISION_MODEL", "a non-blank model identifier", model)
            }
            requireDoubleRange(
                "CHATTER_DECISION_THRESHOLD",
                threshold,
                MIN_DECISION_THRESHOLD..MAX_DECISION_THRESHOLD,
            )
            if (timeoutMillis <= 0L) {
                throw validationError("CHATTER_DECISION_TIMEOUT_SECONDS", "a positive duration", "${timeoutMillis}ms")
            }
            requireIntRange(
                "CHATTER_DECISION_MESSAGE_COUNT",
                decisionMessageCount,
                MIN_DECISION_MESSAGE_COUNT..MAX_DECISION_MESSAGE_COUNT,
            )
        }
    }

    init {
        requireIntRange("CHATTER_MAX_OUTPUT_CHARACTERS", maxOutputCharacters, 1..Int.MAX_VALUE)
        requirePositive("CHATTER_DEBOUNCE_SECONDS", debounceSeconds)
        requirePositive("CHATTER_MENTION_DEBOUNCE_SECONDS", mentionDebounceSeconds)
        requireDoubleRange(
            "CHATTER_DEBOUNCE_MAX_SECONDS",
            debounceMaxSeconds,
            MIN_DEBOUNCE_MAX_SECONDS..MAX_DEBOUNCE_MAX_SECONDS,
        )
        requireIntRange("CHATTER_MAX_INTERRUPTS", maxInterrupts, 0..Int.MAX_VALUE)
        requireTimezone("CHATTER_TIMEZONE", timezone)
        requireDoubleRange(
            "CHATTER_REACTION_THRESHOLD",
            reactionThreshold,
            MIN_REACTION_THRESHOLD..MAX_REACTION_THRESHOLD,
        )
        requireIntRange("SUMMARY_MAX_IMAGES", maxImages, 1..Int.MAX_VALUE)
    }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    val effectiveEnabled: Boolean
        get() = filter.enabled && isConfigured

    val cleanModel: String
        get() = model.trim().removePrefix("models/")

    val cleanEmojiVisionModel: String?
        get() = emojiVisionModel?.trim()?.removePrefix("models/")?.ifBlank { null }

    val cleanBaseUrl: String
        get() = baseUrl.trim().trimEnd('/').removeSuffix("/v1beta")

    val decisionEndpoint: String
        get() = "$cleanBaseUrl/v1beta/decisions"

    val debounceMillis: Long
        get() = (debounceSeconds * 1000.0).roundToLong()

    val mentionDebounceMillis: Long
        get() = (mentionDebounceSeconds * 1000.0).roundToLong()

    val debounceMaxMillis: Long
        get() = (debounceMaxSeconds * 1000.0).roundToLong()

    companion object {
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

        const val DEFAULT_DECISION_MODEL = "gemini-decision-1"
        const val DEFAULT_DECISION_THRESHOLD = 0.7
        const val MIN_DECISION_THRESHOLD = 0.0
        const val MAX_DECISION_THRESHOLD = 1.0
        const val DEFAULT_DECISION_TIMEOUT_SECONDS = 15L
        const val DEFAULT_DECISION_TIMEOUT_MILLIS = DEFAULT_DECISION_TIMEOUT_SECONDS * 1000L
        const val DEFAULT_DECISION_MESSAGE_COUNT = 15
        const val MIN_DECISION_MESSAGE_COUNT = 1
        const val MAX_DECISION_MESSAGE_COUNT = 100
        private const val MAX_DECISION_TIMEOUT_SECONDS = Long.MAX_VALUE / 1000L

        internal fun validationError(
            key: String,
            expected: String,
            actual: Any?,
        ): ConfigValidationException {
            val safeValue = SecretMasker.maskValue(key, actual)
            return ConfigValidationException("Invalid configuration for $key: expected $expected, but got '$safeValue'")
        }

        private fun requirePositive(
            key: String,
            value: Double,
        ): Double {
            if (!value.isFinite() || value <= 0.0) {
                throw validationError(key, "a positive finite number", value)
            }
            return value
        }

        private fun requireDoubleRange(
            key: String,
            value: Double,
            range: ClosedFloatingPointRange<Double>,
        ): Double {
            if (!value.isFinite() || value !in range) {
                throw validationError(key, "a number in ${range.start}..${range.endInclusive}", value)
            }
            return value
        }

        private fun requireIntRange(
            key: String,
            value: Int,
            range: IntRange,
        ): Int {
            if (value !in range) {
                throw validationError(key, "an integer in ${range.first}..${range.last}", value)
            }
            return value
        }

        private fun requireTimezone(
            key: String,
            value: String,
        ): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                throw validationError(key, "a valid IANA timezone ID", value)
            }
            try {
                ZoneId.of(trimmed)
            } catch (_: DateTimeException) {
                throw validationError(key, "a valid IANA timezone ID", value)
            }
            return trimmed
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
            val maxImages =
                env.int(
                    "SUMMARY_MAX_IMAGES",
                    ChatterContextCollector.DEFAULT_MAX_IMAGES,
                    1..Int.MAX_VALUE,
                )
            val youtubeApiKey = env.string("YOUTUBE_API_KEY")
            val decision =
                Decision(
                    model =
                        env
                            .string("CHATTER_DECISION_MODEL")
                            ?.removePrefix("models/")
                            ?.ifBlank { null }
                            ?: DEFAULT_DECISION_MODEL,
                    threshold =
                        env.double(
                            "CHATTER_DECISION_THRESHOLD",
                            DEFAULT_DECISION_THRESHOLD,
                            MIN_DECISION_THRESHOLD..MAX_DECISION_THRESHOLD,
                        ),
                    timeoutMillis =
                        env.long(
                            "CHATTER_DECISION_TIMEOUT_SECONDS",
                            DEFAULT_DECISION_TIMEOUT_SECONDS,
                            1L..MAX_DECISION_TIMEOUT_SECONDS,
                        ) * 1000L,
                    instructions = env.string("CHATTER_DECISION_INSTRUCTIONS", GeminiDecisionClient.DEFAULT_INSTRUCTIONS),
                    decisionMessageCount =
                        env.int(
                            "CHATTER_DECISION_MESSAGE_COUNT",
                            DEFAULT_DECISION_MESSAGE_COUNT,
                            MIN_DECISION_MESSAGE_COUNT..MAX_DECISION_MESSAGE_COUNT,
                        ),
                )
            val filter = ChatterFilterConfig.from(env)
            val memory = ChatterMemoryConfig.from(env)

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
                maxImages = maxImages,
                youtubeApiKey = youtubeApiKey,
                decision = decision,
                filter = filter,
                memory = memory,
            )
        }
    }
}
