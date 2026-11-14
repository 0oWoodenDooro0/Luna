package luna.core.ai

import io.ktor.client.HttpClient
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
    private val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
) {
    private val logger = LoggerFactory.getLogger(GeminiInteractionClient::class.java)

    private class ExecutionTraceState {
        var pollingAttempts: Int = 0
        var httpStatus: Int? = null
        var responseRaw: String? = null
    }

    suspend fun runAgenticVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        videoUri: String,
        promptText: String,
        videoDurationSeconds: Long? = null,
    ): GeminiInteractionResult =
        traceRecorder.withActiveOrStandaloneSession(
            pipeline = AiTracePipeline.SUMMARY,
            trigger = AiTraceTrigger.REACTION,
            discord =
                AiTraceDiscordContext(
                    channelId = "standalone",
                    messageId = "standalone",
                ),
            secret = apiKey,
            onStandaloneException = { session, e ->
                val localRedactor = AiTraceRedactor(listOfNotNull(apiKey.takeIf { it.isNotBlank() }))
                session.fail(
                    failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR,
                    errorSummary = localRedactor.redactString(e.message) ?: e::class.simpleName ?: "Unexpected error",
                )
            },
        ) { session, isStandalone ->
            val result =
                executeAgenticVideo(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    videoUri = videoUri,
                    promptText = promptText,
                    videoDurationSeconds = videoDurationSeconds,
                    session = session,
                )
            if (isStandalone && session != null) {
                when (result) {
                    is GeminiInteractionResult.Success -> {
                        session.complete(finalOutput = result.text)
                    }

                    is GeminiInteractionResult.Error -> {
                        val localRedactor = AiTraceRedactor(listOfNotNull(apiKey.takeIf { it.isNotBlank() }))
                        session.fail(
                            failureCategory = classifyFailure(result, apiKey = apiKey, videoUri = videoUri),
                            errorSummary = localRedactor.redactString(result.message),
                        )
                    }
                }
            }
            result
        }

    private suspend fun executeAgenticVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        videoUri: String,
        promptText: String,
        videoDurationSeconds: Long?,
        session: AiTraceSession?,
    ): GeminiInteractionResult {
        val startNano = System.nanoTime()
        val cleanBaseUrl = baseUrl.trim().trimEnd('/').removeSuffix("/v1beta")
        val cleanModel = model.trim().removePrefix("models/")
        val rawEndpoint =
            if (apiKey.isBlank()) {
                "$cleanBaseUrl/v1beta/interactions"
            } else {
                "$cleanBaseUrl/v1beta/interactions?key=$apiKey"
            }
        val sanitizedEndpoint = AiTraceRedactor.sanitizeEndpoint(rawEndpoint)
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
        val attemptRequest =
            if (session?.isEnabled == true) {
                AiAttemptRequest(
                    payload =
                        runCatching {
                            AiTraceJson.encodeToJsonElement(GeminiInteractionRequest.serializer(), requestPayload)
                        }.getOrNull(),
                    hasImages = false,
                    imageCount = 0,
                    hasVideo = videoUri.isNotBlank(),
                    videoDurationSeconds = videoDurationSeconds,
                    transcriptLength = promptText.length,
                )
            } else {
                null
            }

        val traceState = ExecutionTraceState()
        val localRedactor = AiTraceRedactor(listOfNotNull(apiKey.takeIf { it.isNotBlank() }))

        val result: GeminiInteractionResult =
            if (apiKey.isBlank()) {
                logger.warn("Gemini agentic interaction aborted: API key is blank")
                GeminiInteractionResult.Error.Generic("Gemini API key is not configured")
            } else if (videoUri.isBlank()) {
                logger.warn("Gemini agentic interaction aborted: video URI is blank")
                GeminiInteractionResult.Error.Generic("Video URI cannot be blank")
            } else {
                try {
                    val response: HttpResponse =
                        httpClient.post(rawEndpoint) {
                            contentType(ContentType.Application.Json)
                            setBody(requestPayload)
                        }
                    traceState.httpStatus = response.status.value
                    val rawBody = runCatching { response.bodyAsText() }.getOrNull()
                    traceState.responseRaw = rawBody

                    if (response.status == HttpStatusCode.TooManyRequests) {
                        val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                        logger.warn("Gemini interactions submission rate limited (HTTP 429, retryAfter={})", retryAfter)
                        GeminiInteractionResult.Error.RateLimitExceeded(
                            message = "Gemini API rate limit exceeded (HTTP 429)",
                            retryAfterSeconds = retryAfter,
                        )
                    } else if (!response.status.isSuccess()) {
                        val errorBody = rawBody.orEmpty()
                        logger.warn(
                            "Gemini interactions submission failed with HTTP {}: {}",
                            response.status.value,
                            errorBody,
                        )
                        GeminiInteractionResult.Error.ApiError(
                            statusCode = response.status.value,
                            message = "Gemini API error (${response.status.value}): $errorBody",
                        )
                    } else {
                        val interaction =
                            jsonParser.decodeFromString<GeminiInteractionResponse>(
                                rawBody?.takeIf { it.isNotBlank() } ?: throw SerializationException("Empty submission response body"),
                            )
                        val interactionId = interaction.id
                        if (interactionId.isNullOrBlank()) {
                            logger.warn("Gemini interactions submission returned no interaction ID")
                            GeminiInteractionResult.Error.Generic("No interaction ID returned from Gemini")
                        } else {
                            pollInteraction(cleanBaseUrl, apiKey, interactionId, traceState)
                        }
                    }
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

        if (session != null && session.isEnabled) {
            val latencyMs = elapsedMillisFromNanos(startNano)
            val parsedOutcome =
                when (result) {
                    is GeminiInteractionResult.Success -> AiAttemptOutcome.SUCCESS
                    is GeminiInteractionResult.Error -> AiAttemptOutcome.ERROR
                }
            val rawError = (result as? GeminiInteractionResult.Error)?.message
            session.recordAttempt(
                stage = AiAttemptStage.AGENTIC_VIDEO,
                model = cleanModel,
                endpoint = sanitizedEndpoint,
                latencyMs = latencyMs,
                httpStatus = traceState.httpStatus,
                request = attemptRequest,
                responseRaw = traceState.responseRaw,
                pollingAttempts = traceState.pollingAttempts,
                parsedOutcome = parsedOutcome,
                errorMessage = localRedactor.redactString(rawError),
            )
        }

        return result
    }

    private suspend fun pollInteraction(
        cleanBaseUrl: String,
        apiKey: String,
        id: String,
        traceState: ExecutionTraceState,
    ): GeminiInteractionResult {
        val endpoint = "$cleanBaseUrl/v1beta/interactions/$id?key=$apiKey"
        var attempts = 0

        while (attempts < maxPollingAttempts) {
            attempts++
            traceState.pollingAttempts = attempts
            traceState.httpStatus = null
            traceState.responseRaw = null

            val response = httpClient.get(endpoint)
            traceState.httpStatus = response.status.value
            val rawBody = runCatching { response.bodyAsText() }.getOrNull()
            traceState.responseRaw = rawBody

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

            val statusResponse =
                jsonParser.decodeFromString<GeminiInteractionResponse>(
                    rawBody?.takeIf { it.isNotBlank() } ?: throw SerializationException("Empty polling response body"),
                )
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

    private fun classifyFailure(
        result: GeminiInteractionResult.Error,
        apiKey: String,
        videoUri: String,
    ): AiTraceFailureCategory =
        when (result) {
            is GeminiInteractionResult.Error.RateLimitExceeded -> {
                AiTraceFailureCategory.RATE_LIMIT
            }

            is GeminiInteractionResult.Error.Timeout -> {
                AiTraceFailureCategory.TIMEOUT
            }

            is GeminiInteractionResult.Error.ApiError -> {
                AiTraceFailureCategory.HTTP_ERROR
            }

            is GeminiInteractionResult.Error.EmptyOutput -> {
                AiTraceFailureCategory.EMPTY_OUTPUT
            }

            is GeminiInteractionResult.Error.Generic -> {
                when {
                    apiKey.isBlank() -> AiTraceFailureCategory.NOT_CONFIGURED
                    videoUri.isBlank() -> AiTraceFailureCategory.CONTEXT_ERROR
                    result.cause is SerializationException -> AiTraceFailureCategory.PARSE_ERROR
                    else -> AiTraceFailureCategory.UNEXPECTED_ERROR
                }
            }
        }

    companion object {
        const val DEFAULT_POLLING_DELAY_MS = 2000L
        const val DEFAULT_MAX_POLLING_ATTEMPTS = 30

        private val jsonParser =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
                explicitNulls = false
            }
    }
}
