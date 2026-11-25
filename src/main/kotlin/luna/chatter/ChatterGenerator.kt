package luna.chatter

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonPrimitive
import luna.chatter.memory.ChatterMemoryConfig
import luna.chatter.memory.ChatterMemoryStore
import luna.chatter.memory.ChatterMemoryToolHandler
import luna.chatter.memory.UnderstandingPreInjector
import luna.chatter.schedule.ChatterScheduleToolHandler
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ContextUrlClassifier
import luna.core.ai.GeminiHttpClientFactory
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.InteractionTraceContext
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.ToolExecutionOutcome
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
    httpClient: HttpClient? = null,
    private val pollingDelayMs: Long = GeminiInteractionClient.DEFAULT_POLLING_DELAY_MS,
    private val maxPollingAttempts: Int = GeminiInteractionClient.DEFAULT_MAX_POLLING_ATTEMPTS,
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
    private val memoryConfig: ChatterMemoryConfig = ChatterMemoryConfig(),
    private val memoryToolHandler: ChatterMemoryToolHandler? = null,
    private val understandingPreInjector: UnderstandingPreInjector? = null,
    private val scheduleToolHandler: ChatterScheduleToolHandler? = null,
    internal val kordSelfIdProvider: () -> String? = { null },
) : AutoCloseable {
    internal open val memoryStore: ChatterMemoryStore?
        get() = understandingPreInjector?.memoryStore
    private val ownsHttpClient: Boolean = httpClient == null
    private val httpClient: HttpClient = httpClient ?: GeminiHttpClientFactory.create()
    private val logger = LoggerFactory.getLogger(ChatterGenerator::class.java)
    private val interactionClient: GeminiInteractionClient by lazy {
        GeminiInteractionClient(
            httpClient = this.httpClient,
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
        val scheduleToolsEnabled = config.schedule.enabled && scheduleToolHandler != null
        val tools =
            ChatterPromptBuilder.buildTools(
                config = config,
                webUrls = effectiveInput.webUrls,
                includeMemoryTool = memoryToolsEnabled,
                includeScheduleTools = scheduleToolsEnabled,
            )
        val inputUrls =
            buildList {
                addAll(effectiveInput.webUrls)
                addAll(ContextUrlClassifier.extractUrls(effectiveInput.transcript))
                effectiveInput.url?.let(::add)
                input.videoUri?.let(::add)
            }.distinct()
        val trigger = if (effectiveInput.isDirectMention) AiTraceTrigger.MENTION else AiTraceTrigger.AMBIENT

        val kordSelfId = kordSelfIdProvider()
        val understandingContext =
            if (memoryConfig.enabled && understandingPreInjector != null && !effectiveInput.guildId.isNullOrBlank()) {
                understandingPreInjector.buildPreInjectionSection(
                    guildId = effectiveInput.guildId,
                    authorId = effectiveInput.authorId,
                    transcript = effectiveInput.transcript,
                    selfId = kordSelfId,
                )
            } else {
                null
            }

        val request =
            ChatterPromptBuilder.buildInteractionRequest(
                input = effectiveInput,
                model = config.cleanModel,
                tools = tools,
                videoProcessingMode = videoProcessingMode,
                background = background,
                memoryToolsEnabled = memoryToolsEnabled,
                understandingContext = understandingContext,
                scheduleToolsEnabled = scheduleToolsEnabled,
                kordSelfId = kordSelfId,
            )

        val allowedCustomEmojiIds = (effectiveInput.applicationEmojis + effectiveInput.guildEmojis).map { it.id.toString() }.toSet()
        val traceContext =
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
            )

        val result =
            if ((memoryToolsEnabled && memoryToolHandler != null) || (scheduleToolsEnabled && scheduleToolHandler != null)) {
                var memoryCallCount = 0
                interactionClient.interactWithTools(
                    baseUrl = config.cleanBaseUrl,
                    apiKey = config.apiKey,
                    request = request,
                    traceContext = traceContext,
                    maxTurns = memoryConfig.maxTurns.coerceAtLeast(1),
                    continuationStage = AiAttemptStage.CHATTER_TOOL_CONTINUATION,
                ) { call ->
                    val toolOutput =
                        when (call.name) {
                            ChatterMemoryToolHandler.TOOL_NAME -> {
                                if (memoryToolsEnabled && memoryToolHandler != null) {
                                    memoryCallCount++
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
                                            workingMemoryStartMs = effectiveInput.workingMemoryStartMs,
                                        )
                                    }
                                } else {
                                    ChatterMemoryToolHandler.unavailableResponse()
                                }
                            }

                            ChatterScheduleToolHandler.TODAY_TOOL_NAME -> {
                                if (scheduleToolsEnabled && scheduleToolHandler != null) {
                                    scheduleToolHandler.handleTodayScheduleCall(call.arguments)
                                } else {
                                    ChatterScheduleToolHandler.unavailableResponse()
                                }
                            }

                            ChatterScheduleToolHandler.COMMITMENT_TOOL_NAME -> {
                                if (scheduleToolsEnabled && scheduleToolHandler != null) {
                                    scheduleToolHandler.handleCommitmentCall(call.arguments)
                                } else {
                                    ChatterScheduleToolHandler.unavailableResponse()
                                }
                            }

                            else -> {
                                logger.warn("ChatterGenerator received unknown tool call '{}'", call.name)
                                """{"status":"unknown_tool","message":"Unknown tool: ${call.name}"}"""
                            }
                        }
                    ToolExecutionOutcome.Result(JsonPrimitive(toolOutput))
                }
            } else {
                interactionClient.interact(
                    baseUrl = config.cleanBaseUrl,
                    apiKey = config.apiKey,
                    request = request,
                    traceContext = traceContext,
                )
            }

        return when (result) {
            is InteractionExecutionResult.Success -> {
                val sanitized =
                    ChatterPromptBuilder.sanitizeResponse(
                        result.text,
                        maxLength = config.maxOutputCharacters,
                        guildEmojis = effectiveInput.guildEmojis,
                        applicationEmojis = effectiveInput.applicationEmojis,
                    )
                when (
                    val outcome =
                        ChatterPromptBuilder.parseDisengagementOutcome(
                            sanitized,
                            allowDisengagement = effectiveInput.allowMentionDisengagement,
                        )
                ) {
                    is ChatterPromptBuilder.DisengagementOutcome.NoReply -> {
                        if (isStandalone) {
                            session?.complete(finalOutput = ChatterPromptBuilder.NO_REPLY_SENTINEL, citations = emptyList())
                        }
                        ChatterPromptBuilder.NO_REPLY_SENTINEL
                    }

                    is ChatterPromptBuilder.DisengagementOutcome.ColdReact -> {
                        if (isStandalone) {
                            session?.complete(finalOutput = ChatterPromptBuilder.COLD_REACT_SENTINEL, citations = emptyList())
                        }
                        ChatterPromptBuilder.COLD_REACT_SENTINEL
                    }

                    is ChatterPromptBuilder.DisengagementOutcome.Text -> {
                        if (outcome.text.isBlank()) {
                            logger.error("ChatterGenerator response was sanitized to blank")
                            session?.fail(
                                AiTraceFailureCategory.EMPTY_OUTPUT,
                                "Chatter response was sanitized to blank",
                            )
                            return null
                        }
                        if (isStandalone) {
                            session?.complete(finalOutput = outcome.text, citations = emptyList())
                        }
                        outcome.text
                    }
                }
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
                val raw = result.text.trim()
                if (raw.isEmpty()) {
                    logger.warn("ChatterGenerator reaction selection returned blank output")
                    session?.skip(
                        reason = "Gemini reaction selection output is blank",
                        failureCategory = AiTraceFailureCategory.EMPTY_OUTPUT,
                    )
                    return null
                }
                if (isStandalone) {
                    session?.complete(finalOutput = raw, citations = emptyList())
                }
                raw
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
        if (ownsHttpClient) {
            httpClient.close()
        }
    }

    companion object {
        const val MAX_STATIC_VIDEO_DURATION_SECONDS: Long = 300L
        const val MAX_VIDEO_DURATION_SECONDS: Long = 7200L
    }
}
