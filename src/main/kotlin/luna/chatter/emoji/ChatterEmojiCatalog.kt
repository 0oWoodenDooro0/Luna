package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import luna.chatter.ChatterPromptBuilder
import luna.chatter.GuildCustomEmoji
import org.slf4j.LoggerFactory

/**
 * Seam coordinating custom emoji semantic enrichment and guild selection pool preparation
 * for the Chatter pipeline.
 *
 * Downstream features (on-demand vision resolution, 30-day rolling usage ranking, and background pre-scan)
 * plug in behind this interface without requiring structural changes to [luna.chatter.ChatterService].
 */
interface ChatterEmojiCatalog {
    suspend fun prepareTranscript(transcript: String): String = transcript

    suspend fun prepareGuildEmojiPool(
        guildId: Snowflake?,
        availableGuildEmojis: List<GuildCustomEmoji>,
        limit: Int = ChatterPromptBuilder.MAX_PROMPT_GUILD_EMOJIS,
    ): List<GuildCustomEmoji> = availableGuildEmojis.take(limit.coerceAtLeast(0))

    suspend fun recordMessageUsage(
        guildId: Snowflake?,
        content: String,
    ) {}
}

/**
 * Default no-op [ChatterEmojiCatalog] used when database-backed emoji caching is unavailable.
 */
object NoOpChatterEmojiCatalog : ChatterEmojiCatalog

/**
 * Database-backed [ChatterEmojiCatalog] injecting cached `Custom Emoji Meaning` entries into
 * dialogue transcripts and guild selection pools.
 */
class DefaultChatterEmojiCatalog(
    private val meaningRepository: CustomEmojiMeaningRepository,
) : ChatterEmojiCatalog {
    private val logger = LoggerFactory.getLogger(DefaultChatterEmojiCatalog::class.java)

    override suspend fun prepareTranscript(transcript: String): String {
        val tokens = ChatterEmojiTranscriptAnnotator.extractEmojiTokens(transcript)
        if (tokens.isEmpty()) return transcript

        val meaningsById =
            runCatching {
                meaningRepository.findMeaningMap(tokens.map { it.id }.distinct())
            }.onFailure { e ->
                logger.warn(
                    "Failed to query cached Custom Emoji Meaning for transcript annotation; degrading to raw tokens: {}",
                    e.message,
                )
            }.getOrDefault(emptyMap())

        return ChatterEmojiTranscriptAnnotator.annotate(transcript, meaningsById)
    }

    override suspend fun prepareGuildEmojiPool(
        guildId: Snowflake?,
        availableGuildEmojis: List<GuildCustomEmoji>,
        limit: Int,
    ): List<GuildCustomEmoji> {
        val bounded = availableGuildEmojis.take(limit.coerceAtLeast(0))
        if (bounded.isEmpty()) return emptyList()

        val meaningsById =
            runCatching {
                meaningRepository.findMeaningMap(bounded.map { it.id.toString() }.distinct())
            }.onFailure { e ->
                logger.warn(
                    "Failed to query cached Custom Emoji Meaning for guild {}; degrading to bare emoji mentions: {}",
                    guildId,
                    e.message,
                )
            }.getOrDefault(emptyMap())

        return bounded.map { emoji ->
            val meaning = meaningsById[emoji.id.toString()]
            if (meaning != null) emoji.copy(meaning = meaning) else emoji
        }
    }
}
