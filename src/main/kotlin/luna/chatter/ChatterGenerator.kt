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
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException

/**
 * AI client service that formats dialogue transcripts, applies Luna's community persona,
 * invokes Google Gemini API, and enforces Discord's 2,000-character payload bound.
 */
open class ChatterGenerator(
    private val config: ChatterConfig = ChatterConfig.fromEnv(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ChatterGenerator::class.java)

    /**
     * Generates a conversational community-style reply from a chronological transcript string.
     *
     * @param transcript Formatted dialogue transcript.
     * @return Concise organic reply strictly bounded to 2,000 characters, or null on failure.
     */
    open suspend fun generateReply(transcript: String): String? {
        if (!config.isConfigured) {
            logger.warn("ChatterGenerator cannot proceed: Gemini API key is not configured")
            return null
        }
        if (transcript.isBlank()) {
            logger.debug("ChatterGenerator received blank dialogue transcript, returning null")
            return null
        }

        val requestPayload = ChatterPromptBuilder.buildRequest(transcript)
        val endpoint = "${config.cleanBaseUrl}/v1beta/models/${config.cleanModel}:generateContent?key=${config.apiKey}"

        val response: HttpResponse =
            try {
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(requestPayload)
                }
            } catch (e: HttpRequestTimeoutException) {
                logger.warn("ChatterGenerator request timed out: {}", e.message)
                return null
            } catch (e: SocketTimeoutException) {
                logger.warn("ChatterGenerator socket timed out: {}", e.message)
                return null
            } catch (e: TimeoutCancellationException) {
                logger.warn("ChatterGenerator coroutine timed out: {}", e.message)
                return null
            } catch (e: Exception) {
                logger.error("ChatterGenerator unexpected network error: {}", e.message, e)
                return null
            }

        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
            logger.warn(
                "Gemini API returned error status HTTP {}: {}",
                response.status.value,
                errorBody,
            )
            return null
        }

        val responseBody: ChatterGenerateContentResponse =
            try {
                response.body()
            } catch (e: Exception) {
                logger.warn("Failed to parse Gemini API response: {}", e.message, e)
                return null
            }

        val blockReason = responseBody.promptFeedback?.blockReason
        if (!blockReason.isNullOrBlank()) {
            logger.warn("Gemini generation blocked by safety or policy filter: {}", blockReason)
            return null
        }

        val candidates = responseBody.candidates
        if (candidates.isNullOrEmpty()) {
            logger.warn("Gemini API returned no response candidates")
            return null
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
            return null
        }

        val sanitized = ChatterPromptBuilder.sanitizeResponse(rawText)
        if (sanitized.isBlank()) {
            logger.warn("Chatter response was sanitized to blank")
            return null
        }

        return sanitized
    }

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
        return generateReply(transcript)
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
