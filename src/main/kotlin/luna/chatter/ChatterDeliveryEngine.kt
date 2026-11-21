package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Outcome of an outbound delivery attempt in [ChatterDeliveryEngine].
 */
internal sealed interface DeliveryOutcome {
    data object Delivered : DeliveryOutcome

    data object CancelledBeforeDispatch : DeliveryOutcome
}

/**
 * Structured-concurrency handle for awaiting the overlapping reading simulation without exposing raw [kotlinx.coroutines.Job]s.
 */
internal interface ReadingSimulationScope {
    suspend fun awaitReading()
}

/**
 * Internal delivery engine owning overlapping reading simulation, length-paced typing,
 * asymmetric reply threading, segmented burst delivery, silent reaction dispatch, and pre-send lock handoff.
 */
internal class ChatterDeliveryEngine(
    private val channelPort: ChatterChannelPort,
    private val randomProvider: (minInclusive: Long, maxInclusive: Long) -> Long,
) {
    private val logger = LoggerFactory.getLogger(ChatterDeliveryEngine::class.java)

    /**
     * Runs [block] concurrently with the silent reading simulation and guarantees child cleanup in `finally`.
     */
    suspend fun <T> withReadingSimulation(block: suspend ReadingSimulationScope.() -> T): T =
        coroutineScope {
            val readingDelayMs = sampleNonNegativeDelay(MIN_READING_DELAY_MS, MAX_READING_DELAY_MS, "reading delay")
            val readingJob =
                launch {
                    if (readingDelayMs > 0L) {
                        delay(readingDelayMs)
                    }
                }
            val scope =
                object : ReadingSimulationScope {
                    override suspend fun awaitReading() {
                        readingJob.join()
                        currentCoroutineContext().ensureActive()
                    }
                }
            try {
                scope.block()
            } finally {
                readingJob.cancel()
            }
        }

    /**
     * Awaits reading completion, paces initial typing, acquires [markDispatching], and delivers up to 3 segments
     * using asymmetric reply threading on segment 1 and inter-segment typing delays on subsequent segments.
     */
    suspend fun deliverSegmentedReply(
        anchor: Message,
        segments: List<String>,
        selfId: Snowflake?,
        readingSimulation: ReadingSimulationScope,
        markDispatching: () -> Boolean,
    ): DeliveryOutcome {
        val boundedSegments = segments.take(MAX_REPLY_SEGMENTS)
        if (boundedSegments.isEmpty()) return DeliveryOutcome.CancelledBeforeDispatch

        readingSimulation.awaitReading()

        try {
            channelPort.triggerTyping(anchor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("triggerTyping() call failed on channel {}: {}", anchor.channelId, e.message)
        }
        currentCoroutineContext().ensureActive()

        val typingDelayMs = minOf(boundedSegments.first().length * TYPING_MS_PER_CHAR, MAX_TYPING_DELAY_MS)
        if (typingDelayMs > 0L) {
            delay(typingDelayMs)
        }
        currentCoroutineContext().ensureActive()

        if (!markDispatching()) {
            return DeliveryOutcome.CancelledBeforeDispatch
        }

        val useReplyThreading = shouldUseReplyThreading(anchor, selfId)
        if (useReplyThreading) {
            channelPort.sendReply(anchor, boundedSegments.first())
        } else {
            channelPort.sendMessage(anchor, boundedSegments.first())
        }

        for (index in 1 until boundedSegments.size) {
            try {
                channelPort.triggerTyping(anchor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "triggerTyping() call failed before burst segment {} on channel {}: {}",
                    index + 1,
                    anchor.channelId,
                    e.message,
                )
            }

            val segmentDelayMs = sampleNonNegativeDelay(MIN_SEGMENT_DELAY_MS, MAX_SEGMENT_DELAY_MS, "segment delay")
            if (segmentDelayMs > 0L) {
                delay(segmentDelayMs)
            }

            channelPort.sendMessage(anchor, boundedSegments[index])
        }

        return DeliveryOutcome.Delivered
    }

    /**
     * Awaits reading completion, acquires [markDispatching], and adds [emoji] to [target] with zero typing indicator.
     */
    suspend fun deliverSilentReaction(
        target: Message,
        emoji: ReactionEmoji,
        readingSimulation: ReadingSimulationScope,
        markDispatching: () -> Boolean,
    ): DeliveryOutcome {
        readingSimulation.awaitReading()
        if (!markDispatching()) {
            return DeliveryOutcome.CancelledBeforeDispatch
        }
        channelPort.addReaction(target, emoji)
        return DeliveryOutcome.Delivered
    }

    private suspend fun shouldUseReplyThreading(
        triggerMessage: Message,
        selfId: Snowflake?,
    ): Boolean {
        val afterMessages =
            try {
                channelPort.fetchMessagesAfter(triggerMessage, DEFAULT_AFTER_MESSAGES_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Failed to fetch post-trigger messages after {} in channel {}; failing open to sendMessage: {}",
                    triggerMessage.id,
                    triggerMessage.channelId,
                    e.message,
                )
                return false
            }

        val resolvedSelfId = selfId ?: runCatching { triggerMessage.kord.selfId }.getOrNull()
        val interveningCount =
            afterMessages.count { afterMsg ->
                afterMsg.id != triggerMessage.id && !isAuthoredByLuna(afterMsg, resolvedSelfId)
            }
        return interveningCount >= REPLY_THREADING_THRESHOLD
    }

    private fun isAuthoredByLuna(
        message: Message,
        selfId: Snowflake?,
    ): Boolean {
        val resolvedSelfId = selfId ?: runCatching { message.kord.selfId }.getOrNull() ?: return false
        val authorId = runCatching { message.author?.id }.getOrNull() ?: runCatching { message.data.author.id }.getOrNull()
        return authorId == resolvedSelfId
    }

    private fun sampleNonNegativeDelay(
        minMs: Long,
        maxMs: Long,
        label: String,
    ): Long {
        val rawDelayMs = randomProvider(minMs, maxMs)
        if (rawDelayMs >= 0L) return rawDelayMs
        logger.warn("Negative {} '{}ms' returned by randomProvider; clamping to 0ms", label, rawDelayMs)
        return 0L
    }

    companion object {
        const val DEFAULT_AFTER_MESSAGES_LIMIT = 20
        const val REPLY_THREADING_THRESHOLD = 2
        const val MAX_REPLY_SEGMENTS = 3
        const val MIN_READING_DELAY_MS = 800L
        const val MAX_READING_DELAY_MS = 2_000L
        const val MIN_SEGMENT_DELAY_MS = 700L
        const val MAX_SEGMENT_DELAY_MS = 1_500L
        const val TYPING_MS_PER_CHAR = 120L
        const val MAX_TYPING_DELAY_MS = 4_000L
    }
}
