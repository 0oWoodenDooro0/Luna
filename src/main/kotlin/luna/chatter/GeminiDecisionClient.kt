package luna.chatter

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import luna.core.ai.ChatImageAttachment
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException

/**
 * Low-latency Participation Decision client targeting Google Gemini Decisions API
 * (`POST /v1beta/decisions`) using `gemini-decision-1`.
 *
 * Evaluates binary `noul` probabilities over dialogue text and optional low-resolution
 * [ChatImageAttachment] blocks. Enforces fail-closed degradation on timeouts, HTTP 429,
 * HTTP 5xx, non-2xx responses, and parse errors without caching transient failure states.
 * When a multimodal request carrying images fails, emits a descriptive warning and retries
 * once with text-only input before failing closed.
 */
open class GeminiDecisionClient(
    val config: ChatterDecisionConfig = ChatterDecisionConfig.fromEnv(),
    private val httpClient: HttpClient = createDefaultHttpClient(config.timeoutMillis),
    val defaultInstructions: String = DEFAULT_INSTRUCTIONS,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(GeminiDecisionClient::class.java)

    private sealed class RequestOutcome {
        data class Success(
            val decision: ParticipationDecision,
        ) : RequestOutcome()

        data class Failure(
            val reason: String,
            val cause: Throwable? = null,
        ) : RequestOutcome()
    }

    /**
     * Determines whether Luna should participate in the conversation given [state] and optional [images].
     */
    open suspend fun shouldParticipate(
        state: String,
        images: List<ChatImageAttachment> = emptyList(),
    ): Boolean = decide(state, images).shouldParticipate

    /**
     * Queries Gemini Decisions API and returns the detailed [ParticipationDecision] outcome.
     */
    open suspend fun decide(
        state: String,
        images: List<ChatImageAttachment> = emptyList(),
    ): ParticipationDecision {
        if (!config.isConfigured) {
            logger.warn("GEMINI_API_KEY is not configured; skipping conversation decision evaluation (fail-closed)")
            return ParticipationDecision(shouldParticipate = false)
        }
        if (state.isBlank() && images.isEmpty()) {
            logger.debug("Dialogue state and image attachments are blank; skipping decision evaluation (fail-closed)")
            return ParticipationDecision(shouldParticipate = false)
        }

        return when (val initialOutcome = executeDecisionRequest(state, images)) {
            is RequestOutcome.Success -> {
                initialOutcome.decision
            }

            is RequestOutcome.Failure -> {
                if (images.isNotEmpty() && state.isNotBlank()) {
                    logger.warn(
                        "Multimodal Gemini decision request failed ({}); retrying once with text-only input",
                        initialOutcome.reason,
                        initialOutcome.cause,
                    )
                    when (val retryOutcome = executeDecisionRequest(state, emptyList())) {
                        is RequestOutcome.Success -> retryOutcome.decision
                        is RequestOutcome.Failure -> ParticipationDecision(shouldParticipate = false)
                    }
                } else {
                    ParticipationDecision(shouldParticipate = false)
                }
            }
        }
    }

    private suspend fun executeDecisionRequest(
        state: String,
        images: List<ChatImageAttachment>,
    ): RequestOutcome {
        val inputBlocks =
            buildList {
                if (state.isNotBlank()) {
                    add(GeminiDecisionInputBlock.text(state))
                }
                images.forEach { image ->
                    add(
                        GeminiDecisionInputBlock.image(
                            uri = image.url,
                            mimeType = image.mimeType,
                            resolution = GeminiDecisionInputBlock.RESOLUTION_LOW,
                        ),
                    )
                }
            }

        val requestPayload =
            GeminiDecisionRequest(
                model = config.cleanModel,
                input = inputBlocks,
                questions =
                    mapOf(
                        QUESTION_SHOULD_PARTICIPATE to
                            GeminiDecisionQuestion(
                                type = GeminiDecisionQuestion.TYPE_NOUL,
                                instructions = defaultInstructions,
                            ),
                    ),
            )

        return try {
            val response: HttpResponse =
                httpClient.post(config.decisionEndpoint) {
                    header(API_KEY_HEADER, config.apiKey)
                    contentType(ContentType.Application.Json)
                    timeout {
                        requestTimeoutMillis = config.timeoutMillis
                        socketTimeoutMillis = config.timeoutMillis
                        connectTimeoutMillis = config.timeoutMillis
                    }
                    setBody(requestPayload)
                }

            handleResponse(response)
        } catch (e: HttpRequestTimeoutException) {
            logger.warn("Gemini Decisions API request timed out after {}ms: {}", config.timeoutMillis, e.message)
            RequestOutcome.Failure("request timeout after ${config.timeoutMillis}ms: ${e.message}", e)
        } catch (e: SocketTimeoutException) {
            logger.warn("Gemini Decisions API socket timed out after {}ms: {}", config.timeoutMillis, e.message)
            RequestOutcome.Failure("socket timeout after ${config.timeoutMillis}ms: ${e.message}", e)
        } catch (e: TimeoutCancellationException) {
            logger.warn("Gemini Decisions API coroutine timed out: {}", e.message)
            RequestOutcome.Failure("coroutine timeout: ${e.message}", e)
        } catch (e: Exception) {
            logger.warn("Unexpected failure invoking Gemini Decisions API: {}", e.message, e)
            RequestOutcome.Failure("unexpected error: ${e.message}", e)
        }
    }

    private suspend fun handleResponse(response: HttpResponse): RequestOutcome {
        when {
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]
                logger.warn("Gemini Decisions API rate limit exceeded (HTTP 429). Retry-After: {}", retryAfter)
                return RequestOutcome.Failure("HTTP 429 rate limit exceeded")
            }

            response.status.value >= 500 -> {
                val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                logger.warn("Gemini Decisions API upstream server error (HTTP {}): {}", response.status.value, errorBody)
                return RequestOutcome.Failure("HTTP ${response.status.value} upstream server error")
            }

            !response.status.isSuccess() -> {
                val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                logger.warn("Gemini Decisions API returned HTTP {}: {}", response.status.value, errorBody)
                return RequestOutcome.Failure("HTTP ${response.status.value}: $errorBody")
            }

            else -> {
                val parsedResponse =
                    try {
                        response.body<GeminiDecisionResponse>()
                    } catch (_: Exception) {
                        val rawBody = runCatching { response.bodyAsText() }.getOrDefault("")
                        try {
                            jsonParser.decodeFromString<GeminiDecisionResponse>(rawBody)
                        } catch (ex: Exception) {
                            logger.warn("Failed to parse Gemini Decisions API response payload: {}", ex.message)
                            return RequestOutcome.Failure("malformed JSON payload: ${ex.message}", ex)
                        }
                    }

                if (parsedResponse.error != null) {
                    val errorMsg = parsedResponse.error.message ?: parsedResponse.error.status ?: "unknown error"
                    logger.warn("Gemini Decisions API reported error: {}", errorMsg)
                    return RequestOutcome.Failure("API error response: $errorMsg")
                }

                val answer = parsedResponse.answers[QUESTION_SHOULD_PARTICIPATE]
                if (answer == null) {
                    logger.warn("Gemini Decisions response missing question answer for '{}'", QUESTION_SHOULD_PARTICIPATE)
                    return RequestOutcome.Failure("missing '$QUESTION_SHOULD_PARTICIPATE' answer")
                }

                val noul = answer.noul
                if (noul == null || noul.isNaN() || noul.isInfinite()) {
                    logger.warn("Gemini Decisions answer '{}' missing valid numeric 'noul' score", QUESTION_SHOULD_PARTICIPATE)
                    return RequestOutcome.Failure("missing valid numeric 'noul' score")
                }

                val shouldParticipate = noul >= config.threshold
                return RequestOutcome.Success(
                    ParticipationDecision(
                        shouldParticipate = shouldParticipate,
                        noul = noul,
                        confidence = answer.confidence,
                        rawAnswer = answer,
                    ),
                )
            }
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val API_KEY_HEADER = "x-goog-api-key"
        const val QUESTION_SHOULD_PARTICIPATE = "should_participate"

        const val DEFAULT_INSTRUCTIONS =
            "Should the AI assistant join and participate in this conversation? " +
                "Answer true if the message asks a question, seeks discussion, mentions the bot, " +
                "or raises a conversational topic suitable for chat; answer false if it is a " +
                "personal command, pure greeting exchange, system notice, or closed dialogue."

        val jsonParser =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
                explicitNulls = false
            }

        fun createDefaultHttpClient(timeoutMillis: Long = ChatterDecisionConfig.DEFAULT_TIMEOUT_MILLIS): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                }
            }
    }
}
