package luna.chatter.schedule

import dev.kord.common.entity.PresenceStatus
import dev.kord.core.Kord
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.on
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Port for synchronizing Luna's active [ScheduleBlock] to an external presence surface (e.g. Discord).
 */
interface SchedulePresencePort {
    suspend fun updatePresence(block: ScheduleBlock)
}

/**
 * Kord implementation of [SchedulePresencePort] using `kord.editPresence` to reflect
 * Luna's current [AvailabilityState] and custom status text.
 *
 * Defers presence updates until Discord Gateway emits [ReadyEvent] (via [registerReadyListener]
 * or [onGatewayReady]) so startup syncs before `kord.login()` do not hit an uninitialized
 * gateway WebSocket (`lateinit property socket has not been initialized`).
 */
class KordSchedulePresencePort(
    private val kord: Kord,
    initiallyReady: Boolean = false,
) : SchedulePresencePort {
    private val logger = LoggerFactory.getLogger(KordSchedulePresencePort::class.java)
    private val gatewayReady = AtomicBoolean(initiallyReady)
    private val latestBlock = AtomicReference<ScheduleBlock?>(null)

    /**
     * Subscribes to Kord [ReadyEvent] in [scope] so deferred startup presence updates
     * are flushed once the gateway connects and re-synced on subsequent reconnects.
     */
    fun registerReadyListener(scope: CoroutineScope): Job? =
        runCatching {
            kord.on<ReadyEvent>(scope = scope) {
                onGatewayReady()
            }
        }.onFailure { ex ->
            logger.warn("Could not register Discord ReadyEvent listener for presence sync: {}", ex.message)
        }.getOrNull()

    /**
     * Marks Discord Gateway as ready and flushes the latest [ScheduleBlock], if any.
     */
    suspend fun onGatewayReady() {
        gatewayReady.set(true)
        val pending = latestBlock.get() ?: return
        syncPresence(pending)
    }

    override suspend fun updatePresence(block: ScheduleBlock) {
        latestBlock.set(block)
        if (!gatewayReady.get()) {
            logger.debug(
                "Discord gateway not ready yet; deferring presence sync for block '{}' ({})",
                block.activityTitle,
                block.availability,
            )
            return
        }
        syncPresence(block)
    }

    private suspend fun syncPresence(block: ScheduleBlock) {
        val targetStatus = block.availability.toPresenceStatus()
        try {
            kord.editPresence {
                status = targetStatus
                if (targetStatus != PresenceStatus.Invisible) {
                    when (block.activityType) {
                        PresenceActivityType.CUSTOM -> state = block.statusText
                        PresenceActivityType.PLAYING -> playing(block.statusText)
                        PresenceActivityType.LISTENING -> listening(block.statusText)
                        PresenceActivityType.WATCHING -> watching(block.statusText)
                    }
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn(
                "Failed to sync Discord presence for block '{}' ({}): {}",
                block.activityTitle,
                block.availability,
                ex.message,
            )
        }
    }
}

private fun AvailabilityState.toPresenceStatus(): PresenceStatus =
    when (this) {
        AvailabilityState.AVAILABLE,
        AvailabilityState.BREAK,
        -> PresenceStatus.Online

        AvailabilityState.BUSY -> PresenceStatus.DoNotDisturb

        AvailabilityState.SLEEPING -> PresenceStatus.Invisible
    }
