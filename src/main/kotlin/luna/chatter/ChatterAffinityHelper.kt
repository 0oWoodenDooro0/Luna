package luna.chatter

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CancellationException
import luna.chatter.memory.AffinityTier
import luna.chatter.memory.ChatterMemoryStore
import luna.chatter.memory.UserRelationship
import org.slf4j.Logger

/**
 * Resolves per-user `User Relationship` state and computes bounded affinity threshold offsets
 * and `Selective Mention Disengagement` eligibility for [ChatterService].
 */
internal object ChatterAffinityHelper {
    const val MIN_EFFECTIVE_THRESHOLD = 0.15
    const val MAX_EFFECTIVE_THRESHOLD = 0.95

    fun resolveAuthorRelationship(
        memoryStore: ChatterMemoryStore?,
        guildId: Snowflake?,
        authorId: Snowflake?,
        logger: Logger,
    ): UserRelationship {
        val guildStr = guildId?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return UserRelationship.neutral("", "")
        val authorStr = authorId?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return UserRelationship.neutral(guildStr, "")
        val store = memoryStore ?: return UserRelationship.neutral(guildStr, authorStr)
        return try {
            store.getUserRelationship(guildId = guildStr, userId = authorStr)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Failed to resolve UserRelationship for guildId={}, authorId={}; failing open to NEUTRAL: {}",
                guildStr,
                authorStr,
                e.message,
            )
            UserRelationship.neutral(guildStr, authorStr)
        }
    }

    fun allowsMomentumThresholdBoost(relationship: UserRelationship): Boolean =
        relationship.tier != AffinityTier.COOL && relationship.tier != AffinityTier.DISLIKED

    fun appliedMomentumThresholdOffset(
        relationship: UserRelationship,
        momentumActive: Boolean,
        configuredBoost: Double,
    ): Double =
        if (!momentumActive || configuredBoost <= 0.0 || !allowsMomentumThresholdBoost(relationship)) {
            0.0
        } else {
            -configuredBoost
        }

    fun computeEffectiveThreshold(
        baseEffectiveThreshold: Double,
        relationship: UserRelationship,
        momentumThresholdOffset: Double = 0.0,
    ): Double =
        (baseEffectiveThreshold + relationship.tier.ambientThresholdOffset + momentumThresholdOffset)
            .coerceIn(MIN_EFFECTIVE_THRESHOLD, MAX_EFFECTIVE_THRESHOLD)

    fun resolveThresholdAndRecordTrace(
        memoryStore: ChatterMemoryStore?,
        guildId: Snowflake?,
        authorId: Snowflake?,
        channelId: Snowflake,
        personaState: PersonaState,
        reactionThreshold: Double,
        configuredMomentumBoost: Double,
        filter: ChatterNoiseFilter,
        session: luna.core.ai.AiTraceSession,
        logger: Logger,
    ): Pair<UserRelationship, Double> {
        val relationship = resolveAuthorRelationship(memoryStore, guildId, authorId, logger)
        val momentumState =
            filter.momentumCache.stateFor(
                channelId = channelId,
                windowMs = filter.momentumWindowMs,
                maxTurns = filter.momentumMaxTurns,
            )
        val momentumOffset =
            appliedMomentumThresholdOffset(
                relationship = relationship,
                momentumActive = momentumState.active,
                configuredBoost = configuredMomentumBoost,
            )
        val effectiveThreshold =
            computeEffectiveThreshold(
                baseEffectiveThreshold = personaState.effectiveThreshold,
                relationship = relationship,
                momentumThresholdOffset = momentumOffset,
            )
        session.recordPersonaState(
            circadianPeriod = personaState.circadianPeriod?.name,
            personaMood = personaState.mood?.label,
            effectiveThreshold = effectiveThreshold,
            reactionThreshold = reactionThreshold,
        )
        session.recordRelationship(
            relationshipTier = relationship.tier.name,
            affinityScore = relationship.affinityScore,
            affinityThresholdOffset = relationship.tier.ambientThresholdOffset,
        )
        session.recordMomentumState(
            active = momentumState.active,
            ambientTurnCount = momentumState.ambientTurnCount,
            thresholdOffset = momentumOffset,
        )
        return relationship to effectiveThreshold
    }

    fun allowsMentionDisengagement(
        isDirectMention: Boolean,
        relationship: UserRelationship,
    ): Boolean = isDirectMention && (relationship.tier == AffinityTier.COOL || relationship.tier == AffinityTier.DISLIKED)
}
