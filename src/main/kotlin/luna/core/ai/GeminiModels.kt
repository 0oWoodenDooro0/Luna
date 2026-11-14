package luna.core.ai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Supported video processing modes for Gemini multimodal video understanding.
 */
enum class VideoProcessingMode(
    val value: String,
) {
    STATIC("static"),
    AGENTIC("agentic"),
}

/**
 * Image attachment from a chat message provided for multimodal AI understanding.
 *
 * @property url Signed CDN HTTPS URL of the image attachment.
 * @property mimeType MIME type of the image (e.g. `image/jpeg`, `image/png`, `image/webp`, `image/gif`).
 * @property filename Optional original filename of the image attachment.
 */
@Serializable
data class ChatImageAttachment(
    val url: String,
    val mimeType: String,
    val filename: String? = null,
)

/**
 * Standard tool declarations for Gemini Function Calling and Search Grounding.
 */
object GeminiTools {
    const val FETCH_EARLIER_MESSAGES = "fetch_earlier_messages"

    val FETCH_EARLIER_MESSAGES_DECLARATION =
        GeminiFunctionDeclaration(
            name = FETCH_EARLIER_MESSAGES,
            description =
                "Fetches earlier chat messages from the channel history when more conversational " +
                    "context or background information is needed.",
            parameters =
                GeminiSchema(
                    type = "OBJECT",
                    description = "Parameters for fetch_earlier_messages tool",
                    properties =
                        mapOf(
                            "reason" to
                                GeminiSchema(
                                    type = "STRING",
                                    description = "Reason explaining why earlier context is requested.",
                                ),
                        ),
                    required = listOf("reason"),
                ),
        )

    val FETCH_EARLIER_MESSAGES_TOOL =
        GeminiTool(
            functionDeclarations = listOf(FETCH_EARLIER_MESSAGES_DECLARATION),
        )

    val GOOGLE_SEARCH_TOOL =
        GeminiTool(
            googleSearch = GeminiGoogleSearch(),
        )

    val GOOGLE_SEARCH = GOOGLE_SEARCH_TOOL
}

/**
 * Canonical Gemini finish reasons used across pipelines to classify safety blocks.
 */
object GeminiFinishReasons {
    val SAFETY_BLOCK_REASONS: Set<String> =
        setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")

    fun isSafetyBlock(finishReason: String?): Boolean = finishReason?.uppercase() in SAFETY_BLOCK_REASONS
}

@Serializable
data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null,
    val tools: List<GeminiTool>? = null,
    val toolConfig: GeminiToolConfig? = null,
)

@Serializable
class GeminiGoogleSearch

@Serializable
data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>? = null,
    @SerialName("function_declarations")
    val functionDeclarationsSnake: List<GeminiFunctionDeclaration>? = null,
    val googleSearch: GeminiGoogleSearch? = null,
    @SerialName("google_search")
    val googleSearchSnake: GeminiGoogleSearch? = null,
) {
    val actualFunctionDeclarations: List<GeminiFunctionDeclaration>
        get() = functionDeclarations ?: functionDeclarationsSnake ?: emptyList()

    val actualGoogleSearch: GeminiGoogleSearch?
        get() = googleSearch ?: googleSearchSnake
}

@Serializable
data class GeminiFunctionDeclaration(
    val name: String,
    val description: String? = null,
    val parameters: GeminiSchema? = null,
)

@Serializable
data class GeminiSchema(
    val type: String,
    val description: String? = null,
    val properties: Map<String, GeminiSchema>? = null,
    val required: List<String>? = null,
)

@Serializable
data class GeminiToolConfig(
    val functionCallingConfig: GeminiFunctionCallingConfig? = null,
)

@Serializable
data class GeminiFunctionCallingConfig(
    val mode: String? = null,
    val allowedFunctionNames: List<String>? = null,
)

@Serializable
data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject? = null,
) {
    val effectiveArgs: JsonObject
        get() = args ?: JsonObject(emptyMap())

    fun argsAsStrings(): Map<String, String> =
        effectiveArgs.mapValues { (_, element) ->
            if (element is JsonPrimitive) {
                element.content
            } else {
                element.toString()
            }
        }
}

@Serializable
data class GeminiFunctionResponse(
    val name: String,
    val response: JsonObject,
) {
    companion object {
        fun of(
            name: String,
            content: String,
        ): GeminiFunctionResponse =
            GeminiFunctionResponse(
                name = name,
                response =
                    buildJsonObject {
                        put("name", name)
                        put("content", content)
                    },
            )
    }
}

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String? = null,
)

@Serializable
data class GeminiFileData(
    @SerialName("file_uri")
    val fileUri: String,
    @SerialName("mime_type")
    val mimeType: String? = null,
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    @SerialName("file_data")
    val fileData: GeminiFileData? = null,
    val functionCall: GeminiFunctionCall? = null,
    @SerialName("function_call")
    val functionCallSnake: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null,
    @SerialName("function_response")
    val functionResponseSnake: GeminiFunctionResponse? = null,
) {
    val actualFunctionCall: GeminiFunctionCall?
        get() = functionCall ?: functionCallSnake

    val actualFunctionResponse: GeminiFunctionResponse?
        get() = functionResponse ?: functionResponseSnake
}

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiUsageMetadata(
    @JsonNames("prompt_token_count", "input_tokens")
    val promptTokenCount: Int? = null,
    @JsonNames("candidates_token_count", "output_tokens")
    val candidatesTokenCount: Int? = null,
    @JsonNames("total_token_count", "total_tokens")
    val totalTokenCount: Int? = null,
    @JsonNames("thoughts_token_count", "thoughts_tokens")
    val thoughtsTokenCount: Int? = null,
    @JsonNames("tool_use_prompt_token_count", "tool_use_input_tokens")
    val toolUseInputTokens: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate>? = null,
    @JsonNames("prompt_feedback")
    val promptFeedback: GeminiPromptFeedback? = null,
    val error: GeminiApiErrorDetails? = null,
    @JsonNames("usage_metadata")
    val usageMetadata: GeminiUsageMetadata? = null,
)

@Serializable
data class GroundingCitation(
    val title: String,
    val url: String,
)

@Serializable
data class GeminiGroundingMetadata(
    val webSearchQueries: List<String>? = null,
    @SerialName("web_search_queries")
    val webSearchQueriesSnake: List<String>? = null,
    val groundingChunks: List<GeminiGroundingChunk>? = null,
    @SerialName("grounding_chunks")
    val groundingChunksSnake: List<GeminiGroundingChunk>? = null,
) {
    val actualWebSearchQueries: List<String>
        get() = webSearchQueries ?: webSearchQueriesSnake ?: emptyList()

    val actualGroundingChunks: List<GeminiGroundingChunk>
        get() = groundingChunks ?: groundingChunksSnake ?: emptyList()
}

@Serializable
data class GeminiGroundingChunk(
    val web: GeminiGroundingWebSource? = null,
)

@Serializable
data class GeminiGroundingWebSource(
    val uri: String? = null,
    val title: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    @JsonNames("finish_reason")
    val finishReason: String? = null,
    val groundingMetadata: GeminiGroundingMetadata? = null,
    @SerialName("grounding_metadata")
    val groundingMetadataSnake: GeminiGroundingMetadata? = null,
) {
    val actualGroundingMetadata: GeminiGroundingMetadata?
        get() = groundingMetadata ?: groundingMetadataSnake
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiPromptFeedback(
    @JsonNames("block_reason")
    val blockReason: String? = null,
)

@Serializable
data class GeminiApiErrorDetails(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

/**
 * Returns `true` when [uri] points to a YouTube host (`youtube.com`, subdomains, or `youtu.be`),
 * for which the Gemini Interactions API requires omitting `mime_type`.
 */
fun isYouTubeUri(uri: String): Boolean = ContextUrlClassifier.isYouTubeUrl(uri)

@Serializable
data class GeminiInteractionTool(
    val type: String,
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonObject? = null,
) {
    companion object {
        const val TYPE_URL_CONTEXT = "url_context"
        const val TYPE_GOOGLE_SEARCH = "google_search"
        const val TYPE_FUNCTION = "function"

        fun urlContext(): GeminiInteractionTool = GeminiInteractionTool(type = TYPE_URL_CONTEXT)

        fun googleSearch(): GeminiInteractionTool = GeminiInteractionTool(type = TYPE_GOOGLE_SEARCH)

        fun function(
            name: String,
            description: String,
            parameters: JsonObject,
        ): GeminiInteractionTool =
            GeminiInteractionTool(
                type = TYPE_FUNCTION,
                name = name,
                description = description,
                parameters = parameters,
            )
    }
}

/**
 * Standard tool definitions for Gemini `/v1beta/interactions` requests.
 */
object GeminiInteractionTools {
    val URL_CONTEXT: GeminiInteractionTool = GeminiInteractionTool.urlContext()

    val GOOGLE_SEARCH: GeminiInteractionTool = GeminiInteractionTool.googleSearch()

    val FETCH_EARLIER_MESSAGES: GeminiInteractionTool =
        GeminiInteractionTool.function(
            name = GeminiTools.FETCH_EARLIER_MESSAGES,
            description =
                "Fetches earlier chat messages from the channel history when more conversational " +
                    "context or background information is needed.",
            parameters =
                buildJsonObject {
                    put("type", "object")
                    put("description", "Parameters for fetch_earlier_messages tool")
                    putJsonObject("properties") {
                        putJsonObject("reason") {
                            put("type", "string")
                            put("description", "Reason explaining why earlier context is requested.")
                        }
                    }
                    put(
                        "required",
                        JsonArray(listOf(JsonPrimitive("reason"))),
                    )
                },
        )
}

@Serializable
data class GeminiInteractionGenerationConfig(
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int? = null,
)

@Serializable
data class GeminiInteractionInput(
    val type: String,
    val text: String? = null,
    val uri: String? = null,
    @SerialName("mime_type")
    val mimeType: String? = null,
    val processing: String? = null,
    val name: String? = null,
    @SerialName("call_id")
    val callId: String? = null,
    val result: JsonElement? = null,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_FUNCTION_RESULT = "function_result"

        fun text(text: String): GeminiInteractionInput =
            GeminiInteractionInput(
                type = TYPE_TEXT,
                text = text,
            )

        fun image(
            uri: String,
            mimeType: String,
        ): GeminiInteractionInput =
            GeminiInteractionInput(
                type = TYPE_IMAGE,
                uri = uri,
                mimeType = mimeType,
            )

        fun video(
            uri: String,
            processing: VideoProcessingMode = VideoProcessingMode.STATIC,
            mimeType: String? = null,
        ): GeminiInteractionInput =
            GeminiInteractionInput(
                type = TYPE_VIDEO,
                uri = uri,
                mimeType = if (isYouTubeUri(uri)) null else mimeType,
                processing = processing.value,
            )

        fun functionResult(
            name: String,
            callId: String,
            result: String,
        ): GeminiInteractionInput =
            functionResult(
                name = name,
                callId = callId,
                result = JsonPrimitive(result),
            )

        fun functionResult(
            name: String,
            callId: String,
            result: JsonElement,
        ): GeminiInteractionInput =
            GeminiInteractionInput(
                type = TYPE_FUNCTION_RESULT,
                name = name,
                callId = callId,
                result = result,
            )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiInteractionRequest(
    val model: String,
    @SerialName("system_instruction")
    val systemInstruction: String? = null,
    val input: List<GeminiInteractionInput>,
    val tools: List<GeminiInteractionTool>? = null,
    @SerialName("generation_config")
    val generationConfig: GeminiInteractionGenerationConfig? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val background: Boolean = true,
    @SerialName("previous_interaction_id")
    val previousInteractionId: String? = null,
)

/**
 * Single URL retrieval status entry extracted from a `url_context_result` Interactions API step.
 */
@Serializable
data class UrlContextResultEntry(
    @SerialName("retrieved_url")
    val retrievedUrl: String,
    val status: String,
) {
    val isSuccess: Boolean
        get() =
            status.equals("success", ignoreCase = true) ||
                status.equals("URL_RETRIEVAL_STATUS_SUCCESS", ignoreCase = true)
}

/**
 * Function call extracted from a `function_call` step in `/v1beta/interactions`.
 */
data class GeminiInteractionFunctionCall(
    val id: String,
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
) {
    fun argumentsAsStrings(): Map<String, String> =
        arguments.mapValues { (_, element) ->
            if (element is JsonPrimitive) {
                element.content
            } else {
                element.toString()
            }
        }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiInteractionUrlCitation(
    @JsonNames("uri")
    val url: String? = null,
    val title: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiInteractionAnnotation(
    val type: String? = null,
    @SerialName("start_index")
    val startIndex: Int? = null,
    @SerialName("end_index")
    val endIndex: Int? = null,
    @JsonNames("uri")
    val url: String? = null,
    val title: String? = null,
    @SerialName("url_citation")
    val urlCitation: GeminiInteractionUrlCitation? = null,
) {
    val effectiveUrl: String?
        get() = url?.takeIf { it.isNotBlank() } ?: urlCitation?.url?.takeIf { it.isNotBlank() }

    val effectiveTitle: String?
        get() = title?.takeIf { it.isNotBlank() } ?: urlCitation?.title?.takeIf { it.isNotBlank() }
}

@Serializable
data class GeminiInteractionStepContent(
    val type: String? = null,
    val text: String? = null,
    val annotations: List<GeminiInteractionAnnotation>? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiInteractionStep(
    val type: String? = null,
    val id: String? = null,
    @SerialName("call_id")
    val callId: String? = null,
    val name: String? = null,
    @JsonNames("args")
    val arguments: JsonObject? = null,
    val result: JsonElement? = null,
    @SerialName("is_error")
    val isError: Boolean? = null,
    val content: List<GeminiInteractionStepContent>? = null,
    val annotations: List<GeminiInteractionAnnotation>? = null,
    @JsonNames("finish_reason")
    val finishReason: String? = null,
    @SerialName("retrieved_url")
    val retrievedUrl: String? = null,
    val status: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeminiInteractionResponse(
    val id: String? = null,
    val model: String? = null,
    val status: String? = null,
    @SerialName("previous_interaction_id")
    val previousInteractionId: String? = null,
    @SerialName("output_text")
    val outputText: String? = null,
    val steps: List<GeminiInteractionStep>? = null,
    @JsonNames("usage_metadata")
    val usage: GeminiUsageMetadata? = null,
    @JsonNames("finish_reason")
    val finishReason: String? = null,
    val error: GeminiApiErrorDetails? = null,
) {
    fun extractOutputText(): String {
        val stepText =
            steps
                ?.filter { it.type == "model_output" }
                ?.flatMap { it.content ?: emptyList() }
                ?.mapNotNull { it.text }
                ?.joinToString("\n")
                ?.trim()

        if (!stepText.isNullOrBlank()) {
            return stepText
        }
        return outputText?.trim() ?: ""
    }

    fun extractUrlContextResults(): List<UrlContextResultEntry> {
        val stepList = steps ?: return emptyList()
        val entries = mutableListOf<UrlContextResultEntry>()
        for (step in stepList) {
            if (step.type != "url_context_result") continue
            val defaultStatus = if (step.isError == true) "error" else "unknown"
            val resultElem = step.result
            when (resultElem) {
                is JsonArray -> {
                    for (item in resultElem) {
                        extractUrlContextEntryFromObject(item as? JsonObject, defaultStatus)?.let(entries::add)
                    }
                }

                is JsonObject -> {
                    val direct = extractUrlContextEntryFromObject(resultElem, defaultStatus)
                    if (direct != null) {
                        entries.add(direct)
                    } else {
                        val nestedArray =
                            (resultElem["url_metadata"] as? JsonArray)
                                ?: (resultElem["results"] as? JsonArray)
                                ?: (resultElem["urls"] as? JsonArray)
                        nestedArray?.forEach { item ->
                            extractUrlContextEntryFromObject(item as? JsonObject, defaultStatus)?.let(entries::add)
                        }
                    }
                }

                else -> {
                    val topUrl = step.retrievedUrl?.trim()?.takeIf { it.isNotBlank() }
                    if (topUrl != null) {
                        entries.add(
                            UrlContextResultEntry(
                                retrievedUrl = topUrl,
                                status = step.status?.trim()?.takeIf { it.isNotBlank() } ?: defaultStatus,
                            ),
                        )
                    }
                }
            }
        }
        return entries
    }

    fun extractGoogleSearchQueries(): List<String> {
        val stepList = steps ?: return emptyList()
        val queries = LinkedHashSet<String>()
        for (step in stepList) {
            if (step.type != "google_search_call") continue
            val args = step.arguments ?: continue
            val queriesArr = args["queries"] as? JsonArray
            queriesArr?.forEach { elem ->
                (elem as? JsonPrimitive)
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(queries::add)
            }
            (args["query"] as? JsonPrimitive)
                ?.content
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(queries::add)
        }
        return queries.toList()
    }

    fun extractFunctionCalls(): List<GeminiInteractionFunctionCall> {
        val stepList = steps ?: return emptyList()
        return stepList.mapNotNull { step ->
            if (step.type != "function_call") return@mapNotNull null
            val fnName = step.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val callId = step.callId?.trim()?.takeIf { it.isNotBlank() } ?: step.id?.trim() ?: ""
            GeminiInteractionFunctionCall(
                id = callId,
                name = fnName,
                arguments = step.arguments ?: JsonObject(emptyMap()),
            )
        }
    }

    fun extractRawCitations(): List<GroundingCitation> {
        val stepList = steps ?: return emptyList()
        val citations = mutableListOf<GroundingCitation>()
        for (step in stepList) {
            if (step.type != "model_output") continue
            val allAnnotations =
                buildList {
                    step.content?.forEach { contentItem ->
                        contentItem.annotations?.let(::addAll)
                    }
                    step.annotations?.let(::addAll)
                }
            for (annotation in allAnnotations) {
                if (annotation.type != "url_citation") continue
                val rawUrl = annotation.effectiveUrl?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val rawTitle = annotation.effectiveTitle?.trim()?.takeIf { it.isNotBlank() } ?: rawUrl
                citations.add(GroundingCitation(title = rawTitle, url = rawUrl))
            }
        }
        return citations
    }

    fun extractFilteredGroundingCitations(
        excludedUrls: Collection<String> = emptyList(),
        maxCount: Int = MAX_GROUNDING_CITATIONS,
    ): List<GroundingCitation> {
        val excludedNormalized = HashSet<String>()
        for (url in excludedUrls) {
            val normalized = normalizeUrlForComparison(url)
            if (normalized.isNotEmpty()) excludedNormalized.add(normalized)
        }
        for (entry in extractUrlContextResults()) {
            val normalized = normalizeUrlForComparison(entry.retrievedUrl)
            if (normalized.isNotEmpty()) excludedNormalized.add(normalized)
        }

        val seenNormalized = HashSet<String>()
        val filtered = mutableListOf<GroundingCitation>()
        for (citation in extractRawCitations()) {
            val normalized = normalizeUrlForComparison(citation.url)
            if (normalized.isEmpty()) continue
            if (normalized in excludedNormalized) continue
            if (!seenNormalized.add(normalized)) continue
            filtered.add(citation)
            if (filtered.size >= maxCount) break
        }
        return filtered
    }

    companion object {
        const val MAX_GROUNDING_CITATIONS = 3

        private fun extractUrlContextEntryFromObject(
            obj: JsonObject?,
            defaultStatus: String,
        ): UrlContextResultEntry? {
            if (obj == null) return null
            val url =
                ((obj["retrieved_url"] as? JsonPrimitive)?.content ?: (obj["url"] as? JsonPrimitive)?.content)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
            val status =
                (
                    (obj["status"] as? JsonPrimitive)?.content
                        ?: (obj["url_retrieval_status"] as? JsonPrimitive)?.content
                )?.trim()?.takeIf { it.isNotBlank() } ?: defaultStatus
            return UrlContextResultEntry(retrievedUrl = url, status = status)
        }
    }
}

/**
 * Normalizes a URL for comparison by canonicalizing scheme (`http`/`https` -> `https`),
 * lowercasing host, removing default ports (`:80`, `:443`), trimming trailing slashes on path,
 * preserving query parameters, and stripping fragments.
 */
fun normalizeUrlForComparison(rawUrl: String): String = ContextUrlClassifier.normalizeUrlForComparison(rawUrl)
