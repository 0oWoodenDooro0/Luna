package luna.core.ai

import dev.kord.core.entity.Message
import kotlin.time.Duration

/**
 * Formats relative time-gap markers between consecutive retained messages in a dialogue transcript.
 */
internal object DialogueTimeGapFormatter {
    fun formatMarkerIfNeeded(
        previous: Message?,
        current: Message,
        threshold: Duration?,
    ): String? {
        if (previous == null || threshold == null) return null
        val prevTs = runCatching { previous.timestamp }.getOrNull() ?: return null
        val currTs = runCatching { current.timestamp }.getOrNull() ?: return null
        val gap = currTs - prevTs
        if (!gap.isPositive() || gap < threshold) return null
        return formatMarker(gap)
    }

    fun formatMarker(gap: Duration): String {
        val totalMinutes = gap.inWholeMinutes
        if (totalMinutes < 60L) {
            return "--- $totalMinutes 分鐘後 ---"
        }
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (minutes == 0L) {
            "--- $hours 小時後 ---"
        } else {
            "--- $hours 小時 $minutes 分鐘後 ---"
        }
    }
}
