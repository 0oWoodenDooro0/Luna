package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.ReactionEmoji
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import luna.chatter.GuildCustomEmoji

/**
 * Combined result of preparing the annotated dialogue transcript, ranked guild emoji pool,
 * ranked Application Emoji pool, and recent reaction history for a single turn.
 */
data class PreparedPromptEmojis(
    val transcript: String,
    val guildEmojis: List<GuildCustomEmoji>,
    val applicationEmojis: List<GuildCustomEmoji> = emptyList(),
    val recentReactions: List<String> = emptyList(),
)

/**
 * Validated outcome of resolving a raw `Silent Reaction Path` model output against
 * `Chatter Custom Emoji Policy`.
 */
sealed interface ReactionResolution {
    data object Declined : ReactionResolution

    data class Invalid(
        val reason: String,
    ) : ReactionResolution

    data class Valid(
        val normalizedToken: String,
        val reactionEmoji: ReactionEmoji,
    ) : ReactionResolution
}

/**
 * Turn-scoped handle owning the shared vision round budget, cadence gating, prompt pool preparation,
 * reaction validation, and paired cadence + usage delivery recording for one Chatter turn.
 */
interface ChatterEmojiTurn {
    suspend fun annotateTranscript(transcript: String): String

    suspend fun prepareReplyEmojis(transcript: String): PreparedPromptEmojis

    fun isReactionOnCooldown(): Boolean

    suspend fun prepareReactionEmojis(): PreparedPromptEmojis

    fun resolveReaction(rawModelOutput: String?): ReactionResolution

    suspend fun recordDeliveredReply(segments: List<String>)

    suspend fun recordDeliveredReaction(resolved: ReactionResolution.Valid)
}

/**
 * Deep custom emoji module for `Chatter Pipeline`, owning `Custom Emoji Meaning`, 30-day
 * `Emoji Usage Ranking`, Application Emoji TTL caching, transcript annotation, turn-scoped
 * vision budgeting, cadence gating, and reaction validation.
 */
interface ChatterEmojiService {
    suspend fun preScanStartup(kord: Kord)

    suspend fun preScanGuild(
        guildId: Snowflake?,
        emojis: List<GuildCustomEmoji>,
    )

    suspend fun recordMessageUsage(
        guildId: Snowflake?,
        content: String,
    )

    suspend fun recordReactionUsage(
        guildId: Snowflake?,
        emoji: ReactionEmoji,
    )

    fun beginTurn(
        channelId: Snowflake,
        guildId: Snowflake?,
        guildEmojisProvider: suspend () -> List<GuildCustomEmoji>,
        applicationEmojisProvider: (suspend () -> List<GuildCustomEmoji>)? = null,
        preScanScope: CoroutineScope? = null,
    ): ChatterEmojiTurn

    companion object {
        /**
         * Fixed global sentinel scope key for 30-day rolling `Emoji Usage Ranking` of Discord Application Emojis.
         */
        const val APP_EMOJI_USAGE_SCOPE: String = "__app__"

        fun createDefaultVisionHttpClient(): HttpClient = ChatterEmojiVisionResolver.createDefaultHttpClient()
    }
}
