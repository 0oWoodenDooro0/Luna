package luna.summarizer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptRecord
import luna.core.ai.AiAttemptRequest
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceJson
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.EmojiSanitizer
import luna.core.ai.GeminiContent
import luna.core.ai.GeminiFunctionResponse
import luna.core.ai.GeminiGenerateContentRequest
import luna.core.ai.GeminiGenerateContentResponse
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionResult
import luna.core.ai.GeminiPart
import luna.core.ai.GeminiTool
import luna.core.ai.GeminiTools
import luna.core.ai.GeminiUsageMetadata
import luna.core.ai.GroundingCitation
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.VideoProcessingMode
import luna.core.ai.currentAiTraceSession
import luna.core.ai.elapsedMillisFromNanos
import luna.core.ai.withActiveOrStandaloneSession
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException

/**
 * AI client service that formats prompts and invokes Google Gemini API
 * to produce structured, emoji-free text summaries.
 */
open class GeminiSummarizer(
    private val config: GeminiConfig = GeminiConfig.fromEnv(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val pollingDelayMs: Long = 2000L,
    private val maxPollingAttempts: Int = 30,
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java)
    private val interactionClient =
        GeminiInteractionClient(
            httpClient = httpClient,
            pollingDelayMs = pollingDelayMs,
            maxPollingAttempts = maxPollingAttempts,
            traceRecorder = traceRecorder,
        )

    private data class AttemptRequestMetadata(
        val hasImages: Boolean = false,
        val imageCount: Int = 0,
        val hasVideo: Boolean = false,
        val videoDurationSeconds: Long? = null,
        val transcriptLength: Int = 0,
    )

    private data class HandledResponse(
        val result: SummaryResult,
        val responseRaw: String? = null,
        val finishReason: String? = null,
        val blockReason: String? = null,
        val usageMetadata: GeminiUsageMetadata? = null,
    )

    private fun SummaryInput.toAttemptMetadata(extraTranscriptLength: Int = 0): AttemptRequestMetadata =
        AttemptRequestMetadata(
            hasImages = imageAttachments.isNotEmpty(),
            imageCount = imageAttachments.size,
            hasVideo = !videoUri.isNullOrBlank(),
            videoDurationSeconds = videoDurationSeconds,
            transcriptLength = content.length + extraTranscriptLength,
        )

    private fun resolveVariant(tools: List<GeminiTool>?): String {
        val hasSearch = tools?.any { it.actualGoogleSearch != null } == true
        val hasLookback =
            tools?.any { tool ->
                tool.actualFunctionDeclarations.any { it.name == GeminiTools.FETCH_EARLIER_MESSAGES }
            } == true
        return when {
            hasSearch && hasLookback -> "grounded+lookback"
            hasSearch -> "grounded"
            hasLookback -> "lookback"
            else -> "baseline"
        }
    }

    suspend fun summarize(
        content: String,
        comments: List<String> = emptyList(),
        title: String? = null,
        url: String? = null,
    ): SummaryResult =
        summarize(
            SummaryInput(
                content = content,
                title = title,
                comments = comments,
                url = url,
            ),
        )

    private suspend fun summarizeDirect(
        input: SummaryInput,
        tools: List<GeminiTool>? = input.tools,
        variant: String = resolveVariant(tools),
    ): SummaryResult {
        val mode =
            if (!input.videoUri.isNullOrBlank()) {
                GeminiPromptBuilder.resolveProcessingMode(input.videoDurationSeconds)
            } else {
                VideoProcessingMode.STATIC
            }

        return if (mode == VideoProcessingMode.AGENTIC && !input.videoUri.isNullOrBlank()) {
            summarizeAgentic(input)
        } else {
            summarizeStatic(input, tools, variant)
        }
    }

    open suspend fun summarize(
        input: SummaryInput,
        lookbackProvider: (suspend (reason: String) -> String?)? = null,
    ): SummaryResult =
        traceRecorder.withActiveOrStandaloneSession(
            pipeline = AiTracePipeline.SUMMARY,
            trigger = AiTraceTrigger.REACTION,
            discord =
                AiTraceDiscordContext(
                    channelId = "direct",
                    messageId = "direct",
                ),
            secret = config.apiKey,
            onStandaloneException = { session, e ->
                session.fail(
                    failureCategory = classifySummaryException(e),
                    errorSummary = e.message ?: e::class.simpleName ?: "Unexpected error",
                )
            },
        ) { session, isStandalone ->
            val result = executeSummarize(input, lookbackProvider, session)
            if (isStandalone && session != null) {
                when (result) {
                    is SummaryResult.Success -> {
                        session.complete(
                            finalOutput = result.text,
                            citations = result.citations,
                        )
                    }

                    is SummaryResult.Error -> {
                        session.fail(
                            failureCategory = classifySummaryError(result),
                            errorSummary = result.message,
                        )
                    }

                    is SummaryResult.ToolCall -> {
                        session.complete(finalOutput = null)
                    }
                }
            }
            result
        }

    private suspend fun executeSummarize(
        input: SummaryInput,
        lookbackProvider: (suspend (reason: String) -> String?)?,
        session: AiTraceSession?,
    ): SummaryResult {
        if (!config.isConfigured) {
            val error = SummaryResult.Error.MissingApiKey()
            session?.fail(AiTraceFailureCategory.NOT_CONFIGURED, error.message)
            return error
        }
        if (input.content.isBlank()) {
            val error = SummaryResult.Error.Generic("Content to summarize cannot be blank")
            session?.fail(AiTraceFailureCategory.CONTEXT_ERROR, error.message)
            return error
        }

        val isAgenticVideo =
            !input.videoUri.isNullOrBlank() &&
                GeminiPromptBuilder.resolveProcessingMode(input.videoDurationSeconds) == VideoProcessingMode.AGENTIC
        if (isAgenticVideo) {
            return summarizeAgentic(input)
        }

        val activeSearchTools = if (config.searchGroundingEnabled) listOf(GeminiTools.GOOGLE_SEARCH_TOOL) else emptyList()

        if (lookbackProvider == null) {
            val initialTools = input.tools ?: activeSearchTools.takeIf { it.isNotEmpty() }
            val initialVariant = resolveVariant(initialTools)
            val res =
                try {
                    summarizeDirect(input, tools = initialTools, variant = initialVariant)
                } catch (e: Exception) {
                    if (initialTools != null && input.tools == null) {
                        logger.warn("Search grounding failed with exception: {}. Retrying baseline without search.", e.message)
                        session?.markDegraded("Search grounding failed with exception: ${e.message}")
                        retryBaseline(input.copy(tools = null), session = session, initialVariant = "no-search")
                    } else if (input.imageAttachments.isNotEmpty()) {
                        logger.warn("Multimodal summarization failed with exception: {}. Retrying text baseline without images.", e.message)
                        session?.markDegraded("Multimodal summarization failed with exception: ${e.message}")
                        retryBaseline(
                            input.copy(imageAttachments = emptyList(), tools = null),
                            session = session,
                            initialVariant = "no-images",
                        )
                    } else {
                        throw e
                    }
                }
            return if (res is SummaryResult.Error) {
                if (initialTools != null && input.tools == null) {
                    logger.warn("Search grounding returned error: {}. Retrying baseline without search.", res.message)
                    session?.markDegraded("Search grounding failed: ${res.message}")
                    retryBaseline(input.copy(tools = null), session = session, initialVariant = "no-search")
                } else if (input.imageAttachments.isNotEmpty()) {
                    logger.warn("Multimodal summarization returned error: {}. Retrying text baseline without images.", res.message)
                    session?.markDegraded("Multimodal summarization failed: ${res.message}")
                    retryBaseline(
                        input.copy(imageAttachments = emptyList(), tools = null),
                        session = session,
                        initialVariant = "no-images",
                    )
                } else {
                    res
                }
            } else {
                res
            }
        }

        val baselineInput = input.copy(tools = null)
        val combinedTools = (activeSearchTools + GeminiTools.FETCH_EARLIER_MESSAGES_TOOL).distinct()
        val toolEnabledInput = input.copy(tools = combinedTools)
        val toolVariant = resolveVariant(combinedTools)
        var baselineAttempted = false

        var result: SummaryResult =
            try {
                summarizeDirect(toolEnabledInput, tools = toolEnabledInput.tools, variant = toolVariant)
            } catch (e: Exception) {
                logger.warn("Tool-enabled summarization failed: {}. Retrying baseline fallback.", e.message)
                session?.markDegraded("Tool-enabled summarization failed: ${e.message}")
                baselineAttempted = true
                retryBaseline(baselineInput, session = session, initialVariant = "baseline")
            }

        if (result is SummaryResult.Error && !baselineAttempted) {
            logger.warn("Tool-enabled summarization returned error: {}. Retrying baseline fallback.", result.message)
            session?.markDegraded("Tool-enabled summarization failed: ${result.message}")
            baselineAttempted = true
            result = retryBaseline(baselineInput, session = session, initialVariant = "baseline")
        }

        if (result is SummaryResult.ToolCall) {
            if (result.name == GeminiTools.FETCH_EARLIER_MESSAGES) {
                val reason = result.args["reason"] ?: ""
                logger.info("Gemini invoked fetch_earlier_messages with reason='{}'", reason)
                val continuation = result.continuation
                if (continuation != null) {
                    val toolOutput =
                        try {
                            lookbackProvider(reason) ?: ConversationSliceCollector.NO_FURTHER_HISTORY_MESSAGE
                        } catch (e: Exception) {
                            logger.warn("Lookback provider failed: {}", e.message)
                            session?.markDegraded("Lookback provider failed: ${e.message}")
                            null
                        }

                    if (toolOutput != null) {
                        val lookbackResult =
                            try {
                                continuation(toolOutput)
                            } catch (e: Exception) {
                                logger.warn("Lookback continuation failed: {}", e.message)
                                session?.markDegraded("Lookback continuation failed: ${e.message}")
                                null
                            }

                        if (lookbackResult is SummaryResult.Success) {
                            result = lookbackResult
                        } else {
                            logger.warn("Lookback continuation returned non-success: {}. Falling back to baseline.", lookbackResult)
                            val reasonMsg = (lookbackResult as? SummaryResult.Error)?.message ?: "non-success tool continuation"
                            session?.markDegraded("Lookback continuation fallback: $reasonMsg")
                            baselineAttempted = true
                            result = retryBaseline(baselineInput, session = session, initialVariant = "baseline")
                        }
                    } else {
                        baselineAttempted = true
                        result = retryBaseline(baselineInput, session = session, initialVariant = "baseline")
                    }
                } else {
                    logger.warn("No continuation provided for tool call, falling back to baseline")
                    session?.markDegraded("No continuation provided for tool call")
                    baselineAttempted = true
                    result = retryBaseline(baselineInput, session = session, initialVariant = "baseline")
                }
            } else {
                logger.warn("Unhandled tool call {}, falling back to baseline", result.name)
                session?.markDegraded("Unhandled tool call: ${result.name}")
                baselineAttempted = true
                result = retryBaseline(baselineInput, session = session, initialVariant = "baseline")
            }
        }

        if (result is SummaryResult.Error && input.imageAttachments.isNotEmpty() && !baselineAttempted) {
            logger.warn("Multimodal summarization failed: {}. Retrying text-only fallback.", result.message)
            session?.markDegraded("Multimodal summarization failed: ${result.message}")
            result =
                retryBaseline(
                    baselineInput.copy(imageAttachments = emptyList()),
                    session = session,
                    initialVariant = "no-images",
                )
        }

        return result
    }

    private suspend fun retryBaseline(
        input: SummaryInput,
        session: AiTraceSession?,
        initialVariant: String = "baseline",
    ): SummaryResult =
        try {
            val res = summarizeDirect(input, tools = null, variant = initialVariant)
            if (res is SummaryResult.Error && input.imageAttachments.isNotEmpty()) {
                logger.warn("Baseline retry with images failed: {}. Retrying text-only fallback.", res.message)
                session?.markDegraded("Baseline retry with images failed: ${res.message}")
                summarizeDirect(input.copy(imageAttachments = emptyList()), tools = null, variant = "no-images")
            } else {
                res
            }
        } catch (e: Exception) {
            logger.warn("Baseline retry encountered exception: {}. Attempting fallback.", e.message, e)
            if (input.imageAttachments.isNotEmpty()) {
                try {
                    logger.warn("Retrying text-only fallback after baseline exception: {}", e.message)
                    session?.markDegraded("Baseline retry exception: ${e.message}")
                    summarizeDirect(input.copy(imageAttachments = emptyList()), tools = null, variant = "no-images")
                } catch (e2: Exception) {
                    SummaryResult.Error.Generic("Baseline fallback failed: ${e2.message}", cause = e2)
                }
            } else {
                SummaryResult.Error.Generic("Baseline fallback failed: ${e.message}", cause = e)
            }
        }

    private suspend fun postGenerateContent(
        requestPayload: GeminiGenerateContentRequest,
        activeTools: List<GeminiTool>?,
        stage: AiAttemptStage = AiAttemptStage.SUMMARY_GENERATE,
        variant: String? = resolveVariant(activeTools),
        requestMeta: AttemptRequestMetadata = AttemptRequestMetadata(),
    ): SummaryResult {
        val cleanEndpoint = "${config.cleanBaseUrl}/v1beta/models/${config.cleanModel}:generateContent"
        val endpointWithKey = "$cleanEndpoint?key=${config.apiKey}"
        val session = currentAiTraceSession()
        val startNano = System.nanoTime()

        var httpStatus: Int? = null
        var responseRaw: String? = null
        var finishReason: String? = null
        var blockReason: String? = null
        var usageMetadata: GeminiUsageMetadata? = null

        val result: SummaryResult =
            try {
                val response: HttpResponse =
                    httpClient.post(endpointWithKey) {
                        contentType(ContentType.Application.Json)
                        setBody(requestPayload)
                    }
                httpStatus = response.status.value

                val handled =
                    handleResponse(
                        response = response,
                        originalRequest = requestPayload,
                        activeTools = activeTools,
                        requestMeta = requestMeta,
                    )
                responseRaw = handled.responseRaw
                finishReason = handled.finishReason
                blockReason = handled.blockReason
                usageMetadata = handled.usageMetadata
                handled.result
            } catch (e: HttpRequestTimeoutException) {
                SummaryResult.Error.Timeout("Gemini API request timed out: ${e.message}", cause = e)
            } catch (e: SocketTimeoutException) {
                SummaryResult.Error.Timeout("Gemini API socket timed out: ${e.message}", cause = e)
            } catch (e: TimeoutCancellationException) {
                SummaryResult.Error.Timeout("Gemini API coroutine timed out: ${e.message}", cause = e)
            } catch (e: Exception) {
                SummaryResult.Error.Generic("Unexpected error invoking Gemini API: ${e.message}", cause = e)
            }

        val latencyMs = elapsedMillisFromNanos(startNano)
        if (session != null && session.isEnabled) {
            val parsedOutcome =
                when (result) {
                    is SummaryResult.Success -> AiAttemptOutcome.SUCCESS
                    is SummaryResult.ToolCall -> AiAttemptOutcome.TOOL_CALL
                    is SummaryResult.Error -> AiAttemptOutcome.ERROR
                }
            val errorMessage = (result as? SummaryResult.Error)?.message
            val encodedPayload =
                runCatching {
                    AiTraceJson.encodeToJsonElement(GeminiGenerateContentRequest.serializer(), requestPayload)
                }.getOrNull()

            session.recordAttempt(
                stage = stage,
                variant = variant,
                model = config.cleanModel,
                endpoint = cleanEndpoint,
                latencyMs = latencyMs,
                httpStatus = httpStatus,
                request =
                    AiAttemptRequest(
                        payload = encodedPayload,
                        hasImages = requestMeta.hasImages,
                        imageCount = requestMeta.imageCount,
                        hasVideo = requestMeta.hasVideo,
                        videoDurationSeconds = requestMeta.videoDurationSeconds,
                        transcriptLength = requestMeta.transcriptLength,
                    ),
                responseRaw = responseRaw,
                finishReason = finishReason,
                blockReason = blockReason,
                usageMetadata = usageMetadata,
                pollingAttempts = null,
                parsedOutcome = parsedOutcome,
                errorMessage = errorMessage,
            )
        }

        return result
    }

    private suspend fun summarizeStatic(
        input: SummaryInput,
        tools: List<GeminiTool>? = input.tools,
        variant: String = resolveVariant(tools),
    ): SummaryResult {
        val requestPayload = GeminiPromptBuilder.buildRequest(input, tools)
        return postGenerateContent(
            requestPayload = requestPayload,
            activeTools = tools,
            stage = AiAttemptStage.SUMMARY_GENERATE,
            variant = variant,
            requestMeta = input.toAttemptMetadata(),
        )
    }

    private suspend fun executeToolResponse(
        previousRequest: GeminiGenerateContentRequest,
        modelContent: GeminiContent,
        toolName: String,
        toolResponseContent: String,
        tools: List<GeminiTool>? = null,
        requestMeta: AttemptRequestMetadata = AttemptRequestMetadata(transcriptLength = toolResponseContent.length),
    ): SummaryResult {
        if (!config.isConfigured) {
            return SummaryResult.Error.MissingApiKey()
        }

        val updatedContents = previousRequest.contents.toMutableList()
        updatedContents.add(modelContent)
        updatedContents.add(
            GeminiContent(
                role = "function",
                parts =
                    listOf(
                        GeminiPart(
                            functionResponse =
                                GeminiFunctionResponse.of(
                                    name = toolName,
                                    content = toolResponseContent,
                                ),
                        ),
                    ),
            ),
        )

        val nextRequest =
            GeminiGenerateContentRequest(
                contents = updatedContents,
                systemInstruction = previousRequest.systemInstruction,
                generationConfig = previousRequest.generationConfig,
                tools = tools,
                toolConfig = previousRequest.toolConfig,
            )

        return postGenerateContent(
            requestPayload = nextRequest,
            activeTools = tools,
            stage = AiAttemptStage.SUMMARY_TOOL_CONTINUATION,
            variant = toolName,
            requestMeta = requestMeta,
        )
    }

    private suspend fun summarizeAgentic(input: SummaryInput): SummaryResult {
        val userPrompt = GeminiPromptBuilder.buildUserPrompt(input)
        val systemInstruction = GeminiPromptBuilder.buildSystemInstruction(input)
        val promptText = "$systemInstruction\n\n---\n\n$userPrompt"

        return when (
            val result =
                interactionClient.runAgenticVideo(
                    baseUrl = config.cleanBaseUrl,
                    apiKey = config.apiKey,
                    model = config.cleanModel,
                    videoUri = input.videoUri.orEmpty(),
                    promptText = promptText,
                    videoDurationSeconds = input.videoDurationSeconds,
                )
        ) {
            is GeminiInteractionResult.Success -> {
                SummaryResult.Success(text = result.text)
            }

            is GeminiInteractionResult.Error.RateLimitExceeded -> {
                SummaryResult.Error.RateLimitExceeded(
                    message = result.message,
                    retryAfterSeconds = result.retryAfterSeconds,
                )
            }

            is GeminiInteractionResult.Error.Timeout -> {
                SummaryResult.Error.Timeout(message = result.message, cause = result.cause)
            }

            is GeminiInteractionResult.Error.ApiError -> {
                SummaryResult.Error.ApiError(statusCode = result.statusCode, message = result.message)
            }

            is GeminiInteractionResult.Error.EmptyOutput -> {
                SummaryResult.Error.Generic(result.message)
            }

            is GeminiInteractionResult.Error.Generic -> {
                SummaryResult.Error.Generic(message = result.message, cause = result.cause)
            }
        }
    }

    private suspend fun handleResponse(
        response: HttpResponse,
        originalRequest: GeminiGenerateContentRequest? = null,
        activeTools: List<GeminiTool>? = null,
        requestMeta: AttemptRequestMetadata = AttemptRequestMetadata(),
    ): HandledResponse {
        when {
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                val rawBody = runCatching { response.bodyAsText() }.getOrNull()
                return HandledResponse(
                    result =
                        SummaryResult.Error.RateLimitExceeded(
                            message = "Gemini API rate limit exceeded (HTTP 429)",
                            retryAfterSeconds = retryAfter,
                        ),
                    responseRaw = rawBody,
                )
            }

            !response.status.isSuccess() -> {
                val errorBody = response.bodyAsText()
                val parsedMessage =
                    try {
                        jsonParser.decodeFromString<GeminiGenerateContentResponse>(errorBody).error?.message
                    } catch (_: Exception) {
                        null
                    }
                val message = parsedMessage ?: errorBody.ifBlank { "HTTP error ${response.status.value}" }
                return HandledResponse(
                    result =
                        SummaryResult.Error.ApiError(
                            statusCode = response.status.value,
                            message = "Gemini API error (${response.status.value}): $message",
                        ),
                    responseRaw = errorBody,
                )
            }

            else -> {
                val rawBody = response.bodyAsText()
                val parsedResponse =
                    try {
                        jsonParser.decodeFromString<GeminiGenerateContentResponse>(rawBody)
                    } catch (e: Exception) {
                        return HandledResponse(
                            result =
                                SummaryResult.Error.Generic(
                                    message = "Failed to parse Gemini API response: ${e.message}",
                                    cause = e,
                                ),
                            responseRaw = rawBody,
                        )
                    }

                val candidate = parsedResponse.candidates?.firstOrNull()
                val finishReason = candidate?.finishReason
                val blockReason = parsedResponse.promptFeedback?.blockReason
                val usageMetadata = parsedResponse.usageMetadata

                if (parsedResponse.error != null) {
                    return HandledResponse(
                        result =
                            SummaryResult.Error.ApiError(
                                statusCode = parsedResponse.error.code ?: response.status.value,
                                message = parsedResponse.error.message ?: "Unknown API error",
                            ),
                        responseRaw = rawBody,
                        finishReason = finishReason,
                        blockReason = blockReason,
                        usageMetadata = usageMetadata,
                    )
                }

                if (blockReason != null) {
                    return HandledResponse(
                        result =
                            SummaryResult.Error.Generic(
                                "Gemini prompt blocked due to: $blockReason",
                            ),
                        responseRaw = rawBody,
                        finishReason = finishReason,
                        blockReason = blockReason,
                        usageMetadata = usageMetadata,
                    )
                }

                val candidates = parsedResponse.candidates
                if (candidates.isNullOrEmpty()) {
                    return HandledResponse(
                        result = SummaryResult.Error.Generic("No summary candidates returned by Gemini"),
                        responseRaw = rawBody,
                        finishReason = finishReason,
                        blockReason = blockReason,
                        usageMetadata = usageMetadata,
                    )
                }

                val candidateContent = candidate?.content
                val parts = candidateContent?.parts ?: emptyList()

                val functionCallPart = parts.firstOrNull { it.actualFunctionCall != null }
                val functionCall = functionCallPart?.actualFunctionCall

                if (functionCall != null) {
                    if (functionCall.name.isBlank()) {
                        return HandledResponse(
                            result = SummaryResult.Error.Generic("Malformed tool call: missing function name"),
                            responseRaw = rawBody,
                            finishReason = finishReason,
                            blockReason = blockReason,
                            usageMetadata = usageMetadata,
                        )
                    }

                    val declaredToolNames =
                        activeTools
                            ?.flatMap { it.actualFunctionDeclarations }
                            ?.map { it.name }
                            ?.toSet()

                    if (declaredToolNames != null && declaredToolNames.isNotEmpty() && !declaredToolNames.contains(functionCall.name)) {
                        return HandledResponse(
                            result = SummaryResult.Error.Generic("Unknown tool call requested by Gemini: ${functionCall.name}"),
                            responseRaw = rawBody,
                            finishReason = finishReason,
                            blockReason = blockReason,
                            usageMetadata = usageMetadata,
                        )
                    }

                    if (functionCall.name == GeminiTools.FETCH_EARLIER_MESSAGES) {
                        val reason = functionCall.argsAsStrings()["reason"]
                        if (reason.isNullOrBlank()) {
                            return HandledResponse(
                                result =
                                    SummaryResult.Error.Generic(
                                        "Malformed tool call: missing required 'reason' parameter for ${functionCall.name}",
                                    ),
                                responseRaw = rawBody,
                                finishReason = finishReason,
                                blockReason = blockReason,
                                usageMetadata = usageMetadata,
                            )
                        }
                    }

                    val continuation: (suspend (String) -> SummaryResult)? =
                        if (originalRequest != null) {
                            { toolOutput ->
                                executeToolResponse(
                                    previousRequest = originalRequest,
                                    modelContent = candidateContent ?: GeminiContent(role = "model", parts = parts),
                                    toolName = functionCall.name,
                                    toolResponseContent = toolOutput,
                                    tools = activeTools?.takeIf { it.isNotEmpty() },
                                    requestMeta =
                                        requestMeta.copy(
                                            transcriptLength = requestMeta.transcriptLength + toolOutput.length,
                                        ),
                                )
                            }
                        } else {
                            null
                        }

                    return HandledResponse(
                        result =
                            SummaryResult.ToolCall(
                                name = functionCall.name,
                                args = functionCall.argsAsStrings(),
                                rawArgs = functionCall.effectiveArgs,
                                continuation = continuation,
                            ),
                        responseRaw = rawBody,
                        finishReason = finishReason,
                        blockReason = blockReason,
                        usageMetadata = usageMetadata,
                    )
                }

                val rawText =
                    parts
                        .mapNotNull { it.text }
                        .joinToString("\n")
                        .trim()
                if (rawText.isBlank()) {
                    val errorResult =
                        if (finishReason in SAFETY_FINISH_REASONS) {
                            SummaryResult.Error.Generic("Gemini response blocked due to finishReason: $finishReason")
                        } else {
                            SummaryResult.Error.Generic("Empty text in Gemini summary response")
                        }
                    return HandledResponse(
                        result = errorResult,
                        responseRaw = rawBody,
                        finishReason = finishReason,
                        blockReason = blockReason,
                        usageMetadata = usageMetadata,
                    )
                }

                val sanitized = EmojiSanitizer.stripEmojis(rawText).trim()
                if (sanitized.isBlank()) {
                    return HandledResponse(
                        result = SummaryResult.Error.Generic("Gemini summary output is empty or sanitized to blank"),
                        responseRaw = rawBody,
                        finishReason = finishReason,
                        blockReason = blockReason,
                        usageMetadata = usageMetadata,
                    )
                }

                val candidateGrounding = candidate?.actualGroundingMetadata
                val citations =
                    candidateGrounding
                        ?.actualGroundingChunks
                        ?.mapNotNull { chunk ->
                            val web = chunk.web ?: return@mapNotNull null
                            val uri = web.uri?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val title = web.title?.trim()?.takeIf { it.isNotBlank() } ?: uri
                            GroundingCitation(title = title, url = uri)
                        }?.distinctBy { it.url }
                        ?.take(3) ?: emptyList()

                return HandledResponse(
                    result = SummaryResult.Success(text = sanitized, citations = citations),
                    responseRaw = rawBody,
                    finishReason = finishReason,
                    blockReason = blockReason,
                    usageMetadata = usageMetadata,
                )
            }
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        private val SAFETY_FINISH_REASONS =
            setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")

        private val jsonParser =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
                explicitNulls = false
            }

        private fun createDefaultHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                }
            }
    }
}

internal fun classifySummaryError(error: SummaryResult.Error): AiTraceFailureCategory =
    when (error) {
        is SummaryResult.Error.MissingApiKey -> {
            AiTraceFailureCategory.NOT_CONFIGURED
        }

        is SummaryResult.Error.RateLimitExceeded -> {
            AiTraceFailureCategory.RATE_LIMIT
        }

        is SummaryResult.Error.Timeout -> {
            AiTraceFailureCategory.TIMEOUT
        }

        is SummaryResult.Error.ApiError -> {
            AiTraceFailureCategory.HTTP_ERROR
        }

        is SummaryResult.Error.Generic -> {
            val msg = error.message
            when {
                error.cause is SerializationException ||
                    msg.contains("parse", ignoreCase = true) ||
                    msg.contains("Malformed tool call", ignoreCase = true) ||
                    msg.contains("Unknown tool call", ignoreCase = true) -> {
                    AiTraceFailureCategory.PARSE_ERROR
                }

                msg.contains("prompt blocked", ignoreCase = true) ||
                    msg.contains("blocked due to", ignoreCase = true) ||
                    msg.contains("SAFETY", ignoreCase = false) -> {
                    AiTraceFailureCategory.SAFETY_BLOCK
                }

                msg.contains("empty", ignoreCase = true) ||
                    msg.contains("No summary candidates", ignoreCase = true) -> {
                    AiTraceFailureCategory.EMPTY_OUTPUT
                }

                msg.contains("cannot be blank", ignoreCase = true) -> {
                    AiTraceFailureCategory.CONTEXT_ERROR
                }

                error.cause is HttpRequestTimeoutException ||
                    error.cause is SocketTimeoutException ||
                    error.cause is TimeoutCancellationException -> {
                    AiTraceFailureCategory.TIMEOUT
                }

                else -> {
                    AiTraceFailureCategory.UNEXPECTED_ERROR
                }
            }
        }
    }

internal fun classifySummaryException(e: Throwable): AiTraceFailureCategory =
    when (e) {
        is HttpRequestTimeoutException,
        is SocketTimeoutException,
        is TimeoutCancellationException,
        -> AiTraceFailureCategory.TIMEOUT

        is SerializationException -> AiTraceFailureCategory.PARSE_ERROR

        else -> AiTraceFailureCategory.UNEXPECTED_ERROR
    }
