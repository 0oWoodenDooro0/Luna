package luna.chatter

import io.ktor.client.HttpClient
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
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptRequest
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ChatImageAttachment
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.elapsedMillisFromNanos
import luna.core.ai.withActiveOrStandaloneSession
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
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(GeminiDecisionClient::class.java)

    private sealed class RequestOutcome {
        data class Success(
            val decision: ParticipationDecision,
        ) : RequestOutcome()

        data class Failure(
            val reason: String,
            val failureCategory: AiTraceFailureCategory,
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
    ): ParticipationDecision =
        traceRecorder.withActiveOrStandaloneSession(
            pipeline = AiTracePipeline.CHATTER,
            trigger = AiTraceTrigger.AMBIENT,
            discord =
                AiTraceDiscordContext(
                    channelId = "standalone",
                    messageId = "standalone",
                    isDirectMention = false,
                ),
            secret = config.apiKey,
        ) { session, isStandalone ->
            executeDecide(state, images, session, isStandalone)
        }

    private suspend fun executeDecide(
        state: String,
        images: List<ChatImageAttachment>,
        session: AiTraceSession?,
        isStandalone: Boolean,
    ): ParticipationDecision {
        if (!config.isConfigured) {
            logger.warn("GEMINI_API_KEY is not configured; skipping conversation decision evaluation (fail-closed)")
            session?.fail(
                AiTraceFailureCategory.NOT_CONFIGURED,
                "GEMINI_API_KEY is not configured",
            )
            return ParticipationDecision(shouldParticipate = false)
        }
        if (state.isBlank() && images.isEmpty()) {
            logger.debug("Dialogue state and image attachments are blank; skipping decision evaluation (fail-closed)")
            session?.fail(
                AiTraceFailureCategory.CONTEXT_ERROR,
                "Dialogue state and image attachments are blank",
            )
            return ParticipationDecision(shouldParticipate = false)
        }

        val initialVariant = if (images.isNotEmpty()) "multimodal" else null
        return when (val initialOutcome = executeDecisionRequest(state, images, session, variant = initialVariant)) {
            is RequestOutcome.Success -> {
                val decision = initialOutcome.decision
                if (!decision.shouldParticipate) {
                    session?.skip(
                        reason =
                            "Participation decision skipped (noul=${decision.noul}, " +
                                "confidence=${decision.confidence}, threshold=${config.threshold})",
                    )
                } else if (isStandalone) {
                    session?.complete(
                        finalOutput =
                            "shouldParticipate=true, noul=${decision.noul}, confidence=${decision.confidence}",
                    )
                }
                decision
            }

            is RequestOutcome.Failure -> {
                if (images.isNotEmpty() && state.isNotBlank()) {
                    logger.warn(
                        "Multimodal Gemini decision request failed ({}); retrying once with text-only input",
                        initialOutcome.reason,
                        initialOutcome.cause,
                    )
                    session?.markDegraded(initialOutcome.reason)
                    when (val retryOutcome = executeDecisionRequest(state, emptyList(), session, variant = "text-only")) {
                        is RequestOutcome.Success -> {
                            val decision = retryOutcome.decision
                            if (!decision.shouldParticipate) {
                                session?.skip(
                                    reason =
                                        "Participation decision skipped after text-only retry " +
                                            "(noul=${decision.noul}, confidence=${decision.confidence}, " +
                                            "threshold=${config.threshold})",
                                )
                            } else if (isStandalone) {
                                session?.complete(
                                    finalOutput =
                                        "shouldParticipate=true, noul=${decision.noul}, confidence=${decision.confidence}",
                                )
                            }
                            decision
                        }

                        is RequestOutcome.Failure -> {
                            session?.fail(retryOutcome.failureCategory, retryOutcome.reason)
                            ParticipationDecision(shouldParticipate = false)
                        }
                    }
                } else {
                    session?.fail(initialOutcome.failureCategory, initialOutcome.reason)
                    ParticipationDecision(shouldParticipate = false)
                }
            }
        }
    }

    private suspend fun executeDecisionRequest(
        state: String,
        images: List<ChatImageAttachment>,
        session: AiTraceSession?,
        variant: String?,
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
        val attemptRequest =
            if (session?.isEnabled == true) {
                AiAttemptRequest(
                    payload =
                        runCatching {
                            jsonParser.encodeToJsonElement(GeminiDecisionRequest.serializer(), requestPayload)
                        }.getOrNull(),
                    hasImages = images.isNotEmpty(),
                    imageCount = images.size,
                    hasVideo = false,
                    videoDurationSeconds = null,
                    transcriptLength = state.length,
                )
            } else {
                null
            }
        val startNano = System.nanoTime()
        var httpStatus: Int? = null
        var responseRaw: String? = null

        val outcome: RequestOutcome =
            try {
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
                httpStatus = response.status.value
                responseRaw = runCatching { response.bodyAsText() }.getOrNull()
                evaluateResponse(response, responseRaw)
            } catch (e: HttpRequestTimeoutException) {
                val reason = "request timeout after ${config.timeoutMillis}ms: ${e.message}"
                logger.warn("Gemini Decisions API request timed out after {}ms: {}", config.timeoutMillis, e.message)
                RequestOutcome.Failure(reason, AiTraceFailureCategory.TIMEOUT, e)
            } catch (e: SocketTimeoutException) {
                val reason = "socket timeout after ${config.timeoutMillis}ms: ${e.message}"
                logger.warn("Gemini Decisions API socket timed out after {}ms: {}", config.timeoutMillis, e.message)
                RequestOutcome.Failure(reason, AiTraceFailureCategory.TIMEOUT, e)
            } catch (e: TimeoutCancellationException) {
                val reason = "coroutine timeout: ${e.message}"
                logger.warn("Gemini Decisions API coroutine timed out: {}", e.message)
                RequestOutcome.Failure(reason, AiTraceFailureCategory.TIMEOUT, e)
            } catch (e: Exception) {
                val reason = "unexpected error: ${e.message}"
                logger.warn("Unexpected failure invoking Gemini Decisions API: {}", e.message, e)
                RequestOutcome.Failure(reason, AiTraceFailureCategory.UNEXPECTED_ERROR, e)
            }

        val parsedOutcome =
            when (outcome) {
                is RequestOutcome.Success -> {
                    if (outcome.decision.shouldParticipate) AiAttemptOutcome.SUCCESS else AiAttemptOutcome.SKIPPED
                }

                is RequestOutcome.Failure -> {
                    AiAttemptOutcome.ERROR
                }
            }
        session?.recordAttempt(
            stage = AiAttemptStage.DECISION,
            variant = variant,
            model = config.cleanModel,
            endpoint = config.decisionEndpoint,
            latencyMs = elapsedMillisFromNanos(startNano),
            httpStatus = httpStatus,
            request = attemptRequest,
            responseRaw = responseRaw,
            parsedOutcome = parsedOutcome,
            errorMessage = (outcome as? RequestOutcome.Failure)?.reason,
        )
        return outcome
    }

    private fun evaluateResponse(
        response: HttpResponse,
        rawBody: String?,
    ): RequestOutcome {
        val httpStatus = response.status.value

        return when {
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]
                val reason = "HTTP 429 rate limit exceeded"
                logger.warn("Gemini Decisions API rate limit exceeded (HTTP 429). Retry-After: {}", retryAfter)
                RequestOutcome.Failure(reason, AiTraceFailureCategory.RATE_LIMIT)
            }

            response.status.value >= 500 -> {
                val errorBody = rawBody.orEmpty()
                val reason = "HTTP $httpStatus upstream server error"
                logger.warn("Gemini Decisions API upstream server error (HTTP {}): {}", httpStatus, errorBody)
                RequestOutcome.Failure(reason, AiTraceFailureCategory.HTTP_ERROR)
            }

            !response.status.isSuccess() -> {
                val errorBody = rawBody.orEmpty()
                val reason = "HTTP $httpStatus: $errorBody"
                logger.warn("Gemini Decisions API returned HTTP {}: {}", httpStatus, errorBody)
                RequestOutcome.Failure(reason, AiTraceFailureCategory.HTTP_ERROR)
            }

            else -> {
                val parsedResponse =
                    try {
                        val bodyText = rawBody?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("empty response body")
                        jsonParser.decodeFromString<GeminiDecisionResponse>(bodyText)
                    } catch (ex: Exception) {
                        val reason = "malformed JSON payload: ${ex.message}"
                        logger.warn("Failed to parse Gemini Decisions API response payload: {}", ex.message)
                        return RequestOutcome.Failure(reason, AiTraceFailureCategory.PARSE_ERROR, ex)
                    }

                if (parsedResponse.error != null) {
                    val errorMsg = parsedResponse.error.message ?: parsedResponse.error.status ?: "unknown error"
                    val reason = "API error response: $errorMsg"
                    logger.warn("Gemini Decisions API reported error: {}", errorMsg)
                    return RequestOutcome.Failure(reason, AiTraceFailureCategory.HTTP_ERROR)
                }

                val answer = parsedResponse.answers[QUESTION_SHOULD_PARTICIPATE]
                if (answer == null) {
                    val reason = "missing '$QUESTION_SHOULD_PARTICIPATE' answer"
                    logger.warn("Gemini Decisions response missing question answer for '{}'", QUESTION_SHOULD_PARTICIPATE)
                    return RequestOutcome.Failure(reason, AiTraceFailureCategory.PARSE_ERROR)
                }

                val noul = answer.noul
                if (noul == null || noul.isNaN() || noul.isInfinite()) {
                    val reason = "missing valid numeric 'noul' score"
                    logger.warn("Gemini Decisions answer '{}' missing valid numeric 'noul' score", QUESTION_SHOULD_PARTICIPATE)
                    return RequestOutcome.Failure(reason, AiTraceFailureCategory.PARSE_ERROR)
                }

                RequestOutcome.Success(
                    ParticipationDecision(
                        shouldParticipate = noul >= config.threshold,
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
