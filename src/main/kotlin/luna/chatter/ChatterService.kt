package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.reply
import dev.kord.core.entity.Message
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.withAiTraceSession
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Service orchestrating the Chatter auto-reply pipeline:
 * 1. Pre-filtering incoming messages (noise, bot, command, cooldown, allowed channels).
 * 2. Coordinating per-channel burst debounce windows and pre-send cancellation via [burstCoordinator].
 * 3. Overlapping silent reading simulation (0.8s–2.0s via [randomProvider]) with participation decision and reply generation.
 * 4. Triggering `channel.type()` and length-paced typing delay (`min(charCount * 120ms, 4000ms)`) only when non-blank output is ready.
 * 5. Locking dispatch, counting intervening post-trigger messages via [afterMessagesFetcher], delivering up to 3 segmented messages
 *    with asymmetric reply threading via [deliverReply], and refreshing cooldown.
 */
open class ChatterService(
    val filter: ChatterNoiseFilter = ChatterNoiseFilter(),
    val decisionClient: GeminiDecisionClient,
    val chatterGenerator: ChatterGenerator,
    val cooldownCache: BoundedChannelCooldownCache = filter.cooldownCache,
    val decisionMessageCount: Int = DEFAULT_DECISION_MESSAGE_COUNT,
    val historyMessageCount: Int = DEFAULT_HISTORY_MESSAGE_COUNT,
    val historyFetcher: suspend (message: Message, limit: Int) -> List<Message> = { message, limit ->
        message.channel
            .getMessagesBefore(message.id, limit = limit)
            .toList()
            .reversed()
    },
    val messageSender: (suspend (channel: MessageChannelBehavior, content: String) -> Message)? = null,
    val contextCollector: ChatterContextCollector =
        ChatterContextCollector(
            noiseFilter = filter,
            decisionMessageCount = decisionMessageCount,
            historyMessageCount = historyMessageCount,
        ),
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
    val burstCoordinator: BoundedChannelBurstCoordinator = BoundedChannelBurstCoordinator(),
    val randomProvider: (minInclusive: Long, maxInclusive: Long) -> Long = { min, max ->
        Random.nextLong(min, max + 1)
    },
    val afterMessagesFetcher: suspend (message: Message, limit: Int) -> List<Message> = defaultAfterMessagesFetcher,
    val replySender: (suspend (message: Message, content: String) -> Message)? = null,
) {
    private val logger = LoggerFactory.getLogger(ChatterService::class.java)

    /**
     * Registers the message create listener onto Kord.
     */
    fun register(
        kord: Kord,
        scope: CoroutineScope = safeScope(kord),
    ): Job =
        kord.on<MessageCreateEvent>(scope = scope) {
            onMessageCreate(this)
        }

    /**
     * Handles incoming [MessageCreateEvent].
     */
    suspend fun onMessageCreate(event: MessageCreateEvent): Boolean = processMessage(event.message)

    /**
     * Alias for [processMessage] handling an incoming [Message].
     */
    suspend fun handleMessage(message: Message): Boolean = processMessage(message)

    /**
     * Core pipeline processing an incoming [Message].
     */
    suspend fun processMessage(message: Message): Boolean {
        val selfId = runCatching { message.kord.selfId }.getOrNull()
        val isDirectMention = filter.isDirectBotMention(message, selfId)

        // 1. Pre-checks filter before opening/extending/cancelling any debounce window
        if (!filter.shouldEvaluate(message, isDirectMention = isDirectMention, kordSelfId = selfId)) {
            logger.debug("ChatterNoiseFilter rejected message {}", message.id)
            return false
        }

        val channelId = message.channelId
        val authorId =
            runCatching { message.author?.id }.getOrNull()
                ?: runCatching { message.data.author.id }.getOrNull()

        return burstCoordinator.executeWithDebounce(
            channelId = channelId,
            messageId = message.id,
            authorId = authorId,
            isDirectMention = isDirectMention,
            canProceedAfterWait = {
                filter.shouldEvaluate(message, isDirectMention = isDirectMention, kordSelfId = selfId)
            },
        ) { effectiveIsDirectMention, markDispatching ->
            runTracedSession(
                message = message,
                selfId = selfId,
                channelId = channelId,
                authorId = authorId,
                isDirectMention = effectiveIsDirectMention,
                markDispatching = markDispatching,
            )
        }
    }

    private suspend fun runTracedSession(
        message: Message,
        selfId: Snowflake?,
        channelId: Snowflake,
        authorId: Snowflake?,
        isDirectMention: Boolean,
        markDispatching: () -> Boolean,
    ): Boolean {
        val session =
            traceRecorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = if (isDirectMention) AiTraceTrigger.MENTION else AiTraceTrigger.AMBIENT,
                discord =
                    AiTraceDiscordContext(
                        guildId =
                            runCatching {
                                message.data.guildId.value
                                    ?.toString()
                            }.getOrNull(),
                        channelId = channelId.toString(),
                        messageId = message.id.toString(),
                        authorId = authorId?.toString(),
                        reactingUserId = null,
                        isDirectMention = isDirectMention,
                    ),
            )
        runCatching { session.registerSecret(decisionClient.config.apiKey) }
        runCatching { session.registerSecret(chatterGenerator.config.apiKey) }

        return try {
            withAiTraceSession(session) {
                processEvaluatedMessage(
                    message = message,
                    selfId = selfId,
                    isDirectMention = isDirectMention,
                    session = session,
                    markDispatching = markDispatching,
                )
            }
        } catch (e: BurstInterruptedCancellationException) {
            logger.debug("Pre-send cancellation skipped Chatter run for message {}: {}", message.id, e.message)
            session.skip(reason = e.message ?: "Cancelled by newer channel message before send")
            false
        } catch (e: CancellationException) {
            session.skip(reason = e.message ?: "Cancelled before send")
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error in ChatterService.processMessage: {}", e.message, e)
            session.fail(
                AiTraceFailureCategory.UNEXPECTED_ERROR,
                "Unexpected error in ChatterService.processMessage: ${e.message}",
            )
            false
        } finally {
            session.finish()
        }
    }

    private suspend fun processEvaluatedMessage(
        message: Message,
        selfId: Snowflake?,
        isDirectMention: Boolean,
        session: AiTraceSession,
        markDispatching: () -> Boolean,
    ): Boolean =
        coroutineScope {
            val rawReadingDelayMs = randomProvider(MIN_READING_DELAY_MS, MAX_READING_DELAY_MS)
            val readingDelayMs =
                if (rawReadingDelayMs < 0L) {
                    logger.warn("Negative reading delay '{}ms' returned by randomProvider; clamping to 0ms", rawReadingDelayMs)
                    0L
                } else {
                    rawReadingDelayMs
                }

            val readingJob =
                launch {
                    if (readingDelayMs > 0L) {
                        delay(readingDelayMs)
                    }
                }

            try {
                // 2. Gathers recent messages from channel slice to construct state / history
                val previousMessages =
                    try {
                        historyFetcher(message, maxOf(decisionMessageCount, historyMessageCount))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Failed to fetch channel history for message {}: {}", message.id, e.message)
                        emptyList()
                    }
                currentCoroutineContext().ensureActive()

                val allRaw = previousMessages + message

                // 3. Participation evaluation: bypass for direct bot mentions
                if (!isDirectMention) {
                    val decisionContext =
                        try {
                            contextCollector.collectDecisionContext(allRaw, kordSelfId = selfId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Error collecting decision context for message {}: {}", message.id, e.message)
                            session.fail(
                                AiTraceFailureCategory.CONTEXT_ERROR,
                                "Error collecting decision context for message ${message.id}: ${e.message}",
                            )
                            return@coroutineScope false
                        }
                    currentCoroutineContext().ensureActive()
                    if (decisionContext.transcript.isBlank() && decisionContext.images.isEmpty()) {
                        logger.debug("Assembled decision dialogue state is blank; skipping evaluation")
                        session.fail(
                            AiTraceFailureCategory.CONTEXT_ERROR,
                            "Assembled decision dialogue state is blank",
                        )
                        return@coroutineScope false
                    }

                    val decision =
                        try {
                            decisionClient.decide(decisionContext.transcript, decisionContext.images)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Gemini decision evaluation threw error for state: {}", e.message)
                            session.fail(
                                AiTraceFailureCategory.UNEXPECTED_ERROR,
                                "Gemini decision evaluation threw error: ${e.message}",
                            )
                            return@coroutineScope false
                        }
                    currentCoroutineContext().ensureActive()

                    if (!decision.shouldParticipate) {
                        logger.debug("Decision client evaluated shouldParticipate=false for message {}", message.id)
                        if (decision.noul != null) {
                            val threshold = runCatching { decisionClient.config.threshold }.getOrNull()
                            session.skip(
                                reason =
                                    "Participation decision skipped (noul=${decision.noul}, " +
                                        "confidence=${decision.confidence}, threshold=$threshold)",
                            )
                        }
                        return@coroutineScope false
                    }
                } else {
                    logger.info("Direct bot mention detected on message {}; bypassing decision evaluation", message.id)
                }

                // Resolve channel before heavy context/generation (no typing indicator yet!)
                val channel =
                    try {
                        message.channel
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Unable to obtain channel for message {}: {}", message.id, e.message)
                        session.fail(
                            AiTraceFailureCategory.CONTEXT_ERROR,
                            "Unable to obtain channel for message ${message.id}: ${e.message}",
                        )
                        return@coroutineScope false
                    }

                // Assemble heavy reply context (15 messages, reply chains, images, SocialPeek)
                val replyContext =
                    try {
                        contextCollector.collectReplyContext(allRaw, kordSelfId = selfId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Error collecting reply context for message {}: {}", message.id, e.message)
                        session.fail(
                            AiTraceFailureCategory.CONTEXT_ERROR,
                            "Error collecting reply context for message ${message.id}: ${e.message}",
                        )
                        return@coroutineScope false
                    }
                currentCoroutineContext().ensureActive()
                if (replyContext.transcript.isBlank() &&
                    replyContext.imageAttachments.isEmpty() &&
                    replyContext.videoUri.isNullOrBlank()
                ) {
                    logger.debug("No valid chat messages or media found in reply context for message {}", message.id)
                    session.fail(
                        AiTraceFailureCategory.CONTEXT_ERROR,
                        "Reply context is blank and contains no media",
                    )
                    return@coroutineScope false
                }

                val chatterInput = replyContext.toChatterInput(isDirectMention = isDirectMention)

                // Calls chatterGenerator.generateReply(chatterInput) while reading simulation runs concurrently
                val replyText =
                    try {
                        chatterGenerator.generateReply(chatterInput)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("ChatterGenerator failed to generate reply: {}", e.message, e)
                        session.fail(
                            AiTraceFailureCategory.UNEXPECTED_ERROR,
                            "ChatterGenerator failed to generate reply: ${e.message}",
                        )
                        return@coroutineScope false
                    }
                currentCoroutineContext().ensureActive()

                if (replyText.isNullOrBlank()) {
                    logger.debug("ChatterGenerator returned null or blank reply for message {}", message.id)
                    if (replyText != null) {
                        session.fail(
                            AiTraceFailureCategory.EMPTY_OUTPUT,
                            "ChatterGenerator returned blank reply",
                        )
                    }
                    return@coroutineScope false
                }

                val segments = ChatterPromptBuilder.splitReplySegments(replyText)
                if (segments.isEmpty()) {
                    logger.debug("ChatterPromptBuilder.splitReplySegments returned no segments for message {}", message.id)
                    session.fail(
                        AiTraceFailureCategory.EMPTY_OUTPUT,
                        "ChatterGenerator returned blank segments",
                    )
                    return@coroutineScope false
                }

                // Wait for any remaining silent reading time before triggering typing indicator
                readingJob.join()
                currentCoroutineContext().ensureActive()

                // 4. Both reading simulation and reply generation succeeded -> show typing and pace proportionally
                try {
                    channel.type()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("channel.type() call failed on channel {}: {}", message.channelId, e.message)
                }
                currentCoroutineContext().ensureActive()

                val typingDelayMs = minOf(segments.first().length * TYPING_MS_PER_CHAR, MAX_TYPING_DELAY_MS)
                if (typingDelayMs > 0L) {
                    delay(typingDelayMs)
                }
                currentCoroutineContext().ensureActive()

                // Acquire dispatch lock to prevent mid-send interruption
                if (!markDispatching()) {
                    session.skip(reason = "Cancelled right before dispatch lock in channel ${message.channelId}")
                    return@coroutineScope false
                }

                // 5. Deliver segmented reply batch to channel (no mid-burst cancellation after lock)
                try {
                    deliverReply(channel, message, segments, selfId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to send reply to channel {}: {}", message.channelId, e.message, e)
                    session.fail(
                        AiTraceFailureCategory.UNEXPECTED_ERROR,
                        "Failed to deliver Discord reply: ${e.message}",
                    )
                    return@coroutineScope false
                }

                val deliveredOutput = segments.joinToString("\n")
                cooldownCache.record(message.channelId)
                session.complete(finalOutput = deliveredOutput)
                logger.info("Chatter sent reply to channel {}: {}", message.channelId, deliveredOutput)
                true
            } finally {
                readingJob.cancel()
            }
        }

    /**
     * Clean reply delivery seam for sending segmented dialogue to a Discord channel with asymmetric reply threading.
     */
    protected open suspend fun deliverReply(
        channel: MessageChannelBehavior,
        triggerMessage: Message,
        segments: List<String>,
        selfId: Snowflake? = runCatching { triggerMessage.kord.selfId }.getOrNull(),
    ) {
        if (segments.isEmpty()) return

        val useReplyThreading = shouldUseReplyThreading(triggerMessage, selfId)
        if (useReplyThreading) {
            sendReplyMessage(triggerMessage, segments.first())
        } else {
            sendChannelMessage(channel, segments.first())
        }

        for (index in 1 until segments.size) {
            try {
                channel.type()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "channel.type() call failed before burst segment {} on channel {}: {}",
                    index + 1,
                    triggerMessage.channelId,
                    e.message,
                )
            }

            val rawSegmentDelayMs = randomProvider(MIN_SEGMENT_DELAY_MS, MAX_SEGMENT_DELAY_MS)
            val segmentDelayMs =
                if (rawSegmentDelayMs < 0L) {
                    logger.warn("Negative segment delay '{}ms' returned by randomProvider; clamping to 0ms", rawSegmentDelayMs)
                    0L
                } else {
                    rawSegmentDelayMs
                }
            if (segmentDelayMs > 0L) {
                delay(segmentDelayMs)
            }

            sendChannelMessage(channel, segments[index])
        }
    }

    private suspend fun shouldUseReplyThreading(
        triggerMessage: Message,
        selfId: Snowflake?,
    ): Boolean {
        val afterMessages =
            try {
                afterMessagesFetcher(triggerMessage, DEFAULT_AFTER_MESSAGES_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Failed to fetch post-trigger messages after {} in channel {}; failing open to createMessage: {}",
                    triggerMessage.id,
                    triggerMessage.channelId,
                    e.message,
                )
                return false
            }

        val resolvedSelfId = selfId ?: runCatching { triggerMessage.kord.selfId }.getOrNull()
        val interveningCount =
            afterMessages.count { afterMsg ->
                afterMsg.id != triggerMessage.id && !isAuthoredByLuna(afterMsg, resolvedSelfId)
            }
        return interveningCount >= REPLY_THREADING_THRESHOLD
    }

    private fun isAuthoredByLuna(
        message: Message,
        selfId: Snowflake?,
    ): Boolean {
        val resolvedSelfId = selfId ?: runCatching { message.kord.selfId }.getOrNull() ?: return false
        val authorId =
            runCatching { message.author?.id }.getOrNull()
                ?: runCatching { message.data.author.id }.getOrNull()
        return authorId == resolvedSelfId
    }

    private suspend fun sendChannelMessage(
        channel: MessageChannelBehavior,
        content: String,
    ) {
        if (messageSender != null) {
            messageSender.invoke(channel, content)
        } else {
            channel.createMessage(content)
        }
    }

    private suspend fun sendReplyMessage(
        triggerMessage: Message,
        content: String,
    ) {
        if (replySender != null) {
            replySender.invoke(triggerMessage, content)
        } else {
            triggerMessage.reply {
                this.messageReference = triggerMessage.id
                this.content = content
            }
        }
    }

    companion object {
        const val DEFAULT_DECISION_MESSAGE_COUNT = 15
        const val DEFAULT_HISTORY_MESSAGE_COUNT = 15
        const val DEFAULT_AFTER_MESSAGES_LIMIT = 20
        const val REPLY_THREADING_THRESHOLD = 2
        const val MIN_READING_DELAY_MS = 800L
        const val MAX_READING_DELAY_MS = 2_000L
        const val MIN_SEGMENT_DELAY_MS = 700L
        const val MAX_SEGMENT_DELAY_MS = 1_500L
        const val TYPING_MS_PER_CHAR = 120L
        const val MAX_TYPING_DELAY_MS = 4_000L

        val defaultAfterMessagesFetcher: suspend (message: Message, limit: Int) -> List<Message> = { message, limit ->
            message.channel
                .getMessagesAfter(message.id, limit = limit)
                .toList()
        }
    }
}
