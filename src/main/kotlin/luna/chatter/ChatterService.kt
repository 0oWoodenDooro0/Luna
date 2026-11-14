package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.Message
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.withAiTraceSession
import org.slf4j.LoggerFactory

/**
 * Service orchestrating the Chatter auto-reply pipeline:
 * 1. Pre-filtering incoming messages (noise, bot, command, cooldown, allowed channels).
 * 2. Assembling recent dialogue turns (2~3 messages) for Gemini Decision 1 participation evaluation.
 * 3. Triggering channel typing feedback when the decision model confirms participation.
 * 4. Assembling recent conversation history (10~20 messages) for Gemini dialogue generation.
 * 5. Sending generated reply to the Discord channel and refreshing cooldown.
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

        // 1. Pre-checks filter: filter.shouldEvaluate(message, isDirectMention = isDirectMention)
        if (!filter.shouldEvaluate(message, isDirectMention = isDirectMention)) {
            logger.debug("ChatterNoiseFilter rejected message {}", message.id)
            return false
        }

        // Pre-filter ambient messages before fetching channel history
        if (!isDirectMention && !filter.isEligibleForDecisionEvaluation(message)) {
            logger.debug(
                "Ambient message {} does not meet decision evaluation criteria; skipping decision call",
                message.id,
            )
            return false
        }

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
                        channelId = message.channelId.toString(),
                        messageId = message.id.toString(),
                        authorId =
                            runCatching { message.author?.id?.toString() }.getOrNull()
                                ?: runCatching {
                                    message.data.author.id
                                        .toString()
                                }.getOrNull(),
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
                )
            }
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
    ): Boolean {
        // 2. Gathers recent messages from channel slice to construct state / history
        val previousMessages =
            try {
                historyFetcher(message, historyMessageCount)
            } catch (e: Exception) {
                logger.warn("Failed to fetch channel history for message {}: {}", message.id, e.message)
                emptyList()
            }

        val allRaw = previousMessages + message

        // 3. Participation evaluation: bypass for direct bot mentions
        if (!isDirectMention) {
            val decisionContext = contextCollector.collectDecisionContext(allRaw, kordSelfId = selfId)
            if (decisionContext.transcript.isBlank() && decisionContext.images.isEmpty()) {
                logger.debug("Assembled decision dialogue state is blank; skipping evaluation")
                session.fail(
                    AiTraceFailureCategory.CONTEXT_ERROR,
                    "Assembled decision dialogue state is blank",
                )
                return false
            }

            val decision =
                try {
                    decisionClient.decide(decisionContext.transcript, decisionContext.images)
                } catch (e: Exception) {
                    logger.warn("Gemini decision evaluation threw error for state: {}", e.message)
                    session.fail(
                        AiTraceFailureCategory.UNEXPECTED_ERROR,
                        "Gemini decision evaluation threw error: ${e.message}",
                    )
                    return false
                }

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
                return false
            }
        } else {
            logger.info("Direct bot mention detected on message {}; bypassing decision evaluation", message.id)
        }

        // 4. Decision confirms participation: trigger channel.type()
        val channel =
            try {
                message.channel
            } catch (e: Exception) {
                logger.warn("Unable to obtain channel for message {}: {}", message.id, e.message)
                session.fail(
                    AiTraceFailureCategory.CONTEXT_ERROR,
                    "Unable to obtain channel for message ${message.id}: ${e.message}",
                )
                return false
            }

        try {
            channel.type()
        } catch (e: Exception) {
            logger.warn("channel.type() call failed on channel {}: {}", message.channelId, e.message)
        }

        // Assemble heavy reply context (15 messages, reply chains, images, SocialPeek)
        val replyContext = contextCollector.collectReplyContext(allRaw, kordSelfId = selfId)
        if (replyContext.transcript.isBlank() &&
            replyContext.imageAttachments.isEmpty() &&
            replyContext.videoUri.isNullOrBlank()
        ) {
            logger.debug("No valid chat messages or media found in reply context for message {}", message.id)
            session.fail(
                AiTraceFailureCategory.CONTEXT_ERROR,
                "Reply context is blank and contains no media",
            )
            return false
        }

        val chatterInput = replyContext.toChatterInput(isDirectMention = isDirectMention)

        // Calls chatterGenerator.generateReply(chatterInput)
        val replyText =
            try {
                chatterGenerator.generateReply(chatterInput)
            } catch (e: Exception) {
                logger.error("ChatterGenerator failed to generate reply: {}", e.message, e)
                session.fail(
                    AiTraceFailureCategory.UNEXPECTED_ERROR,
                    "ChatterGenerator failed to generate reply: ${e.message}",
                )
                return false
            }

        // If reply is not null/blank:
        if (replyText.isNullOrBlank()) {
            logger.debug("ChatterGenerator returned null or blank reply for message {}", message.id)
            if (replyText != null) {
                session.fail(
                    AiTraceFailureCategory.EMPTY_OUTPUT,
                    "ChatterGenerator returned blank reply",
                )
            }
            return false
        }

        // Sends message directly to channel using channel.createMessage(replyText)
        try {
            if (messageSender != null) {
                messageSender.invoke(channel, replyText)
            } else {
                channel.createMessage(replyText)
            }
        } catch (e: Exception) {
            logger.error("Failed to send reply to channel {}: {}", message.channelId, e.message, e)
            session.fail(
                AiTraceFailureCategory.UNEXPECTED_ERROR,
                "Failed to deliver Discord reply: ${e.message}",
            )
            return false
        }

        // Refreshes channel cooldown timestamp
        cooldownCache.record(message.channelId)
        session.complete(finalOutput = replyText)
        logger.info("Chatter sent reply to channel {}: {}", message.channelId, replyText)
        return true
    }

    companion object {
        const val DEFAULT_DECISION_MESSAGE_COUNT = 3
        const val DEFAULT_HISTORY_MESSAGE_COUNT = 15
    }
}
