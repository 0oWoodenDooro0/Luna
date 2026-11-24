package luna.chatter.schedule

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.Event
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.ChatterAiTraceTestSupport
import luna.chatter.ChatterConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterNoiseFilter
import luna.chatter.ChatterPlugin
import luna.chatter.ChatterService
import luna.chatter.CircadianPersonaDrift
import luna.chatter.FakeChatterChannelPort
import luna.chatter.GeminiDecisionClient
import luna.chatter.PersonaMood
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTraceTrigger
import luna.core.ai.JsonlAiTraceRecorder
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DailyScheduleChatterGatingTest {
    private fun fixedMoodRandom(mood: PersonaMood): Random {
        val index = PersonaMood.entries.indexOf(mood)
        return object : Random() {
            override fun nextBits(bitCount: Int): Int = 0

            override fun nextInt(until: Int): Int = index.coerceAtMost(until - 1)

            override fun nextLong(
                from: Long,
                until: Long,
            ): Long = until - 1L
        }
    }

    private fun scheduleWithBlock(
        title: String,
        availability: AvailabilityState,
    ): DailySchedule =
        DailySchedule(
            date = LocalDate.of(2026, 11, 19),
            timezone = "Asia/Taipei",
            blocks =
                listOf(
                    ScheduleBlock(
                        startMinute = 0,
                        endMinute = 1440,
                        activityTitle = title,
                        activityDetail = "測試行程細節",
                        statusText = title,
                        availability = availability,
                    ),
                ),
            summary = "測試行程",
            createdAtMillis = 1000L,
        )

    private class MutableScheduleProvider(
        var enabled: Boolean = true,
        var schedule: DailySchedule? = null,
    ) : DailyScheduleProvider {
        override fun isEnabled(): Boolean = enabled

        override fun currentSchedule(): DailySchedule? = if (enabled) schedule else null

        override fun currentBlock(): ScheduleBlock? = currentSchedule()?.blocks?.firstOrNull()
    }

    @Test
    fun `BUSY and SLEEPING skip ambient and direct mention messages with zero LLM calls, record CatchUpWatermark, and write AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        var llmCallCount = 0
        val mockEngine =
            MockEngine {
                llmCallCount++
                error("LLM should not be called during BUSY or SLEEPING")
            }

        val db = Database.connect("jdbc:sqlite:${tempDir.resolve("schedule.db")}", driver = "org.sqlite.JDBC")
        val scheduleStore = SqliteDailyScheduleStore(db)
        val scheduleProvider =
            MutableScheduleProvider(
                schedule = scheduleWithBlock("專心剪片", AvailabilityState.BUSY),
            )
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val channelPort = FakeChatterChannelPort()
        val service =
            ChatterService(
                decisionClient = GeminiDecisionClient(config = ChatterConfig(apiKey = "secret-key"), httpClient = httpClient, traceRecorder = recorder),
                chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "secret-key"), httpClient = httpClient, traceRecorder = recorder),
                filter = ChatterNoiseFilter(),
                channelPort = channelPort,
                traceRecorder = recorder,
                personaStateProvider = CircadianPersonaDrift(scheduleProvider = scheduleProvider),
                randomProvider = { _, _ -> 0L },
                config = ChatterConfig(apiKey = "secret-key"),
                scheduleProvider = scheduleProvider,
                scheduleStore = scheduleStore,
            )

        val channelId = 1001L
        val guildId = 2001L
        val ambientMsg =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 3001L,
                channelId = channelId,
                guildId = guildId,
                authorId = 4001L,
                username = "Alice",
                content = "大家今天過得怎樣",
            )
        assertFalse(service.processMessage(ambientMsg), "Expected ambient message to be skipped during BUSY")
        assertEquals(0, llmCallCount)

        // Switch to SLEEPING and send a direct @Luna mention
        scheduleProvider.schedule = scheduleWithBlock("睡覺補眠", AvailabilityState.SLEEPING)
        val selfId = Snowflake(9999UL)
        val mentionMsg =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 3002L,
                channelId = channelId,
                guildId = guildId,
                authorId = 4002L,
                username = "Bob",
                content = "<@9999> Luna 在嗎？",
                mentionedUserIds = setOf(selfId),
                selfId = selfId,
            )
        assertFalse(service.processMessage(mentionMsg), "Expected direct mention to be skipped during SLEEPING")
        assertEquals(0, llmCallCount)
        assertTrue(channelPort.allDeliveredTexts.isEmpty(), "Expected no messages delivered during BUSY/SLEEPING")
        recorder.flushAndClose()

        // Verify CatchUpWatermark persisted with first=3001, last=3002
        val watermark = scheduleStore.getCatchUpWatermark(channelId.toString())
        assertNotNull(watermark)
        assertEquals("3001", watermark.firstPendingMessageId)
        assertEquals("3002", watermark.lastPendingMessageId)
        assertEquals(guildId.toString(), watermark.guildId)

        // Verify AI Trace recorded both skips with schedule metadata
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(2, traces.size)
        assertEquals(AiTraceOutcome.SKIPPED, traces[0].outcome)
        assertEquals(AiTraceTrigger.AMBIENT, traces[0].trigger)
        assertEquals("專心剪片", traces[0].scheduleBlockTitle)
        assertEquals("BUSY", traces[0].scheduleAvailability)
        assertEquals(0, traces[0].attemptCount)

        assertEquals(AiTraceOutcome.SKIPPED, traces[1].outcome)
        assertEquals(AiTraceTrigger.MENTION, traces[1].trigger)
        assertEquals("睡覺補眠", traces[1].scheduleBlockTitle)
        assertEquals("SLEEPING", traces[1].scheduleAvailability)
        assertEquals(0, traces[1].attemptCount)
    }

    @Test
    fun `BREAK allows real-time reply with elevated threshold, quick-break cue, and AI Trace metadata`(
        @TempDir tempDir: Path,
    ) = runTest {
        var capturedSystemInstruction: String? = null
        val mockEngine =
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/decisions")) {
                    // noul = 0.77 >= 0.70 + 0.06 (BREAK) = 0.76
                    respond(
                        content = """{"answers":{"should_participate":{"type":"noul","noul":0.77,"confidence":0.8}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    val reqJson =
                        ChatterAiTraceTestSupport.clientJson
                            .parseToJsonElement(request.body.toByteArray().decodeToString())
                            .jsonObject
                    capturedSystemInstruction = reqJson["system_instruction"]?.jsonPrimitive?.content
                    respond(
                        content = """{"id":"int-break-1","status":"completed","finish_reason":"STOP","output_text":"剛起來喝口水瞄到"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }

        val scheduleProvider =
            MutableScheduleProvider(
                schedule = scheduleWithBlock("喝水伸展", AvailabilityState.BREAK),
            )
        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                clock = Clock.fixed(Instant.parse("2026-11-19T04:00:00Z"), ZoneId.of("UTC")),
                random = fixedMoodRandom(PersonaMood.STEADY),
                scheduleProvider = scheduleProvider,
            )
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val channelPort = FakeChatterChannelPort()
        val service =
            ChatterService(
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterConfig(apiKey = "secret-key", decision = ChatterConfig.Decision(threshold = 0.70)),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "secret-key"), httpClient = httpClient, traceRecorder = recorder),
                filter = ChatterNoiseFilter(),
                channelPort = channelPort,
                traceRecorder = recorder,
                personaStateProvider = drift,
                randomProvider = { _, _ -> 0L },
                config = ChatterConfig(apiKey = "secret-key", decision = ChatterConfig.Decision(threshold = 0.70)),
                scheduleProvider = scheduleProvider,
            )

        val msg =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 3010L,
                channelId = 1010L,
                guildId = 2010L,
                authorId = 4010L,
                username = "Alice",
                content = "好想喝珍珠奶茶",
            )
        assertTrue(service.processMessage(msg), "Expected BREAK message to be processed and replied")
        recorder.flushAndClose()
        assertTrue(capturedSystemInstruction?.contains("喝水伸展") == true, "Expected active BREAK block title in prompt")
        assertTrue(capturedSystemInstruction?.contains("短暫休息") == true, "Expected quick-break cue in prompt")

        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        val trace = traces.single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals(0.76, trace.effectiveThreshold ?: 0.0, 1e-6)
        assertEquals("喝水伸展", trace.scheduleBlockTitle)
        assertEquals("BREAK", trace.scheduleAvailability)
    }

    @Test
    fun `ChatterPlugin wires scheduleProvider and scheduleStore into CircadianPersonaDrift and ChatterService`(
        @TempDir tempDir: Path,
    ) = runTest {
        val db = Database.connect("jdbc:sqlite:${tempDir.resolve("plugin-schedule.db")}", driver = "org.sqlite.JDBC")
        val kord = mockk<Kord>(relaxed = true)
        every { kord.events } returns MutableSharedFlow<Event>()
        every { kord.coroutineContext } returns EmptyCoroutineContext

        val plugin = ChatterPlugin()
        try {
            plugin.initialize(
                DefaultPluginContext(
                    kord = kord,
                    routing = mockk(relaxed = true),
                    database = db,
                    env =
                        luna.config.EnvReader.of(
                            mapOf(
                                "GEMINI_API_KEY" to "test-key",
                                "CHATTER_ENABLED" to "true",
                                "CHATTER_SCHEDULE_ENABLED" to "true",
                            ),
                        ),
                ),
            )
            assertNotNull(plugin.scheduleCoordinator)
            assertNotNull(plugin.service?.scheduleProvider)
            assertNotNull(plugin.service?.scheduleStore)
        } finally {
            plugin.shutdown()
        }
    }
}
