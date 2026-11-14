package luna.chatter

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ContextUrlClassifier
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.VideoProcessingMode
import luna.core.ai.withActiveOrStandaloneSession
import org.slf4j.LoggerFactory

/**
 * Generates organic, community-style Discord chat replies for Luna via Google Gemini Interactions API
 * (`POST /v1beta/interactions`), supporting multimodal inputs, conditional `url_context` & `google_search`
 * tools, and Tiered Video Latency Policy (`static` sync vs `agentic` background polling).
 *
 * Enforces fail-closed degradation on configuration gaps, upstream errors, timeouts, safety blocks,
 * and blank responses without layered fallback retries.
 */
open class ChatterGenerator(
    val config: ChatterConfig = ChatterConfig.fromEnv(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val pollingDelayMs: Long = GeminiInteractionClient.DEFAULT_POLLING_DELAY_MS,
    private val maxPollingAttempts: Int = GeminiInteractionClient.DEFAULT_MAX_POLLING_ATTEMPTS,
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ChatterGenerator::class.java)
    private val interactionClient: GeminiInteractionClient by lazy {
        GeminiInteractionClient(
            httpClient = httpClient,
            pollingDelayMs = pollingDelayMs,
            maxPollingAttempts = maxPollingAttempts,
            traceRecorder = traceRecorder,
        )
    }

    /**
     * Generates a conversational community-style reply from multimodal [ChatterInput].
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
            executeGenerateReply(input, session, isStandalone)
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
        var effectiveVideoUri: String? = rawVideoUri
        var videoProcessingMode = VideoProcessingMode.STATIC
        var background = false
        var stage = AiAttemptStage.CHATTER_GENERATE

        if (rawVideoUri != null) {
            when {
                duration != null && duration > MAX_VIDEO_DURATION_SECONDS -> {
                    logger.warn(
                        "YouTube video duration {}s exceeds 2-hour cap ({}s) for {}; omitting videoUri and proceeding with transcript metadata/comments",
                        duration,
                        MAX_VIDEO_DURATION_SECONDS,
                        rawVideoUri,
                    )
                    effectiveVideoUri = null
                }

                duration != null && duration > MAX_STATIC_VIDEO_DURATION_SECONDS -> {
                    if (input.isDirectMention) {
                        videoProcessingMode = VideoProcessingMode.AGENTIC
                        background = true
                        stage = AiAttemptStage.AGENTIC_VIDEO
                    } else {
                        logger.info(
                            "Ambient Chatter on long YouTube video ({}s > {}s) omits videoUri to preserve low latency; relying on transcript metadata/comments",
                            duration,
                            MAX_STATIC_VIDEO_DURATION_SECONDS,
                        )
                        effectiveVideoUri = null
                    }
                }
            }
        }

        val effectiveInput =
            if (effectiveVideoUri == input.videoUri) {
                input
            } else {
                input.copy(videoUri = effectiveVideoUri)
            }

        val tools = ChatterPromptBuilder.buildTools(config, effectiveInput.webUrls)
        val inputUrls =
            buildList {
                addAll(effectiveInput.webUrls)
                addAll(ContextUrlClassifier.extractUrls(effectiveInput.transcript))
                effectiveInput.url?.let(::add)
                input.videoUri?.let(::add)
            }.distinct()
        val trigger = if (effectiveInput.isDirectMention) AiTraceTrigger.MENTION else AiTraceTrigger.AMBIENT

        val result =
            interactionClient.interact(
                baseUrl = config.cleanBaseUrl,
                apiKey = config.apiKey,
                model = config.cleanModel,
                promptText = ChatterPromptBuilder.buildUserPrompt(effectiveInput),
                systemInstruction = ChatterPromptBuilder.buildSystemInstruction(),
                imageAttachments = effectiveInput.imageAttachments,
                videoUri = effectiveVideoUri,
                videoMimeType = null,
                videoProcessingMode = videoProcessingMode,
                videoDurationSeconds = duration,
                webUrlCount = effectiveInput.webUrls.size,
                inputUrls = inputUrls,
                tools = tools,
                maxOutputTokens = ChatterPromptBuilder.MAX_OUTPUT_TOKENS,
                background = background,
                stage = stage,
                variant = null,
                transcriptLength = effectiveInput.transcript.length,
                pipeline = AiTracePipeline.CHATTER,
                trigger = trigger,
            )

        return when (result) {
            is InteractionExecutionResult.Success -> {
                for (entry in result.urlContextResults) {
                    if (!entry.isSuccess) {
                        logger.error(
                            "ChatterGenerator url_context_result non-success (interactionId={}): retrieved_url={}, status={}",
                            result.interactionId ?: "unknown",
                            entry.retrievedUrl,
                            entry.status,
                        )
                    }
                }

                val sanitized =
                    ChatterPromptBuilder.sanitizeResponse(
                        result.text,
                        maxLength = config.maxOutputCharacters,
                    )
                if (sanitized.isBlank()) {
                    logger.error("ChatterGenerator response was sanitized to blank")
                    session?.fail(
                        AiTraceFailureCategory.EMPTY_OUTPUT,
                        "Chatter response was sanitized to blank",
                    )
                    return null
                }
                if (isStandalone) {
                    session?.complete(finalOutput = sanitized, citations = emptyList())
                }
                sanitized
            }

            is InteractionExecutionResult.Error -> {
                logger.error(
                    "ChatterGenerator interaction failed (stage={}, category={}): {}",
                    stage,
                    result.failureCategory,
                    result.message,
                    result.cause,
                )
                session?.fail(result.failureCategory, result.message)
                null
            }

            is InteractionExecutionResult.FunctionCall -> {
                val msg = "Unexpected function_call returned for Chatter interaction ${result.interactionId}"
                logger.error(msg)
                session?.fail(AiTraceFailureCategory.PARSE_ERROR, msg)
                null
            }
        }
    }

    /**
     * Generates a conversational community-style reply given the formatted conversation [transcript].
     */
    open suspend fun generateReply(transcript: String): String? = generateReply(ChatterInput(transcript = transcript))

    /**
     * Generates a conversational community-style reply given structured [ChatMessage] history.
     *
     * @param messages Chronologically ordered list of recent messages in the channel.
     * @return The generated reply bounded to [ChatterConfig.maxOutputCharacters], or `null` on failure.
     */
    open suspend fun generateReply(messages: List<ChatMessage>): String? {
        if (messages.isEmpty()) return null
        val transcript = ChatterPromptBuilder.formatTranscript(messages)
        return generateReply(ChatterInput(transcript = transcript))
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val MAX_STATIC_VIDEO_DURATION_SECONDS: Long = 300L
        const val MAX_VIDEO_DURATION_SECONDS: Long = 7200L

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
