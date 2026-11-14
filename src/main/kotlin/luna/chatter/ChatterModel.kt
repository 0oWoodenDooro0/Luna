package luna.chatter

import luna.core.ai.ChatImageAttachment

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

/**
 * Unified multimodal input payload for [ChatterGenerator].
 *
 * @property transcript Formatted dialogue transcript (including optional enriched link/comments text).
 * @property imageAttachments Discord CDN chat image attachments for multimodal understanding.
 * @property videoUri Optional YouTube video URI when primary URL resolves to YouTube.
 * @property videoDurationSeconds Optional YouTube video duration in seconds for Tiered Video Latency Policy.
 * @property isDirectMention True if the triggering message directly @mentioned Luna.
 * @property title Optional resolved page/video title.
 * @property url Optional resolved clean URL.
 * @property comments Optional extracted social/YouTube audience comments.
 * @property webUrls Deduplicated general public web URLs in the dialogue window (up to 20) for `url_context`.
 */
data class ChatterInput(
    val transcript: String,
    val imageAttachments: List<ChatImageAttachment> = emptyList(),
    val videoUri: String? = null,
    val videoDurationSeconds: Long? = null,
    val isDirectMention: Boolean = false,
    val title: String? = null,
    val url: String? = null,
    val comments: List<String> = emptyList(),
    val webUrls: List<String> = emptyList(),
)
