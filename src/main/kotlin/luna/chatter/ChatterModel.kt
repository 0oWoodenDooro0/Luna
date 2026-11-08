package luna.chatter

import kotlinx.serialization.Serializable

/**
 * Single turn message in a conversational dialogue.
 *
 * @property author Username or nickname of the message speaker.
 * @property content Textual content of the message.
 */
data class ChatMessage(
    val author: String,
    val content: String,
)

@Serializable
data class ChatterGenerateContentRequest(
    val contents: List<ChatterContent>,
    val systemInstruction: ChatterContent? = null,
    val generationConfig: ChatterGenerationConfig? = null,
)

@Serializable
data class ChatterContent(
    val parts: List<ChatterPart>,
    val role: String? = null,
)

@Serializable
data class ChatterPart(
    val text: String? = null,
)

@Serializable
data class ChatterGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
)

@Serializable
data class ChatterGenerateContentResponse(
    val candidates: List<ChatterCandidate>? = null,
    val promptFeedback: ChatterPromptFeedback? = null,
)

@Serializable
data class ChatterCandidate(
    val content: ChatterContent? = null,
    val finishReason: String? = null,
)

@Serializable
data class ChatterPromptFeedback(
    val blockReason: String? = null,
)
