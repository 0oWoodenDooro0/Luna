package luna.chatter

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request payload for Laya System 1 decision API (`POST /v1/systemone`).
 */
@Serializable
data class LayaSystemOneRequest(
    val state: String,
    val questions: Map<String, LayaQuestion>,
    val model: String? = null,
)

/**
 * Question descriptor in the Laya System 1 schema.
 *
 * @property type Question type (e.g. `noul`, `choice`, `score`).
 * @property instructions Direct prompt / instructions for the decision.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LayaQuestion(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = TYPE_NOUL,
    val instructions: String,
) {
    companion object {
        const val TYPE_NOUL = "noul"
    }
}

/**
 * Response payload from Laya System 1 API (`POST /v1/systemone`).
 */
@Serializable
data class LayaSystemOneResponse(
    val model: String? = null,
    val answers: Map<String, LayaAnswer> = emptyMap(),
    val usage: LayaUsage? = null,
    val error: LayaErrorResponse? = null,
)

/**
 * Answer payload for an individual question in Laya System 1 response.
 */
@Serializable
data class LayaAnswer(
    val noul: Boolean? = null,
    val probability: Double? = null,
    val confidence: Double? = null,
    val choice: String? = null,
    val score: Double? = null,
    val level: String? = null,
) {
    /**
     * Resolves the decision probability, falling back to confidence if probability is null.
     */
    val effectiveProbability: Double
        get() = probability ?: confidence ?: 0.0
}

/**
 * Token usage metadata returned by Laya API.
 */
@Serializable
data class LayaUsage(
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
)

/**
 * Error detail object returned on API failure.
 */
@Serializable
data class LayaErrorResponse(
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
)

/**
 * Evaluated decision outcome for a dialogue state.
 *
 * @property shouldParticipate Whether Luna should participate in the conversation.
 * @property noul Raw boolean result from Laya (true/false) or null if evaluation failed.
 * @property probability Probability value reported by Laya or resolved from confidence.
 * @property confidence Confidence value reported by Laya.
 * @property rawAnswer Complete raw answer object if received.
 */
data class LayaDecision(
    val shouldParticipate: Boolean,
    val noul: Boolean? = null,
    val probability: Double = 0.0,
    val confidence: Double = 0.0,
    val rawAnswer: LayaAnswer? = null,
)
