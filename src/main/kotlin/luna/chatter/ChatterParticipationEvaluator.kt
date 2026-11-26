package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import luna.chatter.emoji.ChatterEmojiTurn
import luna.chatter.memory.UserRelationship
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceSession
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal data class ParticipationEvaluationContext(
    val message: Message,
    val allRaw: List<Message>,
    val selfId: Snowflake?,
    val authorId: Snowflake?,
    val personaState: PersonaState,
    val relationship: UserRelationship,
    val baseThreshold: Double,
    val effectiveThreshold: Double,
    val reactionThreshold: Double,
    val emojiTurn: ChatterEmojiTurn,
    val readingScope: ReadingSimulationScope,
    val session: AiTraceSession,
    val markDispatching: () -> Boolean,
)

internal sealed interface ParticipationEvaluationResult {
    data object ProceedToReply : ParticipationEvaluationResult

    data class Completed(
        val outcome: Boolean,
    ) : ParticipationEvaluationResult
}

/**
 * Evaluates ambient `Participation Decision` and routes intermediate scores to the `Silent Reaction Path`.
 */
internal class ChatterParticipationEvaluator(
    private val contextCollector: ChatterContextCollector,
    private val decisionClient: GeminiDecisionClient,
    private val silentReactionCoordinator: ChatterSilentReactionCoordinator,
    private val logger: Logger = LoggerFactory.getLogger(ChatterParticipationEvaluator::class.java),
) {
    suspend fun evaluate(context: ParticipationEvaluationContext): ParticipationEvaluationResult {
        val message = context.message
        val decisionContext = collectDecisionContext(context) ?: return ParticipationEvaluationResult.Completed(false)
        currentCoroutineContext().ensureActive()

        if (decisionContext.transcript.isBlank() && decisionContext.images.isEmpty()) {
            logger.debug("Assembled decision dialogue state is blank; skipping evaluation")
            context.session.fail(
                AiTraceFailureCategory.CONTEXT_ERROR,
                "Assembled decision dialogue state is blank",
            )
            return ParticipationEvaluationResult.Completed(false)
        }

        val annotatedTranscript = context.emojiTurn.annotateTranscript(decisionContext.transcript)
        currentCoroutineContext().ensureActive()
        val annotatedContext = decisionContext.copy(transcript = annotatedTranscript)

        val decision = evaluateDecision(annotatedContext, context) ?: return ParticipationEvaluationResult.Completed(false)
        currentCoroutineContext().ensureActive()

        if (decision.shouldParticipate) {
            return ParticipationEvaluationResult.ProceedToReply
        }

        val noul = decision.noul
        if (noul != null && context.reactionThreshold < context.effectiveThreshold && noul >= context.reactionThreshold) {
            return handleSilentReactionBranch(
                context = context,
                annotatedContext = annotatedContext,
                noul = noul,
                confidence = decision.confidence,
            )
        }

        logger.debug(
            "Decision client evaluated shouldParticipate=false for message {} (noul={}, effectiveThreshold={}, reactionThreshold={})",
            message.id,
            decision.noul,
            context.effectiveThreshold,
            context.reactionThreshold,
        )
        if (decision.noul != null) {
            context.session.skip(
                reason =
                    "Participation decision skipped (noul=${decision.noul}, " +
                        "confidence=${decision.confidence}, threshold=${context.effectiveThreshold}, " +
                        "reactionThreshold=${context.reactionThreshold}, baseThreshold=${context.baseThreshold})",
            )
        }
        return ParticipationEvaluationResult.Completed(false)
    }

    private suspend fun collectDecisionContext(context: ParticipationEvaluationContext): ChatterDecisionContext? =
        try {
            contextCollector.collectDecisionContext(context.allRaw, kordSelfId = context.selfId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Error collecting decision context for message {}: {}", context.message.id, e.message)
            context.session.fail(
                AiTraceFailureCategory.CONTEXT_ERROR,
                "Error collecting decision context for message ${context.message.id}: ${e.message}",
            )
            null
        }

    private suspend fun evaluateDecision(
        annotatedContext: ChatterDecisionContext,
        context: ParticipationEvaluationContext,
    ): ParticipationDecision? =
        try {
            decisionClient.decide(
                annotatedContext.transcript,
                annotatedContext.images,
                effectiveThreshold = context.effectiveThreshold,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Gemini decision evaluation threw error for state: {}", e.message)
            context.session.fail(
                AiTraceFailureCategory.UNEXPECTED_ERROR,
                "Gemini decision evaluation threw error: ${e.message}",
            )
            null
        }

    private suspend fun handleSilentReactionBranch(
        context: ParticipationEvaluationContext,
        annotatedContext: ChatterDecisionContext,
        noul: Double,
        confidence: Double?,
    ): ParticipationEvaluationResult.Completed {
        val message = context.message
        if (context.relationship.tier.suppressFriendlySilentReaction) {
            logger.debug(
                "Silent reaction suppressed for {} user {} on message {} (noul={}, effectiveThreshold={}, reactionThreshold={})",
                context.relationship.tier,
                context.authorId,
                message.id,
                noul,
                context.effectiveThreshold,
                context.reactionThreshold,
            )
            context.session.skip(
                reason =
                    "Silent reaction suppressed for ${context.relationship.tier.name} user " +
                        "(noul=$noul, confidence=$confidence, " +
                        "threshold=${context.effectiveThreshold}, reactionThreshold=${context.reactionThreshold})",
            )
            return ParticipationEvaluationResult.Completed(false)
        }

        val outcome =
            silentReactionCoordinator.handleSilentReactionPath(
                message = message,
                decisionContext = annotatedContext,
                personaState = context.personaState,
                noul = noul,
                confidence = confidence,
                effectiveThreshold = context.effectiveThreshold,
                reactionThreshold = context.reactionThreshold,
                readingScope = context.readingScope,
                session = context.session,
                markDispatching = context.markDispatching,
                emojiTurn = context.emojiTurn,
            )
        return ParticipationEvaluationResult.Completed(outcome)
    }
}
