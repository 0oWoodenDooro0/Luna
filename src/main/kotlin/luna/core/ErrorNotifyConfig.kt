package luna.core

import dev.kord.common.entity.Snowflake
import org.slf4j.LoggerFactory

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
        private val logger = LoggerFactory.getLogger(ErrorNotifyConfig::class.java)

        fun fromMap(config: Map<String, Any?>): ErrorNotifyConfig {
            val recipientUserIds = parseRecipientIds(config["ERROR_NOTIFY_DISCORD_USER_IDS"])
            val environment = config["ERROR_NOTIFY_ENV"]?.toString()?.trim()?.ifBlank { null }
            val cooldownSeconds = parseCooldownSeconds(config["ERROR_NOTIFY_COOLDOWN_SECONDS"])

            return ErrorNotifyConfig(
                recipientUserIds = recipientUserIds,
                environment = environment,
                cooldownSeconds = cooldownSeconds,
            )
        }

        private fun parseRecipientIds(raw: Any?): List<Snowflake> {
            if (raw == null) return emptyList()
            val tokens =
                when (raw) {
                    is Collection<*> -> raw.mapNotNull { it?.toString()?.trim() }
                    else -> raw.toString().split(',')
                }

            val parsed = mutableListOf<Snowflake>()
            for (rawToken in tokens) {
                val token = rawToken.trim()
                if (token.isEmpty()) continue
                val numericId = token.toULongOrNull()?.takeIf { it > 0uL }
                if (numericId == null) {
                    logger.warn("Ignoring malformed Discord user ID '{}' in ERROR_NOTIFY_DISCORD_USER_IDS", token)
                    continue
                }
                parsed.add(Snowflake(numericId))
            }
            return parsed.distinct()
        }

        private fun parseCooldownSeconds(raw: Any?): Int {
            if (raw == null) return DEFAULT_COOLDOWN_SECONDS
            if (raw is String && raw.isBlank()) return DEFAULT_COOLDOWN_SECONDS

            val parsed =
                when (raw) {
                    is Number -> raw.toLong()
                    is String -> raw.trim().toLongOrNull()
                    else -> raw.toString().trim().toLongOrNull()
                }

            if (parsed == null || parsed <= 0L || parsed > Int.MAX_VALUE) {
                logger.warn(
                    "Invalid ERROR_NOTIFY_COOLDOWN_SECONDS '{}'; falling back to default {}s",
                    raw,
                    DEFAULT_COOLDOWN_SECONDS,
                )
                return DEFAULT_COOLDOWN_SECONDS
            }

            return parsed.toInt()
        }
    }
}
