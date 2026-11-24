package luna.chatter.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import luna.chatter.ChatterConfig
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceTrigger
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionGenerationConfig
import luna.core.ai.GeminiInteractionInput
import luna.core.ai.GeminiInteractionRequest
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.InteractionTraceContext
import org.slf4j.LoggerFactory

/**
 * Delivery action chosen by `Deferred Catch-Up` triage for a missed message.
 */
@Serializable
enum class CatchUpAction {
    REPLY,
    REACT,
}

/**
 * Compact missed-message representation fed into [DeferredCatchUpTriageClient].
 */
data class CatchUpCandidateMessage(
    val messageId: String,
    val authorName: String,
    val content: String,
    val timestampMillis: Long,
    val isDirectMention: Boolean,
    val isReplyToLuna: Boolean,
) {
    val isDirectTarget: Boolean
        get() = isDirectMention || isReplyToLuna
}

/**
 * Validated triage decision selecting a specific missed message for catch-up delivery.
 */
data class CatchUpTriageSelection(
    val messageId: String,
    val action: CatchUpAction,
    val reason: String? = null,
)

/**
 * Lightweight LLM triage client for `Deferred Catch-Up` via Gemini Interactions API
 * (`POST /v1beta/interactions`).
 *
 * Reviews missed channel messages accumulated while Luna was `BUSY` or `SLEEPING`,
 * prioritizes direct `@Luna` mentions / replies to Luna, enforces the configured ambient cap,
 * and discards invalid or out-of-range message IDs safely.
 */
open class DeferredCatchUpTriageClient(
    private val interactionClient: GeminiInteractionClient,
    private val model: String = ChatterConfig.DEFAULT_MODEL,
    private val apiKey: String,
    private val baseUrl: String = ChatterConfig.DEFAULT_BASE_URL,
) {
    private val logger = LoggerFactory.getLogger(DeferredCatchUpTriageClient::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    @Serializable
    private data class TriageSelectionDto(
        val messageId: String,
        val action: String,
        val reason: String? = null,
    )

    @Serializable
    private data class TriageResponseDto(
        val selections: List<TriageSelectionDto> = emptyList(),
    )

    open suspend fun triage(
        candidates: List<CatchUpCandidateMessage>,
        previousAvailability: AvailabilityState,
        currentBlock: ScheduleBlock,
        maxAmbient: Int,
    ): Result<List<CatchUpTriageSelection>> {
        if (candidates.isEmpty()) {
            return Result.success(emptyList())
        }
        if (apiKey.isBlank()) {
            val err = IllegalStateException("GEMINI_API_KEY is not configured")
            logger.warn("DeferredCatchUpTriageClient cannot triage missed messages: {}", err.message)
            return Result.failure(err)
        }

        val cleanModel = model.trim().removePrefix("models/").ifBlank { ChatterConfig.DEFAULT_MODEL }
        val prompt = buildUserPrompt(candidates, previousAvailability, currentBlock, maxAmbient.coerceAtLeast(0))
        val request =
            GeminiInteractionRequest(
                model = cleanModel,
                systemInstruction = SYSTEM_INSTRUCTION,
                input = listOf(GeminiInteractionInput.text(prompt)),
                generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = MAX_OUTPUT_TOKENS),
                background = false,
            )

        val executionResult =
            try {
                interactionClient.interact(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    request = request,
                    traceContext =
                        InteractionTraceContext(
                            stage = AiAttemptStage.CHATTER_GENERATE,
                            pipeline = AiTracePipeline.CHATTER,
                            trigger = AiTraceTrigger.AMBIENT,
                            transcriptLength = prompt.length,
                            stripOutputEmojis = false,
                        ),
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.warn("DeferredCatchUpTriageClient request threw during triage: {}", ex.message)
                return Result.failure(ex)
            }

        val rawText =
            when (executionResult) {
                is InteractionExecutionResult.Success -> {
                    executionResult.text
                }

                is InteractionExecutionResult.FunctionCall -> {
                    val err = IllegalStateException("Unexpected function call during deferred catch-up triage")
                    logger.warn("DeferredCatchUpTriageClient failed: {}", err.message)
                    return Result.failure(err)
                }

                is InteractionExecutionResult.Error -> {
                    val err = IllegalStateException("Gemini API error (${executionResult.failureCategory}): ${executionResult.message}")
                    logger.warn("DeferredCatchUpTriageClient failed: {}", err.message)
                    return Result.failure(err)
                }
            }

        return parseAndSanitize(rawText, candidates, maxAmbient.coerceAtLeast(0))
    }

    internal fun parseAndSanitize(
        rawText: String,
        candidates: List<CatchUpCandidateMessage>,
        maxAmbient: Int,
    ): Result<List<CatchUpTriageSelection>> =
        runCatching {
            val cleaned = stripMarkdownCodeFences(rawText)
            val dto = json.decodeFromString<TriageResponseDto>(cleaned)
            val candidatesById = candidates.associateBy { it.messageId }
            val seenIds = mutableSetOf<String>()

            val directSelections = mutableListOf<CatchUpTriageSelection>()
            val ambientSelections = mutableListOf<CatchUpTriageSelection>()

            for (item in dto.selections) {
                val id = item.messageId.trim()
                val candidate = candidatesById[id]
                if (candidate == null) {
                    logger.debug("DeferredCatchUpTriageClient discarded out-of-range messageId '{}'", id)
                    continue
                }
                if (!seenIds.add(id)) {
                    logger.debug("DeferredCatchUpTriageClient discarded duplicate messageId '{}'", id)
                    continue
                }
                val action = parseAction(item.action)
                if (action == null) {
                    logger.debug("DeferredCatchUpTriageClient discarded invalid action '{}' for messageId '{}'", item.action, id)
                    continue
                }
                val selection =
                    CatchUpTriageSelection(
                        messageId = id,
                        action = action,
                        reason = item.reason?.trim()?.ifBlank { null },
                    )
                if (candidate.isDirectTarget) {
                    directSelections.add(selection)
                } else if (ambientSelections.size < maxAmbient) {
                    ambientSelections.add(selection)
                }
            }

            directSelections + ambientSelections
        }.onFailure { ex ->
            logger.warn("DeferredCatchUpTriageClient failed to parse triage response: {}", ex.message)
        }

    private fun parseAction(raw: String): CatchUpAction? =
        when (raw.trim().uppercase()) {
            "REPLY" -> CatchUpAction.REPLY
            "REACT" -> CatchUpAction.REACT
            else -> null
        }

    private fun buildUserPrompt(
        candidates: List<CatchUpCandidateMessage>,
        previousAvailability: AvailabilityState,
        currentBlock: ScheduleBlock,
        maxAmbient: Int,
    ): String =
        buildString {
            appendLine("Luna 剛從 ${previousAvailability.name} 狀態回到 ${currentBlock.availability.name}。")
            appendLine("目前行程：${currentBlock.activityTitle}（${currentBlock.activityDetail}）")
            appendLine("非直接提及（ambient）的訊息最多只能挑選 $maxAmbient 則。")
            appendLine()
            appendLine("以下是錯過期間的頻道訊息（依時間順序）：")
            for (candidate in candidates) {
                val tags =
                    buildList {
                        if (candidate.isDirectMention) add("DIRECT_MENTION")
                        if (candidate.isReplyToLuna) add("REPLY_TO_LUNA")
                    }
                val tagSuffix = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""
                val snippet =
                    candidate.content
                        .replace('\n', ' ')
                        .trim()
                        .take(MAX_CONTENT_PREVIEW_CHARS)
                appendLine("- id=${candidate.messageId}$tagSuffix ${candidate.authorName}: $snippet")
            }
            appendLine()
            appendLine("請只輸出合法 JSON，不要包含額外說明文字。")
        }.trim()

    private fun stripMarkdownCodeFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        val withoutOpen = if (firstNewline >= 0) trimmed.substring(firstNewline + 1) else trimmed.removePrefix("```")
        val lastFence = withoutOpen.lastIndexOf("```")
        return if (lastFence >= 0) withoutOpen.substring(0, lastFence).trim() else withoutOpen.trim()
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 1024
        private const val MAX_CONTENT_PREVIEW_CHARS = 300

        val SYSTEM_INSTRUCTION =
            """
            你是 Luna（露娜）的補看訊息篩選器（Deferred Catch-Up Triage）。
            Luna 剛結束忙碌或睡覺時段，回來滑了一下 Discord 頻道裡錯過的訊息。
            她不需要每句都回，只會挑出真正值得回應的少數訊息：
            1. 優先檢視標記 [DIRECT_MENTION] 或 [REPLY_TO_LUNA] 的訊息（若話題早已結束或無需回覆，也可略過）。
            2. 一般閒聊（ambient）訊息最多只能選取提示中指定的上限數量；若沒有特別值得互動的訊息，請回傳空陣列。
            3. 每則選中的訊息請決定動作：
               - REPLY：用文字回覆（適合提問、邀請、有共鳴的話題）
               - REACT：只按一個表情符號反應（適合梗圖、近況分享、輕量致意）
            4. 只能輸出清單中存在的 messageId，不可捏造 ID。

            請嚴格輸出以下 JSON 結構：
            {
              "selections": [
                {
                  "messageId": "123456789",
                  "action": "REPLY",
                  "reason": "簡短原因"
                }
              ]
            }
            """.trimIndent()
    }
}
