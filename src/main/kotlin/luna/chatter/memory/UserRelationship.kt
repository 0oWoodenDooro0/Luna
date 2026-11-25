package luna.chatter.memory

import java.util.concurrent.TimeUnit

/**
 * Five-level discrete classification derived from a user's `Affinity Score` within a guild.
 */
enum class AffinityTier(
    val ambientThresholdOffset: Double,
    val suppressFriendlySilentReaction: Boolean = false,
) {
    DISLIKED(ambientThresholdOffset = 0.22, suppressFriendlySilentReaction = true),
    COOL(ambientThresholdOffset = 0.10),
    NEUTRAL(ambientThresholdOffset = 0.0),
    WARM(ambientThresholdOffset = -0.05),
    FAVORITE(ambientThresholdOffset = -0.10),
    ;

    companion object {
        fun fromScore(score: Int): AffinityTier =
            when {
                score <= -40 -> DISLIKED
                score <= -15 -> COOL
                score <= 14 -> NEUTRAL
                score <= 49 -> WARM
                else -> FAVORITE
            }
    }
}

/**
 * Guild-scoped `User Relationship` stance Luna maintains toward a specific Discord user.
 */
data class UserRelationship(
    val guildId: String,
    val userId: String,
    val affinityScore: Int,
    val toneStyle: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastInteractionAt: Long = 0L,
) {
    val tier: AffinityTier
        get() = AffinityTier.fromScore(affinityScore)

    companion object {
        fun neutral(
            guildId: String,
            userId: String,
            now: Long = 0L,
        ): UserRelationship =
            UserRelationship(
                guildId = guildId,
                userId = userId,
                affinityScore = 0,
                toneStyle = "",
                createdAt = now,
                updatedAt = now,
                lastInteractionAt = now,
            )
    }
}

/**
 * Asymmetric `Negative Affinity Decay` and bounding rules for `User Relationship` state.
 */
object NegativeAffinityDecay {
    const val MIN_SCORE = -100
    const val MAX_SCORE = 100
    const val MIN_DELTA = -15
    const val MAX_DELTA = 15
    const val MAX_TONE_STYLE_CHARS = 120
    const val DECAY_STEP = 5

    val COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(7)
    val STEP_INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(7)

    fun clampScore(score: Int): Int = score.coerceIn(MIN_SCORE, MAX_SCORE)

    fun clampDelta(delta: Int): Int = delta.coerceIn(MIN_DELTA, MAX_DELTA)

    fun sanitizeToneStyle(toneStyle: String): String = toneStyle.trim().take(MAX_TONE_STYLE_CHARS)

    /**
     * Computes the effective `Affinity Score` at [nowMs].
     *
     * Negative scores remain unchanged during the 7-day inactivity cooldown, then drift toward `0`
     * at `+5` per 7 days without crossing above `0`. Positive scores and `0` never decay automatically.
     */
    fun effectiveScore(
        rawScore: Int,
        lastInteractionAtMs: Long,
        nowMs: Long,
    ): Int {
        val clamped = clampScore(rawScore)
        if (clamped >= 0) return clamped

        val elapsedMs = (nowMs - lastInteractionAtMs).coerceAtLeast(0L)
        val postCooldownMs = elapsedMs - COOLDOWN_MS
        if (postCooldownMs <= 0L) return clamped

        val steps = (postCooldownMs / STEP_INTERVAL_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (steps <= 0) return clamped

        return (clamped.toLong() + steps.toLong() * DECAY_STEP).coerceAtMost(0L).toInt()
    }
}
