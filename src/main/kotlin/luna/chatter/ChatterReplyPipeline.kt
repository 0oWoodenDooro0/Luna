package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import luna.chatter.emoji.ChatterEmojiTurn
import luna.chatter.emoji.PreparedPromptEmojis
import luna.chatter.memory.UserRelationship
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceSession
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal data class ReplyPipelineContext(
    val message: Message,
    val allRaw: List<Message>,
    val selfId: Snowflake?,
    val guildId: Snowflake?,
    val authorId: Snowflake?,
    val isDirectMention: Boolean,
    val personaState: PersonaState,
    val relationship: UserRelationship,
    val effectiveThreshold: Double,
    val reactionThreshold: Double,
    val emojiTurn: ChatterEmojiTurn,
    val readingScope: ReadingSimulationScope,
    val session: AiTraceSession,
    val markDispatching: () -> Boolean,
)

/**
 * Executes reply context assembly, generation, `Selective Mention Disengagement` handling,
 * validation/segmentation, `Segmented Burst Delivery`, and post-delivery state recording.
 */
internal class ChatterReplyPipeline(
    private val contextCollector: ChatterContextCollector,
    private val chatterGenerator: ChatterGenerator,
    private val silentReactionCoordinator: ChatterSilentReactionCoordinator,
    private val deliveryEngine: ChatterDeliveryEngine,
    private val filter: ChatterNoiseFilter,
    private val logger: Logger = LoggerFactory.getLogger(ChatterReplyPipeline::class.java),
) {
    suspend fun execute(context: ReplyPipelineContext): Boolean {
        val message = context.message
        val replyContext = collectReplyContext(context) ?: return false
        currentCoroutineContext().ensureActive()

        if (replyContext.transcript.isBlank() &&
            replyContext.imageAttachments.isEmpty() &&
            replyContext.videoUri.isNullOrBlank()
        ) {
            logger.debug("No valid chat messages or media found in reply context for message {}", message.id)
            context.session.fail(
                AiTraceFailureCategory.CONTEXT_ERROR,
                "Reply context is blank and contains no media",
            )
            return false
        }

        val preparedEmojis = context.emojiTurn.prepareReplyEmojis(replyContext.transcript)
        val allowMentionDisengagement =
            ChatterAffinityHelper.allowsMentionDisengagement(
                isDirectMention = context.isDirectMention,
                relationship = context.relationship,
            )
        val chatterInput = buildChatterInput(context, replyContext, preparedEmojis, allowMentionDisengagement)

        val replyText = generateReply(context, chatterInput) ?: return false
        currentCoroutineContext().ensureActive()

        val cleanedReplyText =
            when (
                val outcome =
                    ChatterPromptBuilder.parseDisengagementOutcome(
                        replyText,
                        allowDisengagement = allowMentionDisengagement,
                    )
            ) {
                is ChatterPromptBuilder.DisengagementOutcome.NoReply -> {
                    logger.info(
                        "Direct mention disengaged with [NO_REPLY] for {} user {} in channel {}",
                        context.relationship.tier,
                        context.authorId,
                        message.channelId,
                    )
                    context.session.skip(
                        reason = "Direct mention disengaged with [NO_REPLY] for ${context.relationship.tier.name} user",
                    )
                    return false
                }

                is ChatterPromptBuilder.DisengagementOutcome.ColdReact -> {
                    return handleColdReact(context, replyContext, preparedEmojis)
                }

                is ChatterPromptBuilder.DisengagementOutcome.Text -> {
                    outcome.text
                }
            }

        val segments = validateAndSplitSegments(context, cleanedReplyText) ?: return false
        if (!deliverSegments(context, segments)) return false

        val deliveredOutput = segments.joinToString("\n")
        filter.cooldownCache.record(message.channelId)
        filter.recordMomentumReply(channelId = message.channelId, isDirectMention = context.isDirectMention)
        context.emojiTurn.recordDeliveredReply(segments)
        context.session.complete(finalOutput = deliveredOutput)
        logger.info("Chatter sent reply to channel {}: {}", message.channelId, deliveredOutput)
        return true
    }

    private suspend fun collectReplyContext(context: ReplyPipelineContext): PreparedChatterContext? =
        try {
            contextCollector.collectReplyContext(context.allRaw, kordSelfId = context.selfId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Error collecting reply context for message {}: {}", context.message.id, e.message)
            context.session.fail(
                AiTraceFailureCategory.CONTEXT_ERROR,
                "Error collecting reply context for message ${context.message.id}: ${e.message}",
            )
            null
        }

    private fun buildChatterInput(
        context: ReplyPipelineContext,
        replyContext: PreparedChatterContext,
        preparedEmojis: PreparedPromptEmojis,
        allowMentionDisengagement: Boolean,
    ): ChatterInput =
        replyContext
            .toChatterInput(isDirectMention = context.isDirectMention)
            .copy(
                transcript = preparedEmojis.transcript,
                personaState = context.personaState,
                guildEmojis = preparedEmojis.guildEmojis,
                applicationEmojis = preparedEmojis.applicationEmojis,
                guildId = context.guildId?.toString(),
                channelId = context.message.channelId.toString(),
                authorId = context.authorId?.toString(),
                allowMentionDisengagement = allowMentionDisengagement,
            )

    private suspend fun generateReply(
        context: ReplyPipelineContext,
        chatterInput: ChatterInput,
    ): String? {
        val replyText =
            try {
                chatterGenerator.generateReply(chatterInput)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("ChatterGenerator failed to generate reply: {}", e.message, e)
                context.session.fail(
                    AiTraceFailureCategory.UNEXPECTED_ERROR,
                    "ChatterGenerator failed to generate reply: ${e.message}",
                )
                return null
            }
        if (replyText == null) {
            logger.debug("ChatterGenerator returned null reply for message {}", context.message.id)
        }
        return replyText
    }

    private suspend fun handleColdReact(
        context: ReplyPipelineContext,
        replyContext: PreparedChatterContext,
        preparedEmojis: PreparedPromptEmojis,
    ): Boolean =
        silentReactionCoordinator.handleSilentReactionPath(
            message = context.message,
            decisionContext =
                ChatterDecisionContext(
                    transcript = preparedEmojis.transcript,
                    images = replyContext.imageAttachments,
                    coldReaction = true,
                ),
            personaState = context.personaState,
            noul = 0.0,
            confidence = null,
            effectiveThreshold = context.effectiveThreshold,
            reactionThreshold = context.reactionThreshold,
            readingScope = context.readingScope,
            session = context.session,
            markDispatching = context.markDispatching,
            emojiTurn = context.emojiTurn,
        )

    private fun validateAndSplitSegments(
        context: ReplyPipelineContext,
        cleanedReplyText: String,
    ): List<String>? {
        if (cleanedReplyText.isBlank()) {
            logger.debug("ChatterGenerator returned blank reply for message {}", context.message.id)
            context.session.fail(
                AiTraceFailureCategory.EMPTY_OUTPUT,
                "ChatterGenerator returned blank reply",
            )
            return null
        }

        val segments = ChatterPromptBuilder.splitReplySegments(cleanedReplyText)
        if (segments.isEmpty()) {
            logger.debug("ChatterPromptBuilder.splitReplySegments returned no segments for message {}", context.message.id)
            context.session.fail(
                AiTraceFailureCategory.EMPTY_OUTPUT,
                "ChatterGenerator returned blank segments",
            )
            return null
        }
        return segments
    }

    private suspend fun deliverSegments(
        context: ReplyPipelineContext,
        segments: List<String>,
    ): Boolean {
        val message = context.message
        val delivered =
            try {
                deliveryEngine.deliverSegmentedReply(
                    anchor = message,
                    segments = segments,
                    selfId = context.selfId,
                    readingSimulation = context.readingScope,
                    markDispatching = context.markDispatching,
                    pingRepliedUser = context.isDirectMention,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to send reply to channel {}: {}", message.channelId, e.message, e)
                context.session.fail(AiTraceFailureCategory.UNEXPECTED_ERROR, "Failed to deliver Discord reply: ${e.message}")
                return false
            }
        if (delivered == DeliveryOutcome.CancelledBeforeDispatch) {
            context.session.skip(reason = "Cancelled right before dispatch lock in channel ${message.channelId}")
            return false
        }
        return true
    }
}
