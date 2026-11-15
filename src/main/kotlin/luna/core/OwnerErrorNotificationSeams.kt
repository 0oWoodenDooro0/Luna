package luna.core

import java.time.Instant

/**
 * Severity level for Owner Error Notification alerts.
 */
enum class ErrorAlertSeverity {
    WARN,
    ERROR,
}

/**
 * Immutable event payload flowing through the Owner Error Notification pipeline.
 */
data class OwnerErrorEvent(
    val timestamp: Instant,
    val severity: ErrorAlertSeverity,
    val loggerName: String,
    val message: String,
    val throwableClass: String? = null,
    val stackTrace: String? = null,
    val layer: String? = null,
    val component: String? = null,
    val operation: String? = null,
    val traceId: String? = null,
    val guildId: String? = null,
    val channelId: String? = null,
    val threadName: String? = null,
    val contextFields: Map<String, String> = emptyMap(),
    val suppressedCount: Int = 0,
)

/**
 * Seam for filtering and correlating incoming log events before rate-limiting and DM dispatch.
 */
fun interface ErrorEventFilter {
    fun shouldNotify(event: OwnerErrorEvent): Boolean
}

/**
 * Seam for fingerprint cooldown deduplication and per-severity rate limiting.
 *
 * Returns the [OwnerErrorEvent] to dispatch (optionally enriched with [OwnerErrorEvent.suppressedCount]),
 * or `null` if the alert is suppressed by cooldown or rate limits.
 */
fun interface AlertRateLimiter {
    fun acquire(event: OwnerErrorEvent): OwnerErrorEvent?
}

/**
 * Seam for buffering pre-`ReadyEvent` startup alerts until Discord Gateway readiness.
 */
interface StartupAlertBuffer {
    val isReady: Boolean

    /**
     * Retains [event] if the gateway is not yet ready and returns `true`,
     * or returns `false` if the gateway is ready for immediate dispatch.
     */
    fun bufferIfNotReady(event: OwnerErrorEvent): Boolean

    /**
     * Transitions to ready state and drains buffered events in FIFO order.
     */
    fun markReadyAndDrain(): List<OwnerErrorEvent>
}
