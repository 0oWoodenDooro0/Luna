package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.ReactionEmoji
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.Routing
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import luna.core.ai.AiTraceOutcome
import luna.core.ai.JsonlAiTraceRecorder
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatterSilentReactionConcurrencyAndPluginTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun createClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    private fun fixedPersonaProvider(effectiveThreshold: Double = 0.70): PersonaStateProvider =
        object : PersonaStateProvider {
            override fun currentState(baseThreshold: Double): PersonaState =
                PersonaState(
                    driftEnabled = false,
                    timezone = "Asia/Taipei",
                    localTime = ZonedDateTime.of(2026, 11, 19, 21, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                    formattedWeekday = "星期四",
                    formattedTime = "21:00",
                    circadianPeriod = null,
                    mood = null,
                    baseThreshold = baseThreshold,
                    effectiveThreshold = effectiveThreshold,
                )
        }

    @Test
    fun `burst interruption during Silent Reaction reading simulation cancels before addReaction and skips trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val decisionEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val mockEngine =
            MockEngine { request ->
                if (request.url.toString().contains("/v1beta/interactions")) {
                    respond(
                        content = """{"id":"int-ok","status":"completed","finish_reason":"STOP","output_text":"🔥"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    decisionEntered.complete(Unit)
                    releaseFirst.await()
                    respond(
                        content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.55,"confidence":0.80}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })
        val filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
        val decisionClient =
            GeminiDecisionClient(
                config = ChatterConfig(apiKey = "secret-key", decision = ChatterConfig.Decision(threshold = 0.70)),
                httpClient = createClient(mockEngine),
                traceRecorder = recorder,
            )
        val chatterGenerator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45),
                httpClient = createClient(mockEngine),
                traceRecorder = recorder,
            )

        val channelPort = FakeChatterChannelPort()
        val service =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = chatterGenerator,
                channelPort = channelPort,
                contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                traceRecorder = recorder,
                burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 100L),
                randomProvider = { _, _ -> 1000L },
                personaStateProvider = fixedPersonaProvider(effectiveThreshold = 0.70),
            )

        val msg1 = ChatterAiTraceTestSupport.createMockMessage(id = 301L, channelId = 501L, content = "第一則")
        val msg2 = ChatterAiTraceTestSupport.createMockMessage(id = 302L, channelId = 501L, content = "第二則補充")

        val job1 = async { service.processMessage(msg1) }
        advanceTimeBy(101L)
        runCurrent()
        decisionEntered.await()

        // Second message arrives while msg1 is in decision/silent reaction -> cancels msg1 burst
        val job2 = async { service.processMessage(msg2) }
        releaseFirst.complete(Unit)
        advanceTimeBy(2_000L)
        runCurrent()

        assertFalse(job1.await(), "Cancelled first message must return false without adding reaction")
        assertTrue(job2.await(), "Second message should complete silent reaction")
        recorder.flushAndClose()

        assertEquals(
            listOf<Pair<Snowflake, ReactionEmoji>>(Snowflake(302L) to ReactionEmoji.Unicode("🔥")),
            channelPort.addedReactions.map { it.targetMessageId to it.emoji },
        )
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(2, traces.size)
        assertEquals(AiTraceOutcome.SKIPPED, traces.first { it.discord?.messageId == "301" }.outcome)
        assertEquals(AiTraceOutcome.SUCCESS, traces.first { it.discord?.messageId == "302" }.outcome)
    }

    @Test
    fun `ChatterPlugin wires CHATTER_REACTION_THRESHOLD cleanly to ChatterGenerator`() =
        runTest {
            val context =
                DefaultPluginContext(
                    kord = mockk<Kord>(relaxed = true),
                    routing = mockk<Routing>(relaxed = true),
                    database = mockk<Database>(relaxed = true),
                    env =
                        luna.config.EnvReader.of(
                            "GEMINI_API_KEY" to "plugin-key",
                            "CHATTER_REACTION_THRESHOLD" to "0.52",
                        ),
                )
            val plugin = ChatterPlugin()
            plugin.initialize(context)
            assertEquals(0.52, plugin.chatterGenerator?.config?.reactionThreshold)
            plugin.shutdown()
        }
}
