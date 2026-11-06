package luna.core

import dev.kord.common.entity.Snowflake
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface defining webhook registry operations for tracking Luna-managed webhooks.
 */
interface WebhookRegistry {
    fun register(webhookId: Snowflake)

    fun register(webhookId: Long)

    fun contains(webhookId: Snowflake): Boolean

    fun contains(webhookId: Long): Boolean

    fun remove(webhookId: Snowflake): Boolean

    fun remove(webhookId: Long): Boolean

    fun clear()

    val size: Int
}

/**
 * Thread-safe in-memory registry of Discord Webhooks created or managed by Luna
 * (e.g. Luna Preview masquerading webhooks).
 *
 * Provides fast O(1), permissionless verification for the dialogue noise filter
 * without making Discord REST API calls.
 */
object LunaWebhookRegistry : WebhookRegistry {
    const val MAX_REGISTRY_ENTRIES = 2000

    private val webhookIds: MutableSet<Snowflake> =
        Collections.synchronizedSet(
            Collections.newSetFromMap(
                object : LinkedHashMap<Snowflake, Boolean>(MAX_REGISTRY_ENTRIES, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, Boolean>?): Boolean = size > MAX_REGISTRY_ENTRIES
                },
            ),
        )

    override fun register(webhookId: Snowflake) {
        webhookIds.add(webhookId)
    }

    override fun register(webhookId: Long) {
        webhookIds.add(Snowflake(webhookId))
    }

    override fun contains(webhookId: Snowflake): Boolean = webhookIds.contains(webhookId)

    override fun contains(webhookId: Long): Boolean = webhookIds.contains(Snowflake(webhookId))

    override fun remove(webhookId: Snowflake): Boolean = webhookIds.remove(webhookId)

    override fun remove(webhookId: Long): Boolean = webhookIds.remove(Snowflake(webhookId))

    override fun clear() {
        webhookIds.clear()
    }

    override val size: Int
        get() = webhookIds.size

    val all: Set<Snowflake>
        get() = Collections.unmodifiableSet(webhookIds)
}
