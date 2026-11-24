package luna.chatter.schedule

import luna.config.EnvReader

/**
 * Configuration for persona-driven `Daily Schedule` and `Deferred Catch-Up` in `Chatter Pipeline`.
 *
 * @property enabled Whether daily schedule generation, availability gating, and presence sync are enabled.
 * @property catchUpMaxMessages Maximum missed messages fetched per pending channel during `Deferred Catch-Up`.
 * @property catchUpMaxAgeHours Maximum age in hours of missed messages considered during `Deferred Catch-Up`.
 * @property catchUpMaxAmbient Maximum number of ambient (non-mention) messages selected per channel during catch-up.
 */
data class ChatterScheduleConfig(
    val enabled: Boolean = DEFAULT_ENABLED,
    val catchUpMaxMessages: Int = DEFAULT_CATCHUP_MAX_MESSAGES,
    val catchUpMaxAgeHours: Int = DEFAULT_CATCHUP_MAX_AGE_HOURS,
    val catchUpMaxAmbient: Int = DEFAULT_CATCHUP_MAX_AMBIENT,
) {
    init {
        require(catchUpMaxMessages in MIN_CATCHUP_MAX_MESSAGES..MAX_CATCHUP_MAX_MESSAGES) {
            "catchUpMaxMessages must be in $MIN_CATCHUP_MAX_MESSAGES..$MAX_CATCHUP_MAX_MESSAGES"
        }
        require(catchUpMaxAgeHours in MIN_CATCHUP_MAX_AGE_HOURS..MAX_CATCHUP_MAX_AGE_HOURS) {
            "catchUpMaxAgeHours must be in $MIN_CATCHUP_MAX_AGE_HOURS..$MAX_CATCHUP_MAX_AGE_HOURS"
        }
        require(catchUpMaxAmbient in MIN_CATCHUP_MAX_AMBIENT..MAX_CATCHUP_MAX_AMBIENT) {
            "catchUpMaxAmbient must be in $MIN_CATCHUP_MAX_AMBIENT..$MAX_CATCHUP_MAX_AMBIENT"
        }
    }

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_CATCHUP_MAX_MESSAGES = 30
        const val MIN_CATCHUP_MAX_MESSAGES = 1
        const val MAX_CATCHUP_MAX_MESSAGES = 100
        const val DEFAULT_CATCHUP_MAX_AGE_HOURS = 8
        const val MIN_CATCHUP_MAX_AGE_HOURS = 1
        const val MAX_CATCHUP_MAX_AGE_HOURS = 72
        const val DEFAULT_CATCHUP_MAX_AMBIENT = 2
        const val MIN_CATCHUP_MAX_AMBIENT = 0
        const val MAX_CATCHUP_MAX_AMBIENT = 10

        /** Parses [ChatterScheduleConfig] from [env] with strict fail-fast validation. */
        fun from(env: EnvReader): ChatterScheduleConfig {
            val enabled = env.boolean("CHATTER_SCHEDULE_ENABLED", DEFAULT_ENABLED)
            val catchUpMaxMessages =
                env.int(
                    "CHATTER_SCHEDULE_CATCHUP_MAX_MESSAGES",
                    DEFAULT_CATCHUP_MAX_MESSAGES,
                    MIN_CATCHUP_MAX_MESSAGES..MAX_CATCHUP_MAX_MESSAGES,
                )
            val catchUpMaxAgeHours =
                env.int(
                    "CHATTER_SCHEDULE_CATCHUP_MAX_AGE_HOURS",
                    DEFAULT_CATCHUP_MAX_AGE_HOURS,
                    MIN_CATCHUP_MAX_AGE_HOURS..MAX_CATCHUP_MAX_AGE_HOURS,
                )
            val catchUpMaxAmbient =
                env.int(
                    "CHATTER_SCHEDULE_CATCHUP_MAX_AMBIENT",
                    DEFAULT_CATCHUP_MAX_AMBIENT,
                    MIN_CATCHUP_MAX_AMBIENT..MAX_CATCHUP_MAX_AMBIENT,
                )
            return ChatterScheduleConfig(
                enabled = enabled,
                catchUpMaxMessages = catchUpMaxMessages,
                catchUpMaxAgeHours = catchUpMaxAgeHours,
                catchUpMaxAmbient = catchUpMaxAmbient,
            )
        }
    }
}
