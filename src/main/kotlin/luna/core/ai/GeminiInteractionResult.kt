package luna.core.ai

/**
 * Execution and AI-trace metadata supplied alongside a [GeminiInteractionRequest].
 */
data class InteractionTraceContext(
    val webUrlCount: Int = 0,
    val inputUrls: List<String> = emptyList(),
    val hasImages: Boolean? = null,
    val imageCount: Int? = null,
    val hasVideo: Boolean? = null,
    val videoDurationSeconds: Long? = null,
    val transcriptLength: Int? = null,
    val stage: AiAttemptStage? = null,
    val variant: String? = null,
    val pipeline: AiTracePipeline = AiTracePipeline.SUMMARY,
    val trigger: AiTraceTrigger = AiTraceTrigger.REACTION,
    val stripOutputEmojis: Boolean = true,
    val allowedCustomEmojiIds: Set<String> = emptySet(),
)

/**
 * Rich result returned by [GeminiInteractionClient.interact], [GeminiInteractionClient.continueInteraction],
 * and [GeminiInteractionClient.interactWithTools] for synchronous and background `/v1beta/interactions` executions.
 */
sealed class InteractionExecutionResult {
    data class Success(
        val interactionId: String?,
        val text: String,
        val citations: List<GroundingCitation> = emptyList(),
        val rawCitations: List<GroundingCitation> = emptyList(),
        val urlContextResults: List<UrlContextResultEntry> = emptyList(),
        val googleSearchQueries: List<String> = emptyList(),
        val usageMetadata: GeminiUsageMetadata? = null,
    ) : InteractionExecutionResult()

    data class FunctionCall(
        val interactionId: String,
        val functionCalls: List<GeminiInteractionFunctionCall>,
        val urlContextResults: List<UrlContextResultEntry> = emptyList(),
        val googleSearchQueries: List<String> = emptyList(),
        val usageMetadata: GeminiUsageMetadata? = null,
    ) : InteractionExecutionResult()

    sealed class Error(
        open val message: String,
        open val cause: Throwable? = null,
        open val failureCategory: AiTraceFailureCategory,
    ) : InteractionExecutionResult() {
        data class RateLimitExceeded(
            override val message: String = "Gemini API rate limit exceeded (HTTP 429)",
            val retryAfterSeconds: Long? = null,
        ) : Error(message, failureCategory = AiTraceFailureCategory.RATE_LIMIT)

        data class Timeout(
            override val message: String = "Gemini API request timed out",
            override val cause: Throwable? = null,
        ) : Error(message, cause, failureCategory = AiTraceFailureCategory.TIMEOUT)

        data class ApiError(
            val statusCode: Int,
            override val message: String,
        ) : Error(message, failureCategory = AiTraceFailureCategory.HTTP_ERROR)

        data class SafetyBlock(
            override val message: String,
            val finishReason: String? = null,
        ) : Error(message, failureCategory = AiTraceFailureCategory.SAFETY_BLOCK)

        data class EmptyOutput(
            override val message: String = "Gemini interaction output is empty or sanitized to blank",
        ) : Error(message, failureCategory = AiTraceFailureCategory.EMPTY_OUTPUT)

        data class Generic(
            override val message: String,
            override val cause: Throwable? = null,
            override val failureCategory: AiTraceFailureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR,
        ) : Error(message, cause, failureCategory)
    }
}
