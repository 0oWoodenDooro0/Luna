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
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ContextUrlClassifier
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionInput
import luna.core.ai.GeminiInteractionTool
import luna.core.ai.GeminiInteractionTools
import luna.core.ai.GeminiTools
import luna.core.ai.InteractionExecutionResult
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
            val result = executeSummarize(input, lookbackProvider, session)
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
        session: AiTraceSession?,
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

        if (lookbackProvider == null) {
            val initialTools = buildTools(input, includeLookback = false)
            val initialVariant = resolveVariant(initialTools)

            val initialResult =
                try {
                    runSyncInteraction(input, tools = initialTools, variant = initialVariant)
                } catch (e: Exception) {
                    if (input.imageAttachments.isNotEmpty()) {
                        logger.warn(
                            "Multimodal summarization with {} images threw exception ({}), retrying text-only",
                            input.imageAttachments.size,
                            e.message,
                        )
                        session?.markDegraded("Image summarization exception: ${e.message}")
                        return runSyncInteraction(
                            input = input.copy(imageAttachments = emptyList()),
                            tools = emptyList(),
                            variant = "no-images",
                        )
                    }
                    throw e
                }

            if (initialResult is SummaryResult.Error && input.imageAttachments.isNotEmpty()) {
                logger.warn(
                    "Multimodal summarization with {} images failed ({}), retrying text-only",
                    input.imageAttachments.size,
                    initialResult.message,
                )
                session?.markDegraded("Image summarization failed: ${initialResult.message}")
                return runSyncInteraction(
                    input = input.copy(imageAttachments = emptyList()),
                    tools = emptyList(),
                    variant = "no-images",
                )
            }

            return initialResult
        }

        val toolEnabledTools = buildTools(input, includeLookback = true)
        val toolVariant = resolveVariant(toolEnabledTools)
        val nonLookbackTools =
            buildTools(input, includeLookback = false).filterNot {
                it.type == GeminiInteractionTool.TYPE_FUNCTION && it.name == GeminiTools.FETCH_EARLIER_MESSAGES
            }

        val initialResult =
            try {
                runSyncInteraction(input, tools = toolEnabledTools, variant = toolVariant)
            } catch (e: Exception) {
                logger.warn("Tool-enabled summarization failed with exception, falling back to baseline", e)
                session?.markDegraded("Tool-enabled summarization exception: ${e.message}")
                return retryBaseline(
                    input = input,
                    tools = nonLookbackTools,
                    session = session,
                    initialVariant = "baseline",
                )
            }

        if (initialResult is SummaryResult.Error) {
            logger.warn("Tool-enabled summarization returned error ({}), falling back to baseline", initialResult.message)
            session?.markDegraded("Tool-enabled summarization error: ${initialResult.message}")
            return retryBaseline(
                input = input,
                tools = nonLookbackTools,
                session = session,
                initialVariant = "baseline",
            )
        }

        if (initialResult is SummaryResult.ToolCall) {
            if (initialResult.name == GeminiTools.FETCH_EARLIER_MESSAGES && initialResult.continuation != null) {
                val reason = initialResult.args["reason"] ?: ""
                logger.info("Model invoked autonomous lookback tool (reason={})", reason)
                val earlierContext =
                    try {
                        lookbackProvider(reason) ?: ConversationSliceCollector.NO_FURTHER_HISTORY_MESSAGE
                    } catch (e: Exception) {
                        logger.warn("Lookback provider threw exception, falling back to baseline summary", e)
                        session?.markDegraded("Lookback provider failed: ${e.message}")
                        return retryBaseline(
                            input = input,
                            tools = nonLookbackTools,
                            session = session,
                            initialVariant = "baseline",
                        )
                    }

                val lookbackResult =
                    try {
                        initialResult.continuation.invoke(earlierContext)
                    } catch (e: Exception) {
                        logger.warn("Tool continuation threw exception, falling back to baseline summary", e)
                        session?.markDegraded("Tool continuation exception: ${e.message}")
                        return retryBaseline(
                            input = input,
                            tools = nonLookbackTools,
                            session = session,
                            initialVariant = "baseline",
                        )
                    }

                return if (lookbackResult is SummaryResult.Success) {
                    lookbackResult
                } else {
                    logger.warn("Model failed or attempted multiple lookback turns after tool response, falling back to baseline")
                    session?.markDegraded("Lookback continuation did not succeed: $lookbackResult")
                    retryBaseline(
                        input = input,
                        tools = nonLookbackTools,
                        session = session,
                        initialVariant = "baseline",
                    )
                }
            } else {
                logger.warn("Unhandled tool call or missing continuation for {}, falling back to baseline", initialResult.name)
                session?.markDegraded("Unhandled tool call ${initialResult.name}")
                return retryBaseline(
                    input = input,
                    tools = nonLookbackTools,
                    session = session,
                    initialVariant = "baseline",
                )
            }
        }

        return initialResult
    }

    private suspend fun retryBaseline(
        input: SummaryInput,
        tools: List<GeminiInteractionTool>,
        session: AiTraceSession?,
        initialVariant: String = "baseline",
    ): SummaryResult =
        try {
            val res = runSyncInteraction(input, tools = tools, variant = initialVariant)
            if (res is SummaryResult.Error && input.imageAttachments.isNotEmpty()) {
                logger.warn("Baseline summarization with images failed ({}), retrying text-only", res.message)
                session?.markDegraded("Baseline with images failed: ${res.message}")
                runSyncInteraction(
                    input = input.copy(imageAttachments = emptyList()),
                    tools = emptyList(),
                    variant = "no-images",
                )
            } else {
                res
            }
        } catch (e: Exception) {
            if (input.imageAttachments.isNotEmpty()) {
                logger.warn("Baseline summarization threw exception ({}), retrying text-only", e.message)
                session?.markDegraded("Baseline exception with images: ${e.message}")
                runSyncInteraction(
                    input = input.copy(imageAttachments = emptyList()),
                    tools = emptyList(),
                    variant = "no-images",
                )
            } else {
                throw e
            }
        }

    private suspend fun summarizeAgentic(input: SummaryInput): SummaryResult {
        val systemInstruction = GeminiPromptBuilder.buildSystemInstruction(input)
        val userPrompt = GeminiPromptBuilder.buildUserPrompt(input)
        val tools = buildTools(input, includeLookback = false)
        logger.info("Executing agentic video summary via Interactions API (uri={})", input.videoUri)

        val result =
            interactionClient.interact(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                promptText = userPrompt,
                systemInstruction = systemInstruction,
                imageAttachments = input.imageAttachments,
                videoUri = input.videoUri,
                videoMimeType = "video/mp4",
                videoProcessingMode = VideoProcessingMode.AGENTIC,
                videoDurationSeconds = input.videoDurationSeconds,
                webUrlCount = input.webUrls.size,
                inputUrls = collectInputUrls(input),
                tools = tools,
                maxOutputTokens = 1024,
                background = true,
                stage = AiAttemptStage.AGENTIC_VIDEO,
                variant = null,
            )
        return mapInteractionResult(result, input, tools)
    }

    private suspend fun runSyncInteraction(
        input: SummaryInput,
        tools: List<GeminiInteractionTool>,
        variant: String,
    ): SummaryResult {
        val systemInstruction = GeminiPromptBuilder.buildSystemInstruction(input)
        val userPrompt = GeminiPromptBuilder.buildUserPrompt(input)
        val result =
            interactionClient.interact(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                promptText = userPrompt,
                systemInstruction = systemInstruction,
                imageAttachments = input.imageAttachments,
                videoUri = input.videoUri,
                videoMimeType = if (input.videoUri.isNullOrBlank()) null else "video/mp4",
                videoProcessingMode = GeminiPromptBuilder.resolveProcessingMode(input.videoDurationSeconds),
                videoDurationSeconds = input.videoDurationSeconds,
                webUrlCount = input.webUrls.size,
                inputUrls = collectInputUrls(input),
                tools = tools,
                maxOutputTokens = 1024,
                background = false,
                stage = AiAttemptStage.SUMMARY_GENERATE,
                variant = variant,
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
                SummaryResult.Error.RateLimitExceeded(
                    message = result.message,
                    retryAfterSeconds = result.retryAfterSeconds,
                )
            }

            is InteractionExecutionResult.Error.Timeout -> {
                SummaryResult.Error.Timeout(
                    message = result.message,
                    cause = result.cause,
                )
            }

            is InteractionExecutionResult.Error.ApiError -> {
                SummaryResult.Error.ApiError(
                    statusCode = result.statusCode,
                    message = result.message,
                )
            }

            is InteractionExecutionResult.Error.EmptyOutput -> {
                SummaryResult.Error.Generic(
                    message = result.message,
                    failureCategory = result.failureCategory,
                )
            }

            is InteractionExecutionResult.Error.SafetyBlock -> {
                SummaryResult.Error.Generic(
                    message = result.message,
                    failureCategory = result.failureCategory,
                )
            }

            is InteractionExecutionResult.Error.Generic -> {
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
            logger.warn("Gemini returned function_call step with missing function name")
            return SummaryResult.Error.Generic(
                message = "Malformed tool call: missing function name",
                failureCategory = AiTraceFailureCategory.PARSE_ERROR,
            )
        }

        val declaredToolNames =
            tools
                .filter { it.type == GeminiInteractionTool.TYPE_FUNCTION }
                .mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotBlank() } }
                .toSet()
        if (declaredToolNames.isNotEmpty() && !declaredToolNames.contains(functionCall.name)) {
            logger.warn("Gemini requested unknown tool '{}' which was not declared", functionCall.name)
            return SummaryResult.Error.Generic(
                message = "Unknown tool call requested by Gemini: ${functionCall.name}",
                failureCategory = AiTraceFailureCategory.PARSE_ERROR,
            )
        }

        val args = functionCall.argumentsAsStrings()
        if (functionCall.name == GeminiTools.FETCH_EARLIER_MESSAGES && args["reason"].isNullOrBlank()) {
            logger.warn("Gemini tool call '{}' missing required 'reason' argument", functionCall.name)
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
                    inputUrls = collectInputUrls(input),
                    webUrlCount = input.webUrls.size,
                    imageCount = input.imageAttachments.size,
                    hasVideo = !input.videoUri.isNullOrBlank(),
                    videoDurationSeconds = input.videoDurationSeconds,
                    transcriptLength = input.content.length + toolOutput.length,
                    stage = AiAttemptStage.SUMMARY_TOOL_CONTINUATION,
                    variant = functionCall.name,
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
