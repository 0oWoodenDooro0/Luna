package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.Channel
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import luna.core.SummaryRegistry
import luna.core.WebhookRegistry
import luna.core.ai.CommandPrefixRule
import luna.core.ai.DialogueContextPolicy
import luna.core.ai.DialogueNoiseReason
import luna.core.ai.DiscordDialogueContextEngine

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
    val contextEngine: DiscordDialogueContextEngine = DiscordDialogueContextEngine(webhookRegistry, summaryRegistry),
) {
    constructor(
        config: ChatterFilterConfig,
        cooldownCache: BoundedChannelCooldownCache =
            BoundedChannelCooldownCache(defaultCooldownDurationMs = config.cooldownDurationMs),
        webhookRegistry: WebhookRegistry = LunaWebhookRegistry,
        summaryRegistry: SummaryRegistry = SummaryMessageRegistry,
        contextEngine: DiscordDialogueContextEngine = DiscordDialogueContextEngine(webhookRegistry, summaryRegistry),
    ) : this(
        cooldownCache = cooldownCache,
        webhookRegistry = webhookRegistry,
        summaryRegistry = summaryRegistry,
        enabled = config.enabled,
        allowedChannels = config.allowedChannels,
        cooldownDurationMs = config.cooldownDurationMs,
        botPrefixes = config.botPrefixes,
        contextEngine = contextEngine,
    )

    internal fun contextPolicy(): DialogueContextPolicy = DialogueContextPolicy.chatter(botPrefixes = botPrefixes)

    private fun triggerPolicy(): DialogueContextPolicy = contextPolicy().copy(allowThirdPartyBots = false)

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

        // 4. Delegate shared noise evaluation (commands, system notices, webhooks/bots, blank/media checks)
        val noiseEval = contextEngine.evaluateNoise(message, triggerPolicy(), kordSelfId = null)
        if (noiseEval.isNoise) {
            if (noiseEval.reason == DialogueNoiseReason.EMPTY_OR_EMOJI_ONLY && directMention) {
                return ChatterFilterResult.Allowed(
                    speakerName = noiseEval.speaker.name,
                    channelId = channelId,
                    messageId = message.id,
                    content = message.content,
                )
            }
            return mapNoiseRejection(message, noiseEval.reason)
        }

        val finalContent =
            if (message.content.isNotBlank()) {
                message.content
            } else {
                noiseEval.blankPlaceholder ?: message.content
            }

        return ChatterFilterResult.Allowed(
            speakerName = noiseEval.speaker.name,
            channelId = channelId,
            messageId = message.id,
            content = finalContent,
        )
    }

    private fun mapNoiseRejection(
        message: Message,
        reason: DialogueNoiseReason?,
    ): ChatterFilterResult.Rejected =
        when (reason) {
            DialogueNoiseReason.COMMAND -> {
                ChatterFilterResult.Rejected(
                    RejectionReason.COMMAND,
                    "Message starts with slash command or bot prefix command",
                )
            }

            DialogueNoiseReason.SYSTEM_MESSAGE -> {
                val type = runCatching { message.type }.getOrNull() ?: MessageType.Default
                ChatterFilterResult.Rejected(
                    RejectionReason.SYSTEM_MESSAGE,
                    "System message type: $type",
                )
            }

            DialogueNoiseReason.UNVERIFIED_WEBHOOK -> {
                val webhookId = runCatching { message.webhookId }.getOrNull()
                ChatterFilterResult.Rejected(
                    RejectionReason.UNVERIFIED_WEBHOOK,
                    "Message from unverified external webhook $webhookId",
                )
            }

            DialogueNoiseReason.THIRD_PARTY_BOT,
            DialogueNoiseReason.BOT_SUMMARY,
            DialogueNoiseReason.MISSING_AUTHOR,
            -> {
                ChatterFilterResult.Rejected(
                    RejectionReason.BOT_MESSAGE,
                    "Message from ambient third-party bot or missing author",
                )
            }

            DialogueNoiseReason.STICKER_ONLY,
            DialogueNoiseReason.EMPTY_OR_EMOJI_ONLY,
            null,
            -> {
                ChatterFilterResult.Rejected(
                    RejectionReason.EMPTY_MESSAGE,
                    "Message content is blank or empty without attachments, stickers, or embeds",
                )
            }
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
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull() ?: return false
        if (runCatching { message.mentionedUserIds.contains(resolvedSelfId) }.getOrDefault(false)) {
            return true
        }
        val content = message.content
        return content.contains("<@$resolvedSelfId>") || content.contains("<@!$resolvedSelfId>")
    }

    fun isContextMessage(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): Boolean {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        val noiseEval = contextEngine.evaluateNoise(message, contextPolicy(), kordSelfId = resolvedSelfId)
        if (!noiseEval.isNoise) {
            return true
        }
        return noiseEval.reason == DialogueNoiseReason.EMPTY_OR_EMOJI_ONLY &&
            isDirectBotMention(message, resolvedSelfId)
    }

    fun filterContextMessages(
        messages: List<Message>,
        kordSelfId: Snowflake? = null,
    ): List<Message> = messages.filter { isContextMessage(it, kordSelfId) }

    fun toChatMessageOrNull(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): ChatMessage? {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        val noiseEval = contextEngine.evaluateNoise(message, contextPolicy(), kordSelfId = resolvedSelfId)
        if (noiseEval.isNoise) {
            return null
        }
        val content =
            if (message.content.isNotBlank()) {
                message.content
            } else {
                noiseEval.blankPlaceholder ?: return null
            }
        return ChatMessage(
            author = noiseEval.speaker.name,
            content = content,
            authorId = noiseEval.speaker.authorId,
            isBot = noiseEval.speaker.isThirdPartyBot,
        )
    }

    fun filterHistory(
        messages: List<Message>,
        kordSelfId: Snowflake? = null,
    ): List<ChatMessage> = messages.mapNotNull { toChatMessageOrNull(it, kordSelfId) }

    fun speakerName(message: Message): String = extractSpeakerName(message)

    fun extractSpeakerName(message: Message): String = contextEngine.extractSpeakerName(message)

    companion object {
        val DEFAULT_BOT_PREFIXES = CommandPrefixRule.DEFAULT_BOT_PREFIXES
        val URL_REGEX = Regex("""https?://[^\s<>"']+""")
        const val ATTACHMENT_PLACEHOLDER = DiscordDialogueContextEngine.ATTACHMENT_PLACEHOLDER
        const val EMBED_PLACEHOLDER = DiscordDialogueContextEngine.EMBED_PLACEHOLDER
        const val GIF_PLACEHOLDER = DiscordDialogueContextEngine.GIF_PLACEHOLDER
        const val STICKER_PLACEHOLDER = DiscordDialogueContextEngine.STICKER_PLACEHOLDER

        private val DEFAULT_CONTEXT_ENGINE = DiscordDialogueContextEngine()

        fun resolveBlankMessagePlaceholder(message: Message): String? = DEFAULT_CONTEXT_ENGINE.resolveBlankMessagePlaceholder(message)
    }
}
