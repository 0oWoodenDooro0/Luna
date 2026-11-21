package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import dev.kord.core.event.guild.EmojisUpdateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.on
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import luna.chatter.emoji.ChatterEmojiService
import luna.chatter.emoji.ChatterEmojiTurn
import luna.chatter.emoji.DefaultChatterEmojiService
import luna.chatter.emoji.ReactionResolution
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
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
 * 3. Overlapping silent reading simulation (0.8s–2.0s via [randomProvider]) with participation decision and
 *    routing intermediate scores into the Silent Reaction Path or full reply generation.
 * 4. Triggering typing and length-paced typing delay (`min(charCount * 120ms, 4000ms)`) via [channelPort]
 *    and [ChatterDeliveryEngine] only when non-blank text reply output is ready.
 * 5. Locking dispatch, counting intervening post-trigger messages via [channelPort], delivering up to 3 segmented messages
 *    with asymmetric reply threading, and refreshing [filter]'s cooldown cache.
 */
class ChatterService(
    val decisionClient: GeminiDecisionClient,
    val chatterGenerator: ChatterGenerator,
    val filter: ChatterNoiseFilter = ChatterNoiseFilter(),
    val contextCollector: ChatterContextCollector = ChatterContextCollector(noiseFilter = filter),
    val channelPort: ChatterChannelPort = KordChatterChannelAdapter,
    val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
    val burstCoordinator: BoundedChannelBurstCoordinator = BoundedChannelBurstCoordinator(),
    val personaStateProvider: PersonaStateProvider = CircadianPersonaDrift(),
    val emojiService: ChatterEmojiService = DefaultChatterEmojiService(),
    val randomProvider: (minInclusive: Long, maxInclusive: Long) -> Long = { min, max ->
        Random.nextLong(min, max + 1)
    },
) {
    private val logger = LoggerFactory.getLogger(ChatterService::class.java)

    @Volatile
    private var backgroundScope: CoroutineScope? = null
    private val deliveryEngine =
        ChatterDeliveryEngine(
            channelPort = channelPort,
            randomProvider = randomProvider,
        )

    private data class TriggerSnapshot(
        val selfId: Snowflake?,
        val guildId: Snowflake?,
        val authorId: Snowflake?,
        val isBotAuthor: Boolean,
        val content: String,
    )

    /**
     * Registers the message create and reaction add listeners onto Kord.
     */
    fun register(
        kord: Kord,
        scope: CoroutineScope = safeScope(kord),
    ): Job {
        val parentJob = Job(scope.coroutineContext[Job])
        val listenerScope = CoroutineScope(scope.coroutineContext + parentJob)
        backgroundScope = listenerScope
        parentJob.invokeOnCompletion { if (backgroundScope === listenerScope) backgroundScope = null }
        kord.on<MessageCreateEvent>(scope = listenerScope) { onMessageCreate(this) }
        kord.on<ReactionAddEvent>(scope = listenerScope) { onReactionAdd(this) }
        kord.on<EmojisUpdateEvent>(scope = listenerScope) { onEmojisUpdate(this) }
        listenerScope.launch { emojiService.preScanStartup(kord) }
        return parentJob
    }

    /**
     * Handles incoming [MessageCreateEvent].
     */
    internal suspend fun onMessageCreate(event: MessageCreateEvent): Boolean = processMessage(event.message)

    /**
     * Handles incoming [ReactionAddEvent] for custom emoji usage tracking.
     */
    internal suspend fun onReactionAdd(event: ReactionAddEvent) {
        val selfId = runCatching { event.kord.selfId }.getOrNull()
        if (selfId != null && event.userId == selfId) return
        emojiService.recordReactionUsage(
            guildId = event.guildId,
            emoji = event.emoji,
        )
    }

    /**
     * Handles incoming [EmojisUpdateEvent] for background guild custom emoji pre-scanning.
     */
    internal suspend fun onEmojisUpdate(event: EmojisUpdateEvent) {
        val availableEmojis = event.emojis.toAvailableGuildCustomEmojis()
        emojiService.preScanGuild(guildId = event.guildId, emojis = availableEmojis)
    }

    /**
     * Core pipeline processing an incoming [Message].
     */
    suspend fun processMessage(message: Message): Boolean {
        val snapshot = extractTriggerSnapshot(message)
        val isDirectMention = filter.isDirectBotMention(message, snapshot.selfId)

        if (!snapshot.isBotAuthor) {
            emojiService.recordMessageUsage(
                guildId = snapshot.guildId,
                content = snapshot.content,
            )
        }

        // 1. Pre-checks filter before opening/extending/cancelling any debounce window
        if (!filter.shouldEvaluate(message, isDirectMention = isDirectMention, kordSelfId = snapshot.selfId)) {
            logger.debug("ChatterNoiseFilter rejected message {}", message.id)
            return false
        }

        val channelId = message.channelId

        return burstCoordinator.executeWithDebounce(
            channelId = channelId,
            messageId = message.id,
            authorId = snapshot.authorId,
            isDirectMention = isDirectMention,
            canProceedAfterWait = { filter.shouldEvaluate(message, isDirectMention = isDirectMention, kordSelfId = snapshot.selfId) },
        ) { effectiveIsDirectMention, markDispatching ->
            runTracedSession(message, snapshot, channelId, effectiveIsDirectMention, markDispatching)
        }
    }

    private suspend fun runTracedSession(
        message: Message,
        snapshot: TriggerSnapshot,
        channelId: Snowflake,
        isDirectMention: Boolean,
        markDispatching: () -> Boolean,
    ): Boolean {
        val session =
            traceRecorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = if (isDirectMention) AiTraceTrigger.MENTION else AiTraceTrigger.AMBIENT,
                discord =
                    AiTraceDiscordContext(
                        guildId = snapshot.guildId?.toString(),
                        channelId = channelId.toString(),
                        messageId = message.id.toString(),
                        authorId = snapshot.authorId?.toString(),
                        reactingUserId = null,
                        isDirectMention = isDirectMention,
                    ),
            )
        runCatching { session.registerSecret(decisionClient.config.apiKey) }
        runCatching { session.registerSecret(chatterGenerator.config.apiKey) }

        return try {
            withAiTraceSession(session) {
                processEvaluatedMessage(message, snapshot, isDirectMention, session, markDispatching)
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
        snapshot: TriggerSnapshot,
        isDirectMention: Boolean,
        session: AiTraceSession,
        markDispatching: () -> Boolean,
    ): Boolean =
        deliveryEngine.withReadingSimulation {
            val readingScope = this
            val baseThreshold = runCatching { decisionClient.config.threshold }.getOrDefault(ChatterDecisionConfig.DEFAULT_THRESHOLD)
            val reactionThreshold = runCatching { chatterGenerator.config.normalizedReactionThreshold }.getOrDefault(ChatterConfig.DEFAULT_REACTION_THRESHOLD)
            val personaState = personaStateProvider.currentState(baseThreshold)
            session.recordPersonaState(
                circadianPeriod = personaState.circadianPeriod?.name,
                personaMood = personaState.mood?.label,
                effectiveThreshold = personaState.effectiveThreshold,
                reactionThreshold = reactionThreshold,
            )

            // 2. Gathers recent messages from channel slice to construct state / history
            val fetchLimit = maxOf(contextCollector.decisionMessageCount, contextCollector.historyMessageCount)
            val previousMessages =
                try {
                    channelPort.fetchMessagesBefore(message, fetchLimit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Failed to fetch channel history for message {}: {}", message.id, e.message)
                    emptyList()
                }
            currentCoroutineContext().ensureActive()

            val allRaw = previousMessages + message
            val emojiTurn =
                emojiService.beginTurn(
                    channelId = message.channelId,
                    guildId = snapshot.guildId,
                    guildEmojisProvider = { resolveGuildCustomEmojis(message) },
                    applicationEmojisProvider = { channelPort.listApplicationEmojis(message) },
                    preScanScope = backgroundScope,
                )

            // 3. Participation evaluation: bypass for direct bot mentions
            if (!isDirectMention) {
                val decisionContext =
                    try {
                        contextCollector.collectDecisionContext(allRaw, kordSelfId = snapshot.selfId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Error collecting decision context for message {}: {}", message.id, e.message)
                        session.fail(
                            AiTraceFailureCategory.CONTEXT_ERROR,
                            "Error collecting decision context for message ${message.id}: ${e.message}",
                        )
                        return@withReadingSimulation false
                    }
                currentCoroutineContext().ensureActive()
                if (decisionContext.transcript.isBlank() && decisionContext.images.isEmpty()) {
                    logger.debug("Assembled decision dialogue state is blank; skipping evaluation")
                    session.fail(
                        AiTraceFailureCategory.CONTEXT_ERROR,
                        "Assembled decision dialogue state is blank",
                    )
                    return@withReadingSimulation false
                }

                val annotatedDecisionTranscript = emojiTurn.annotateTranscript(decisionContext.transcript)
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
                        return@withReadingSimulation false
                    }
                currentCoroutineContext().ensureActive()

                if (!decision.shouldParticipate) {
                    val noul = decision.noul
                    if (noul != null && reactionThreshold < effectiveThreshold && noul >= reactionThreshold) {
                        return@withReadingSimulation handleSilentReactionPath(
                            message = message,
                            decisionContext = annotatedDecisionContext,
                            personaState = personaState,
                            noul = noul,
                            confidence = decision.confidence,
                            effectiveThreshold = effectiveThreshold,
                            reactionThreshold = reactionThreshold,
                            readingScope = readingScope,
                            session = session,
                            markDispatching = markDispatching,
                            emojiTurn = emojiTurn,
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
                    return@withReadingSimulation false
                }
            } else {
                logger.info("Direct bot mention detected on message {}; bypassing decision evaluation", message.id)
            }

            // Assemble heavy reply context (15 messages, reply chains, images, SocialPeek)
            val replyContext =
                try {
                    contextCollector.collectReplyContext(allRaw, kordSelfId = snapshot.selfId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Error collecting reply context for message {}: {}", message.id, e.message)
                    session.fail(
                        AiTraceFailureCategory.CONTEXT_ERROR,
                        "Error collecting reply context for message ${message.id}: ${e.message}",
                    )
                    return@withReadingSimulation false
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
                return@withReadingSimulation false
            }

            val preparedEmojis = emojiTurn.prepareReplyEmojis(replyContext.transcript)

            val chatterInput =
                replyContext
                    .toChatterInput(isDirectMention = isDirectMention)
                    .copy(
                        transcript = preparedEmojis.transcript,
                        personaState = personaState,
                        guildEmojis = preparedEmojis.guildEmojis,
                        applicationEmojis = preparedEmojis.applicationEmojis,
                        guildId = snapshot.guildId?.toString(),
                        channelId = message.channelId.toString(),
                        authorId = snapshot.authorId?.toString(),
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
                    return@withReadingSimulation false
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
                return@withReadingSimulation false
            }

            val segments = ChatterPromptBuilder.splitReplySegments(replyText)
            if (segments.isEmpty()) {
                logger.debug("ChatterPromptBuilder.splitReplySegments returned no segments for message {}", message.id)
                session.fail(
                    AiTraceFailureCategory.EMPTY_OUTPUT,
                    "ChatterGenerator returned blank segments",
                )
                return@withReadingSimulation false
            }

            val delivered =
                try {
                    deliveryEngine.deliverSegmentedReply(
                        anchor = message,
                        segments = segments,
                        selfId = snapshot.selfId,
                        readingSimulation = readingScope,
                        markDispatching = markDispatching,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to send reply to channel {}: {}", message.channelId, e.message, e)
                    session.fail(AiTraceFailureCategory.UNEXPECTED_ERROR, "Failed to deliver Discord reply: ${e.message}")
                    return@withReadingSimulation false
                }
            if (delivered == DeliveryOutcome.CancelledBeforeDispatch) {
                session.skip(reason = "Cancelled right before dispatch lock in channel ${message.channelId}")
                return@withReadingSimulation false
            }

            val deliveredOutput = segments.joinToString("\n")
            filter.cooldownCache.record(message.channelId)
            emojiTurn.recordDeliveredReply(segments)
            session.complete(finalOutput = deliveredOutput)
            logger.info("Chatter sent reply to channel {}: {}", message.channelId, deliveredOutput)
            true
        }

    private fun extractTriggerSnapshot(message: Message): TriggerSnapshot =
        TriggerSnapshot(
            selfId = runCatching { message.kord.selfId }.getOrNull(),
            guildId = runCatching { message.data.guildId.value }.getOrNull(),
            authorId = resolveAuthorId(message),
            isBotAuthor = runCatching { message.author?.isBot }.getOrNull() ?: false,
            content = runCatching { message.content }.getOrNull().orEmpty(),
        )

    private suspend fun resolveGuildCustomEmojis(message: Message): List<GuildCustomEmoji> {
        val guildEmojis =
            try {
                channelPort.listGuildCustomEmojis(message).toAvailableGuildCustomEmojis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Failed to resolve guild emojis for message {} in channel {}: {}",
                    message.id,
                    message.channelId,
                    e.message,
                )
                return emptyList()
            }
        if (guildEmojis.isEmpty()) {
            logger.debug("No guild custom emojis available for message {} in channel {}", message.id, message.channelId)
        }
        return guildEmojis
    }

    private fun resolveAuthorId(message: Message): Snowflake? =
        runCatching { message.author?.id }.getOrNull() ?: runCatching { message.data.author.id }.getOrNull()

    private suspend fun handleSilentReactionPath(
        message: Message,
        decisionContext: ChatterDecisionContext,
        personaState: PersonaState,
        noul: Double,
        confidence: Double?,
        effectiveThreshold: Double,
        reactionThreshold: Double,
        readingScope: ReadingSimulationScope,
        session: AiTraceSession,
        markDispatching: () -> Boolean,
        emojiTurn: ChatterEmojiTurn,
    ): Boolean {
        if (emojiTurn.isReactionOnCooldown()) {
            logger.debug("Silent reaction skipped for message {} in channel {} due to reaction cooldown", message.id, message.channelId)
            session.skip(reason = "Silent reaction skipped due to channel reaction cooldown")
            return false
        }

        val preparedEmojis = emojiTurn.prepareReactionEmojis()

        val reactionInput =
            ChatterInput(
                transcript = decisionContext.transcript,
                imageAttachments = decisionContext.images,
                isDirectMention = false,
                personaState = personaState,
                guildEmojis = preparedEmojis.guildEmojis,
                applicationEmojis = preparedEmojis.applicationEmojis,
                recentReactions = preparedEmojis.recentReactions,
            )

        val rawSelection =
            try {
                chatterGenerator.selectReactionEmoji(reactionInput)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("ChatterGenerator failed during silent reaction selection for message {}: {}", message.id, e.message, e)
                session.skip(reason = "Silent reaction selection failed: ${e.message}", failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR)
                return false
            }
        currentCoroutineContext().ensureActive()

        if (rawSelection.isNullOrBlank()) {
            logger.debug("Silent reaction selection returned no valid emoji for message {} (noul={})", message.id, noul)
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

        val resolved =
            when (val resolution = emojiTurn.resolveReaction(rawSelection)) {
                is ReactionResolution.Declined -> {
                    logger.debug("Silent reaction selection explicitly declined to react (NONE) for message {}", message.id)
                    session.skip(reason = "Reaction selection model declined to react (NONE)")
                    return false
                }

                is ReactionResolution.Invalid -> {
                    logger.warn(
                        "Silent reaction selection returned invalid or unverified emoji '{}' for message {}",
                        rawSelection.trim().take(80),
                        message.id,
                    )
                    session.skip(reason = resolution.reason, failureCategory = AiTraceFailureCategory.PARSE_ERROR)
                    return false
                }

                is ReactionResolution.Valid -> {
                    resolution
                }
            }

        val outcome =
            try {
                deliveryEngine.deliverSilentReaction(
                    target = message,
                    emoji = resolved.reactionEmoji,
                    readingSimulation = readingScope,
                    markDispatching = markDispatching,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Failed to add silent reaction '{}' to message {} in channel {}: {}",
                    resolved.normalizedToken,
                    message.id,
                    message.channelId,
                    e.message,
                    e,
                )
                session.skip(reason = "Failed to add Discord reaction: ${e.message}", failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR)
                return false
            }
        if (outcome == DeliveryOutcome.CancelledBeforeDispatch) {
            session.skip(reason = "Cancelled right before silent reaction dispatch in channel ${message.channelId}")
            return false
        }

        emojiTurn.recordDeliveredReaction(resolved)
        session.complete(finalOutput = resolved.normalizedToken)
        logger.info(
            "Chatter added silent reaction {} to message {} in channel {} (noul={}, confidence={}, reactionThreshold={}, effectiveThreshold={})",
            resolved.normalizedToken,
            message.id,
            message.channelId,
            noul,
            confidence,
            reactionThreshold,
            effectiveThreshold,
        )
        return true
    }
}
