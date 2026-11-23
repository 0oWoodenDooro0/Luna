package luna.chatter.memory

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

@Serializable
private data class ExtractedFactCandidateDto(
    val scope: String,
    @SerialName("target_user_id")
    val targetUserId: String? = null,
    val content: String,
    @SerialName("expires_at")
    val expiresAt: String? = null,
)

@Serializable
private data class ReconciliationDecisionDto(
    val action: String,
    @SerialName("memory_id")
    val memoryId: Long? = null,
    @SerialName("candidate_index")
    val candidateIndex: Int? = null,
    val content: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
)

/**
 * Asynchronous `Memory Distillation` worker that fetches unprocessed channel messages after the
 * persisted `Distillation Cursor`, extracts pronoun-resolved `Atomic Fact` candidates using
 * `gemini-3.5-flash-lite`, embeds and searches Top-3 existing memories, reconciles mutations
 * (`INSERT`/`UPDATE`/`DELETE`/`NOOP`), and atomically updates `ChatterMemoryStore` and cursor.
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
) {
    private val logger = LoggerFactory.getLogger(MemoryDistiller::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Runs one distillation cycle for [channelId] in [guildId] starting after [cursorMessageId].
     */
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
        val chatMessages = fetched.mapNotNull { noiseFilter.toChatMessageOrNull(it, selfId) }

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

        if (candidates.isEmpty()) {
            memoryStore.upsertCursor(channelId = channelId, guildId = guildId, lastSummarizedMessageId = newestMessageId)
            session.complete(finalOutput = "mutations=0, cursor=$newestMessageId")
            return DistillOutcome.Completed(mutationsCount = 0, newCursorMessageId = newestMessageId)
        }

        val candidateEmbeddings = embeddingClient.embedDocuments(candidates.map { it.content })
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

        val mutations = buildMutations(guildId, candidates, candidateEmbeddings, decisions)
        val cursorUpdate =
            ChatterMemoryCursor(
                channelId = channelId,
                guildId = guildId,
                lastSummarizedMessageId = newestMessageId,
                updatedAt = clock.millis(),
            )
        memoryStore.applyReconciliation(mutations = mutations, cursorUpdate = cursorUpdate)

        session.complete(finalOutput = "mutations=${mutations.size}, cursor=$newestMessageId")
        return DistillOutcome.Completed(mutationsCount = mutations.size, newCursorMessageId = newestMessageId)
    }

    private suspend fun extractCandidates(
        transcript: String,
        session: AiTraceSession,
    ): List<FactCandidate>? {
        val nowInstant = clock.instant()
        val nowMs = nowInstant.toEpochMilli()
        val zone = clock.zone
        val zonedNow = nowInstant.atZone(zone)
        val currentDateIso = zonedNow.toLocalDate().toString()
        val dayOfWeek = zonedNow.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

        val request =
            GeminiInteractionRequest(
                model = config.cleanDistillModel,
                systemInstruction = EXTRACTION_SYSTEM_INSTRUCTION,
                input =
                    listOf(
                        GeminiInteractionInput.text(
                            "Current date: $currentDateIso ($dayOfWeek)\nConversation transcript:\n$transcript",
                        ),
                    ),
                generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = MAX_DISTILL_OUTPUT_TOKENS),
                background = false,
            )
        val result =
            interactionClient.interact(
                baseUrl = baseUrl,
                apiKey = apiKey,
                request = request,
                traceContext =
                    InteractionTraceContext(
                        stage = AiAttemptStage.MEMORY_DISTILL_EXTRACT,
                        pipeline = AiTracePipeline.CHATTER_MEMORY_DISTILL,
                        trigger = AiTraceTrigger.AMBIENT,
                        transcriptLength = transcript.length,
                        stripOutputEmojis = false,
                    ),
            )
        val responseText =
            when (result) {
                is InteractionExecutionResult.Success -> {
                    result.text
                }

                is InteractionExecutionResult.FunctionCall -> {
                    session.fail(
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                        errorSummary = "Unexpected function call during extraction",
                    )
                    return null
                }

                is InteractionExecutionResult.Error -> {
                    session.fail(
                        failureCategory = result.failureCategory,
                        errorSummary = result.message,
                    )
                    return null
                }
            }

        val dtos =
            runCatching {
                json.decodeFromString<List<ExtractedFactCandidateDto>>(MemoryCandidateProcessor.stripJsonFences(responseText))
            }.getOrElse { ex ->
                logger.warn("Failed to parse memory extraction JSON: {}", ex.message)
                session.fail(
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    errorSummary = "Extraction JSON parse error: ${ex.message}",
                )
                return null
            }

        return dtos.mapNotNull { dto ->
            val content = dto.content.trim()
            if (content.isBlank()) return@mapNotNull null
            val scope =
                when (dto.scope.trim().lowercase()) {
                    "user" -> MemoryScope.USER
                    "guild" -> MemoryScope.GUILD
                    else -> return@mapNotNull null
                }
            val targetUserId = dto.targetUserId?.trim()?.takeIf { it.isNotEmpty() }
            if (scope == MemoryScope.USER && targetUserId == null) return@mapNotNull null
            FactCandidate(
                scope = scope,
                targetUserId = if (scope == MemoryScope.USER) targetUserId else null,
                content = content,
                expiresAt = MemoryCandidateProcessor.parseAndClampExpiresAt(dto.expiresAt, nowMs, zone),
            )
        }
    }

    private suspend fun reconcileCandidates(
        candidates: List<FactCandidate>,
        hitsPerCandidate: List<List<ChatterMemorySearchHit>>,
        session: AiTraceSession,
    ): List<ReconciliationDecisionDto>? {
        val prompt = buildReconciliationPrompt(candidates, hitsPerCandidate)
        val request =
            GeminiInteractionRequest(
                model = config.cleanDistillModel,
                systemInstruction = RECONCILIATION_SYSTEM_INSTRUCTION,
                input = listOf(GeminiInteractionInput.text(prompt)),
                generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = MAX_DISTILL_OUTPUT_TOKENS),
                background = false,
            )
        val result =
            interactionClient.interact(
                baseUrl = baseUrl,
                apiKey = apiKey,
                request = request,
                traceContext =
                    InteractionTraceContext(
                        stage = AiAttemptStage.MEMORY_DISTILL_RECONCILE,
                        pipeline = AiTracePipeline.CHATTER_MEMORY_DISTILL,
                        trigger = AiTraceTrigger.AMBIENT,
                        transcriptLength = prompt.length,
                        stripOutputEmojis = false,
                    ),
            )
        val responseText =
            when (result) {
                is InteractionExecutionResult.Success -> {
                    result.text
                }

                is InteractionExecutionResult.FunctionCall -> {
                    session.fail(
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                        errorSummary = "Unexpected function call during reconciliation",
                    )
                    return null
                }

                is InteractionExecutionResult.Error -> {
                    session.fail(
                        failureCategory = result.failureCategory,
                        errorSummary = result.message,
                    )
                    return null
                }
            }

        return runCatching {
            json.decodeFromString<List<ReconciliationDecisionDto>>(MemoryCandidateProcessor.stripJsonFences(responseText))
        }.getOrElse { ex ->
            logger.warn("Failed to parse memory reconciliation JSON: {}", ex.message)
            session.fail(
                failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                errorSummary = "Reconciliation JSON parse error: ${ex.message}",
            )
            null
        }
    }

    private suspend fun buildMutations(
        guildId: String,
        candidates: List<FactCandidate>,
        candidateEmbeddings: List<FloatArray>,
        decisions: List<ReconciliationDecisionDto>,
    ): List<MemoryMutation> {
        // Collect any modified content strings that need fresh embeddings
        val textsToEmbed = mutableListOf<String>()
        val embedIndexByDecisionIdx = mutableMapOf<Int, Int>()

        decisions.forEachIndexed { idx, decision ->
            val action = decision.action.trim().uppercase()
            if (action == "INSERT" || action == "UPDATE") {
                val candidate = decision.candidateIndex?.let { candidates.getOrNull(it) }
                val finalContent = decision.content?.trim()?.takeIf { it.isNotBlank() } ?: candidate?.content
                if (finalContent != null && (candidate == null || finalContent != candidate.content)) {
                    embedIndexByDecisionIdx[idx] = textsToEmbed.size
                    textsToEmbed.add(finalContent)
                }
            }
        }

        val reembeddedVectors =
            if (textsToEmbed.isNotEmpty()) {
                embeddingClient.embedDocuments(textsToEmbed)
            } else {
                emptyList()
            }

        val nowMs = clock.millis()
        val zone = clock.zone
        val mutations = mutableListOf<MemoryMutation>()
        decisions.forEachIndexed { idx, decision ->
            val action = decision.action.trim().uppercase()
            val candidate = decision.candidateIndex?.let { candidates.getOrNull(it) }
            val candidateEmbedding = decision.candidateIndex?.let { candidateEmbeddings.getOrNull(it) }

            when (action) {
                "INSERT" -> {
                    if (candidate != null) {
                        val finalContent = decision.content?.trim()?.takeIf { it.isNotBlank() } ?: candidate.content
                        val embedding = embedIndexByDecisionIdx[idx]?.let { reembeddedVectors[it] } ?: candidateEmbedding
                        val finalExpiresAt =
                            if (decision.expiresAt != null) {
                                MemoryCandidateProcessor.parseAndClampExpiresAt(decision.expiresAt, nowMs, zone)
                            } else {
                                candidate.expiresAt
                            }
                        mutations.add(
                            MemoryMutation.Insert(
                                guildId = guildId,
                                scope = candidate.scope,
                                targetUserId = candidate.targetUserId,
                                content = finalContent,
                                embedding = embedding,
                                expiresAt = finalExpiresAt,
                            ),
                        )
                    }
                }

                "UPDATE" -> {
                    val memoryId = decision.memoryId
                    val finalContent = decision.content?.trim()?.takeIf { it.isNotBlank() } ?: candidate?.content
                    if (memoryId != null && finalContent != null) {
                        val embedding = embedIndexByDecisionIdx[idx]?.let { reembeddedVectors[it] } ?: candidateEmbedding
                        val finalExpiresAt =
                            if (decision.expiresAt != null) {
                                MemoryCandidateProcessor.parseAndClampExpiresAt(decision.expiresAt, nowMs, zone)
                            } else {
                                candidate?.expiresAt
                            }
                        mutations.add(
                            MemoryMutation.Update(
                                id = memoryId,
                                guildId = guildId,
                                content = finalContent,
                                embedding = embedding,
                                expiresAt = finalExpiresAt,
                            ),
                        )
                    }
                }

                "DELETE" -> {
                    val memoryId = decision.memoryId
                    if (memoryId != null) {
                        mutations.add(
                            MemoryMutation.Delete(
                                id = memoryId,
                                guildId = guildId,
                            ),
                        )
                    }
                }

                "NOOP" -> {
                    // Intentionally no-op
                }
            }
        }
        return mutations
    }

    private fun buildReconciliationPrompt(
        candidates: List<FactCandidate>,
        hitsPerCandidate: List<List<ChatterMemorySearchHit>>,
    ): String =
        buildString {
            appendLine("Reconcile each extracted candidate fact against existing similar memories.")
            candidates.forEachIndexed { idx, candidate ->
                appendLine("Candidate [$idx] (scope=${candidate.scope.dbValue}, target_user_id=${candidate.targetUserId}): ${candidate.content}")
                val hits = hitsPerCandidate[idx]
                if (hits.isEmpty()) {
                    appendLine("  Existing similar memories: (none)")
                } else {
                    appendLine("  Existing similar memories:")
                    hits.forEach { hit ->
                        appendLine("    - id=${hit.memory.id}, scope=${hit.memory.scope.dbValue}, content=${hit.memory.content}")
                    }
                }
            }
        }

    companion object {
        private const val FETCH_BUFFER_SIZE = 20
        private const val TOP_K_SIMILAR = 6
        private const val MAX_DISTILL_OUTPUT_TOKENS = 2048
        private const val HTTP_REQUEST_TIMEOUT_MS = 30_000L
        private const val HTTP_CONNECT_TIMEOUT_MS = 10_000L

        fun createDefaultHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            encodeDefaults = false
                        },
                    )
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = HTTP_REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = HTTP_CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = HTTP_REQUEST_TIMEOUT_MS
                }
            }

        private val EXTRACTION_SYSTEM_INSTRUCTION =
            """
            You extract durable, self-contained Atomic Facts from a Discord conversation transcript.
            Rules:
            - Speakers are formatted as `Name (uid:<id>): ...`, `BotName [BOT] (uid:<id>): ...`, or `Luna (uid:<selfId>): ...`.
            - Extract ONLY objective, enduring user preferences, life events, relationships, or shared guild/server facts, plus time-bound upcoming plans/events worth remembering until they pass.
            - NEVER extract Luna's jokes, roleplay, fabrications, or ephemeral small talk.
            - Resolve all pronouns ("I", "my", "he", "she") into explicit names/subjects so each fact is standalone.
            - Use the provided `Current date` to resolve relative time expressions ("tomorrow", "next Friday", "this weekend").
            - Set `expires_at` to `"YYYY-MM-DD"` for time-bound events/plans (e.g., interviews, trips, exams, weekend events) and `null` for durable preferences or standing facts.
            - Output ONLY a valid JSON array of objects:
              [{"scope": "user" | "guild", "target_user_id": "<uid string or null>", "content": "<atomic fact>", "expires_at": "YYYY-MM-DD" | null}]
            - Return `[]` if there are no facts worth remembering.
            """.trimIndent()

        private val RECONCILIATION_SYSTEM_INSTRUCTION =
            """
            You reconcile newly extracted candidate facts against existing stored memories.
            For each candidate_index, decide one action:
            - `INSERT`: new fact not yet stored.
            - `UPDATE`: updates/replaces an existing memory (provide `memory_id`, updated `content`, and optional `expires_at`).
            - `DELETE`: invalidates/contradicts an existing memory (provide `memory_id`).
            - `NOOP`: already accurately captured or redundant.
            Output ONLY a valid JSON array of objects:
            [{"action": "INSERT"|"UPDATE"|"DELETE"|"NOOP", "memory_id": <long or null>, "candidate_index": <int>, "content": "<final fact text or null>", "expires_at": "<YYYY-MM-DD or null>"}]
            """.trimIndent()
    }
}
