package luna.chatter.memory

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import luna.chatter.ChatterNoiseFilter
import luna.chatter.ChatterPromptBuilder
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionGenerationConfig
import luna.core.ai.GeminiInteractionInput
import luna.core.ai.GeminiInteractionRequest
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.InteractionTraceContext
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.withAiTraceSession
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.format.TextStyle
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Outcome of a single channel `Memory Distillation` run.
 */
sealed class DistillOutcome {
    data object NoMessages : DistillOutcome()

    data class NoExtractableContent(
        val newCursorMessageId: String,
    ) : DistillOutcome()

    data class Completed(
        val mutationsCount: Int,
        val newCursorMessageId: String,
    ) : DistillOutcome()

    data class Failed(
        val reason: String,
        val cause: Throwable? = null,
    ) : DistillOutcome()
}

private data class ChannelActivityState(
    val channelId: String,
    val guildId: String,
    var lastActivityAtMs: Long,
    var oldestUnprocessedAtMs: Long? = null,
    var unprocessedHumanCount: Int = 0,
    var inFlight: Boolean = false,
)

private data class EligibleChannelBatch(
    val channelId: String,
    val guildId: String,
    val countAtStart: Int,
)

/**
 * Asynchronous `Memory Distillation` lifecycle and worker for Chatter-enabled guild channels.
 *
 * Maintains a bounded LRU channel activity tracker (`maxTrackedChannels = 1000`), cold-starts
 * new channels at the first observed message without historical backfill, runs eligible
 * distillation batches sequentially on [tick], extracts pronoun-resolved `Atomic Fact` candidates,
 * embeds and searches Top-6 existing memories, reconciles mutations (`INSERT`/`UPDATE`/`DELETE`/`NOOP`),
 * and atomically updates [ChatterMemoryStore] and the persisted `Distillation Cursor`.
 */
class MemoryDistiller(
    private val memoryStore: ChatterMemoryStore,
    private val embeddingClient: GeminiEmbeddingClient,
    private val interactionClient: GeminiInteractionClient,
    private val noiseFilter: ChatterNoiseFilter,
    private val config: ChatterMemoryConfig,
    private val apiKey: String,
    private val baseUrl: String,
    private val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
    private val clock: Clock = Clock.systemUTC(),
    private val messagesAfterFetcher: suspend (channelId: Snowflake, afterMessageId: Snowflake, limit: Int) -> List<Message>,
    private val kordSelfIdProvider: () -> Snowflake? = { null },
    private val allowedChannels: Set<Snowflake> = emptySet(),
    private val chatterEnabled: Boolean = true,
    private val maxTrackedChannels: Int = DEFAULT_MAX_TRACKED_CHANNELS,
) {
    private val logger = LoggerFactory.getLogger(MemoryDistiller::class.java)
    private val lock = Any()

    private val activityTracker: MutableMap<String, ChannelActivityState> =
        object : LinkedHashMap<String, ChannelActivityState>(maxTrackedChannels, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChannelActivityState>?): Boolean = size > maxTrackedChannels
        }

    init {
        require(maxTrackedChannels >= 1) { "maxTrackedChannels must be >= 1" }
    }

    /** Registers a Kord [MessageCreateEvent] observer for Chatter-enabled guild channels. */
    fun registerMessageObserver(
        kord: Kord,
        scope: CoroutineScope,
    ): Job =
        kord.on<MessageCreateEvent>(scope = scope) {
            val guildId =
                message.data.guildId.value
                    ?.toString() ?: return@on
            observeMessage(message = message, guildId = guildId)
        }

    /** Starts the periodic background ticker that drains eligible channels sequentially. */
    fun startTicker(
        scope: CoroutineScope,
        intervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
    ): Job =
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    tick()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    logger.warn("Memory distillation ticker iteration failed: {}", ex.message)
                }
            }
        }

    /** Observes a Discord message for distillation scheduling or cold-start cursor initialization. */
    fun observeMessage(
        message: Message,
        guildId: String?,
    ) {
        if (!chatterEnabled || !config.enabled) return
        if (guildId.isNullOrBlank()) return

        val channelSnowflake = message.channelId
        if (allowedChannels.isNotEmpty() && !allowedChannels.contains(channelSnowflake)) return

        val selfId = kordSelfIdProvider()
        if (!noiseFilter.isContextMessage(message, selfId)) return

        val authorId =
            runCatching { message.author?.id }.getOrNull()
                ?: runCatching { message.data.author.id }.getOrNull()
        val isLunaBot = selfId != null && authorId == selfId
        val isBot = isLunaBot || (runCatching { message.author?.isBot }.getOrNull() == true)
        val isHuman = !isBot && message.author != null

        val channelId = channelSnowflake.toString()
        val messageId = message.id.toString()
        val nowMs = clock.millis()

        val existingCursor =
            runCatching { memoryStore.getCursor(channelId) }
                .onFailure { ex ->
                    logger.warn("Failed to read distillation cursor for channel {}: {}", channelId, ex.message)
                }.getOrElse { return }

        if (existingCursor == null) {
            val coldStarted =
                runCatching {
                    memoryStore.upsertCursor(
                        channelId = channelId,
                        guildId = guildId,
                        lastSummarizedMessageId = messageId,
                    )
                }.onFailure { ex ->
                    logger.warn("Failed to cold-start distillation cursor for channel {}: {}", channelId, ex.message)
                }.isSuccess
            if (!coldStarted) return

            synchronized(lock) {
                activityTracker[channelId] =
                    ChannelActivityState(
                        channelId = channelId,
                        guildId = guildId,
                        lastActivityAtMs = nowMs,
                        oldestUnprocessedAtMs = null,
                        unprocessedHumanCount = 0,
                        inFlight = false,
                    )
            }
            return
        }

        synchronized(lock) {
            val state =
                activityTracker[channelId]
                    ?: ChannelActivityState(
                        channelId = channelId,
                        guildId = guildId,
                        lastActivityAtMs = nowMs,
                    ).also { activityTracker[channelId] = it }

            state.lastActivityAtMs = nowMs
            if (isHuman) {
                state.unprocessedHumanCount += 1
                if (state.oldestUnprocessedAtMs == null) {
                    state.oldestUnprocessedAtMs = nowMs
                }
            }
        }
    }

    /** Evaluates tracked channels and runs `Memory Distillation` sequentially for eligible batches. */
    suspend fun tick() {
        if (!chatterEnabled || !config.enabled) return

        runCatching { memoryStore.purgeExpiredMemories(clock.millis()) }
            .onSuccess { purged ->
                if (purged > 0) {
                    logger.debug("Purged {} expired chatter memories", purged)
                }
            }.onFailure { ex ->
                logger.warn("Failed to purge expired chatter memories: {}", ex.message)
            }

        val eligible = snapshotEligibleChannels()
        for (batch in eligible) {
            val outcome =
                try {
                    val cursor = memoryStore.getCursor(batch.channelId)
                    if (cursor == null) {
                        clearInFlight(batch.channelId)
                        continue
                    }
                    distillChannel(batch.channelId, batch.guildId, cursor.lastSummarizedMessageId)
                } catch (ex: CancellationException) {
                    clearInFlight(batch.channelId)
                    throw ex
                } catch (ex: Exception) {
                    logger.warn(
                        "Memory distillation threw for channel {} (guild {}): {}",
                        batch.channelId,
                        batch.guildId,
                        ex.message,
                    )
                    DistillOutcome.Failed(reason = ex.message ?: "Unexpected failure", cause = ex)
                }

            when (outcome) {
                is DistillOutcome.NoMessages,
                is DistillOutcome.NoExtractableContent,
                is DistillOutcome.Completed,
                -> {
                    markBatchCompleted(batch.channelId, batch.countAtStart)
                }

                is DistillOutcome.Failed -> {
                    // Evict transient failure state so the next eligible tick retries cleanly
                    logger.warn(
                        "Memory distillation failed transiently for channel {} (leaving cursor unchanged): {}",
                        batch.channelId,
                        outcome.reason,
                    )
                    clearInFlight(batch.channelId)
                }
            }
        }
    }

    fun trackedChannelCount(): Int = synchronized(lock) { activityTracker.size }

    /** Runs one distillation cycle for [channelId] in [guildId] starting after [cursorMessageId]. */
    suspend fun distillChannel(
        channelId: String,
        guildId: String,
        cursorMessageId: String,
    ): DistillOutcome {
        val session =
            traceRecorder.startSession(
                pipeline = AiTracePipeline.CHATTER_MEMORY_DISTILL,
                trigger = AiTraceTrigger.AMBIENT,
                discord =
                    AiTraceDiscordContext(
                        guildId = guildId,
                        channelId = channelId,
                        messageId = cursorMessageId,
                    ),
            )

        return session.use {
            try {
                withAiTraceSession(session) {
                    executeDistillation(channelId, guildId, cursorMessageId, session)
                }
            } catch (ex: CancellationException) {
                session.fail(
                    failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR,
                    errorSummary = "Distillation cancelled",
                )
                throw ex
            } catch (ex: Exception) {
                logger.warn("Memory distillation failed for channel {} (guild {}): {}", channelId, guildId, ex.message)
                session.fail(
                    failureCategory = AiTraceFailureCategory.UNEXPECTED_ERROR,
                    errorSummary = ex.message ?: ex::class.simpleName,
                )
                DistillOutcome.Failed(reason = ex.message ?: "Unexpected distillation failure", cause = ex)
            }
        }
    }

    private suspend fun executeDistillation(
        channelId: String,
        guildId: String,
        cursorMessageId: String,
        session: AiTraceSession,
    ): DistillOutcome {
        val channelSnowflake = Snowflake(channelId)
        val cursorSnowflake = Snowflake(cursorMessageId)
        val fetchLimit = config.maxBatchMessages + FETCH_BUFFER_SIZE

        val fetched =
            messagesAfterFetcher(channelSnowflake, cursorSnowflake, fetchLimit)
                .filter { it.id > cursorSnowflake }
                .sortedBy { it.id }

        if (fetched.isEmpty()) {
            session.skip(reason = "no_messages_after_cursor")
            return DistillOutcome.NoMessages
        }

        val newestMessageId = fetched.last().id.toString()
        val selfId = kordSelfIdProvider()
        val chatMessages = noiseFilter.filterAndResolveHistory(fetched, selfId)

        if (chatMessages.isEmpty()) {
            memoryStore.upsertCursor(channelId = channelId, guildId = guildId, lastSummarizedMessageId = newestMessageId)
            session.skip(reason = "no_extractable_messages; cursor=$newestMessageId")
            return DistillOutcome.NoExtractableContent(newestMessageId)
        }

        val transcript = ChatterPromptBuilder.formatTranscript(chatMessages)
        val rawCandidates =
            extractCandidates(transcript, session)
                ?: return DistillOutcome.Failed("Failed to extract memory candidates")
        val candidates = MemoryCandidateProcessor.deduplicateBatchCandidates(rawCandidates)

        val understandingCtx =
            UnderstandingPatchContext(
                memoryStore = memoryStore,
                interactionClient = interactionClient,
                model = config.cleanDistillModel,
                baseUrl = baseUrl,
                apiKey = apiKey,
                maxOutputTokens = MAX_DISTILL_OUTPUT_TOKENS,
                guildId = guildId,
                chatMessages = chatMessages,
                transcript = transcript,
                selfId = selfId,
                session = session,
            )

        val mutationsCount =
            if (candidates.isEmpty()) {
                memoryStore.upsertCursor(channelId = channelId, guildId = guildId, lastSummarizedMessageId = newestMessageId)
                0
            } else {
                val candidateEmbeddings =
                    embeddingClient.embedDocuments(
                        candidates.map { MemoryDistillationPrompt.buildEmbeddingText(it.content, it.keywords) },
                    )
                val hitsPerCandidate =
                    candidates.mapIndexed { idx, candidate ->
                        val searchScope = if (candidate.scope == MemoryScope.USER) SearchScope.USER else SearchScope.GUILD
                        memoryStore.search(
                            guildId = guildId,
                            queryText = candidate.content,
                            queryEmbedding = candidateEmbeddings[idx],
                            scope = searchScope,
                            targetUserId = candidate.targetUserId,
                            limit = TOP_K_SIMILAR,
                            recencyExcludeMinutes = 0,
                            scoreCutoff = null,
                            updateLastAccessed = false,
                        )
                    }

                val decisions =
                    reconcileCandidates(candidates, hitsPerCandidate, session)
                        ?: return DistillOutcome.Failed("Failed to reconcile memory candidates")

                val mutations =
                    MemoryDistillationPrompt.buildMutations(
                        guildId = guildId,
                        candidates = candidates,
                        candidateEmbeddings = candidateEmbeddings,
                        decisions = decisions,
                        embeddingClient = embeddingClient,
                        clock = clock,
                    )
                val cursorUpdate = ChatterMemoryCursor(channelId, guildId, newestMessageId, clock.millis())
                memoryStore.applyReconciliation(mutations = mutations, cursorUpdate = cursorUpdate)
                mutations.size
            }

        UnderstandingPatchPrompt.executeFailOpen(understandingCtx)
        session.complete(finalOutput = "mutations=$mutationsCount, cursor=$newestMessageId")
        return DistillOutcome.Completed(mutationsCount = mutationsCount, newCursorMessageId = newestMessageId)
    }

    private suspend fun extractCandidates(
        transcript: String,
        session: AiTraceSession,
    ): List<FactCandidate>? {
        val nowInstant = clock.instant()
        val nowMs = nowInstant.toEpochMilli()
        val zone = clock.zone
        val zonedNow = nowInstant.atZone(zone)
        val input =
            MemoryDistillationPrompt.buildExtractionInput(
                currentDateIso = zonedNow.toLocalDate().toString(),
                dayOfWeek = zonedNow.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                zoneId = zonedNow.zone.id,
                transcript = transcript,
            )

        val request =
            GeminiInteractionRequest(
                model = config.cleanDistillModel,
                systemInstruction = MemoryDistillationPrompt.EXTRACTION_SYSTEM_INSTRUCTION,
                input = listOf(GeminiInteractionInput.text(input)),
                generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = MAX_DISTILL_OUTPUT_TOKENS),
                background = false,
            )
        val responseText =
            executeInteraction(
                request = request,
                stage = AiAttemptStage.MEMORY_DISTILL_EXTRACT,
                transcriptLength = transcript.length,
                session = session,
            ) ?: return null

        return MemoryDistillationPrompt
            .parseExtractionResponse(responseText, nowMs, zone)
            .getOrElse { ex ->
                logger.warn("Failed to parse memory extraction JSON: {}", ex.message)
                session.fail(
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    errorSummary = "Extraction JSON parse error: ${ex.message}",
                )
                null
            }
    }

    private suspend fun reconcileCandidates(
        candidates: List<FactCandidate>,
        hitsPerCandidate: List<List<ChatterMemorySearchHit>>,
        session: AiTraceSession,
    ): List<ReconciliationDecisionDto>? {
        val prompt = MemoryDistillationPrompt.buildReconciliationPrompt(candidates, hitsPerCandidate)
        val request =
            GeminiInteractionRequest(
                model = config.cleanDistillModel,
                systemInstruction = MemoryDistillationPrompt.RECONCILIATION_SYSTEM_INSTRUCTION,
                input = listOf(GeminiInteractionInput.text(prompt)),
                generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = MAX_DISTILL_OUTPUT_TOKENS),
                background = false,
            )
        val responseText =
            executeInteraction(
                request = request,
                stage = AiAttemptStage.MEMORY_DISTILL_RECONCILE,
                transcriptLength = prompt.length,
                session = session,
            ) ?: return null

        return MemoryDistillationPrompt
            .parseReconciliationResponse(responseText)
            .getOrElse { ex ->
                logger.warn("Failed to parse memory reconciliation JSON: {}", ex.message)
                session.fail(
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    errorSummary = "Reconciliation JSON parse error: ${ex.message}",
                )
                null
            }
    }

    private suspend fun executeInteraction(
        request: GeminiInteractionRequest,
        stage: AiAttemptStage,
        transcriptLength: Int,
        session: AiTraceSession,
    ): String? {
        val result =
            interactionClient.interact(
                baseUrl = baseUrl,
                apiKey = apiKey,
                request = request,
                traceContext =
                    InteractionTraceContext(
                        stage = stage,
                        pipeline = AiTracePipeline.CHATTER_MEMORY_DISTILL,
                        trigger = AiTraceTrigger.AMBIENT,
                        transcriptLength = transcriptLength,
                        stripOutputEmojis = false,
                    ),
            )
        return when (result) {
            is InteractionExecutionResult.Success -> {
                result.text
            }

            is InteractionExecutionResult.FunctionCall -> {
                session.fail(
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    errorSummary = "Unexpected function call during ${stage.name.lowercase()}",
                )
                null
            }

            is InteractionExecutionResult.Error -> {
                session.fail(
                    failureCategory = result.failureCategory,
                    errorSummary = result.message,
                )
                null
            }
        }
    }

    private fun snapshotEligibleChannels(): List<EligibleChannelBatch> {
        val nowMs = clock.millis()
        val quietMs = config.quietMinutes * MILLIS_PER_MINUTE
        val maxAgeMs = config.maxBatchAgeMinutes * MILLIS_PER_MINUTE

        return synchronized(lock) {
            val batches = mutableListOf<EligibleChannelBatch>()
            for (state in activityTracker.values) {
                if (state.inFlight) continue
                val count = state.unprocessedHumanCount
                val isQuietTrigger = count >= config.minMessages && (nowMs - state.lastActivityAtMs) >= quietMs
                val isMaxCountTrigger = count >= config.maxBatchMessages
                val oldestAt = state.oldestUnprocessedAtMs
                val isMaxAgeTrigger =
                    count >= config.minMessages &&
                        oldestAt != null &&
                        (nowMs - oldestAt) >= maxAgeMs

                if (isQuietTrigger || isMaxCountTrigger || isMaxAgeTrigger) {
                    state.inFlight = true
                    batches.add(
                        EligibleChannelBatch(
                            channelId = state.channelId,
                            guildId = state.guildId,
                            countAtStart = count,
                        ),
                    )
                }
            }
            batches
        }
    }

    private fun markBatchCompleted(
        channelId: String,
        processedCount: Int,
    ) {
        synchronized(lock) {
            val state = activityTracker[channelId] ?: return
            state.inFlight = false
            val remaining = (state.unprocessedHumanCount - processedCount).coerceAtLeast(0)
            state.unprocessedHumanCount = remaining
            if (remaining == 0) {
                state.oldestUnprocessedAtMs = null
            }
        }
    }

    private fun clearInFlight(channelId: String) {
        synchronized(lock) {
            activityTracker[channelId]?.inFlight = false
        }
    }

    companion object {
        const val DEFAULT_MAX_TRACKED_CHANNELS = 1000
        const val DEFAULT_TICK_INTERVAL_MS = 30_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val FETCH_BUFFER_SIZE = 20
        private const val TOP_K_SIMILAR = 6
        private const val MAX_DISTILL_OUTPUT_TOKENS = 2048
    }
}
