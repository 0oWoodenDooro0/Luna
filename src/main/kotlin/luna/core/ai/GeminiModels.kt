package luna.core.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Supported video processing modes for Gemini multimodal video understanding.
 */
enum class VideoProcessingMode(
    val value: String,
) {
    STATIC("static"),
    AGENTIC("agentic"),
}

/**
 * Image attachment from a chat message provided for multimodal AI understanding.
 *
 * @property url Signed CDN HTTPS URL of the image attachment.
 * @property mimeType MIME type of the image (e.g. `image/jpeg`, `image/png`, `image/webp`, `image/gif`).
 * @property filename Optional original filename of the image attachment.
 */
@Serializable
data class ChatImageAttachment(
    val url: String,
    val mimeType: String,
    val filename: String? = null,
)

/**
 * Standard tool declarations for Gemini Function Calling and Search Grounding.
 */
object GeminiTools {
    const val FETCH_EARLIER_MESSAGES = "fetch_earlier_messages"

    val FETCH_EARLIER_MESSAGES_DECLARATION =
        GeminiFunctionDeclaration(
            name = FETCH_EARLIER_MESSAGES,
            description =
                "Fetches earlier chat messages from the channel history when more conversational " +
                    "context or background information is needed.",
            parameters =
                GeminiSchema(
                    type = "OBJECT",
                    description = "Parameters for fetch_earlier_messages tool",
                    properties =
                        mapOf(
                            "reason" to
                                GeminiSchema(
                                    type = "STRING",
                                    description = "Reason explaining why earlier context is requested.",
                                ),
                        ),
                    required = listOf("reason"),
                ),
        )

    val FETCH_EARLIER_MESSAGES_TOOL =
        GeminiTool(
            functionDeclarations = listOf(FETCH_EARLIER_MESSAGES_DECLARATION),
        )

    val GOOGLE_SEARCH_TOOL =
        GeminiTool(
            googleSearch = GeminiGoogleSearch(),
        )

    val GOOGLE_SEARCH = GOOGLE_SEARCH_TOOL
}

/**
 * Canonical Gemini finish reasons used across pipelines to classify safety blocks.
 */
object GeminiFinishReasons {
    val SAFETY_BLOCK_REASONS: Set<String> =
        setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")

    fun isSafetyBlock(finishReason: String?): Boolean = finishReason?.uppercase() in SAFETY_BLOCK_REASONS
}

@Serializable
data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null,
    val tools: List<GeminiTool>? = null,
    val toolConfig: GeminiToolConfig? = null,
)

@Serializable
class GeminiGoogleSearch

@Serializable
data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>? = null,
    @SerialName("function_declarations")
    val functionDeclarationsSnake: List<GeminiFunctionDeclaration>? = null,
    val googleSearch: GeminiGoogleSearch? = null,
    @SerialName("google_search")
    val googleSearchSnake: GeminiGoogleSearch? = null,
) {
    val actualFunctionDeclarations: List<GeminiFunctionDeclaration>
        get() = functionDeclarations ?: functionDeclarationsSnake ?: emptyList()

    val actualGoogleSearch: GeminiGoogleSearch?
        get() = googleSearch ?: googleSearchSnake
}

@Serializable
data class GeminiFunctionDeclaration(
    val name: String,
    val description: String? = null,
    val parameters: GeminiSchema? = null,
)

@Serializable
data class GeminiSchema(
    val type: String,
    val description: String? = null,
    val properties: Map<String, GeminiSchema>? = null,
    val required: List<String>? = null,
)

@Serializable
data class GeminiToolConfig(
    val functionCallingConfig: GeminiFunctionCallingConfig? = null,
)

@Serializable
data class GeminiFunctionCallingConfig(
    val mode: String? = null,
    val allowedFunctionNames: List<String>? = null,
)

@Serializable
data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject? = null,
) {
    val effectiveArgs: JsonObject
        get() = args ?: JsonObject(emptyMap())

    fun argsAsStrings(): Map<String, String> =
        effectiveArgs.mapValues { (_, element) ->
            if (element is JsonPrimitive) {
                element.content
            } else {
                element.toString()
            }
        }
}

@Serializable
data class GeminiFunctionResponse(
    val name: String,
    val response: JsonObject,
) {
    companion object {
        fun of(
            name: String,
            content: String,
        ): GeminiFunctionResponse =
            GeminiFunctionResponse(
                name = name,
                response =
                    buildJsonObject {
                        put("name", name)
                        put("content", content)
                    },
            )
    }
}

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String? = null,
)

@Serializable
data class GeminiFileData(
    @SerialName("file_uri")
    val fileUri: String,
    @SerialName("mime_type")
    val mimeType: String? = null,
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    @SerialName("file_data")
    val fileData: GeminiFileData? = null,
    val functionCall: GeminiFunctionCall? = null,
    @SerialName("function_call")
    val functionCallSnake: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null,
    @SerialName("function_response")
    val functionResponseSnake: GeminiFunctionResponse? = null,
) {
    val actualFunctionCall: GeminiFunctionCall?
        get() = functionCall ?: functionCallSnake

    val actualFunctionResponse: GeminiFunctionResponse?
        get() = functionResponse ?: functionResponseSnake
}

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiUsageMetadata(
    @JsonNames("prompt_token_count")
    val promptTokenCount: Int? = null,
    @JsonNames("candidates_token_count")
    val candidatesTokenCount: Int? = null,
    @JsonNames("total_token_count")
    val totalTokenCount: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate>? = null,
    @JsonNames("prompt_feedback")
    val promptFeedback: GeminiPromptFeedback? = null,
    val error: GeminiApiErrorDetails? = null,
    @JsonNames("usage_metadata")
    val usageMetadata: GeminiUsageMetadata? = null,
)

@Serializable
data class GroundingCitation(
    val title: String,
    val url: String,
)

@Serializable
data class GeminiGroundingMetadata(
    val webSearchQueries: List<String>? = null,
    @SerialName("web_search_queries")
    val webSearchQueriesSnake: List<String>? = null,
    val groundingChunks: List<GeminiGroundingChunk>? = null,
    @SerialName("grounding_chunks")
    val groundingChunksSnake: List<GeminiGroundingChunk>? = null,
) {
    val actualWebSearchQueries: List<String>
        get() = webSearchQueries ?: webSearchQueriesSnake ?: emptyList()

    val actualGroundingChunks: List<GeminiGroundingChunk>
        get() = groundingChunks ?: groundingChunksSnake ?: emptyList()
}

@Serializable
data class GeminiGroundingChunk(
    val web: GeminiGroundingWebSource? = null,
)

@Serializable
data class GeminiGroundingWebSource(
    val uri: String? = null,
    val title: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    @JsonNames("finish_reason")
    val finishReason: String? = null,
    val groundingMetadata: GeminiGroundingMetadata? = null,
    @SerialName("grounding_metadata")
    val groundingMetadataSnake: GeminiGroundingMetadata? = null,
) {
    val actualGroundingMetadata: GeminiGroundingMetadata?
        get() = groundingMetadata ?: groundingMetadataSnake
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiPromptFeedback(
    @JsonNames("block_reason")
    val blockReason: String? = null,
)

@Serializable
data class GeminiApiErrorDetails(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

@Serializable
data class GeminiInteractionInput(
    val type: String,
    val text: String? = null,
    val uri: String? = null,
    @SerialName("mime_type")
    val mimeType: String? = null,
    val processing: String? = null,
)

@Serializable
data class GeminiInteractionRequest(
    val model: String,
    val input: List<GeminiInteractionInput>,
    val background: Boolean = true,
)

@Serializable
data class GeminiInteractionStepContent(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class GeminiInteractionStep(
    val type: String? = null,
    val content: List<GeminiInteractionStepContent>? = null,
)

@Serializable
data class GeminiInteractionResponse(
    val id: String? = null,
    val status: String? = null,
    @SerialName("output_text")
    val outputText: String? = null,
    val steps: List<GeminiInteractionStep>? = null,
    val error: GeminiApiErrorDetails? = null,
) {
    fun extractOutputText(): String {
        val stepText =
            steps
                ?.filter { it.type == "model_output" }
                ?.flatMap { it.content ?: emptyList() }
                ?.mapNotNull { it.text }
                ?.joinToString("\n")
                ?.trim()

        if (!stepText.isNullOrBlank()) {
            return stepText
        }
        return outputText?.trim() ?: ""
    }
}
