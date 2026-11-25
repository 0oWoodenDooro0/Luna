package luna.chatter.memory

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import luna.chatter.ChatMessage
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionGenerationConfig
import luna.core.ai.GeminiInteractionInput
import luna.core.ai.GeminiInteractionRequest
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.InteractionTraceContext
import org.slf4j.LoggerFactory

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UnderstandingPatchDto(
    @JsonNames("op", "type", "operation", "patch_action")
    val action: String? = null,
    val id: String? = null,
    val scope: String? = null,
    @SerialName("target_user_id")
    val targetUserId: String? = null,
    val subject: String? = null,
    val content: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class RelationshipUpdateDto(
    @SerialName("targetUserId")
    @JsonNames("target_user_id", "user_id", "userId")
    val targetUserId: String? = null,
    @SerialName("affinityDelta")
    @JsonNames("affinity_delta", "delta")
    val affinityDelta: Int? = null,
    @SerialName("toneStyle")
    @JsonNames("tone_style")
    val toneStyle: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UnderstandingPatchEnvelopeDto(
    @JsonNames("understanding_patches", "patches", "understandings")
    val understandingPatches: List<UnderstandingPatchDto> = emptyList(),
    @JsonNames("relationship_updates")
    val relationshipUpdates: List<RelationshipUpdateDto> = emptyList(),
)

internal data class ValidatedRelationshipUpdate(
    val targetUserId: String,
    val affinityDelta: Int,
    val toneStyle: String?,
)

internal data class Stage3PatchResult(
    val understandingMutations: List<UnderstandingMutation>,
    val relationshipUpdates: List<ValidatedRelationshipUpdate>,
)

internal data class UnderstandingPatchContext(
    val memoryStore: ChatterMemoryStore,
    val interactionClient: GeminiInteractionClient,
    val model: String,
    val baseUrl: String,
    val apiKey: String,
    val maxOutputTokens: Int,
    val guildId: String,
    val chatMessages: List<ChatMessage>,
    val transcript: String,
    val selfId: Snowflake?,
    val session: AiTraceSession,
)

/**
 * Prompt builder and JSON response parser for Stage 3 (`UnderstandingPatch`) of `Memory Distillation`.
 */
internal object UnderstandingPatchPrompt {
    private val logger = LoggerFactory.getLogger(UnderstandingPatchPrompt::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    val SYSTEM_INSTRUCTION: String =
        """
        You maintain concise Social Understanding impressions (communication style, humor, tone, interpersonal dynamics, and guild inside jokes/norms) and per-user User Relationship updates from a Discord conversation transcript.
        Rules:
        - Focus ONLY on social/relational understanding, communication style, and interpersonal rapport—NOT objective atomic facts (those are handled separately).
        - Existing Social Understanding entries are labeled with short aliases like `[u1]`, `[u2]`.
        - For each warranted understanding change, add a patch object to `"understandingPatches"` with an `"action"` field:
          - `ADD`: new impression (`{"action": "ADD", "scope": "user"|"guild", "target_user_id": "<uid string or null>", "subject": "<short topic label>", "content": "<concise impression>"}`).
          - `UPDATE`: revise an existing impression (`{"action": "UPDATE", "id": "u1", "subject": "<short topic label>", "content": "<updated impression>"}`).
          - `REAFFIRM`: timestamp-only touch when an existing impression is clearly reinforced without needing text changes (`{"action": "REAFFIRM", "id": "u1"}`).
          - `DELETE`: remove an existing impression that is clearly contradicted or obsolete (`{"action": "DELETE", "id": "u1"}`).
        - For each human speaker in this batch whose interaction meaningfully shifts Luna's rapport or warrants updating her subjective tone cue, add an entry to `"relationshipUpdates"`:
          - `{"targetUserId": "<uid>", "affinityDelta": <int in -15..15>, "toneStyle": "<optional concise feeling/interaction tone cue, <=120 chars>"}`
          - Omit unchanged speakers or use `[]`.
        - Output ONLY a valid JSON object `{"understandingPatches": [...], "relationshipUpdates": [...]}` (or `{"understandingPatches": [], "relationshipUpdates": []}` if no changes are warranted).
        """.trimIndent()

    fun buildPrompt(
        humanSpeakers: Map<String, String>,
        userUnderstandings: Map<String, List<SocialUnderstandingRecord>>,
        guildUnderstandings: List<SocialUnderstandingRecord>,
        transcript: String,
        userRelationships: Map<String, UserRelationship> = emptyMap(),
    ): Pair<String, Map<String, SocialUnderstandingRecord>> {
        val aliasMap = linkedMapOf<String, SocialUnderstandingRecord>()
        var nextIndex = 1

        val promptText =
            buildString {
                appendLine("Human speakers in this batch:")
                if (humanSpeakers.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    humanSpeakers.forEach { (uid, name) ->
                        val rel = userRelationships[uid] ?: UserRelationship.neutral(guildId = "", userId = uid)
                        val toneCue = rel.toneStyle.ifBlank { "(none)" }
                        appendLine(
                            "  - $name (uid:$uid) [relationship: tier=${rel.tier.name}, score=${rel.affinityScore}, toneStyle=\"$toneCue\"]",
                        )
                    }
                }

                appendLine()
                appendLine("Existing Social Understandings:")
                humanSpeakers.forEach { (uid, name) ->
                    val records = userUnderstandings[uid].orEmpty()
                    appendLine("User $name (uid:$uid):")
                    if (records.isEmpty()) {
                        appendLine("  (none)")
                    } else {
                        records.forEach { record ->
                            val alias = "u${nextIndex++}"
                            aliasMap[alias] = record
                            appendLine("  [$alias] subject=\"${record.subject}\": ${record.content}")
                        }
                    }
                }

                appendLine("Guild:")
                if (guildUnderstandings.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    guildUnderstandings.forEach { record ->
                        val alias = "u${nextIndex++}"
                        aliasMap[alias] = record
                        appendLine("  [$alias] subject=\"${record.subject}\": ${record.content}")
                    }
                }

                appendLine()
                appendLine("Conversation transcript:")
                appendLine(transcript)
            }
        return promptText to aliasMap
    }

    fun parseAndBuildMutations(
        guildId: String,
        responseText: String,
        aliasMap: Map<String, SocialUnderstandingRecord>,
    ): Result<List<UnderstandingMutation>> =
        parsePatchResponse(
            guildId = guildId,
            responseText = responseText,
            aliasMap = aliasMap,
        ).map { it.understandingMutations }

    fun parsePatchResponse(
        guildId: String,
        responseText: String,
        aliasMap: Map<String, SocialUnderstandingRecord>,
        validSpeakerIds: Set<String>? = null,
    ): Result<Stage3PatchResult> =
        runCatching {
            val stripped = MemoryCandidateProcessor.stripJsonFences(responseText)
            val element = json.parseToJsonElement(stripped)
            val envelope =
                when (element) {
                    is JsonArray -> {
                        UnderstandingPatchEnvelopeDto(
                            understandingPatches = json.decodeFromJsonElement<List<UnderstandingPatchDto>>(element),
                            relationshipUpdates = emptyList(),
                        )
                    }

                    is JsonObject -> {
                        json.decodeFromJsonElement<UnderstandingPatchEnvelopeDto>(element)
                    }

                    else -> {
                        throw IllegalArgumentException("Expected JSON array or object for UnderstandingPatch response")
                    }
                }
            val mutations = envelope.understandingPatches.mapNotNull { dto -> toMutationOrNull(guildId, dto, aliasMap) }
            val relationshipUpdates =
                envelope.relationshipUpdates.mapNotNull { dto ->
                    toRelationshipUpdateOrNull(guildId, dto, validSpeakerIds)
                }
            Stage3PatchResult(
                understandingMutations = mutations,
                relationshipUpdates = relationshipUpdates,
            )
        }

    private fun toRelationshipUpdateOrNull(
        guildId: String,
        dto: RelationshipUpdateDto,
        validSpeakerIds: Set<String>?,
    ): ValidatedRelationshipUpdate? {
        val targetUserId = dto.targetUserId?.trim()?.takeIf { it.isNotEmpty() }
        if (targetUserId == null) {
            logger.debug("Ignoring relationshipUpdate with blank targetUserId for guild {}", guildId)
            return null
        }
        if (validSpeakerIds != null && targetUserId !in validSpeakerIds) {
            logger.debug("Ignoring relationshipUpdate for unknown speaker {} in guild {}", targetUserId, guildId)
            return null
        }
        val clampedDelta = NegativeAffinityDecay.clampDelta(dto.affinityDelta ?: 0)
        val sanitizedToneStyle = dto.toneStyle?.let { NegativeAffinityDecay.sanitizeToneStyle(it) }
        return ValidatedRelationshipUpdate(
            targetUserId = targetUserId,
            affinityDelta = clampedDelta,
            toneStyle = sanitizedToneStyle,
        )
    }

    private fun toMutationOrNull(
        guildId: String,
        dto: UnderstandingPatchDto,
        aliasMap: Map<String, SocialUnderstandingRecord>,
    ): UnderstandingMutation? {
        val normalizedAction =
            dto.action
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.isNotEmpty() }
        if (normalizedAction == null) {
            logger.warn("Skipping UnderstandingPatch element missing 'action' field for guild {}", guildId)
            return null
        }
        return when (normalizedAction) {
            "ADD" -> {
                val scope =
                    when (dto.scope?.trim()?.lowercase()) {
                        "user" -> MemoryScope.USER
                        "guild" -> MemoryScope.GUILD
                        else -> return null
                    }
                val targetUserId = dto.targetUserId?.trim()?.takeIf { it.isNotEmpty() }
                if (scope == MemoryScope.USER && targetUserId == null) return null
                val subject = dto.subject?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val content = dto.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                UnderstandingMutation.Add(
                    guildId = guildId,
                    scope = scope,
                    targetUserId = if (scope == MemoryScope.USER) targetUserId else null,
                    subject = subject,
                    content = content,
                )
            }

            "UPDATE" -> {
                val existing = resolveAlias(dto.id, aliasMap) ?: return null
                val subject = dto.subject?.trim()?.takeIf { it.isNotEmpty() } ?: existing.subject
                val content = dto.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                UnderstandingMutation.Update(
                    id = existing.id,
                    guildId = guildId,
                    subject = subject,
                    content = content,
                )
            }

            "REAFFIRM" -> {
                val existing = resolveAlias(dto.id, aliasMap) ?: return null
                UnderstandingMutation.Reaffirm(id = existing.id, guildId = guildId)
            }

            "DELETE" -> {
                val existing = resolveAlias(dto.id, aliasMap) ?: return null
                UnderstandingMutation.Delete(id = existing.id, guildId = guildId)
            }

            else -> {
                null
            }
        }
    }

    suspend fun executeFailOpen(ctx: UnderstandingPatchContext) {
        val guildId = ctx.guildId
        try {
            val selfIdStr = ctx.selfId?.toString()
            val humanSpeakers = linkedMapOf<String, String>()
            for (msg in ctx.chatMessages) {
                val uid = msg.authorId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (!msg.isBot && uid != selfIdStr) {
                    humanSpeakers.putIfAbsent(uid, msg.author)
                }
            }

            val userUnderstandings =
                humanSpeakers.keys.associateWith { uid ->
                    ctx.memoryStore.listUserUnderstandings(guildId = guildId, userId = uid)
                }
            val guildUnderstandings = ctx.memoryStore.listGuildUnderstandings(guildId = guildId)
            val userRelationships =
                if (humanSpeakers.isEmpty()) {
                    emptyMap()
                } else {
                    runCatching {
                        ctx.memoryStore.listUserRelationships(guildId = guildId, userIds = humanSpeakers.keys)
                    }.getOrElse { ex ->
                        if (ex is CancellationException) throw ex
                        logger.warn("Failed to load UserRelationships for guild {} (failing open): {}", guildId, ex.message)
                        ctx.session.markDegraded("UserRelationship load failed: ${ex.message}")
                        emptyMap()
                    }
                }
            val (prompt, aliasMap) =
                buildPrompt(
                    humanSpeakers = humanSpeakers,
                    userUnderstandings = userUnderstandings,
                    guildUnderstandings = guildUnderstandings,
                    transcript = ctx.transcript,
                    userRelationships = userRelationships,
                )

            val request =
                GeminiInteractionRequest(
                    model = ctx.model,
                    systemInstruction = SYSTEM_INSTRUCTION,
                    input = listOf(GeminiInteractionInput.text(prompt)),
                    generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = ctx.maxOutputTokens),
                    background = false,
                )
            val result =
                ctx.interactionClient.interact(
                    baseUrl = ctx.baseUrl,
                    apiKey = ctx.apiKey,
                    request = request,
                    traceContext =
                        InteractionTraceContext(
                            stage = AiAttemptStage.MEMORY_DISTILL_UNDERSTANDING,
                            pipeline = AiTracePipeline.CHATTER_MEMORY_DISTILL,
                            trigger = AiTraceTrigger.AMBIENT,
                            transcriptLength = prompt.length,
                            stripOutputEmojis = false,
                        ),
                )
            val responseText =
                when (result) {
                    is InteractionExecutionResult.Success -> result.text

                    is InteractionExecutionResult.FunctionCall,
                    is InteractionExecutionResult.Error,
                    -> null
                }
            if (responseText == null) {
                logger.warn("UnderstandingPatch stage interaction failed for guild {} (failing open)", guildId)
                ctx.session.markDegraded("UnderstandingPatch interaction failed")
                return
            }

            val stage3Result =
                parsePatchResponse(
                    guildId = guildId,
                    responseText = responseText,
                    aliasMap = aliasMap,
                    validSpeakerIds = humanSpeakers.keys,
                ).getOrElse { ex ->
                    logger.warn("Failed to parse UnderstandingPatch JSON for guild {} (failing open): {}", guildId, ex.message)
                    ctx.session.markDegraded("UnderstandingPatch JSON parse error: ${ex.message}")
                    return
                }

            if (stage3Result.understandingMutations.isNotEmpty()) {
                runCatching {
                    ctx.memoryStore.applyUnderstandingMutations(stage3Result.understandingMutations)
                }.onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    logger.warn("Failed to apply UnderstandingPatch mutations for guild {} (failing open): {}", guildId, ex.message)
                    ctx.session.markDegraded("UnderstandingPatch apply failed: ${ex.message}")
                }
            }

            for (update in stage3Result.relationshipUpdates) {
                runCatching {
                    ctx.memoryStore.applyRelationshipUpdate(
                        guildId = guildId,
                        userId = update.targetUserId,
                        affinityDelta = update.affinityDelta,
                        toneStyle = update.toneStyle,
                    )
                }.onFailure { ex ->
                    if (ex is CancellationException) throw ex
                    logger.warn(
                        "Failed to apply UserRelationship update for guild {} user {} (failing open): {}",
                        guildId,
                        update.targetUserId,
                        ex.message,
                    )
                    ctx.session.markDegraded("UserRelationship update failed: ${ex.message}")
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("UnderstandingPatch stage failed open for guild {}: {}", guildId, ex.message)
            ctx.session.markDegraded("UnderstandingPatch failed: ${ex.message}")
        }
    }

    private fun resolveAlias(
        rawId: String?,
        aliasMap: Map<String, SocialUnderstandingRecord>,
    ): SocialUnderstandingRecord? {
        val normalized =
            rawId
                ?.trim()
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.trim() ?: return null
        return aliasMap[normalized]
    }
}
