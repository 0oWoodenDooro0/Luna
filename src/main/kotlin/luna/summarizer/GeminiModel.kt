package luna.summarizer

import kotlinx.serialization.json.JsonObject
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.ChatImageAttachment
import luna.core.ai.GeminiInteractionTool
import luna.core.ai.GroundingCitation

/**
 * Input content provided for AI summarization.
 *
 * @property content The main textual content or message body.
 * @property title Optional title of the page or post.
 * @property comments Optional list of user comments or discussion items.
 * @property url Optional source URL.
 * @property webUrls Ordered, deduplicated general public web URLs eligible for `url_context` (capped at 20).
 * @property originalUrls All distinct original URLs observed across the slice for citation exclusion.
 * @property videoUri Optional video URI for multimodal video understanding.
 * @property videoDurationSeconds Optional video duration in seconds for adaptive mode selection.
 * @property imageAttachments Optional list of chat image attachments for multimodal visual understanding.
 * @property tools Optional list of tool declarations provided for model function calling.
 * @property earlierContext Optional preceding conversational background context from autonomous lookback.
 */
data class SummaryInput(
    val content: String,
    val title: String? = null,
    val comments: List<String> = emptyList(),
    val url: String? = null,
    val webUrls: List<String> = emptyList(),
    val originalUrls: List<String> = emptyList(),
    val videoUri: String? = null,
    val videoDurationSeconds: Long? = null,
    val imageAttachments: List<ChatImageAttachment> = emptyList(),
    val tools: List<GeminiInteractionTool>? = null,
    val earlierContext: String? = null,
)

/**
 * Result returned by [GeminiSummarizer].
 */
sealed class SummaryResult {
    /**
     * Successful summary output.
     *
     * @property text The generated structured summary.
     * @property citations Optional search grounding citations referenced during generation.
     */
    data class Success(
        val text: String,
        val citations: List<GroundingCitation> = emptyList(),
    ) : SummaryResult()

    /**
     * Tool call requested by Gemini during summarization.
     *
     * @property name Function name requested by the model (e.g. `fetch_earlier_messages`).
     * @property args Parsed argument key-value map.
     * @property rawArgs Raw JsonObject arguments.
     * @property continuation Optional continuation function to resume summarization with tool response.
     */
    data class ToolCall(
        val name: String,
        val args: Map<String, String> = emptyMap(),
        val rawArgs: JsonObject = JsonObject(emptyMap()),
        val continuation: (suspend (String) -> SummaryResult)? = null,
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
            val failureCategory: AiTraceFailureCategory? = null,
        ) : Error(message, cause)
    }
}
