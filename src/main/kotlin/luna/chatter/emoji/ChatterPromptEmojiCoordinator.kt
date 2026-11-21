package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import luna.chatter.ChatterChannelPort
import luna.chatter.ChatterPromptBuilder
import luna.chatter.GuildCustomEmoji
import luna.chatter.toAvailableGuildCustomEmojis
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Cohesive coordinator encapsulating prompt custom emoji pool assembly, startup guild pre-scan,
 * and post-delivery Application Emoji usage recording for [luna.chatter.ChatterService].
 */
internal class ChatterPromptEmojiCoordinator(
    private val channelPort: ChatterChannelPort,
    private val emojiCatalog: ChatterEmojiCatalog,
    private val applicationEmojiCache: ApplicationEmojiCache,
    private val logger: Logger = LoggerFactory.getLogger(ChatterPromptEmojiCoordinator::class.java),
) {
    suspend fun preScanStartupGuilds(kord: Kord) {
        try {
            coroutineScope {
                kord.guilds.collect { guild ->
                    val guildId = guild.id
                    launch {
                        runCatching {
                            val availableEmojis = guild.emojis.toList().toAvailableGuildCustomEmojis()
                            emojiCatalog.preScanGuildEmojis(guildId = guildId, emojis = availableEmojis)
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            logger.warn(
                                "Failed to pre-scan custom emojis for guild {} on startup: {}",
                                guildId,
                                e.message,
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to enumerate guilds for startup custom emoji pre-scan: {}", e.message)
        }
    }

    suspend fun preparePromptEmojis(
        message: Message,
        guildId: Snowflake?,
        transcript: String?,
        fallbackDescription: String,
        roundBudget: EmojiVisionTurnBudget,
        preScanScope: CoroutineScope?,
        guildLimit: Int = ChatterPromptBuilder.MAX_REACTION_PROMPT_GUILD_EMOJIS,
        applicationEmojiLimit: Int = ChatterPromptBuilder.MAX_REACTION_PROMPT_APP_EMOJIS,
        includeEmojiPools: Boolean = true,
    ): PreparedPromptEmojis {
        if (!includeEmojiPools) {
            logger.debug("Skipping reply emoji pools for channel {} due to reply emoji cadence cooldown", message.channelId)
            val annotatedTranscript = transcript?.let { emojiCatalog.prepareTranscript(transcript = it, roundBudget = roundBudget) }.orEmpty()
            currentCoroutineContext().ensureActive()
            return PreparedPromptEmojis(
                transcript = annotatedTranscript,
                guildEmojis = emptyList(),
                applicationEmojis = emptyList(),
            )
        }

        val availableApplicationEmojis =
            applicationEmojiCache.resolveForMessage(
                message = message,
                channelPort = channelPort,
                emojiCatalog = emojiCatalog,
                preScanScope = preScanScope,
            )
        currentCoroutineContext().ensureActive()
        val availableGuildEmojis = resolveGuildEmojis(message = message, fallbackDescription = fallbackDescription)
        currentCoroutineContext().ensureActive()

        val prepared =
            emojiCatalog.preparePromptEmojis(
                transcript = transcript.orEmpty(),
                guildId = guildId,
                availableGuildEmojis = availableGuildEmojis,
                limit = guildLimit,
                roundBudget = roundBudget,
                availableApplicationEmojis = availableApplicationEmojis,
                applicationEmojiLimit = applicationEmojiLimit,
            )
        currentCoroutineContext().ensureActive()
        return prepared
    }

    suspend fun recordDeliveredReplyUsage(
        deliveredOutput: String,
        preparedApplicationEmojis: List<GuildCustomEmoji>,
    ) {
        val deliveredAppIds = applicationEmojiCache.knownIds() + preparedApplicationEmojis.map { it.id.toString() }
        emojiCatalog.recordMessageUsage(
            guildId = null,
            content = deliveredOutput,
            knownApplicationEmojiIds = deliveredAppIds,
        )
    }

    suspend fun recordDeliveredReactionUsage(
        emoji: ReactionEmoji,
        preparedApplicationEmojis: List<GuildCustomEmoji>,
    ) {
        val deliveredAppIds = applicationEmojiCache.knownIds() + preparedApplicationEmojis.map { it.id.toString() }
        emojiCatalog.recordReactionUsage(
            guildId = null,
            emoji = emoji,
            knownApplicationEmojiIds = deliveredAppIds,
        )
    }

    private suspend fun resolveGuildEmojis(
        message: Message,
        fallbackDescription: String,
    ): List<GuildCustomEmoji> {
        val guildEmojis =
            try {
                channelPort.listGuildCustomEmojis(message).toAvailableGuildCustomEmojis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Failed to resolve guild emojis for message {} in channel {}; {}: {}",
                    message.id,
                    message.channelId,
                    fallbackDescription,
                    e.message,
                )
                return emptyList()
            }
        if (guildEmojis.isEmpty()) {
            logger.debug("No guild custom emojis available for message {} in channel {}; {}", message.id, message.channelId, fallbackDescription)
        }
        return guildEmojis
    }
}
