package luna.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import luna.core.JsonLogger
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Shared recording seam for emitting [AiTraceRecord] diagnostics across Luna plugins.
 */
interface AiTraceRecorder : AutoCloseable {
    fun isEnabled(): Boolean

    fun startSession(
        pipeline: AiTracePipeline,
        trigger: AiTraceTrigger,
        discord: AiTraceDiscordContext,
    ): AiTraceSession

    fun record(trace: AiTraceRecord)

    fun record(
        trace: AiTraceRecord,
        extraSecrets: Set<String>,
    ) {
        record(trace)
    }

    suspend fun flushAndClose(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS)

    override fun close() {
        runBlocking {
            flushAndClose()
        }
    }

    companion object {
        const val DEFAULT_FLUSH_TIMEOUT_MS = 5000L
    }
}

/**
 * Default no-op [AiTraceRecorder] used in standalone constructors, disabled configs, and unit tests.
 */
object NoOpAiTraceRecorder : AiTraceRecorder {
    override fun isEnabled(): Boolean = false

    override fun startSession(
        pipeline: AiTracePipeline,
        trigger: AiTraceTrigger,
        discord: AiTraceDiscordContext,
    ): AiTraceSession =
        AiTraceSession(
            recorder = this,
            pipeline = pipeline,
            trigger = trigger,
            discord = discord,
            isEnabled = false,
            emitCorrelationLog = false,
        )

    override fun record(trace: AiTraceRecord) {
        // No-op
    }

    override suspend fun flushAndClose(timeoutMs: Long) {
        // No-op
    }

    override fun close() {
        // No-op
    }
}

/**
 * Thread-safe builder for a single Discord-triggered AI workflow execution.
 * Accumulates ordered [AiAttemptRecord] entries and flushes idempotently via [finish] / [close].
 */
class AiTraceSession internal constructor(
    private val recorder: AiTraceRecorder,
    val pipeline: AiTracePipeline,
    val trigger: AiTraceTrigger,
    val discord: AiTraceDiscordContext,
    val isEnabled: Boolean = recorder.isEnabled(),
    private val emitCorrelationLog: Boolean = true,
    clock: Clock = Clock.systemUTC(),
    val traceId: String = UUID.randomUUID().toString(),
) : AutoCloseable {
    private val startedInstant: Instant = clock.instant()
    private val startNanoTime: Long = System.nanoTime()
    private val lock = Any()
    private val flushed = AtomicBoolean(false)

    private val attempts = mutableListOf<AiAttemptRecord>()
    private val fallbackPath = mutableListOf<String>()
    private val extraSecrets = mutableSetOf<String>()

    private var outcome: AiTraceOutcome? = null
    private var degraded: Boolean = false
    private var failureCategory: AiTraceFailureCategory? = null
    private var finalOutput: String? = null
    private var citations: List<GroundingCitation> = emptyList()
    private var errorSummary: String? = null

    fun registerSecret(secret: String?) {
        if (!isEnabled || secret.isNullOrBlank()) return
        synchronized(lock) {
            extraSecrets.add(secret)
        }
    }

    fun recordAttempt(
        stage: AiAttemptStage,
        variant: String? = null,
        model: String? = null,
        endpoint: String? = null,
        latencyMs: Long = 0L,
        httpStatus: Int? = null,
        request: AiAttemptRequest? = null,
        responseRaw: String? = null,
        finishReason: String? = null,
        blockReason: String? = null,
        usageMetadata: GeminiUsageMetadata? = null,
        pollingAttempts: Int? = null,
        parsedOutcome: AiAttemptOutcome,
        errorMessage: String? = null,
        urlContextResults: List<UrlContextResultEntry> = emptyList(),
        googleSearchQueries: List<String> = emptyList(),
        rawCitations: List<GroundingCitation> = emptyList(),
        filteredCitations: List<GroundingCitation> = emptyList(),
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            val attemptIndex = attempts.size + 1
            val pathLabel =
                if (!variant.isNullOrBlank()) {
                    "${stage.name}:$variant"
                } else {
                    stage.name
                }
            fallbackPath.add(pathLabel)
            attempts.add(
                AiAttemptRecord(
                    attemptIndex = attemptIndex,
                    stage = stage,
                    model = model,
                    endpoint = endpoint,
                    latencyMs = latencyMs,
                    httpStatus = httpStatus,
                    request = request,
                    responseRaw = responseRaw,
                    finishReason = finishReason,
                    blockReason = blockReason,
                    usageMetadata = usageMetadata,
                    pollingAttempts = pollingAttempts,
                    parsedOutcome = parsedOutcome,
                    errorMessage = errorMessage,
                    urlContextResults = urlContextResults,
                    googleSearchQueries = googleSearchQueries,
                    rawCitations = rawCitations,
                    filteredCitations = filteredCitations,
                ),
            )
        }
    }

    fun markDegraded(reason: String? = null) {
        if (!isEnabled) return
        synchronized(lock) {
            degraded = true
            if (!reason.isNullOrBlank() && errorSummary == null) {
                errorSummary = reason
            }
        }
    }

    fun complete(
        finalOutput: String? = null,
        citations: List<GroundingCitation> = emptyList(),
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            this.outcome = if (degraded) AiTraceOutcome.DEGRADED_SUCCESS else AiTraceOutcome.SUCCESS
            this.finalOutput = finalOutput
            this.citations = citations
        }
    }

    fun completeDegraded(
        finalOutput: String? = null,
        citations: List<GroundingCitation> = emptyList(),
        failureCategory: AiTraceFailureCategory? = null,
        errorSummary: String? = null,
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            this.degraded = true
            this.outcome = AiTraceOutcome.DEGRADED_SUCCESS
            this.finalOutput = finalOutput
            this.citations = citations
            if (failureCategory != null) {
                this.failureCategory = failureCategory
            }
            if (!errorSummary.isNullOrBlank()) {
                this.errorSummary = errorSummary
            }
        }
    }

    fun skip(
        reason: String? = null,
        failureCategory: AiTraceFailureCategory? = null,
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            this.outcome = AiTraceOutcome.SKIPPED
            if (failureCategory != null) {
                this.failureCategory = failureCategory
            }
            if (!reason.isNullOrBlank()) {
                this.errorSummary = reason
            }
        }
    }

    fun fail(
        failureCategory: AiTraceFailureCategory,
        errorSummary: String? = null,
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            this.outcome = AiTraceOutcome.FAILED
            this.failureCategory = failureCategory
            if (!errorSummary.isNullOrBlank()) {
                this.errorSummary = errorSummary
            }
        }
    }

    fun finish() {
        if (!flushed.compareAndSet(false, true)) return
        if (!isEnabled) return

        val snapshot: AiTraceRecord
        val secretsSnapshot: Set<String>
        synchronized(lock) {
            val resolvedOutcome =
                outcome ?: if (degraded) {
                    AiTraceOutcome.DEGRADED_SUCCESS
                } else {
                    AiTraceOutcome.FAILED
                }
            val resolvedFailureCategory =
                if (outcome == null && resolvedOutcome == AiTraceOutcome.FAILED && failureCategory == null) {
                    AiTraceFailureCategory.UNEXPECTED_ERROR
                } else {
                    failureCategory
                }
            val totalLatencyMs = elapsedMillisFromNanos(startNanoTime)

            snapshot =
                AiTraceRecord(
                    traceId = traceId,
                    timestamp = startedInstant.toString(),
                    pipeline = pipeline,
                    trigger = trigger,
                    discord = discord,
                    outcome = resolvedOutcome,
                    degraded = degraded,
                    failureCategory = resolvedFailureCategory,
                    fallbackPath = fallbackPath.toList(),
                    totalLatencyMs = totalLatencyMs,
                    attemptCount = attempts.size,
                    finalOutput = finalOutput,
                    citations = citations.toList(),
                    errorSummary = errorSummary,
                    attempts = attempts.toList(),
                )
            secretsSnapshot = extraSecrets.toSet()
        }

        recorder.record(snapshot, secretsSnapshot)

        if (emitCorrelationLog) {
            runCatching {
                JsonLogger.log(
                    layer = "AI_TRACE",
                    component = pipeline.name,
                    operation = "traceRecorded",
                    data =
                        mapOf(
                            "traceId" to snapshot.traceId,
                            "pipeline" to snapshot.pipeline.name,
                            "trigger" to snapshot.trigger.name,
                            "outcome" to snapshot.outcome.name,
                            "degraded" to snapshot.degraded,
                            "attemptCount" to snapshot.attemptCount,
                            "totalLatencyMs" to snapshot.totalLatencyMs,
                        ),
                    status = snapshot.outcome.name,
                )
            }.onFailure { err ->
                logger.warn("Failed to emit AI_TRACE correlation log for {}: {}", snapshot.traceId, err.message)
            }
        }
    }

    override fun close() {
        finish()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AiTraceSession::class.java)
    }
}

class AiTraceContextElement(
    val session: AiTraceSession,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiTraceContextElement>
}

suspend fun currentAiTraceSession(): AiTraceSession? = coroutineContext[AiTraceContextElement]?.session

suspend fun <T> withAiTraceSession(
    session: AiTraceSession,
    block: suspend () -> T,
): T = withContext(AiTraceContextElement(session)) { block() }

fun elapsedMillisFromNanos(startNanoTime: Long): Long = ((System.nanoTime() - startNanoTime) / 1_000_000L).coerceAtLeast(0L)

suspend fun <T> AiTraceRecorder.withActiveOrStandaloneSession(
    pipeline: AiTracePipeline,
    trigger: AiTraceTrigger,
    discord: AiTraceDiscordContext,
    secret: String? = null,
    onStandaloneException: ((AiTraceSession, Exception) -> Unit)? = null,
    block: suspend (session: AiTraceSession?, isStandalone: Boolean) -> T,
): T {
    val existingSession = currentAiTraceSession()
    if (existingSession != null) {
        existingSession.registerSecret(secret)
        return block(existingSession, false)
    }
    if (!isEnabled()) {
        return block(null, false)
    }
    val standaloneSession = startSession(pipeline = pipeline, trigger = trigger, discord = discord)
    standaloneSession.registerSecret(secret)
    return try {
        withAiTraceSession(standaloneSession) {
            block(standaloneSession, true)
        }
    } catch (e: Exception) {
        if (onStandaloneException != null) {
            onStandaloneException(standaloneSession, e)
        } else {
            standaloneSession.fail(
                failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR,
                errorSummary = e.message ?: e::class.simpleName ?: "Unexpected error",
            )
        }
        throw e
    } finally {
        standaloneSession.finish()
    }
}

/**
 * Bounded, fail-open JSONL [AiTraceRecorder] writing daily `ai-YYYY-MM-DD.jsonl` files
 * under [traceDir] with automatic age and total-size retention cleanup.
 */
class JsonlAiTraceRecorder internal constructor(
    private val traceDir: Path,
    private val maxDays: Int = DEFAULT_MAX_DAYS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val redactor: AiTraceRedactor = AiTraceRedactor(),
    private val clock: Clock = Clock.systemUTC(),
    private val emitCorrelationLog: Boolean = true,
    private val beforeWriteHook: (() -> Unit)? = null,
) : AiTraceRecorder {
    constructor(
        traceDir: Path,
        maxDays: Int = DEFAULT_MAX_DAYS,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
        redactor: AiTraceRedactor = AiTraceRedactor(),
        clock: Clock = Clock.systemUTC(),
        emitCorrelationLog: Boolean = true,
    ) : this(
        traceDir = traceDir,
        maxDays = maxDays,
        maxTotalBytes = maxTotalBytes,
        queueCapacity = queueCapacity,
        redactor = redactor,
        clock = clock,
        emitCorrelationLog = emitCorrelationLog,
        beforeWriteHook = null,
    )

    private val logger = LoggerFactory.getLogger(JsonlAiTraceRecorder::class.java)
    private val closed = AtomicBoolean(false)
    private val channel = Channel<String>(capacity = queueCapacity)

    private val writerExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "luna-ai-trace-writer").apply { isDaemon = true }
        }
    private val writerDispatcher = writerExecutor.asCoroutineDispatcher()
    private val writerScope = CoroutineScope(SupervisorJob() + writerDispatcher)
    private val writerJob: Job =
        writerScope.launch {
            runWriterLoop()
        }

    override fun isEnabled(): Boolean = !closed.get()

    override fun startSession(
        pipeline: AiTracePipeline,
        trigger: AiTraceTrigger,
        discord: AiTraceDiscordContext,
    ): AiTraceSession =
        AiTraceSession(
            recorder = this,
            pipeline = pipeline,
            trigger = trigger,
            discord = discord,
            isEnabled = isEnabled(),
            emitCorrelationLog = emitCorrelationLog,
            clock = clock,
        )

    override fun record(trace: AiTraceRecord) {
        record(trace, emptySet())
    }

    override fun record(
        trace: AiTraceRecord,
        extraSecrets: Set<String>,
    ) {
        if (closed.get()) return
        try {
            val effectiveRedactor = redactor.withExtraSecrets(extraSecrets)
            val sanitized = effectiveRedactor.redactTrace(trace)
            val jsonLine = AiTraceJson.encodeToString(AiTraceRecord.serializer(), sanitized)
            val result = channel.trySend(jsonLine)
            if (!result.isSuccess) {
                emitWarn(
                    operation = "enqueueTrace",
                    message = "AI Trace queue full or closed (capacity=$queueCapacity); dropping trace ${trace.traceId}",
                    data = mapOf("traceId" to trace.traceId, "queueCapacity" to queueCapacity),
                )
            }
        } catch (e: Exception) {
            emitWarn(
                operation = "serializeTrace",
                message = "Failed to serialize AI Trace ${trace.traceId}; dropping trace: ${e.message}",
                data = mapOf("traceId" to trace.traceId),
            )
        }
    }

    override suspend fun flushAndClose(timeoutMs: Long) {
        if (!closed.compareAndSet(false, true)) return
        channel.close()
        withContext(Dispatchers.IO) {
            val drained =
                withTimeoutOrNull(timeoutMs) {
                    writerJob.join()
                    true
                } ?: false
            if (!drained) {
                emitWarn(
                    operation = "flushAndClose",
                    message = "Timed out waiting ${timeoutMs}ms for AI Trace writer to drain",
                    data = mapOf("timeoutMs" to timeoutMs),
                )
                writerScope.cancel()
            }
            writerExecutor.shutdown()
            if (!drained) {
                writerExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun runWriterLoop() {
        var lastWrittenDate: LocalDate? = null
        var bytesSincePrune = 0L
        val pruneByteThreshold = (maxTotalBytes / 4L).coerceIn(1L, 50L * 1024L * 1024L)

        if (Files.exists(traceDir) && Files.isDirectory(traceDir)) {
            runCatching {
                pruneRetention(LocalDate.now(clock), currentTargetFile = null)
            }.onFailure { err ->
                emitWarn(
                    operation = "startupPrune",
                    message = "Failed initial AI Trace retention cleanup in $traceDir: ${err.message}",
                    data = mapOf("traceDir" to traceDir.toString()),
                )
            }
        }

        for (line in channelBlockingIterator()) {
            try {
                beforeWriteHook?.invoke()
                val today = LocalDate.now(clock)
                val targetFile = traceDir.resolve(formatFileName(today))
                val rolledOver = lastWrittenDate != null && lastWrittenDate != today

                Files.createDirectories(traceDir)
                val payload = line + "\n"
                Files.writeString(
                    targetFile,
                    payload,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                lastWrittenDate = today
                bytesSincePrune += payload.toByteArray(StandardCharsets.UTF_8).size

                if (rolledOver || bytesSincePrune >= pruneByteThreshold) {
                    pruneRetention(today, currentTargetFile = targetFile)
                    bytesSincePrune = 0L
                }
            } catch (e: Exception) {
                emitWarn(
                    operation = "writeTrace",
                    message = "Failed to write AI Trace to $traceDir (fail-open drop): ${e.message}",
                    data = mapOf("traceDir" to traceDir.toString()),
                )
            }
        }
    }

    private fun channelBlockingIterator(): Iterator<String> =
        iterator {
            while (true) {
                val next = runBlocking { channel.receiveCatching() }
                val value = next.getOrNull() ?: break
                yield(value)
            }
        }

    private fun pruneRetention(
        today: LocalDate,
        currentTargetFile: Path?,
    ) {
        if (!Files.exists(traceDir) || !Files.isDirectory(traceDir)) return

        val entries = mutableListOf<TraceFileEntry>()
        Files.list(traceDir).use { stream ->
            stream.forEach { path ->
                if (!Files.isRegularFile(path)) return@forEach
                val match = TRACE_FILE_REGEX.matchEntire(path.fileName.toString()) ?: return@forEach
                val parsedDate = runCatching { LocalDate.parse(match.groupValues[1], DATE_FORMATTER) }.getOrNull() ?: return@forEach
                entries.add(TraceFileEntry(path = path, date = parsedDate))
            }
        }

        val remaining = mutableListOf<TraceFileEntry>()
        for (entry in entries) {
            val ageDays = ChronoUnit.DAYS.between(entry.date, today)
            if (maxDays >= 0 && ageDays > maxDays.toLong() && entry.path != currentTargetFile) {
                Files.deleteIfExists(entry.path)
            } else {
                remaining.add(entry)
            }
        }

        remaining.sortWith(compareBy<TraceFileEntry> { it.date }.thenBy { it.path.fileName.toString() })

        var totalBytes = remaining.sumOf { runCatching { Files.size(it.path) }.getOrDefault(0L) }
        for (entry in remaining) {
            if (totalBytes <= maxTotalBytes) break
            if (entry.path == currentTargetFile) continue
            val size = runCatching { Files.size(entry.path) }.getOrDefault(0L)
            if (Files.deleteIfExists(entry.path)) {
                totalBytes = (totalBytes - size).coerceAtLeast(0L)
            }
        }
    }

    private fun emitWarn(
        operation: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        runCatching {
            JsonLogger.warn(
                layer = "AI_TRACE",
                component = "JsonlAiTraceRecorder",
                operation = operation,
                data = data,
                message = message,
            )
        }.onFailure {
            logger.warn(message)
        }
    }

    private data class TraceFileEntry(
        val path: Path,
        val date: LocalDate,
    )

    companion object {
        const val DEFAULT_MAX_DAYS = 14
        const val DEFAULT_MAX_TOTAL_MB = 200
        const val DEFAULT_MAX_TOTAL_BYTES = DEFAULT_MAX_TOTAL_MB * 1024L * 1024L
        const val DEFAULT_QUEUE_CAPACITY = 256

        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val TRACE_FILE_REGEX = Regex("""^ai-(\d{4}-\d{2}-\d{2})\.jsonl$""")

        fun formatFileName(date: LocalDate): String = "ai-${DATE_FORMATTER.format(date)}.jsonl"

        fun fromConfig(
            traceConfig: AiTraceConfig,
            fullConfig: Map<String, Any?> = emptyMap(),
            clock: Clock = Clock.systemUTC(),
            emitCorrelationLog: Boolean = true,
        ): AiTraceRecorder {
            if (!traceConfig.enabled) return NoOpAiTraceRecorder
            return JsonlAiTraceRecorder(
                traceDir = Paths.get(traceConfig.dir),
                maxDays = traceConfig.maxDays,
                maxTotalBytes = traceConfig.maxTotalMb.toLong() * 1024L * 1024L,
                redactor = AiTraceRedactor.fromConfig(fullConfig),
                clock = clock,
                emitCorrelationLog = emitCorrelationLog,
            )
        }
    }
}
