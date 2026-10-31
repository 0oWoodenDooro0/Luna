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
import java.net.SocketTimeoutException

/**
 * AI client service that formats prompts and invokes Google Gemini API
 * to produce structured, emoji-free text summaries.
 */
class GeminiSummarizer(
    private val config: GeminiConfig = GeminiConfig.fromEnv(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val pollingDelayMs: Long = 2000L,
    private val maxPollingAttempts: Int = 30,
) : AutoCloseable {
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

    suspend fun summarize(input: SummaryInput): SummaryResult {
        if (!config.isConfigured) {
            return SummaryResult.Error.MissingApiKey()
        }
        if (input.content.isBlank()) {
            return SummaryResult.Error.Generic("Content to summarize cannot be blank")
        }

        val mode =
            if (!input.videoUri.isNullOrBlank()) {
                GeminiPromptBuilder.resolveProcessingMode(input.videoDurationSeconds)
            } else {
                VideoProcessingMode.STATIC
            }

        return if (mode == VideoProcessingMode.AGENTIC && !input.videoUri.isNullOrBlank()) {
            summarizeAgentic(input)
        } else {
            summarizeStatic(input)
        }
    }

    private suspend fun summarizeStatic(input: SummaryInput): SummaryResult {
        val requestPayload = GeminiPromptBuilder.buildRequest(input)
        val endpoint = "${config.baseUrl.trimEnd('/')}/v1beta/models/${config.model}:generateContent?key=${config.apiKey}"

        return try {
            val response: HttpResponse =
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestPayload)
                }

            handleResponse(response)
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

    private suspend fun summarizeAgentic(input: SummaryInput): SummaryResult {
        val promptText = GeminiPromptBuilder.buildUserPrompt(input)
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
            if (statusResponse.status == "completed" || !statusResponse.outputText.isNullOrBlank()) {
                val text = statusResponse.outputText ?: ""
                val sanitized = EmojiSanitizer.stripEmojis(text).trim()
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

    private suspend fun handleResponse(response: HttpResponse): SummaryResult {
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

                val rawText =
                    candidates
                        .firstOrNull()
                        ?.content
                        ?.parts
                        ?.mapNotNull { it.text }
                        ?.joinToString("\n")
                        ?.trim()
                if (rawText.isNullOrBlank()) {
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
