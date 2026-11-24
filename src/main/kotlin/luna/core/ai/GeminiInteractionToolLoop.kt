package luna.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

/**
 * Outcome returned by a caller-supplied tool executor inside [GeminiInteractionClient.interactWithTools].
 */
sealed interface ToolExecutionOutcome {
    /**
     * Tool executed successfully; [payload] is serialized as `function_result` to continue the loop.
     */
    data class Result(
        val payload: JsonElement,
    ) : ToolExecutionOutcome

    /**
     * Tool execution aborted; terminates the loop immediately with a deterministic [failureCategory].
     */
    data class Abort(
        val message: String,
        val failureCategory: AiTraceFailureCategory,
        val cause: Throwable? = null,
    ) : ToolExecutionOutcome
}

/**
 * Cohesive collaborator owning the bounded multi-turn tool-continuation protocol for
 * [GeminiInteractionClient.interactWithTools].
 */
internal class GeminiInteractionToolLoop(
    private val client: GeminiInteractionClient,
) {
    private val logger = LoggerFactory.getLogger(GeminiInteractionToolLoop::class.java)

    suspend fun execute(
        baseUrl: String,
        apiKey: String,
        request: GeminiInteractionRequest,
        traceContext: InteractionTraceContext,
        maxTurns: Int,
        continuationStage: AiAttemptStage,
        toolExecutor: suspend (GeminiInteractionFunctionCall) -> ToolExecutionOutcome,
    ): InteractionExecutionResult {
        val effectiveMaxTurns = maxTurns.coerceAtLeast(1)
        val declaredToolNames =
            request.tools
                .orEmpty()
                .filter { it.type == GeminiInteractionTool.TYPE_FUNCTION }
                .mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotBlank() } }
                .toSet()
        val baseTranscriptLength =
            traceContext.transcriptLength
                ?: request.input
                    .filter { it.type == GeminiInteractionInput.TYPE_TEXT }
                    .sumOf { it.text?.length ?: 0 }

        var currentResult =
            client.interact(
                baseUrl = baseUrl,
                apiKey = apiKey,
                request = request,
                traceContext = traceContext,
            )
        var turnCount = 1

        while (currentResult is InteractionExecutionResult.FunctionCall) {
            if (turnCount >= effectiveMaxTurns) {
                val msg = "Gemini interaction exceeded maxTurns ($effectiveMaxTurns) during tool continuation"
                logger.warn("{} (interactionId={})", msg, currentResult.interactionId)
                return InteractionExecutionResult.Error.Generic(
                    message = msg,
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                )
            }
            if (currentResult.interactionId.isBlank()) {
                val msg = "Malformed function_call step: missing interactionId"
                logger.error(msg)
                return InteractionExecutionResult.Error.Generic(
                    message = msg,
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                )
            }
            val functionCalls = currentResult.functionCalls
            if (functionCalls.isEmpty()) {
                val msg = "Gemini returned function_call step with empty functionCalls for interaction ${currentResult.interactionId}"
                logger.error(msg)
                return InteractionExecutionResult.Error.Generic(
                    message = msg,
                    failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                )
            }

            val functionResults = mutableListOf<GeminiInteractionInput>()
            for (call in functionCalls) {
                if (call.name.isBlank() || call.id.isBlank()) {
                    val msg = "Malformed function_call step (name='${call.name}', call_id='${call.id}')"
                    logger.error(msg)
                    return InteractionExecutionResult.Error.Generic(
                        message = msg,
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    )
                }
                if (call.name !in declaredToolNames) {
                    val msg = "Unknown tool call requested by Gemini: ${call.name}"
                    logger.error(msg)
                    return InteractionExecutionResult.Error.Generic(
                        message = msg,
                        failureCategory = AiTraceFailureCategory.PARSE_ERROR,
                    )
                }

                val outcome =
                    try {
                        toolExecutor(call)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Tool executor threw exception for tool '{}'", call.name, e)
                        return InteractionExecutionResult.Error.Generic(
                            message = "Tool executor failed for '${call.name}': ${e.message}",
                            cause = e,
                            failureCategory = AiTraceFailureCategory.CONTEXT_ERROR,
                        )
                    }

                when (outcome) {
                    is ToolExecutionOutcome.Result -> {
                        functionResults.add(
                            GeminiInteractionInput.functionResult(
                                name = call.name,
                                callId = call.id,
                                result = outcome.payload,
                            ),
                        )
                    }

                    is ToolExecutionOutcome.Abort -> {
                        logger.error(
                            "Tool executor aborted for tool '{}' (category={}): {}",
                            call.name,
                            outcome.failureCategory,
                            outcome.message,
                            outcome.cause,
                        )
                        return InteractionExecutionResult.Error.Generic(
                            message = outcome.message,
                            cause = outcome.cause,
                            failureCategory = outcome.failureCategory,
                        )
                    }
                }
            }

            turnCount++
            val continuationPayloadLength = functionResults.sumOf { it.result?.toString()?.length ?: 0 }
            val continuationVariant = functionCalls.map { it.name }.distinct().joinToString(",")
            currentResult =
                client.continueInteraction(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = request.model,
                    previousInteractionId = currentResult.interactionId,
                    functionResults = functionResults,
                    systemInstruction = request.systemInstruction,
                    tools = request.tools.orEmpty(),
                    maxOutputTokens = request.generationConfig?.maxOutputTokens,
                    background = request.background,
                    traceContext =
                        traceContext.copy(
                            stage = continuationStage,
                            variant = continuationVariant,
                            transcriptLength = baseTranscriptLength + continuationPayloadLength,
                        ),
                )
        }

        return currentResult
    }
}
