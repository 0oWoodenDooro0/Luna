package luna.chatter

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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import luna.core.ai.GeminiGenerateContentResponse
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionResult
import luna.core.ai.GeminiTool
import luna.core.ai.GeminiTools
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
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ChatterGenerator::class.java)
    private val interactionClient =
        GeminiInteractionClient(
            httpClient = httpClient,
            pollingDelayMs = pollingDelayMs,
            maxPollingAttempts = maxPollingAttempts,
        )

    private sealed class GenerateAttempt {
        data class Success(
            val text: String,
        ) : GenerateAttempt()

        data class Failure(
            val reason: String,
            val cause: Throwable? = null,
        ) : GenerateAttempt()
    }

    /**
     * Generates a conversational community-style reply from multimodal [ChatterInput].
     *
     * @param input Unified multimodal dialogue, image, video, and link context.
     * @return Concise organic reply strictly bounded to 2,000 characters, or null on failure.
     */
    open suspend fun generateReply(input: ChatterInput): String? {
        if (!config.isConfigured) {
            logger.warn("ChatterGenerator cannot proceed: Gemini API key is not configured")
            return null
        }
        if (input.transcript.isBlank() && input.imageAttachments.isEmpty() && input.videoUri.isNullOrBlank()) {
            logger.debug("ChatterGenerator received blank dialogue input, returning null")
            return null
        }

        val rawVideoUri = input.videoUri?.trim()?.takeIf { it.isNotEmpty() }
        val duration = input.videoDurationSeconds

        if (rawVideoUri != null) {
            when {
                duration != null && duration > MAX_VIDEO_DURATION_SECONDS -> {
                    logger.warn(
                        "YouTube video duration {}s exceeds 2-hour cap ({}s) for {}; omitting videoUri and proceeding with transcript metadata/comments",
                        duration,
                        MAX_VIDEO_DURATION_SECONDS,
                        rawVideoUri,
                    )
                    return generateWithFallback(input.copy(videoUri = null))
                }

                duration != null && duration > MAX_STATIC_VIDEO_DURATION_SECONDS -> {
                    if (input.isDirectMention) {
                        val agenticReply = generateAgenticVideoReply(input.copy(videoUri = rawVideoUri))
                        if (agenticReply != null) {
                            return agenticReply
                        }
                        return generateWithFallback(input.copy(videoUri = null))
                    } else {
                        logger.info(
                            "Ambient Chatter on long YouTube video ({}s > {}s) omits videoUri to preserve low latency; relying on transcript metadata/comments",
                            duration,
                            MAX_STATIC_VIDEO_DURATION_SECONDS,
                        )
                        return generateWithFallback(input.copy(videoUri = null))
                    }
                }

                else -> {
                    return generateWithFallback(input.copy(videoUri = rawVideoUri))
                }
            }
        }

        return generateWithFallback(input.copy(videoUri = null))
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

    private suspend fun generateWithFallback(input: ChatterInput): String? {
        val searchTools = if (config.searchGroundingEnabled) listOf(GeminiTools.GOOGLE_SEARCH_TOOL) else null
        val initialOutcome = executeGenerateContent(input, tools = searchTools)
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
            when (val noSearchOutcome = executeGenerateContent(input, tools = null)) {
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
                return null
            }
            logger.warn(
                "ChatterGenerator multimodal generateContent with file_data failed ({}); retrying text-only baseline without file_data",
                latestFailure.reason,
                latestFailure.cause,
            )
            val textOnlyInput = input.copy(imageAttachments = emptyList(), videoUri = null)
            return when (val textOnlyOutcome = executeGenerateContent(textOnlyInput, tools = null)) {
                is GenerateAttempt.Success -> textOnlyOutcome.text
                is GenerateAttempt.Failure -> null
            }
        }

        return null
    }

    private suspend fun executeGenerateContent(
        input: ChatterInput,
        tools: List<GeminiTool>?,
    ): GenerateAttempt {
        val requestPayload = ChatterPromptBuilder.buildRequest(input, tools = tools)
        val endpoint = "${config.cleanBaseUrl}/v1beta/models/${config.cleanModel}:generateContent?key=${config.apiKey}"

        val response: HttpResponse =
            try {
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestPayload)
                }
            } catch (e: HttpRequestTimeoutException) {
                logger.warn("ChatterGenerator request timed out: {}", e.message)
                return GenerateAttempt.Failure("request timeout: ${e.message}", e)
            } catch (e: SocketTimeoutException) {
                logger.warn("ChatterGenerator socket timed out: {}", e.message)
                return GenerateAttempt.Failure("socket timeout: ${e.message}", e)
            } catch (e: TimeoutCancellationException) {
                logger.warn("ChatterGenerator coroutine timed out: {}", e.message)
                return GenerateAttempt.Failure("coroutine timeout: ${e.message}", e)
            } catch (e: Exception) {
                logger.error("ChatterGenerator unexpected network error: {}", e.message, e)
                return GenerateAttempt.Failure("unexpected network error: ${e.message}", e)
            }

        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
            logger.warn(
                "Gemini API returned error status HTTP {}: {}",
                response.status.value,
                errorBody,
            )
            return GenerateAttempt.Failure("HTTP ${response.status.value}: $errorBody")
        }

        val responseBody: GeminiGenerateContentResponse =
            try {
                response.body()
            } catch (e: Exception) {
                logger.warn("Failed to parse Gemini API response: {}", e.message, e)
                return GenerateAttempt.Failure("response parse failure: ${e.message}", e)
            }

        if (responseBody.error != null) {
            val errMsg = responseBody.error.message ?: responseBody.error.status ?: "unknown API error"
            logger.warn("Gemini API returned embedded error: {}", errMsg)
            return GenerateAttempt.Failure("API error: $errMsg")
        }

        val blockReason = responseBody.promptFeedback?.blockReason
        if (!blockReason.isNullOrBlank()) {
            logger.warn("Gemini generation blocked by safety or policy filter: {}", blockReason)
            return GenerateAttempt.Failure("blocked by filter: $blockReason")
        }

        val candidates = responseBody.candidates
        if (candidates.isNullOrEmpty()) {
            logger.warn("Gemini API returned no response candidates")
            return GenerateAttempt.Failure("empty candidates")
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
            logger.warn("Gemini candidate content was blank or empty")
            return GenerateAttempt.Failure("blank candidate content")
        }

        val sanitized =
            ChatterPromptBuilder.sanitizeResponse(
                rawText,
                maxLength = config.maxOutputCharacters,
            )
        if (sanitized.isBlank()) {
            logger.warn("Chatter response was sanitized to blank")
            return GenerateAttempt.Failure("sanitized to blank")
        }

        return GenerateAttempt.Success(sanitized)
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val MAX_STATIC_VIDEO_DURATION_SECONDS = 300L
        const val MAX_VIDEO_DURATION_SECONDS = 7200L

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
