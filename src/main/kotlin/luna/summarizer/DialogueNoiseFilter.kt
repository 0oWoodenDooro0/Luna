package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.core.entity.Message

/**
 * Encapsulates dialogue noise filtering and message boundary checks for conversation slices.
 */
open class DialogueNoiseFilter {
    open fun isBotSummaryMessage(message: Message): Boolean =
        message.author?.isBot == true &&
            (
                message.content.startsWith("TL;DR:") || message.content.startsWith("TL;DR") || message.content.contains("重點摘要") ||
                    message.content.contains("Previous summary")
            )

    open fun isNoiseMessage(message: Message): Boolean {
        // Exclude bot or missing author
        if (message.author == null || message.author?.isBot == true) {
            return true
        }

        // Exclude webhooks
        if (runCatching { message.webhookId }.getOrNull() != null) {
            return true
        }

        // Exclude system message types
        val type = runCatching { message.type }.getOrNull()
        if (type != null && isSystemMessageType(type)) {
            return true
        }

        // Exclude commands matching slash or bot command patterns
        if (isCommand(message.content, type)) {
            return true
        }

        // Exclude messages consisting solely of stickers
        val hasStickers = runCatching { message.stickers }.getOrNull()?.isNotEmpty() == true
        if (hasStickers && isBlankOrCustomEmojiOnly(message.content)) {
            return true
        }

        // Exclude empty, blank, or solely custom graphic emojis without text (unless attachments are present)
        val hasAttachments = runCatching { message.attachments }.getOrNull()?.isNotEmpty() == true
        if (!hasAttachments && isBlankOrCustomEmojiOnly(message.content)) {
            return true
        }

        return false
    }

    private fun isSystemMessageType(type: MessageType): Boolean =
        when (type) {
            MessageType.Default,
            MessageType.Reply,
            MessageType.ThreadStarterMessage,
            -> false

            else -> true
        }

    private fun isCommand(
        content: String,
        type: MessageType?,
    ): Boolean {
        if (type == MessageType.ChatInputCommand || type == MessageType.ContextMenuCommand) {
            return true
        }
        val trimmed = content.trim()
        if (trimmed.startsWith("/")) return true
        if (trimmed.startsWith("!") && trimmed.length > 1 && trimmed[1].isLetter()) return true
        return false
    }

    private fun isBlankOrCustomEmojiOnly(content: String): Boolean {
        if (content.isBlank()) return true
        return CUSTOM_EMOJI_REGEX.replace(content, "").isBlank()
    }

    companion object {
        val CUSTOM_EMOJI_REGEX = Regex("""<a?:[a-zA-Z0-9_]+:\d+>""")
    }
}
