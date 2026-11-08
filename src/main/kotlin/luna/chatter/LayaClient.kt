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
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException

/**
 * High-speed System 1 decision client targeting Cloud Laya Studio (`POST /v1/systemone`).
 *
 * Encapsulates the binary (`noul`) question schema to determine whether Luna should participate in a conversation.
 * Applies a 3-second network timeout and enforces observable fail-closed degradation on timeout, HTTP 5xx,
 * or 429 rate limit errors without caching transient failure states.
 *
 * @property config Configuration holding API credentials, endpoint, and decision threshold.
 * @property httpClient Ktor HTTP client configured with content negotiation and timeout plugins.
 * @property defaultInstructions Default system instructions for the `noul` question schema.
 */
class LayaClient(
    val config: LayaConfig = LayaConfig.fromEnv(),
    private val httpClient: HttpClient = createDefaultHttpClient(config.timeoutMillis),
    val defaultInstructions: String = DEFAULT_INSTRUCTIONS,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(LayaClient::class.java)

    /**
     * Determines whether Luna should participate in the conversation given the recent [state] dialogue turns.
     *
     * Returns true when Laya responds with `noul == true` and probability/confidence is greater than or
     * equal to the configured threshold (default 0.7). Returns false on negative decision or any upstream failure.
     */
    suspend fun shouldParticipate(state: String): Boolean = decide(state).shouldParticipate

    /**
     * Evaluates conversation state and returns [LayaDecision] detailing participation outcome.
     */
    suspend fun evaluateParticipation(state: String): LayaDecision = decide(state)

    /**
     * Queries Laya System 1 decision API and returns the detailed [LayaDecision] outcome.
     */
    suspend fun decide(state: String): LayaDecision {
        if (!config.isConfigured) {
            logger.warn("Laya API key is not configured; skipping conversation evaluation (fail-closed)")
            return LayaDecision(shouldParticipate = false)
        }
        if (state.isBlank()) {
            logger.debug("Dialogue state is blank; skipping Laya evaluation (fail-closed)")
            return LayaDecision(shouldParticipate = false)
        }

        val requestPayload =
            LayaSystemOneRequest(
                state = state,
                questions =
                    mapOf(
                        QUESTION_SHOULD_PARTICIPATE to
                            LayaQuestion(
                                type = LayaQuestion.TYPE_NOUL,
                                instructions = defaultInstructions,
                            ),
                    ),
            )

        return try {
            val response: HttpResponse =
                httpClient.post(config.cleanEndpoint) {
                    header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
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
            logger.warn("Laya Studio API request timed out after {}ms: {}", config.timeoutMillis, e.message)
            LayaDecision(shouldParticipate = false)
        } catch (e: SocketTimeoutException) {
            logger.warn("Laya Studio API socket timed out after {}ms: {}", config.timeoutMillis, e.message)
            LayaDecision(shouldParticipate = false)
        } catch (e: TimeoutCancellationException) {
            logger.warn("Laya Studio API coroutine timed out: {}", e.message)
            LayaDecision(shouldParticipate = false)
        } catch (e: Exception) {
            logger.warn("Unexpected failure invoking Laya Studio API: {}", e.message, e)
            LayaDecision(shouldParticipate = false)
        }
    }

    private suspend fun handleResponse(response: HttpResponse): LayaDecision {
        when {
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]
                logger.warn("Laya Studio API rate limit exceeded (HTTP 429). Retry-After: {}", retryAfter)
                return LayaDecision(shouldParticipate = false)
            }

            response.status.value >= 500 -> {
                val errorBody = response.bodyAsText()
                logger.warn("Laya Studio upstream server error (HTTP {}): {}", response.status.value, errorBody)
                return LayaDecision(shouldParticipate = false)
            }

            !response.status.isSuccess() -> {
                val errorBody = response.bodyAsText()
                logger.warn("Laya Studio API returned HTTP {}: {}", response.status.value, errorBody)
                return LayaDecision(shouldParticipate = false)
            }

            else -> {
                val parsedResponse =
                    try {
                        response.body<LayaSystemOneResponse>()
                    } catch (_: Exception) {
                        val rawBody = response.bodyAsText()
                        try {
                            jsonParser.decodeFromString<LayaSystemOneResponse>(rawBody)
                        } catch (ex: Exception) {
                            logger.warn("Failed to parse Laya Studio response payload: {}", ex.message)
                            return LayaDecision(shouldParticipate = false)
                        }
                    }

                if (parsedResponse.error != null) {
                    logger.warn("Laya Studio API reported error: {}", parsedResponse.error.message)
                    return LayaDecision(shouldParticipate = false)
                }

                val answer = parsedResponse.answers[QUESTION_SHOULD_PARTICIPATE]
                if (answer == null) {
                    logger.warn("Laya response missing question answer for '{}'", QUESTION_SHOULD_PARTICIPATE)
                    return LayaDecision(shouldParticipate = false)
                }

                val noul = answer.noul
                val prob = answer.effectiveProbability
                val conf = answer.confidence ?: prob

                if (noul != true) {
                    return LayaDecision(
                        shouldParticipate = false,
                        noul = noul,
                        probability = prob,
                        confidence = conf,
                        rawAnswer = answer,
                    )
                }

                val meetsThreshold = prob >= config.threshold
                return LayaDecision(
                    shouldParticipate = meetsThreshold,
                    noul = true,
                    probability = prob,
                    confidence = conf,
                    rawAnswer = answer,
                )
            }
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val TIMEOUT_MILLIS = DEFAULT_TIMEOUT_MILLIS
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

        fun createDefaultHttpClient(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): HttpClient =
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
