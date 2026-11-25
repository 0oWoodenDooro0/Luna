package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.entity.Message
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.test.runTest
import luna.chatter.memory.ChatterMemoryStore
import luna.chatter.memory.UserRelationship
import luna.core.ai.AiTraceDiscordContext
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRecorder
import luna.core.ai.AiTraceSession
import luna.core.ai.AiTraceTrigger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatterServiceAffinityTest {
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
        private val failure: Throwable? = null,
    ) : ChatterMemoryStore by io.mockk.mockk(relaxed = true) {
        override fun getUserRelationship(
            guildId: String,
            userId: String,
            now: Long?,
        ): UserRelationship {
            failure?.let { throw it }
            return relationships[guildId to userId]
                ?: UserRelationship.neutral(guildId = guildId, userId = userId, now = now ?: 0L)
        }
    }

    @BeforeEach
    fun setUp() = fixture.setUp()

    @AfterEach
    fun tearDown() = fixture.tearDown()

    private fun createGuildMessage(
        id: Long,
        guildId: Long = 1000L,
        channelId: Long = 500L,
        authorId: Long = 101L,
        username: String = "Alice",
        content: String = "Hello everyone!",
        mentionedUserIds: Set<Snowflake> = emptySet(),
        selfId: Snowflake = Snowflake(9999L),
    ): Message {
        val msg =
            fixture.createMockMessage(
                id = id,
                channelId = channelId,
                authorId = authorId,
                username = username,
                content = content,
                mentionedUserIds = mentionedUserIds,
                selfId = selfId,
            )
        every { msg.data.guildId } returns OptionalSnowflake.Value(Snowflake(guildId))
        return msg
    }

    private fun buildService(
        baseThreshold: Double = 0.60,
        reactionThreshold: Double = 0.45,
        memoryStore: ChatterMemoryStore? = null,
        traceRecorder: AiTraceRecorder = CapturingTraceRecorder(),
    ): ChatterService {
        every { fixture.chatterGenerator.memoryStore } returns memoryStore
        return ChatterService(
            decisionClient = fixture.decisionClient,
            chatterGenerator = fixture.chatterGenerator,
            filter = fixture.filter,
            contextCollector = fixture.contextCollector,
            channelPort = fixture.channelPort,
            traceRecorder = traceRecorder,
            personaStateProvider = CircadianPersonaDrift(enabled = false),
            config =
                ChatterConfig(
                    decision = ChatterConfig.Decision(threshold = baseThreshold),
                    reactionThreshold = reactionThreshold,
                ),
        )
    }

    @Test
    fun `ambient participation applies affinityThresholdOffset, clamps to bounds, and records AI Trace fields`() =
        runTest {
            val store =
                FakeRelationshipStore(
                    relationships =
                        mapOf(
                            ("1000" to "101") to UserRelationship(guildId = "1000", userId = "101", affinityScore = 60), // FAVORITE (-0.10)
                            ("1000" to "202") to UserRelationship(guildId = "1000", userId = "202", affinityScore = -60), // DISLIKED (+0.22)
                        ),
                )

            // 1. Lower clamp: base 0.20 + (-0.10) = 0.10 -> clamped to 0.15
            val traceLow = CapturingTraceRecorder()
            val svcLow = buildService(baseThreshold = 0.20, memoryStore = store, traceRecorder = traceLow)
            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns ParticipationDecision(shouldParticipate = false, noul = 0.1)

            svcLow.processMessage(createGuildMessage(id = 1L, authorId = 101L))
            coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any(), effectiveThreshold = 0.15) }
            val recLow = traceLow.records.single()
            assertEquals("FAVORITE", recLow.relationshipTier)
            assertEquals(60, recLow.affinityScore)
            assertEquals(-0.10, recLow.affinityThresholdOffset)
            assertEquals(0.15, recLow.effectiveThreshold)

            // 2. Upper clamp: base 0.85 + (+0.22) = 1.07 -> clamped to 0.95
            val traceHigh = CapturingTraceRecorder()
            val svcHigh = buildService(baseThreshold = 0.85, memoryStore = store, traceRecorder = traceHigh)
            svcHigh.processMessage(createGuildMessage(id = 2L, authorId = 202L))
            coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any(), effectiveThreshold = 0.95) }
            val recHigh = traceHigh.records.single()
            assertEquals("DISLIKED", recHigh.relationshipTier)
            assertEquals(-60, recHigh.affinityScore)
            assertEquals(0.22, recHigh.affinityThresholdOffset)
            assertEquals(0.95, recHigh.effectiveThreshold)
        }

    @Test
    fun `DISLIKED user skips automatic friendly Silent Reaction Path and records skip reason in AI Trace`() =
        runTest {
            val store =
                FakeRelationshipStore(
                    relationships =
                        mapOf(
                            ("1000" to "202") to UserRelationship(guildId = "1000", userId = "202", affinityScore = -60), // DISLIKED
                        ),
                )
            val traceRecorder = CapturingTraceRecorder()
            val service = buildService(baseThreshold = 0.60, reactionThreshold = 0.45, memoryStore = store, traceRecorder = traceRecorder)

            coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                ParticipationDecision(shouldParticipate = false, noul = 0.55, confidence = 0.8)

            val result = service.processMessage(createGuildMessage(id = 10L, authorId = 202L))

            assertFalse(result, "Expected DISLIKED ambient message not to participate or react")
            coVerify(exactly = 0) { fixture.chatterGenerator.selectReactionEmoji(any()) }
            assertTrue(fixture.channelPort.addedReactions.isEmpty(), "Expected no reactions added for DISLIKED user")
            val record = traceRecorder.records.single()
            assertEquals(AiTraceOutcome.SKIPPED, record.outcome)
            assertTrue(
                record.errorSummary?.contains("DISLIKED") == true,
                "Expected DISLIKED skip reason in AI Trace, got: ${record.errorSummary}",
            )
        }

    @Test
    fun `getUserRelationship failure fails open to NEUTRAL with warning log`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                val failingStore = FakeRelationshipStore(failure = IllegalStateException("db unavailable"))
                val traceRecorder = CapturingTraceRecorder()
                val service = buildService(baseThreshold = 0.60, memoryStore = failingStore, traceRecorder = traceRecorder)

                coEvery { fixture.decisionClient.decide(any(), any(), any()) } returns
                    ParticipationDecision(shouldParticipate = false, noul = 0.2)

                service.processMessage(createGuildMessage(id = 20L, authorId = 101L))

                coVerify(exactly = 1) { fixture.decisionClient.decide(any(), any(), effectiveThreshold = 0.60) }
                val record = traceRecorder.records.single()
                assertEquals("NEUTRAL", record.relationshipTier)
                assertEquals(0, record.affinityScore)
                assertEquals(0.0, record.affinityThresholdOffset)

                val warnMessages = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnMessages.any { it.contains("UserRelationship") || it.contains("relationship") },
                    "Expected WARN log on relationship lookup failure, got: $warnMessages",
                )
            } finally {
                logger.detachAppender(appender)
            }
        }

    @Test
    fun `direct mention keeps allowMentionDisengagement false for NEUTRAL_WARM_FAVORITE and true for COOL_DISLIKED`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val store =
                FakeRelationshipStore(
                    relationships =
                        mapOf(
                            ("1000" to "101") to UserRelationship(guildId = "1000", userId = "101", affinityScore = 25), // WARM
                            ("1000" to "202") to UserRelationship(guildId = "1000", userId = "202", affinityScore = -25), // COOL
                            ("1000" to "303") to UserRelationship(guildId = "1000", userId = "303", affinityScore = -60), // DISLIKED
                        ),
                )
            val service = buildService(memoryStore = store)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "收到"

            service.processMessage(
                createGuildMessage(id = 30L, authorId = 101L, content = "<@9999> hi", mentionedUserIds = setOf(botSelfId)),
            )
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(match<ChatterInput> { it.authorId == "101" && !it.allowMentionDisengagement })
            }

            service.processMessage(
                createGuildMessage(id = 31L, authorId = 202L, content = "<@9999> hi", mentionedUserIds = setOf(botSelfId)),
            )
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(match<ChatterInput> { it.authorId == "202" && it.allowMentionDisengagement })
            }

            service.processMessage(
                createGuildMessage(id = 32L, authorId = 303L, content = "<@9999> hi", mentionedUserIds = setOf(botSelfId)),
            )
            coVerify(exactly = 1) {
                fixture.chatterGenerator.generateReply(match<ChatterInput> { it.authorId == "303" && it.allowMentionDisengagement })
            }
        }

    @Test
    fun `COOL or DISLIKED direct mention handles NO_REPLY, COLD_REACT, and strips mixed sentinels before delivery`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val store =
                FakeRelationshipStore(
                    relationships =
                        mapOf(
                            ("1000" to "202") to UserRelationship(guildId = "1000", userId = "202", affinityScore = -55), // DISLIKED
                        ),
                )

            // 1. [NO_REPLY] -> no text sent, AI Trace SKIPPED
            val traceNoReply = CapturingTraceRecorder()
            val svc1 = buildService(memoryStore = store, traceRecorder = traceNoReply)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "[NO_REPLY]"
            val res1 =
                svc1.processMessage(
                    createGuildMessage(id = 40L, authorId = 202L, content = "<@9999> hey", mentionedUserIds = setOf(botSelfId)),
                )
            assertFalse(res1, "Expected NO_REPLY disengagement to return false")
            assertTrue(fixture.channelPort.sentMessages.isEmpty(), "Expected no Discord text sent on NO_REPLY")
            assertEquals(AiTraceOutcome.SKIPPED, traceNoReply.records.single().outcome)

            // 2. [COLD_REACT] -> routes to silentReactionCoordinator with coldReaction = true, no text sent
            val traceColdReact = CapturingTraceRecorder()
            val svc2 = buildService(memoryStore = store, traceRecorder = traceColdReact)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "[COLD_REACT]"
            coEvery { fixture.chatterGenerator.selectReactionEmoji(any()) } returns "😒"
            val res2 =
                svc2.processMessage(
                    createGuildMessage(id = 41L, authorId = 202L, content = "<@9999> hey again", mentionedUserIds = setOf(botSelfId)),
                )
            assertTrue(res2, "Expected COLD_REACT disengagement to return true after adding reaction")
            assertTrue(fixture.channelPort.sentMessages.isEmpty(), "Expected no Discord text sent on COLD_REACT")
            assertEquals(1, fixture.channelPort.addedReactions.size)
            coVerify(exactly = 1) {
                fixture.chatterGenerator.selectReactionEmoji(match { it.coldReaction })
            }

            // 3. Mixed sentinel -> stripped before delivery
            val svc3 = buildService(memoryStore = store)
            coEvery { fixture.chatterGenerator.generateReply(any<ChatterInput>()) } returns "走開 [NO_REPLY]"
            val res3 =
                svc3.processMessage(
                    createGuildMessage(id = 42L, authorId = 202L, content = "<@9999> hello", mentionedUserIds = setOf(botSelfId)),
                )
            assertTrue(res3, "Expected mixed sentinel reply to deliver stripped text")
            assertEquals(listOf("走開"), fixture.channelPort.sentMessages.map { it.content })
        }
}
