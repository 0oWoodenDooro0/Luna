package luna.core.ai

import dev.socialpeek.model.PeekPost

/**
 * Reason a Discord message was classified as dialogue noise by [DiscordDialogueContextEngine].
 */
enum class DialogueNoiseReason {
    SYSTEM_MESSAGE,
    COMMAND,
    UNVERIFIED_WEBHOOK,
    THIRD_PARTY_BOT,
    BOT_SUMMARY,
    MISSING_AUTHOR,
    STICKER_ONLY,
    EMPTY_OR_EMOJI_ONLY,
}

/**
 * Resolved speaker identity metadata for a Discord message.
 */
data class DialogueSpeaker(
    val name: String,
    val authorId: String?,
    val isLunaBot: Boolean,
    val isThirdPartyBot: Boolean,
)

/**
 * Result of synchronous noise evaluation for a single Discord [dev.kord.core.entity.Message].
 */
data class DialogueNoiseEvaluation(
    val isNoise: Boolean,
    val reason: DialogueNoiseReason? = null,
    val blankPlaceholder: String? = null,
    val speaker: DialogueSpeaker,
)

/**
 * Result of formatting chronological Discord dialogue messages into transcript turns.
 */
data class FormattedDialogueResult(
    val turns: List<String>,
    val transcript: String,
    val nextImageIndex: Int,
)

/**
 * Result of reverse-chronological URL classification and SocialPeek/YouTube enrichment.
 */
data class DialogueLinkEnrichmentResult(
    val primaryUrl: String? = null,
    val peekTargetUrl: String? = null,
    val resolvedUrl: String? = null,
    val title: String? = null,
    val postContent: String? = null,
    val comments: List<String> = emptyList(),
    val videoUri: String? = null,
    val videoDurationSeconds: Long? = null,
    val durationExceeded: Boolean = false,
    val classifiedUrls: ClassifiedContextUrls = ClassifiedContextUrls(),
    val resolvedPost: PeekPost? = null,
)
