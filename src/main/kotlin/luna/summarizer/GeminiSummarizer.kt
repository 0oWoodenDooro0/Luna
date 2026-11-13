package luna.summarizer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.serialization.json.Json
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
import luna.core.ai.GroundingCitation
import luna.core.ai.VideoProcessingMode
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
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(GeminiSummarizer::class.java)
    private val interactionClient =
        GeminiInteractionClient(
            httpClient = httpClient,
            pollingDelayMs = pollingDelayMs,
            maxPollingAttempts = maxPollingAttempts,
        )

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
            summarizeStatic(input, tools)
        }
    }

    open suspend fun summarize(
        input: SummaryInput,
        lookbackProvider: (suspend (reason: String) -> String?)? = null,
    ): SummaryResult {
        if (!config.isConfigured) {
            return SummaryResult.Error.MissingApiKey()
        }
        if (input.content.isBlank()) {
            return SummaryResult.Error.Generic("Content to summarize cannot be blank")
        }

        val activeSearchTools = if (config.searchGroundingEnabled) listOf(GeminiTools.GOOGLE_SEARCH_TOOL) else emptyList()

        if (lookbackProvider == null) {
            val initialTools = input.tools ?: activeSearchTools.takeIf { it.isNotEmpty() }
            val res =
                try {
                    summarizeDirect(input, tools = initialTools)
                } catch (e: Exception) {
                    if (initialTools != null && input.tools == null) {
                        logger.warn("Search grounding failed with exception: {}. Retrying baseline without search.", e.message)
                        retryBaseline(input.copy(tools = null))
                    } else if (input.imageAttachments.isNotEmpty()) {
                        logger.warn("Multimodal summarization failed with exception: {}. Retrying text baseline without images.", e.message)
                        retryBaseline(input.copy(imageAttachments = emptyList(), tools = null))
                    } else {
                        throw e
                    }
                }
            return if (res is SummaryResult.Error) {
                if (initialTools != null && input.tools == null) {
                    logger.warn("Search grounding returned error: {}. Retrying baseline without search.", res.message)
                    retryBaseline(input.copy(tools = null))
                } else if (input.imageAttachments.isNotEmpty()) {
                    logger.warn("Multimodal summarization returned error: {}. Retrying text baseline without images.", res.message)
                    retryBaseline(input.copy(imageAttachments = emptyList(), tools = null))
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

        var result: SummaryResult =
            try {
                summarizeDirect(toolEnabledInput, tools = toolEnabledInput.tools)
            } catch (e: Exception) {
                logger.warn("Tool-enabled summarization failed: {}. Retrying baseline fallback.", e.message)
                retryBaseline(baselineInput)
            }

        if (result is SummaryResult.Error) {
            logger.warn("Tool-enabled summarization returned error: {}. Retrying baseline fallback.", result.message)
            result = retryBaseline(baselineInput)
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
                            null
                        }

                    if (toolOutput != null) {
                        val lookbackResult =
                            try {
                                continuation(toolOutput)
                            } catch (e: Exception) {
                                logger.warn("Lookback continuation failed: {}", e.message)
                                null
                            }

                        if (lookbackResult is SummaryResult.Success) {
                            result = lookbackResult
                        } else {
                            logger.warn("Lookback continuation returned non-success: {}. Falling back to baseline.", lookbackResult)
                            result = retryBaseline(baselineInput)
                        }
                    } else {
                        result = retryBaseline(baselineInput)
                    }
                } else {
                    logger.warn("No continuation provided for tool call, falling back to baseline")
                    result = retryBaseline(baselineInput)
                }
            } else {
                logger.warn("Unhandled tool call {}, falling back to baseline", result.name)
                result = retryBaseline(baselineInput)
            }
        }

        if (result is SummaryResult.Error && input.imageAttachments.isNotEmpty()) {
            logger.warn("Multimodal summarization failed: {}. Retrying text-only fallback.", result.message)
            result = retryBaseline(baselineInput.copy(imageAttachments = emptyList()))
        }

        return result
    }

    private suspend fun retryBaseline(input: SummaryInput): SummaryResult =
        try {
            val res = summarizeDirect(input, tools = null)
            if (res is SummaryResult.Error && input.imageAttachments.isNotEmpty()) {
                logger.warn("Baseline retry with images failed: {}. Retrying text-only fallback.", res.message)
                summarizeDirect(input.copy(imageAttachments = emptyList()), tools = null)
            } else {
                res
            }
        } catch (e: Exception) {
            logger.warn("Baseline retry encountered exception: {}. Attempting fallback.", e.message, e)
            if (input.imageAttachments.isNotEmpty()) {
                try {
                    logger.warn("Retrying text-only fallback after baseline exception: {}", e.message)
                    summarizeDirect(input.copy(imageAttachments = emptyList()), tools = null)
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
    ): SummaryResult {
        val endpoint =
            "${config.cleanBaseUrl}/v1beta/models/${config.cleanModel}:generateContent?key=${config.apiKey}"

        return try {
            val response: HttpResponse =
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestPayload)
                }

            handleResponse(
                response = response,
                originalRequest = requestPayload,
                activeTools = activeTools,
            )
        } catch (e: HttpRequestTimeoutException) {
            SummaryResult.Error.Timeout("Gemini API request timed out: ${e.message}", cause = e)
        } catch (e: SocketTimeoutException) {
            SummaryResult.Error.Timeout("Gemini API socket timed out: ${e.message}", cause = e)
        } catch (e: TimeoutCancellationException) {
            SummaryResult.Error.Timeout("Gemini API coroutine timed out: ${e.message}", cause = e)
        } catch (e: Exception) {
            SummaryResult.Error.Generic("Unexpected error invoking Gemini API: ${e.message}", cause = e)
        }
    }

    private suspend fun summarizeStatic(
        input: SummaryInput,
        tools: List<GeminiTool>? = input.tools,
    ): SummaryResult {
        val requestPayload = GeminiPromptBuilder.buildRequest(input, tools)
        return postGenerateContent(requestPayload, tools)
    }

    private suspend fun executeToolResponse(
        previousRequest: GeminiGenerateContentRequest,
        modelContent: GeminiContent,
        toolName: String,
        toolResponseContent: String,
        tools: List<GeminiTool>? = null,
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

        return postGenerateContent(nextRequest, tools)
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
    ): SummaryResult {
        when {
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                return SummaryResult.Error.RateLimitExceeded(
                    message = "Gemini API rate limit exceeded (HTTP 429)",
                    retryAfterSeconds = retryAfter,
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
                return SummaryResult.Error.ApiError(
                    statusCode = response.status.value,
                    message = "Gemini API error (${response.status.value}): $message",
                )
            }

            else -> {
                val parsedResponse =
                    try {
                        response.body<GeminiGenerateContentResponse>()
                    } catch (_: Exception) {
                        val rawBody = response.bodyAsText()
                        jsonParser.decodeFromString<GeminiGenerateContentResponse>(rawBody)
                    }

                if (parsedResponse.error != null) {
                    return SummaryResult.Error.ApiError(
                        statusCode = parsedResponse.error.code ?: response.status.value,
                        message = parsedResponse.error.message ?: "Unknown API error",
                    )
                }

                val promptFeedback = parsedResponse.promptFeedback
                if (promptFeedback?.blockReason != null) {
                    return SummaryResult.Error.Generic(
                        "Gemini prompt blocked due to: ${promptFeedback.blockReason}",
                    )
                }

                val candidates = parsedResponse.candidates
                if (candidates.isNullOrEmpty()) {
                    return SummaryResult.Error.Generic("No summary candidates returned by Gemini")
                }

                val candidate = candidates.firstOrNull()
                val candidateContent = candidate?.content
                val parts = candidateContent?.parts ?: emptyList()

                val functionCallPart = parts.firstOrNull { it.actualFunctionCall != null }
                val functionCall = functionCallPart?.actualFunctionCall

                if (functionCall != null) {
                    if (functionCall.name.isBlank()) {
                        return SummaryResult.Error.Generic("Malformed tool call: missing function name")
                    }

                    val declaredToolNames =
                        activeTools
                            ?.flatMap { it.actualFunctionDeclarations }
                            ?.map { it.name }
                            ?.toSet()

                    if (declaredToolNames != null && declaredToolNames.isNotEmpty() && !declaredToolNames.contains(functionCall.name)) {
                        return SummaryResult.Error.Generic("Unknown tool call requested by Gemini: ${functionCall.name}")
                    }

                    if (functionCall.name == GeminiTools.FETCH_EARLIER_MESSAGES) {
                        val reason = functionCall.argsAsStrings()["reason"]
                        if (reason.isNullOrBlank()) {
                            return SummaryResult.Error.Generic(
                                "Malformed tool call: missing required 'reason' parameter for ${functionCall.name}",
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
                                )
                            }
                        } else {
                            null
                        }

                    return SummaryResult.ToolCall(
                        name = functionCall.name,
                        args = functionCall.argsAsStrings(),
                        rawArgs = functionCall.effectiveArgs,
                        continuation = continuation,
                    )
                }

                val rawText =
                    parts
                        .mapNotNull { it.text }
                        .joinToString("\n")
                        .trim()
                if (rawText.isBlank()) {
                    return SummaryResult.Error.Generic("Empty text in Gemini summary response")
                }

                val sanitized = EmojiSanitizer.stripEmojis(rawText).trim()
                if (sanitized.isBlank()) {
                    return SummaryResult.Error.Generic("Gemini summary output is empty or sanitized to blank")
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

                return SummaryResult.Success(text = sanitized, citations = citations)
            }
        }
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
