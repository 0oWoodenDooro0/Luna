package luna.chatter.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import luna.core.ai.GeminiEmbeddingClient
import java.time.Clock
import java.time.ZoneId

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
internal data class ReconciliationDecisionDto(
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
 * Stateless prompt builders and JSON response parsers for `Memory Distillation`.
 */
internal object MemoryDistillationPrompt {
    private val json = Json { ignoreUnknownKeys = true }

    val EXTRACTION_SYSTEM_INSTRUCTION: String =
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

    val RECONCILIATION_SYSTEM_INSTRUCTION: String =
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

    fun buildExtractionInput(
        currentDateIso: String,
        dayOfWeek: String,
        zoneId: String,
        transcript: String,
    ): String = "Current date: $currentDateIso ($dayOfWeek, $zoneId)\nConversation transcript:\n$transcript"

    fun buildReconciliationPrompt(
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

    fun parseExtractionResponse(
        responseText: String,
        nowMs: Long,
        zone: ZoneId,
    ): Result<List<FactCandidate>> =
        runCatching {
            val dtos =
                json.decodeFromString<List<ExtractedFactCandidateDto>>(
                    MemoryCandidateProcessor.stripJsonFences(responseText),
                )
            dtos.mapNotNull { dto ->
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

    fun parseReconciliationResponse(responseText: String): Result<List<ReconciliationDecisionDto>> =
        runCatching {
            json.decodeFromString<List<ReconciliationDecisionDto>>(
                MemoryCandidateProcessor.stripJsonFences(responseText),
            )
        }

    suspend fun buildMutations(
        guildId: String,
        candidates: List<FactCandidate>,
        candidateEmbeddings: List<FloatArray>,
        decisions: List<ReconciliationDecisionDto>,
        embeddingClient: GeminiEmbeddingClient,
        clock: Clock,
    ): List<MemoryMutation> {
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
            if (textsToEmbed.isNotEmpty()) embeddingClient.embedDocuments(textsToEmbed) else emptyList()

        val nowMs = clock.millis()
        val zone = clock.zone
        val mutations = mutableListOf<MemoryMutation>()
        decisions.forEachIndexed { idx, decision ->
            val action = decision.action.trim().uppercase()
            val candidate = decision.candidateIndex?.let { candidates.getOrNull(it) }
            val candidateEmbedding = decision.candidateIndex?.let { candidateEmbeddings.getOrNull(it) }
            val resolvedExpiresAt =
                if (decision.expiresAt != null) {
                    MemoryCandidateProcessor.parseAndClampExpiresAt(decision.expiresAt, nowMs, zone)
                } else {
                    candidate?.expiresAt
                }

            when (action) {
                "INSERT" -> {
                    if (candidate != null) {
                        val finalContent = decision.content?.trim()?.takeIf { it.isNotBlank() } ?: candidate.content
                        val embedding = embedIndexByDecisionIdx[idx]?.let { reembeddedVectors[it] } ?: candidateEmbedding
                        mutations.add(
                            MemoryMutation.Insert(
                                guildId = guildId,
                                scope = candidate.scope,
                                targetUserId = candidate.targetUserId,
                                content = finalContent,
                                embedding = embedding,
                                expiresAt = resolvedExpiresAt,
                            ),
                        )
                    }
                }

                "UPDATE" -> {
                    val memoryId = decision.memoryId
                    val finalContent = decision.content?.trim()?.takeIf { it.isNotBlank() } ?: candidate?.content
                    if (memoryId != null && finalContent != null) {
                        val embedding = embedIndexByDecisionIdx[idx]?.let { reembeddedVectors[it] } ?: candidateEmbedding
                        mutations.add(
                            MemoryMutation.Update(
                                id = memoryId,
                                guildId = guildId,
                                content = finalContent,
                                embedding = embedding,
                                expiresAt = resolvedExpiresAt,
                            ),
                        )
                    }
                }

                "DELETE" -> {
                    decision.memoryId?.let { memoryId ->
                        mutations.add(MemoryMutation.Delete(id = memoryId, guildId = guildId))
                    }
                }
            }
        }
        return mutations
    }
}
