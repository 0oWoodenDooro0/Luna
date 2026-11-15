package luna.core

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Bounded fingerprint cooldown deduplicator and per-severity global rate limiter.
 *
 * - Normalizes each [OwnerErrorEvent] into a stable fingerprint by severity, source/component,
 *   exception class, and normalized message signature (stripping Snowflakes, UUIDs, timestamps,
 *   durations, dynamic IDs, and numeric tokens).
 * - Suppresses repeated matching alerts during [effectiveCooldownSeconds] (default `300`, falling
 *   back safely to `300` for non-positive values) and attaches [OwnerErrorEvent.suppressedCount]
 *   to the first alert dispatched after cooldown expiry.
 * - Stores per-fingerprint cooldown state in a bounded LRU [LinkedHashMap] (`removeEldestEntry`)
 *   capped at [effectiveMaxFingerprints] to avoid unbounded memory growth.
 * - Enforces independent sliding 60-second global budgets for [ErrorAlertSeverity.ERROR] and
 *   [ErrorAlertSeverity.WARN] so noisy warnings cannot starve error alerts, emitting an observable
 *   warning log via [OwnerErrorNotifier.INTERNAL_LOGGER_NAME] under a recursion guard on drops.
 */
class BoundedAlertRateLimiter(
    cooldownSeconds: Long = ErrorNotifyConfig.DEFAULT_COOLDOWN_SECONDS.toLong(),
    maxErrorsPerMinute: Int = DEFAULT_MAX_ERRORS_PER_MINUTE,
    maxWarnsPerMinute: Int = DEFAULT_MAX_WARNS_PER_MINUTE,
    maxFingerprints: Int = DEFAULT_MAX_FINGERPRINTS,
    private val clock: Clock = Clock.systemUTC(),
    private val warningLogger: Logger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME),
) : AlertRateLimiter {
    constructor(
        cooldownSeconds: Int,
        maxErrorsPerMinute: Int = DEFAULT_MAX_ERRORS_PER_MINUTE,
        maxWarnsPerMinute: Int = DEFAULT_MAX_WARNS_PER_MINUTE,
        maxFingerprints: Int = DEFAULT_MAX_FINGERPRINTS,
        clock: Clock = Clock.systemUTC(),
        warningLogger: Logger = LoggerFactory.getLogger(OwnerErrorNotifier.INTERNAL_LOGGER_NAME),
    ) : this(
        cooldownSeconds = cooldownSeconds.toLong(),
        maxErrorsPerMinute = maxErrorsPerMinute,
        maxWarnsPerMinute = maxWarnsPerMinute,
        maxFingerprints = maxFingerprints,
        clock = clock,
        warningLogger = warningLogger,
    )

    val effectiveCooldownSeconds: Long =
        if (cooldownSeconds > 0L) {
            cooldownSeconds
        } else {
            ErrorNotifyConfig.DEFAULT_COOLDOWN_SECONDS.toLong()
        }

    val effectiveMaxErrorsPerMinute: Int =
        if (maxErrorsPerMinute > 0) maxErrorsPerMinute else DEFAULT_MAX_ERRORS_PER_MINUTE

    val effectiveMaxWarnsPerMinute: Int =
        if (maxWarnsPerMinute > 0) maxWarnsPerMinute else DEFAULT_MAX_WARNS_PER_MINUTE

    val effectiveMaxFingerprints: Int =
        if (maxFingerprints > 0) maxFingerprints else DEFAULT_MAX_FINGERPRINTS

    private val cooldownMillis: Long = effectiveCooldownSeconds * MILLIS_PER_SECOND
    private val lock = Any()

    private data class FingerprintState(
        var lastDispatchedAtMillis: Long,
        var suppressedCount: Int = 0,
    )

    private val fingerprintCache: MutableMap<String, FingerprintState> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, FingerprintState>(effectiveMaxFingerprints, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FingerprintState>?): Boolean = size > effectiveMaxFingerprints
            },
        )

    private val errorDispatchTimestamps = ArrayDeque<Long>(effectiveMaxErrorsPerMinute)
    private val warnDispatchTimestamps = ArrayDeque<Long>(effectiveMaxWarnsPerMinute)

    init {
        if (cooldownSeconds <= 0L) {
            OwnerErrorNotifier.withRecursionGuard {
                warningLogger.warn(
                    "Invalid alert rate limiter cooldownSeconds={}; falling back to default {}s",
                    cooldownSeconds,
                    ErrorNotifyConfig.DEFAULT_COOLDOWN_SECONDS,
                )
            }
        }
        if (maxErrorsPerMinute <= 0 || maxWarnsPerMinute <= 0 || maxFingerprints <= 0) {
            OwnerErrorNotifier.withRecursionGuard {
                warningLogger.warn(
                    "Non-positive rate limiter bounds (maxErrorsPerMinute={}, maxWarnsPerMinute={}, maxFingerprints={}); using safe defaults ({}, {}, {})",
                    maxErrorsPerMinute,
                    maxWarnsPerMinute,
                    maxFingerprints,
                    effectiveMaxErrorsPerMinute,
                    effectiveMaxWarnsPerMinute,
                    effectiveMaxFingerprints,
                )
            }
        }
    }

    override fun acquire(event: OwnerErrorEvent): OwnerErrorEvent? {
        val nowMillis = clock.millis()
        val fingerprint = computeFingerprint(event)

        var rateLimitDroppedLimit: Int? = null

        val result =
            synchronized(lock) {
                val existing = fingerprintCache[fingerprint]
                if (existing != null && nowMillis - existing.lastDispatchedAtMillis < cooldownMillis) {
                    existing.suppressedCount += 1
                    return@synchronized null
                }

                val (window, limit) =
                    when (event.severity) {
                        ErrorAlertSeverity.ERROR -> errorDispatchTimestamps to effectiveMaxErrorsPerMinute
                        ErrorAlertSeverity.WARN -> warnDispatchTimestamps to effectiveMaxWarnsPerMinute
                    }

                evictExpiredWindowEntries(window, nowMillis)
                if (window.size >= limit) {
                    if (existing != null) {
                        existing.suppressedCount += 1
                    }
                    rateLimitDroppedLimit = limit
                    return@synchronized null
                }

                window.addLast(nowMillis)
                val suppressed = existing?.suppressedCount ?: 0
                fingerprintCache[fingerprint] = FingerprintState(lastDispatchedAtMillis = nowMillis, suppressedCount = 0)
                if (suppressed > 0) {
                    event.copy(suppressedCount = suppressed)
                } else {
                    event
                }
            }

        rateLimitDroppedLimit?.let { limit ->
            OwnerErrorNotifier.withRecursionGuard {
                warningLogger.warn(
                    "Owner error notification global {} rate limit reached ({}/min); dropping alert from {} (fingerprint={})",
                    event.severity,
                    limit,
                    event.loggerName,
                    fingerprint,
                )
            }
        }

        return result
    }

    private fun evictExpiredWindowEntries(
        window: ArrayDeque<Long>,
        nowMillis: Long,
    ) {
        while (window.isNotEmpty() && nowMillis - window.first() >= RATE_LIMIT_WINDOW_MILLIS) {
            window.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_ERRORS_PER_MINUTE = 5
        const val DEFAULT_MAX_WARNS_PER_MINUTE = 5
        const val DEFAULT_MAX_FINGERPRINTS = 500
        private const val MILLIS_PER_SECOND = 1_000L
        private const val RATE_LIMIT_WINDOW_MILLIS = 60_000L

        private val UUID_REGEX =
            Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
        private val ISO_TIMESTAMP_REGEX =
            Regex("""\b\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)?\b""")
        private val TIME_ONLY_REGEX =
            Regex("""\b\d{2}:\d{2}:\d{2}(?:\.\d+)?\b""")
        private val DYNAMIC_ID_KV_REGEX =
            Regex(
                """(?i)\b(trace_?id|request_?id|correlation_?id|guild_?id|channel_?id|message_?id|user_?id|author_?id|session_?id|interaction_?id|job_?id|run_?id)\s*([:=])\s*[^\s,;\]})]+""",
            )
        private val HEX_PREFIX_REGEX = Regex("""\b0x[0-9a-fA-F]+\b""")
        private val HEX_TOKEN_REGEX =
            Regex("""\b(?=[0-9a-fA-F]*[0-9])(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{8,}\b""")
        private val DURATION_REGEX =
            Regex("""(?i)\b\d+(?:\.\d+)?(?:ms|ns|us|s|sec|secs|seconds|m|min|mins|minutes|h|hr|hrs|hours)\b""")
        private val UNDERSCORE_NUM_REGEX = Regex("""_\d+\b""")
        private val NUMBER_REGEX = Regex("""\b\d+(?:\.\d+)?\b""")
        private val WHITESPACE_REGEX = Regex("""\s+""")

        fun computeFingerprint(event: OwnerErrorEvent): String {
            val loggerPart =
                event.loggerName
                    .trim()
                    .ifEmpty { "unknown" }
                    .lowercase()
            val componentPart =
                event.component
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
            val throwablePart = event.throwableClass?.trim()?.takeIf { it.isNotEmpty() } ?: "none"
            val normalizedMessage = normalizeMessage(event.message)
            return "${event.severity.name}|$loggerPart|$componentPart|$throwablePart|$normalizedMessage"
        }

        fun normalizeMessage(message: String): String {
            if (message.isBlank()) return ""
            var normalized = message
            normalized = UUID_REGEX.replace(normalized, "<uuid>")
            normalized = ISO_TIMESTAMP_REGEX.replace(normalized, "<ts>")
            normalized = TIME_ONLY_REGEX.replace(normalized, "<ts>")
            normalized =
                DYNAMIC_ID_KV_REGEX.replace(normalized) { match ->
                    "${match.groupValues[1]}${match.groupValues[2]}<id>"
                }
            normalized = HEX_PREFIX_REGEX.replace(normalized, "<hex>")
            normalized = HEX_TOKEN_REGEX.replace(normalized, "<hex>")
            normalized = DURATION_REGEX.replace(normalized, "<duration>")
            normalized = UNDERSCORE_NUM_REGEX.replace(normalized, "_<num>")
            normalized = NUMBER_REGEX.replace(normalized, "<num>")
            return WHITESPACE_REGEX.replace(normalized, " ").trim().lowercase()
        }
    }
}
