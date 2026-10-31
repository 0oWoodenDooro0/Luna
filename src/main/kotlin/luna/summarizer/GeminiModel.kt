package luna.summarizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * Input content provided for AI summarization.
 *
 * @property content The main textual content or message body.
 * @property title Optional title of the page or post.
 * @property comments Optional list of user comments or discussion items.
 * @property url Optional source URL.
 * @property videoUri Optional video URI for multimodal video understanding.
 * @property videoDurationSeconds Optional video duration in seconds for adaptive mode selection.
 */
data class SummaryInput(
    val content: String,
    val title: String? = null,
    val comments: List<String> = emptyList(),
    val url: String? = null,
    val videoUri: String? = null,
    val videoDurationSeconds: Long? = null,
)

/**
 * Result returned by [GeminiSummarizer].
 */
sealed class SummaryResult {
    /**
     * Successful summary output.
     *
     * @property text The generated structured summary.
     */
    data class Success(
        val text: String,
    ) : SummaryResult()

    /**
     * Error result from summarization.
     *
     * @property message Human-readable error message.
     * @property cause Optional underlying throwable.
     */
    sealed class Error(
        open val message: String,
        open val cause: Throwable? = null,
    ) : SummaryResult() {
        data class MissingApiKey(
            override val message: String = "Gemini API key is not configured",
        ) : Error(message)

        data class RateLimitExceeded(
            override val message: String = "Gemini API rate limit exceeded",
            val retryAfterSeconds: Long? = null,
        ) : Error(message)

        data class Timeout(
            override val message: String = "Gemini API request timed out",
            override val cause: Throwable? = null,
        ) : Error(message, cause)

        data class ApiError(
            val statusCode: Int,
            override val message: String,
        ) : Error(message)

        data class Generic(
            override val message: String,
            override val cause: Throwable? = null,
        ) : Error(message, cause)
    }
}

@Serializable
data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null,
)

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
    val processing: String? = null,
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    @SerialName("file_data")
    val fileData: GeminiFileData? = null,
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
)

@Serializable
data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate>? = null,
    val promptFeedback: GeminiPromptFeedback? = null,
    val error: GeminiApiErrorDetails? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

@Serializable
data class GeminiPromptFeedback(
    val blockReason: String? = null,
)

@Serializable
data class GeminiApiErrorDetails(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)
