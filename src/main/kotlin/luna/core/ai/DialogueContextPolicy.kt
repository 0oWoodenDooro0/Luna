package luna.core.ai

/**
 * Policy rule governing how Discord text prefix and slash commands are detected during noise filtering.
 *
 * @property prefixes Command prefix tokens (e.g. `"!"`, `"~"`, `"?"`). Slash commands (`/`) and
 *   interaction command [dev.kord.common.entity.MessageType]s are always treated as commands.
 * @property requireLetterOnlyAfterPrefix When `true`, only prefixes followed immediately by a letter
 *   (`Char.isLetter`) are treated as commands (Summary Pipeline). When `false`, letters or digits
 *   (`Char.isLetterOrDigit`) match (Chatter Pipeline).
 */
data class CommandPrefixRule(
    val prefixes: Set<String> = DEFAULT_BOT_PREFIXES,
    val requireLetterOnlyAfterPrefix: Boolean = false,
) {
    fun matches(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.startsWith("/")) return true
        return prefixes.any { prefix ->
            if (!trimmed.startsWith(prefix) || trimmed.length <= prefix.length) {
                false
            } else {
                val nextChar = trimmed[prefix.length]
                if (requireLetterOnlyAfterPrefix) nextChar.isLetter() else nextChar.isLetterOrDigit()
            }
        }
    }

    companion object {
        val DEFAULT_BOT_PREFIXES: Set<String> = setOf("!", "~", "?")
    }
}

/**
 * Explicit policy configuration for [DiscordDialogueContextEngine], encapsulating all behavioral
 * differences between `Chatter Pipeline` and `Summary Pipeline` without cross-plugin coupling.
 */
data class DialogueContextPolicy(
    val allowThirdPartyBots: Boolean,
    val allowStickerOnlyMessages: Boolean,
    val allowCustomEmojiOnlyMessages: Boolean,
    val allowEmbedOnlyMessages: Boolean,
    val commandPrefixRule: CommandPrefixRule,
    val includeStickersAndGifEmbeds: Boolean,
    val includeStickerAnnotations: Boolean,
    val maxExternalReferences: Int = DEFAULT_MAX_EXTERNAL_REFERENCES,
    val maxReplyExcerptLength: Int = DEFAULT_MAX_REPLY_EXCERPT_LENGTH,
    val fallbackPrimaryUrlToWeb: Boolean,
    val maxYouTubeDurationSeconds: Long = DEFAULT_MAX_YOUTUBE_DURATION_SECONDS,
    val wrapTurnHeaderInBrackets: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_EXTERNAL_REFERENCES = 3
        const val DEFAULT_MAX_REPLY_EXCERPT_LENGTH = 120
        const val DEFAULT_MAX_YOUTUBE_DURATION_SECONDS = 7200L

        fun chatter(
            botPrefixes: Set<String> = CommandPrefixRule.DEFAULT_BOT_PREFIXES,
            maxExternalReferences: Int = DEFAULT_MAX_EXTERNAL_REFERENCES,
            maxReplyExcerptLength: Int = DEFAULT_MAX_REPLY_EXCERPT_LENGTH,
            maxYouTubeDurationSeconds: Long = DEFAULT_MAX_YOUTUBE_DURATION_SECONDS,
        ): DialogueContextPolicy =
            DialogueContextPolicy(
                allowThirdPartyBots = true,
                allowStickerOnlyMessages = true,
                allowCustomEmojiOnlyMessages = true,
                allowEmbedOnlyMessages = true,
                commandPrefixRule = CommandPrefixRule(prefixes = botPrefixes, requireLetterOnlyAfterPrefix = false),
                includeStickersAndGifEmbeds = true,
                includeStickerAnnotations = true,
                maxExternalReferences = maxExternalReferences,
                maxReplyExcerptLength = maxReplyExcerptLength,
                fallbackPrimaryUrlToWeb = false,
                maxYouTubeDurationSeconds = maxYouTubeDurationSeconds,
                wrapTurnHeaderInBrackets = false,
            )

        fun summary(
            maxExternalReferences: Int = DEFAULT_MAX_EXTERNAL_REFERENCES,
            maxReplyExcerptLength: Int = DEFAULT_MAX_REPLY_EXCERPT_LENGTH,
            maxYouTubeDurationSeconds: Long = DEFAULT_MAX_YOUTUBE_DURATION_SECONDS,
        ): DialogueContextPolicy =
            DialogueContextPolicy(
                allowThirdPartyBots = false,
                allowStickerOnlyMessages = false,
                allowCustomEmojiOnlyMessages = false,
                allowEmbedOnlyMessages = false,
                commandPrefixRule = CommandPrefixRule(prefixes = setOf("!"), requireLetterOnlyAfterPrefix = true),
                includeStickersAndGifEmbeds = false,
                includeStickerAnnotations = false,
                maxExternalReferences = maxExternalReferences,
                maxReplyExcerptLength = maxReplyExcerptLength,
                fallbackPrimaryUrlToWeb = true,
                maxYouTubeDurationSeconds = maxYouTubeDurationSeconds,
                wrapTurnHeaderInBrackets = true,
            )
    }
}
