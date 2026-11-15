package luna.core

import org.slf4j.LoggerFactory

/**
 * Bounded, thread-safe [StartupAlertBuffer] retaining pre-`ReadyEvent` alerts in FIFO order.
 *
 * Retains up to [maxEntries] (default `20`) qualifying alerts while `!isReady`. Excess events
 * beyond [maxEntries] are dropped with an observable degraded local warning logged via
 * [OwnerErrorNotifier.INTERNAL_LOGGER_NAME] under [OwnerErrorNotifier.withRecursionGuard].
 * Calling [markReadyAndDrain] atomically transitions [isReady] to `true` and drains buffered
 * events in FIFO order; subsequent runtime events return `false` from [bufferIfNotReady] so
 * they bypass the startup buffer.
 */
class BoundedStartupAlertBuffer(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : StartupAlertBuffer {
    init {
        require(maxEntries >= 0) { "maxEntries must be non-negative, got $maxEntries" }
    }

    private val internalLogger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME)
    private val lock = Any()
    private val buffer = ArrayDeque<OwnerErrorEvent>(maxEntries.coerceAtMost(DEFAULT_MAX_ENTRIES))

    @Volatile
    private var ready: Boolean = false
    private var droppedOverflowCount: Int = 0

    override val isReady: Boolean
        get() = ready

    val bufferedCount: Int
        get() = synchronized(lock) { buffer.size }

    val droppedCount: Int
        get() = synchronized(lock) { droppedOverflowCount }

    override fun bufferIfNotReady(event: OwnerErrorEvent): Boolean {
        var overflowTotal: Int? = null
        val handledByBuffer =
            synchronized(lock) {
                if (ready) {
                    return false
                }
                if (buffer.size < maxEntries) {
                    buffer.addLast(event)
                } else {
                    droppedOverflowCount += 1
                    overflowTotal = droppedOverflowCount
                }
                true
            }

        overflowTotal?.let { totalDropped ->
            OwnerErrorNotifier.withRecursionGuard {
                internalLogger.warn(
                    "Startup alert buffer full (maxEntries={}); dropping excess {} alert from {} (droppedCount={})",
                    maxEntries,
                    event.severity,
                    event.loggerName,
                    totalDropped,
                )
            }
        }

        return handledByBuffer
    }

    override fun markReadyAndDrain(): List<OwnerErrorEvent> =
        synchronized(lock) {
            if (ready) {
                return emptyList()
            }
            ready = true
            if (buffer.isEmpty()) {
                return emptyList()
            }
            val drained = buffer.toList()
            buffer.clear()
            drained
        }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20
    }
}
