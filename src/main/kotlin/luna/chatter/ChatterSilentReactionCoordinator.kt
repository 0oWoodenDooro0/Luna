package luna.chatter

import dev.kord.core.entity.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import luna.chatter.emoji.ChatterEmojiTurn
import luna.chatter.emoji.ReactionResolution
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTraceSession
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Coordinates the `Silent Reaction Path` when `Participation Decision` falls between
 * `reactionThreshold` and `effectiveThreshold`.
 */
internal class ChatterSilentReactionCoordinator(
    private val chatterGenerator: ChatterGenerator,
    private val deliveryEngine: ChatterDeliveryEngine,
    private val logger: Logger = LoggerFactory.getLogger(ChatterSilentReactionCoordinator::class.java),
) {
    suspend fun handleSilentReactionPath(
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
                coldReaction = decisionContext.coldReaction,
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
