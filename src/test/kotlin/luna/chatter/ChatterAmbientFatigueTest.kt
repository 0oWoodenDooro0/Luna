package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.test.runTest
import luna.config.ConfigValidationException
import luna.config.EnvReader
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterAmbientFatigueTest {
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

    private lateinit var momentumCache: BoundedChannelMomentumCache
    private lateinit var fatigueCache: BoundedChannelFatigueCache
    private lateinit var traceRecorder: CapturingTraceRecorder

    @BeforeEach
    fun setUp() {
        fixture.setUp()
        momentumCache = BoundedChannelMomentumCache(timeSource = { fixture.simulatedTime })
        fatigueCache = BoundedChannelFatigueCache(timeSource = { fixture.simulatedTime })
        traceRecorder = CapturingTraceRecorder()
        fixture.filter =
            ChatterNoiseFilter(
                config = ChatterFilterConfig(),
                cooldownCache = fixture.cooldownCache,
                momentumCache = momentumCache,
                fatigueCache = fatigueCache,
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
    fun `ChatterFilterConfig parses and validates ambient fatigue env vars`() {
        val defaults = ChatterConfig.from(EnvReader.of())
        assertEquals(300_000L, defaults.filter.fatigueWindowMs)
        assertEquals(0.08, defaults.filter.fatigueStep)
        assertEquals(0.24, defaults.filter.fatigueMaxOffset)

        val custom =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_FATIGUE_WINDOW_SECONDS" to "120",
                    "CHATTER_FATIGUE_STEP" to "0.05",
                    "CHATTER_FATIGUE_MAX_OFFSET" to "0.15",
                ),
            )
        assertEquals(120_000L, custom.filter.fatigueWindowMs)
        assertEquals(0.05, custom.filter.fatigueStep)
        assertEquals(0.15, custom.filter.fatigueMaxOffset)

        val disabled =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_FATIGUE_WINDOW_SECONDS" to "0",
                    "CHATTER_FATIGUE_STEP" to "0.0",
                    "CHATTER_FATIGUE_MAX_OFFSET" to "0.0",
                ),
            )
        assertEquals(0L, disabled.filter.fatigueWindowMs)
        assertEquals(0.0, disabled.filter.fatigueStep)
        assertEquals(0.0, disabled.filter.fatigueMaxOffset)

        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_FATIGUE_WINDOW_SECONDS" to "-1")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_FATIGUE_STEP" to "-0.01")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_FATIGUE_STEP" to "1.01")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_FATIGUE_MAX_OFFSET" to "-0.01")) }
        assertFailsWith<ConfigValidationException> { ChatterConfig.from(EnvReader.of("CHATTER_FATIGUE_MAX_OFFSET" to "1.01")) }
    }

    @Test
    fun `uninvited ambient replies accumulate fatigue on both effectiveThreshold and reactionThreshold and record trace`() =
        runTest {
            val channelId = 100L
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = true, confidence = 0.96, noul = 0.96)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "hi"

            val msg1 = fixture.createMockMessage(id = 1L, channelId = channelId, authorId = 20L, content = "anyone?")
            stubGuild(msg1)
            assertTrue(fixture.service.processMessage(msg1), "First ambient reply should deliver")
            assertFalse(momentumCache.stateFor(Snowflake(channelId)).active, "Uninvited ambient reply must not start momentum")
            assertEquals(1, fatigueCache.stateFor(Snowflake(channelId)).count)
            val trace1 = traceRecorder.records.last()
            assertEquals(0, trace1.ambientFatigueCount)
            assertEquals(0.0, trace1.ambientFatigueOffset)
            assertEquals(0.80, trace1.effectiveThreshold)
            assertEquals(0.55, trace1.reactionThreshold)

            // 30s later -> fatigue count=1, offset=0.08 -> effectiveThreshold=0.88, reactionThreshold=0.63
            fixture.simulatedTime += 30_000L
            val msg2 = fixture.createMockMessage(id = 2L, channelId = channelId, authorId = 21L, content = "topic 2")
            stubGuild(msg2)
            assertTrue(fixture.service.processMessage(msg2), "Second ambient reply should deliver")
            coVerify { fixture.decisionClient.decide(any(), any(), effectiveThreshold = match { kotlin.math.abs(it - 0.88) < 1e-9 }) }
            val trace2 = traceRecorder.records.last()
            assertEquals(1, trace2.ambientFatigueCount)
            assertEquals(0.08, trace2.ambientFatigueOffset ?: -1.0, 1e-9)
            assertEquals(0.88, trace2.effectiveThreshold ?: -1.0, 1e-9)
            assertEquals(0.63, trace2.reactionThreshold ?: -1.0, 1e-9)
            assertEquals(2, fatigueCache.stateFor(Snowflake(channelId)).count)

            // 30s later -> fatigue count=2, offset=0.16 -> effectiveThreshold=0.95 (clamped from 0.96), reactionThreshold=0.71
            fixture.simulatedTime += 30_000L
            val msg3 = fixture.createMockMessage(id = 3L, channelId = channelId, authorId = 22L, content = "topic 3")
            stubGuild(msg3)
            assertTrue(fixture.service.processMessage(msg3), "Third ambient reply should deliver")
            coVerify { fixture.decisionClient.decide(any(), any(), effectiveThreshold = match { kotlin.math.abs(it - 0.95) < 1e-9 }) }
            val trace3 = traceRecorder.records.last()
            assertEquals(2, trace3.ambientFatigueCount)
            assertEquals(0.16, trace3.ambientFatigueOffset ?: -1.0, 1e-9)
            assertEquals(0.95, trace3.effectiveThreshold ?: -1.0, 1e-9)
            assertEquals(0.71, trace3.reactionThreshold ?: -1.0, 1e-9)
        }

    @Test
    fun `direct mention resets fatigue and active momentum ambient turns do not accumulate fatigue`() =
        runTest {
            val channelId = 100L
            val botSelfId = Snowflake(9999L)
            fatigueCache.recordAmbientReply(Snowflake(channelId), timestamp = fixture.simulatedTime)
            fatigueCache.recordAmbientReply(Snowflake(channelId), timestamp = fixture.simulatedTime)
            assertEquals(2, fatigueCache.stateFor(Snowflake(channelId)).count)

            fixture.simulatedTime += 5_000L
            val mentionMsg =
                fixture.createMockMessage(
                    id = 10L,
                    channelId = channelId,
                    authorId = 10L,
                    username = "Alice",
                    content = "<@9999> 來聊一下",
                    mentionedUserIds = setOf(botSelfId),
                    selfId = botSelfId,
                )
            stubGuild(mentionMsg)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "好啊！"

            assertTrue(fixture.service.processMessage(mentionMsg), "Direct mention should deliver")
            assertEquals(0, fatigueCache.stateFor(Snowflake(channelId)).count, "Direct mention must reset ambient fatigue")
            assertTrue(momentumCache.stateFor(Snowflake(channelId)).active, "Direct mention must activate momentum")

            // Follow-up ambient reply during active momentum must NOT accumulate fatigue
            fixture.simulatedTime += 2_000L
            val followUpMsg =
                fixture.createMockMessage(
                    id = 11L,
                    channelId = channelId,
                    authorId = 20L,
                    content = "真的嗎",
                    selfId = botSelfId,
                )
            stubGuild(followUpMsg)
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = true, confidence = 0.85, noul = 0.85)
            assertTrue(fixture.service.processMessage(followUpMsg), "Momentum ambient follow-up should deliver")
            assertEquals(
                0,
                fatigueCache.stateFor(Snowflake(channelId)).count,
                "Ambient turn during active momentum must not accumulate fatigue",
            )
            assertEquals(1, momentumCache.stateFor(Snowflake(channelId)).ambientTurnCount)
        }

    @Test
    fun `silent reaction path never accumulates ambient fatigue`() =
        runTest {
            val channelId = 100L
            val reactionMsg = fixture.createMockMessage(id = 20L, channelId = channelId, authorId = 20L, content = "haha")
            stubGuild(reactionMsg)
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = false, confidence = 0.65, noul = 0.65)
            coEvery { fixture.chatterGenerator.selectReactionEmoji(any()) } returns "😂"

            fixture.service.processMessage(reactionMsg)
            assertEquals(0, fatigueCache.stateFor(Snowflake(channelId)).count, "Silent reaction must not accumulate fatigue")
        }

    @Test
    fun `GeminiDecisionClient DEFAULT_INSTRUCTIONS includes anti-hijack clause`() {
        assertTrue(
            GeminiDecisionClient.DEFAULT_INSTRUCTIONS.contains("Luna recently spoke in [Recent Context]"),
            "DEFAULT_INSTRUCTIONS must include the anti-hijack clause",
        )
    }
}
