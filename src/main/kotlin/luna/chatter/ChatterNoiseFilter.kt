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
        kordSelfId: Snowflake? = null,
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

        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        val directMention = isDirectMention || isDirectBotMention(message, resolvedSelfId)

        // 3. Check if channel is on cooldown (bypassed for direct bot mentions)
        if (!directMention && cooldownCache.isOnCooldown(channelId, cooldownDurationMs)) {
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
        if (message.content.isBlank() && !hasAttachments && !directMention) {
            return ChatterFilterResult.Rejected(
                RejectionReason.EMPTY_MESSAGE,
                "Message content is blank or empty without attachments",
            )
        }

        val finalContent =
            resolveCleanedContent(
                rawContent = message.content,
                botSelfId = resolvedSelfId,
                hasAttachments = hasAttachments,
                isDirectMention = directMention,
            ) ?: message.content

        val speakerName = extractSpeakerName(message)

        return ChatterFilterResult.Allowed(
            speakerName = speakerName,
            channelId = channelId,
            messageId = message.id,
            content = finalContent,
        )
    }

    fun shouldProcess(
        message: Message,
        channel: Channel? = null,
        isDirectMention: Boolean = false,
        kordSelfId: Snowflake? = null,
    ): Boolean = evaluate(message, channel, isDirectMention, kordSelfId) is ChatterFilterResult.Allowed

    fun shouldEvaluate(
        message: Message,
        channel: Channel? = null,
        isDirectMention: Boolean = false,
        kordSelfId: Snowflake? = null,
    ): Boolean = shouldProcess(message, channel, isDirectMention, kordSelfId)

    fun isDirectBotMention(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): Boolean {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        if (resolvedSelfId != null && runCatching { message.mentionedUserIds.contains(resolvedSelfId) }.getOrDefault(false)) {
            return true
        }
        if (resolvedSelfId != null) {
            val content = message.content
            if (content.contains("<@$resolvedSelfId>") || content.contains("<@!$resolvedSelfId>")) {
                return true
            }
        }
        val refAuthorId = runCatching { message.referencedMessage?.author?.id }.getOrNull()
        if (resolvedSelfId != null && refAuthorId == resolvedSelfId) {
            return true
        }
        if (LUNA_MENTION_REGEX.containsMatchIn(message.content)) {
            return true
        }
        return false
    }

    fun isEligibleForDecisionEvaluation(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.contains("luna", ignoreCase = true) || trimmed.contains("露娜")) {
            return true
        }
        if (trimmed.contains('?') || trimmed.contains('？')) {
            return true
        }
        return trimmed.length >= MIN_DECISION_CONTENT_LENGTH
    }

    fun stripLunaMentions(
        content: String,
        botSelfId: Snowflake? = null,
    ): String {
        var result = content
        if (botSelfId != null) {
            val botIdStr = botSelfId.toString()
            result = result.replace(Regex("""<@!?$botIdStr>"""), "")
        }
        result = result.replace(LUNA_MENTION_REGEX, "")
        result = result.replace(MULTIPLE_SPACES_REGEX, " ")
        return result.lines().joinToString("\n") { it.trim() }.trim()
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

        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        val isLunaBot = resolvedSelfId != null && runCatching { message.author?.id }.getOrNull() == resolvedSelfId
        if (!isLunaWebhook && !isLunaBot && (message.author == null || message.author?.isBot == true)) return null

        if (isLunaBot) {
            val isSummary = summaryRegistry.contains(message.id) || SummaryRegistry.isSummaryContent(message.content)
            if (isSummary) {
                return null
            }
        }

        val hasAttachments = runCatching { message.attachments.isNotEmpty() }.getOrDefault(false)
        if (message.content.isBlank() && !hasAttachments) return null

        val content =
            resolveCleanedContent(
                rawContent = message.content,
                botSelfId = resolvedSelfId,
                hasAttachments = hasAttachments,
            ) ?: return null

        val speaker = if (isLunaBot) "Luna" else extractSpeakerName(message)
        return ChatMessage(speaker, content)
    }

    fun resolveCleanedContent(
        rawContent: String,
        botSelfId: Snowflake? = null,
        hasAttachments: Boolean = false,
        isDirectMention: Boolean = false,
    ): String? {
        val stripped = stripLunaMentions(rawContent, botSelfId)
        return when {
            stripped.isNotBlank() -> stripped
            isDirectMention -> CALLOUT_GREETING
            hasAttachments -> "[Attachment]"
            rawContent.isNotBlank() -> CALLOUT_GREETING
            else -> null
        }
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
        const val MIN_DECISION_CONTENT_LENGTH = 5
        val DEFAULT_BOT_PREFIXES = setOf("!", "~", "?")
        const val CALLOUT_GREETING = "你好"
        private val LUNA_MENTION_REGEX = Regex("""(?i)(?<!\w)@luna\b""")
        private val MULTIPLE_SPACES_REGEX = Regex("""[ \t]+""")
    }
}
