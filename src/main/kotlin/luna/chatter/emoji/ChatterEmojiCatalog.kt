package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.ReactionEmoji
import kotlinx.coroutines.CancellationException
import luna.chatter.ChatterPromptBuilder
import luna.chatter.GuildCustomEmoji
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks a single conversation turn's shared vision-resolution budget across pipeline stages
 * (`Participation Decision`, reply generation, and `Silent Reaction Path`) so a turn never
 * chains sequential round timeouts or retries already-attempted emojis, while ensuring
 * non-vision latency (such as LLM decision calls or reply context collection) never starves
 * the vision round budget.
 */
class EmojiVisionTurnBudget {
    private val attemptedEmojiIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val consumedVisionNanos: AtomicLong = AtomicLong(0L)

    fun remainingTimeoutMs(roundTimeoutMs: Long): Long {
        if (roundTimeoutMs <= 0L) return 0L
        val elapsedMs = (consumedVisionNanos.get() / 1_000_000L).coerceAtLeast(0L)
        return (roundTimeoutMs - elapsedMs).coerceAtLeast(0L)
    }

    fun recordVisionElapsedNanos(elapsedNanos: Long) {
        if (elapsedNanos > 0L) {
            consumedVisionNanos.addAndGet(elapsedNanos)
        }
    }

    fun filterUnattempted(targets: List<EmojiVisionRequest>): List<EmojiVisionRequest> = targets.filter { it.id !in attemptedEmojiIds }

    fun markAttempted(targets: List<EmojiVisionRequest>) {
        targets.forEach { attemptedEmojiIds.add(it.id) }
    }
}

/**
 * Combined result of preparing the annotated dialogue transcript, ranked guild emoji pool,
 * and ranked Application Emoji pool for a single turn.
 */
data class PreparedPromptEmojis(
    val transcript: String,
    val guildEmojis: List<GuildCustomEmoji>,
    val applicationEmojis: List<GuildCustomEmoji> = emptyList(),
)

/**
 * Seam coordinating custom emoji semantic enrichment and selection pool preparation
 * for the Chatter pipeline.
 *
 * Downstream features (on-demand vision resolution, 30-day rolling usage ranking, and background pre-scan)
 * plug in behind this interface without requiring structural changes to [luna.chatter.ChatterService].
 */
interface ChatterEmojiCatalog {
    suspend fun prepareTranscript(
        transcript: String,
        roundBudget: EmojiVisionTurnBudget? = null,
    ): String = transcript

    suspend fun prepareGuildEmojiPool(
        guildId: Snowflake?,
        availableGuildEmojis: List<GuildCustomEmoji>,
        limit: Int = ChatterPromptBuilder.MAX_PROMPT_GUILD_EMOJIS,
        roundBudget: EmojiVisionTurnBudget? = null,
    ): List<GuildCustomEmoji> = availableGuildEmojis.take(limit.coerceAtLeast(0))

    suspend fun prepareApplicationEmojiPool(
        availableApplicationEmojis: List<GuildCustomEmoji>,
        limit: Int = ChatterPromptBuilder.MAX_REACTION_PROMPT_APP_EMOJIS,
        roundBudget: EmojiVisionTurnBudget? = null,
    ): List<GuildCustomEmoji> = availableApplicationEmojis.take(limit.coerceAtLeast(0))

    suspend fun preparePromptEmojis(
        transcript: String,
        guildId: Snowflake?,
        availableGuildEmojis: List<GuildCustomEmoji>,
        limit: Int = ChatterPromptBuilder.MAX_PROMPT_GUILD_EMOJIS,
        roundBudget: EmojiVisionTurnBudget? = null,
        availableApplicationEmojis: List<GuildCustomEmoji> = emptyList(),
        applicationEmojiLimit: Int = ChatterPromptBuilder.MAX_REACTION_PROMPT_APP_EMOJIS,
    ): PreparedPromptEmojis {
        val preparedAppPool =
            prepareApplicationEmojiPool(
                availableApplicationEmojis = availableApplicationEmojis,
                limit = applicationEmojiLimit,
                roundBudget = roundBudget,
            )
        val preparedPool =
            prepareGuildEmojiPool(
                guildId = guildId,
                availableGuildEmojis = availableGuildEmojis,
                limit = limit,
                roundBudget = roundBudget,
            )
        val preparedTranscript =
            prepareTranscript(
                transcript = transcript,
                roundBudget = roundBudget,
            )
        return PreparedPromptEmojis(
            transcript = preparedTranscript,
            guildEmojis = preparedPool,
            applicationEmojis = preparedAppPool,
        )
    }

    suspend fun recordMessageUsage(
        guildId: Snowflake?,
        content: String,
        knownApplicationEmojiIds: Set<String> = emptySet(),
    ) {}

    suspend fun recordReactionUsage(
        guildId: Snowflake?,
        emoji: ReactionEmoji,
        knownApplicationEmojiIds: Set<String> = emptySet(),
    ) {}

    suspend fun preScanGuildEmojis(
        guildId: Snowflake?,
        emojis: List<GuildCustomEmoji>,
    ) {}

    suspend fun preScanApplicationEmojis(emojis: List<GuildCustomEmoji>) {}

    companion object {
        /**
         * Fixed global sentinel scope key for 30-day rolling `Emoji Usage Ranking` of Discord Application Emojis.
         */
        const val APP_EMOJI_USAGE_SCOPE: String = "__app__"
    }
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
    private val usageRepository: EmojiUsageRankingRepository? = null,
    private val visionResolver: CustomEmojiVisionResolver? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ChatterEmojiCatalog {
    private val logger = LoggerFactory.getLogger(DefaultChatterEmojiCatalog::class.java)

    override suspend fun prepareTranscript(
        transcript: String,
        roundBudget: EmojiVisionTurnBudget?,
    ): String {
        val uniqueTargets = extractTranscriptTargets(transcript)
        if (uniqueTargets.isEmpty()) return transcript

        resolveOrSyncMetadata(uniqueTargets, contextLabel = "transcript", roundBudget = roundBudget)
        val meaningsById = queryMeaningMap(uniqueTargets.map { it.id }, contextLabel = "transcript")
        return ChatterEmojiTranscriptAnnotator.annotate(transcript, meaningsById)
    }

    override suspend fun prepareGuildEmojiPool(
        guildId: Snowflake?,
        availableGuildEmojis: List<GuildCustomEmoji>,
        limit: Int,
        roundBudget: EmojiVisionTurnBudget?,
    ): List<GuildCustomEmoji> {
        val bounded = selectBoundedEmojis(guildId?.toString(), availableGuildEmojis, limit, scopeLabel = "guild $guildId")
        if (bounded.isEmpty()) return emptyList()

        val targets = toVisionTargets(bounded)
        resolveOrSyncMetadata(targets, contextLabel = "guild $guildId", roundBudget = roundBudget)
        val meaningsById = queryMeaningMap(targets.map { it.id }, contextLabel = "guild $guildId")
        return applyMeanings(bounded, meaningsById)
    }

    override suspend fun prepareApplicationEmojiPool(
        availableApplicationEmojis: List<GuildCustomEmoji>,
        limit: Int,
        roundBudget: EmojiVisionTurnBudget?,
    ): List<GuildCustomEmoji> {
        val bounded =
            selectBoundedEmojis(
                scopeKey = ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE,
                availableEmojis = availableApplicationEmojis,
                limit = limit,
                scopeLabel = "application emojis (${ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE})",
            )
        if (bounded.isEmpty()) return emptyList()

        val targets = toVisionTargets(bounded)
        resolveOrSyncMetadata(targets, contextLabel = "application emojis", roundBudget = roundBudget)
        val meaningsById = queryMeaningMap(targets.map { it.id }, contextLabel = "application emojis")
        return applyMeanings(bounded, meaningsById)
    }

    override suspend fun preparePromptEmojis(
        transcript: String,
        guildId: Snowflake?,
        availableGuildEmojis: List<GuildCustomEmoji>,
        limit: Int,
        roundBudget: EmojiVisionTurnBudget?,
        availableApplicationEmojis: List<GuildCustomEmoji>,
        applicationEmojiLimit: Int,
    ): PreparedPromptEmojis {
        val boundedApp =
            selectBoundedEmojis(
                scopeKey = ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE,
                availableEmojis = availableApplicationEmojis,
                limit = applicationEmojiLimit,
                scopeLabel = "application emojis (${ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE})",
            )
        val boundedGuild =
            selectBoundedEmojis(
                scopeKey = guildId?.toString(),
                availableEmojis = availableGuildEmojis,
                limit = limit,
                scopeLabel = "guild $guildId",
            )
        val transcriptTargets = extractTranscriptTargets(transcript)
        val appPoolTargets = toVisionTargets(boundedApp)
        val guildPoolTargets = toVisionTargets(boundedGuild)
        val combinedTargets = (transcriptTargets + appPoolTargets + guildPoolTargets).associateBy { it.id }.values.toList()

        resolveOrSyncMetadata(
            targets = combinedTargets,
            contextLabel = "turn prompt (guild $guildId)",
            roundBudget = roundBudget,
        )

        val meaningsById =
            if (combinedTargets.isEmpty()) {
                emptyMap()
            } else {
                queryMeaningMap(combinedTargets.map { it.id }, contextLabel = "turn prompt (guild $guildId)")
            }

        val annotatedTranscript =
            if (transcriptTargets.isEmpty()) {
                transcript
            } else {
                ChatterEmojiTranscriptAnnotator.annotate(transcript, meaningsById)
            }
        return PreparedPromptEmojis(
            transcript = annotatedTranscript,
            guildEmojis = applyMeanings(boundedGuild, meaningsById),
            applicationEmojis = applyMeanings(boundedApp, meaningsById),
        )
    }

    private fun extractTranscriptTargets(transcript: String): List<EmojiVisionRequest> =
        ChatterEmojiTranscriptAnnotator
            .extractEmojiTokens(transcript)
            .associateBy { it.id }
            .values
            .map { token ->
                EmojiVisionRequest(
                    id = token.id,
                    name = token.name,
                    isAnimated = token.isAnimated,
                )
            }

    private fun toVisionTargets(emojis: List<GuildCustomEmoji>): List<EmojiVisionRequest> =
        emojis.map { emoji ->
            EmojiVisionRequest(
                id = emoji.id.toString(),
                name = emoji.name,
                isAnimated = emoji.isAnimated,
            )
        }

    private fun applyMeanings(
        emojis: List<GuildCustomEmoji>,
        meaningsById: Map<String, String>,
    ): List<GuildCustomEmoji> =
        emojis.map { emoji ->
            val meaning = meaningsById[emoji.id.toString()]
            if (meaning != null) emoji.copy(meaning = meaning) else emoji
        }

    private suspend fun selectBoundedEmojis(
        scopeKey: String?,
        availableEmojis: List<GuildCustomEmoji>,
        limit: Int,
        scopeLabel: String,
    ): List<GuildCustomEmoji> {
        val normalizedLimit = limit.coerceAtLeast(0)
        if (normalizedLimit == 0 || availableEmojis.isEmpty()) return emptyList()

        val usageRepo = usageRepository ?: return availableEmojis.take(normalizedLimit)
        val usageById =
            if (scopeKey != null) {
                runCatching {
                    usageRepo.findRollingUsageCounts(scopeKey)
                }.onFailure { e ->
                    logger.warn(
                        "Failed to query 30-day Emoji Usage Ranking for {}; degrading to deterministic order: {}",
                        scopeLabel,
                        e.message,
                    )
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }

        return availableEmojis
            .sortedWith(
                compareByDescending<GuildCustomEmoji> { usageById[it.id.toString()] ?: 0L }
                    .thenBy { it.name }
                    .thenBy { it.id.value },
            ).take(normalizedLimit)
    }

    private suspend fun queryMeaningMap(
        emojiIds: List<String>,
        contextLabel: String,
    ): Map<String, String> =
        runCatching {
            meaningRepository.findMeaningMap(emojiIds.distinct())
        }.onFailure { e ->
            logger.warn(
                "Failed to query cached Custom Emoji Meaning for {}; degrading to bare emoji tokens: {}",
                contextLabel,
                e.message,
            )
        }.getOrDefault(emptyMap())

    override suspend fun recordMessageUsage(
        guildId: Snowflake?,
        content: String,
        knownApplicationEmojiIds: Set<String>,
    ) {
        if (content.isBlank()) return
        val usageRepo = usageRepository ?: return
        val tokens = ChatterEmojiTranscriptAnnotator.extractEmojiTokens(content)
        if (tokens.isEmpty()) return

        if (guildId != null) {
            runCatching {
                usageRepo.recordUsage(guildId.toString(), tokens.map { it.id })
            }.onFailure { e ->
                logger.warn(
                    "Failed to record custom emoji message usage for guild {}: {}",
                    guildId,
                    e.message,
                )
            }
        }

        if (knownApplicationEmojiIds.isNotEmpty()) {
            val appEmojiIds = tokens.map { it.id }.filter { it in knownApplicationEmojiIds }
            if (appEmojiIds.isNotEmpty()) {
                runCatching {
                    usageRepo.recordUsage(ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE, appEmojiIds)
                }.onFailure { e ->
                    logger.warn(
                        "Failed to record Application Emoji message usage under {}: {}",
                        ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE,
                        e.message,
                    )
                }
            }
        }
    }

    override suspend fun recordReactionUsage(
        guildId: Snowflake?,
        emoji: ReactionEmoji,
        knownApplicationEmojiIds: Set<String>,
    ) {
        val usageRepo = usageRepository ?: return
        val customEmoji = emoji as? ReactionEmoji.Custom ?: return
        val emojiId = customEmoji.id.toString()

        if (guildId != null) {
            runCatching {
                usageRepo.recordUsage(guildId.toString(), listOf(emojiId))
            }.onFailure { e ->
                logger.warn(
                    "Failed to record custom emoji reaction usage for guild {} and emoji {}: {}",
                    guildId,
                    customEmoji.id,
                    e.message,
                )
            }
        }

        if (emojiId in knownApplicationEmojiIds) {
            runCatching {
                usageRepo.recordUsage(ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE, listOf(emojiId))
            }.onFailure { e ->
                logger.warn(
                    "Failed to record Application Emoji reaction usage under {} for emoji {}: {}",
                    ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE,
                    customEmoji.id,
                    e.message,
                )
            }
        }
    }

    override suspend fun preScanGuildEmojis(
        guildId: Snowflake?,
        emojis: List<GuildCustomEmoji>,
    ) {
        if (emojis.isEmpty()) return
        val targets =
            emojis.map { emoji ->
                EmojiVisionRequest(
                    id = emoji.id.toString(),
                    name = emoji.name,
                    isAnimated = emoji.isAnimated,
                )
            }
        resolveOrSyncMetadata(
            targets = targets,
            contextLabel = "pre-scan guild $guildId",
            useRoundTimeout = false,
        )
    }

    override suspend fun preScanApplicationEmojis(emojis: List<GuildCustomEmoji>) {
        if (emojis.isEmpty()) return
        resolveOrSyncMetadata(
            targets = toVisionTargets(emojis),
            contextLabel = "pre-scan application emojis",
            useRoundTimeout = false,
        )
    }

    private suspend fun resolveOrSyncMetadata(
        targets: List<EmojiVisionRequest>,
        contextLabel: String,
        useRoundTimeout: Boolean = true,
        roundBudget: EmojiVisionTurnBudget? = null,
    ) {
        if (targets.isEmpty()) return
        val resolver = visionResolver
        if (resolver != null) {
            val unattempted = roundBudget?.filterUnattempted(targets) ?: targets
            val effectiveTimeoutMs =
                if (!useRoundTimeout) {
                    null
                } else if (roundBudget != null) {
                    roundBudget.remainingTimeoutMs(resolver.roundTimeoutMs)
                } else {
                    resolver.roundTimeoutMs
                }

            if (unattempted.isNotEmpty() && (effectiveTimeoutMs == null || effectiveTimeoutMs > 0L)) {
                roundBudget?.markAttempted(unattempted)
                val callStartedAtNanos = System.nanoTime()
                try {
                    resolver.resolveMissing(unattempted, timeoutMs = effectiveTimeoutMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(
                        "Custom Emoji Meaning vision resolution failed for {}; degrading gracefully: {}",
                        contextLabel,
                        e.message,
                    )
                } finally {
                    roundBudget?.recordVisionElapsedNanos(System.nanoTime() - callStartedAtNanos)
                }
                return
            }

            if (unattempted.isNotEmpty() && effectiveTimeoutMs != null && effectiveTimeoutMs <= 0L) {
                logger.warn(
                    "Skipping on-demand Custom Emoji Meaning vision resolution for {} ({} emoji(s)); turn round budget exhausted",
                    contextLabel,
                    unattempted.size,
                )
            }
        }

        // Sync observed emoji names/animation metadata for already-cached rows when vision is not invoked.
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
