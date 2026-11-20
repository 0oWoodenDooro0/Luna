package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.reply
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.event.guild.EmojisUpdateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.ReactionAddEvent
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
import luna.chatter.emoji.ChatterEmojiCatalog
import luna.chatter.emoji.EmojiVisionTurnBudget
import luna.chatter.emoji.NoOpChatterEmojiCatalog
import luna.chatter.emoji.PreparedPromptEmojis
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.EmojiSanitizer
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.withAiTraceSession
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Service orchestrating the Chatter auto-reply pipeline:
 * 1. Pre-filtering incoming messages (noise, bot, command, cooldown, allowed channels).
 * 2. Coordinating per-channel burst debounce windows and pre-send cancellation via [burstCoordinator].
 * 3. Overlapping silent reading simulation (0.8s–2.0s via [randomProvider]) with participation decision and
 *    routing intermediate scores into the Silent Reaction Path or full reply generation.
 * 4. Triggering `channel.type()` and length-paced typing delay (`min(charCount * 120ms, 4000ms)`) only when non-blank text reply output is ready.
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
    val personaStateProvider: PersonaStateProvider = CircadianPersonaDrift(),
    val guildEmojisProvider: suspend (message: Message) -> List<GuildCustomEmoji> = defaultGuildEmojisProvider,
    val reactionSender: (suspend (message: Message, emoji: ReactionEmoji) -> Unit)? = null,
    val emojiCatalog: ChatterEmojiCatalog = NoOpChatterEmojiCatalog,
) {
    private val logger = LoggerFactory.getLogger(ChatterService::class.java)

    /**
     * Registers the message create and reaction add listeners onto Kord.
     */
    fun register(
        kord: Kord,
        scope: CoroutineScope = safeScope(kord),
    ): Job {
        val parentJob = Job(scope.coroutineContext[Job])
        val listenerScope = CoroutineScope(scope.coroutineContext + parentJob)
        kord.on<MessageCreateEvent>(scope = listenerScope) {
            onMessageCreate(this)
        }
        kord.on<ReactionAddEvent>(scope = listenerScope) {
            onReactionAdd(this)
        }
        kord.on<EmojisUpdateEvent>(scope = listenerScope) {
            onEmojisUpdate(this)
        }
        listenerScope.launch {
            preScanStartupGuilds(kord)
        }
        return parentJob
    }

    /**
     * Pre-scans custom emojis across all joined guilds on startup without blocking gateway dispatch.
     */
    suspend fun preScanStartupGuilds(kord: Kord) {
        try {
            coroutineScope {
                kord.guilds.collect { guild ->
                    val guildId = guild.id
                    launch {
                        runCatching {
                            val availableEmojis = guild.emojis.toList().toAvailableGuildCustomEmojis()
                            emojiCatalog.preScanGuildEmojis(guildId = guildId, emojis = availableEmojis)
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            logger.warn(
                                "Failed to pre-scan custom emojis for guild {} on startup: {}",
                                guildId,
                                e.message,
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to enumerate guilds for startup custom emoji pre-scan: {}", e.message)
        }
    }

    /**
     * Handles incoming [MessageCreateEvent].
     */
    suspend fun onMessageCreate(event: MessageCreateEvent): Boolean = processMessage(event.message)

    /**
     * Handles incoming [ReactionAddEvent] for custom emoji usage tracking.
     */
    suspend fun onReactionAdd(event: ReactionAddEvent) {
        val guildId = event.guildId ?: return
        val selfId = runCatching { event.kord.selfId }.getOrNull()
        if (selfId != null && event.userId == selfId) return
        emojiCatalog.recordReactionUsage(guildId = guildId, emoji = event.emoji)
    }

    /**
     * Handles incoming [EmojisUpdateEvent] for background guild custom emoji pre-scanning.
     */
    suspend fun onEmojisUpdate(event: EmojisUpdateEvent) {
        val availableEmojis = event.emojis.toAvailableGuildCustomEmojis()
        emojiCatalog.preScanGuildEmojis(guildId = event.guildId, emojis = availableEmojis)
    }

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

        val isBotAuthor = runCatching { message.author?.isBot }.getOrNull() ?: false
        if (!isBotAuthor) {
            val guildId = runCatching { message.data.guildId.value }.getOrNull()
            val content = runCatching { message.content }.getOrNull().orEmpty()
            emojiCatalog.recordMessageUsage(guildId = guildId, content = content)
        }

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
            val baseThreshold =
                runCatching { decisionClient.config.threshold }
                    .getOrDefault(ChatterDecisionConfig.DEFAULT_THRESHOLD)
            val reactionThreshold =
                runCatching { chatterGenerator.config.normalizedReactionThreshold }
                    .getOrDefault(ChatterConfig.DEFAULT_REACTION_THRESHOLD)
            val personaState = personaStateProvider.currentState(baseThreshold)
            session.recordPersonaState(
                circadianPeriod = personaState.circadianPeriod?.name,
                personaMood = personaState.mood?.label,
                effectiveThreshold = personaState.effectiveThreshold,
                reactionThreshold = reactionThreshold,
            )

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
                val emojiRoundBudget = EmojiVisionTurnBudget()

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

                    val annotatedDecisionTranscript =
                        emojiCatalog.prepareTranscript(
                            transcript = decisionContext.transcript,
                            roundBudget = emojiRoundBudget,
                        )
                    currentCoroutineContext().ensureActive()
                    val annotatedDecisionContext = decisionContext.copy(transcript = annotatedDecisionTranscript)

                    val effectiveThreshold = personaState.effectiveThreshold
                    val decision =
                        try {
                            decisionClient.decide(
                                annotatedDecisionContext.transcript,
                                annotatedDecisionContext.images,
                                effectiveThreshold = effectiveThreshold,
                            )
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
                        val noul = decision.noul
                        if (noul != null && reactionThreshold < effectiveThreshold && noul >= reactionThreshold) {
                            return@coroutineScope handleSilentReactionPath(
                                message = message,
                                decisionContext = annotatedDecisionContext,
                                personaState = personaState,
                                noul = noul,
                                confidence = decision.confidence,
                                effectiveThreshold = effectiveThreshold,
                                reactionThreshold = reactionThreshold,
                                readingJob = readingJob,
                                session = session,
                                markDispatching = markDispatching,
                                emojiRoundBudget = emojiRoundBudget,
                            )
                        }

                        logger.debug(
                            "Decision client evaluated shouldParticipate=false for message {} (noul={}, effectiveThreshold={}, reactionThreshold={})",
                            message.id,
                            decision.noul,
                            effectiveThreshold,
                            reactionThreshold,
                        )
                        if (decision.noul != null) {
                            session.skip(
                                reason =
                                    "Participation decision skipped (noul=${decision.noul}, " +
                                        "confidence=${decision.confidence}, threshold=$effectiveThreshold, " +
                                        "reactionThreshold=$reactionThreshold, baseThreshold=$baseThreshold)",
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

                val preparedEmojis =
                    prepareGuildPromptEmojis(
                        message = message,
                        transcript = replyContext.transcript,
                        fallbackDescription = "degrading to emoji-free text reply",
                        roundBudget = emojiRoundBudget,
                    )

                val authorId =
                    runCatching { message.author?.id }.getOrNull()
                        ?: runCatching { message.data.author.id }.getOrNull()
                val guildId = runCatching { message.data.guildId.value }.getOrNull()

                val chatterInput =
                    replyContext
                        .toChatterInput(isDirectMention = isDirectMention)
                        .copy(
                            transcript = preparedEmojis.transcript,
                            personaState = personaState,
                            guildEmojis = preparedEmojis.guildEmojis,
                            guildId = guildId?.toString(),
                            channelId = message.channelId.toString(),
                            authorId = authorId?.toString(),
                        )

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

                val allowedCustomEmojiIds = preparedEmojis.guildEmojis.map { it.id.toString() }.toSet()
                val sanitizedReply = EmojiSanitizer.stripEmojis(replyText, allowedCustomEmojiIds = allowedCustomEmojiIds).trim()
                val segments = ChatterPromptBuilder.splitReplySegments(sanitizedReply)
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

    private suspend fun resolveGuildEmojis(
        message: Message,
        fallbackDescription: String,
    ): List<GuildCustomEmoji> {
        val guildEmojis =
            try {
                guildEmojisProvider(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Failed to resolve guild emojis for message {} in channel {}; {}: {}",
                    message.id,
                    message.channelId,
                    fallbackDescription,
                    e.message,
                )
                return emptyList()
            }
        if (guildEmojis.isEmpty()) {
            logger.debug(
                "No guild custom emojis available for message {} in channel {}; {}",
                message.id,
                message.channelId,
                fallbackDescription,
            )
        }
        return guildEmojis
    }

    private suspend fun prepareGuildPromptEmojis(
        message: Message,
        transcript: String?,
        fallbackDescription: String,
        roundBudget: EmojiVisionTurnBudget,
    ): PreparedPromptEmojis {
        val guildId = runCatching { message.data.guildId.value }.getOrNull()
        val availableGuildEmojis =
            resolveGuildEmojis(
                message = message,
                fallbackDescription = fallbackDescription,
            )
        currentCoroutineContext().ensureActive()

        val prepared =
            if (transcript != null) {
                emojiCatalog.preparePromptEmojis(
                    transcript = transcript,
                    guildId = guildId,
                    availableGuildEmojis = availableGuildEmojis,
                    limit = ChatterPromptBuilder.MAX_PROMPT_GUILD_EMOJIS,
                    roundBudget = roundBudget,
                )
            } else {
                PreparedPromptEmojis(
                    transcript = "",
                    guildEmojis =
                        emojiCatalog.prepareGuildEmojiPool(
                            guildId = guildId,
                            availableGuildEmojis = availableGuildEmojis,
                            limit = ChatterPromptBuilder.MAX_PROMPT_GUILD_EMOJIS,
                            roundBudget = roundBudget,
                        ),
                )
            }
        currentCoroutineContext().ensureActive()
        return prepared
    }

    private suspend fun handleSilentReactionPath(
        message: Message,
        decisionContext: ChatterDecisionContext,
        personaState: PersonaState,
        noul: Double,
        confidence: Double?,
        effectiveThreshold: Double,
        reactionThreshold: Double,
        readingJob: Job,
        session: AiTraceSession,
        markDispatching: () -> Boolean,
        emojiRoundBudget: EmojiVisionTurnBudget,
    ): Boolean {
        val preparedEmojis =
            prepareGuildPromptEmojis(
                message = message,
                transcript = null,
                fallbackDescription = "continuing with Unicode reactions only",
                roundBudget = emojiRoundBudget,
            )

        val reactionInput =
            ChatterInput(
                transcript = decisionContext.transcript,
                imageAttachments = decisionContext.images,
                isDirectMention = false,
                personaState = personaState,
                guildEmojis = preparedEmojis.guildEmojis,
            )

        val selectedEmoji =
            try {
                chatterGenerator.selectReactionEmoji(reactionInput)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "ChatterGenerator failed during silent reaction selection for message {}: {}",
                    message.id,
                    e.message,
                    e,
                )
                session.skip(
                    reason = "Silent reaction selection failed: ${e.message}",
                    failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR,
                )
                return false
            }
        currentCoroutineContext().ensureActive()

        if (selectedEmoji.isNullOrBlank()) {
            logger.debug(
                "Silent reaction selection returned no valid emoji for message {} (noul={})",
                message.id,
                noul,
            )
            if (session.currentOutcome() != AiTraceOutcome.SKIPPED) {
                session.skip(
                    reason =
                        "Silent reaction selection returned no valid emoji (noul=$noul, " +
                            "reactionThreshold=$reactionThreshold, effectiveThreshold=$effectiveThreshold)",
                    failureCategory = AiTraceFailureCategory.EMPTY_OUTPUT,
                )
            }
            return false
        }

        val reactionEmoji = ChatterReactionEmojiValidator.toReactionEmoji(selectedEmoji, preparedEmojis.guildEmojis)
        if (reactionEmoji == null) {
            logger.warn(
                "Silent reaction emoji '{}' failed post-selection Discord conversion for message {}",
                selectedEmoji,
                message.id,
            )
            session.skip(
                reason = "Silent reaction emoji failed validation before Discord dispatch",
                failureCategory = AiTraceFailureCategory.PARSE_ERROR,
            )
            return false
        }

        readingJob.join()
        currentCoroutineContext().ensureActive()

        if (!markDispatching()) {
            session.skip(reason = "Cancelled right before silent reaction dispatch in channel ${message.channelId}")
            return false
        }

        try {
            dispatchReaction(message, reactionEmoji)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Failed to add silent reaction '{}' to message {} in channel {}: {}",
                selectedEmoji,
                message.id,
                message.channelId,
                e.message,
                e,
            )
            session.skip(
                reason = "Failed to add Discord reaction: ${e.message}",
                failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR,
            )
            return false
        }

        session.complete(finalOutput = selectedEmoji)
        logger.info(
            "Chatter added silent reaction {} to message {} in channel {} " +
                "(noul={}, confidence={}, reactionThreshold={}, effectiveThreshold={})",
            selectedEmoji,
            message.id,
            message.channelId,
            noul,
            confidence,
            reactionThreshold,
            effectiveThreshold,
        )
        return true
    }

    private suspend fun dispatchReaction(
        message: Message,
        emoji: ReactionEmoji,
    ) {
        if (reactionSender != null) {
            reactionSender.invoke(message, emoji)
        } else {
            message.addReaction(emoji)
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

        val defaultGuildEmojisProvider: suspend (message: Message) -> List<GuildCustomEmoji> = { message ->
            val guild = message.getGuildOrNull()
            if (guild == null) {
                emptyList()
            } else {
                guild.emojis.toList().toAvailableGuildCustomEmojis()
            }
        }

        internal fun Iterable<GuildEmoji>.toAvailableGuildCustomEmojis(): List<GuildCustomEmoji> =
            mapNotNull { emoji ->
                val emojiName = emoji.name?.takeIf { it.isNotBlank() }
                if (emoji.isAvailable && emojiName != null) {
                    GuildCustomEmoji(
                        id = emoji.id,
                        name = emojiName,
                        isAnimated = emoji.isAnimated,
                    )
                } else {
                    null
                }
            }
    }
}
