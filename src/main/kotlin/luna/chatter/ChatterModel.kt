package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.ReactionEmoji
import luna.core.ai.ChatImageAttachment

/**
 * Snapshot of an available Discord guild custom emoji for Chatter reaction selection and custom emoji verification.
 *
 * @property id Discord emoji snowflake ID.
 * @property name Discord emoji shortcode name (without colons).
 * @property isAnimated Whether the custom emoji is animated (`<a:name:id>`).
 * @property meaning Optional cached `Custom Emoji Meaning` in Traditional Chinese for prompt injection.
 */
data class GuildCustomEmoji(
    val id: Snowflake,
    val name: String,
    val isAnimated: Boolean = false,
    val meaning: String? = null,
) {
    val mention: String
        get() = if (isAnimated) "<a:$name:$id>" else "<:$name:$id>"

    fun toReactionEmoji(): ReactionEmoji.Custom =
        ReactionEmoji.Custom(
            id = id,
            name = name,
            isAnimated = isAnimated,
        )
}

/**
 * Single turn message in a conversational dialogue.
 *
 * @property author Username or nickname of the message speaker.
 * @property content Textual content of the message.
 * @property authorId Optional Discord user snowflake ID string for speaker attribution.
 * @property isBot Whether the speaker is a third-party Discord bot (`[BOT]` badge; false for Luna and humans).
 */
data class ChatMessage(
    val author: String,
    val content: String,
    val authorId: String? = null,
    val isBot: Boolean = false,
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
 * @property personaState Optional active `Circadian Persona Drift` state injected into the system instruction.
 * @property guildEmojis Available custom emojis from the target Discord guild for reaction/custom emoji verification.
 * @property applicationEmojis Available Discord Application Emojis owned by Luna, prioritized ahead of [guildEmojis].
 * @property guildId Optional Discord guild Snowflake ID for guild-isolated `Long-Term Memory` retrieval.
 * @property channelId Optional Discord channel Snowflake ID for channel-scoped `Long-Term Memory` retrieval.
 * @property authorId Optional triggering author Snowflake ID for default `target_user_id` in `scope="both"`.
 * @property recentReactions Optional list of recently delivered reaction emojis in the target channel for reaction diversity.
 * @property workingMemoryStartMs Optional epoch-millisecond timestamp of the oldest message in the current `Working Memory Window`,
 * used to dynamically align `Long-Term Memory` recency exclusion without a distillation gap.
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
    val personaState: PersonaState? = null,
    val guildEmojis: List<GuildCustomEmoji> = emptyList(),
    val applicationEmojis: List<GuildCustomEmoji> = emptyList(),
    val guildId: String? = null,
    val channelId: String? = null,
    val authorId: String? = null,
    val recentReactions: List<String> = emptyList(),
    val workingMemoryStartMs: Long? = null,
    val allowMentionDisengagement: Boolean = false,
    val coldReaction: Boolean = false,
)
