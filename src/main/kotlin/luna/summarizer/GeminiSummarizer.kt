package luna.summarizer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
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

        val effectiveLookbackProvider = lookbackProvider ?: input.lookbackProvider
        if (effectiveLookbackProvider == null) {
            val res =
                try {
                    summarizeDirect(input, tools = input.tools)
                } catch (e: Exception) {
                    if (input.imageAttachments.isNotEmpty()) {
                        logger.warn("Multimodal summarization failed with exception: {}. Retrying text-only fallback.", e.message)
                        retryBaseline(input.copy(imageAttachments = emptyList(), tools = null))
                    } else {
                        throw e
                    }
                }
            return if (res is SummaryResult.Error && input.imageAttachments.isNotEmpty()) {
                logger.warn("Multimodal summarization failed: {}. Retrying text-only fallback.", res.message)
                retryBaseline(input.copy(imageAttachments = emptyList(), tools = null))
            } else {
                res
            }
        }

        val baselineInput = input.copy(tools = null)
        val toolEnabledInput = input.copy(tools = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL))

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
                            effectiveLookbackProvider(reason) ?: ConversationSliceCollector.NO_FURTHER_HISTORY_MESSAGE
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
                summarizeDirect(input.copy(imageAttachments = emptyList()), tools = null)
            } else {
                res
            }
        } catch (e: Exception) {
            if (input.imageAttachments.isNotEmpty()) {
                try {
                    summarizeDirect(input.copy(imageAttachments = emptyList()), tools = null)
                } catch (e2: Exception) {
                    SummaryResult.Error.Generic("Baseline fallback failed: ${e2.message}", cause = e2)
                }
            } else {
                SummaryResult.Error.Generic("Baseline fallback failed: ${e.message}", cause = e)
            }
        }

    private suspend fun summarizeStatic(
        input: SummaryInput,
        tools: List<GeminiTool>? = input.tools,
    ): SummaryResult {
        val requestPayload = GeminiPromptBuilder.buildRequest(input, tools)
        val endpoint = "${config.baseUrl.trimEnd('/')}/v1beta/models/${config.model}:generateContent?key=${config.apiKey}"

        return try {
            val response: HttpResponse =
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestPayload)
                }

            handleResponse(
                response = response,
                originalRequest = requestPayload,
                activeTools = tools,
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

    suspend fun executeToolResponse(
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

        val endpoint =
            "${config.baseUrl.trimEnd('/')}/v1beta/models/${config.model}:generateContent?key=${config.apiKey}"

        return try {
            val response: HttpResponse =
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(nextRequest)
                }

            handleResponse(
                response = response,
                originalRequest = nextRequest,
                activeTools = tools,
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

    suspend fun summarizeWithTools(
        input: SummaryInput,
        tools: List<GeminiTool> = listOf(GeminiTools.FETCH_EARLIER_MESSAGES_TOOL),
        maxTurns: Int = 3,
        toolExecutor: suspend (name: String, args: Map<String, String>) -> String,
    ): SummaryResult {
        var result = summarizeDirect(input = input, tools = tools)
        var turn = 0

        while (result is SummaryResult.ToolCall && turn < maxTurns) {
            val toolCall = result
            val continuation =
                toolCall.continuation
                    ?: return SummaryResult.Error.Generic(
                        "No continuation available for tool call: ${toolCall.name}",
                    )

            val toolOutput =
                try {
                    toolExecutor(toolCall.name, toolCall.args)
                } catch (e: Exception) {
                    return SummaryResult.Error.Generic(
                        "Error executing tool '${toolCall.name}': ${e.message}",
                        cause = e,
                    )
                }

            result = continuation(toolOutput)
            turn++
        }

        if (result is SummaryResult.ToolCall) {
            return SummaryResult.Error.Generic("Exceeded maximum tool call turns ($maxTurns)")
        }

        return result
    }

    private suspend fun summarizeAgentic(input: SummaryInput): SummaryResult {
        val userPrompt = GeminiPromptBuilder.buildUserPrompt(input)
        val systemInstruction = GeminiPromptBuilder.buildSystemInstruction(input)
        val promptText = "$systemInstruction\n\n---\n\n$userPrompt"
        val endpoint = "${config.baseUrl.trimEnd('/')}/v1beta/interactions?key=${config.apiKey}"
        val requestPayload =
            GeminiInteractionRequest(
                model = config.model,
                input =
                    listOf(
                        GeminiInteractionInput(
                            type = "video",
                            uri = input.videoUri,
                            mimeType = "video/mp4",
                            processing = "agentic",
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
                return SummaryResult.Error.RateLimitExceeded(
                    message = "Gemini API rate limit exceeded (HTTP 429)",
                    retryAfterSeconds = retryAfter,
                )
            }
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                return SummaryResult.Error.ApiError(
                    statusCode = response.status.value,
                    message = "Gemini API error (${response.status.value}): $errorBody",
                )
            }

            val interaction = response.body<GeminiInteractionResponse>()
            val interactionId =
                interaction.id
                    ?: return SummaryResult.Error.Generic("No interaction ID returned from Gemini")

            pollInteraction(interactionId)
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

    private suspend fun pollInteraction(id: String): SummaryResult {
        val endpoint = "${config.baseUrl.trimEnd('/')}/v1beta/interactions/$id?key=${config.apiKey}"
        var attempts = 0

        while (attempts < maxPollingAttempts) {
            attempts++
            val response = httpClient.get(endpoint)
            if (response.status == HttpStatusCode.TooManyRequests) {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                return SummaryResult.Error.RateLimitExceeded(
                    message = "Gemini API rate limit exceeded (HTTP 429)",
                    retryAfterSeconds = retryAfter,
                )
            }
            if (!response.status.isSuccess()) {
                return SummaryResult.Error.ApiError(
                    statusCode = response.status.value,
                    message = "Error polling interaction: ${response.status.value}",
                )
            }

            val statusResponse = response.body<GeminiInteractionResponse>()
            val rawOutput = statusResponse.extractOutputText()
            if (statusResponse.status == "completed" || rawOutput.isNotBlank()) {
                val sanitized = EmojiSanitizer.stripEmojis(rawOutput).trim()
                if (sanitized.isBlank()) {
                    return SummaryResult.Error.Generic("Gemini summary output is empty or sanitized to blank")
                }
                return SummaryResult.Success(text = sanitized)
            }
            if (statusResponse.status == "failed") {
                val errorMsg = statusResponse.error?.message ?: "Agentic video processing failed"
                return SummaryResult.Error.Generic(errorMsg)
            }

            delay(pollingDelayMs)
        }

        return SummaryResult.Error.Timeout("Agentic video interaction timed out after $maxPollingAttempts attempts")
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
                                    tools = null,
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
                return SummaryResult.Success(text = sanitized)
            }
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val FETCH_EARLIER_MESSAGES = GeminiTools.FETCH_EARLIER_MESSAGES
        val FETCH_EARLIER_MESSAGES_TOOL = GeminiTools.FETCH_EARLIER_MESSAGES_TOOL

        private val jsonParser =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
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
