package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.Message
import dev.kord.core.event.guild.EmojisUpdateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.ReactionAddEvent
import dev.kord.core.on
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import luna.chatter.emoji.BoundedChannelEmojiCadenceTracker
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
    val emojiCatalog: ChatterEmojiCatalog = NoOpChatterEmojiCatalog,
    val emojiCadenceTracker: BoundedChannelEmojiCadenceTracker = BoundedChannelEmojiCadenceTracker(),
    val randomProvider: (minInclusive: Long, maxInclusive: Long) -> Long = { min, max ->
        Random.nextLong(min, max + 1)
    },
) {
    private val logger = LoggerFactory.getLogger(ChatterService::class.java)
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
    internal suspend fun preScanStartupGuilds(kord: Kord) {
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
    internal suspend fun onMessageCreate(event: MessageCreateEvent): Boolean = processMessage(event.message)

    /**
     * Handles incoming [ReactionAddEvent] for custom emoji usage tracking.
     */
    internal suspend fun onReactionAdd(event: ReactionAddEvent) {
        val guildId = event.guildId ?: return
        val selfId = runCatching { event.kord.selfId }.getOrNull()
        if (selfId != null && event.userId == selfId) return
        emojiCatalog.recordReactionUsage(guildId = guildId, emoji = event.emoji)
    }

    /**
     * Handles incoming [EmojisUpdateEvent] for background guild custom emoji pre-scanning.
     */
    internal suspend fun onEmojisUpdate(event: EmojisUpdateEvent) {
        val availableEmojis = event.emojis.toAvailableGuildCustomEmojis()
        emojiCatalog.preScanGuildEmojis(guildId = event.guildId, emojis = availableEmojis)
    }

    /**
     * Core pipeline processing an incoming [Message].
     */
    suspend fun processMessage(message: Message): Boolean {
        val snapshot = extractTriggerSnapshot(message)
        val isDirectMention = filter.isDirectBotMention(message, snapshot.selfId)

        if (!snapshot.isBotAuthor) {
            emojiCatalog.recordMessageUsage(guildId = snapshot.guildId, content = snapshot.content)
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
            val emojiRoundBudget = EmojiVisionTurnBudget()

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
                        return@withReadingSimulation false
                    }
                currentCoroutineContext().ensureActive()

                if (!decision.shouldParticipate) {
                    val noul = decision.noul
                    if (noul != null && reactionThreshold < effectiveThreshold && noul >= reactionThreshold) {
                        return@withReadingSimulation handleSilentReactionPath(
                            message = message,
                            guildId = snapshot.guildId,
                            decisionContext = annotatedDecisionContext,
                            personaState = personaState,
                            noul = noul,
                            confidence = decision.confidence,
                            effectiveThreshold = effectiveThreshold,
                            reactionThreshold = reactionThreshold,
                            readingScope = readingScope,
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

            val allowReplyEmojis = emojiCadenceTracker.shouldAllowReplyCustomEmojis(message.channelId)
            val preparedEmojis =
                prepareGuildPromptEmojis(
                    message = message,
                    guildId = snapshot.guildId,
                    transcript = replyContext.transcript,
                    fallbackDescription = "degrading to emoji-free text reply",
                    roundBudget = emojiRoundBudget,
                    limit = ChatterPromptBuilder.MAX_REPLY_PROMPT_GUILD_EMOJIS,
                    includeGuildPool = allowReplyEmojis,
                )

            val chatterInput =
                replyContext
                    .toChatterInput(isDirectMention = isDirectMention)
                    .copy(
                        transcript = preparedEmojis.transcript,
                        personaState = personaState,
                        guildEmojis = preparedEmojis.guildEmojis,
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

            val allowedCustomEmojiIds = preparedEmojis.guildEmojis.map { it.id.toString() }.toSet()
            val sanitizedReply = EmojiSanitizer.stripEmojis(replyText, allowedCustomEmojiIds = allowedCustomEmojiIds).trim()
            val segments = ChatterPromptBuilder.splitReplySegments(sanitizedReply)
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
            emojiCadenceTracker.recordDeliveredReply(message.channelId, deliveredOutput)
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

    private fun resolveAuthorId(message: Message): Snowflake? =
        runCatching { message.author?.id }.getOrNull() ?: runCatching { message.data.author.id }.getOrNull()

    private suspend fun resolveGuildEmojis(
        message: Message,
        fallbackDescription: String,
    ): List<GuildCustomEmoji> {
        val guildEmojis =
            try {
                channelPort.listGuildCustomEmojis(message).toAvailableGuildCustomEmojis()
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
            logger.debug("No guild custom emojis available for message {} in channel {}; {}", message.id, message.channelId, fallbackDescription)
        }
        return guildEmojis
    }

    private suspend fun prepareGuildPromptEmojis(
        message: Message,
        guildId: Snowflake?,
        transcript: String?,
        fallbackDescription: String,
        roundBudget: EmojiVisionTurnBudget,
        limit: Int = ChatterPromptBuilder.MAX_PROMPT_GUILD_EMOJIS,
        includeGuildPool: Boolean = true,
    ): PreparedPromptEmojis {
        if (!includeGuildPool) {
            logger.debug("Skipping reply guild emoji pool for channel {} due to reply emoji cadence cooldown", message.channelId)
            val annotatedTranscript = transcript?.let { emojiCatalog.prepareTranscript(transcript = it, roundBudget = roundBudget) }.orEmpty()
            currentCoroutineContext().ensureActive()
            return PreparedPromptEmojis(transcript = annotatedTranscript, guildEmojis = emptyList())
        }

        val availableGuildEmojis = resolveGuildEmojis(message = message, fallbackDescription = fallbackDescription)
        currentCoroutineContext().ensureActive()

        val prepared =
            if (transcript != null) {
                emojiCatalog.preparePromptEmojis(transcript, guildId, availableGuildEmojis, limit, roundBudget)
            } else {
                PreparedPromptEmojis("", emojiCatalog.prepareGuildEmojiPool(guildId, availableGuildEmojis, limit, roundBudget))
            }
        currentCoroutineContext().ensureActive()
        return prepared
    }

    private suspend fun handleSilentReactionPath(
        message: Message,
        guildId: Snowflake?,
        decisionContext: ChatterDecisionContext,
        personaState: PersonaState,
        noul: Double,
        confidence: Double?,
        effectiveThreshold: Double,
        reactionThreshold: Double,
        readingScope: ReadingSimulationScope,
        session: AiTraceSession,
        markDispatching: () -> Boolean,
        emojiRoundBudget: EmojiVisionTurnBudget,
    ): Boolean {
        if (emojiCadenceTracker.isReactionOnCooldown(message.channelId)) {
            logger.debug("Silent reaction skipped for message {} in channel {} due to reaction cooldown", message.id, message.channelId)
            session.skip(reason = "Silent reaction skipped due to channel reaction cooldown")
            return false
        }

        val preparedEmojis =
            prepareGuildPromptEmojis(
                message = message,
                guildId = guildId,
                transcript = null,
                fallbackDescription = "continuing with Unicode reactions only",
                roundBudget = emojiRoundBudget,
                limit = ChatterPromptBuilder.MAX_PROMPT_GUILD_EMOJIS,
            )

        val reactionInput =
            ChatterInput(
                transcript = decisionContext.transcript,
                imageAttachments = decisionContext.images,
                isDirectMention = false,
                personaState = personaState,
                guildEmojis = preparedEmojis.guildEmojis,
                recentReactions = emojiCadenceTracker.recentReactions(message.channelId),
            )

        val selectedEmoji =
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

        if (selectedEmoji.isNullOrBlank()) {
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

        val reactionEmoji = ChatterReactionEmojiValidator.toReactionEmoji(selectedEmoji, preparedEmojis.guildEmojis)
        if (reactionEmoji == null) {
            logger.warn("Silent reaction emoji '{}' failed post-selection Discord conversion for message {}", selectedEmoji, message.id)
            session.skip(reason = "Silent reaction emoji failed validation before Discord dispatch", failureCategory = AiTraceFailureCategory.PARSE_ERROR)
            return false
        }

        val outcome =
            try {
                deliveryEngine.deliverSilentReaction(
                    target = message,
                    emoji = reactionEmoji,
                    readingSimulation = readingScope,
                    markDispatching = markDispatching,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to add silent reaction '{}' to message {} in channel {}: {}", selectedEmoji, message.id, message.channelId, e.message, e)
                session.skip(reason = "Failed to add Discord reaction: ${e.message}", failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR)
                return false
            }
        if (outcome == DeliveryOutcome.CancelledBeforeDispatch) {
            session.skip(reason = "Cancelled right before silent reaction dispatch in channel ${message.channelId}")
            return false
        }

        emojiCadenceTracker.recordDeliveredReaction(message.channelId, selectedEmoji)
        session.complete(finalOutput = selectedEmoji)
        logger.info(
            "Chatter added silent reaction {} to message {} in channel {} (noul={}, confidence={}, reactionThreshold={}, effectiveThreshold={})",
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

    companion object {
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
