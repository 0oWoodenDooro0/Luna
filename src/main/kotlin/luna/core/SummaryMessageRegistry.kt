package luna.core

import dev.kord.common.entity.Snowflake
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Interface defining summary message registry operations for tracking dispatched AI summary messages.
 */
interface SummaryRegistry {
    fun register(messageId: Snowflake)

    fun register(messageId: Long)

    fun contains(messageId: Snowflake): Boolean

    fun contains(messageId: Long): Boolean

    fun remove(messageId: Snowflake): Boolean

    fun remove(messageId: Long): Boolean

    fun clear()

    val size: Int

    companion object {
        fun isSummaryContent(content: String): Boolean {
            val trimmed = content.trim()
            return trimmed.startsWith("TL;DR:") ||
                trimmed.startsWith("TL;DR") ||
                content.contains("重點摘要") ||
                content.contains("Previous summary")
        }
    }
}

/**
 * Thread-safe bounded LRU set implementation for summary message tracking.
 */
class BoundedSummaryRegistry(
    val maxEntries: Int = MAX_REGISTRY_ENTRIES,
) : SummaryRegistry {
    companion object {
        const val MAX_REGISTRY_ENTRIES = 2000
    }

    private val messageIds: MutableSet<Snowflake> =
        Collections.synchronizedSet(
            Collections.newSetFromMap(
                object : LinkedHashMap<Snowflake, Boolean>(maxEntries, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, Boolean>?): Boolean = size > maxEntries
                },
            ),
        )

    override fun register(messageId: Snowflake) {
        messageIds.add(messageId)
    }

    override fun register(messageId: Long) {
        messageIds.add(Snowflake(messageId))
    }

    override fun contains(messageId: Snowflake): Boolean = messageIds.contains(messageId)

    override fun contains(messageId: Long): Boolean = messageIds.contains(Snowflake(messageId))

    override fun remove(messageId: Snowflake): Boolean = messageIds.remove(messageId)

    override fun remove(messageId: Long): Boolean = messageIds.remove(Snowflake(messageId))

    override fun clear() {
        messageIds.clear()
    }

    override val size: Int
        get() = messageIds.size

    val all: Set<Snowflake>
        get() = Collections.unmodifiableSet(messageIds)
}

/**
 * Thread-safe in-memory registry of Discord messages containing AI summaries dispatched by Luna.
 *
 * Provides fast O(1) lookup to prevent Chatter and Conversation Slice loops.
 */
object SummaryMessageRegistry : SummaryRegistry {
    const val MAX_REGISTRY_ENTRIES = BoundedSummaryRegistry.MAX_REGISTRY_ENTRIES

    private val delegate = BoundedSummaryRegistry(MAX_REGISTRY_ENTRIES)

    override fun register(messageId: Snowflake) {
        delegate.register(messageId)
    }

    override fun register(messageId: Long) {
        delegate.register(messageId)
    }

    override fun contains(messageId: Snowflake): Boolean = delegate.contains(messageId)

    override fun contains(messageId: Long): Boolean = delegate.contains(messageId)

    override fun remove(messageId: Snowflake): Boolean = delegate.remove(messageId)

    override fun remove(messageId: Long): Boolean = delegate.remove(messageId)

    override fun clear() {
        delegate.clear()
    }

    override val size: Int
        get() = delegate.size

    val all: Set<Snowflake>
        get() = delegate.all
}
