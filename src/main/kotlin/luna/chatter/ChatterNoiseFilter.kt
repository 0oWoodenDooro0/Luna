package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.Channel
import luna.config.ConfigValidationException
import luna.config.EnvReader
import luna.config.SecretMasker
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

data class MomentumFilterSettings(
    val windowMs: Long = BoundedChannelMomentumCache.DEFAULT_WINDOW_MS,
    val cooldownFloorMs: Long = ChatterFilterConfig.DEFAULT_MOMENTUM_COOLDOWN_FLOOR_MS,
    val maxTurns: Int = BoundedChannelMomentumCache.DEFAULT_MAX_TURNS,
)

data class FatigueFilterSettings(
    val windowMs: Long = BoundedChannelFatigueCache.DEFAULT_WINDOW_MS,
    val step: Double = BoundedChannelFatigueCache.DEFAULT_STEP,
    val maxOffset: Double = BoundedChannelFatigueCache.DEFAULT_MAX_OFFSET,
)

data class ChatterFilterConfig(
    val enabled: Boolean = DEFAULT_ENABLED,
    val allowedChannels: Set<Snowflake> = emptySet(),
    val cooldownDurationMs: Long = BoundedChannelCooldownCache.DEFAULT_COOLDOWN_MS,
    val botPrefixes: Set<String> = ChatterNoiseFilter.DEFAULT_BOT_PREFIXES,
    val momentumWindowMs: Long = BoundedChannelMomentumCache.DEFAULT_WINDOW_MS,
    val momentumCooldownFloorMs: Long = DEFAULT_MOMENTUM_COOLDOWN_FLOOR_MS,
    val momentumMaxTurns: Int = BoundedChannelMomentumCache.DEFAULT_MAX_TURNS,
    val fatigueWindowMs: Long = BoundedChannelFatigueCache.DEFAULT_WINDOW_MS,
    val fatigueStep: Double = BoundedChannelFatigueCache.DEFAULT_STEP,
    val fatigueMaxOffset: Double = BoundedChannelFatigueCache.DEFAULT_MAX_OFFSET,
) {
    init {
        requireNonNegative("CHATTER_COOLDOWN_SECONDS", cooldownDurationMs, "duration", "ms")
        requireNonNegative("CHATTER_MOMENTUM_WINDOW_SECONDS", momentumWindowMs, "duration", "ms")
        requireNonNegative("CHATTER_MOMENTUM_COOLDOWN_FLOOR_MS", momentumCooldownFloorMs, "duration", "ms")
        requireNonNegative("CHATTER_MOMENTUM_MAX_TURNS", momentumMaxTurns.toLong(), "integer", "")
        requireNonNegative("CHATTER_FATIGUE_WINDOW_SECONDS", fatigueWindowMs, "duration", "ms")
        requireUnitDouble("CHATTER_FATIGUE_STEP", fatigueStep)
        requireUnitDouble("CHATTER_FATIGUE_MAX_OFFSET", fatigueMaxOffset)
    }

    private fun requireUnitDouble(
        key: String,
        value: Double,
    ) {
        if (!value.isFinite() || value !in 0.0..1.0) {
            val safeValue = SecretMasker.maskValue(key, value)
            throw ConfigValidationException(
                "Invalid configuration for $key: expected a number in 0.0..1.0, but got '$safeValue'",
            )
        }
    }

    private fun requireNonNegative(
        key: String,
        value: Long,
        expectedKind: String,
        unitSuffix: String,
    ) {
        if (value < 0L) {
            val safeValue = SecretMasker.maskValue(key, value)
            throw ConfigValidationException(
                "Invalid configuration for $key: expected a non-negative $expectedKind, but got '$safeValue$unitSuffix'",
            )
        }
    }

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_COOLDOWN_SECONDS = 30L
        const val DEFAULT_MOMENTUM_COOLDOWN_FLOOR_MS = 1_500L
        private const val MAX_COOLDOWN_SECONDS = Long.MAX_VALUE / 1000L

        /** Parses [ChatterFilterConfig] from [env] with strict fail-fast validation. */
        fun from(env: EnvReader): ChatterFilterConfig {
            val enabled = env.boolean("CHATTER_ENABLED", DEFAULT_ENABLED)
            val allowedChannels =
                env
                    .csv("CHATTER_ALLOWED_CHANNELS")
                    .map { rawId ->
                        val idValue =
                            rawId.toULongOrNull()
                                ?: throw ConfigValidationException(
                                    "Invalid configuration for CHATTER_ALLOWED_CHANNELS: " +
                                        "expected comma-separated Discord Snowflake IDs, but got '$rawId'",
                                )
                        Snowflake(idValue)
                    }.toSet()
            val cooldownSeconds = env.long("CHATTER_COOLDOWN_SECONDS", DEFAULT_COOLDOWN_SECONDS, 0L..MAX_COOLDOWN_SECONDS)
            val momentumWindowSeconds =
                env.long(
                    "CHATTER_MOMENTUM_WINDOW_SECONDS",
                    BoundedChannelMomentumCache.DEFAULT_WINDOW_SECONDS,
                    0L..MAX_COOLDOWN_SECONDS,
                )
            val momentumCooldownFloorMs =
                env.long(
                    "CHATTER_MOMENTUM_COOLDOWN_FLOOR_MS",
                    DEFAULT_MOMENTUM_COOLDOWN_FLOOR_MS,
                    0L..Long.MAX_VALUE,
                )
            val momentumMaxTurns =
                env.int(
                    "CHATTER_MOMENTUM_MAX_TURNS",
                    BoundedChannelMomentumCache.DEFAULT_MAX_TURNS,
                    0..Int.MAX_VALUE,
                )
            val fatigueWindowSeconds =
                env.long(
                    "CHATTER_FATIGUE_WINDOW_SECONDS",
                    BoundedChannelFatigueCache.DEFAULT_WINDOW_SECONDS,
                    0L..MAX_COOLDOWN_SECONDS,
                )
            val fatigueStep =
                env.double(
                    "CHATTER_FATIGUE_STEP",
                    BoundedChannelFatigueCache.DEFAULT_STEP,
                    0.0..1.0,
                )
            val fatigueMaxOffset =
                env.double(
                    "CHATTER_FATIGUE_MAX_OFFSET",
                    BoundedChannelFatigueCache.DEFAULT_MAX_OFFSET,
                    0.0..1.0,
                )
            return ChatterFilterConfig(
                enabled = enabled,
                allowedChannels = allowedChannels,
                cooldownDurationMs = cooldownSeconds * 1000L,
                momentumWindowMs = momentumWindowSeconds * 1000L,
                momentumCooldownFloorMs = momentumCooldownFloorMs,
                momentumMaxTurns = momentumMaxTurns,
                fatigueWindowMs = fatigueWindowSeconds * 1000L,
                fatigueStep = fatigueStep,
                fatigueMaxOffset = fatigueMaxOffset,
            )
        }
    }
}

open class ChatterNoiseFilter(
    config: ChatterFilterConfig,
    val cooldownCache: BoundedChannelCooldownCache =
        BoundedChannelCooldownCache(defaultCooldownDurationMs = config.cooldownDurationMs),
    val webhookRegistry: WebhookRegistry = LunaWebhookRegistry,
    val summaryRegistry: SummaryRegistry = SummaryMessageRegistry,
    val contextEngine: DiscordDialogueContextEngine = DiscordDialogueContextEngine(webhookRegistry, summaryRegistry),
    val momentumCache: BoundedChannelMomentumCache =
        BoundedChannelMomentumCache(
            defaultWindowMs = config.momentumWindowMs,
            defaultMaxTurns = config.momentumMaxTurns,
        ),
    val fatigueCache: BoundedChannelFatigueCache =
        BoundedChannelFatigueCache(
            defaultWindowMs = config.fatigueWindowMs,
            defaultStep = config.fatigueStep,
            defaultMaxOffset = config.fatigueMaxOffset,
        ),
) {
    var enabled: Boolean = config.enabled
    var allowedChannels: Set<Snowflake> = config.allowedChannels
    var cooldownDurationMs: Long = config.cooldownDurationMs
    var botPrefixes: Set<String> = config.botPrefixes
    var momentumSettings: MomentumFilterSettings =
        MomentumFilterSettings(
            windowMs = config.momentumWindowMs,
            cooldownFloorMs = config.momentumCooldownFloorMs,
            maxTurns = config.momentumMaxTurns,
        )
    val fatigueSettings: FatigueFilterSettings =
        FatigueFilterSettings(
            windowMs = config.fatigueWindowMs,
            step = config.fatigueStep,
            maxOffset = config.fatigueMaxOffset,
        )

    constructor(
        cooldownCache: BoundedChannelCooldownCache = BoundedChannelCooldownCache(),
        webhookRegistry: WebhookRegistry = LunaWebhookRegistry,
        summaryRegistry: SummaryRegistry = SummaryMessageRegistry,
        enabled: Boolean = true,
        allowedChannels: Set<Snowflake> = emptySet(),
        cooldownDurationMs: Long = BoundedChannelCooldownCache.DEFAULT_COOLDOWN_MS,
        botPrefixes: Set<String> = DEFAULT_BOT_PREFIXES,
        contextEngine: DiscordDialogueContextEngine = DiscordDialogueContextEngine(webhookRegistry, summaryRegistry),
        momentumCache: BoundedChannelMomentumCache = BoundedChannelMomentumCache(),
        fatigueCache: BoundedChannelFatigueCache = BoundedChannelFatigueCache(),
    ) : this(
        config =
            ChatterFilterConfig(
                enabled = enabled,
                allowedChannels = allowedChannels,
                cooldownDurationMs = cooldownDurationMs,
                botPrefixes = botPrefixes,
                momentumWindowMs = momentumCache.defaultWindowMs,
                momentumMaxTurns = momentumCache.defaultMaxTurns,
                fatigueWindowMs = fatigueCache.defaultWindowMs,
                fatigueStep = fatigueCache.defaultStep,
                fatigueMaxOffset = fatigueCache.defaultMaxOffset,
            ),
        cooldownCache = cooldownCache,
        webhookRegistry = webhookRegistry,
        summaryRegistry = summaryRegistry,
        contextEngine = contextEngine,
        momentumCache = momentumCache,
        fatigueCache = fatigueCache,
    )

    internal val directInteractionResolver = ChatterDirectInteractionResolver(contextEngine = contextEngine)

    fun currentMomentumState(channelId: Snowflake): MomentumState =
        momentumCache.stateFor(
            channelId = channelId,
            windowMs = momentumSettings.windowMs,
            maxTurns = momentumSettings.maxTurns,
        )

    fun recordMomentumReply(
        channelId: Snowflake,
        isDirectMention: Boolean,
    ) {
        momentumCache.recordReply(
            channelId = channelId,
            isDirectMention = isDirectMention,
            windowMs = momentumSettings.windowMs,
            maxTurns = momentumSettings.maxTurns,
        )
    }

    fun currentFatigueState(channelId: Snowflake): FatigueState =
        fatigueCache.stateFor(
            channelId = channelId,
            windowMs = fatigueSettings.windowMs,
            step = fatigueSettings.step,
            maxOffset = fatigueSettings.maxOffset,
        )

    fun recordAmbientFatigue(channelId: Snowflake) {
        fatigueCache.recordAmbientReply(
            channelId = channelId,
            windowMs = fatigueSettings.windowMs,
            step = fatigueSettings.step,
            maxOffset = fatigueSettings.maxOffset,
        )
    }

    fun resetFatigue(channelId: Snowflake) {
        fatigueCache.reset(channelId)
    }

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
        val directMention = isDirectMention || directInteractionResolver.isDirectBotMention(message, resolvedSelfId)

        // 3. Check if channel is on cooldown (bypassed for direct bot mentions; softened during active momentum)
        if (!directMention) {
            val momentumActive = currentMomentumState(channelId).active
            val effectiveCooldownMs =
                if (momentumActive) {
                    minOf(momentumSettings.cooldownFloorMs, cooldownDurationMs)
                } else {
                    cooldownDurationMs
                }
            if (cooldownCache.isOnCooldown(channelId, effectiveCooldownMs)) {
                return ChatterFilterResult.Rejected(
                    RejectionReason.CHANNEL_ON_COOLDOWN,
                    "Channel $channelId is currently on cooldown",
                )
            }
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

    private fun evaluateCachedContextEligibility(
        message: Message,
        resolvedSelfId: Snowflake?,
    ): Boolean? {
        val noiseEval = contextEngine.evaluateNoise(message, contextPolicy(), kordSelfId = resolvedSelfId)
        if (!noiseEval.isNoise) {
            return true
        }
        if (noiseEval.reason != DialogueNoiseReason.EMPTY_OR_EMOJI_ONLY) {
            return false
        }
        if (
            directInteractionResolver.isDirectBotMention(message, resolvedSelfId) ||
            directInteractionResolver.isCachedReplyToLuna(message, resolvedSelfId)
        ) {
            return true
        }
        return null
    }

    fun isContextMessage(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): Boolean {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        return evaluateCachedContextEligibility(message, resolvedSelfId) == true
    }

    suspend fun isContextMessageResolvingReply(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): Boolean {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
        return evaluateCachedContextEligibility(message, resolvedSelfId)
            ?: directInteractionResolver.isReplyToLuna(message, resolvedSelfId)
    }

    fun filterContextMessages(
        messages: List<Message>,
        kordSelfId: Snowflake? = null,
    ): List<Message> = messages.filter { isContextMessage(it, kordSelfId) }

    suspend fun filterContextMessagesResolvingReplies(
        messages: List<Message>,
        kordSelfId: Snowflake? = null,
    ): List<Message> = messages.filter { isContextMessageResolvingReply(it, kordSelfId) }

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

    suspend fun filterAndResolveHistory(
        messages: List<Message>,
        kordSelfId: Snowflake? = null,
    ): List<ChatMessage> {
        val raw = filterHistory(messages, kordSelfId)
        if (raw.isEmpty()) return raw
        return resolveChatMessages(messages, raw, kordSelfId)
    }

    suspend fun resolveChatMessages(
        sourceMessages: List<Message>,
        chatMessages: List<ChatMessage>,
        kordSelfId: Snowflake? = null,
    ): List<ChatMessage> {
        if (chatMessages.isEmpty()) return chatMessages
        val resolvedMentions =
            contextEngine.mentionResolver.resolveSlice(
                messages = sourceMessages,
                policy = contextPolicy(),
                kordSelfId = kordSelfId,
            )
        return chatMessages.map { msg ->
            val resolvedAuthor =
                if (msg.author == "Luna") {
                    msg.author
                } else {
                    msg.authorId
                        ?.toLongOrNull()
                        ?.let { Snowflake(it) }
                        ?.let { resolvedMentions.speakerDisplayName(it) }
                        ?: msg.author
                }
            msg.copy(
                author = resolvedAuthor,
                content = resolvedMentions.formatText(msg.content),
            )
        }
    }

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
