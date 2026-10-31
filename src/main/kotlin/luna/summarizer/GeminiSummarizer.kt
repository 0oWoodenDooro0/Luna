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
import java.net.SocketTimeoutException

/**
 * AI client service that formats prompts and invokes Google Gemini API
 * to produce structured, emoji-free text summaries.
 */
class GeminiSummarizer(
    private val config: GeminiConfig = GeminiConfig.fromEnv(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
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
                        ?.joinToString("\n") { it.text }
                        ?.trim()
                if (rawText.isNullOrBlank()) {
                    return SummaryResult.Error.Generic("Empty text in Gemini summary response")
                }

                val sanitized = EmojiSanitizer.stripEmojis(rawText).trim()
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
