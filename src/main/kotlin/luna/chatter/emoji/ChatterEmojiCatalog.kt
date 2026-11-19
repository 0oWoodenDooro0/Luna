package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CancellationException
import luna.chatter.ChatterPromptBuilder
import luna.chatter.GuildCustomEmoji
import org.slf4j.LoggerFactory
import java.time.Clock

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
    private val visionResolver: CustomEmojiVisionResolver? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ChatterEmojiCatalog {
    private val logger = LoggerFactory.getLogger(DefaultChatterEmojiCatalog::class.java)

    override suspend fun prepareTranscript(transcript: String): String {
        val tokens = ChatterEmojiTranscriptAnnotator.extractEmojiTokens(transcript)
        if (tokens.isEmpty()) return transcript

        val uniqueTargets =
            tokens
                .associateBy { it.id }
                .values
                .map { token ->
                    EmojiVisionRequest(
                        id = token.id,
                        name = token.name,
                        isAnimated = token.isAnimated,
                    )
                }
        resolveOrSyncMetadata(uniqueTargets, contextLabel = "transcript")

        val meaningsById =
            runCatching {
                meaningRepository.findMeaningMap(uniqueTargets.map { it.id })
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

        val targets =
            bounded.map { emoji ->
                EmojiVisionRequest(
                    id = emoji.id.toString(),
                    name = emoji.name,
                    isAnimated = emoji.isAnimated,
                )
            }
        resolveOrSyncMetadata(targets, contextLabel = "guild $guildId")

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

    private suspend fun resolveOrSyncMetadata(
        targets: List<EmojiVisionRequest>,
        contextLabel: String,
    ) {
        if (targets.isEmpty()) return
        if (visionResolver != null) {
            try {
                visionResolver.resolveMissing(targets)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "On-demand Custom Emoji Meaning vision resolution failed for {}; degrading gracefully: {}",
                    contextLabel,
                    e.message,
                )
            }
            return
        }

        // Even when vision resolution is not wired, keep observed emoji names/animation metadata fresh.
        runCatching {
            val existingById = meaningRepository.findByIds(targets.map { it.id }.distinct())
            for (target in targets) {
                if (existingById.containsKey(target.id)) {
                    meaningRepository.updateMetadataIfChanged(target.id, target.name, target.isAnimated)
                }
            }
        }.onFailure { e ->
            logger.warn(
                "Failed to sync emoji metadata for {} (clock={}): {}",
                contextLabel,
                clock.millis(),
                e.message,
            )
        }
    }
}
