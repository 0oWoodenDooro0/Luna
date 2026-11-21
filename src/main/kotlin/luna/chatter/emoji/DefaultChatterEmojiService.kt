package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.ReactionEmoji
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import luna.chatter.ChatterConfig
import luna.chatter.ChatterPromptBuilder
import luna.chatter.GuildCustomEmoji
import luna.chatter.toAvailableGuildCustomEmojis
import luna.core.ai.GeminiInteractionClient
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Default implementation of [ChatterEmojiService].
 *
 * When [database] is `null`, SQLite meaning/usage persistence and vision resolution gracefully no-op
 * while in-memory cadence tracking, Application Emoji TTL caching, deterministic prompt pool slicing,
 * and reaction validation remain fully active.
 */
class DefaultChatterEmojiService(
    database: Database? = null,
    visionModel: String? = null,
    interactionClient: GeminiInteractionClient? = null,
    baseUrl: String = ChatterConfig.DEFAULT_BASE_URL,
    apiKey: String = "",
    private val applicationEmojiFetcher: (suspend () -> List<GuildCustomEmoji>)? = null,
    private val clock: Clock = Clock.systemUTC(),
    timeSource: () -> Long = { clock.millis() },
    visionMaxConcurrency: Int = ChatterEmojiVisionResolver.DEFAULT_MAX_CONCURRENCY,
    visionRoundTimeoutMs: Long = ChatterEmojiVisionResolver.DEFAULT_ROUND_TIMEOUT_MS,
    visionFailureCooldownMs: Long = ChatterEmojiVisionResolver.DEFAULT_FAILURE_COOLDOWN_MS,
    applicationEmojiTtlMillis: Long = ApplicationEmojiCache.DEFAULT_TTL_MILLIS,
    cadenceMaxChannels: Int = BoundedChannelEmojiCadenceTracker.DEFAULT_MAX_CAPACITY,
    replyEmojiCooldownTurns: Int = BoundedChannelEmojiCadenceTracker.DEFAULT_REPLY_EMOJI_COOLDOWN_TURNS,
    replyEmojiCooldownWindowMs: Long = BoundedChannelEmojiCadenceTracker.DEFAULT_REPLY_EMOJI_COOLDOWN_WINDOW_MS,
    reactionCooldownMs: Long = BoundedChannelEmojiCadenceTracker.DEFAULT_REACTION_COOLDOWN_MS,
    maxRecentReactions: Int = BoundedChannelEmojiCadenceTracker.DEFAULT_MAX_RECENT_REACTIONS,
    recentReactionWindowMs: Long = BoundedChannelEmojiCadenceTracker.DEFAULT_RECENT_REACTION_WINDOW_MS,
) : ChatterEmojiService {
    private val logger = LoggerFactory.getLogger(DefaultChatterEmojiService::class.java)
    private val sqliteStore: ChatterEmojiSqliteStore? = database?.let { ChatterEmojiSqliteStore(it, clock) }
    private val visionResolver: ChatterEmojiVisionResolver? =
        if (sqliteStore != null && interactionClient != null) {
            ChatterEmojiVisionResolver(
                store = sqliteStore,
                visionModel = visionModel,
                interactionClient = interactionClient,
                baseUrl = baseUrl,
                apiKey = apiKey,
                clock = clock,
                maxConcurrency = visionMaxConcurrency,
                roundTimeoutMs = visionRoundTimeoutMs,
                failureCooldownMs = visionFailureCooldownMs,
            ).takeIf { it.isEnabled }
        } else {
            null
        }
    private val applicationEmojiCache =
        ApplicationEmojiCache(
            ttlMillis = applicationEmojiTtlMillis,
            clock = clock,
        )
    private val cadenceTracker =
        BoundedChannelEmojiCadenceTracker(
            maxEntries = cadenceMaxChannels,
            replyCooldownTurns = replyEmojiCooldownTurns,
            replyCooldownWindowMs = replyEmojiCooldownWindowMs,
            reactionCooldownMs = reactionCooldownMs,
            maxRecentReactions = maxRecentReactions,
            recentReactionWindowMs = recentReactionWindowMs,
            timeSource = timeSource,
        )

    override suspend fun preScanStartup(kord: Kord) {
        coroutineScope {
            launch { preScanStartupGuilds(kord) }
            launch { preScanStartupApplicationEmojis(kord) }
        }
    }

    override suspend fun preScanGuild(
        guildId: Snowflake?,
        emojis: List<GuildCustomEmoji>,
    ) {
        if (emojis.isEmpty()) return
        resolveOrSyncMetadata(
            targets = toVisionTargets(emojis),
            contextLabel = "pre-scan guild $guildId",
            useRoundTimeout = false,
            roundBudget = null,
        )
    }

    override suspend fun recordMessageUsage(
        guildId: Snowflake?,
        content: String,
    ) {
        recordMessageUsageInternal(
            guildId = guildId,
            content = content,
            extraKnownAppIds = emptySet(),
        )
    }

    override suspend fun recordReactionUsage(
        guildId: Snowflake?,
        emoji: ReactionEmoji,
    ) {
        recordReactionUsageInternal(
            guildId = guildId,
            emoji = emoji,
            extraKnownAppIds = emptySet(),
        )
    }

    override fun beginTurn(
        channelId: Snowflake,
        guildId: Snowflake?,
        guildEmojisProvider: suspend () -> List<GuildCustomEmoji>,
        applicationEmojisProvider: (suspend () -> List<GuildCustomEmoji>)?,
        preScanScope: CoroutineScope?,
    ): ChatterEmojiTurn =
        TurnImpl(
            channelId = channelId,
            guildId = guildId,
            guildEmojisProvider = guildEmojisProvider,
            applicationEmojisProvider = applicationEmojisProvider ?: applicationEmojiFetcher,
            preScanScope = preScanScope,
        )

    private inner class TurnImpl(
        private val channelId: Snowflake,
        private val guildId: Snowflake?,
        private val guildEmojisProvider: suspend () -> List<GuildCustomEmoji>,
        private val applicationEmojisProvider: (suspend () -> List<GuildCustomEmoji>)?,
        private val preScanScope: CoroutineScope?,
    ) : ChatterEmojiTurn {
        private val roundBudget = EmojiVisionTurnBudget()
        private var lastPreparedGuildEmojis: List<GuildCustomEmoji> = emptyList()
        private var lastPreparedAppEmojis: List<GuildCustomEmoji> = emptyList()

        override suspend fun annotateTranscript(transcript: String): String {
            val uniqueTargets = extractTranscriptTargets(transcript)
            if (uniqueTargets.isEmpty()) return transcript
            resolveOrSyncMetadata(
                targets = uniqueTargets,
                contextLabel = "transcript",
                useRoundTimeout = true,
                roundBudget = roundBudget,
            )
            val meaningsById = queryMeaningMap(uniqueTargets.map { it.id }, contextLabel = "transcript")
            return ChatterEmojiTranscriptAnnotator.annotate(transcript, meaningsById)
        }

        override suspend fun prepareReplyEmojis(transcript: String): PreparedPromptEmojis {
            val allowReplyEmojis = cadenceTracker.shouldAllowReplyCustomEmojis(channelId)
            if (!allowReplyEmojis) {
                logger.debug("Skipping reply emoji pools for channel {} due to reply emoji cadence cooldown", channelId)
                val annotatedTranscript = annotateTranscript(transcript)
                currentCoroutineContext().ensureActive()
                lastPreparedGuildEmojis = emptyList()
                lastPreparedAppEmojis = emptyList()
                return PreparedPromptEmojis(
                    transcript = annotatedTranscript,
                    guildEmojis = emptyList(),
                    applicationEmojis = emptyList(),
                )
            }

            return preparePoolsAndTranscript(
                transcript = transcript,
                guildLimit = ChatterPromptBuilder.MAX_REPLY_PROMPT_GUILD_EMOJIS,
                appLimit = ChatterPromptBuilder.MAX_REPLY_PROMPT_APP_EMOJIS,
                fallbackDescription = "degrading to emoji-free text reply",
                includeRecentReactions = false,
            )
        }

        override fun isReactionOnCooldown(): Boolean = cadenceTracker.isReactionOnCooldown(channelId)

        override suspend fun prepareReactionEmojis(): PreparedPromptEmojis =
            preparePoolsAndTranscript(
                transcript = "",
                guildLimit = ChatterPromptBuilder.MAX_REACTION_PROMPT_GUILD_EMOJIS,
                appLimit = ChatterPromptBuilder.MAX_REACTION_PROMPT_APP_EMOJIS,
                fallbackDescription = "continuing with Unicode reactions only",
                includeRecentReactions = true,
            )

        override fun resolveReaction(rawModelOutput: String?): ReactionResolution =
            ChatterReactionEmojiValidator.resolve(
                raw = rawModelOutput,
                guildEmojis = lastPreparedGuildEmojis,
                applicationEmojis = lastPreparedAppEmojis,
            )

        override suspend fun recordDeliveredReply(segments: List<String>) {
            val deliveredOutput = segments.joinToString("\n")
            cadenceTracker.recordDeliveredReply(channelId, deliveredOutput)
            val extraAppIds = lastPreparedAppEmojis.map { it.id.toString() }.toSet()
            recordMessageUsageInternal(
                guildId = null,
                content = deliveredOutput,
                extraKnownAppIds = extraAppIds,
            )
        }

        override suspend fun recordDeliveredReaction(resolved: ReactionResolution.Valid) {
            cadenceTracker.recordDeliveredReaction(channelId, resolved.normalizedToken)
            val extraAppIds = lastPreparedAppEmojis.map { it.id.toString() }.toSet()
            recordReactionUsageInternal(
                guildId = null,
                emoji = resolved.reactionEmoji,
                extraKnownAppIds = extraAppIds,
            )
        }

        private suspend fun preparePoolsAndTranscript(
            transcript: String,
            guildLimit: Int,
            appLimit: Int,
            fallbackDescription: String,
            includeRecentReactions: Boolean,
        ): PreparedPromptEmojis {
            val availableAppEmojis = resolveApplicationEmojis(applicationEmojisProvider, preScanScope)
            currentCoroutineContext().ensureActive()
            val availableGuildEmojis = resolveGuildEmojis(guildEmojisProvider, fallbackDescription)
            currentCoroutineContext().ensureActive()

            val boundedApp =
                selectBoundedEmojis(
                    scopeKey = ChatterEmojiService.APP_EMOJI_USAGE_SCOPE,
                    availableEmojis = availableAppEmojis,
                    limit = appLimit,
                    scopeLabel = "application emojis (${ChatterEmojiService.APP_EMOJI_USAGE_SCOPE})",
                )
            val boundedGuild =
                selectBoundedEmojis(
                    scopeKey = guildId?.toString(),
                    availableEmojis = availableGuildEmojis,
                    limit = guildLimit,
                    scopeLabel = "guild $guildId",
                )

            val transcriptTargets = extractTranscriptTargets(transcript)
            val combinedTargets =
                (transcriptTargets + toVisionTargets(boundedApp) + toVisionTargets(boundedGuild))
                    .associateBy { it.id }
                    .values
                    .toList()

            resolveOrSyncMetadata(
                targets = combinedTargets,
                contextLabel = "turn prompt (guild $guildId)",
                useRoundTimeout = true,
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

            val preparedGuild = applyMeanings(boundedGuild, meaningsById)
            val preparedApp = applyMeanings(boundedApp, meaningsById)
            lastPreparedGuildEmojis = preparedGuild
            lastPreparedAppEmojis = preparedApp
            currentCoroutineContext().ensureActive()

            return PreparedPromptEmojis(
                transcript = annotatedTranscript,
                guildEmojis = preparedGuild,
                applicationEmojis = preparedApp,
                recentReactions = if (includeRecentReactions) cadenceTracker.recentReactions(channelId) else emptyList(),
            )
        }
    }

    private suspend fun preScanStartupGuilds(kord: Kord) {
        try {
            coroutineScope {
                kord.guilds.collect { guild ->
                    val guildId = guild.id
                    launch {
                        runCatching {
                            val availableEmojis = guild.emojis.toList().toAvailableGuildCustomEmojis()
                            preScanGuild(guildId = guildId, emojis = availableEmojis)
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            logger.warn("Failed to pre-scan custom emojis for guild {} on startup: {}", guildId, e.message)
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

    private suspend fun preScanStartupApplicationEmojis(kord: Kord) {
        val fetcher = applicationEmojiFetcher ?: { kord.emojis.toList().toAvailableGuildCustomEmojis() }
        val availableEmojis = applicationEmojiCache.refresh(fetcher = fetcher)
        if (availableEmojis.isEmpty()) return
        runCatching {
            preScanApplicationEmojis(availableEmojis)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            logger.warn("Failed to pre-scan {} Application Emoji(s) on startup: {}", availableEmojis.size, e.message)
        }
    }

    private suspend fun preScanApplicationEmojis(emojis: List<GuildCustomEmoji>) {
        if (emojis.isEmpty()) return
        resolveOrSyncMetadata(
            targets = toVisionTargets(emojis),
            contextLabel = "pre-scan application emojis",
            useRoundTimeout = false,
            roundBudget = null,
        )
    }

    private suspend fun resolveApplicationEmojis(
        fetcher: (suspend () -> List<GuildCustomEmoji>)?,
        preScanScope: CoroutineScope?,
    ): List<GuildCustomEmoji> {
        if (fetcher == null) return emptyList()
        return applicationEmojiCache.getOrRefresh(
            fetcher = fetcher,
            onNewEmojisDiscovered = { newlyDiscovered ->
                if (preScanScope == null) {
                    logger.debug(
                        "Skipping background pre-scan for {} newly discovered Application Emoji(s); no active background scope",
                        newlyDiscovered.size,
                    )
                } else {
                    preScanScope.launch {
                        runCatching {
                            preScanApplicationEmojis(newlyDiscovered)
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            logger.warn(
                                "Failed to pre-scan {} newly discovered Application Emoji(s) after TTL refresh: {}",
                                newlyDiscovered.size,
                                e.message,
                            )
                        }
                    }
                }
            },
        )
    }

    private suspend fun resolveGuildEmojis(
        provider: suspend () -> List<GuildCustomEmoji>,
        fallbackDescription: String,
    ): List<GuildCustomEmoji> =
        try {
            provider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to resolve guild emojis; {}: {}", fallbackDescription, e.message)
            emptyList()
        }

    private suspend fun selectBoundedEmojis(
        scopeKey: String?,
        availableEmojis: List<GuildCustomEmoji>,
        limit: Int,
        scopeLabel: String,
    ): List<GuildCustomEmoji> {
        val normalizedLimit = limit.coerceAtLeast(0)
        if (normalizedLimit == 0 || availableEmojis.isEmpty()) return emptyList()

        val store = sqliteStore ?: return availableEmojis.take(normalizedLimit)
        val usageById =
            if (scopeKey != null) {
                runCatching {
                    store.findRollingUsageCounts(scopeKey)
                }.onFailure { e ->
                    if (e is CancellationException) throw e
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
    ): Map<String, String> {
        val store = sqliteStore ?: return emptyMap()
        return runCatching {
            store.findMeaningMap(emojiIds.distinct())
        }.onFailure { e ->
            if (e is CancellationException) throw e
            logger.warn(
                "Failed to query cached Custom Emoji Meaning for {}; degrading to bare emoji tokens: {}",
                contextLabel,
                e.message,
            )
        }.getOrDefault(emptyMap())
    }

    private suspend fun recordMessageUsageInternal(
        guildId: Snowflake?,
        content: String,
        extraKnownAppIds: Set<String>,
    ) {
        if (content.isBlank()) return
        val store = sqliteStore ?: return
        val tokens = ChatterEmojiTranscriptAnnotator.extractEmojiTokens(content)
        if (tokens.isEmpty()) return

        if (guildId != null) {
            runCatching {
                store.recordUsage(guildId.toString(), tokens.map { it.id })
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Failed to record custom emoji message usage for guild {}: {}", guildId, e.message)
            }
        }

        val knownAppIds = applicationEmojiCache.knownIds() + extraKnownAppIds
        if (knownAppIds.isNotEmpty()) {
            val appEmojiIds = tokens.map { it.id }.filter { it in knownAppIds }
            if (appEmojiIds.isNotEmpty()) {
                runCatching {
                    store.recordUsage(ChatterEmojiService.APP_EMOJI_USAGE_SCOPE, appEmojiIds)
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    logger.warn(
                        "Failed to record Application Emoji message usage under {}: {}",
                        ChatterEmojiService.APP_EMOJI_USAGE_SCOPE,
                        e.message,
                    )
                }
            }
        }
    }

    private suspend fun recordReactionUsageInternal(
        guildId: Snowflake?,
        emoji: ReactionEmoji,
        extraKnownAppIds: Set<String>,
    ) {
        val store = sqliteStore ?: return
        val customEmoji = emoji as? ReactionEmoji.Custom ?: return
        val emojiId = customEmoji.id.toString()

        if (guildId != null) {
            runCatching {
                store.recordUsage(guildId.toString(), listOf(emojiId))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn(
                    "Failed to record custom emoji reaction usage for guild {} and emoji {}: {}",
                    guildId,
                    customEmoji.id,
                    e.message,
                )
            }
        }

        val knownAppIds = applicationEmojiCache.knownIds() + extraKnownAppIds
        if (emojiId in knownAppIds) {
            runCatching {
                store.recordUsage(ChatterEmojiService.APP_EMOJI_USAGE_SCOPE, listOf(emojiId))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn(
                    "Failed to record Application Emoji reaction usage under {} for emoji {}: {}",
                    ChatterEmojiService.APP_EMOJI_USAGE_SCOPE,
                    customEmoji.id,
                    e.message,
                )
            }
        }
    }

    private suspend fun resolveOrSyncMetadata(
        targets: List<EmojiVisionRequest>,
        contextLabel: String,
        useRoundTimeout: Boolean,
        roundBudget: EmojiVisionTurnBudget?,
    ) {
        if (targets.isEmpty()) return
        val store = sqliteStore ?: return
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

        runCatching {
            store.syncMetadataIfChanged(targets)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            logger.warn("Failed to sync emoji metadata for {}: {}", contextLabel, e.message)
        }
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
}
