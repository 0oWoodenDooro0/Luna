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

    fun computeEffectiveThreshold(
        baseEffectiveThreshold: Double,
        relationship: UserRelationship,
    ): Double =
        (baseEffectiveThreshold + relationship.tier.ambientThresholdOffset)
            .coerceIn(MIN_EFFECTIVE_THRESHOLD, MAX_EFFECTIVE_THRESHOLD)

    fun allowsMentionDisengagement(
        isDirectMention: Boolean,
        relationship: UserRelationship,
    ): Boolean = isDirectMention && (relationship.tier == AffinityTier.COOL || relationship.tier == AffinityTier.DISLIKED)
}
