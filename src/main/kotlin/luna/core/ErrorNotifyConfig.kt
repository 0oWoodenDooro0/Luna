package luna.core

import dev.kord.common.entity.Snowflake
import luna.config.ConfigValidationException
import luna.config.EnvReader

/**
 * Configuration for Discord DM Owner Error Notification inside Luna Host.
 *
 * @property recipientUserIds Valid Discord operator user IDs to receive DM alerts (empty disables notifications).
 * @property environment Optional deployment environment label (e.g. `production`, `staging`).
 * @property cooldownSeconds Cooldown window in seconds for error deduplication (default `300`).
 */
data class ErrorNotifyConfig(
    val recipientUserIds: List<Snowflake> = emptyList(),
    val environment: String? = null,
    val cooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS,
) {
    val isEnabled: Boolean
        get() = recipientUserIds.isNotEmpty()

    companion object {
        const val DEFAULT_COOLDOWN_SECONDS = 300

        fun from(env: EnvReader): ErrorNotifyConfig {
            val recipientUserIds =
                env
                    .csv("ERROR_NOTIFY_DISCORD_USER_IDS")
                    .map { rawId ->
                        val numericId =
                            rawId.toULongOrNull()?.takeIf { it > 0uL }
                                ?: throw ConfigValidationException(
                                    "Invalid configuration for ERROR_NOTIFY_DISCORD_USER_IDS: " +
                                        "expected comma-separated Discord Snowflake IDs, but got '$rawId'",
                                )
                        Snowflake(numericId)
                    }.distinct()
            val environment = env.string("ERROR_NOTIFY_ENV")
            val cooldownSeconds =
                env
                    .long(
                        "ERROR_NOTIFY_COOLDOWN_SECONDS",
                        DEFAULT_COOLDOWN_SECONDS.toLong(),
                        1L..Int.MAX_VALUE.toLong(),
                    ).toInt()

            return ErrorNotifyConfig(
                recipientUserIds = recipientUserIds,
                environment = environment,
                cooldownSeconds = cooldownSeconds,
            )
        }
    }
}
