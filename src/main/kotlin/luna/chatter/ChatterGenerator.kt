package luna.chatter

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
import kotlinx.serialization.json.Json
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptRequest
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.GeminiFinishReasons
import luna.core.ai.GeminiGenerateContentRequest
import luna.core.ai.GeminiGenerateContentResponse
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionResult
import luna.core.ai.GeminiTool
import luna.core.ai.GeminiTools
import luna.core.ai.GeminiUsageMetadata
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.elapsedMillisFromNanos
import luna.core.ai.withActiveOrStandaloneSession
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException

/**
 * AI client service that formats multimodal dialogue context, applies Luna's community persona,
 * enforces the Tiered Video Latency Policy, invokes Google Gemini API with Search Grounding,
 * performs sequential observable degraded fallbacks, and enforces Discord's 2,000-character bound.
 */
open class ChatterGenerator(
    val config: ChatterConfig = ChatterConfig.fromEnv(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val pollingDelayMs: Long = GeminiInteractionClient.DEFAULT_POLLING_DELAY_MS,
    private val maxPollingAttempts: Int = GeminiInteractionClient.DEFAULT_MAX_POLLING_ATTEMPTS,
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ChatterGenerator::class.java)
    private val interactionClient =
        GeminiInteractionClient(
            httpClient = httpClient,
            pollingDelayMs = pollingDelayMs,
            maxPollingAttempts = maxPollingAttempts,
            traceRecorder = traceRecorder,
        )

    private sealed class GenerateAttempt {
        data class Success(
            val text: String,
        ) : GenerateAttempt()

        data class Failure(
            val reason: String,
            val failureCategory: AiTraceFailureCategory,
            val cause: Throwable? = null,
        ) : GenerateAttempt()
    }

    private data class EvaluatedGenerateResponse(
        val outcome: GenerateAttempt,
        val finishReason: String? = null,
        val blockReason: String? = null,
        val usageMetadata: GeminiUsageMetadata? = null,
    )

    /**
     * Generates a conversational community-style reply from multimodal [ChatterInput].
     *
     * @param input Unified multimodal dialogue, image, video, and link context.
     * @return Concise organic reply strictly bounded to 2,000 characters, or null on failure.
     */
    open suspend fun generateReply(input: ChatterInput): String? =
        traceRecorder.withActiveOrStandaloneSession(
            pipeline = AiTracePipeline.CHATTER,
            trigger = if (input.isDirectMention) AiTraceTrigger.MENTION else AiTraceTrigger.AMBIENT,
            discord =
                AiTraceDiscordContext(
                    channelId = "standalone",
                    messageId = "standalone",
                    isDirectMention = input.isDirectMention,
                ),
            secret = config.apiKey,
        ) { session, isStandalone ->
            executeGenerateReply(input, session, isStandalone = isStandalone)
        }

    private suspend fun executeGenerateReply(
        input: ChatterInput,
        session: AiTraceSession?,
        isStandalone: Boolean,
    ): String? {
        if (!config.isConfigured) {
            logger.warn("ChatterGenerator cannot proceed: Gemini API key is not configured")
            session?.fail(
                AiTraceFailureCategory.NOT_CONFIGURED,
                "GEMINI_API_KEY is not configured",
            )
            return null
        }
        if (input.transcript.isBlank() && input.imageAttachments.isEmpty() && input.videoUri.isNullOrBlank()) {
            logger.debug("ChatterGenerator received blank dialogue input, returning null")
            session?.fail(
                AiTraceFailureCategory.CONTEXT_ERROR,
                "ChatterGenerator received blank dialogue input",
            )
            return null
        }

        val rawVideoUri = input.videoUri?.trim()?.takeIf { it.isNotEmpty() }
        val duration = input.videoDurationSeconds

        val replyText =
            if (rawVideoUri != null) {
                when {
                    duration != null && duration > MAX_VIDEO_DURATION_SECONDS -> {
                        logger.warn(
                            "YouTube video duration {}s exceeds 2-hour cap ({}s) for {}; omitting videoUri and proceeding with transcript metadata/comments",
                            duration,
                            MAX_VIDEO_DURATION_SECONDS,
                            rawVideoUri,
                        )
                        generateWithFallback(input.copy(videoUri = null), session)
                    }

                    duration != null && duration > MAX_STATIC_VIDEO_DURATION_SECONDS -> {
                        if (input.isDirectMention) {
                            val agenticReply = generateAgenticVideoReply(input.copy(videoUri = rawVideoUri))
                            if (agenticReply != null) {
                                agenticReply
                            } else {
                                session?.markDegraded(
                                    "Agentic video interaction failed; degrading to generateContent without videoUri",
                                )
                                generateWithFallback(input.copy(videoUri = null), session)
                            }
                        } else {
                            logger.info(
                                "Ambient Chatter on long YouTube video ({}s > {}s) omits videoUri to preserve low latency; relying on transcript metadata/comments",
                                duration,
                                MAX_STATIC_VIDEO_DURATION_SECONDS,
                            )
                            generateWithFallback(input.copy(videoUri = null), session)
                        }
                    }

                    else -> {
                        generateWithFallback(input.copy(videoUri = rawVideoUri), session)
                    }
                }
            } else {
                generateWithFallback(input.copy(videoUri = null), session)
            }

        if (isStandalone && replyText != null) {
            session?.complete(finalOutput = replyText)
        }
        return replyText
    }

    /**
     * Generates a conversational community-style reply from a chronological transcript string.
     *
     * @param transcript Formatted dialogue transcript.
     * @return Concise organic reply strictly bounded to 2,000 characters, or null on failure.
     */
    open suspend fun generateReply(transcript: String): String? = generateReply(ChatterInput(transcript = transcript))

    /**
     * Generates a conversational community-style reply from structured dialogue turns.
     *
     * @param messages List of chronological chat message turns.
     * @return Concise organic reply strictly bounded to 2,000 characters, or null on failure.
     */
    open suspend fun generateReply(messages: List<ChatMessage>): String? {
        if (messages.isEmpty()) {
            return null
        }
        val transcript = ChatterPromptBuilder.formatTranscript(messages)
        return generateReply(ChatterInput(transcript = transcript))
    }

    private suspend fun generateAgenticVideoReply(input: ChatterInput): String? {
        val userPrompt = ChatterPromptBuilder.buildUserPrompt(input)
        val systemInstruction = ChatterPromptBuilder.buildSystemInstruction()
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
                val sanitized =
                    ChatterPromptBuilder.sanitizeResponse(
                        result.text,
                        maxLength = config.maxOutputCharacters,
                    )
                if (sanitized.isBlank()) {
                    logger.warn(
                        "Agentic video interaction output was sanitized to blank for {}; degrading to generateContent baseline without videoUri",
                        input.videoUri,
                    )
                    null
                } else {
                    sanitized
                }
            }

            is GeminiInteractionResult.Error -> {
                logger.warn(
                    "Agentic video understanding failed for {} ({}); degrading to generateContent baseline without videoUri",
                    input.videoUri,
                    result.message,
                    result.cause,
                )
                null
            }
        }
    }

    private suspend fun generateWithFallback(
        input: ChatterInput,
        session: AiTraceSession?,
    ): String? {
        val searchTools = if (config.searchGroundingEnabled) listOf(GeminiTools.GOOGLE_SEARCH_TOOL) else null
        val initialOutcome = executeGenerateContent(input, tools = searchTools, session = session, variant = null)
        if (initialOutcome is GenerateAttempt.Success) {
            return initialOutcome.text
        }

        var latestFailure = initialOutcome as GenerateAttempt.Failure
        if (searchTools != null) {
            logger.warn(
                "ChatterGenerator generateContent with googleSearch failed ({}); retrying without googleSearch",
                latestFailure.reason,
                latestFailure.cause,
            )
            session?.markDegraded(latestFailure.reason)
            when (val noSearchOutcome = executeGenerateContent(input, tools = null, session = session, variant = VARIANT_NO_SEARCH)) {
                is GenerateAttempt.Success -> return noSearchOutcome.text
                is GenerateAttempt.Failure -> latestFailure = noSearchOutcome
            }
        }

        val hasMedia = input.imageAttachments.isNotEmpty() || !input.videoUri.isNullOrBlank()
        if (hasMedia) {
            if (input.transcript.isBlank()) {
                logger.warn(
                    "ChatterGenerator multimodal generateContent failed ({}) and transcript is blank; returning null",
                    latestFailure.reason,
                    latestFailure.cause,
                )
                session?.fail(latestFailure.failureCategory, latestFailure.reason)
                return null
            }
            logger.warn(
                "ChatterGenerator multimodal generateContent with file_data failed ({}); retrying text-only baseline without file_data",
                latestFailure.reason,
                latestFailure.cause,
            )
            session?.markDegraded(latestFailure.reason)
            val textOnlyInput = input.copy(imageAttachments = emptyList(), videoUri = null)
            return when (val textOnlyOutcome = executeGenerateContent(textOnlyInput, tools = null, session = session, variant = VARIANT_NO_MEDIA)) {
                is GenerateAttempt.Success -> {
                    textOnlyOutcome.text
                }

                is GenerateAttempt.Failure -> {
                    session?.fail(textOnlyOutcome.failureCategory, textOnlyOutcome.reason)
                    null
                }
            }
        }

        session?.fail(latestFailure.failureCategory, latestFailure.reason)
        return null
    }

    private suspend fun executeGenerateContent(
        input: ChatterInput,
        tools: List<GeminiTool>?,
        session: AiTraceSession?,
        variant: String?,
    ): GenerateAttempt {
        val requestPayload = ChatterPromptBuilder.buildRequest(input, tools = tools)
        val sanitizedEndpoint = "${config.cleanBaseUrl}/v1beta/models/${config.cleanModel}:generateContent"
        val endpoint = "$sanitizedEndpoint?key=${config.apiKey}"
        val attemptRequest =
            if (session?.isEnabled == true) {
                AiAttemptRequest(
                    payload =
                        runCatching {
                            jsonParser.encodeToJsonElement(GeminiGenerateContentRequest.serializer(), requestPayload)
                        }.getOrNull(),
                    hasImages = input.imageAttachments.isNotEmpty(),
                    imageCount = input.imageAttachments.size,
                    hasVideo = !input.videoUri.isNullOrBlank(),
                    videoDurationSeconds = input.videoDurationSeconds,
                    transcriptLength = input.transcript.length,
                )
            } else {
                null
            }
        val startNano = System.nanoTime()
        var httpStatus: Int? = null
        var responseRaw: String? = null
        var finishReason: String? = null
        var blockReason: String? = null
        var usageMetadata: GeminiUsageMetadata? = null

        val outcome: GenerateAttempt =
            try {
                val response: HttpResponse =
                    httpClient.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        setBody(requestPayload)
                    }
                httpStatus = response.status.value
                responseRaw = runCatching { response.bodyAsText() }.getOrNull()

                val evaluated = evaluateResponse(response, responseRaw)
                finishReason = evaluated.finishReason
                blockReason = evaluated.blockReason
                usageMetadata = evaluated.usageMetadata
                evaluated.outcome
            } catch (e: HttpRequestTimeoutException) {
                val reason = "request timeout: ${e.message}"
                logger.warn("ChatterGenerator request timed out: {}", e.message)
                GenerateAttempt.Failure(reason, AiTraceFailureCategory.TIMEOUT, e)
            } catch (e: SocketTimeoutException) {
                val reason = "socket timeout: ${e.message}"
                logger.warn("ChatterGenerator socket timed out: {}", e.message)
                GenerateAttempt.Failure(reason, AiTraceFailureCategory.TIMEOUT, e)
            } catch (e: TimeoutCancellationException) {
                val reason = "coroutine timeout: ${e.message}"
                logger.warn("ChatterGenerator coroutine timed out: {}", e.message)
                GenerateAttempt.Failure(reason, AiTraceFailureCategory.TIMEOUT, e)
            } catch (e: Exception) {
                val reason = "unexpected network error: ${e.message}"
                logger.error("ChatterGenerator unexpected network error: {}", e.message, e)
                GenerateAttempt.Failure(reason, AiTraceFailureCategory.UNEXPECTED_ERROR, e)
            }

        session?.recordAttempt(
            stage = AiAttemptStage.CHATTER_GENERATE,
            variant = variant,
            model = config.cleanModel,
            endpoint = sanitizedEndpoint,
            latencyMs = elapsedMillisFromNanos(startNano),
            httpStatus = httpStatus,
            request = attemptRequest,
            responseRaw = responseRaw,
            finishReason = finishReason,
            blockReason = blockReason,
            usageMetadata = usageMetadata,
            parsedOutcome =
                when (outcome) {
                    is GenerateAttempt.Success -> AiAttemptOutcome.SUCCESS
                    is GenerateAttempt.Failure -> AiAttemptOutcome.ERROR
                },
            errorMessage = (outcome as? GenerateAttempt.Failure)?.reason,
        )
        return outcome
    }

    private fun evaluateResponse(
        response: HttpResponse,
        rawBody: String?,
    ): EvaluatedGenerateResponse {
        val httpStatus = response.status.value

        if (!response.status.isSuccess()) {
            val errorBody = rawBody.orEmpty()
            val reason = "HTTP $httpStatus: $errorBody"
            val failureCategory =
                if (response.status == HttpStatusCode.TooManyRequests) {
                    AiTraceFailureCategory.RATE_LIMIT
                } else {
                    AiTraceFailureCategory.HTTP_ERROR
                }
            logger.warn(
                "Gemini API returned error status HTTP {}: {}",
                httpStatus,
                errorBody,
            )
            return EvaluatedGenerateResponse(GenerateAttempt.Failure(reason, failureCategory))
        }

        val responseBody: GeminiGenerateContentResponse =
            try {
                val bodyText = rawBody?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("empty response body")
                jsonParser.decodeFromString<GeminiGenerateContentResponse>(bodyText)
            } catch (e: Exception) {
                val reason = "response parse failure: ${e.message}"
                logger.warn("Failed to parse Gemini API response: {}", e.message, e)
                return EvaluatedGenerateResponse(GenerateAttempt.Failure(reason, AiTraceFailureCategory.PARSE_ERROR, e))
            }

        val finishReason = responseBody.candidates?.firstOrNull()?.finishReason
        val blockReason = responseBody.promptFeedback?.blockReason
        val usageMetadata = responseBody.usageMetadata

        fun evaluated(outcome: GenerateAttempt): EvaluatedGenerateResponse =
            EvaluatedGenerateResponse(
                outcome = outcome,
                finishReason = finishReason,
                blockReason = blockReason,
                usageMetadata = usageMetadata,
            )

        if (responseBody.error != null) {
            val errMsg = responseBody.error.message ?: responseBody.error.status ?: "unknown API error"
            val reason = "API error: $errMsg"
            logger.warn("Gemini API returned embedded error: {}", errMsg)
            return evaluated(GenerateAttempt.Failure(reason, AiTraceFailureCategory.HTTP_ERROR))
        }

        if (!blockReason.isNullOrBlank()) {
            val reason = "blocked by filter: $blockReason"
            logger.warn("Gemini generation blocked by safety or policy filter: {}", blockReason)
            return evaluated(GenerateAttempt.Failure(reason, AiTraceFailureCategory.SAFETY_BLOCK))
        }

        val candidates = responseBody.candidates
        if (candidates.isNullOrEmpty()) {
            val reason = "empty candidates"
            logger.warn("Gemini API returned no response candidates")
            return evaluated(GenerateAttempt.Failure(reason, AiTraceFailureCategory.EMPTY_OUTPUT))
        }

        val rawText =
            candidates
                .firstOrNull()
                ?.content
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString("\n")
                ?.trim()

        if (rawText.isNullOrBlank()) {
            val reason = "blank candidate content"
            val failureCategory =
                if (GeminiFinishReasons.isSafetyBlock(finishReason)) {
                    AiTraceFailureCategory.SAFETY_BLOCK
                } else {
                    AiTraceFailureCategory.EMPTY_OUTPUT
                }
            logger.warn("Gemini candidate content was blank or empty")
            return evaluated(GenerateAttempt.Failure(reason, failureCategory))
        }

        val sanitized =
            ChatterPromptBuilder.sanitizeResponse(
                rawText,
                maxLength = config.maxOutputCharacters,
            )
        if (sanitized.isBlank()) {
            val reason = "sanitized to blank"
            logger.warn("Chatter response was sanitized to blank")
            return evaluated(GenerateAttempt.Failure(reason, AiTraceFailureCategory.EMPTY_OUTPUT))
        }

        return evaluated(GenerateAttempt.Success(sanitized))
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val MAX_STATIC_VIDEO_DURATION_SECONDS = 300L
        const val MAX_VIDEO_DURATION_SECONDS = 7200L
        private const val VARIANT_NO_SEARCH = "no-search"
        private const val VARIANT_NO_MEDIA = "no-media"

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
