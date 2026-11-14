package luna.summarizer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceRedactor
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ContextUrlClassifier
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionInput
import luna.core.ai.GeminiInteractionTool
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.GeminiTools
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.InteractionTraceContext
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.VideoProcessingMode
import luna.core.ai.withActiveOrStandaloneSession
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException

/**
 * Service client for calling Google Gemini Interactions API (`/v1beta/interactions`)
 * to generate structured Traditional Chinese summaries with optional `url_context`,
 * `google_search` grounding, and single-turn `fetch_earlier_messages` lookback.
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

    private fun resolveVariant(tools: List<GeminiInteractionTool>): String {
        val hasUrlContext = tools.any { it.type == GeminiInteractionTool.TYPE_URL_CONTEXT }
        val hasSearch = tools.any { it.type == GeminiInteractionTool.TYPE_GOOGLE_SEARCH }
        val hasLookback =
            tools.any { tool ->
                tool.type == GeminiInteractionTool.TYPE_FUNCTION && tool.name == GeminiTools.FETCH_EARLIER_MESSAGES
            }
        val parts =
            buildList {
                if (hasUrlContext) add("url_context")
                if (hasSearch) add("grounded")
                if (hasLookback) add("lookback")
            }
        return if (parts.isEmpty()) "baseline" else parts.joinToString("+")
    }

    private fun buildTools(
        input: SummaryInput,
        includeLookback: Boolean,
    ): List<GeminiInteractionTool> {
        input.tools?.let { return it }
        val tools = mutableListOf<GeminiInteractionTool>()
        if (config.urlContextEnabled && input.webUrls.isNotEmpty()) {
            tools.add(GeminiInteractionTools.URL_CONTEXT)
        }
        if (config.searchGroundingEnabled) {
            tools.add(GeminiInteractionTools.GOOGLE_SEARCH)
        }
        if (includeLookback) {
            tools.add(GeminiInteractionTools.FETCH_EARLIER_MESSAGES)
        }
        return tools
    }

    private fun collectInputUrls(input: SummaryInput): List<String> =
        buildList {
            addAll(input.originalUrls)
            addAll(input.webUrls)
            input.url?.takeIf { it.isNotBlank() }?.let { add(it) }
            input.videoUri?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(ContextUrlClassifier.extractUrls(input.content))
        }.distinct()

    open suspend fun summarize(
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

    open suspend fun summarize(
        input: SummaryInput,
        lookbackProvider: (suspend (reason: String) -> String?)? = null,
    ): SummaryResult =
        traceRecorder.withActiveOrStandaloneSession(
            pipeline = AiTracePipeline.SUMMARY,
            trigger = AiTraceTrigger.REACTION,
            discord =
                AiTraceDiscordContext(
                    channelId = "standalone",
                    messageId = "standalone",
                ),
            secret = config.apiKey,
            onStandaloneException = { session, e ->
                val localRedactor = AiTraceRedactor(listOfNotNull(config.apiKey.takeIf { it.isNotBlank() }))
                session.fail(
                    failureCategory = classifySummaryException(e),
                    errorSummary = localRedactor.redactString(e.message) ?: e::class.simpleName ?: "Unexpected error",
                )
            },
        ) { session, isStandalone ->
            val result = executeSummarize(input, lookbackProvider)
            if (isStandalone && session != null) {
                when (result) {
                    is SummaryResult.Success -> {
                        session.complete(
                            finalOutput = result.text,
                            citations = result.citations,
                        )
                    }

                    is SummaryResult.ToolCall -> {
                        session.complete(finalOutput = null, citations = emptyList())
                    }

                    is SummaryResult.Error -> {
                        val localRedactor = AiTraceRedactor(listOfNotNull(config.apiKey.takeIf { it.isNotBlank() }))
                        session.fail(
                            failureCategory = classifySummaryError(result),
                            errorSummary = localRedactor.redactString(result.message),
                        )
                    }
                }
            }
            result
        }

    private suspend fun executeSummarize(
        input: SummaryInput,
        lookbackProvider: (suspend (reason: String) -> String?)?,
    ): SummaryResult {
        if (!config.isConfigured) {
            logger.warn("Skipping Gemini summarization: GEMINI_API_KEY is not configured")
            return SummaryResult.Error.MissingApiKey()
        }

        if (input.content.isBlank()) {
            logger.warn("Skipping Gemini summarization: content is blank")
            return SummaryResult.Error.Generic("Content to summarize cannot be blank")
        }

        val isAgenticVideo =
            !input.videoUri.isNullOrBlank() &&
                GeminiPromptBuilder.resolveProcessingMode(input.videoDurationSeconds) == VideoProcessingMode.AGENTIC

        if (isAgenticVideo) {
            return summarizeAgentic(input)
        }

        val initialTools = buildTools(input, includeLookback = lookbackProvider != null)
        val initialVariant = resolveVariant(initialTools)
        val initialResult = runSyncInteraction(input, tools = initialTools, variant = initialVariant)

        if (lookbackProvider == null) {
            return initialResult
        }

        if (initialResult is SummaryResult.ToolCall) {
            if (initialResult.name == GeminiTools.FETCH_EARLIER_MESSAGES && initialResult.continuation != null) {
                val reason = initialResult.args["reason"] ?: ""
                logger.info("Model invoked autonomous lookback tool (reason={})", reason)
                val earlierContext =
                    try {
                        lookbackProvider(reason) ?: ConversationSliceCollector.NO_FURTHER_HISTORY_MESSAGE
                    } catch (e: Exception) {
                        logger.error("Lookback provider threw exception while fetching earlier messages", e)
                        return SummaryResult.Error.Generic(
                            message = "Lookback provider failed: ${e.message}",
                            cause = e,
                            failureCategory = AiTraceFailureCategory.CONTEXT_ERROR,
                        )
                    }

                val lookbackResult =
                    try {
                        initialResult.continuation.invoke(earlierContext)
                    } catch (e: Exception) {
                        logger.error("Tool continuation threw exception", e)
                        return SummaryResult.Error.Generic(
                            message = "Tool continuation failed: ${e.message}",
                            cause = e,
                            failureCategory = classifySummaryException(e),
                        )
                    }

                return when (lookbackResult) {
                    is SummaryResult.Success -> {
                        lookbackResult
                    }

                    is SummaryResult.ToolCall -> {
                        logger.error("Model attempted multiple lookback turns after tool response")
                        SummaryResult.Error.Generic(
                            message = "Model attempted multiple lookback turns after tool response",
                            failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                        )
                    }

                    is SummaryResult.Error -> {
                        lookbackResult
                    }
                }
            } else {
                logger.error("Unhandled tool call or missing continuation for {}", initialResult.name)
                return SummaryResult.Error.Generic(
                    message = "Unhandled tool call or missing continuation for ${initialResult.name}",
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                )
            }
        }

        return initialResult
    }

    private suspend fun summarizeAgentic(input: SummaryInput): SummaryResult {
        val tools = buildTools(input, includeLookback = false)
        logger.info("Executing agentic video summary via Interactions API (uri={})", input.videoUri)
        return executeInteraction(
            input = input,
            tools = tools,
            background = true,
            stage = AiAttemptStage.AGENTIC_VIDEO,
            variant = null,
        )
    }

    private suspend fun runSyncInteraction(
        input: SummaryInput,
        tools: List<GeminiInteractionTool>,
        variant: String,
    ): SummaryResult =
        executeInteraction(
            input = input,
            tools = tools,
            background = false,
            stage = AiAttemptStage.SUMMARY_GENERATE,
            variant = variant,
        )

    private suspend fun executeInteraction(
        input: SummaryInput,
        tools: List<GeminiInteractionTool>,
        background: Boolean,
        stage: AiAttemptStage,
        variant: String?,
    ): SummaryResult {
        val request =
            GeminiPromptBuilder.buildRequest(
                input = input,
                model = config.model,
                tools = tools,
                background = background,
            )
        val result =
            interactionClient.interact(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                request = request,
                traceContext =
                    InteractionTraceContext(
                        webUrlCount = input.webUrls.size,
                        inputUrls = collectInputUrls(input),
                        videoDurationSeconds = input.videoDurationSeconds,
                        stage = stage,
                        variant = variant,
                        pipeline = AiTracePipeline.SUMMARY,
                        trigger = AiTraceTrigger.REACTION,
                    ),
            )
        return mapInteractionResult(result, input, tools)
    }

    private fun mapInteractionResult(
        result: InteractionExecutionResult,
        input: SummaryInput,
        tools: List<GeminiInteractionTool>,
    ): SummaryResult =
        when (result) {
            is InteractionExecutionResult.Success -> {
                SummaryResult.Success(
                    text = result.text,
                    citations = result.citations,
                )
            }

            is InteractionExecutionResult.Error.RateLimitExceeded -> {
                logger.error("Gemini Interactions API rate limited: {}", result.message)
                SummaryResult.Error.RateLimitExceeded(
                    message = result.message,
                    retryAfterSeconds = result.retryAfterSeconds,
                )
            }

            is InteractionExecutionResult.Error.Timeout -> {
                logger.error("Gemini Interactions API timed out: {}", result.message)
                SummaryResult.Error.Timeout(
                    message = result.message,
                    cause = result.cause,
                )
            }

            is InteractionExecutionResult.Error.ApiError -> {
                logger.error("Gemini Interactions API error (status={}): {}", result.statusCode, result.message)
                SummaryResult.Error.ApiError(
                    statusCode = result.statusCode,
                    message = result.message,
                )
            }

            is InteractionExecutionResult.Error.EmptyOutput -> {
                logger.error("Gemini Interactions API returned empty output: {}", result.message)
                SummaryResult.Error.Generic(
                    message = result.message,
                    failureCategory = result.failureCategory,
                )
            }

            is InteractionExecutionResult.Error.SafetyBlock -> {
                logger.error("Gemini Interactions API blocked output due to safety: {}", result.message)
                SummaryResult.Error.Generic(
                    message = result.message,
                    failureCategory = result.failureCategory,
                )
            }

            is InteractionExecutionResult.Error.Generic -> {
                logger.error("Gemini Interactions API failed: {}", result.message, result.cause)
                SummaryResult.Error.Generic(
                    message = result.message,
                    cause = result.cause,
                    failureCategory = result.failureCategory,
                )
            }

            is InteractionExecutionResult.FunctionCall -> {
                mapFunctionCall(result, input, tools)
            }
        }

    private fun mapFunctionCall(
        result: InteractionExecutionResult.FunctionCall,
        input: SummaryInput,
        tools: List<GeminiInteractionTool>,
    ): SummaryResult {
        val functionCall = result.functionCalls.firstOrNull()
        if (functionCall == null || functionCall.name.isBlank()) {
            logger.error("Gemini returned function_call step with missing function name")
            return SummaryResult.Error.Generic(
                message = "Malformed tool call: missing function name",
                failureCategory = AiTraceFailureCategory.PARSE_ERROR,
            )
        }
        if (functionCall.id.isBlank()) {
            logger.error("Gemini tool call '{}' missing required 'call_id'", functionCall.name)
            return SummaryResult.Error.Generic(
                message = "Malformed tool call: missing call_id for ${functionCall.name}",
                failureCategory = AiTraceFailureCategory.PARSE_ERROR,
            )
        }

        val declaredToolNames =
            tools
                .filter { it.type == GeminiInteractionTool.TYPE_FUNCTION }
                .mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotBlank() } }
                .toSet()
        if (declaredToolNames.isNotEmpty() && !declaredToolNames.contains(functionCall.name)) {
            logger.error("Gemini requested unknown tool '{}' which was not declared", functionCall.name)
            return SummaryResult.Error.Generic(
                message = "Unknown tool call requested by Gemini: ${functionCall.name}",
                failureCategory = AiTraceFailureCategory.PARSE_ERROR,
            )
        }

        val args = functionCall.argumentsAsStrings()
        if (functionCall.name == GeminiTools.FETCH_EARLIER_MESSAGES && args["reason"].isNullOrBlank()) {
            logger.error("Gemini tool call '{}' missing required 'reason' argument", functionCall.name)
            return SummaryResult.Error.Generic(
                message = "Malformed tool call: missing required 'reason' parameter for ${functionCall.name}",
                failureCategory = AiTraceFailureCategory.PARSE_ERROR,
            )
        }

        val continuation: suspend (String) -> SummaryResult = { toolOutput ->
            val contResult =
                interactionClient.continueInteraction(
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = config.model,
                    previousInteractionId = result.interactionId,
                    functionResults =
                        listOf(
                            GeminiInteractionInput.functionResult(
                                name = functionCall.name,
                                callId = functionCall.id,
                                result = toolOutput,
                            ),
                        ),
                    systemInstruction = GeminiPromptBuilder.buildSystemInstruction(input),
                    tools = tools,
                    maxOutputTokens = 1024,
                    background = false,
                    traceContext =
                        InteractionTraceContext(
                            inputUrls = collectInputUrls(input),
                            webUrlCount = input.webUrls.size,
                            imageCount = input.imageAttachments.size,
                            hasImages = input.imageAttachments.isNotEmpty(),
                            hasVideo = !input.videoUri.isNullOrBlank(),
                            videoDurationSeconds = input.videoDurationSeconds,
                            transcriptLength = input.content.length + toolOutput.length,
                            stage = AiAttemptStage.SUMMARY_TOOL_CONTINUATION,
                            variant = functionCall.name,
                            pipeline = AiTracePipeline.SUMMARY,
                            trigger = AiTraceTrigger.REACTION,
                        ),
                )
            mapInteractionResult(contResult, input, tools)
        }

        return SummaryResult.ToolCall(
            name = functionCall.name,
            args = args,
            rawArgs = functionCall.arguments,
            continuation = continuation,
        )
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
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
            error.failureCategory?.let { return it }
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
