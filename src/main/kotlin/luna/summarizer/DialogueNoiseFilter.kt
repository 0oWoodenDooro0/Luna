package luna.summarizer

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import luna.core.SummaryRegistry
import luna.core.WebhookRegistry
import luna.core.ai.DialogueContextPolicy
import luna.core.ai.DiscordDialogueContextEngine
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
    val summaryRegistry: SummaryRegistry = SummaryMessageRegistry,
    val contextEngine: DiscordDialogueContextEngine =
        DiscordDialogueContextEngine(
            webhookRegistry = webhookRegistry,
            summaryRegistry = summaryRegistry,
        ),
    val policy: DialogueContextPolicy = DialogueContextPolicy.summary(),
) {
    private val logger = LoggerFactory.getLogger(DialogueNoiseFilter::class.java)
    var kordInstance: Kord? = kord
        private set

    val selfId: Snowflake?
        get() = runCatching { kordInstance?.selfId }.getOrNull()

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
        val verifiedWebhook = if (webhookId != null) isLunaWebhook(webhookId) else null
        return contextEngine.isBotSummaryMessage(message, isVerifiedWebhook = verifiedWebhook)
    }

    open suspend fun isNoiseMessage(message: Message): Boolean {
        val webhookId = runCatching { message.webhookId }.getOrNull()
        val verifiedWebhook = if (webhookId != null) isLunaWebhook(webhookId) else null
        return contextEngine.isNoiseMessage(
            message = message,
            policy = policy,
            kordSelfId = selfId,
            isVerifiedWebhook = verifiedWebhook,
        )
    }

    companion object {
        const val LUNA_PREVIEW_WEBHOOK_NAME = "Luna Preview"
    }
}
