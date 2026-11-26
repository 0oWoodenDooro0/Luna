package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.test.runTest
import luna.chatter.memory.ChatterMemoryStore
import luna.chatter.memory.UserRelationship
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterServiceMomentumTest {
    private val fixture = ChatterServiceTestSupport()

    private class CapturingTraceRecorder : AiTraceRecorder {
        val records = mutableListOf<AiTraceRecord>()

        override fun isEnabled(): Boolean = true

        override fun startSession(
            pipeline: AiTracePipeline,
            trigger: AiTraceTrigger,
            discord: AiTraceDiscordContext,
        ): AiTraceSession =
            AiTraceSession(
                recorder = this,
                pipeline = pipeline,
                trigger = trigger,
                discord = discord,
                isEnabled = true,
                emitCorrelationLog = false,
            )

        override fun record(trace: AiTraceRecord) {
            records += trace
        }

        override suspend fun flushAndClose(timeoutMs: Long) = Unit
    }

    private class FakeRelationshipStore(
        private val relationships: Map<Pair<String, String>, UserRelationship> = emptyMap(),
    ) : ChatterMemoryStore by io.mockk.mockk(relaxed = true) {
        override fun getUserRelationship(
            guildId: String,
            userId: String,
            now: Long?,
        ): UserRelationship =
            relationships[guildId to userId]
                ?: UserRelationship.neutral(guildId = guildId, userId = userId, now = now ?: 0L)
    }

    private lateinit var momentumCache: BoundedChannelMomentumCache
    private lateinit var traceRecorder: CapturingTraceRecorder

    @BeforeEach
    fun setUp() {
        fixture.setUp()
        momentumCache = BoundedChannelMomentumCache(timeSource = { fixture.simulatedTime })
        traceRecorder = CapturingTraceRecorder()
        fixture.filter =
            ChatterNoiseFilter(
                cooldownCache = fixture.cooldownCache,
                momentumCache = momentumCache,
            )
        fixture.contextCollector =
            ChatterContextCollector(
                noiseFilter = fixture.filter,
                peekResolver = { null },
            )
        fixture.service =
            ChatterService(
                filter = fixture.filter,
                decisionClient = fixture.decisionClient,
                chatterGenerator = fixture.chatterGenerator,
                contextCollector = fixture.contextCollector,
                channelPort = fixture.channelPort,
                traceRecorder = traceRecorder,
                personaStateProvider = CircadianPersonaDrift(enabled = false),
            )
    }

    @AfterEach
    fun tearDown() = fixture.tearDown()

    private fun stubGuild(
        msg: dev.kord.core.entity.Message,
        guildId: Long = 500L,
    ) {
        every { msg.data.guildId } returns OptionalSnowflake.Value(Snowflake(guildId))
    }

    @Test
    fun `direct mention starts momentum so ambient follow-up at 2s passes cooldown and gets threshold boost`() =
        runTest {
            val channelId = 100L
            val botSelfId = Snowflake(9999L)
            val mentionMsg =
                fixture.createMockMessage(
                    id = 1L,
                    channelId = channelId,
                    authorId = 10L,
                    username = "Alice",
                    content = "<@9999> 早安",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            stubGuild(mentionMsg)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "早安呀！"

            assertTrue(fixture.service.processMessage(mentionMsg), "Direct mention should deliver reply")
            val afterMention = momentumCache.stateFor(Snowflake(channelId))
            assertTrue(afterMention.active, "Direct mention delivery should activate momentum")
            assertEquals(0, afterMention.ambientTurnCount)

            // 2s later (< 5s cooldown, >= 1.5s floor), another user chimes in without @mention
            fixture.simulatedTime += 2_000L
            val followUpMsg =
                fixture.createMockMessage(
                    id = 2L,
                    channelId = channelId,
                    authorId = 20L,
                    username = "Bob",
                    content = "今天天氣超好",
                    selfId = botSelfId,
                )
            stubGuild(followUpMsg)
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = true, confidence = 0.65, noul = 0.65)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "對啊！"

            assertTrue(fixture.service.processMessage(followUpMsg), "Follow-up at 2s should pass softened cooldown and reply")

            // Threshold reduced by 0.10 (0.80 -> 0.70), reactionThreshold stays 0.55
            coVerify { fixture.decisionClient.decide(any(), any(), effectiveThreshold = match { kotlin.math.abs(it - 0.70) < 1e-9 }) }
            val followUpTrace = traceRecorder.records.last()
            assertEquals(true, followUpTrace.momentumActive)
            assertEquals(0, followUpTrace.momentumTurnCount)
            assertEquals(-0.10, followUpTrace.momentumThresholdOffset)
            assertEquals(0.55, followUpTrace.reactionThreshold)

            // Ambient reply incremented turn count to 1
            val afterAmbient = momentumCache.stateFor(Snowflake(channelId))
            assertTrue(afterAmbient.active, "Momentum should remain active after 1 ambient turn")
            assertEquals(1, afterAmbient.ambientTurnCount)
        }

    @Test
    fun `low-affinity COOL and DISLIKED users suppress momentum threshold boost while keeping cooldown floor`() =
        runTest {
            val channelId = 100L
            val guildId = 500L
            val coolUserId = 30L
            val dislikedUserId = 40L
            val store =
                FakeRelationshipStore(
                    mapOf(
                        (guildId.toString() to coolUserId.toString()) to
                            UserRelationship(
                                guildId = guildId.toString(),
                                userId = coolUserId.toString(),
                                affinityScore = -20,
                            ),
                        (guildId.toString() to dislikedUserId.toString()) to
                            UserRelationship(
                                guildId = guildId.toString(),
                                userId = dislikedUserId.toString(),
                                affinityScore = -50,
                            ),
                    ),
                )
            every { fixture.chatterGenerator.memoryStore } returns store

            // Activate momentum via direct mention
            momentumCache.recordReply(Snowflake(channelId), isDirectMention = true, timestamp = fixture.simulatedTime)
            fixture.cooldownCache.record(Snowflake(channelId), fixture.simulatedTime)

            fixture.simulatedTime += 2_000L
            val coolMsg =
                fixture.createMockMessage(
                    id = 10L,
                    channelId = channelId,
                    authorId = coolUserId,
                    username = "CoolUser",
                    content = "hi",
                )
            stubGuild(coolMsg, guildId)
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = false, confidence = 0.2, noul = 0.2)

            assertFalse(fixture.service.processMessage(coolMsg), "Low-score COOL message should not participate")
            // 0.80 + 0.10 (COOL) + 0.0 (momentum suppressed) = 0.90
            coVerify { fixture.decisionClient.decide(any(), any(), effectiveThreshold = match { kotlin.math.abs(it - 0.90) < 1e-9 }) }
            val coolTrace = traceRecorder.records.last()
            assertEquals(true, coolTrace.momentumActive)
            assertEquals(0.0, coolTrace.momentumThresholdOffset)

            fixture.simulatedTime += 2_000L
            val dislikedMsg =
                fixture.createMockMessage(
                    id = 11L,
                    channelId = channelId,
                    authorId = dislikedUserId,
                    username = "DislikedUser",
                    content = "yo",
                )
            stubGuild(dislikedMsg, guildId)
            assertFalse(fixture.service.processMessage(dislikedMsg), "Low-score DISLIKED message should not participate")
            // 0.80 + 0.22 (DISLIKED) + 0.0 (momentum suppressed) = 1.02 -> clamped to 0.95
            coVerify { fixture.decisionClient.decide(any(), any(), effectiveThreshold = match { kotlin.math.abs(it - 0.95) < 1e-9 }) }
            val dislikedTrace = traceRecorder.records.last()
            assertEquals(true, dislikedTrace.momentumActive)
            assertEquals(0.0, dislikedTrace.momentumThresholdOffset)
        }

    @Test
    fun `exhausted momentum restores full cooldown and removes threshold boost until mention or window expiry`() =
        runTest {
            val channelId = 100L
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = true, confidence = 0.95, noul = 0.95)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "ok"

            // Direct mention activates momentum
            momentumCache.recordReply(Snowflake(channelId), isDirectMention = true, timestamp = fixture.simulatedTime)
            fixture.cooldownCache.record(Snowflake(channelId), fixture.simulatedTime)

            // 2 consecutive ambient deliveries exhaust maxTurns=2
            repeat(2) { idx ->
                fixture.simulatedTime += 2_000L
                val msg =
                    fixture.createMockMessage(
                        id = (idx + 1).toLong(),
                        channelId = channelId,
                        authorId = 20L,
                        content = "turn ${idx + 1}",
                    )
                stubGuild(msg)
                assertTrue(fixture.service.processMessage(msg), "Ambient turn ${idx + 1} should deliver")
            }

            val exhausted = momentumCache.stateFor(Snowflake(channelId))
            assertFalse(exhausted.active, "Momentum should be exhausted after 2 ambient turns")
            assertEquals(2, exhausted.ambientTurnCount)

            // 3rd ambient message at +2s (< 30s full cooldown) is rejected by cooldown
            fixture.simulatedTime += 2_000L
            val blockedMsg = fixture.createMockMessage(id = 4L, channelId = channelId, authorId = 20L, content = "too fast")
            stubGuild(blockedMsg)
            assertFalse(fixture.service.processMessage(blockedMsg), "Exhausted momentum should re-engage full 30s cooldown")

            // After full 30s cooldown, 3rd ambient message runs WITHOUT threshold boost (0.80); momentum window also expired at 30s
            fixture.simulatedTime += 28_000L
            val afterCooldownMsg = fixture.createMockMessage(id = 5L, channelId = channelId, authorId = 20L, content = "after 30s")
            stubGuild(afterCooldownMsg)
            assertTrue(fixture.service.processMessage(afterCooldownMsg), "Message after full 30s cooldown should deliver")
            coVerify { fixture.decisionClient.decide(any(), any(), effectiveThreshold = 0.80) }
            val trace = traceRecorder.records.last()
            assertEquals(false, trace.momentumActive)
            assertEquals(0, trace.momentumTurnCount)
            assertEquals(0.0, trace.momentumThresholdOffset)
            assertEquals(0, momentumCache.stateFor(Snowflake(channelId)).ambientTurnCount)
        }

    @Test
    fun `silent reaction path and blank reply do not start or extend momentum`() =
        runTest {
            val channelId = 100L

            // Silent reaction path (noul=0.50 in [0.45, 0.70))
            val reactionMsg = fixture.createMockMessage(id = 20L, channelId = channelId, authorId = 20L, content = "haha")
            stubGuild(reactionMsg)
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = false, confidence = 0.5, noul = 0.5)
            coEvery { fixture.chatterGenerator.selectReactionEmoji(any()) } returns "😂"

            fixture.service.processMessage(reactionMsg)
            assertFalse(momentumCache.stateFor(Snowflake(channelId)).withinWindow, "Silent reaction must not start momentum")

            // Blank reply (delivery not performed)
            fixture.simulatedTime += 2_000L
            val blankMsg = fixture.createMockMessage(id = 21L, channelId = channelId, authorId = 20L, content = "hey")
            stubGuild(blankMsg)
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = true, confidence = 0.9, noul = 0.9)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "   "

            assertFalse(fixture.service.processMessage(blankMsg), "Blank reply should not deliver")
            assertFalse(momentumCache.stateFor(Snowflake(channelId)).withinWindow, "Blank reply must not start momentum")
        }
}
