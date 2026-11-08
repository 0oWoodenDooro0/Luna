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
 * 2. Assembling recent dialogue turns (2~3 messages) for Laya System 1 decision evaluation.
 * 3. Triggering channel typing feedback when Laya decides to participate.
 * 4. Assembling recent conversation history (10~20 messages) for Gemini dialogue generation.
 * 5. Sending generated reply to the Discord channel and refreshing cooldown.
 */
open class ChatterService(
    val filter: ChatterNoiseFilter = ChatterNoiseFilter(),
    val layaClient: LayaClient,
    val chatterGenerator: ChatterGenerator,
    val cooldownCache: BoundedChannelCooldownCache = filter.cooldownCache,
    val layaMessageCount: Int = DEFAULT_LAYA_MESSAGE_COUNT,
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
            // 1. Pre-checks filter: filter.shouldEvaluate(message) (and checks channel cooldown)
            if (!filter.shouldEvaluate(message)) {
                logger.debug("ChatterNoiseFilter rejected message {}", message.id)
                return false
            }

            // 2. Gathers recent messages from channel slice to construct state for Laya
            val previousMessages =
                try {
                    historyFetcher(message, historyMessageCount)
                } catch (e: Exception) {
                    logger.warn("Failed to fetch channel history for message {}: {}", message.id, e.message)
                    emptyList()
                }

            val allRaw = previousMessages + message
            val selfId = runCatching { message.kord.selfId }.getOrNull()
            val chatHistory = filter.filterHistory(allRaw, selfId)
            if (chatHistory.isEmpty()) {
                logger.debug("No valid chat messages found in history for message {}", message.id)
                return false
            }

            val layaSlice = chatHistory.takeLast(layaMessageCount.coerceAtLeast(1))
            val layaState = ChatterPromptBuilder.formatTranscript(layaSlice)
            if (layaState.isBlank()) {
                logger.debug("Assembled Laya dialogue state is blank; skipping evaluation")
                return false
            }

            // 3. Calls layaClient.evaluateParticipation(state)
            val decision =
                try {
                    layaClient.evaluateParticipation(layaState)
                } catch (e: Exception) {
                    logger.warn("Laya evaluation threw error for state: {}", e.message)
                    LayaDecision(shouldParticipate = false)
                }

            if (!decision.shouldParticipate) {
                logger.debug("Laya evaluated shouldParticipate=false for message {}", message.id)
                return false
            }

            // 4. Laya confirms participation: trigger channel.type()
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
        const val DEFAULT_LAYA_MESSAGE_COUNT = 3
        const val DEFAULT_HISTORY_MESSAGE_COUNT = 15
    }
}
