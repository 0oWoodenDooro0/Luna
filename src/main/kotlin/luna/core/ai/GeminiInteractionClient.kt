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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException

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
    private val requestTimeoutMs: Long? = null,
    private val connectTimeoutMs: Long? = null,
    private val socketTimeoutMs: Long? = null,
) {
    private val logger = LoggerFactory.getLogger(GeminiInteractionClient::class.java)
    private val toolLoop = GeminiInteractionToolLoop(this)

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

    suspend fun interact(
        baseUrl: String,
        apiKey: String,
        request: GeminiInteractionRequest,
        traceContext: InteractionTraceContext = InteractionTraceContext(),
    ): InteractionExecutionResult {
        val validationError =
            when {
                request.previousInteractionId != null && request.previousInteractionId.isBlank() -> {
                    "previous_interaction_id cannot be blank"
                }

                request.input.isEmpty() -> {
                    if (request.previousInteractionId != null) {
                        "functionResults cannot be empty"
                    } else {
                        "Interaction input cannot be blank"
                    }
                }

                else -> {
                    null
                }
            }
        return interactInternal(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = request,
            traceContext = traceContext,
            validationError = validationError,
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
        traceContext: InteractionTraceContext =
            InteractionTraceContext(stage = AiAttemptStage.SUMMARY_TOOL_CONTINUATION),
    ): InteractionExecutionResult {
        val validationError =
            when {
                previousInteractionId.isBlank() -> "previous_interaction_id cannot be blank"
                functionResults.isEmpty() -> "functionResults cannot be empty"
                else -> null
            }
        val request =
            GeminiInteractionRequest(
                model = model,
                systemInstruction = systemInstruction,
                input = functionResults,
                tools = tools.takeIf { it.isNotEmpty() },
                generationConfig = maxOutputTokens?.let { GeminiInteractionGenerationConfig(maxOutputTokens = it) },
                background = background,
                previousInteractionId = previousInteractionId,
            )
        val effectiveTraceContext =
            if (traceContext.stage == null) {
                traceContext.copy(stage = AiAttemptStage.SUMMARY_TOOL_CONTINUATION)
            } else {
                traceContext
            }
        return interactInternal(
            baseUrl = baseUrl,
            apiKey = apiKey,
            request = request,
            traceContext = effectiveTraceContext,
            validationError = validationError,
        )
    }

    /**
     * Executes an interaction and drives the bounded multi-turn tool-continuation loop until the
     * model produces a final [InteractionExecutionResult.Success], returns an [InteractionExecutionResult.Error],
     * or hits a protocol/turn guardrail.
     */
    suspend fun interactWithTools(
        baseUrl: String,
        apiKey: String,
        request: GeminiInteractionRequest,
        traceContext: InteractionTraceContext = InteractionTraceContext(),
        maxTurns: Int = DEFAULT_MAX_TOOL_TURNS,
        continuationStage: AiAttemptStage = AiAttemptStage.SUMMARY_TOOL_CONTINUATION,
        toolExecutor: suspend (GeminiInteractionFunctionCall) -> ToolExecutionOutcome,
    ): InteractionExecutionResult =
        traceRecorder.withActiveOrStandaloneSession(
            pipeline = traceContext.pipeline,
            trigger = traceContext.trigger,
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
                toolLoop.execute(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    request = request,
                    traceContext = traceContext,
                    maxTurns = maxTurns,
                    continuationStage = continuationStage,
                    toolExecutor = toolExecutor,
                )
            if (isStandalone && session != null) {
                completeStandaloneSession(session = session, apiKey = apiKey, result = result)
            }
            result
        }

    private fun completeStandaloneSession(
        session: AiTraceSession,
        apiKey: String,
        result: InteractionExecutionResult,
    ) {
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

    private suspend fun interactInternal(
        baseUrl: String,
        apiKey: String,
        request: GeminiInteractionRequest,
        traceContext: InteractionTraceContext,
        validationError: String?,
    ): InteractionExecutionResult =
        traceRecorder.withActiveOrStandaloneSession(
            pipeline = traceContext.pipeline,
            trigger = traceContext.trigger,
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
                    request = request,
                    traceContext = traceContext,
                    validationError = validationError,
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
        request: GeminiInteractionRequest,
        traceContext: InteractionTraceContext,
        validationError: String?,
        session: AiTraceSession?,
    ): InteractionExecutionResult {
        val startNano = System.nanoTime()
        val cleanBaseUrl = baseUrl.trim().trimEnd('/').removeSuffix("/v1beta")
        val cleanModel = request.model.trim().removePrefix("models/")
        val rawEndpoint =
            if (apiKey.isBlank()) {
                "$cleanBaseUrl/v1beta/interactions"
            } else {
                "$cleanBaseUrl/v1beta/interactions?key=$apiKey"
            }
        val sanitizedEndpoint = AiTraceRedactor.sanitizeEndpoint(rawEndpoint)
        val requestPayload =
            request.copy(
                model = cleanModel,
                systemInstruction = request.systemInstruction?.takeIf { it.isNotBlank() },
                tools = request.tools?.takeIf { it.isNotEmpty() },
                previousInteractionId = request.previousInteractionId?.takeIf { it.isNotBlank() },
            )
        val effectiveTools = requestPayload.tools.orEmpty()
        val enabledToolNames =
            effectiveTools.map { tool ->
                tool.name?.takeIf { it.isNotBlank() && tool.type == GeminiInteractionTool.TYPE_FUNCTION } ?: tool.type
            }
        val imageCount =
            traceContext.imageCount
                ?: requestPayload.input.count { it.type == GeminiInteractionInput.TYPE_IMAGE }
        val hasImages = traceContext.hasImages ?: (imageCount > 0)
        val hasVideo =
            traceContext.hasVideo
                ?: requestPayload.input.any { it.type == GeminiInteractionInput.TYPE_VIDEO }
        val transcriptLength =
            traceContext.transcriptLength
                ?: requestPayload.input
                    .filter { it.type == GeminiInteractionInput.TYPE_TEXT }
                    .sumOf { it.text?.length ?: 0 }
        val resolvedStage =
            traceContext.stage
                ?: when {
                    requestPayload.previousInteractionId != null -> AiAttemptStage.SUMMARY_TOOL_CONTINUATION
                    requestPayload.background -> AiAttemptStage.AGENTIC_VIDEO
                    else -> AiAttemptStage.SUMMARY_GENERATE
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
                    videoDurationSeconds = traceContext.videoDurationSeconds,
                    transcriptLength = transcriptLength,
                    webUrlCount = traceContext.webUrlCount,
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
                            applyGeminiTimeout(httpClient, requestTimeoutMs, connectTimeoutMs, socketTimeoutMs)
                            setBody(requestPayload)
                        }
                    traceState.httpStatus = response.status.value
                    val rawBody = readBodyTextSafely(response)
                    traceState.responseRaw = rawBody

                    if (response.status == HttpStatusCode.TooManyRequests) {
                        val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                        logger.error("Gemini interaction rate limited (HTTP 429, retryAfter={})", retryAfter)
                        InteractionExecutionResult.Error.RateLimitExceeded(
                            message = "Gemini API rate limit exceeded (HTTP 429)",
                            retryAfterSeconds = retryAfter,
                        )
                    } else if (!response.status.isSuccess()) {
                        classifyHttpError(
                            statusCode = response.status.value,
                            errorBody = rawBody.orEmpty(),
                            traceState = traceState,
                            localRedactor = localRedactor,
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
                            background = requestPayload.background,
                            inputUrls = traceContext.inputUrls,
                            stripOutputEmojis = traceContext.stripOutputEmojis,
                            allowedCustomEmojiIds = traceContext.allowedCustomEmojiIds,
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
                } catch (e: CancellationException) {
                    throw e
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
                stage = resolvedStage,
                variant = traceContext.variant,
                model = cleanModel,
                endpoint = sanitizedEndpoint,
                latencyMs = latencyMs,
                httpStatus = traceState.httpStatus,
                request = attemptRequest,
                responseRaw = traceState.responseRaw,
                finishReason = traceState.finishReason,
                usageMetadata = traceState.usageMetadata,
                pollingAttempts = if (requestPayload.background) traceState.pollingAttempts else null,
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
        stripOutputEmojis: Boolean,
        allowedCustomEmojiIds: Set<String>,
        traceState: ExecutionTraceState,
        localRedactor: AiTraceRedactor,
    ): InteractionExecutionResult {
        if (!background) {
            return evaluateCompletedResponse(
                response = interaction,
                inputUrls = inputUrls,
                stripOutputEmojis = stripOutputEmojis,
                allowedCustomEmojiIds = allowedCustomEmojiIds,
                traceState = traceState,
                localRedactor = localRedactor,
            )
        }

        if (interaction.isTerminalState()) {
            return evaluateCompletedResponse(
                response = interaction,
                inputUrls = inputUrls,
                stripOutputEmojis = stripOutputEmojis,
                allowedCustomEmojiIds = allowedCustomEmojiIds,
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
            stripOutputEmojis = stripOutputEmojis,
            allowedCustomEmojiIds = allowedCustomEmojiIds,
            traceState = traceState,
            localRedactor = localRedactor,
        )
    }

    private suspend fun pollInteraction(
        cleanBaseUrl: String,
        apiKey: String,
        id: String,
        inputUrls: List<String>,
        stripOutputEmojis: Boolean,
        allowedCustomEmojiIds: Set<String>,
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

            val response =
                httpClient.get(endpoint) {
                    applyGeminiTimeout(httpClient, requestTimeoutMs, connectTimeoutMs, socketTimeoutMs)
                }
            traceState.httpStatus = response.status.value
            val rawBody = readBodyTextSafely(response)
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
                return classifyHttpError(
                    statusCode = response.status.value,
                    errorBody = rawBody.orEmpty(),
                    traceState = traceState,
                    localRedactor = localRedactor,
                    pollingInteractionId = id,
                )
            }

            val statusResponse =
                jsonParser.decodeFromString<GeminiInteractionResponse>(
                    rawBody?.takeIf { it.isNotBlank() } ?: throw SerializationException("Empty polling response body"),
                )
            if (statusResponse.isTerminalState()) {
                return evaluateCompletedResponse(
                    response = statusResponse,
                    inputUrls = inputUrls,
                    stripOutputEmojis = stripOutputEmojis,
                    allowedCustomEmojiIds = allowedCustomEmojiIds,
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
        stripOutputEmojis: Boolean,
        allowedCustomEmojiIds: Set<String>,
        traceState: ExecutionTraceState,
        localRedactor: AiTraceRedactor,
    ): InteractionExecutionResult {
        val interactionId = response.id
        val parsedStatus = response.parsedStatus()
        traceState.usageMetadata = response.usage
        traceState.finishReason = response.resolvedFinishReason()

        if (response.isIncompleteStatus()) {
            logger.warn(
                "Gemini interaction {} returned status=incomplete (output may be truncated by max_output_tokens; outputTokens={})",
                interactionId ?: "unknown",
                response.usage?.candidatesTokenCount,
            )
        }

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

        if (parsedStatus == InteractionStatus.FAILED || parsedStatus == InteractionStatus.CANCELLED || response.error != null) {
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
        if (parsedStatus == InteractionStatus.REQUIRES_ACTION || functionCalls.isNotEmpty()) {
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

        val sanitized =
            if (stripOutputEmojis) {
                EmojiSanitizer.stripEmojis(rawOutput, allowedCustomEmojiIds = allowedCustomEmojiIds).trim()
            } else {
                rawOutput.trim()
            }
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

    private fun classifyHttpError(
        statusCode: Int,
        errorBody: String,
        traceState: ExecutionTraceState,
        localRedactor: AiTraceRedactor,
        pollingInteractionId: String? = null,
    ): InteractionExecutionResult.Error {
        val safetyReason = extractSafetyBlockReason(errorBody)
        if (safetyReason != null) {
            traceState.finishReason = safetyReason
            logger.warn(
                "Gemini interaction{} blocked by safety/policy filter (HTTP {}, reason={}): {}",
                pollingInteractionId?.let { " polling for $it" }.orEmpty(),
                statusCode,
                safetyReason,
                localRedactor.redactString(errorBody),
            )
            return InteractionExecutionResult.Error.SafetyBlock(
                message = "Gemini API error ($statusCode): $errorBody",
                finishReason = safetyReason,
            )
        }
        if (pollingInteractionId != null) {
            logger.error("Gemini interaction polling failed for {} with HTTP {}", pollingInteractionId, statusCode)
            return InteractionExecutionResult.Error.ApiError(
                statusCode = statusCode,
                message = "Error polling interaction: $statusCode",
            )
        }
        logger.error(
            "Gemini interaction failed with HTTP {}: {}",
            statusCode,
            localRedactor.redactString(errorBody),
        )
        return InteractionExecutionResult.Error.ApiError(
            statusCode = statusCode,
            message = "Gemini API error ($statusCode): $errorBody",
        )
    }

    private fun extractSafetyBlockReason(errorBody: String): String? {
        if (errorBody.isBlank()) return null
        val errorObj =
            runCatching {
                jsonParser.parseToJsonElement(errorBody).jsonObject["error"] as? JsonObject
            }.getOrNull() ?: return null
        val code = (errorObj["code"] as? JsonPrimitive)?.contentOrNull
        val status = (errorObj["status"] as? JsonPrimitive)?.contentOrNull
        return when {
            GeminiFinishReasons.isSafetyBlock(code) -> code!!.trim().uppercase()
            GeminiFinishReasons.isSafetyBlock(status) -> status!!.trim().uppercase()
            else -> null
        }
    }

    private suspend fun readBodyTextSafely(response: HttpResponse): String? =
        try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    companion object {
        const val DEFAULT_POLLING_DELAY_MS = 2000L
        const val DEFAULT_MAX_POLLING_ATTEMPTS = 30
        const val DEFAULT_MAX_TOOL_TURNS = 8

        private val jsonParser = GeminiHttpClientFactory.json

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
