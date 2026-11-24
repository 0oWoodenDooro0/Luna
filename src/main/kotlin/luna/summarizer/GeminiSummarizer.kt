package luna.summarizer

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceRedactor
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ContextUrlClassifier
import luna.core.ai.GeminiHttpClientFactory
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
    httpClient: HttpClient? = null,
    private val pollingDelayMs: Long = 2000L,
    private val maxPollingAttempts: Int = 30,
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
) : AutoCloseable {
    private val ownsHttpClient: Boolean = httpClient == null
    private val httpClient: HttpClient = httpClient ?: GeminiHttpClientFactory.create()
    private val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java)

    private val interactionClient =
        GeminiInteractionClient(
            httpClient = this.httpClient,
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
            return SummaryResult.Error.Generic(
                message = "Content to summarize cannot be blank",
                failureCategory = AiTraceFailureCategory.CONTEXT_ERROR,
            )
        }

        val isAgenticVideo =
            !input.videoUri.isNullOrBlank() &&
                GeminiPromptBuilder.resolveProcessingMode(input.videoDurationSeconds) == VideoProcessingMode.AGENTIC

        if (isAgenticVideo) {
            return summarizeAgentic(input)
        }

        if (lookbackProvider != null) {
            return summarizeWithLookback(input, lookbackProvider)
        }

        val initialTools = buildTools(input, includeLookback = false)
        val initialVariant = resolveVariant(initialTools)
        return runSyncInteraction(input, tools = initialTools, variant = initialVariant)
    }

    private suspend fun summarizeWithLookback(
        input: SummaryInput,
        lookbackProvider: suspend (reason: String) -> String?,
    ): SummaryResult {
        val tools = buildTools(input, includeLookback = true)
        val variant = resolveVariant(tools)
        val request =
            GeminiPromptBuilder.buildRequest(
                input = input,
                model = config.model,
                tools = tools,
                background = false,
            )
        val traceContext =
            InteractionTraceContext(
                webUrlCount = input.webUrls.size,
                inputUrls = collectInputUrls(input),
                imageCount = input.imageAttachments.size,
                hasImages = input.imageAttachments.isNotEmpty(),
                hasVideo = !input.videoUri.isNullOrBlank(),
                videoDurationSeconds = input.videoDurationSeconds,
                transcriptLength = input.content.length,
                stage = AiAttemptStage.SUMMARY_GENERATE,
                variant = variant,
                pipeline = AiTracePipeline.SUMMARY,
                trigger = AiTraceTrigger.REACTION,
            )
        val result =
            interactionClient.interactWithTools(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                request = request,
                traceContext = traceContext,
                maxTurns = 2,
                continuationStage = AiAttemptStage.SUMMARY_TOOL_CONTINUATION,
            ) { call ->
                if (call.name != GeminiTools.FETCH_EARLIER_MESSAGES) {
                    return@interactWithTools luna.core.ai.ToolExecutionOutcome.Abort(
                        message = "Unhandled tool call: ${call.name}",
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    )
                }
                val reason = call.argumentsAsStrings()["reason"]
                if (reason.isNullOrBlank()) {
                    logger.error("Gemini tool call '{}' missing required 'reason' argument", call.name)
                    return@interactWithTools luna.core.ai.ToolExecutionOutcome.Abort(
                        message = "Malformed tool call: missing required 'reason' parameter for ${call.name}",
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    )
                }
                logger.info("Model invoked autonomous lookback tool (reason={})", reason)
                val earlierContext =
                    try {
                        lookbackProvider(reason) ?: ConversationSliceCollector.NO_FURTHER_HISTORY_MESSAGE
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Lookback provider threw exception while fetching earlier messages", e)
                        return@interactWithTools luna.core.ai.ToolExecutionOutcome.Abort(
                            message = "Lookback provider failed: ${e.message}",
                            failureCategory = AiTraceFailureCategory.CONTEXT_ERROR,
                            cause = e,
                        )
                    }
                luna.core.ai.ToolExecutionOutcome
                    .Result(kotlinx.serialization.json.JsonPrimitive(earlierContext))
            }
        return mapInteractionResult(result)
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
        return mapInteractionResult(result)
    }

    private fun mapInteractionResult(result: InteractionExecutionResult): SummaryResult =
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
                logger.error(
                    "Unexpected function_call returned when lookback is disabled (interactionId={})",
                    result.interactionId,
                )
                SummaryResult.Error.Generic(
                    message = "Unexpected tool call requested by Gemini",
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                )
            }
        }

    override fun close() {
        if (ownsHttpClient) {
            httpClient.close()
        }
    }
}

internal fun classifySummaryError(error: SummaryResult.Error): AiTraceFailureCategory = error.failureCategory

internal fun classifySummaryException(e: Throwable): AiTraceFailureCategory =
    when (e) {
        is HttpRequestTimeoutException,
        is SocketTimeoutException,
        is TimeoutCancellationException,
        -> AiTraceFailureCategory.TIMEOUT

        is SerializationException -> AiTraceFailureCategory.PARSE_ERROR

        else -> AiTraceFailureCategory.UNEXPECTED_ERROR
    }
