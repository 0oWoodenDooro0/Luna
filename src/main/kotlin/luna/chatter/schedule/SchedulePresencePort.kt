package luna.chatter.schedule

import dev.kord.common.entity.PresenceStatus
import dev.kord.core.Kord
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Port for synchronizing Luna's active [ScheduleBlock] to an external presence surface (e.g. Discord).
 */
interface SchedulePresencePort {
    suspend fun updatePresence(block: ScheduleBlock)
}

/**
 * Kord implementation of [SchedulePresencePort] using `kord.editPresence` to reflect
 * Luna's current [AvailabilityState] and custom status text.
 */
class KordSchedulePresencePort(
    private val kord: Kord,
) : SchedulePresencePort {
    private val logger = LoggerFactory.getLogger(KordSchedulePresencePort::class.java)

    override suspend fun updatePresence(block: ScheduleBlock) {
        val targetStatus = block.availability.toPresenceStatus()
        try {
            kord.editPresence {
                status = targetStatus
                state = block.statusText
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

        AvailabilityState.SLEEPING -> PresenceStatus.Idle
    }
