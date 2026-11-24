package luna.chatter.schedule

import dev.kord.common.Color
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import luna.core.Command
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Public `/schedule` slash command displaying Luna's full 00:00–24:00 [DailySchedule]
 * in a Discord Embed, with the currently active [ScheduleBlock] highlighted.
 */
class ScheduleCommand(
    private val scheduleProvider: DailyScheduleProvider,
    private val clock: Clock = Clock.systemUTC(),
) : Command {
    override val name: String = "schedule"
    override val description: String = "查看 Luna 今天的每日行程表"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val response = interaction.deferPublicResponse()

        if (!scheduleProvider.isEnabled()) {
            response.respond {
                content = "📅 Luna 的每日行程表功能目前尚未啟用。"
            }
            return
        }

        val schedule = scheduleProvider.currentSchedule()
        if (schedule == null) {
            response.respond {
                content = "⏳ Luna 今天的每日行程表仍在規劃中，請稍後再試！"
            }
            return
        }

        val zoneId = runCatching { ZoneId.of(schedule.timezone) }.getOrElse { ZoneOffset.UTC }
        val localNow = clock.instant().atZone(zoneId)
        val isToday = schedule.date == localNow.toLocalDate()
        val currentMinute = localNow.hour * 60 + localNow.minute
        val sortedBlocks = schedule.blocks.sortedBy { it.startMinute }
        val activeBlock = if (isToday) sortedBlocks.firstOrNull { it.containsMinute(currentMinute) } else null

        response.respond {
            embed {
                title = "📅 Luna 的每日行程表 (${schedule.date})"
                color = activeBlock?.availability?.embedColor() ?: DEFAULT_EMBED_COLOR
                description = truncateDescription(buildScheduleDescription(schedule, sortedBlocks, activeBlock))
                footer {
                    text = "時區：${schedule.timezone}"
                }
            }
        }
    }

    private fun buildScheduleDescription(
        schedule: DailySchedule,
        sortedBlocks: List<ScheduleBlock>,
        activeBlock: ScheduleBlock?,
    ): String =
        buildString {
            if (activeBlock != null) {
                appendLine(
                    "目前狀態：${activeBlock.availability.icon()} **${activeBlock.activityTitle}**（${activeBlock.statusText}）",
                )
            }
            appendLine(schedule.summary)
            appendLine()
            appendLine("**今日時間軸 (00:00–24:00)**")
            for (block in sortedBlocks) {
                val isCurrent = block == activeBlock
                if (isCurrent) {
                    appendLine("▶ **${block.formattedTimeRange()}** ${block.availability.icon()} **${block.activityTitle}** *(進行中)*")
                } else {
                    appendLine("${block.formattedTimeRange()} ${block.availability.icon()} ${block.activityTitle}")
                }
            }
        }.trimEnd()

    companion object {
        private val DEFAULT_EMBED_COLOR = Color(0x5865F2)
        internal const val DISCORD_EMBED_DESCRIPTION_MAX_LENGTH = 4096

        internal fun truncateDescription(
            text: String,
            maxLength: Int = DISCORD_EMBED_DESCRIPTION_MAX_LENGTH,
        ): String {
            if (text.length <= maxLength) {
                return text
            }
            if (maxLength <= 3) {
                return "...".take(maxLength)
            }
            return text.take(maxLength - 3) + "..."
        }
    }
}

private fun AvailabilityState.icon(): String =
    when (this) {
        AvailabilityState.AVAILABLE -> "🟢"
        AvailabilityState.BREAK -> "☕"
        AvailabilityState.BUSY -> "🔴"
        AvailabilityState.SLEEPING -> "🌙"
    }

private fun AvailabilityState.embedColor(): Color =
    when (this) {
        AvailabilityState.AVAILABLE -> Color(0x57F287)
        AvailabilityState.BREAK -> Color(0xFEE75C)
        AvailabilityState.BUSY -> Color(0xED4245)
        AvailabilityState.SLEEPING -> Color(0x99AAB5)
    }
