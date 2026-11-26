package luna.chatter

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.ReactionEmoji
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterSilentReactionServiceTest {
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

    private fun fixedPersonaProvider(
        effectiveThreshold: Double = 0.70,
        driftEnabled: Boolean = false,
    ): PersonaStateProvider =
        object : PersonaStateProvider {
            override fun currentState(baseThreshold: Double): PersonaState =
                PersonaState(
                    driftEnabled = driftEnabled,
                    timezone = "Asia/Taipei",
                    localTime = ZonedDateTime.of(2026, 11, 19, 21, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                    formattedWeekday = "星期四",
                    formattedTime = "21:00",
                    circadianPeriod = if (driftEnabled) CircadianPeriod.EVENING else null,
                    mood = if (driftEnabled) PersonaMood.MELLOW else null,
                    baseThreshold = baseThreshold,
                    effectiveThreshold = effectiveThreshold,
                )
        }

    private fun decisionJson(
        noul: Double,
        confidence: Double = 0.80,
    ): String = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":$noul,"confidence":$confidence}}}"""

    @Test
    fun `intermediate noul routes to Silent Reaction Path with multimodal attachments, custom emoji, no typing or cooldown, and AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val mockEngine =
            MockEngine { request ->
                val url = request.url.toString()
                val body = request.body.toByteArray().decodeToString()
                requests += url to body
                when {
                    url.contains("/v1beta/interactions") && body.contains("Reaction Emoji") -> {
                        respond(
                            content = """{"id":"int-react-1","status":"completed","finish_reason":"STOP","output_text":"<a:party:555666777888>"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        // Decision API call returning intermediate score 0.55 (between 0.45 and 0.82)
                        respond(
                            content = decisionJson(noul = 0.55, confidence = 0.82),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
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

        val stickerImage =
            ChatterAiTraceTestSupport.createMockImageAttachment(
                url = "https://cdn.discordapp.com/attachments/1/2/funny.png",
                mimeType = "image/png",
                filename = "funny.png",
            )
        val message =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 101L,
                channelId = 201L,
                content = "大家看這張貓貓圖！",
                attachments = setOf(stickerImage),
            )

        val partyEmoji =
            mockk<GuildEmoji>(relaxed = true).apply {
                every { id } returns Snowflake(555666777888L)
                every { name } returns "party"
                every { isAvailable } returns true
                every { isAnimated } returns true
            }
        val channelPort =
            FakeChatterChannelPort().apply {
                guildEmojis = listOf(partyEmoji)
            }
        val service =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = chatterGenerator,
                channelPort = channelPort,
                contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                traceRecorder = recorder,
                burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                randomProvider = { min, _ -> min },
                personaStateProvider = fixedPersonaProvider(effectiveThreshold = 0.82, driftEnabled = true),
            )

        val result = service.processMessage(message)
        recorder.flushAndClose()

        assertTrue(result, "Silent reaction path should return true")
        assertEquals(
            listOf<ReactionEmoji>(ReactionEmoji.Custom(Snowflake(555666777888L), "party", isAnimated = true)),
            channelPort.addedReactions.map { it.emoji },
        )
        assertTrue(channelPort.allDeliveredTexts.isEmpty(), "Silent Reaction Path must not send text replies")
        assertEquals(0, channelPort.typingCount)
        assertFalse(cooldownCache.isOnCooldown(Snowflake(201L)), "Silent Reaction Path must not start channel cooldown")

        // Verify only 2 HTTP calls: DECISION + REACTION_SELECT (no full CHATTER_GENERATE)
        assertEquals(2, requests.size)
        val reactionReq = json.parseToJsonElement(requests[1].second).jsonObject
        val inputBlocks = reactionReq["input"]?.jsonArray
        assertEquals(
            "image",
            inputBlocks
                ?.get(0)
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "https://cdn.discordapp.com/attachments/1/2/funny.png",
            inputBlocks
                ?.get(0)
                ?.jsonObject
                ?.get("uri")
                ?.jsonPrimitive
                ?.content,
        )

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals("<a:party:555666777888>", trace.finalOutput)
        assertEquals(0.55, trace.reactionThreshold)
        assertEquals(0.82, trace.effectiveThreshold)
        assertNull(trace.errorSummary)
        assertNull(trace.failureCategory)
        assertEquals(listOf(AiAttemptStage.DECISION, AiAttemptStage.REACTION_SELECT), trace.attempts.map { it.stage })
    }

    @Test
    fun `boundary noul equal to reactionThreshold triggers silent Unicode reaction via default Message addReaction`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    val body = request.body.toByteArray().decodeToString()
                    if (request.url.toString().contains("/v1beta/interactions") && body.contains("Reaction Emoji")) {
                        respond(
                            content = """{"id":"int-react-unicode","status":"completed","finish_reason":"STOP","output_text":"👀"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = decisionJson(noul = 0.45, confidence = 0.70),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })
            val filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
            val decisionClient =
                GeminiDecisionClient(
                    config = ChatterConfig(apiKey = "secret-key", decision = ChatterConfig.Decision(threshold = 0.70)),
                    httpClient = createClient(mockEngine),
                )
            val chatterGenerator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45),
                    httpClient = createClient(mockEngine),
                )

            val guildEmojiMock =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(777L)
                    every { name } returns "available_cat"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val unavailableEmojiMock =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(888L)
                    every { name } returns "broken_cat"
                    every { isAvailable } returns false
                    every { isAnimated } returns false
                }
            val message = ChatterAiTraceTestSupport.createMockMessage(id = 102L, channelId = 202L, content = "偷偷看一下")
            val channelPort =
                FakeChatterChannelPort().apply {
                    guildEmojis = listOf(guildEmojiMock, unavailableEmojiMock)
                }

            val service =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    channelPort = channelPort,
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    personaStateProvider = fixedPersonaProvider(effectiveThreshold = 0.70),
                    config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45),
                )

            val handled = service.processMessage(message)

            assertTrue(handled, "Boundary reactionThreshold should trigger silent Unicode reaction")
            assertEquals(listOf<ReactionEmoji>(ReactionEmoji.Unicode("👀")), channelPort.addedReactions.map { it.emoji })
            assertEquals(0, channelPort.typingCount)
            assertFalse(cooldownCache.isOnCooldown(Snowflake(202L)), "Unicode reaction must not start channel cooldown")
        }

    @Test
    fun `below reactionThreshold and inverted threshold bounds bypass Silent Reaction Path cleanly`(
        @TempDir tempDir: Path,
    ) = runTest {
        var nextNoul = 0.44
        val interactionCalls = mutableListOf<String>()
        val mockEngine =
            MockEngine { request ->
                val url = request.url.toString()
                if (url.contains("/v1beta/interactions")) {
                    interactionCalls += url
                }
                respond(
                    content = decisionJson(noul = nextNoul, confidence = 0.75),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
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

        // Case 1: noul = 0.44 < reactionThreshold = 0.45
        val genStandard =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45),
                httpClient = createClient(mockEngine),
                traceRecorder = recorder,
            )
        val channelPort = FakeChatterChannelPort()
        val service1 =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = genStandard,
                channelPort = channelPort,
                contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                traceRecorder = recorder,
                burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                randomProvider = { _, _ -> 0L },
                personaStateProvider = fixedPersonaProvider(effectiveThreshold = 0.70),
            )
        assertFalse(
            service1.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 103L, channelId = 203L)),
            "noul below reactionThreshold must bypass silent reaction",
        )

        // Case 2: reactionThreshold (0.80) >= effectiveThreshold (0.70) with noul = 0.65 -> bypassed
        nextNoul = 0.65
        val invertedConfig = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.80)
        val genInverted =
            ChatterGenerator(
                config = invertedConfig,
                httpClient = createClient(mockEngine),
                traceRecorder = recorder,
            )
        val service2 =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = genInverted,
                channelPort = channelPort,
                contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                traceRecorder = recorder,
                burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                randomProvider = { _, _ -> 0L },
                personaStateProvider = fixedPersonaProvider(effectiveThreshold = 0.70),
                config = invertedConfig,
            )
        assertFalse(
            service2.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 104L, channelId = 204L)),
            "Inverted reactionThreshold >= effectiveThreshold must bypass silent reaction",
        )
        recorder.flushAndClose()

        assertTrue(interactionCalls.isEmpty(), "Neither case may call /v1beta/interactions")
        assertTrue(channelPort.addedReactions.isEmpty(), "Bypassed cases must not add reactions")
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(2, traces.size)
        assertTrue(traces.all { it.outcome == AiTraceOutcome.SKIPPED })
        assertEquals(0.55, traces[0].reactionThreshold)
        assertEquals(0.70, traces[1].reactionThreshold)
    }

    @Test
    fun `strict no-fallback on REACTION_SELECT failure or reactionSender error records SKIPPED trace, warns, and skips cooldown`(
        @TempDir tempDir: Path,
    ) = runTest {
        val serviceLogger = LoggerFactory.getLogger(ChatterService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        serviceLogger.addAppender(appender)

        try {
            var reactionFailMode = true
            val mockEngine =
                MockEngine { request ->
                    if (request.url.toString().contains("/v1beta/interactions")) {
                        if (reactionFailMode) {
                            respond(
                                content = """{"error":{"code":503,"message":"unavailable"}}""",
                                status = HttpStatusCode.ServiceUnavailable,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond(
                                content = """{"id":"int-ok","status":"completed","finish_reason":"STOP","output_text":"👍"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    } else {
                        respond(
                            content = decisionJson(noul = 0.55, confidence = 0.80),
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

            val channelPort1 = FakeChatterChannelPort()
            // 1. Reaction API returns HTTP 503 -> no fallback reaction
            val serviceApiFail =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    channelPort = channelPort1,
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    traceRecorder = recorder,
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    personaStateProvider = fixedPersonaProvider(effectiveThreshold = 0.70),
                    config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45),
                )
            assertFalse(
                serviceApiFail.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 105L, channelId = 205L)),
                "Reaction API failure must return false",
            )

            // 2. Reaction API succeeds, guildEmojis throws (fail-open), addReaction throws (Missing Permissions)
            reactionFailMode = false
            val channelPort2 =
                FakeChatterChannelPort().apply {
                    guildEmojisError = IllegalStateException("Guild emojis unavailable")
                    reactionError = RuntimeException("Missing Add Reactions permission")
                }
            val serviceSenderFail =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    channelPort = channelPort2,
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    traceRecorder = recorder,
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    personaStateProvider = fixedPersonaProvider(effectiveThreshold = 0.70),
                    config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45),
                )
            assertFalse(
                serviceSenderFail.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 106L, channelId = 206L)),
                "addReaction failure must return false",
            )
            recorder.flushAndClose()

            assertTrue(channelPort1.addedReactions.isEmpty(), "API failure must not add reactions")
            assertTrue(channelPort2.addedReactions.isEmpty(), "addReaction failure must not record added reactions")
            assertEquals(0, channelPort2.typingCount)
            assertFalse(cooldownCache.isOnCooldown(Snowflake(205L)), "Reaction API failure must not start cooldown")
            assertFalse(cooldownCache.isOnCooldown(Snowflake(206L)), "addReaction failure must not start cooldown")

            val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
            assertEquals(2, traces.size)
            assertEquals(AiTraceOutcome.SKIPPED, traces[0].outcome)
            assertEquals(AiTraceFailureCategory.HTTP_ERROR, traces[0].failureCategory)
            assertEquals(AiTraceOutcome.SKIPPED, traces[1].outcome)
            assertEquals(AiTraceFailureCategory.UNEXPECTED_ERROR, traces[1].failureCategory)
            assertTrue(
                traces[1].errorSummary.orEmpty().contains("Missing Add Reactions permission"),
                "Expected Missing Add Reactions permission in errorSummary",
            )

            val warns = appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }
            assertTrue(warns.any { it.contains("guild emojis", ignoreCase = true) })
            assertTrue(warns.any { it.contains("Missing Add Reactions permission") })
        } finally {
            serviceLogger.detachAppender(appender)
        }
    }
}
