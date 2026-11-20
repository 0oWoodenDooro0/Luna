package luna.chatter

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import luna.chatter.memory.ChatterMemoryConfig
import luna.chatter.memory.ChatterMemoryToolHandler
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ContextUrlClassifier
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionInput
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.InteractionTraceContext
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
    val config: ChatterConfig = ChatterConfig(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val pollingDelayMs: Long = GeminiInteractionClient.DEFAULT_POLLING_DELAY_MS,
    private val maxPollingAttempts: Int = GeminiInteractionClient.DEFAULT_MAX_POLLING_ATTEMPTS,
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
    private val memoryConfig: ChatterMemoryConfig = ChatterMemoryConfig(),
    private val memoryToolHandler: ChatterMemoryToolHandler? = null,
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

        val memoryToolsEnabled =
            memoryConfig.enabled &&
                memoryToolHandler != null &&
                !effectiveInput.guildId.isNullOrBlank()
        val tools =
            ChatterPromptBuilder.buildTools(
                config = config,
                webUrls = effectiveInput.webUrls,
                includeMemoryTool = memoryToolsEnabled,
            )
        val inputUrls =
            buildList {
                addAll(effectiveInput.webUrls)
                addAll(ContextUrlClassifier.extractUrls(effectiveInput.transcript))
                effectiveInput.url?.let(::add)
                input.videoUri?.let(::add)
            }.distinct()
        val trigger = if (effectiveInput.isDirectMention) AiTraceTrigger.MENTION else AiTraceTrigger.AMBIENT

        val request =
            ChatterPromptBuilder.buildInteractionRequest(
                input = effectiveInput,
                model = config.cleanModel,
                tools = tools,
                videoProcessingMode = videoProcessingMode,
                background = background,
                memoryToolsEnabled = memoryToolsEnabled,
            )

        val allowedCustomEmojiIds = effectiveInput.guildEmojis.map { it.id.toString() }.toSet()
        val initialResult =
            interactionClient.interact(
                baseUrl = config.cleanBaseUrl,
                apiKey = config.apiKey,
                request = request,
                traceContext =
                    InteractionTraceContext(
                        webUrlCount = effectiveInput.webUrls.size,
                        inputUrls = inputUrls,
                        videoDurationSeconds = duration,
                        transcriptLength = effectiveInput.transcript.length,
                        stage = stage,
                        variant = null,
                        pipeline = AiTracePipeline.CHATTER,
                        trigger = trigger,
                        allowedCustomEmojiIds = allowedCustomEmojiIds,
                    ),
            )

        val result =
            if (initialResult is InteractionExecutionResult.FunctionCall && memoryToolsEnabled && memoryToolHandler != null) {
                resolveToolContinuations(
                    initialResult = initialResult,
                    effectiveInput = effectiveInput,
                    tools = tools,
                    inputUrls = inputUrls,
                    duration = duration,
                    background = background,
                    trigger = trigger,
                    allowedCustomEmojiIds = allowedCustomEmojiIds,
                    memoryToolHandler = memoryToolHandler,
                    session = session,
                ) ?: return null
            } else {
                initialResult
            }

        return when (result) {
            is InteractionExecutionResult.Success -> {
                val sanitized =
                    ChatterPromptBuilder.sanitizeResponse(
                        result.text,
                        maxLength = config.maxOutputCharacters,
                        guildEmojis = effectiveInput.guildEmojis,
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

            is InteractionExecutionResult.Error.SafetyBlock -> {
                logger.warn(
                    "ChatterGenerator interaction safety blocked (stage={}, reason={}): {}",
                    stage,
                    result.finishReason,
                    result.message,
                )
                session?.fail(result.failureCategory, result.message)
                null
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

    private suspend fun resolveToolContinuations(
        initialResult: InteractionExecutionResult.FunctionCall,
        effectiveInput: ChatterInput,
        tools: List<luna.core.ai.GeminiInteractionTool>,
        inputUrls: List<String>,
        duration: Long?,
        background: Boolean,
        trigger: AiTraceTrigger,
        allowedCustomEmojiIds: Set<String>,
        memoryToolHandler: ChatterMemoryToolHandler,
        session: AiTraceSession?,
    ): InteractionExecutionResult? {
        var currentResult: InteractionExecutionResult = initialResult
        var turnCount = 1
        var memoryCallCount = 0
        val maxTurns = memoryConfig.maxTurns.coerceAtLeast(1)
        val systemInstruction =
            ChatterPromptBuilder.buildSystemInstruction(
                personaState = effectiveInput.personaState,
                memoryToolsEnabled = true,
            )

        while (currentResult is InteractionExecutionResult.FunctionCall) {
            if (turnCount >= maxTurns) {
                val msg = "ChatterGenerator exceeded maxTurns ($maxTurns) during memory tool continuation"
                logger.warn(msg)
                session?.fail(AiTraceFailureCategory.PARSE_ERROR, msg)
                return null
            }

            val functionCalls = currentResult.functionCalls
            if (functionCalls.isEmpty()) {
                val msg = "Gemini returned function_call step with empty functionCalls for interaction ${currentResult.interactionId}"
                logger.error(msg)
                session?.fail(AiTraceFailureCategory.PARSE_ERROR, msg)
                return null
            }

            val functionResults = mutableListOf<GeminiInteractionInput>()
            for (call in functionCalls) {
                if (call.name.isBlank() || call.id.isBlank()) {
                    val msg = "Malformed function_call step (name='${call.name}', call_id='${call.id}')"
                    logger.error(msg)
                    session?.fail(AiTraceFailureCategory.PARSE_ERROR, msg)
                    return null
                }
                if (call.name != ChatterMemoryToolHandler.TOOL_NAME) {
                    val msg = "Unknown tool call requested by Gemini in Chatter: ${call.name}"
                    logger.error(msg)
                    session?.fail(AiTraceFailureCategory.PARSE_ERROR, msg)
                    return null
                }

                memoryCallCount++
                val toolOutput =
                    if (memoryCallCount > memoryConfig.maxToolCalls) {
                        logger.info(
                            "Chatter memory tool call cap ({}) reached on call {}; returning limit_reached notice",
                            memoryConfig.maxToolCalls,
                            memoryCallCount,
                        )
                        ChatterMemoryToolHandler.limitReachedResponse()
                    } else {
                        memoryToolHandler.handleToolCall(
                            arguments = call.arguments,
                            guildId = effectiveInput.guildId,
                            channelId = effectiveInput.channelId,
                            defaultAuthorId = effectiveInput.authorId,
                        )
                    }

                functionResults.add(
                    GeminiInteractionInput.functionResult(
                        name = call.name,
                        callId = call.id,
                        result = toolOutput,
                    ),
                )
            }

            turnCount++
            val continuationPayloadLength = functionResults.sumOf { it.result?.toString()?.length ?: 0 }
            currentResult =
                interactionClient.continueInteraction(
                    baseUrl = config.cleanBaseUrl,
                    apiKey = config.apiKey,
                    model = config.cleanModel,
                    previousInteractionId = currentResult.interactionId,
                    functionResults = functionResults,
                    systemInstruction = systemInstruction,
                    tools = tools,
                    maxOutputTokens = ChatterPromptBuilder.MAX_OUTPUT_TOKENS,
                    background = background,
                    traceContext =
                        InteractionTraceContext(
                            webUrlCount = effectiveInput.webUrls.size,
                            inputUrls = inputUrls,
                            videoDurationSeconds = duration,
                            transcriptLength = effectiveInput.transcript.length + continuationPayloadLength,
                            stage = AiAttemptStage.CHATTER_TOOL_CONTINUATION,
                            variant = ChatterMemoryToolHandler.TOOL_NAME,
                            pipeline = AiTracePipeline.CHATTER,
                            trigger = trigger,
                            allowedCustomEmojiIds = allowedCustomEmojiIds,
                        ),
                )
        }
        return currentResult
    }

    /**
     * Selects a single Discord reaction emoji (standard Unicode or verified guild custom emoji) from
     * multimodal [ChatterInput] without generating a text reply or using fallback emoji pools.
     */
    open suspend fun selectReactionEmoji(input: ChatterInput): String? =
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
            executeSelectReactionEmoji(input, session, isStandalone)
        }

    private suspend fun executeSelectReactionEmoji(
        input: ChatterInput,
        session: AiTraceSession?,
        isStandalone: Boolean,
    ): String? {
        if (!config.isConfigured) {
            logger.warn("ChatterGenerator reaction selection skipped: Gemini API key is not configured")
            session?.skip(
                reason = "GEMINI_API_KEY is not configured",
                failureCategory = AiTraceFailureCategory.NOT_CONFIGURED,
            )
            return null
        }
        if (input.transcript.isBlank() && input.imageAttachments.isEmpty()) {
            logger.debug("ChatterGenerator reaction selection received blank input, skipping reaction")
            session?.skip(
                reason = "Chatter reaction selection received blank input",
                failureCategory = AiTraceFailureCategory.CONTEXT_ERROR,
            )
            return null
        }

        val request =
            ChatterPromptBuilder.buildReactionInteractionRequest(
                input = input,
                model = config.cleanModel,
            )

        val result =
            interactionClient.interact(
                baseUrl = config.cleanBaseUrl,
                apiKey = config.apiKey,
                request = request,
                traceContext =
                    InteractionTraceContext(
                        webUrlCount = 0,
                        inputUrls = emptyList(),
                        transcriptLength = input.transcript.length,
                        stage = AiAttemptStage.REACTION_SELECT,
                        variant = null,
                        pipeline = AiTracePipeline.CHATTER,
                        trigger = AiTraceTrigger.AMBIENT,
                        stripOutputEmojis = false,
                    ),
            )

        return when (result) {
            is InteractionExecutionResult.Success -> {
                if (result.text.isBlank()) {
                    logger.warn("ChatterGenerator reaction selection returned blank output")
                    session?.skip(
                        reason = "Gemini reaction selection output is blank",
                        failureCategory = AiTraceFailureCategory.EMPTY_OUTPUT,
                    )
                    return null
                }
                if (ChatterReactionEmojiValidator.isExplicitNoReaction(result.text)) {
                    logger.debug("ChatterGenerator reaction selection explicitly declined to react (NONE)")
                    session?.skip(reason = "Reaction selection model declined to react (NONE)")
                    return null
                }
                val normalized = ChatterReactionEmojiValidator.validateAndNormalize(result.text, input.guildEmojis)
                if (normalized == null) {
                    logger.warn(
                        "ChatterGenerator reaction selection returned invalid or unverified emoji: {}",
                        result.text.trim().take(80),
                    )
                    session?.skip(
                        reason = "Reaction selection output was not a single valid Unicode or verified guild custom emoji",
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    )
                    return null
                }
                if (isStandalone) {
                    session?.complete(finalOutput = normalized, citations = emptyList())
                }
                normalized
            }

            is InteractionExecutionResult.Error -> {
                logger.warn(
                    "ChatterGenerator reaction selection failed (category={}): {}",
                    result.failureCategory,
                    result.message,
                    result.cause,
                )
                session?.skip(
                    reason = result.message,
                    failureCategory = result.failureCategory,
                )
                null
            }

            is InteractionExecutionResult.FunctionCall -> {
                val msg = "Unexpected function_call returned for reaction selection interaction ${result.interactionId}"
                logger.warn(msg)
                session?.skip(
                    reason = msg,
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                )
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
