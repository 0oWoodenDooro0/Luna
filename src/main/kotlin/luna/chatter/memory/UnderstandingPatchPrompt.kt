package luna.chatter.memory

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
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
        You maintain concise Social Understanding impressions (communication style, humor, tone, interpersonal dynamics, and guild inside jokes/norms) from a Discord conversation transcript.
        Rules:
        - Focus ONLY on social/relational understanding and communication style—NOT objective atomic facts (those are handled separately).
        - Existing entries are labeled with short aliases like `[u1]`, `[u2]`.
        - For each warranted change, output one patch object with an `"action"` field:
          - `ADD`: new impression (`{"action": "ADD", "scope": "user"|"guild", "target_user_id": "<uid string or null>", "subject": "<short topic label>", "content": "<concise impression>"}`).
          - `UPDATE`: revise an existing impression (`{"action": "UPDATE", "id": "u1", "subject": "<short topic label>", "content": "<updated impression>"}`).
          - `REAFFIRM`: timestamp-only touch when an existing impression is clearly reinforced without needing text changes (`{"action": "REAFFIRM", "id": "u1"}`).
          - `DELETE`: remove an existing impression that is clearly contradicted or obsolete (`{"action": "DELETE", "id": "u1"}`).
        - Output ONLY a valid JSON array of patch objects, or `[]` if no understanding changes are warranted.
        """.trimIndent()

    fun buildPrompt(
        humanSpeakers: Map<String, String>,
        userUnderstandings: Map<String, List<SocialUnderstandingRecord>>,
        guildUnderstandings: List<SocialUnderstandingRecord>,
        transcript: String,
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
                        appendLine("  - $name (uid:$uid)")
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
        runCatching {
            val dtos =
                json.decodeFromString<List<UnderstandingPatchDto>>(
                    MemoryCandidateProcessor.stripJsonFences(responseText),
                )
            dtos.mapNotNull { dto -> toMutationOrNull(guildId, dto, aliasMap) }
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
            val (prompt, aliasMap) =
                buildPrompt(
                    humanSpeakers = humanSpeakers,
                    userUnderstandings = userUnderstandings,
                    guildUnderstandings = guildUnderstandings,
                    transcript = ctx.transcript,
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

            val mutations =
                parseAndBuildMutations(guildId = guildId, responseText = responseText, aliasMap = aliasMap)
                    .getOrElse { ex ->
                        logger.warn("Failed to parse UnderstandingPatch JSON for guild {} (failing open): {}", guildId, ex.message)
                        ctx.session.markDegraded("UnderstandingPatch JSON parse error: ${ex.message}")
                        return
                    }

            if (mutations.isNotEmpty()) {
                ctx.memoryStore.applyUnderstandingMutations(mutations)
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
