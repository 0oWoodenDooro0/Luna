package luna.chatter

import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.Message
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
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
        try {
            val selfId = runCatching { message.kord.selfId }.getOrNull()
            val isDirectMention = filter.isDirectBotMention(message, selfId)

            // 1. Pre-checks filter: filter.shouldEvaluate(message, isDirectMention = isDirectMention)
            if (!filter.shouldEvaluate(message, isDirectMention = isDirectMention)) {
                logger.debug("ChatterNoiseFilter rejected message {}", message.id)
                return false
            }

            // 2. Gathers recent messages from channel slice to construct state / history
            val previousMessages =
                try {
                    historyFetcher(message, historyMessageCount)
                } catch (e: Exception) {
                    logger.warn("Failed to fetch channel history for message {}: {}", message.id, e.message)
                    emptyList()
                }

            val allRaw = previousMessages + message
            val chatHistory = filter.filterHistory(allRaw, selfId)
            if (chatHistory.isEmpty()) {
                logger.debug("No valid chat messages found in history for message {}", message.id)
                return false
            }

            // 3. Participation evaluation: bypass for direct bot mentions; pre-filter ambient messages
            if (!isDirectMention) {
                if (!filter.isEligibleForDecisionEvaluation(message.content)) {
                    logger.debug(
                        "Ambient message {} does not meet decision evaluation criteria; skipping decision call",
                        message.id,
                    )
                    return false
                }

                val decisionSlice = chatHistory.takeLast(decisionMessageCount.coerceAtLeast(1))
                val decisionState = ChatterPromptBuilder.formatTranscript(decisionSlice)
                if (decisionState.isBlank()) {
                    logger.debug("Assembled decision dialogue state is blank; skipping evaluation")
                    return false
                }

                val decision =
                    try {
                        decisionClient.decide(decisionState)
                    } catch (e: Exception) {
                        logger.warn("Gemini decision evaluation threw error for state: {}", e.message)
                        ParticipationDecision(shouldParticipate = false)
                    }

                if (!decision.shouldParticipate) {
                    logger.debug("Decision client evaluated shouldParticipate=false for message {}", message.id)
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
                    return false
                }

            try {
                channel.type()
            } catch (e: Exception) {
                logger.warn("channel.type() call failed on channel {}: {}", message.channelId, e.message)
            }

            // Collects recent 10~20 channel messages (chronological order)
            val geminiHistory = chatHistory.takeLast(historyMessageCount.coerceAtLeast(1))

            // Calls chatterGenerator.generateReply(history)
            val replyText =
                try {
                    chatterGenerator.generateReply(geminiHistory)
                } catch (e: Exception) {
                    logger.error("ChatterGenerator failed to generate reply: {}", e.message, e)
                    null
                }

            // If reply is not null/blank:
            if (replyText.isNullOrBlank()) {
                logger.debug("ChatterGenerator returned null or blank reply for message {}", message.id)
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
                return false
            }

            // Refreshes channel cooldown timestamp
            cooldownCache.record(message.channelId)
            logger.info("Chatter sent reply to channel {}: {}", message.channelId, replyText)
            return true
        } catch (e: Exception) {
            logger.error("Unexpected error in ChatterService.processMessage: {}", e.message, e)
            return false
        }
    }

    companion object {
        const val DEFAULT_DECISION_MESSAGE_COUNT = 3
        const val DEFAULT_HISTORY_MESSAGE_COUNT = 15
    }
}
