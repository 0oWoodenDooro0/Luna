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
 * Result returned by [GeminiInteractionClient.runAgenticVideo] for background Agentic Video Understanding executions.
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
 * Rich result returned by [GeminiInteractionClient.interact] and [GeminiInteractionClient.continueInteraction]
 * for synchronous and background `/v1beta/interactions` executions.
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

/**
 * Unified client for Google Gemini `/v1beta/interactions` API (`steps` schema), supporting
 * synchronous execution (`background = false`), background polling (`background = true`),
 * multimodal inputs (`video`, `image`, `text`), built-in tools (`url_context`, `google_search`,
 * `function`), and stateful `previous_interaction_id` + `function_result` continuations.
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
        var finishReason: String? = null
        var usageMetadata: GeminiUsageMetadata? = null
        var urlContextResults: List<UrlContextResultEntry> = emptyList()
        var googleSearchQueries: List<String> = emptyList()
        var rawCitations: List<GroundingCitation> = emptyList()
        var filteredCitations: List<GroundingCitation> = emptyList()
    }

    suspend fun runAgenticVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        videoUri: String,
        promptText: String,
        videoDurationSeconds: Long? = null,
    ): GeminiInteractionResult {
        if (videoUri.isBlank() && apiKey.isNotBlank()) {
            // Preserve legacy pre-HTTP blank videoUri check even when promptText is non-blank
            return when (
                val res =
                    interactInternal(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        inputs = emptyList(),
                        validationError = "Video URI cannot be blank",
                        hasImages = false,
                        imageCount = 0,
                        hasVideo = false,
                        videoDurationSeconds = videoDurationSeconds,
                        webUrlCount = 0,
                        inputUrls = emptyList(),
                        systemInstruction = null,
                        tools = emptyList(),
                        maxOutputTokens = null,
                        background = true,
                        previousInteractionId = null,
                        stage = AiAttemptStage.AGENTIC_VIDEO,
                        variant = null,
                        transcriptLength = promptText.length,
                        pipeline = AiTracePipeline.SUMMARY,
                        trigger = AiTraceTrigger.REACTION,
                    )
            ) {
                is InteractionExecutionResult.Success -> GeminiInteractionResult.Success(res.text)
                is InteractionExecutionResult.Error -> res.toLegacyError()
                is InteractionExecutionResult.FunctionCall -> GeminiInteractionResult.Error.Generic("Unexpected function_call")
            }
        }

        val outcome =
            interact(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                promptText = promptText,
                videoUri = videoUri,
                videoMimeType = "video/mp4",
                videoProcessingMode = VideoProcessingMode.AGENTIC,
                videoDurationSeconds = videoDurationSeconds,
                background = true,
                stage = AiAttemptStage.AGENTIC_VIDEO,
            )
        return when (outcome) {
            is InteractionExecutionResult.Success -> GeminiInteractionResult.Success(outcome.text)
            is InteractionExecutionResult.Error -> outcome.toLegacyError()
            is InteractionExecutionResult.FunctionCall -> GeminiInteractionResult.Error.Generic("Unexpected function_call in agentic video")
        }
    }

    suspend fun interact(
        baseUrl: String,
        apiKey: String,
        model: String,
        promptText: String,
        systemInstruction: String? = null,
        imageAttachments: List<ChatImageAttachment> = emptyList(),
        videoUri: String? = null,
        videoMimeType: String? = null,
        videoProcessingMode: VideoProcessingMode = VideoProcessingMode.STATIC,
        videoDurationSeconds: Long? = null,
        webUrlCount: Int = 0,
        inputUrls: List<String> = emptyList(),
        tools: List<GeminiInteractionTool> = emptyList(),
        maxOutputTokens: Int? = null,
        background: Boolean = false,
        previousInteractionId: String? = null,
        stage: AiAttemptStage = if (background) AiAttemptStage.AGENTIC_VIDEO else AiAttemptStage.SUMMARY_GENERATE,
        variant: String? = null,
        transcriptLength: Int = promptText.length,
        pipeline: AiTracePipeline = AiTracePipeline.SUMMARY,
        trigger: AiTraceTrigger = AiTraceTrigger.REACTION,
    ): InteractionExecutionResult {
        val cleanVideoUri = videoUri?.trim()?.takeIf { it.isNotBlank() }
        val inputs =
            buildMultimodalInputs(
                promptText = promptText,
                imageAttachments = imageAttachments,
                videoUri = cleanVideoUri,
                videoMimeType = videoMimeType,
                videoProcessingMode = videoProcessingMode,
            )
        val validationError =
            if (inputs.isEmpty()) {
                "Interaction input cannot be blank"
            } else {
                null
            }
        return interactInternal(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            inputs = inputs,
            validationError = validationError,
            hasImages = imageAttachments.isNotEmpty(),
            imageCount = imageAttachments.size,
            hasVideo = cleanVideoUri != null,
            videoDurationSeconds = videoDurationSeconds,
            webUrlCount = webUrlCount,
            inputUrls = inputUrls,
            systemInstruction = systemInstruction,
            tools = tools,
            maxOutputTokens = maxOutputTokens,
            background = background,
            previousInteractionId = previousInteractionId,
            stage = stage,
            variant = variant,
            transcriptLength = transcriptLength,
            pipeline = pipeline,
            trigger = trigger,
        )
    }

    suspend fun continueInteraction(
        baseUrl: String,
        apiKey: String,
        model: String,
        previousInteractionId: String,
        functionResults: List<GeminiInteractionInput>,
        systemInstruction: String? = null,
        tools: List<GeminiInteractionTool> = emptyList(),
        maxOutputTokens: Int? = null,
        background: Boolean = false,
        inputUrls: List<String> = emptyList(),
        webUrlCount: Int = 0,
        imageCount: Int = 0,
        hasVideo: Boolean = false,
        videoDurationSeconds: Long? = null,
        transcriptLength: Int = 0,
        stage: AiAttemptStage = AiAttemptStage.SUMMARY_TOOL_CONTINUATION,
        variant: String? = null,
        pipeline: AiTracePipeline = AiTracePipeline.SUMMARY,
        trigger: AiTraceTrigger = AiTraceTrigger.REACTION,
    ): InteractionExecutionResult {
        val validationError =
            when {
                previousInteractionId.isBlank() -> "previous_interaction_id cannot be blank"
                functionResults.isEmpty() -> "functionResults cannot be empty"
                else -> null
            }
        return interactInternal(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            inputs = functionResults,
            validationError = validationError,
            hasImages = imageCount > 0,
            imageCount = imageCount,
            hasVideo = hasVideo,
            videoDurationSeconds = videoDurationSeconds,
            webUrlCount = webUrlCount,
            inputUrls = inputUrls,
            systemInstruction = systemInstruction,
            tools = tools,
            maxOutputTokens = maxOutputTokens,
            background = background,
            previousInteractionId = previousInteractionId,
            stage = stage,
            variant = variant,
            transcriptLength = transcriptLength,
            pipeline = pipeline,
            trigger = trigger,
        )
    }

    private suspend fun interactInternal(
        baseUrl: String,
        apiKey: String,
        model: String,
        inputs: List<GeminiInteractionInput>,
        validationError: String?,
        hasImages: Boolean,
        imageCount: Int,
        hasVideo: Boolean,
        videoDurationSeconds: Long?,
        webUrlCount: Int,
        inputUrls: List<String>,
        systemInstruction: String?,
        tools: List<GeminiInteractionTool>,
        maxOutputTokens: Int?,
        background: Boolean,
        previousInteractionId: String?,
        stage: AiAttemptStage,
        variant: String?,
        transcriptLength: Int,
        pipeline: AiTracePipeline,
        trigger: AiTraceTrigger,
    ): InteractionExecutionResult =
        traceRecorder.withActiveOrStandaloneSession(
            pipeline = pipeline,
            trigger = trigger,
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
                executeInteraction(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    inputs = inputs,
                    validationError = validationError,
                    hasImages = hasImages,
                    imageCount = imageCount,
                    hasVideo = hasVideo,
                    videoDurationSeconds = videoDurationSeconds,
                    webUrlCount = webUrlCount,
                    inputUrls = inputUrls,
                    systemInstruction = systemInstruction,
                    tools = tools,
                    maxOutputTokens = maxOutputTokens,
                    background = background,
                    previousInteractionId = previousInteractionId,
                    stage = stage,
                    variant = variant,
                    transcriptLength = transcriptLength,
                    session = session,
                )
            if (isStandalone && session != null) {
                when (result) {
                    is InteractionExecutionResult.Success -> {
                        session.complete(finalOutput = result.text, citations = result.citations)
                    }

                    is InteractionExecutionResult.FunctionCall -> {
                        session.complete(finalOutput = null, citations = emptyList())
                    }

                    is InteractionExecutionResult.Error -> {
                        val localRedactor = AiTraceRedactor(listOfNotNull(apiKey.takeIf { it.isNotBlank() }))
                        session.fail(
                            failureCategory = result.failureCategory,
                            errorSummary = localRedactor.redactString(result.message),
                        )
                    }
                }
            }
            result
        }

    private suspend fun executeInteraction(
        baseUrl: String,
        apiKey: String,
        model: String,
        inputs: List<GeminiInteractionInput>,
        validationError: String?,
        hasImages: Boolean,
        imageCount: Int,
        hasVideo: Boolean,
        videoDurationSeconds: Long?,
        webUrlCount: Int,
        inputUrls: List<String>,
        systemInstruction: String?,
        tools: List<GeminiInteractionTool>,
        maxOutputTokens: Int?,
        background: Boolean,
        previousInteractionId: String?,
        stage: AiAttemptStage,
        variant: String?,
        transcriptLength: Int,
        session: AiTraceSession?,
    ): InteractionExecutionResult {
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
        val generationConfig =
            maxOutputTokens?.let { GeminiInteractionGenerationConfig(maxOutputTokens = it) }
        val requestPayload =
            GeminiInteractionRequest(
                model = cleanModel,
                systemInstruction = systemInstruction?.takeIf { it.isNotBlank() },
                input = inputs,
                tools = tools.takeIf { it.isNotEmpty() },
                generationConfig = generationConfig,
                background = background,
                previousInteractionId = previousInteractionId?.takeIf { it.isNotBlank() },
            )
        val enabledToolNames =
            tools.map { tool ->
                tool.name?.takeIf { it.isNotBlank() && tool.type == GeminiInteractionTool.TYPE_FUNCTION } ?: tool.type
            }
        val attemptRequest =
            if (session?.isEnabled == true) {
                AiAttemptRequest(
                    payload =
                        runCatching {
                            jsonParser.encodeToJsonElement(GeminiInteractionRequest.serializer(), requestPayload)
                        }.getOrNull(),
                    hasImages = hasImages,
                    imageCount = imageCount,
                    hasVideo = hasVideo,
                    videoDurationSeconds = videoDurationSeconds,
                    transcriptLength = transcriptLength,
                    webUrlCount = webUrlCount,
                    enabledTools = enabledToolNames,
                )
            } else {
                null
            }

        val traceState = ExecutionTraceState()
        val localRedactor = AiTraceRedactor(listOfNotNull(apiKey.takeIf { it.isNotBlank() }))

        val result: InteractionExecutionResult =
            if (apiKey.isBlank()) {
                logger.warn("Gemini interaction aborted: API key is blank")
                InteractionExecutionResult.Error.Generic(
                    message = "Gemini API key is not configured",
                    failureCategory = AiTraceFailureCategory.NOT_CONFIGURED,
                )
            } else if (validationError != null) {
                logger.warn("Gemini interaction aborted: {}", validationError)
                InteractionExecutionResult.Error.Generic(
                    message = validationError,
                    failureCategory = AiTraceFailureCategory.CONTEXT_ERROR,
                )
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
                        logger.error("Gemini interaction rate limited (HTTP 429, retryAfter={})", retryAfter)
                        InteractionExecutionResult.Error.RateLimitExceeded(
                            message = "Gemini API rate limit exceeded (HTTP 429)",
                            retryAfterSeconds = retryAfter,
                        )
                    } else if (!response.status.isSuccess()) {
                        val errorBody = rawBody.orEmpty()
                        logger.error(
                            "Gemini interaction failed with HTTP {}: {}",
                            response.status.value,
                            localRedactor.redactString(errorBody),
                        )
                        InteractionExecutionResult.Error.ApiError(
                            statusCode = response.status.value,
                            message = "Gemini API error (${response.status.value}): $errorBody",
                        )
                    } else {
                        val interaction =
                            jsonParser.decodeFromString<GeminiInteractionResponse>(
                                rawBody?.takeIf { it.isNotBlank() }
                                    ?: throw SerializationException("Empty interaction response body"),
                            )
                        handleSubmissionResponse(
                            interaction = interaction,
                            cleanBaseUrl = cleanBaseUrl,
                            apiKey = apiKey,
                            background = background,
                            inputUrls = inputUrls,
                            traceState = traceState,
                            localRedactor = localRedactor,
                        )
                    }
                } catch (e: HttpRequestTimeoutException) {
                    logger.error("Gemini interaction request timed out: {}", localRedactor.redactString(e.message))
                    InteractionExecutionResult.Error.Timeout(
                        message = "Gemini API request timed out: ${e.message}",
                        cause = e,
                    )
                } catch (e: SocketTimeoutException) {
                    logger.error("Gemini interaction socket timed out: {}", localRedactor.redactString(e.message))
                    InteractionExecutionResult.Error.Timeout(
                        message = "Gemini API socket timed out: ${e.message}",
                        cause = e,
                    )
                } catch (e: TimeoutCancellationException) {
                    logger.error("Gemini interaction coroutine timed out: {}", localRedactor.redactString(e.message))
                    InteractionExecutionResult.Error.Timeout(
                        message = "Gemini API coroutine timed out: ${e.message}",
                        cause = e,
                    )
                } catch (e: SerializationException) {
                    logger.error("Failed to parse Gemini interaction response: {}", localRedactor.redactString(e.message))
                    InteractionExecutionResult.Error.Generic(
                        message = "Failed to parse Gemini interaction response: ${e.message}",
                        cause = e,
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    )
                } catch (e: Exception) {
                    logger.error("Unexpected error invoking Gemini interaction: {}", localRedactor.redactString(e.message), e)
                    InteractionExecutionResult.Error.Generic(
                        message = "Unexpected error invoking Gemini API: ${e.message}",
                        cause = e,
                    )
                }
            }

        if (session != null && session.isEnabled) {
            val latencyMs = elapsedMillisFromNanos(startNano)
            val parsedOutcome =
                when (result) {
                    is InteractionExecutionResult.Success -> AiAttemptOutcome.SUCCESS
                    is InteractionExecutionResult.FunctionCall -> AiAttemptOutcome.TOOL_CALL
                    is InteractionExecutionResult.Error -> AiAttemptOutcome.ERROR
                }
            val rawError = (result as? InteractionExecutionResult.Error)?.message
            session.recordAttempt(
                stage = stage,
                variant = variant,
                model = cleanModel,
                endpoint = sanitizedEndpoint,
                latencyMs = latencyMs,
                httpStatus = traceState.httpStatus,
                request = attemptRequest,
                responseRaw = traceState.responseRaw,
                finishReason = traceState.finishReason,
                usageMetadata = traceState.usageMetadata,
                pollingAttempts = if (background) traceState.pollingAttempts else null,
                parsedOutcome = parsedOutcome,
                errorMessage = localRedactor.redactString(rawError),
                urlContextResults = traceState.urlContextResults,
                googleSearchQueries = traceState.googleSearchQueries,
                rawCitations = traceState.rawCitations,
                filteredCitations = traceState.filteredCitations,
            )
        }

        return result
    }

    private suspend fun handleSubmissionResponse(
        interaction: GeminiInteractionResponse,
        cleanBaseUrl: String,
        apiKey: String,
        background: Boolean,
        inputUrls: List<String>,
        traceState: ExecutionTraceState,
        localRedactor: AiTraceRedactor,
    ): InteractionExecutionResult {
        if (!background) {
            return evaluateCompletedResponse(
                response = interaction,
                inputUrls = inputUrls,
                traceState = traceState,
                localRedactor = localRedactor,
            )
        }

        val immediateStatus = interaction.status?.lowercase()
        val hasImmediateResult =
            immediateStatus == "completed" ||
                immediateStatus == "failed" ||
                immediateStatus == "cancelled" ||
                immediateStatus == "requires_action" ||
                interaction.error != null ||
                interaction.extractFunctionCalls().isNotEmpty() ||
                interaction.extractOutputText().isNotBlank()

        if (hasImmediateResult) {
            return evaluateCompletedResponse(
                response = interaction,
                inputUrls = inputUrls,
                traceState = traceState,
                localRedactor = localRedactor,
            )
        }

        val interactionId = interaction.id
        if (interactionId.isNullOrBlank()) {
            logger.error("Gemini interactions submission returned no interaction ID")
            return InteractionExecutionResult.Error.Generic("No interaction ID returned from Gemini")
        }
        return pollInteraction(
            cleanBaseUrl = cleanBaseUrl,
            apiKey = apiKey,
            id = interactionId,
            inputUrls = inputUrls,
            traceState = traceState,
            localRedactor = localRedactor,
        )
    }

    private suspend fun pollInteraction(
        cleanBaseUrl: String,
        apiKey: String,
        id: String,
        inputUrls: List<String>,
        traceState: ExecutionTraceState,
        localRedactor: AiTraceRedactor,
    ): InteractionExecutionResult {
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
                logger.error("Gemini interaction polling rate limited for {} (HTTP 429, retryAfter={})", id, retryAfter)
                return InteractionExecutionResult.Error.RateLimitExceeded(
                    message = "Gemini API rate limit exceeded (HTTP 429)",
                    retryAfterSeconds = retryAfter,
                )
            }
            if (!response.status.isSuccess()) {
                logger.error("Gemini interaction polling failed for {} with HTTP {}", id, response.status.value)
                return InteractionExecutionResult.Error.ApiError(
                    statusCode = response.status.value,
                    message = "Error polling interaction: ${response.status.value}",
                )
            }

            val statusResponse =
                jsonParser.decodeFromString<GeminiInteractionResponse>(
                    rawBody?.takeIf { it.isNotBlank() } ?: throw SerializationException("Empty polling response body"),
                )
            val status = statusResponse.status?.lowercase()
            val hasTerminalState =
                status == "completed" ||
                    status == "failed" ||
                    status == "cancelled" ||
                    status == "requires_action" ||
                    statusResponse.error != null ||
                    statusResponse.extractFunctionCalls().isNotEmpty() ||
                    statusResponse.extractOutputText().isNotBlank()

            if (hasTerminalState) {
                return evaluateCompletedResponse(
                    response = statusResponse,
                    inputUrls = inputUrls,
                    traceState = traceState,
                    localRedactor = localRedactor,
                )
            }

            delay(pollingDelayMs)
        }

        logger.error("Gemini interaction {} timed out after {} polling attempts", id, maxPollingAttempts)
        return InteractionExecutionResult.Error.Timeout(
            "Agentic video interaction timed out after $maxPollingAttempts attempts",
        )
    }

    private fun evaluateCompletedResponse(
        response: GeminiInteractionResponse,
        inputUrls: List<String>,
        traceState: ExecutionTraceState,
        localRedactor: AiTraceRedactor,
    ): InteractionExecutionResult {
        val interactionId = response.id
        traceState.usageMetadata = response.usage
        traceState.finishReason = response.finishReason

        val urlContextResults = response.extractUrlContextResults()
        val googleSearchQueries = response.extractGoogleSearchQueries()
        val rawCitations = response.extractRawCitations()
        val filteredCitations = response.extractFilteredGroundingCitations(excludedUrls = inputUrls)

        traceState.urlContextResults = urlContextResults
        traceState.googleSearchQueries = googleSearchQueries
        traceState.rawCitations = rawCitations
        traceState.filteredCitations = filteredCitations

        for (entry in urlContextResults) {
            if (!entry.isSuccess) {
                logger.error(
                    "Gemini url_context_result non-success for interactionId={}: retrieved_url={}, status={}",
                    interactionId ?: "unknown",
                    localRedactor.redactString(entry.retrievedUrl),
                    entry.status,
                )
            }
        }

        val status = response.status?.lowercase()
        if (status == "failed" || status == "cancelled" || response.error != null) {
            val isSafety =
                GeminiFinishReasons.isSafetyBlock(response.finishReason) ||
                    GeminiFinishReasons.isSafetyBlock(response.error?.status)
            val errorMsg = response.error?.message ?: "Agentic video processing failed"
            logger.error("Gemini interaction {} failed: {}", interactionId ?: "unknown", localRedactor.redactString(errorMsg))
            return if (isSafety) {
                InteractionExecutionResult.Error.SafetyBlock(
                    message = errorMsg,
                    finishReason = response.finishReason ?: response.error?.status,
                )
            } else {
                InteractionExecutionResult.Error.Generic(errorMsg)
            }
        }

        val functionCalls = response.extractFunctionCalls()
        if (status == "requires_action" || functionCalls.isNotEmpty()) {
            if (functionCalls.isEmpty()) {
                logger.error("Gemini interaction {} returned requires_action with no function_call steps", interactionId ?: "unknown")
                return InteractionExecutionResult.Error.Generic(
                    message = "Gemini interaction returned requires_action with no function_call steps",
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                )
            }
            if (interactionId.isNullOrBlank()) {
                logger.error("Gemini interaction returned function_call with no interaction ID")
                return InteractionExecutionResult.Error.Generic(
                    message = "Gemini interaction returned function_call with no interaction ID",
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                )
            }
            return InteractionExecutionResult.FunctionCall(
                interactionId = interactionId,
                functionCalls = functionCalls,
                urlContextResults = urlContextResults,
                googleSearchQueries = googleSearchQueries,
                usageMetadata = response.usage,
            )
        }

        val rawOutput = response.extractOutputText()
        if (rawOutput.isBlank()) {
            if (GeminiFinishReasons.isSafetyBlock(response.finishReason)) {
                val msg = "Gemini interaction blocked due to finishReason: ${response.finishReason}"
                logger.error("Gemini interaction {} safety blocked: {}", interactionId ?: "unknown", msg)
                return InteractionExecutionResult.Error.SafetyBlock(
                    message = msg,
                    finishReason = response.finishReason,
                )
            }
            logger.error("Gemini interaction {} completed with empty output", interactionId ?: "unknown")
            return InteractionExecutionResult.Error.EmptyOutput(
                "Gemini interaction output is empty or sanitized to blank",
            )
        }

        val sanitized = EmojiSanitizer.stripEmojis(rawOutput).trim()
        if (sanitized.isBlank()) {
            logger.error("Gemini interaction {} completed with empty or emoji-only output", interactionId ?: "unknown")
            return InteractionExecutionResult.Error.EmptyOutput(
                "Gemini interaction output is empty or sanitized to blank",
            )
        }

        return InteractionExecutionResult.Success(
            interactionId = interactionId,
            text = sanitized,
            citations = filteredCitations,
            rawCitations = rawCitations,
            urlContextResults = urlContextResults,
            googleSearchQueries = googleSearchQueries,
            usageMetadata = response.usage,
        )
    }

    private fun InteractionExecutionResult.Error.toLegacyError(): GeminiInteractionResult.Error =
        when (this) {
            is InteractionExecutionResult.Error.RateLimitExceeded -> {
                GeminiInteractionResult.Error.RateLimitExceeded(
                    message = message,
                    retryAfterSeconds = retryAfterSeconds,
                )
            }

            is InteractionExecutionResult.Error.Timeout -> {
                GeminiInteractionResult.Error.Timeout(
                    message = message,
                    cause = cause,
                )
            }

            is InteractionExecutionResult.Error.ApiError -> {
                GeminiInteractionResult.Error.ApiError(
                    statusCode = statusCode,
                    message = message,
                )
            }

            is InteractionExecutionResult.Error.EmptyOutput -> {
                GeminiInteractionResult.Error.EmptyOutput(message = message)
            }

            is InteractionExecutionResult.Error.SafetyBlock -> {
                GeminiInteractionResult.Error.Generic(message = message)
            }

            is InteractionExecutionResult.Error.Generic -> {
                GeminiInteractionResult.Error.Generic(
                    message = message,
                    cause = cause,
                )
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

        /**
         * Builds ordered Interactions API input blocks with multimodal media (`video`, `image`)
         * placed before `text`, omitting `mime_type` for YouTube URIs.
         */
        fun buildMultimodalInputs(
            promptText: String,
            imageAttachments: List<ChatImageAttachment> = emptyList(),
            videoUri: String? = null,
            videoMimeType: String? = null,
            videoProcessingMode: VideoProcessingMode = VideoProcessingMode.STATIC,
        ): List<GeminiInteractionInput> {
            val inputs = mutableListOf<GeminiInteractionInput>()
            val cleanVideoUri = videoUri?.trim()?.takeIf { it.isNotBlank() }
            if (cleanVideoUri != null) {
                inputs.add(
                    GeminiInteractionInput.video(
                        uri = cleanVideoUri,
                        processing = videoProcessingMode,
                        mimeType = videoMimeType,
                    ),
                )
            }
            for (attachment in imageAttachments) {
                if (attachment.url.isNotBlank() && attachment.mimeType.isNotBlank()) {
                    inputs.add(
                        GeminiInteractionInput.image(
                            uri = attachment.url,
                            mimeType = attachment.mimeType,
                        ),
                    )
                }
            }
            if (promptText.isNotBlank()) {
                inputs.add(GeminiInteractionInput.text(promptText))
            }
            return inputs
        }
    }
}
