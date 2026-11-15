package luna.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import luna.config.SecretMasker
import net.logstash.logback.marker.SingleFieldAppendingMarker
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Asynchronous, fail-open owner DM error notification pipeline.
 *
 * Captures qualifying log events via [OwnerErrorLogbackAppender] into a bounded coroutine
 * [Channel], applies [ErrorEventFilter], [StartupAlertBuffer], [AlertRateLimiter], secret
 * redaction via [OwnerErrorRedactor], Discord 2,000-char formatting, and fail-open DM delivery.
 */
class OwnerErrorNotifier(
    val config: ErrorNotifyConfig,
    secretConfig: Map<String, Any?> = emptyMap(),
    private val eventFilter: ErrorEventFilter = DefaultErrorEventFilter(),
    private val rateLimiter: AlertRateLimiter = PassthroughAlertRateLimiter,
    private val startupBuffer: StartupAlertBuffer = ImmediateReadyStartupAlertBuffer,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val clock: Clock = Clock.systemUTC(),
    private val redactor: OwnerErrorRedactor = OwnerErrorRedactor.fromConfig(secretConfig),
    dispatcher: CoroutineDispatcher? = null,
    private val dmSender: (suspend (Snowflake, String) -> Unit)? = null,
) {
    private val internalLogger = LoggerFactory.getLogger(INTERNAL_LOGGER_NAME)
    private val closed = AtomicBoolean(false)
    private val kordRef = AtomicReference<Kord?>(null)
    private val channel = Channel<OwnerErrorEvent>(capacity = queueCapacity.coerceAtLeast(1))

    private val ownedExecutor: ExecutorService? =
        if (dispatcher == null) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "luna-owner-error-notifier").apply { isDaemon = true }
            }
        } else {
            null
        }
    private val workerDispatcher: CoroutineDispatcher =
        dispatcher ?: ownedExecutor!!.asCoroutineDispatcher()
    private val workerScope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private val workerJob: Job =
        workerScope.launch {
            runWorkerLoop()
        }

    fun bindKord(kord: Kord?) {
        kordRef.set(kord)
    }

    /**
     * Entry point invoked synchronously by [OwnerErrorLogbackAppender].
     * Never blocks the calling thread and enforces logger exclusion + recursion guard.
     */
    fun onLogEvent(loggingEvent: ILoggingEvent) {
        if (!config.isEnabled || closed.get()) return
        if (loggingEvent.level.levelInt < Level.WARN_INT) return
        if (isExcludedLogger(loggingEvent.loggerName)) return
        if (isRecursionGuardActive()) return

        val ownerEvent = toOwnerErrorEvent(loggingEvent)
        submit(ownerEvent)
    }

    /**
     * Submits an already-constructed [OwnerErrorEvent] through filtering and the bounded queue.
     */
    fun submit(event: OwnerErrorEvent) {
        if (!config.isEnabled || closed.get()) return
        if (isExcludedLogger(event.loggerName)) return
        if (!eventFilter.shouldNotify(event)) return

        val result = channel.trySend(event)
        if (!result.isSuccess) {
            withRecursionGuard {
                internalLogger.warn(
                    "Owner error notification queue full or closed (capacity={}); dropping {} alert from {}",
                    queueCapacity,
                    event.severity,
                    event.loggerName,
                )
            }
        }
    }

    /**
     * Signals Discord Gateway readiness (`ReadyEvent`) and flushes any buffered startup alerts.
     */
    suspend fun onReady() {
        if (!config.isEnabled || closed.get()) return
        withContext(workerDispatcher) {
            val buffered = startupBuffer.markReadyAndDrain()
            for (event in buffered) {
                dispatchWithRateLimit(event)
            }
        }
    }

    /**
     * Drains all queued alerts up to [timeoutMs] and closes the worker cleanly.
     */
    suspend fun flushAndClose(timeoutMs: Long = DEFAULT_SHUTDOWN_TIMEOUT_MS) {
        if (!closed.compareAndSet(false, true)) return
        channel.close()
        withContext(Dispatchers.IO) {
            val drained =
                withTimeoutOrNull(timeoutMs) {
                    workerJob.join()
                    true
                } ?: false
            if (!drained) {
                withRecursionGuard {
                    internalLogger.warn(
                        "Timed out waiting {}ms for OwnerErrorNotifier to drain queued alerts",
                        timeoutMs,
                    )
                }
                workerScope.cancel()
            }
            ownedExecutor?.shutdown()
            if (!drained) {
                ownedExecutor?.awaitTermination(500L, TimeUnit.MILLISECONDS)
            }
        }
    }

    private suspend fun runWorkerLoop() {
        for (event in channel) {
            if (startupBuffer.bufferIfNotReady(event)) {
                continue
            }
            dispatchWithRateLimit(event)
        }
    }

    private suspend fun dispatchWithRateLimit(event: OwnerErrorEvent) {
        val allowedEvent = rateLimiter.acquire(event) ?: return
        val formatted = formatAlertMessage(allowedEvent)
        dispatchToRecipients(formatted)
    }

    internal fun formatAlertMessage(event: OwnerErrorEvent): String {
        val sanitized = redactor.redactEvent(event)
        val badge =
            when (sanitized.severity) {
                ErrorAlertSeverity.ERROR -> "🚨 **[ERROR]**"
                ErrorAlertSeverity.WARN -> "⚠️ **[WARN]**"
            }
        val envTag = config.environment?.let { " `[$it]`" } ?: ""
        val displayTarget = sanitized.component ?: sanitized.loggerName.substringAfterLast('.')

        val metadataLines =
            buildList {
                add("$badge$envTag `$displayTarget`")
                add("**Time:** ${sanitized.timestamp}")
                val sourceParts =
                    buildList {
                        add("**Source:** `${sanitized.loggerName}`")
                        sanitized.layer?.let { add("**Layer:** `$it`") }
                        sanitized.operation?.let { add("**Op:** `$it`") }
                        sanitized.threadName?.let { add("**Thread:** `$it`") }
                    }
                add(sourceParts.joinToString(" | "))

                val contextTokens =
                    buildList {
                        sanitized.traceId?.let { add("`traceId=$it`") }
                        sanitized.guildId?.let { add("`guildId=$it`") }
                        sanitized.channelId?.let { add("`channelId=$it`") }
                        sanitized.contextFields.entries
                            .filter { (k, _) -> k in SAFE_CONTEXT_ALLOWLIST && k !in TOP_LEVEL_CONTEXT_KEYS }
                            .take(MAX_EXTRA_CONTEXT_FIELDS)
                            .forEach { (k, v) -> add("`$k=$v`") }
                    }
                if (contextTokens.isNotEmpty()) {
                    add("**Context:** ${contextTokens.joinToString(" ")}")
                }
                if (sanitized.suppressedCount > 0) {
                    add("**Suppressed:** ${sanitized.suppressedCount} repeated alert(s) during cooldown")
                }
            }

        val bodyBuilder = StringBuilder()
        bodyBuilder.append(metadataLines.joinToString("\n"))
        bodyBuilder.append("\n**Message:** ").append(sanitized.message.ifBlank { "(empty message)" })

        sanitized.throwableClass?.let { exClass ->
            bodyBuilder.append("\n**Exception:** `").append(exClass).append("`")
        }

        val trace = sanitized.stackTrace?.trim()
        if (!trace.isNullOrEmpty()) {
            val headerSoFar = bodyBuilder.toString()
            // Reserve room for "\n```\n" + "\n```" (9 chars) and stay within 2,000 chars
            val remainingBudget = (MAX_DISCORD_MESSAGE_LENGTH - headerSoFar.length - 12).coerceAtLeast(0)
            if (remainingBudget > 20) {
                val clippedTrace = truncateForDiscord(trace, remainingBudget)
                bodyBuilder.append("\n```\n").append(clippedTrace).append("\n```")
            }
        }

        return truncateForDiscord(bodyBuilder.toString(), MAX_DISCORD_MESSAGE_LENGTH)
    }

    private suspend fun dispatchToRecipients(message: String) {
        withContext(recursionThreadLocal.asContextElement(true)) {
            for (userId in config.recipientUserIds) {
                runCatching {
                    val sender = dmSender
                    if (sender != null) {
                        sender.invoke(userId, message)
                    } else {
                        val kord =
                            kordRef.get()
                                ?: throw IllegalStateException("Kord client is not initialized")
                        val user =
                            kord.getUser(userId)
                                ?: throw IllegalStateException("Discord user $userId not found or unreachable")
                        user.getDmChannel().createMessage(message)
                    }
                }.onFailure { err ->
                    internalLogger.warn(
                        "Failed to deliver owner error DM to user {}: {}",
                        userId.value,
                        err.message ?: err::class.simpleName,
                    )
                }
            }
        }
    }

    private fun toOwnerErrorEvent(event: ILoggingEvent): OwnerErrorEvent {
        val severity =
            if (event.level.levelInt >= Level.ERROR_INT) {
                ErrorAlertSeverity.ERROR
            } else {
                ErrorAlertSeverity.WARN
            }

        val extracted = extractStructuredContext(event)
        val throwableProxy = event.throwableProxy
        val stackTrace = throwableProxy?.let { ThrowableProxyUtil.asString(it) }
        val messageText =
            buildString {
                append(event.formattedMessage ?: "")
                val explicitErrorMsg = extracted["error_message"] ?: extracted["errorMessage"]
                if (!explicitErrorMsg.isNullOrBlank() && !contains(explicitErrorMsg)) {
                    if (isNotEmpty()) append(" | ")
                    append(explicitErrorMsg)
                }
            }

        return OwnerErrorEvent(
            timestamp = Instant.ofEpochMilli(event.timeStamp).takeIf { event.timeStamp > 0 } ?: clock.instant(),
            severity = severity,
            loggerName = event.loggerName ?: "unknown",
            message = messageText,
            throwableClass = throwableProxy?.className,
            stackTrace = stackTrace,
            layer = extracted["layer"],
            component = extracted["component"],
            operation = extracted["operation"],
            traceId = extracted["traceId"] ?: extracted["trace_id"],
            guildId = extracted["guildId"] ?: extracted["guild_id"],
            channelId = extracted["channelId"] ?: extracted["channel_id"],
            threadName = event.threadName,
            contextFields = extracted.filterKeys { it in SAFE_CONTEXT_ALLOWLIST },
        )
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 128
        const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 2_000L
        const val MAX_DISCORD_MESSAGE_LENGTH = 2_000
        const val INTERNAL_LOGGER_NAME = "luna.core.OwnerErrorNotifier.INTERNAL"
        private const val MAX_EXTRA_CONTEXT_FIELDS = 6
        private const val TRUNCATION_ELLIPSIS = "..."

        private val recursionThreadLocal = ThreadLocal.withInitial { false }

        private val EXCLUDED_LOGGER_PREFIXES =
            listOf(
                INTERNAL_LOGGER_NAME,
                OwnerErrorNotifier::class.java.name,
                OwnerErrorLogbackAppender::class.java.name,
            )

        private val TOP_LEVEL_CONTEXT_KEYS =
            setOf("layer", "component", "operation", "traceId", "trace_id", "guildId", "guild_id", "channelId", "channel_id")

        private val SAFE_CONTEXT_ALLOWLIST =
            setOf(
                "traceId",
                "trace_id",
                "guildId",
                "guild_id",
                "channelId",
                "channel_id",
                "messageId",
                "message_id",
                "userId",
                "user_id",
                "authorId",
                "author_id",
                "stage",
                "model",
                "attempt",
                "status",
                "reason",
                "code",
                "httpStatus",
                "statusCode",
                "errorCode",
                "durationMs",
            )

        private val EXTRACTABLE_METADATA_KEYS =
            SAFE_CONTEXT_ALLOWLIST + setOf("layer", "component", "operation", "error_message", "errorMessage")

        private val singleFieldValueMethod =
            runCatching {
                SingleFieldAppendingMarker::class.java.getDeclaredMethod("getFieldValue").apply {
                    isAccessible = true
                }
            }.getOrNull()

        fun isExcludedLogger(loggerName: String?): Boolean {
            if (loggerName.isNullOrBlank()) return false
            return EXCLUDED_LOGGER_PREFIXES.any { prefix ->
                loggerName == prefix || loggerName.startsWith("$prefix.")
            }
        }

        fun isRecursionGuardActive(): Boolean = recursionThreadLocal.get() == true

        fun <T> withRecursionGuard(block: () -> T): T {
            val previous = recursionThreadLocal.get()
            recursionThreadLocal.set(true)
            return try {
                block()
            } finally {
                recursionThreadLocal.set(previous)
            }
        }

        fun truncateForDiscord(
            text: String,
            maxLength: Int = MAX_DISCORD_MESSAGE_LENGTH,
        ): String {
            if (maxLength <= 0) return ""
            if (text.length <= maxLength) return text
            if (maxLength <= TRUNCATION_ELLIPSIS.length) return text.take(maxLength)
            return text.take(maxLength - TRUNCATION_ELLIPSIS.length) + TRUNCATION_ELLIPSIS
        }

        private fun extractStructuredContext(event: ILoggingEvent): Map<String, String> {
            val collected = linkedMapOf<String, String>()

            event.mdcPropertyMap?.forEach { (key, value) ->
                if (key in EXTRACTABLE_METADATA_KEYS && !value.isNullOrBlank() && !SecretMasker.isSensitiveKey(key)) {
                    collected[key] = value
                }
            }

            val args = event.argumentArray ?: return collected
            for (arg in args) {
                if (arg is SingleFieldAppendingMarker) {
                    val fieldName = arg.fieldName
                    val fieldValue =
                        runCatching {
                            singleFieldValueMethod?.invoke(arg)
                        }.getOrNull()

                    if (fieldName == "data" && fieldValue is Map<*, *>) {
                        fieldValue.forEach { (rawK, rawV) ->
                            val k = rawK?.toString() ?: return@forEach
                            val v = rawV?.toString() ?: return@forEach
                            if (k in EXTRACTABLE_METADATA_KEYS && v.isNotBlank() && !SecretMasker.isSensitiveKey(k)) {
                                collected.putIfAbsent(k, v)
                            }
                        }
                    } else if (fieldName in EXTRACTABLE_METADATA_KEYS && fieldValue != null && !SecretMasker.isSensitiveKey(fieldName)) {
                        val strVal = fieldValue.toString()
                        if (strVal.isNotBlank()) {
                            collected.putIfAbsent(fieldName, strVal)
                        }
                    }
                }
            }

            return collected
        }
    }
}

/**
 * Non-blocking Logback appender forwarding [ILoggingEvent]s into [OwnerErrorNotifier].
 */
class OwnerErrorLogbackAppender(
    private val notifier: OwnerErrorNotifier,
) : AppenderBase<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent) {
        notifier.onLogEvent(eventObject)
    }

    companion object {
        const val APPENDER_NAME = "OWNER_ERROR_NOTIFIER"
    }
}
