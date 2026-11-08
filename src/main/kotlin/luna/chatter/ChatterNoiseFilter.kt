package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.Channel
import luna.core.LunaWebhookRegistry
import luna.core.WebhookRegistry

enum class RejectionReason {
    CHATTER_DISABLED,
    CHANNEL_NOT_ALLOWED,
    CHANNEL_ON_COOLDOWN,
    SYSTEM_MESSAGE,
    BOT_MESSAGE,
    UNVERIFIED_WEBHOOK,
    COMMAND,
    EMPTY_MESSAGE,
}

sealed class ChatterFilterResult {
    data class Allowed(
        val speakerName: String,
        val channelId: Snowflake,
        val messageId: Snowflake,
        val content: String,
    ) : ChatterFilterResult()

    data class Rejected(
        val reason: RejectionReason,
        val detail: String? = null,
    ) : ChatterFilterResult()
}

data class ChatterFilterConfig(
    val enabled: Boolean = true,
    val allowedChannels: Set<Snowflake> = emptySet(),
    val cooldownDurationMs: Long = BoundedChannelCooldownCache.DEFAULT_COOLDOWN_MS,
    val botPrefixes: Set<String> = ChatterNoiseFilter.DEFAULT_BOT_PREFIXES,
)

open class ChatterNoiseFilter(
    val cooldownCache: BoundedChannelCooldownCache = BoundedChannelCooldownCache(),
    val webhookRegistry: WebhookRegistry = LunaWebhookRegistry,
    var enabled: Boolean = true,
    var allowedChannels: Set<Snowflake> = emptySet(),
    var cooldownDurationMs: Long = BoundedChannelCooldownCache.DEFAULT_COOLDOWN_MS,
    var botPrefixes: Set<String> = DEFAULT_BOT_PREFIXES,
) {
    constructor(
        config: ChatterFilterConfig,
        cooldownCache: BoundedChannelCooldownCache =
            BoundedChannelCooldownCache(defaultCooldownDurationMs = config.cooldownDurationMs),
        webhookRegistry: WebhookRegistry = LunaWebhookRegistry,
    ) : this(
        cooldownCache = cooldownCache,
        webhookRegistry = webhookRegistry,
        enabled = config.enabled,
        allowedChannels = config.allowedChannels,
        cooldownDurationMs = config.cooldownDurationMs,
        botPrefixes = config.botPrefixes,
    )

    fun evaluate(
        message: Message,
        channel: Channel? = null,
    ): ChatterFilterResult {
        // 1. Check if chatter is disabled globally
        if (!enabled) {
            return ChatterFilterResult.Rejected(RejectionReason.CHATTER_DISABLED, "Chatter is globally disabled")
        }

        val channelId = channel?.id ?: message.channelId

        // 2. Check channel whitelist (when configured)
        if (allowedChannels.isNotEmpty() && !allowedChannels.contains(channelId)) {
            return ChatterFilterResult.Rejected(
                RejectionReason.CHANNEL_NOT_ALLOWED,
                "Channel $channelId is not in the allowed channels whitelist",
            )
        }

        // 3. Check if channel is on cooldown
        if (cooldownCache.isOnCooldown(channelId, cooldownDurationMs)) {
            return ChatterFilterResult.Rejected(
                RejectionReason.CHANNEL_ON_COOLDOWN,
                "Channel $channelId is currently on cooldown",
            )
        }

        // 4. Check slash commands and bot prefix commands
        val type = runCatching { message.type }.getOrNull() ?: MessageType.Default
        if (isCommand(message.content, type)) {
            return ChatterFilterResult.Rejected(
                RejectionReason.COMMAND,
                "Message starts with slash command or bot prefix command",
            )
        }

        // 5. Check system notices / non-speech message types
        if (isSystemNotice(type)) {
            return ChatterFilterResult.Rejected(
                RejectionReason.SYSTEM_MESSAGE,
                "System message type: $type",
            )
        }

        // 6. Check webhooks & bots
        val webhookId = runCatching { message.webhookId }.getOrNull()
        val isLunaWebhook = webhookId != null && webhookRegistry.contains(webhookId)

        if (webhookId != null && !isLunaWebhook) {
            // Third-party / unverified webhook
            return ChatterFilterResult.Rejected(
                RejectionReason.UNVERIFIED_WEBHOOK,
                "Message from unverified external webhook $webhookId",
            )
        }

        if (!isLunaWebhook && (message.author == null || message.author?.isBot == true)) {
            return ChatterFilterResult.Rejected(
                RejectionReason.BOT_MESSAGE,
                "Message from ambient third-party bot or missing author",
            )
        }

        // 7. Check empty / whitespace-only messages
        if (message.content.isBlank()) {
            return ChatterFilterResult.Rejected(
                RejectionReason.EMPTY_MESSAGE,
                "Message content is blank or empty",
            )
        }

        val speakerName = extractSpeakerName(message)

        return ChatterFilterResult.Allowed(
            speakerName = speakerName,
            channelId = channelId,
            messageId = message.id,
            content = message.content,
        )
    }

    fun shouldProcess(
        message: Message,
        channel: Channel? = null,
    ): Boolean = evaluate(message, channel) is ChatterFilterResult.Allowed

    fun isAllowed(
        message: Message,
        channel: Channel? = null,
    ): Boolean = shouldProcess(message, channel)

    fun isNoise(
        message: Message,
        channel: Channel? = null,
    ): Boolean = !shouldProcess(message, channel)

    fun isNoiseMessage(
        message: Message,
        channel: Channel? = null,
    ): Boolean = isNoise(message, channel)

    fun extractSpeakerName(message: Message): String =
        message.author?.username
            ?: runCatching { message.data.author.username }.getOrNull()
            ?: "Unknown"

    fun recordReply(
        channelId: Snowflake,
        timestamp: Long? = null,
    ) {
        if (timestamp != null) {
            cooldownCache.record(channelId, timestamp)
        } else {
            cooldownCache.record(channelId)
        }
    }

    fun recordReply(
        channel: Channel,
        timestamp: Long? = null,
    ) {
        recordReply(channel.id, timestamp)
    }

    fun recordReply(
        channelId: Long,
        timestamp: Long? = null,
    ) {
        recordReply(Snowflake(channelId), timestamp)
    }

    fun recordTrigger(
        channelId: Snowflake,
        timestamp: Long? = null,
    ) {
        recordReply(channelId, timestamp)
    }

    fun recordTrigger(
        channel: Channel,
        timestamp: Long? = null,
    ) {
        recordReply(channel.id, timestamp)
    }

    fun markChannelActive(
        channelId: Snowflake,
        timestamp: Long? = null,
    ) {
        recordReply(channelId, timestamp)
    }

    fun isOnCooldown(channelId: Snowflake): Boolean = cooldownCache.isOnCooldown(channelId, cooldownDurationMs)

    fun isOnCooldown(channel: Channel): Boolean = isOnCooldown(channel.id)

    fun isOnCooldown(channelId: Long): Boolean = isOnCooldown(Snowflake(channelId))

    private fun isSystemNotice(type: MessageType): Boolean =
        when (type) {
            MessageType.Default,
            MessageType.Reply,
            MessageType.ThreadStarterMessage,
            -> false

            else -> true
        }

    private fun isCommand(
        content: String,
        type: MessageType,
    ): Boolean {
        if (type == MessageType.ChatInputCommand || type == MessageType.ContextMenuCommand) {
            return true
        }
        val trimmed = content.trim()
        if (trimmed.startsWith("/")) return true
        for (prefix in botPrefixes) {
            if (trimmed.startsWith(prefix) && trimmed.length > prefix.length && trimmed[prefix.length].isLetterOrDigit()) {
                return true
            }
        }
        return false
    }

    companion object {
        val DEFAULT_BOT_PREFIXES = setOf("!", "~", "?")
    }
}
