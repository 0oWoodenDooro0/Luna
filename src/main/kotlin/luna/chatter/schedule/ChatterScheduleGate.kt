package luna.chatter.schedule

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import org.slf4j.LoggerFactory

/**
 * Coordinates `Daily Schedule` availability gating, `Catch-Up Watermark` recording,
 * and `AI Trace` schedule metadata for `ChatterService`.
 */
internal class ChatterScheduleGate(
    private val scheduleProvider: DailyScheduleProvider?,
    private val scheduleStore: DailyScheduleStore?,
    private val traceRecorder: AiTraceRecorder,
    private val apiKey: String,
) {
    private val logger = LoggerFactory.getLogger(ChatterScheduleGate::class.java)

    fun activeUnavailableBlock(): ScheduleBlock? {
        val provider = scheduleProvider?.takeIf { it.isEnabled() } ?: return null
        val block = provider.currentBlock() ?: return null
        return block.takeIf { it.availability.isUnavailable }
    }

    fun recordDeferredUnavailableMessage(
        message: Message,
        guildId: Snowflake?,
        authorId: Snowflake?,
        isBotAuthor: Boolean,
        isDirectMention: Boolean,
        block: ScheduleBlock,
        trigger: AiTraceTrigger = if (isDirectMention) AiTraceTrigger.MENTION else AiTraceTrigger.AMBIENT,
    ) {
        recordPendingCatchUpIfNonBot(message, guildId, isBotAuthor)
        val session =
            traceRecorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = trigger,
                discord =
                    AiTraceDiscordContext(
                        guildId = guildId?.toString(),
                        channelId = message.channelId.toString(),
                        messageId = message.id.toString(),
                        authorId = authorId?.toString(),
                        reactingUserId = null,
                        isDirectMention = isDirectMention,
                    ),
            )
        session.registerSecret(apiKey)
        session.recordScheduleState(
            scheduleBlockTitle = block.activityTitle,
            scheduleAvailability = block.availability.name,
        )
        session.skip(
            reason = "Deferred by DailySchedule (${block.availability.name}: ${block.activityTitle})",
        )
        session.finish()
    }

    fun recordDeferredInSession(
        message: Message,
        guildId: Snowflake?,
        isBotAuthor: Boolean,
        session: AiTraceSession,
        block: ScheduleBlock,
    ) {
        recordPendingCatchUpIfNonBot(message, guildId, isBotAuthor)
        session.recordScheduleState(
            scheduleBlockTitle = block.activityTitle,
            scheduleAvailability = block.availability.name,
        )
        session.skip(
            reason = "Deferred by DailySchedule (${block.availability.name}: ${block.activityTitle})",
        )
    }

    fun recordActiveScheduleInSession(
        session: AiTraceSession,
        personaScheduleBlock: ScheduleBlock?,
    ) {
        val activeBlock = personaScheduleBlock ?: scheduleProvider?.takeIf { it.isEnabled() }?.currentBlock()
        if (activeBlock != null) {
            session.recordScheduleState(
                scheduleBlockTitle = activeBlock.activityTitle,
                scheduleAvailability = activeBlock.availability.name,
            )
        }
    }

    private fun recordPendingCatchUpIfNonBot(
        message: Message,
        guildId: Snowflake?,
        isBotAuthor: Boolean,
    ) {
        if (isBotAuthor) return
        val store = scheduleStore ?: return
        val messageAtMillis = runCatching { message.timestamp.toEpochMilliseconds() }.getOrElse { System.currentTimeMillis() }
        runCatching {
            store.recordPendingCatchUp(
                channelId = message.channelId.toString(),
                guildId = guildId?.toString().orEmpty(),
                messageId = message.id.toString(),
                messageAtMillis = messageAtMillis,
            )
        }.onFailure { e ->
            logger.warn("Failed to record pending CatchUpWatermark for channel {}: {}", message.channelId, e.message)
        }
    }
}
