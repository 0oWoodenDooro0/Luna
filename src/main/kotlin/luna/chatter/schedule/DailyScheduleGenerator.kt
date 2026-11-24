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
import java.time.Clock
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Generates Luna's daily 24-hour persona-driven [DailySchedule] via Gemini Interactions API
 * (`POST /v1beta/interactions`).
 *
 * Enforces strict contiguous `00:00–24:00` validation and returns [Result.failure] on any API,
 * parse, or validation error without fabricating a hardcoded fallback schedule.
 */
open class DailyScheduleGenerator(
    private val interactionClient: GeminiInteractionClient,
    private val model: String = ChatterConfig.DEFAULT_MODEL,
    private val apiKey: String,
    private val baseUrl: String = ChatterConfig.DEFAULT_BASE_URL,
    private val timezone: String = ChatterConfig.DEFAULT_TIMEZONE,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(DailyScheduleGenerator::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    @Serializable
    private data class ScheduleBlockDto(
        val startMinute: Int,
        val endMinute: Int,
        val activityTitle: String,
        val activityDetail: String,
        val statusText: String,
        val availability: AvailabilityState,
    )

    @Serializable
    private data class DailyScheduleDto(
        val summary: String,
        val blocks: List<ScheduleBlockDto>,
    )

    /**
     * Generates and validates a 24-hour [DailySchedule] for [date].
     */
    open suspend fun generate(
        date: LocalDate,
        previousDaySummary: String? = null,
        commitments: List<ScheduleCommitment> = emptyList(),
    ): Result<DailySchedule> {
        if (apiKey.isBlank()) {
            val err = IllegalStateException("GEMINI_API_KEY is not configured")
            logger.warn("DailyScheduleGenerator cannot generate schedule: {}", err.message)
            return Result.failure(err)
        }

        val cleanModel = model.trim().removePrefix("models/").ifBlank { ChatterConfig.DEFAULT_MODEL }
        val prompt = buildUserPrompt(date, previousDaySummary, commitments)
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
                logger.warn("DailyScheduleGenerator request threw for {}: {}", date, ex.message)
                return Result.failure(ex)
            }

        val rawText =
            when (executionResult) {
                is InteractionExecutionResult.Success -> {
                    executionResult.text
                }

                is InteractionExecutionResult.FunctionCall -> {
                    val err = IllegalStateException("Unexpected function call during daily schedule generation")
                    logger.warn("DailyScheduleGenerator failed for {}: {}", date, err.message)
                    return Result.failure(err)
                }

                is InteractionExecutionResult.Error -> {
                    val err = IllegalStateException("Gemini API error (${executionResult.failureCategory}): ${executionResult.message}")
                    logger.warn("DailyScheduleGenerator failed for {}: {}", date, err.message)
                    return Result.failure(err)
                }
            }

        return parseAndValidate(rawText, date)
    }

    internal fun parseAndValidate(
        rawText: String,
        date: LocalDate,
    ): Result<DailySchedule> =
        runCatching {
            val cleaned = stripMarkdownCodeFences(rawText)
            val dto = json.decodeFromString<DailyScheduleDto>(cleaned)
            val blocks =
                dto.blocks.map { block ->
                    ScheduleBlock(
                        startMinute = block.startMinute,
                        endMinute = block.endMinute,
                        activityTitle = block.activityTitle.trim(),
                        activityDetail = block.activityDetail.trim(),
                        statusText = block.statusText.trim(),
                        availability = block.availability,
                    )
                }
            DailySchedule(
                date = date,
                timezone = timezone,
                blocks = blocks,
                summary = dto.summary.trim(),
                createdAtMillis = clock.millis(),
            )
        }.onFailure { ex ->
            logger.warn("DailyScheduleGenerator failed to parse/validate schedule for {}: {}", date, ex.message)
        }

    private fun buildUserPrompt(
        date: LocalDate,
        previousDaySummary: String?,
        commitments: List<ScheduleCommitment>,
    ): String {
        val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return buildString {
            appendLine("請為 Luna 規劃 $date ($dayOfWeek, 時區 $timezone) 的完整 24 小時作息排程。")
            if (!previousDaySummary.isNullOrBlank()) {
                appendLine("昨日作息摘要：$previousDaySummary")
            } else {
                appendLine("昨日作息摘要：無（請自然安排今日作息）")
            }
            if (commitments.isNotEmpty()) {
                appendLine("本日已預約的約定行程（請務必在對應時段安排這些活動，其餘時段請自然銜接）：")
                for (commitment in commitments) {
                    appendLine(
                        "- ${commitment.formattedTimeRange()} [${commitment.availability.name}] " +
                            "${commitment.activityTitle}：${commitment.activityDetail} (狀態: ${commitment.statusText})",
                    )
                }
            }
            appendLine("請只輸出合法 JSON，不要包含額外說明文字。")
        }.trim()
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 2048

        val SYSTEM_INSTRUCTION =
            """
            你是 Luna（露娜）的日常作息規劃器。Luna 是一位住在家裡、作息偏夜貓子的自由創作者／接案插畫與設計工作者，個性機靈直率又有點小懶散，喜歡追番、打遊戲、喝飲料和在 Discord 群組跟朋友聊天。

            請根據日期、星期幾（平日接案或休息節奏、週末更放鬆）以及昨日作息摘要（若有），規劃從 00:00 到 24:00（分鐘 0 到 1440）連續無間斷、不重疊的今日作息。

            規則：
            1. blocks 必須從 startMinute = 0 開始，最後一塊必須以 endMinute = 1440 結束，且相鄰區塊必須滿足 prev.endMinute == next.startMinute。
            2. availability 只能是以下四種之一：
               - SLEEPING：睡覺／補眠時段（不看訊息）
               - BUSY：專心接案趕稿、外出辦事、洗澡或專注做某件事（不回訊息）
               - BREAK：吃飯、泡咖啡、伸懶腰滑手機的短暫休息（會看一眼群組並簡短回幾句）
               - AVAILABLE：自由耍廢、追番、打遊戲或窩在電腦前（正常跟群友聊天）
            3. activityTitle、activityDetail、statusText、summary 請使用自然口語的繁體中文；statusText 請簡短適合當作 Discord 自訂狀態（可帶 1 個 emoji）。
            4. 作息節奏建議（彈性原則）：單段連續 BUSY 建議控制在約 1–2 小時左右，避免連續排好幾個小時不休息的 BUSY；若趕稿或辦事需要較長時間，請在中間自然穿插 15–45 分鐘的 BREAK（例如喝水、吃點心、伸懶腰、滑群組）或 AVAILABLE 來分段。整天請避免排滿 BUSY，留出足夠的 AVAILABLE 與 BREAK 時段讓 Luna 可以跟群友聊天（此為彈性節奏建議，不需硬性限制每日總時數）。

            請嚴格輸出以下 JSON 結構：
            {
              "summary": "一句話總結今天作息與情緒節奏",
              "blocks": [
                {
                  "startMinute": 0,
                  "endMinute": 120,
                  "activityTitle": "深夜追番",
                  "activityDetail": "窩在電腦前看新番順便滑群組",
                  "statusText": "📺 深夜追番中",
                  "availability": "AVAILABLE"
                }
              ]
            }
            """.trimIndent()
    }
}

internal fun stripMarkdownCodeFences(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("```")) return trimmed
    val firstNewline = trimmed.indexOf('\n')
    val withoutOpen = if (firstNewline >= 0) trimmed.substring(firstNewline + 1) else trimmed.removePrefix("```")
    val lastFence = withoutOpen.lastIndexOf("```")
    return if (lastFence >= 0) withoutOpen.substring(0, lastFence).trim() else withoutOpen.trim()
}
