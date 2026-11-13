package luna.chatter

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import luna.core.ai.GeminiApiErrorDetails

/**
 * Request payload for Google Gemini Decisions API (`POST /v1beta/decisions`).
 */
@Serializable
data class GeminiDecisionRequest(
    val model: String,
    val input: List<GeminiDecisionInputBlock>,
    val questions: Map<String, GeminiDecisionQuestion>,
)

/**
 * Interactions-style input block for Gemini Decisions API (`text` or low-resolution `image`).
 */
@Serializable
data class GeminiDecisionInputBlock(
    val type: String,
    val text: String? = null,
    val uri: String? = null,
    @SerialName("mime_type")
    val mimeType: String? = null,
    val resolution: String? = null,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val RESOLUTION_LOW = "low"

        fun text(text: String): GeminiDecisionInputBlock =
            GeminiDecisionInputBlock(
                type = TYPE_TEXT,
                text = text,
            )

        fun image(
            uri: String,
            mimeType: String,
            resolution: String = RESOLUTION_LOW,
        ): GeminiDecisionInputBlock =
            GeminiDecisionInputBlock(
                type = TYPE_IMAGE,
                uri = uri,
                mimeType = mimeType,
                resolution = resolution,
            )
    }
}

/**
 * Question descriptor in the Gemini Decisions schema.
 *
 * @property type Question type (always `noul`).
 * @property instructions Direct prompt / instructions for the binary participation evaluation.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiDecisionQuestion(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = TYPE_NOUL,
    val instructions: String,
) {
    companion object {
        const val TYPE_NOUL = "noul"
    }
}

/**
 * Response payload from Google Gemini Decisions API (`POST /v1beta/decisions`).
 */
@Serializable
data class GeminiDecisionResponse(
    val model: String? = null,
    val answers: Map<String, GeminiDecisionAnswer> = emptyMap(),
    val error: GeminiApiErrorDetails? = null,
)

/**
 * Answer payload for an individual `noul` question in Gemini Decisions API response.
 *
 * Note: `noul` is the calibrated yes probability (`0.01..0.99`), while `confidence` equals
 * `max(noul, 1 - noul)` and must NOT be compared against the participation threshold.
 */
@Serializable
data class GeminiDecisionAnswer(
    val type: String? = null,
    val noul: Double? = null,
    val confidence: Double? = null,
)

/**
 * Evaluated Participation Decision outcome for a dialogue state.
 *
 * @property shouldParticipate Whether Luna should participate in the conversation (`noul >= threshold`).
 * @property noul Calibrated `noul` yes probability (`0.01..0.99`), or null if evaluation failed.
 * @property confidence Confidence value reported by Gemini (`max(noul, 1 - noul)`), if present.
 * @property rawAnswer Complete raw answer object if received.
 */
data class ParticipationDecision(
    val shouldParticipate: Boolean,
    val noul: Double? = null,
    val confidence: Double? = null,
    val rawAnswer: GeminiDecisionAnswer? = null,
)
