package luna.core.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
enum class AiTracePipeline {
    SUMMARY,
    CHATTER,
}

@Serializable
enum class AiTraceTrigger {
    REACTION,
    MENTION,
    AMBIENT,
}

@Serializable
enum class AiTraceOutcome {
    SUCCESS,
    DEGRADED_SUCCESS,
    SKIPPED,
    FAILED,
}

@Serializable
enum class AiTraceFailureCategory {
    RATE_LIMIT,
    TIMEOUT,
    HTTP_ERROR,
    SAFETY_BLOCK,
    PARSE_ERROR,
    EMPTY_OUTPUT,
    NOT_CONFIGURED,
    DURATION_EXCEEDED,
    CONTEXT_ERROR,
    UNEXPECTED_ERROR,
}

@Serializable
enum class AiAttemptStage {
    DECISION,
    SUMMARY_GENERATE,
    SUMMARY_TOOL_CONTINUATION,
    CHATTER_GENERATE,
    AGENTIC_VIDEO,
}

@Serializable
enum class AiAttemptOutcome {
    SUCCESS,
    SKIPPED,
    TOOL_CALL,
    ERROR,
}

@Serializable
data class AiTraceDiscordContext(
    val guildId: String? = null,
    val channelId: String,
    val messageId: String,
    val authorId: String? = null,
    val reactingUserId: String? = null,
    val isDirectMention: Boolean = false,
)

@Serializable
data class AiAttemptRequest(
    val payload: JsonElement? = null,
    val hasImages: Boolean = false,
    val imageCount: Int = 0,
    val hasVideo: Boolean = false,
    val videoDurationSeconds: Long? = null,
    val transcriptLength: Int = 0,
    val webUrlCount: Int = 0,
    val enabledTools: List<String> = emptyList(),
)

@Serializable
data class AiAttemptRecord(
    val attemptIndex: Int,
    val stage: AiAttemptStage,
    val model: String? = null,
    val endpoint: String? = null,
    val latencyMs: Long = 0L,
    val httpStatus: Int? = null,
    val request: AiAttemptRequest? = null,
    val responseRaw: String? = null,
    val finishReason: String? = null,
    val blockReason: String? = null,
    val usageMetadata: GeminiUsageMetadata? = null,
    val pollingAttempts: Int? = null,
    val parsedOutcome: AiAttemptOutcome,
    val errorMessage: String? = null,
    val urlContextResults: List<UrlContextResultEntry> = emptyList(),
    val googleSearchQueries: List<String> = emptyList(),
    val rawCitations: List<GroundingCitation> = emptyList(),
    val filteredCitations: List<GroundingCitation> = emptyList(),
)

@Serializable
data class AiTraceRecord(
    val traceId: String,
    val timestamp: String,
    val pipeline: AiTracePipeline,
    val trigger: AiTraceTrigger,
    val discord: AiTraceDiscordContext,
    val outcome: AiTraceOutcome,
    val degraded: Boolean = false,
    val failureCategory: AiTraceFailureCategory? = null,
    val fallbackPath: List<String> = emptyList(),
    val totalLatencyMs: Long = 0L,
    val attemptCount: Int = 0,
    val finalOutput: String? = null,
    val citations: List<GroundingCitation> = emptyList(),
    val errorSummary: String? = null,
    val attempts: List<AiAttemptRecord> = emptyList(),
)

/**
 * Deterministic single-line JSONL serializer for [AiTraceRecord].
 */
@OptIn(ExperimentalSerializationApi::class)
val AiTraceJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        ignoreUnknownKeys = true
    }
