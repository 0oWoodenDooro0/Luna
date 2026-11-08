package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.Channel
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import luna.core.SummaryRegistry
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
    val summaryRegistry: SummaryRegistry = SummaryMessageRegistry,
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
        summaryRegistry: SummaryRegistry = SummaryMessageRegistry,
    ) : this(
        cooldownCache = cooldownCache,
        webhookRegistry = webhookRegistry,
        summaryRegistry = summaryRegistry,
        enabled = config.enabled,
        allowedChannels = config.allowedChannels,
        cooldownDurationMs = config.cooldownDurationMs,
        botPrefixes = config.botPrefixes,
    )

    fun evaluate(
        message: Message,
        channel: Channel? = null,
        isDirectMention: Boolean = false,
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

        // 3. Check if channel is on cooldown (bypassed for direct bot mentions)
        if (!isDirectMention && cooldownCache.isOnCooldown(channelId, cooldownDurationMs)) {
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

        // 7. Check empty / whitespace-only messages without media attachments (CODING_STANDARDS.md §3)
        val hasAttachments = runCatching { message.attachments.isNotEmpty() }.getOrDefault(false)
        if (message.content.isBlank() && !hasAttachments) {
            return ChatterFilterResult.Rejected(
                RejectionReason.EMPTY_MESSAGE,
                "Message content is blank or empty without attachments",
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
        isDirectMention: Boolean = false,
    ): Boolean = evaluate(message, channel, isDirectMention) is ChatterFilterResult.Allowed

    fun shouldEvaluate(
        message: Message,
        channel: Channel? = null,
        isDirectMention: Boolean = false,
    ): Boolean = shouldProcess(message, channel, isDirectMention)

    fun isDirectBotMention(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): Boolean {
        if (kordSelfId != null && runCatching { message.mentionedUserIds.contains(kordSelfId) }.getOrDefault(false)) {
            return true
        }
        if (kordSelfId != null) {
            val content = message.content
            if (content.contains("<@$kordSelfId>") || content.contains("<@!$kordSelfId>")) {
                return true
            }
        }
        val refAuthorId = runCatching { message.referencedMessage?.author?.id }.getOrNull()
        if (kordSelfId != null && refAuthorId == kordSelfId) {
            return true
        }
        val trimmed = message.content.trim()
        if (trimmed.startsWith("@luna", ignoreCase = true) || trimmed.contains("@luna", ignoreCase = true)) {
            return true
        }
        return false
    }

    fun isEligibleForLayaEvaluation(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.contains("luna", ignoreCase = true) || trimmed.contains("露娜")) {
            return true
        }
        if (trimmed.contains('?') || trimmed.contains('？')) {
            return true
        }
        return trimmed.length >= MIN_LAYA_CONTENT_LENGTH
    }

    fun toChatMessageOrNull(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): ChatMessage? {
        val type = runCatching { message.type }.getOrNull() ?: MessageType.Default
        if (isCommand(message.content, type)) return null
        if (isSystemNotice(type)) return null
        val webhookId = runCatching { message.webhookId }.getOrNull()
        val isLunaWebhook = webhookId != null && webhookRegistry.contains(webhookId)
        if (webhookId != null && !isLunaWebhook) return null

        val isLunaBot = kordSelfId != null && runCatching { message.author?.id }.getOrNull() == kordSelfId
        if (!isLunaWebhook && !isLunaBot && (message.author == null || message.author?.isBot == true)) return null

        if (isLunaBot) {
            val isSummary = summaryRegistry.contains(message.id) || SummaryRegistry.isSummaryContent(message.content)
            if (isSummary) {
                return null
            }
        }

        val hasAttachments = runCatching { message.attachments.isNotEmpty() }.getOrDefault(false)
        if (message.content.isBlank() && !hasAttachments) return null

        val content = if (message.content.isBlank()) "[Attachment]" else message.content
        val speaker = if (isLunaBot) "Luna" else extractSpeakerName(message)
        return ChatMessage(speaker, content)
    }

    fun filterHistory(
        messages: List<Message>,
        kordSelfId: Snowflake? = null,
    ): List<ChatMessage> = messages.mapNotNull { toChatMessageOrNull(it, kordSelfId) }

    fun speakerName(message: Message): String = extractSpeakerName(message)

    fun extractSpeakerName(message: Message): String =
        message.author?.username
            ?: runCatching { message.data.author.username }.getOrNull()
            ?: "Unknown"

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
        const val MIN_LAYA_CONTENT_LENGTH = 5
        val DEFAULT_BOT_PREFIXES = setOf("!", "~", "?")
    }
}
