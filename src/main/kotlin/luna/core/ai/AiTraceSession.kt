package luna.core.ai

import luna.core.JsonLogger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
    private var circadianPeriod: String? = null
    private var personaMood: String? = null
    private var effectiveThreshold: Double? = null
    private var reactionThreshold: Double? = null
    private var scheduleBlockTitle: String? = null
    private var scheduleAvailability: String? = null
    private var relationshipTier: String? = null
    private var affinityScore: Int? = null
    private var affinityThresholdOffset: Double? = null

    fun registerSecret(secret: String?) {
        if (!isEnabled || secret.isNullOrBlank()) return
        synchronized(lock) {
            extraSecrets.add(secret)
        }
    }

    fun recordPersonaState(
        circadianPeriod: String?,
        personaMood: String?,
        effectiveThreshold: Double?,
        reactionThreshold: Double? = null,
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            this.circadianPeriod = circadianPeriod
            this.personaMood = personaMood
            this.effectiveThreshold = effectiveThreshold
            if (reactionThreshold != null) {
                this.reactionThreshold = reactionThreshold
            }
        }
    }

    fun recordScheduleState(
        scheduleBlockTitle: String?,
        scheduleAvailability: String?,
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            this.scheduleBlockTitle = scheduleBlockTitle
            this.scheduleAvailability = scheduleAvailability
        }
    }

    fun recordRelationship(
        relationshipTier: String?,
        affinityScore: Int?,
        affinityThresholdOffset: Double?,
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            this.relationshipTier = relationshipTier
            this.affinityScore = affinityScore
            this.affinityThresholdOffset = affinityThresholdOffset
        }
    }

    fun currentOutcome(): AiTraceOutcome? =
        synchronized(lock) {
            outcome
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
            if (!degraded) {
                this.errorSummary = null
                this.failureCategory = null
            }
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
                    circadianPeriod = circadianPeriod,
                    personaMood = personaMood,
                    effectiveThreshold = effectiveThreshold,
                    reactionThreshold = reactionThreshold,
                    scheduleBlockTitle = scheduleBlockTitle,
                    scheduleAvailability = scheduleAvailability,
                    relationshipTier = relationshipTier,
                    affinityScore = affinityScore,
                    affinityThresholdOffset = affinityThresholdOffset,
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
