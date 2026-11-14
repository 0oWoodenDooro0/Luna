package luna.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException

/**
 * Result returned by [GeminiInteractionClient] for background Agentic Video Understanding executions.
 */
sealed class GeminiInteractionResult {
    data class Success(
        val text: String,
    ) : GeminiInteractionResult()

    sealed class Error(
        open val message: String,
        open val cause: Throwable? = null,
    ) : GeminiInteractionResult() {
        data class RateLimitExceeded(
            override val message: String = "Gemini API rate limit exceeded (HTTP 429)",
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

        data class EmptyOutput(
            override val message: String = "Gemini interaction output is empty or sanitized to blank",
        ) : Error(message)

        data class Generic(
            override val message: String,
            override val cause: Throwable? = null,
        ) : Error(message, cause)
    }
}

/**
 * Shared client for submitting and polling Gemini `/v1beta/interactions` background tasks
 * (Agentic Video Understanding).
 */
class GeminiInteractionClient(
    private val httpClient: HttpClient,
    private val pollingDelayMs: Long = DEFAULT_POLLING_DELAY_MS,
    private val maxPollingAttempts: Int = DEFAULT_MAX_POLLING_ATTEMPTS,
) {
    private val logger = LoggerFactory.getLogger(GeminiInteractionClient::class.java)

    suspend fun runAgenticVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        videoUri: String,
        promptText: String,
    ): GeminiInteractionResult {
        if (apiKey.isBlank()) {
            logger.warn("Gemini agentic interaction aborted: API key is blank")
            return GeminiInteractionResult.Error.Generic("Gemini API key is not configured")
        }
        if (videoUri.isBlank()) {
            logger.warn("Gemini agentic interaction aborted: video URI is blank")
            return GeminiInteractionResult.Error.Generic("Video URI cannot be blank")
        }

        val cleanBaseUrl = baseUrl.trim().trimEnd('/').removeSuffix("/v1beta")
        val cleanModel = model.trim().removePrefix("models/")
        val endpoint = "$cleanBaseUrl/v1beta/interactions?key=$apiKey"
        val requestPayload =
            GeminiInteractionRequest(
                model = cleanModel,
                input =
                    listOf(
                        GeminiInteractionInput(
                            type = "video",
                            uri = videoUri,
                            mimeType = "video/mp4",
                            processing = VideoProcessingMode.AGENTIC.value,
                        ),
                        GeminiInteractionInput(
                            type = "text",
                            text = promptText,
                        ),
                    ),
                background = true,
            )

        return try {
            val response: HttpResponse =
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestPayload)
                }

            if (response.status == HttpStatusCode.TooManyRequests) {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                logger.warn("Gemini interactions submission rate limited (HTTP 429, retryAfter={})", retryAfter)
                return GeminiInteractionResult.Error.RateLimitExceeded(
                    message = "Gemini API rate limit exceeded (HTTP 429)",
                    retryAfterSeconds = retryAfter,
                )
            }
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.warn("Gemini interactions submission failed with HTTP {}: {}", response.status.value, errorBody)
                return GeminiInteractionResult.Error.ApiError(
                    statusCode = response.status.value,
                    message = "Gemini API error (${response.status.value}): $errorBody",
                )
            }

            val interaction = response.body<GeminiInteractionResponse>()
            val interactionId = interaction.id
            if (interactionId.isNullOrBlank()) {
                logger.warn("Gemini interactions submission returned no interaction ID")
                return GeminiInteractionResult.Error.Generic("No interaction ID returned from Gemini")
            }

            pollInteraction(cleanBaseUrl, apiKey, interactionId)
        } catch (e: HttpRequestTimeoutException) {
            logger.warn("Gemini agentic interaction request timed out: {}", e.message)
            GeminiInteractionResult.Error.Timeout("Gemini API request timed out: ${e.message}", cause = e)
        } catch (e: SocketTimeoutException) {
            logger.warn("Gemini agentic interaction socket timed out: {}", e.message)
            GeminiInteractionResult.Error.Timeout("Gemini API socket timed out: ${e.message}", cause = e)
        } catch (e: TimeoutCancellationException) {
            logger.warn("Gemini agentic interaction coroutine timed out: {}", e.message)
            GeminiInteractionResult.Error.Timeout("Gemini API coroutine timed out: ${e.message}", cause = e)
        } catch (e: Exception) {
            logger.warn("Unexpected error invoking Gemini agentic interaction: {}", e.message, e)
            GeminiInteractionResult.Error.Generic("Unexpected error invoking Gemini API: ${e.message}", cause = e)
        }
    }

    private suspend fun pollInteraction(
        cleanBaseUrl: String,
        apiKey: String,
        id: String,
    ): GeminiInteractionResult {
        val endpoint = "$cleanBaseUrl/v1beta/interactions/$id?key=$apiKey"
        var attempts = 0

        while (attempts < maxPollingAttempts) {
            attempts++
            val response = httpClient.get(endpoint)
            if (response.status == HttpStatusCode.TooManyRequests) {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                logger.warn("Gemini interaction polling rate limited for {} (HTTP 429, retryAfter={})", id, retryAfter)
                return GeminiInteractionResult.Error.RateLimitExceeded(
                    message = "Gemini API rate limit exceeded (HTTP 429)",
                    retryAfterSeconds = retryAfter,
                )
            }
            if (!response.status.isSuccess()) {
                logger.warn("Gemini interaction polling failed for {} with HTTP {}", id, response.status.value)
                return GeminiInteractionResult.Error.ApiError(
                    statusCode = response.status.value,
                    message = "Error polling interaction: ${response.status.value}",
                )
            }

            val statusResponse = response.body<GeminiInteractionResponse>()
            val rawOutput = statusResponse.extractOutputText()
            if (statusResponse.status == "completed" || rawOutput.isNotBlank()) {
                val sanitized = EmojiSanitizer.stripEmojis(rawOutput).trim()
                if (sanitized.isBlank()) {
                    logger.warn("Gemini interaction {} completed with empty or emoji-only output", id)
                    return GeminiInteractionResult.Error.EmptyOutput(
                        "Gemini interaction output is empty or sanitized to blank",
                    )
                }
                return GeminiInteractionResult.Success(text = sanitized)
            }
            if (statusResponse.status == "failed") {
                val errorMsg = statusResponse.error?.message ?: "Agentic video processing failed"
                logger.warn("Gemini interaction {} failed: {}", id, errorMsg)
                return GeminiInteractionResult.Error.Generic(errorMsg)
            }

            delay(pollingDelayMs)
        }

        logger.warn("Gemini interaction {} timed out after {} polling attempts", id, maxPollingAttempts)
        return GeminiInteractionResult.Error.Timeout(
            "Agentic video interaction timed out after $maxPollingAttempts attempts",
        )
    }

    companion object {
        const val DEFAULT_POLLING_DELAY_MS = 2000L
        const val DEFAULT_MAX_POLLING_ATTEMPTS = 30
    }
}
