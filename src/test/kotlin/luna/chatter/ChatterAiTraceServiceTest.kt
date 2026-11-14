package luna.chatter

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.Event
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
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
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatterAiTraceServiceTest {
    @Test
    fun `ChatterPlugin injects context aiTraceRecorder into decisionClient, generator, and service`(
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
                configMap = mapOf("GEMINI_API_KEY" to "plugin-gemini-key"),
                aiTraceRecorder = recorder,
            )

        val plugin = ChatterPlugin()
        plugin.initialize(context)

        assertSame(recorder, plugin.decisionClient?.traceRecorder)
        assertSame(recorder, plugin.chatterGenerator?.traceRecorder)
        assertSame(recorder, plugin.service?.traceRecorder)

        plugin.shutdown()
        recorder.flushAndClose()
    }

    @Test
    fun `ChatterPlugin preserves backwards compatibility with custom factories`() =
        runTest {
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns MutableSharedFlow<Event>()

            val customDecisionClient = mockk<GeminiDecisionClient>(relaxed = true)
            val customGenerator = mockk<ChatterGenerator>(relaxed = true)
            val customService = mockk<ChatterService>(relaxed = true)

            val plugin =
                ChatterPlugin(
                    decisionClientFactory = { customDecisionClient },
                    generatorFactory = { customGenerator },
                    serviceFactory = { customService },
                )
            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = mockk(relaxed = true),
                    configMap = mapOf("GEMINI_API_KEY" to "k"),
                )

            plugin.initialize(context)
            assertSame(customDecisionClient, plugin.decisionClient)
            assertSame(customGenerator, plugin.chatterGenerator)
            assertSame(customService, plugin.service)
            plugin.shutdown()
        }

    @Test
    fun `ChatterNoiseFilter rejections emit zero AI Trace records`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })
        cooldownCache.record(200L, 100_000L)
        val filter =
            ChatterNoiseFilter(
                cooldownCache = cooldownCache,
                allowedChannels = setOf(Snowflake(200L), Snowflake(201L)),
            )
        val decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = "k"), traceRecorder = recorder)
        val generator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder)
        val service =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = generator,
                cooldownCache = cooldownCache,
                historyFetcher = { _, _ -> emptyList() },
                traceRecorder = recorder,
            )

        // 1. Bot message
        assertFalse(
            service.processMessage(
                ChatterAiTraceTestSupport.createMockMessage(id = 1L, channelId = 201L, isBot = true, content = "Bot hello?"),
            ),
        )
        // 2. Slash/prefix command
        assertFalse(service.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 2L, channelId = 201L, content = "/help")))
        assertFalse(service.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 3L, channelId = 201L, content = "!ping")))
        // 3. Cooldown channel (200L is on cooldown)
        assertFalse(
            service.processMessage(
                ChatterAiTraceTestSupport.createMockMessage(id = 4L, channelId = 200L, content = "Should we discuss Kotlin?"),
            ),
        )
        // 4. Disallowed channel (999L not in whitelist)
        assertFalse(
            service.processMessage(
                ChatterAiTraceTestSupport.createMockMessage(id = 5L, channelId = 999L, content = "Hello in disallowed channel?"),
            ),
        )
        // 5. Blank message without attachments/embeds and system notice
        assertFalse(service.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 6L, channelId = 201L, content = "   ")))
        assertFalse(
            service.processMessage(
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 7L,
                    channelId = 201L,
                    type = MessageType.ChannelPinnedMessage,
                    content = "Pinned",
                ),
            ),
        )

        recorder.flushAndClose()
        assertEquals(emptyList(), ChatterAiTraceTestSupport.readTraceLines(tempDir))
    }

    @Test
    fun `direct mention skips DECISION, emits MENTION trace with CHATTER_GENERATE attempt, final reply, and JsonLogger correlation`(
        @TempDir tempDir: Path,
    ) = runTest {
        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        jsonLogger.addAppender(appender)

        try {
            val mockEngine =
                MockEngine { request ->
                    assertFalse(request.url.encodedPath.contains("/decisions"), "Direct mention must not call /v1beta/decisions")
                    respond(
                        content =
                            """
                            {
                              "id": "int-mention-1",
                              "status": "completed",
                              "finish_reason": "STOP",
                              "usage": {
                                "input_tokens": 80,
                                "output_tokens": 14,
                                "total_tokens": 94
                              },
                              "steps": [
                                {
                                  "type": "model_output",
                                  "content": [{"type": "text", "text": "Kotlin 協程寫起來超順手 😄"}]
                                }
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
            val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
            val filter = ChatterNoiseFilter()
            val decisionClient =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "secret-gemini-key"),
                    httpClient = httpClient,
                    traceRecorder = recorder,
                )
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "secret-gemini-key", searchGroundingEnabled = true),
                    httpClient = httpClient,
                    traceRecorder = recorder,
                )
            val sentMessages = mutableListOf<String>()
            val service =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = generator,
                    historyFetcher = { _, _ -> emptyList() },
                    messageSender = { _, content ->
                        sentMessages.add(content)
                        mockk(relaxed = true)
                    },
                    traceRecorder = recorder,
                )

            val selfId = Snowflake(9999L)
            val mentionMsg =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 501L,
                    channelId = 601L,
                    guildId = 701L,
                    authorId = 801L,
                    username = "Alice",
                    content = "<@9999> 你覺得 Kotlin 協程好用嗎",
                    mentionedUserIds = setOf(selfId),
                    selfId = selfId,
                )

            val handled = service.processMessage(mentionMsg)
            recorder.flushAndClose()

            assertTrue(handled)
            assertEquals(listOf("Kotlin 協程寫起來超順手"), sentMessages)

            val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
            assertEquals(1, traces.size)
            val trace = traces.single()
            assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
            assertEquals(AiTraceTrigger.MENTION, trace.trigger)
            assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
            assertFalse(trace.degraded)
            assertNull(trace.failureCategory)
            assertEquals("701", trace.discord.guildId)
            assertEquals("601", trace.discord.channelId)
            assertEquals("501", trace.discord.messageId)
            assertEquals("801", trace.discord.authorId)
            assertTrue(trace.discord.isDirectMention)
            assertEquals("Kotlin 協程寫起來超順手", trace.finalOutput)
            assertEquals(1, trace.attemptCount)
            assertEquals(listOf("CHATTER_GENERATE"), trace.fallbackPath)
            assertTrue(trace.attempts.none { it.stage == AiAttemptStage.DECISION })

            val genAttempt = trace.attempts.single()
            assertEquals(AiAttemptStage.CHATTER_GENERATE, genAttempt.stage)
            assertEquals(200, genAttempt.httpStatus)
            assertEquals("STOP", genAttempt.finishReason)
            assertEquals(94, genAttempt.usageMetadata?.totalTokenCount)
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/interactions?key=******",
                genAttempt.endpoint,
            )
            assertFalse(genAttempt.endpoint?.contains("secret-gemini-key") == true)

            val correlationEvent =
                appender.list.firstOrNull { event ->
                    event.formattedMessage.contains("traceRecorded") ||
                        event.argumentArray?.any { it.toString().contains(trace.traceId) } == true
                }
            assertNotNull(correlationEvent, "Expected JsonLogger correlation entry for traceId ${trace.traceId}")
            val argsJoined = correlationEvent.argumentArray?.joinToString(" ") { it.toString() }.orEmpty()
            assertTrue(argsJoined.contains(trace.traceId))
            assertTrue(argsJoined.contains("SUCCESS"))
            assertFalse(argsJoined.contains("你覺得 Kotlin 協程好用嗎"), "JsonLogger must not duplicate raw prompts")
        } finally {
            jsonLogger.detachAppender(appender)
        }
    }

    @Test
    fun `ChatterService ambient decision returning false emits SKIPPED trace with DECISION attempt and noul score`(
        @TempDir tempDir: Path,
    ) = runTest {
        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        jsonLogger.addAppender(appender)

        try {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "model": "gemini-decision-1",
                              "answers": {
                                "should_participate": {
                                  "type": "noul",
                                  "noul": 0.31,
                                  "confidence": 0.69
                                }
                              }
                            }
                            """.trimIndent(),
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
                            config = ChatterConfig(apiKey = "secret-key"),
                            httpClient = httpClient,
                            traceRecorder = recorder,
                        ),
                    historyFetcher = { _, _ -> emptyList() },
                    traceRecorder = recorder,
                )

            val message =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 601L,
                    channelId = 602L,
                    guildId = 603L,
                    authorId = 604L,
                    content = "Does anyone know what time lunch is?",
                )

            val handled = service.processMessage(message)
            recorder.flushAndClose()

            assertFalse(handled)
            val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
            assertEquals(1, traces.size)
            val trace = traces.single()
            assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
            assertEquals(AiTraceTrigger.AMBIENT, trace.trigger)
            assertEquals(AiTraceOutcome.SKIPPED, trace.outcome)
            assertFalse(trace.degraded)
            assertEquals(1, trace.attemptCount)
            assertEquals(listOf("DECISION"), trace.fallbackPath)
            assertTrue(trace.errorSummary?.contains("0.31") == true)
            assertTrue(trace.errorSummary?.contains("0.7") == true)

            val attempt = trace.attempts.single()
            assertEquals(AiAttemptStage.DECISION, attempt.stage)
            assertEquals(AiAttemptOutcome.SKIPPED, attempt.parsedOutcome)
            val inputBlocks =
                attempt.request
                    ?.payload
                    ?.jsonObject
                    ?.get("input")
                    ?.jsonArray
            assertNotNull(inputBlocks)
            assertTrue(
                inputBlocks
                    .first()
                    .jsonObject["text"]
                    ?.jsonPrimitive
                    ?.content
                    ?.contains("Does anyone know what time lunch is?") == true,
            )

            val correlationEvent =
                appender.list.firstOrNull { event ->
                    event.argumentArray?.any { it.toString().contains(trace.traceId) } == true
                }
            assertNotNull(correlationEvent)
            val argsJoined = correlationEvent.argumentArray?.joinToString(" ") { it.toString() }.orEmpty()
            assertTrue(argsJoined.contains("SKIPPED"))
            assertFalse(argsJoined.contains("Does anyone know what time lunch is?"))
        } finally {
            jsonLogger.detachAppender(appender)
        }
    }

    @Test
    fun `ChatterService ambient multimodal decision retry and Interactions CHATTER_GENERATE preserve degraded true and fallbackPath`(
        @TempDir tempDir: Path,
    ) = runTest {
        var decisionCall = 0
        val mockEngine =
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/decisions")) {
                    decisionCall++
                    if (decisionCall == 1) {
                        respond(
                            content = """{"error":{"code":500,"message":"Decision vision backend error"}}""",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"answers":{"should_participate":{"type":"noul","noul":0.92,"confidence":0.92}}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                } else {
                    respond(
                        content = """{"id":"int-amb-1","status":"completed","finish_reason":"STOP","output_text":"這題看起來不難"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
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
                        config = ChatterConfig(apiKey = "secret-key", searchGroundingEnabled = true),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, _ -> mockk(relaxed = true) },
                traceRecorder = recorder,
            )

        val message =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 701L,
                channelId = 702L,
                content = "大家看看這張圖",
                attachments = setOf(ChatterAiTraceTestSupport.createMockImageAttachment()),
            )

        val handled = service.processMessage(message)
        recorder.flushAndClose()

        assertTrue(handled)
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTraceTrigger.AMBIENT, trace.trigger)
        assertEquals(AiTraceOutcome.DEGRADED_SUCCESS, trace.outcome)
        assertTrue(trace.degraded)
        assertEquals(3, trace.attemptCount)
        assertEquals(
            listOf(
                "DECISION:multimodal",
                "DECISION:text-only",
                "CHATTER_GENERATE",
            ),
            trace.fallbackPath,
        )
        assertEquals("這題看起來不難", trace.finalOutput)
        assertEquals(listOf(1, 2, 3), trace.attempts.map { it.attemptIndex })
    }
}
