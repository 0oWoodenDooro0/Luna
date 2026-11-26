package luna.chatter.schedule

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.BurstInterruptedCancellationException
import luna.chatter.ChatterChannelPort
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterDeliveryEngine
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterNoiseFilter
import luna.chatter.ChatterPromptBuilder
import luna.chatter.ChatterSilentReactionCoordinator
import luna.chatter.DeliveryOutcome
import luna.chatter.DirectInteractionType
import luna.chatter.GuildCustomEmoji
import luna.chatter.PersonaStateProvider
import luna.chatter.emoji.ChatterEmojiService
import luna.chatter.resolveAuthorId
import luna.chatter.toAvailableGuildCustomEmojis
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceTrigger
import luna.core.ai.withAiTraceSession
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Executes individual `Deferred Catch-Up` `REPLY` and `REACT` deliveries coordinated
 * with [BoundedChannelBurstCoordinator], [ChatterDeliveryEngine], and [ChatterSilentReactionCoordinator].
 */
internal class DeferredCatchUpDeliveryExecutor(
    private val chatterGenerator: ChatterGenerator,
    private val filter: ChatterNoiseFilter,
    private val contextCollector: ChatterContextCollector,
    private val channelPort: ChatterChannelPort,
    private val burstCoordinator: BoundedChannelBurstCoordinator,
    private val personaStateProvider: PersonaStateProvider,
    private val emojiService: ChatterEmojiService,
    private val traceRecorder: AiTraceRecorder,
    private val config: ChatterConfig,
    private val scheduleProvider: DailyScheduleProvider,
    randomProvider: (minInclusive: Long, maxInclusive: Long) -> Long,
    private val logger: Logger = LoggerFactory.getLogger(DeferredCatchUpDeliveryExecutor::class.java),
) {
    private fun isUnavailableNow(): Boolean = scheduleProvider.currentBlock()?.availability?.isUnavailable == true

    private fun isUnavailableWithoutConversationEdit(): Boolean {
        val block = scheduleProvider.currentBlock() ?: return false
        return block.availability.isUnavailable && !block.adjustedByConversation
    }

    private val deliveryEngine =
        ChatterDeliveryEngine(
            channelPort = channelPort,
            randomProvider = randomProvider,
        )
    private val silentReactionCoordinator =
        ChatterSilentReactionCoordinator(
            chatterGenerator = chatterGenerator,
            deliveryEngine = deliveryEngine,
            logger = logger,
        )

    suspend fun deliverSelection(
        targetMessage: Message,
        selection: CatchUpTriageSelection,
        directInteraction: DirectInteractionType,
        eligibleMessages: List<Message>,
        selfId: Snowflake?,
        current: ScheduleBlock,
    ): Boolean {
        val authorId = resolveAuthorId(targetMessage)
        val guildId = runCatching { targetMessage.data.guildId.value }.getOrNull()

        return burstCoordinator.executeWithDirectInteraction(
            channelId = targetMessage.channelId,
            messageId = targetMessage.id,
            authorId = authorId,
            directInteraction = directInteraction,
            canProceedAfterWait = { !isUnavailableNow() },
        ) { effectiveDirectInteraction, markDispatching ->
            if (isUnavailableNow()) {
                return@executeWithDirectInteraction false
            }
            val effectiveIsDirect = effectiveDirectInteraction.isDirect

            val session =
                traceRecorder.startSession(
                    pipeline = AiTracePipeline.CHATTER,
                    trigger = effectiveDirectInteraction.toAiTraceTrigger(),
                    discord =
                        AiTraceDiscordContext(
                            guildId = guildId?.toString(),
                            channelId = targetMessage.channelId.toString(),
                            messageId = targetMessage.id.toString(),
                            authorId = authorId?.toString(),
                            reactingUserId = null,
                            isDirectMention = effectiveIsDirect,
                        ),
                )
            session.registerSecret(config.apiKey)
            session.recordScheduleState(
                scheduleBlockTitle = current.activityTitle,
                scheduleAvailability = current.availability.name,
            )

            try {
                withAiTraceSession(session) {
                    deliveryEngine.withReadingSimulation {
                        val readingScope = this
                        val personaState = personaStateProvider.currentState(config.decision.threshold)
                        session.recordPersonaState(
                            circadianPeriod = personaState.circadianPeriod?.name,
                            personaMood = personaState.mood?.label,
                            effectiveThreshold = personaState.effectiveThreshold,
                            reactionThreshold = config.reactionThreshold,
                        )

                        val fetchLimit = maxOf(contextCollector.decisionMessageCount, contextCollector.historyMessageCount)
                        val historyBefore =
                            try {
                                channelPort.fetchMessagesBefore(targetMessage, fetchLimit)
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                logger.warn(
                                    "Deferred catch-up failed to fetch history before {}: {}",
                                    targetMessage.id,
                                    ex.message,
                                )
                                emptyList()
                            }
                        val contextMessages =
                            if (historyBefore.isNotEmpty()) {
                                historyBefore + targetMessage
                            } else {
                                eligibleMessages.filter { it.id <= targetMessage.id }
                            }

                        val emojiTurn =
                            emojiService.beginTurn(
                                channelId = targetMessage.channelId,
                                guildId = guildId,
                                guildEmojisProvider = { resolveGuildCustomEmojis(targetMessage) },
                                applicationEmojisProvider = { channelPort.listApplicationEmojis(targetMessage) },
                                preScanScope = null,
                            )

                        when (selection.action) {
                            CatchUpAction.REPLY -> {
                                val replyContext = contextCollector.collectReplyContext(contextMessages, kordSelfId = selfId)
                                currentCoroutineContext().ensureActive()
                                if (replyContext.transcript.isBlank() &&
                                    replyContext.imageAttachments.isEmpty() &&
                                    replyContext.videoUri.isNullOrBlank()
                                ) {
                                    session.skip(reason = "Deferred catch-up reply context is blank")
                                    return@withReadingSimulation false
                                }

                                val preparedEmojis = emojiTurn.prepareReplyEmojis(replyContext.transcript)
                                val chatterInput =
                                    replyContext
                                        .toChatterInput(isDirectMention = effectiveIsDirect)
                                        .copy(
                                            transcript = preparedEmojis.transcript,
                                            personaState = personaState,
                                            guildEmojis = preparedEmojis.guildEmojis,
                                            applicationEmojis = preparedEmojis.applicationEmojis,
                                            guildId = guildId?.toString(),
                                            channelId = targetMessage.channelId.toString(),
                                            authorId = authorId?.toString(),
                                        )

                                val replyText = chatterGenerator.generateReply(chatterInput)
                                currentCoroutineContext().ensureActive()
                                if (replyText.isNullOrBlank()) {
                                    session.skip(reason = "Deferred catch-up generator returned blank reply")
                                    return@withReadingSimulation false
                                }

                                val segments = ChatterPromptBuilder.splitReplySegments(replyText)
                                if (segments.isEmpty()) {
                                    session.skip(reason = "Deferred catch-up reply had no segments")
                                    return@withReadingSimulation false
                                }

                                if (isUnavailableWithoutConversationEdit()) {
                                    session.skip(reason = "Deferred catch-up aborted before reply dispatch: schedule unavailable")
                                    return@withReadingSimulation false
                                }

                                val outcome =
                                    deliveryEngine.deliverSegmentedReply(
                                        anchor = targetMessage,
                                        segments = segments,
                                        selfId = selfId,
                                        readingSimulation = readingScope,
                                        markDispatching = markDispatching,
                                        forceReplyThreading = true,
                                        pingRepliedUser = effectiveIsDirect,
                                    )
                                if (outcome == DeliveryOutcome.CancelledBeforeDispatch) {
                                    session.skip(reason = "Deferred catch-up reply cancelled before dispatch")
                                    return@withReadingSimulation false
                                }

                                val deliveredOutput = segments.joinToString("\n")
                                filter.cooldownCache.record(targetMessage.channelId)
                                emojiTurn.recordDeliveredReply(segments)
                                session.complete(finalOutput = deliveredOutput)
                                logger.info(
                                    "Deferred catch-up replied to message {} in channel {}",
                                    targetMessage.id,
                                    targetMessage.channelId,
                                )
                                true
                            }

                            CatchUpAction.REACT -> {
                                val decisionContext = contextCollector.collectDecisionContext(contextMessages, kordSelfId = selfId)
                                currentCoroutineContext().ensureActive()
                                if (decisionContext.transcript.isBlank() && decisionContext.images.isEmpty()) {
                                    session.skip(reason = "Deferred catch-up reaction context is blank")
                                    return@withReadingSimulation false
                                }
                                if (isUnavailableNow()) {
                                    session.skip(reason = "Deferred catch-up aborted before reaction dispatch: schedule unavailable")
                                    return@withReadingSimulation false
                                }

                                val annotatedContext =
                                    decisionContext.copy(
                                        transcript = emojiTurn.annotateTranscript(decisionContext.transcript),
                                    )
                                silentReactionCoordinator.handleSilentReactionPath(
                                    message = targetMessage,
                                    decisionContext = annotatedContext,
                                    personaState = personaState,
                                    noul = 1.0,
                                    confidence = 1.0,
                                    effectiveThreshold = personaState.effectiveThreshold,
                                    reactionThreshold = config.reactionThreshold,
                                    readingScope = readingScope,
                                    session = session,
                                    markDispatching = markDispatching,
                                    emojiTurn = emojiTurn,
                                )
                            }
                        }
                    }
                }
            } catch (ex: BurstInterruptedCancellationException) {
                logger.debug("Deferred catch-up delivery yielded to live chat in channel {}: {}", targetMessage.channelId, ex.message)
                session.skip(reason = ex.message ?: "Deferred catch-up yielded to live chat")
                false
            } catch (ex: CancellationException) {
                session.skip(reason = ex.message ?: "Deferred catch-up delivery cancelled")
                throw ex
            } catch (ex: Exception) {
                logger.warn(
                    "Deferred catch-up delivery failed for message {} in channel {}: {}",
                    targetMessage.id,
                    targetMessage.channelId,
                    ex.message,
                )
                session.fail(
                    AiTraceFailureCategory.UNEXPECTED_ERROR,
                    "Deferred catch-up delivery failed: ${ex.message}",
                )
                false
            } finally {
                session.finish()
            }
        }
    }

    private suspend fun resolveGuildCustomEmojis(message: Message): List<GuildCustomEmoji> =
        try {
            channelPort.listGuildCustomEmojis(message).toAvailableGuildCustomEmojis()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("Deferred catch-up failed to resolve guild emojis for channel {}: {}", message.channelId, ex.message)
            emptyList()
        }
}
