package luna.chatter

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

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
@Serializable(with = LayaAnswerSerializer::class)
data class LayaAnswer(
    val noul: Boolean? = null,
    val probability: Double? = null,
    val confidence: Double? = null,
    val choice: String? = null,
    val score: Double? = null,
    val level: String? = null,
    val type: String? = null,
    @SerialName("answer_choice")
    val answerChoice: String? = null,
) {
    /**
     * Resolves the decision probability, falling back to confidence if probability is null.
     */
    val effectiveProbability: Double
        get() = probability ?: confidence ?: 0.0
}

/**
 * Custom serializer for [LayaAnswer] handling both boolean (`true`/`false`) and numeric
 * probability values (`0.0`..`1.0`) returned for `noul` schema questions by Laya Studio.
 */
object LayaAnswerSerializer : KSerializer<LayaAnswer> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("luna.chatter.LayaAnswer") {
            element("noul", Boolean.serializer().descriptor, isOptional = true)
            element("probability", Double.serializer().descriptor, isOptional = true)
            element("confidence", Double.serializer().descriptor, isOptional = true)
            element("choice", String.serializer().descriptor, isOptional = true)
            element("score", Double.serializer().descriptor, isOptional = true)
            element("level", String.serializer().descriptor, isOptional = true)
            element("type", String.serializer().descriptor, isOptional = true)
            element("answer_choice", String.serializer().descriptor, isOptional = true)
        }

    override fun serialize(
        encoder: Encoder,
        value: LayaAnswer,
    ) {
        val jsonOutput = encoder as? kotlinx.serialization.json.JsonEncoder
        if (jsonOutput != null) {
            val map =
                buildJsonObject {
                    value.noul?.let { put("noul", it) }
                    value.probability?.let { put("probability", it) }
                    value.confidence?.let { put("confidence", it) }
                    value.choice?.let { put("choice", it) }
                    value.score?.let { put("score", it) }
                    value.level?.let { put("level", it) }
                    value.type?.let { put("type", it) }
                    value.answerChoice?.let { put("answer_choice", it) }
                }
            jsonOutput.encodeJsonElement(map)
        } else {
            val composite = encoder.beginStructure(descriptor)
            value.noul?.let { composite.encodeBooleanElement(descriptor, 0, it) }
            value.probability?.let { composite.encodeDoubleElement(descriptor, 1, it) }
            value.confidence?.let { composite.encodeDoubleElement(descriptor, 2, it) }
            value.choice?.let { composite.encodeStringElement(descriptor, 3, it) }
            value.score?.let { composite.encodeDoubleElement(descriptor, 4, it) }
            value.level?.let { composite.encodeStringElement(descriptor, 5, it) }
            value.type?.let { composite.encodeStringElement(descriptor, 6, it) }
            value.answerChoice?.let { composite.encodeStringElement(descriptor, 7, it) }
            composite.endStructure(descriptor)
        }
    }

    override fun deserialize(decoder: Decoder): LayaAnswer {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw IllegalStateException("LayaAnswer can only be deserialized with JsonDecoder")
        val jsonElement = jsonDecoder.decodeJsonElement()
        if (jsonElement !is JsonObject) {
            return LayaAnswer()
        }

        val noulElement = jsonElement["noul"]
        var parsedNoul: Boolean? = null
        var noulScore: Double? = null

        if (noulElement is JsonPrimitive) {
            val boolVal =
                noulElement.booleanOrNull
                    ?: when (noulElement.content.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }

            if (boolVal != null) {
                parsedNoul = boolVal
            } else {
                val doubleVal =
                    noulElement.doubleOrNull
                        ?: noulElement.content.toDoubleOrNull()
                if (doubleVal != null) {
                    noulScore = doubleVal
                    parsedNoul = doubleVal >= 0.5
                }
            }
        }

        val probElement = jsonElement["probability"] as? JsonPrimitive
        val parsedProb =
            probElement?.doubleOrNull
                ?: probElement?.content?.toDoubleOrNull()
                ?: noulScore

        val confElement = jsonElement["confidence"] as? JsonPrimitive
        val parsedConf =
            confElement?.doubleOrNull
                ?: confElement?.content?.toDoubleOrNull()

        val choiceElement = jsonElement["choice"] as? JsonPrimitive
        val parsedChoice = choiceElement?.contentOrNull

        val scoreElement = jsonElement["score"] as? JsonPrimitive
        val parsedScore =
            scoreElement?.doubleOrNull
                ?: scoreElement?.content?.toDoubleOrNull()

        val levelElement = jsonElement["level"] as? JsonPrimitive
        val parsedLevel = levelElement?.contentOrNull

        val typeElement = jsonElement["type"] as? JsonPrimitive
        val parsedType = typeElement?.contentOrNull

        val answerChoiceElement = jsonElement["answer_choice"] as? JsonPrimitive
        val parsedAnswerChoice = answerChoiceElement?.contentOrNull

        return LayaAnswer(
            noul = parsedNoul,
            probability = parsedProb,
            confidence = parsedConf,
            choice = parsedChoice,
            score = parsedScore,
            level = parsedLevel,
            type = parsedType,
            answerChoice = parsedAnswerChoice,
        )
    }
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
