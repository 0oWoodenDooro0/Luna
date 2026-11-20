package luna.chatter

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
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceTrigger
import luna.core.ai.JsonlAiTraceRecorder
import luna.plugin.DefaultPluginContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterPersonaDriftServiceAndTraceTest {
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

    @Test
    fun `ChatterService evaluates noul against lowered effectiveThreshold, propagates personaState to prompt, and records AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        // 20:30 Asia/Taipei (12:30 UTC) -> EVENING (-0.02) + ENERGETIC (-0.08) -> effectiveThreshold = 0.7 - 0.10 = 0.60
        val eveningInstant = Instant.parse("2026-11-19T12:30:00Z")
        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                clock = Clock.fixed(eveningInstant, ZoneId.of("UTC")),
                random = fixedMoodRandom(PersonaMood.ENERGETIC),
            )

        var capturedSystemInstruction: String? = null
        val mockEngine =
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/decisions")) {
                    // noul = 0.65 is below baseThreshold (0.7) but >= effectiveThreshold (0.60)
                    respond(
                        content = """{"answers":{"should_participate":{"type":"noul","noul":0.65,"confidence":0.65}}}""",
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
                        content = """{"id":"int-drift-1","status":"completed","finish_reason":"STOP","output_text":"晚上大家都精神很好欸"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val sentMessages = mutableListOf<String>()
        val service =
            ChatterService(
                filter = ChatterNoiseFilter(),
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.7),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "secret-key", searchGroundingEnabled = false),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, content ->
                    sentMessages.add(content)
                    mockk(relaxed = true)
                },
                traceRecorder = recorder,
                personaStateProvider = drift,
            )

        val message =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 901L,
                channelId = 902L,
                content = "今晚要打什麼遊戲",
            )

        val handled = service.processMessage(message)
        recorder.flushAndClose()

        assertTrue(handled, "Expected noul=0.65 to participate when effectiveThreshold=0.60")
        assertEquals(listOf("晚上大家都精神很好欸"), sentMessages)

        val sysPrompt = assertNotNull(capturedSystemInstruction)
        assertTrue(sysPrompt.contains("星期四"))
        assertTrue(sysPrompt.contains("20:30"))
        assertTrue(sysPrompt.contains(CircadianPeriod.EVENING.promptCue))
        assertTrue(sysPrompt.contains(PersonaMood.ENERGETIC.promptCue))

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.AMBIENT, trace.trigger)
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertNull(trace.errorSummary)
        assertEquals("EVENING", trace.circadianPeriod)
        assertEquals("ENERGETIC", trace.personaMood)
        assertEquals(0.60, trace.effectiveThreshold)

        val decisionAttempt = trace.attempts.first { it.stage == AiAttemptStage.DECISION }
        assertEquals(AiAttemptOutcome.SUCCESS, decisionAttempt.parsedOutcome)
    }

    @Test
    fun `ChatterService skips when noul passes baseThreshold but fails raised late-night effectiveThreshold and records AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        // 03:15 Asia/Taipei (19:15 UTC prev day) -> LATE_NIGHT (+0.08) + TIRED (+0.08) -> effectiveThreshold = 0.7 + 0.16 = 0.86
        val lateNightInstant = Instant.parse("2026-11-18T19:15:00Z")
        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                clock = Clock.fixed(lateNightInstant, ZoneId.of("UTC")),
                random = fixedMoodRandom(PersonaMood.TIRED),
            )

        val mockEngine =
            MockEngine { request ->
                assertTrue(request.url.encodedPath.endsWith("/decisions"), "Skipped run must not invoke interactions")
                respond(
                    content = """{"answers":{"should_participate":{"type":"noul","noul":0.78,"confidence":0.78}}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val service =
            ChatterService(
                filter = ChatterNoiseFilter(),
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.7),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 1.0),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                traceRecorder = recorder,
                personaStateProvider = drift,
            )

        val message =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 911L,
                channelId = 912L,
                content = "半夜還有人在嗎",
            )

        val handled = service.processMessage(message)
        recorder.flushAndClose()

        assertFalse(handled, "Expected noul=0.78 to skip when effectiveThreshold=0.86")

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.SKIPPED, trace.outcome)
        assertEquals("LATE_NIGHT", trace.circadianPeriod)
        assertEquals("TIRED", trace.personaMood)
        assertEquals(0.86, trace.effectiveThreshold)
        assertTrue(trace.errorSummary?.contains("0.86") == true)
        assertEquals(AiAttemptOutcome.SKIPPED, trace.attempts.single().parsedOutcome)
    }

    @Test
    fun `ChatterService direct mention injects circadian persona cues and records AI Trace state even without DECISION`(
        @TempDir tempDir: Path,
    ) = runTest {
        // 09:10 Asia/Taipei (01:10 UTC) -> MORNING (+0.02) + STEADY (0.0) -> effectiveThreshold = 0.72
        val morningInstant = Instant.parse("2026-11-19T01:10:00Z")
        val drift =
            CircadianPersonaDrift(
                timezone = "Asia/Taipei",
                clock = Clock.fixed(morningInstant, ZoneId.of("UTC")),
                random = fixedMoodRandom(PersonaMood.STEADY),
            )

        var capturedSystemInstruction: String? = null
        val mockEngine =
            MockEngine { request ->
                val reqJson =
                    ChatterAiTraceTestSupport.clientJson
                        .parseToJsonElement(request.body.toByteArray().decodeToString())
                        .jsonObject
                capturedSystemInstruction = reqJson["system_instruction"]?.jsonPrimitive?.content
                respond(
                    content = """{"id":"int-mention-drift","status":"completed","finish_reason":"STOP","output_text":"早安剛醒"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val service =
            ChatterService(
                filter = ChatterNoiseFilter(),
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.7),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "secret-key", searchGroundingEnabled = false),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, _ -> mockk(relaxed = true) },
                traceRecorder = recorder,
                personaStateProvider = drift,
            )

        val selfId = Snowflake(9999L)
        val mentionMessage =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 921L,
                channelId = 922L,
                content = "<@9999> 早安",
                mentionedUserIds = setOf(selfId),
                selfId = selfId,
            )

        assertTrue(service.processMessage(mentionMessage))
        recorder.flushAndClose()

        val sysPrompt = assertNotNull(capturedSystemInstruction)
        assertTrue(sysPrompt.contains("09:10"))
        assertTrue(sysPrompt.contains(CircadianPeriod.MORNING.promptCue))
        assertTrue(sysPrompt.contains(PersonaMood.STEADY.promptCue))

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals("MORNING", trace.circadianPeriod)
        assertEquals("STEADY", trace.personaMood)
        assertEquals(0.72, trace.effectiveThreshold)
    }

    @Test
    fun `ChatterPlugin wires CHATTER_TIMEZONE and CHATTER_DRIFT_ENABLED to CircadianPersonaDrift and ChatterService`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockKord = mockk<Kord>(relaxed = true)
        every { mockKord.coroutineContext } returns EmptyCoroutineContext
        every { mockKord.events } returns MutableSharedFlow<Event>()

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val context =
            DefaultPluginContext(
                kord = mockKord,
                routing = mockk(relaxed = true),
                database = mockk(relaxed = true),
                env =
                    luna.config.EnvReader.of(
                        mapOf(
                            "GEMINI_API_KEY" to "plugin-gemini-key",
                            "CHATTER_TIMEZONE" to "Asia/Tokyo",
                            "CHATTER_DRIFT_ENABLED" to "false",
                        ),
                    ),
                aiTraceRecorder = recorder,
            )

        val plugin = ChatterPlugin()
        plugin.initialize(context)

        val provider = assertNotNull(plugin.personaStateProvider)
        assertTrue(provider is CircadianPersonaDrift)
        assertEquals("Asia/Tokyo", provider.resolvedTimezone)
        assertFalse(provider.enabled)
        assertEquals(provider, plugin.service?.personaStateProvider)

        val state = provider.currentState(0.7)
        assertFalse(state.driftEnabled)
        assertNull(state.circadianPeriod)
        assertNull(state.mood)
        assertEquals(0.7, state.effectiveThreshold)

        plugin.shutdown()
        recorder.flushAndClose()
    }
}
