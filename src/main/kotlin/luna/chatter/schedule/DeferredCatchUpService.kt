package luna.chatter.schedule

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.ChatterChannelPort
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterNoiseFilter
import luna.chatter.KordChatterChannelAdapter
import luna.chatter.PersonaStateProvider
import luna.chatter.emoji.ChatterEmojiService
import luna.chatter.emoji.DefaultChatterEmojiService
import luna.chatter.memory.AffinityTier
import luna.chatter.memory.ChatterMemoryStore
import luna.chatter.resolveAuthorId
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceTrigger
import luna.core.ai.DiscordMentionResolver
import luna.core.ai.NoOpAiTraceRecorder
import luna.core.ai.ResolvedSliceMentions
import luna.core.ai.withAiTraceSession
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.random.Random

/**
 * Timing and stagger-delay port for [DeferredCatchUpService] to keep test seams cohesive.
 */
data class CatchUpTimingPort(
    val clock: Clock = Clock.systemUTC(),
    val randomProvider: (minInclusive: Long, maxInclusive: Long) -> Long = { min, max ->
        Random.nextLong(min, max + 1)
    },
    val staggerDelayFn: suspend (Long) -> Unit = { delay(it) },
)

/**
 * Orchestrates `Deferred Catch-Up` when Luna transitions from `BUSY` or `SLEEPING`
 * into `BREAK` or `AVAILABLE`:
 * 1. Queries pending [CatchUpWatermark] records from [scheduleStore] (filtered to allowed channels).
 * 2. Fetches missed messages per channel bounded by `catchUpMaxMessages` and `catchUpMaxAgeHours`.
 * 3. Runs lightweight LLM triage via [triageClient], prioritizing direct mentions/replies and capping ambient items.
 * 4. Delegates selected `REPLY` / `REACT` deliveries to [DeferredCatchUpDeliveryExecutor] with staggered delays
 *    coordinated through [burstCoordinator] so live incoming chat never collides.
 * 5. Immediately aborts remaining items/channels if [scheduleProvider] transitions back to `BUSY` or `SLEEPING`.
 * 6. Advances/clears the channel's [CatchUpWatermark] once reviewed and records catch-up runs in `AI Trace`.
 */
class DeferredCatchUpService(
    private val scheduleStore: DailyScheduleStore,
    private val scheduleProvider: DailyScheduleProvider,
    private val triageClient: DeferredCatchUpTriageClient,
    private val chatterGenerator: ChatterGenerator,
    private val filter: ChatterNoiseFilter,
    private val contextCollector: ChatterContextCollector,
    private val channelPort: ChatterChannelPort = KordChatterChannelAdapter,
    private val catchUpChannelPort: CatchUpChannelPort,
    private val burstCoordinator: BoundedChannelBurstCoordinator,
    private val personaStateProvider: PersonaStateProvider,
    private val emojiService: ChatterEmojiService = DefaultChatterEmojiService(),
    private val memoryStore: ChatterMemoryStore? = null,
    private val traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
    private val config: ChatterConfig = ChatterConfig(),
    private val timing: CatchUpTimingPort = CatchUpTimingPort(),
) {
    private val logger = LoggerFactory.getLogger(DeferredCatchUpService::class.java)
    private val deliveryExecutor =
        DeferredCatchUpDeliveryExecutor(
            chatterGenerator = chatterGenerator,
            filter = filter,
            contextCollector = contextCollector,
            channelPort = channelPort,
            burstCoordinator = burstCoordinator,
            personaStateProvider = personaStateProvider,
            emojiService = emojiService,
            traceRecorder = traceRecorder,
            config = config,
            scheduleProvider = scheduleProvider,
            randomProvider = timing.randomProvider,
            logger = logger,
        )

    /**
     * Entry point wired to [DailyScheduleCoordinator]'s `onBecomeAvailable` hook.
     */
    suspend fun onBecomeAvailable(
        previous: AvailabilityState,
        current: ScheduleBlock,
    ) {
        if (!config.schedule.enabled || !config.effectiveEnabled) {
            return
        }
        if (isUnavailableNow()) {
            logger.debug("Deferred catch-up skipped immediately: Luna is already unavailable")
            return
        }

        val pendingWatermarks =
            runCatching { scheduleStore.listPendingCatchUps() }
                .onFailure { ex ->
                    logger.warn("DeferredCatchUpService failed to list pending CatchUpWatermarks: {}", ex.message)
                }.getOrNull()
                ?: return

        if (pendingWatermarks.isEmpty()) {
            return
        }

        for (watermark in pendingWatermarks) {
            currentCoroutineContext().ensureActive()
            if (isUnavailableNow()) {
                logger.info(
                    "Deferred catch-up aborted before channel {} because schedule transitioned back to unavailable",
                    watermark.channelId,
                )
                return
            }

            val channelSnowflake = parseSnowflake(watermark.channelId, "channelId") ?: continue
            if (filter.allowedChannels.isNotEmpty() && channelSnowflake !in filter.allowedChannels) {
                logger.debug("Skipping deferred catch-up for disallowed channel {}", watermark.channelId)
                continue
            }

            processChannel(
                watermark = watermark,
                channelId = channelSnowflake,
                previous = previous,
                current = current,
            )
        }
    }

    private suspend fun processChannel(
        watermark: CatchUpWatermark,
        channelId: Snowflake,
        previous: AvailabilityState,
        current: ScheduleBlock,
    ) {
        val firstPendingId = parseSnowflake(watermark.firstPendingMessageId, "firstPendingMessageId")
        if (firstPendingId == null) {
            clearWatermarkSafely(watermark.channelId)
            return
        }

        val maxMessages = config.schedule.catchUpMaxMessages
        val rawMissed =
            try {
                catchUpChannelPort.fetchMissedMessages(
                    channelId = channelId,
                    firstPendingMessageId = firstPendingId,
                    limit = maxMessages,
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.warn("Deferred catch-up failed to fetch missed messages for channel {}: {}", watermark.channelId, ex.message)
                return
            }

        val boundedByCount = rawMissed.distinctBy { it.id }.sortedBy { it.id }.takeLast(maxMessages)
        val cutoffMillis = timing.clock.millis() - config.schedule.catchUpMaxAgeHours * MILLIS_PER_HOUR
        val freshMessages = boundedByCount.filter { messageTimestampMillis(it) >= cutoffMillis }
        val selfId = freshMessages.firstNotNullOfOrNull { runCatching { it.kord.selfId }.getOrNull() }
        val eligibleMessages = freshMessages.filter { isEligibleCandidate(it, selfId) }

        if (eligibleMessages.isEmpty()) {
            recordSkippedChannelTrace(
                watermark = watermark,
                current = current,
                reason = "Deferred catch-up skipped: no eligible messages within maxAge/maxMessages bounds",
            )
            clearWatermarkSafely(watermark.channelId)
            return
        }

        val mentionContext =
            filter.contextEngine.mentionResolver.resolveSlice(
                messages = eligibleMessages,
                policy = filter.contextPolicy(),
                kordSelfId = selfId,
            )

        val candidates =
            eligibleMessages.map { msg ->
                val rawContent = runCatching { msg.content }.getOrNull().orEmpty()
                val resolvedContent = mentionContext.formatText(rawContent).ifBlank { "[attachment]" }
                CatchUpCandidateMessage(
                    messageId = msg.id.toString(),
                    authorName = resolveAuthorName(msg, mentionContext),
                    content = resolvedContent,
                    timestampMillis = messageTimestampMillis(msg),
                    isDirectMention = filter.directInteractionResolver.isDirectBotMention(msg, selfId),
                    isReplyToLuna = filter.directInteractionResolver.isReplyToLuna(msg, selfId),
                    authorId = resolveAuthorId(msg)?.toString(),
                )
            }
        val authorAffinities = resolveAuthorAffinities(watermark.guildId, candidates)

        val triageSession =
            traceRecorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.AMBIENT,
                discord =
                    AiTraceDiscordContext(
                        guildId = watermark.guildId.ifBlank { null },
                        channelId = watermark.channelId,
                        messageId = watermark.lastPendingMessageId,
                        authorId = null,
                        reactingUserId = null,
                        isDirectMention = false,
                    ),
            )
        triageSession.registerSecret(config.apiKey)
        triageSession.recordScheduleState(
            scheduleBlockTitle = current.activityTitle,
            scheduleAvailability = current.availability.name,
        )

        val triageResult =
            try {
                withAiTraceSession(triageSession) {
                    triageClient.triage(
                        candidates = candidates,
                        previousAvailability = previous,
                        currentBlock = current,
                        maxAmbient = config.schedule.catchUpMaxAmbient,
                        authorAffinities = authorAffinities,
                    )
                }
            } catch (ex: CancellationException) {
                triageSession.skip(reason = ex.message ?: "Deferred catch-up triage cancelled")
                triageSession.finish()
                throw ex
            } catch (ex: Exception) {
                logger.warn("Deferred catch-up triage threw unexpectedly for channel {}: {}", watermark.channelId, ex.message)
                triageSession.fail(
                    AiTraceFailureCategory.UNEXPECTED_ERROR,
                    "Deferred catch-up triage failed: ${ex.message}",
                )
                triageSession.finish()
                return
            }

        val rawSelections =
            triageResult.getOrElse { ex ->
                logger.warn("Deferred catch-up triage degraded/failed for channel {}: {}", watermark.channelId, ex.message)
                triageSession.fail(
                    AiTraceFailureCategory.UNEXPECTED_ERROR,
                    "Deferred catch-up triage failed: ${ex.message}",
                )
                triageSession.finish()
                return
            }

        val candidatesById = candidates.associateBy { it.messageId }
        val messagesById = eligibleMessages.associateBy { it.id.toString() }
        val sanitizedSelections =
            sanitizeSelections(
                rawSelections = rawSelections,
                candidatesById = candidatesById,
                maxAmbient = config.schedule.catchUpMaxAmbient,
            )

        if (sanitizedSelections.isEmpty()) {
            triageSession.skip(reason = "Deferred catch-up triage selected no worthy messages")
            triageSession.finish()
            clearWatermarkSafely(watermark.channelId)
            return
        }

        triageSession.complete(
            finalOutput = sanitizedSelections.joinToString(",") { "${it.messageId}:${it.action.name}" },
        )
        triageSession.finish()

        var deliveredAny = false
        var abortedUnavailable = false
        for ((index, selection) in sanitizedSelections.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (isUnavailableNow()) {
                abortedUnavailable = true
                logger.info(
                    "Deferred catch-up aborted mid-batch in channel {} before item {} ({} remaining)",
                    watermark.channelId,
                    selection.messageId,
                    sanitizedSelections.size - index,
                )
                break
            }

            if (index > 0) {
                val delayMs = sampleStaggerDelayMs()
                if (delayMs > 0L) {
                    timing.staggerDelayFn(delayMs)
                }
                currentCoroutineContext().ensureActive()
                if (isUnavailableNow()) {
                    abortedUnavailable = true
                    logger.info(
                        "Deferred catch-up aborted after stagger delay in channel {} before item {}",
                        watermark.channelId,
                        selection.messageId,
                    )
                    break
                }
            }

            val targetMessage = messagesById[selection.messageId] ?: continue
            val candidate = candidatesById[selection.messageId]
            val directInteraction =
                when {
                    candidate?.isDirectMention == true -> luna.chatter.DirectInteractionType.MENTION
                    candidate?.isReplyToLuna == true -> luna.chatter.DirectInteractionType.DIRECT_REPLY
                    else -> luna.chatter.DirectInteractionType.NONE
                }
            val delivered =
                deliveryExecutor.deliverSelection(
                    targetMessage = targetMessage,
                    selection = selection,
                    directInteraction = directInteraction,
                    eligibleMessages = eligibleMessages,
                    selfId = selfId,
                    current = current,
                )
            if (delivered) {
                deliveredAny = true
            }
        }

        if (!abortedUnavailable || deliveredAny) {
            clearWatermarkSafely(watermark.channelId)
        }
    }

    private fun sanitizeSelections(
        rawSelections: List<CatchUpTriageSelection>,
        candidatesById: Map<String, CatchUpCandidateMessage>,
        maxAmbient: Int,
    ): List<CatchUpTriageSelection> {
        val seenIds = mutableSetOf<String>()
        val direct = mutableListOf<CatchUpTriageSelection>()
        val ambient = mutableListOf<CatchUpTriageSelection>()
        val ambientCap = maxAmbient.coerceAtLeast(0)

        for (selection in rawSelections) {
            val id = selection.messageId.trim()
            val candidate = candidatesById[id]
            if (candidate == null) {
                logger.warn("Deferred catch-up discarded invalid/out-of-range triage target '{}'", id)
                continue
            }
            if (!seenIds.add(id)) continue
            val normalized = selection.copy(messageId = id)
            if (candidate.isDirectTarget) {
                direct.add(normalized)
            } else if (ambient.size < ambientCap) {
                ambient.add(normalized)
            }
        }
        return direct + ambient
    }

    private fun isUnavailableNow(): Boolean {
        if (!scheduleProvider.isEnabled()) return false
        return scheduleProvider.currentBlock()?.availability?.isUnavailable == true
    }

    private fun isEligibleCandidate(
        message: Message,
        selfId: Snowflake?,
    ): Boolean {
        val isBot = runCatching { message.author?.isBot }.getOrNull() ?: false
        if (isBot) return false
        val authorId = resolveAuthorId(message)
        if (selfId != null && authorId == selfId) return false
        val messageType = runCatching { message.type }.getOrNull()
        if (messageType != null && messageType != MessageType.Default && messageType != MessageType.Reply) {
            return false
        }
        val content = runCatching { message.content }.getOrNull().orEmpty().trim()
        val hasAttachments = runCatching { message.attachments.isNotEmpty() }.getOrDefault(false)
        if (content.isBlank() && !hasAttachments) return false
        if (filter.botPrefixes.any { prefix -> prefix.isNotBlank() && content.startsWith(prefix) }) {
            return false
        }
        return true
    }

    private fun messageTimestampMillis(message: Message): Long = runCatching { message.timestamp.toEpochMilliseconds() }.getOrElse { timing.clock.millis() }

    private fun resolveAuthorAffinities(
        guildId: String,
        candidates: List<CatchUpCandidateMessage>,
    ): Map<String, AffinityTier> {
        val store = memoryStore ?: return emptyMap()
        if (guildId.isBlank()) return emptyMap()
        val authorIds = candidates.mapNotNull { it.authorId }.distinct()
        if (authorIds.isEmpty()) return emptyMap()
        return try {
            store
                .listUserRelationships(
                    guildId = guildId,
                    userIds = authorIds,
                    now = timing.clock.millis(),
                ).mapValues { (_, relationship) -> relationship.tier }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(
                "Deferred catch-up failed to load user relationships for guild {}: {}",
                guildId,
                ex.message,
            )
            emptyMap()
        }
    }

    private fun resolveAuthorName(
        message: Message,
        mentionContext: ResolvedSliceMentions,
    ): String {
        val authorId = resolveAuthorId(message)
        val cachedDisplayName = authorId?.let { mentionContext.speakerDisplayName(it) }
        return cachedDisplayName ?: DiscordMentionResolver.extractSpeakerName(message)
    }

    private fun recordSkippedChannelTrace(
        watermark: CatchUpWatermark,
        current: ScheduleBlock,
        reason: String,
    ) {
        val session =
            traceRecorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.AMBIENT,
                discord =
                    AiTraceDiscordContext(
                        guildId = watermark.guildId.ifBlank { null },
                        channelId = watermark.channelId,
                        messageId = watermark.lastPendingMessageId,
                        authorId = null,
                        reactingUserId = null,
                        isDirectMention = false,
                    ),
            )
        session.registerSecret(config.apiKey)
        session.recordScheduleState(
            scheduleBlockTitle = current.activityTitle,
            scheduleAvailability = current.availability.name,
        )
        session.skip(reason = reason)
        session.finish()
    }

    private fun clearWatermarkSafely(channelId: String) {
        runCatching { scheduleStore.clearCatchUpWatermark(channelId) }
            .onFailure { ex ->
                logger.warn("Deferred catch-up failed to clear CatchUpWatermark for channel {}: {}", channelId, ex.message)
            }
    }

    private fun parseSnowflake(
        raw: String,
        label: String,
    ): Snowflake? {
        val value = raw.trim().toULongOrNull()
        if (value == null) {
            logger.warn("Deferred catch-up encountered invalid {} '{}'; skipping", label, raw)
            return null
        }
        return Snowflake(value)
    }

    private fun sampleStaggerDelayMs(): Long = timing.randomProvider(MIN_STAGGER_DELAY_MS, MAX_STAGGER_DELAY_MS).coerceAtLeast(0L)

    companion object {
        private const val MILLIS_PER_HOUR = 3_600_000L
        const val MIN_STAGGER_DELAY_MS = 1_500L
        const val MAX_STAGGER_DELAY_MS = 3_500L
    }
}
