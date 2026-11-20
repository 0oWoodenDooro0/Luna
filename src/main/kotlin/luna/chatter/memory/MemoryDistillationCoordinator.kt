package luna.chatter.memory

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Message
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import luna.chatter.ChatterNoiseFilter
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.LinkedHashMap

private data class ChannelActivityState(
    val channelId: String,
    val guildId: String,
    var lastActivityAtMs: Long,
    var oldestUnprocessedAtMs: Long? = null,
    var unprocessedHumanCount: Int = 0,
    var inFlight: Boolean = false,
)

private data class EligibleChannelBatch(
    val channelId: String,
    val guildId: String,
    val countAtStart: Int,
)

/**
 * Coordinates cursor-based asynchronous `Memory Distillation` across Chatter-enabled guild channels.
 *
 * Maintains a bounded LRU channel activity tracker (`maxTrackedChannels = 1000`), cold-starts
 * new channels at the first observed message without historical backfill, and runs eligible
 * distillation batches sequentially on a periodic coroutine ticker.
 */
class MemoryDistillationCoordinator(
    private val memoryStore: ChatterMemoryStore,
    private val noiseFilter: ChatterNoiseFilter,
    private val config: ChatterMemoryConfig,
    private val allowedChannels: Set<Snowflake> = emptySet(),
    private val chatterEnabled: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
    private val maxTrackedChannels: Int = DEFAULT_MAX_TRACKED_CHANNELS,
    private val kordSelfIdProvider: () -> Snowflake? = { null },
    private val distillExecutor: suspend (channelId: String, guildId: String, cursorMessageId: String) -> DistillOutcome,
) {
    private val logger = LoggerFactory.getLogger(MemoryDistillationCoordinator::class.java)
    private val lock = Any()

    private val activityTracker: MutableMap<String, ChannelActivityState> =
        object : LinkedHashMap<String, ChannelActivityState>(maxTrackedChannels, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChannelActivityState>?): Boolean = size > maxTrackedChannels
        }

    init {
        require(maxTrackedChannels >= 1) { "maxTrackedChannels must be >= 1" }
    }

    /**
     * Registers a Kord [MessageCreateEvent] observer for Chatter-enabled guild channels.
     */
    fun registerMessageObserver(
        kord: Kord,
        scope: CoroutineScope,
    ): Job =
        kord.on<MessageCreateEvent>(scope = scope) {
            val guildId =
                message.data.guildId.value
                    ?.toString() ?: return@on
            observeMessage(message = message, guildId = guildId)
        }

    /**
     * Starts the periodic background ticker that drains eligible channels sequentially.
     */
    fun startTicker(
        scope: CoroutineScope,
        intervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
    ): Job =
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    tick()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    logger.warn("Memory distillation ticker iteration failed: {}", ex.message)
                }
            }
        }

    /**
     * Observes a Discord message for distillation scheduling or cold-start cursor initialization.
     */
    fun observeMessage(
        message: Message,
        guildId: String?,
    ) {
        if (!chatterEnabled || !config.enabled) return
        if (guildId.isNullOrBlank()) return

        val channelSnowflake = message.channelId
        if (allowedChannels.isNotEmpty() && !allowedChannels.contains(channelSnowflake)) return

        val selfId = kordSelfIdProvider()
        if (!noiseFilter.isContextMessage(message, selfId)) return

        val authorId =
            runCatching { message.author?.id }.getOrNull()
                ?: runCatching { message.data.author.id }.getOrNull()
        val isLunaBot = selfId != null && authorId == selfId
        val isBot = isLunaBot || (runCatching { message.author?.isBot }.getOrNull() == true)
        val isHuman = !isBot && message.author != null

        val channelId = channelSnowflake.toString()
        val messageId = message.id.toString()
        val nowMs = clock.millis()

        val existingCursor = memoryStore.getCursor(channelId)
        if (existingCursor == null) {
            memoryStore.upsertCursor(
                channelId = channelId,
                guildId = guildId,
                lastSummarizedMessageId = messageId,
            )
            synchronized(lock) {
                activityTracker[channelId] =
                    ChannelActivityState(
                        channelId = channelId,
                        guildId = guildId,
                        lastActivityAtMs = nowMs,
                        oldestUnprocessedAtMs = null,
                        unprocessedHumanCount = 0,
                        inFlight = false,
                    )
            }
            return
        }

        synchronized(lock) {
            val state =
                activityTracker[channelId]
                    ?: ChannelActivityState(
                        channelId = channelId,
                        guildId = guildId,
                        lastActivityAtMs = nowMs,
                    ).also { activityTracker[channelId] = it }

            state.lastActivityAtMs = nowMs
            if (isHuman) {
                state.unprocessedHumanCount += 1
                if (state.oldestUnprocessedAtMs == null) {
                    state.oldestUnprocessedAtMs = nowMs
                }
            }
        }
    }

    /**
     * Evaluates tracked channels and runs `Memory Distillation` sequentially for eligible batches.
     */
    suspend fun tick() {
        if (!chatterEnabled || !config.enabled) return

        val eligible = snapshotEligibleChannels()
        for (batch in eligible) {
            val cursor = memoryStore.getCursor(batch.channelId)
            if (cursor == null) {
                clearInFlight(batch.channelId)
                continue
            }

            val outcome =
                try {
                    distillExecutor(batch.channelId, batch.guildId, cursor.lastSummarizedMessageId)
                } catch (ex: CancellationException) {
                    clearInFlight(batch.channelId)
                    throw ex
                } catch (ex: Exception) {
                    logger.warn(
                        "Memory distillation threw for channel {} (guild {}): {}",
                        batch.channelId,
                        batch.guildId,
                        ex.message,
                    )
                    DistillOutcome.Failed(reason = ex.message ?: "Unexpected failure", cause = ex)
                }

            when (outcome) {
                is DistillOutcome.NoMessages,
                is DistillOutcome.NoExtractableContent,
                is DistillOutcome.Completed,
                -> {
                    markBatchCompleted(batch.channelId, batch.countAtStart)
                }

                is DistillOutcome.Failed -> {
                    // Evict transient failure state so the next eligible tick retries cleanly
                    logger.warn(
                        "Memory distillation failed transiently for channel {} (leaving cursor unchanged): {}",
                        batch.channelId,
                        outcome.reason,
                    )
                    clearInFlight(batch.channelId)
                }
            }
        }
    }

    fun trackedChannelCount(): Int = synchronized(lock) { activityTracker.size }

    private fun snapshotEligibleChannels(): List<EligibleChannelBatch> {
        val nowMs = clock.millis()
        val quietMs = config.quietMinutes * MILLIS_PER_MINUTE
        val maxAgeMs = config.maxBatchAgeMinutes * MILLIS_PER_MINUTE

        return synchronized(lock) {
            val batches = mutableListOf<EligibleChannelBatch>()
            for (state in activityTracker.values) {
                if (state.inFlight) continue
                val count = state.unprocessedHumanCount
                val isQuietTrigger = count >= config.minMessages && (nowMs - state.lastActivityAtMs) >= quietMs
                val isMaxCountTrigger = count >= config.maxBatchMessages
                val oldestAt = state.oldestUnprocessedAtMs
                val isMaxAgeTrigger =
                    count >= config.minMessages &&
                        oldestAt != null &&
                        (nowMs - oldestAt) >= maxAgeMs

                if (isQuietTrigger || isMaxCountTrigger || isMaxAgeTrigger) {
                    state.inFlight = true
                    batches.add(
                        EligibleChannelBatch(
                            channelId = state.channelId,
                            guildId = state.guildId,
                            countAtStart = count,
                        ),
                    )
                }
            }
            batches
        }
    }

    private fun markBatchCompleted(
        channelId: String,
        processedCount: Int,
    ) {
        synchronized(lock) {
            val state = activityTracker[channelId] ?: return
            state.inFlight = false
            val remaining = (state.unprocessedHumanCount - processedCount).coerceAtLeast(0)
            state.unprocessedHumanCount = remaining
            if (remaining == 0) {
                state.oldestUnprocessedAtMs = null
            }
        }
    }

    private fun clearInFlight(channelId: String) {
        synchronized(lock) {
            activityTracker[channelId]?.inFlight = false
        }
    }

    companion object {
        const val DEFAULT_MAX_TRACKED_CHANNELS = 1000
        const val DEFAULT_TICK_INTERVAL_MS = 30_000L
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
