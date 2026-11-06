package luna.summarizer

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import luna.core.LunaWebhookRegistry
import luna.core.WebhookRegistry
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Thread-safe bounded LRU cache for webhook verification to avoid unbounded daemon memory growth.
 */
class BoundedWebhookCache(
    private val maxEntries: Int = 1000,
) {
    private val map =
        Collections.synchronizedMap(
            object : LinkedHashMap<Snowflake, Boolean>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, Boolean>?): Boolean = size > maxEntries
            },
        )

    operator fun get(key: Snowflake): Boolean? = map[key]

    operator fun set(
        key: Snowflake,
        value: Boolean,
    ) {
        map[key] = value
    }

    fun remove(key: Snowflake): Boolean = map.remove(key) != null

    fun clear() {
        map.clear()
    }
}

/**
 * Encapsulates dialogue noise filtering and message boundary checks for conversation slices.
 */
open class DialogueNoiseFilter(
    private val isLunaWebhook: (suspend (Snowflake) -> Boolean)? = null,
    kord: Kord? = null,
    val webhookCache: BoundedWebhookCache = BoundedWebhookCache(),
    val webhookRegistry: WebhookRegistry = LunaWebhookRegistry,
) {
    private val logger = LoggerFactory.getLogger(DialogueNoiseFilter::class.java)
    private var kordInstance: Kord? = kord

    fun setKord(kord: Kord) {
        this.kordInstance = kord
    }

    fun clearWebhookCache() {
        webhookCache.clear()
    }

    open suspend fun isLunaWebhook(webhookId: Snowflake): Boolean {
        // Fast, permissionless O(1) in-memory verification from LunaWebhookRegistry
        if (webhookRegistry.contains(webhookId)) {
            return true
        }

        val cached = webhookCache[webhookId]
        if (cached != null) {
            return cached
        }
        val hook = isLunaWebhook
        val currentKord = kordInstance
        val isLuna =
            if (hook != null) {
                try {
                    hook(webhookId)
                } catch (e: Exception) {
                    logger.warn("Transient error verifying webhook {} via hook: {}", webhookId, e.message)
                    return false
                }
            } else if (currentKord != null) {
                val webhook =
                    try {
                        currentKord.getWebhookOrNull(webhookId)
                    } catch (e: Exception) {
                        logger.warn("Transient error retrieving webhook {} from Discord REST: {}", webhookId, e.message)
                        return false
                    }
                val verified = webhook != null && (webhook.creatorId == currentKord.selfId || webhook.name == LUNA_PREVIEW_WEBHOOK_NAME)
                if (verified) {
                    webhookRegistry.register(webhookId)
                }
                verified
            } else {
                false
            }
        webhookCache[webhookId] = isLuna
        return isLuna
    }

    open suspend fun isBotSummaryMessage(message: Message): Boolean {
        val webhookId = runCatching { message.webhookId }.getOrNull()
        if (webhookId != null && isLunaWebhook(webhookId)) {
            return false
        }
        return message.author?.isBot == true &&
            (
                message.content.startsWith("TL;DR:") || message.content.startsWith("TL;DR") || message.content.contains("重點摘要") ||
                    message.content.contains("Previous summary")
            )
    }

    open suspend fun isNoiseMessage(message: Message): Boolean {
        val webhookId = runCatching { message.webhookId }.getOrNull()
        val isLuna = webhookId != null && isLunaWebhook(webhookId)

        // Exclude unknown or third-party webhooks
        if (webhookId != null && !isLuna) {
            return true
        }

        // Exclude bot or missing author (unless it's a Luna Webhook Replacement)
        if (!isLuna && (message.author == null || message.author?.isBot == true)) {
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

        val hasAttachments = runCatching { message.attachments }.getOrNull()?.isNotEmpty() == true

        // Exclude messages consisting solely of stickers (unless attachments are present)
        val hasStickers = runCatching { message.stickers }.getOrNull()?.isNotEmpty() == true
        if (hasStickers && !hasAttachments && isBlankOrCustomEmojiOnly(message.content)) {
            return true
        }

        // Exclude empty, blank, or solely custom graphic emojis without text (unless attachments are present)
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
        const val LUNA_PREVIEW_WEBHOOK_NAME = "Luna Preview"
        val CUSTOM_EMOJI_REGEX = Regex("""<a?:[a-zA-Z0-9_]+:\d+>""")
    }
}
