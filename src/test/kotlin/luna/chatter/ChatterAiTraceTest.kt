package luna.chatter

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.cache.data.MessageData
import dev.kord.core.cache.data.UserData
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.event.Event
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceJson
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ChatImageAttachment
import luna.core.ai.GroundingCitation
import luna.core.ai.JsonlAiTraceRecorder
import luna.plugin.DefaultPluginContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatterAiTraceTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-14T15:30:00Z"), ZoneOffset.UTC)

    private val clientJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    private fun createHttpClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(clientJson)
            }
        }

    private fun readTraceLines(traceDir: Path): List<AiTraceRecord> {
        if (!Files.exists(traceDir)) return emptyList()
        return Files
            .list(traceDir)
            .use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                    .sorted()
                    .toList()
                    .flatMap { Files.readAllLines(it) }
            }.filter { it.isNotBlank() }
            .map { AiTraceJson.decodeFromString(AiTraceRecord.serializer(), it) }
    }

    private fun createMockMessage(
        id: Long = 100L,
        channelId: Long = 200L,
        guildId: Long? = 300L,
        authorId: Long = 400L,
        username: String = "Alice",
        content: String = "Hello Luna, what do you think?",
        isBot: Boolean = false,
        type: MessageType = MessageType.Default,
        mentionedUserIds: Set<Snowflake> = emptySet(),
        attachments: Set<Attachment> = emptySet(),
        selfId: Snowflake = Snowflake(9999L),
        channel: MessageChannel =
            mockk<MessageChannel>(relaxed = true).also { ch ->
                coEvery { ch.type() } returns Unit
                coEvery { ch.createMessage(any<String>()) } returns mockk(relaxed = true)
            },
        channelThrows: Boolean = false,
    ): Message {
        val user =
            mockk<User>().apply {
                every { this@apply.username } returns username
                every { this@apply.isBot } returns isBot
                every { this@apply.id } returns Snowflake(authorId)
            }

        val userData =
            mockk<UserData>().apply {
                every { this@apply.username } returns username
                every { this@apply.id } returns Snowflake(authorId)
            }

        val messageData =
            mockk<MessageData>().apply {
                every { this@apply.author } returns userData
                every { this@apply.content } returns content
                every { this@apply.id } returns Snowflake(id)
                every { this@apply.guildId } returns
                    if (guildId != null) {
                        OptionalSnowflake.Value(Snowflake(guildId))
                    } else {
                        OptionalSnowflake.Missing
                    }
            }

        val mockKord =
            mockk<Kord>(relaxed = true).apply {
                every { this@apply.selfId } returns selfId
            }

        return mockk<Message>().apply {
            every { this@apply.kord } returns mockKord
            every { this@apply.id } returns Snowflake(id)
            every { this@apply.channelId } returns Snowflake(channelId)
            every { this@apply.author } returns user
            every { this@apply.data } returns messageData
            every { this@apply.content } returns content
            every { this@apply.type } returns type
            every { this@apply.webhookId } returns null
            every { this@apply.attachments } returns attachments
            every { this@apply.embeds } returns emptyList()
            every { this@apply.mentionedUserIds } returns mentionedUserIds
            every { this@apply.referencedMessage } returns null
            every { this@apply.messageReference } returns null
            if (channelThrows) {
                every { this@apply.channel } throws RuntimeException("Channel lookup failed")
            } else {
                every { this@apply.channel } returns channel
                coEvery { this@apply.getChannel() } returns channel
            }
        }
    }

    private fun createMockImageAttachment(
        url: String = "https://cdn.discordapp.com/attachments/1/2/photo.png",
        mimeType: String = "image/png",
        filename: String = "photo.png",
    ): Attachment =
        mockk<Attachment>().apply {
            every { this@apply.url } returns url
            every { this@apply.contentType } returns mimeType
            every { this@apply.filename } returns filename
            every { this@apply.size } returns 1024
        }

    @Test
    fun `standalone GeminiDecisionClient records SKIPPED trace with DECISION attempt noul score and input blocks`(
        @TempDir tempDir: Path,
    ) = runTest {
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
                              "noul": 0.23,
                              "confidence": 0.77
                            }
                          }
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val client =
            GeminiDecisionClient(
                config = ChatterDecisionConfig(apiKey = "secret-decision-key", threshold = 0.7),
                httpClient = createHttpClient(mockEngine),
                traceRecorder = recorder,
            )

        val decision = client.decide("[Alice]: Anyone want coffee later?")
        recorder.flushAndClose()

        assertFalse(decision.shouldParticipate)
        assertEquals(0.23, decision.noul)

        val traces = readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.AMBIENT, trace.trigger)
        assertEquals(AiTraceOutcome.SKIPPED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("DECISION"), trace.fallbackPath)
        assertTrue(trace.errorSummary?.contains("0.23") == true)
        assertTrue(trace.errorSummary?.contains("0.7") == true)

        val attempt = trace.attempts.single()
        assertEquals(AiAttemptStage.DECISION, attempt.stage)
        assertEquals("gemini-decision-1", attempt.model)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", attempt.endpoint)
        assertEquals(200, attempt.httpStatus)
        assertEquals(AiAttemptOutcome.SKIPPED, attempt.parsedOutcome)
        assertTrue(attempt.responseRaw?.contains("\"noul\": 0.23") == true)
        assertTrue(attempt.responseRaw?.contains("\"confidence\": 0.77") == true)

        val payloadObj = attempt.request?.payload?.jsonObject
        assertNotNull(payloadObj)
        val inputBlocks = payloadObj["input"]?.jsonArray
        assertNotNull(inputBlocks)
        assertEquals(1, inputBlocks.size)
        assertEquals("text", inputBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("[Alice]: Anyone want coffee later?", inputBlocks[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `standalone GeminiDecisionClient multimodal failure retries text-only and records degraded true with both DECISION attempts`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine { request ->
                val bodyJson = clientJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                val inputSize = bodyJson["input"]!!.jsonArray.size
                if (inputSize > 1) {
                    respond(
                        content = """{"error":{"code":400,"message":"Invalid image URI"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content =
                            """
                            {
                              "model": "gemini-decision-1",
                              "answers": {
                                "should_participate": {
                                  "type": "noul",
                                  "noul": 0.84,
                                  "confidence": 0.84
                                }
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val client =
            GeminiDecisionClient(
                config = ChatterDecisionConfig(apiKey = "secret-decision-key", threshold = 0.7),
                httpClient = createHttpClient(mockEngine),
                traceRecorder = recorder,
            )

        val images =
            listOf(
                ChatImageAttachment(
                    url = "https://cdn.discordapp.com/attachments/1/2/img.png",
                    mimeType = "image/png",
                ),
            )
        val decision = client.decide("[Alice]: look at this", images)
        recorder.flushAndClose()

        assertTrue(decision.shouldParticipate)
        val traces = readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTraceOutcome.DEGRADED_SUCCESS, trace.outcome)
        assertTrue(trace.degraded)
        assertEquals(2, trace.attemptCount)
        assertEquals(listOf("DECISION:multimodal", "DECISION:text-only"), trace.fallbackPath)

        val first = trace.attempts[0]
        assertEquals(1, first.attemptIndex)
        assertEquals(AiAttemptStage.DECISION, first.stage)
        assertEquals(400, first.httpStatus)
        assertEquals(AiAttemptOutcome.ERROR, first.parsedOutcome)
        assertTrue(first.request?.hasImages == true)
        assertEquals(1, first.request?.imageCount)

        val second = trace.attempts[1]
        assertEquals(2, second.attemptIndex)
        assertEquals(AiAttemptStage.DECISION, second.stage)
        assertEquals(200, second.httpStatus)
        assertEquals(AiAttemptOutcome.SUCCESS, second.parsedOutcome)
        assertFalse(second.request?.hasImages ?: true)
        assertEquals(0, second.request?.imageCount)
    }

    @Test
    fun `standalone ChatterGenerator records single CHATTER_GENERATE FAILED attempt without retries on interaction error`(
        @TempDir tempDir: Path,
    ) = runTest {
        var callCount = 0
        val mockEngine =
            MockEngine {
                callCount++
                respond(
                    content = """{"error":{"code":400,"message":"Cannot fetch image URI"}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-chatter-key", searchGroundingEnabled = true, urlContextEnabled = true),
                httpClient = createHttpClient(mockEngine),
                traceRecorder = recorder,
            )

        val reply =
            generator.generateReply(
                ChatterInput(
                    transcript = "[Alice]: 看看這張圖 https://example.com/guide",
                    imageAttachments =
                        listOf(
                            ChatImageAttachment(
                                url = "https://cdn.discordapp.com/attachments/1/2/pic.png",
                                mimeType = "image/png",
                            ),
                        ),
                    webUrls = listOf("https://example.com/guide"),
                    isDirectMention = true,
                ),
            )
        recorder.flushAndClose()

        assertNull(reply)
        assertEquals(1, callCount)
        val traces = readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.HTTP_ERROR, trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("CHATTER_GENERATE"), trace.fallbackPath)

        val attempt = trace.attempts.single()
        assertEquals(400, attempt.httpStatus)
        assertEquals(AiAttemptOutcome.ERROR, attempt.parsedOutcome)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/interactions?key=******",
            attempt.endpoint,
        )
        assertTrue(attempt.request?.hasImages == true)
        assertEquals(1, attempt.request?.webUrlCount)
        assertEquals(listOf("url_context", "google_search"), attempt.request?.enabledTools)
    }

    @Test
    fun `standalone ChatterGenerator records webUrlCount enabledTools urlContextResults googleSearchQueries and filtered citations in AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "id": "int-chatter-trace-1",
                          "status": "completed",
                          "usage": {
                            "input_tokens": 120,
                            "output_tokens": 25,
                            "total_tokens": 145
                          },
                          "steps": [
                            {
                              "type": "url_context_result",
                              "call_id": "uc-1",
                              "result": [
                                {"retrieved_url": "https://kotlinlang.org/docs/coroutines.html", "status": "success"},
                                {"retrieved_url": "https://unsafe.example.com/bad", "status": "unsafe"}
                              ]
                            },
                            {
                              "type": "google_search_call",
                              "id": "gs-1",
                              "arguments": {"queries": ["kotlin coroutines structured concurrency"]}
                            },
                            {
                              "type": "model_output",
                              "content": [
                                {
                                  "type": "text",
                                  "text": "結構化並發用起來超乾淨",
                                  "annotations": [
                                    {"type": "url_citation", "url": "https://kotlinlang.org/docs/coroutines.html", "title": "Kotlin Docs"},
                                    {"type": "url_citation", "url": "https://docs.oracle.com/concurrency", "title": "Concurrency Reference"}
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-chatter-key", searchGroundingEnabled = true, urlContextEnabled = true),
                httpClient = createHttpClient(mockEngine),
                traceRecorder = recorder,
            )

        val reply =
            generator.generateReply(
                ChatterInput(
                    transcript = "[Alice]: 看這篇 https://kotlinlang.org/docs/coroutines.html 跟 https://unsafe.example.com/bad",
                    webUrls = listOf("https://kotlinlang.org/docs/coroutines.html", "https://unsafe.example.com/bad"),
                    isDirectMention = true,
                ),
            )
        recorder.flushAndClose()

        assertEquals("結構化並發用起來超乾淨", reply)
        val trace = readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertTrue(trace.citations.isEmpty(), "Chatter must not append citations to Discord output")

        val attempt = trace.attempts.single()
        assertEquals(2, attempt.request?.webUrlCount)
        assertEquals(listOf("url_context", "google_search"), attempt.request?.enabledTools)
        assertEquals(2, attempt.urlContextResults.size)
        assertEquals("success", attempt.urlContextResults[0].status)
        assertEquals("unsafe", attempt.urlContextResults[1].status)
        assertEquals(listOf("kotlin coroutines structured concurrency"), attempt.googleSearchQueries)
        assertEquals(2, attempt.rawCitations.size)
        assertEquals(
            listOf(GroundingCitation(title = "Concurrency Reference", url = "https://docs.oracle.com/concurrency")),
            attempt.filteredCitations,
        )
        assertEquals(145, attempt.usageMetadata?.totalTokenCount)
    }

    @Test
    fun `ChatterPlugin injects context aiTraceRecorder into decisionClient, generator, and service`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockKord = mockk<Kord>(relaxed = true)
        every { mockKord.coroutineContext } returns EmptyCoroutineContext
        every { mockKord.events } returns MutableSharedFlow<Event>()

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
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
    fun `ChatterNoiseFilter rejections emit zero AI Trace records`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
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
        assertFalse(service.processMessage(createMockMessage(id = 1L, channelId = 201L, isBot = true, content = "Bot hello?")))
        // 2. Slash/prefix command
        assertFalse(service.processMessage(createMockMessage(id = 2L, channelId = 201L, content = "/help")))
        assertFalse(service.processMessage(createMockMessage(id = 3L, channelId = 201L, content = "!ping")))
        // 3. Cooldown channel (200L is on cooldown)
        assertFalse(service.processMessage(createMockMessage(id = 4L, channelId = 200L, content = "Should we discuss Kotlin?")))
        // 4. Disallowed channel (999L not in whitelist)
        assertFalse(service.processMessage(createMockMessage(id = 5L, channelId = 999L, content = "Hello in disallowed channel?")))
        // 5. Blank message without attachments/embeds and system notice
        assertFalse(service.processMessage(createMockMessage(id = 6L, channelId = 201L, content = "   ")))
        assertFalse(
            service.processMessage(
                createMockMessage(id = 7L, channelId = 201L, type = MessageType.ChannelPinnedMessage, content = "Pinned"),
            ),
        )

        recorder.flushAndClose()
        assertEquals(emptyList(), readTraceLines(tempDir))
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

            val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
            val httpClient = createHttpClient(mockEngine)
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
                createMockMessage(
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

            val traces = readTraceLines(tempDir)
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

            val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
            val httpClient = createHttpClient(mockEngine)
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
                createMockMessage(
                    id = 601L,
                    channelId = 602L,
                    guildId = 603L,
                    authorId = 604L,
                    content = "Does anyone know what time lunch is?",
                )

            val handled = service.processMessage(message)
            recorder.flushAndClose()

            assertFalse(handled)
            val traces = readTraceLines(tempDir)
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

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val httpClient = createHttpClient(mockEngine)
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
            createMockMessage(
                id = 701L,
                channelId = 702L,
                content = "大家看看這張圖",
                attachments = setOf(createMockImageAttachment()),
            )

        val handled = service.processMessage(message)
        recorder.flushAndClose()

        assertTrue(handled)
        val traces = readTraceLines(tempDir)
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

    @Test
    fun `ChatterGenerator total failures flush FAILED with accurate failureCategory across error modes`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)

        // 1. RATE_LIMIT
        run {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":{"code":429,"message":"Quota exceeded"}}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        // 2. TIMEOUT
        run {
            val engine =
                MockEngine {
                    throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 30_000L)
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        // 3. HTTP_ERROR
        run {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":{"code":503,"message":"Unavailable"}}""",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        // 4. SAFETY_BLOCK
        run {
            val engine =
                MockEngine {
                    respond(
                        content = """{"id":"int-safety","status":"completed","finish_reason":"SAFETY","steps":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        // 5. EMPTY_OUTPUT
        run {
            val engine =
                MockEngine {
                    respond(
                        content = """{"id":"int-empty","status":"completed","steps":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                    httpClient = createHttpClient(engine),
                    traceRecorder = recorder,
                )
            assertNull(generator.generateReply("hello"))
        }

        recorder.flushAndClose()
        val traces = readTraceLines(tempDir)
        assertEquals(5, traces.size)
        assertEquals(
            listOf(
                AiTraceFailureCategory.RATE_LIMIT,
                AiTraceFailureCategory.TIMEOUT,
                AiTraceFailureCategory.HTTP_ERROR,
                AiTraceFailureCategory.SAFETY_BLOCK,
                AiTraceFailureCategory.EMPTY_OUTPUT,
            ),
            traces.map { it.failureCategory },
        )
        assertTrue(traces.all { it.outcome == AiTraceOutcome.FAILED })
        assertEquals("SAFETY", traces[3].attempts.single().finishReason)
    }

    @Test
    fun `ChatterGenerator classifies RECITATION finishReason with empty text as SAFETY_BLOCK`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val engine =
            MockEngine {
                respond(
                    content = """{"id":"int-recite","status":"completed","finish_reason":"RECITATION","steps":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                httpClient = createHttpClient(engine),
                traceRecorder = recorder,
            )

        assertNull(generator.generateReply("hello"))
        recorder.flushAndClose()

        val trace = readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertEquals(AiTraceFailureCategory.SAFETY_BLOCK, trace.failureCategory)
        assertEquals("RECITATION", trace.attempts.single().finishReason)
    }

    @Test
    fun `ChatterService post-filter preflight aborts record attempts empty or prior DECISION attempts with FAILED outcome`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val selfId = Snowflake(9999L)

        // 1. NOT_CONFIGURED on direct mention -> attempts: []
        run {
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = ""), traceRecorder = recorder),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = ""), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    traceRecorder = recorder,
                )
            val msg = createMockMessage(id = 801L, content = "<@9999> hey!", mentionedUserIds = setOf(selfId), selfId = selfId)
            assertFalse(service.processMessage(msg))
        }

        // 2. Blank reply context on direct mention (e.g. collector returns blank context) -> attempts: []
        run {
            val emptyCollector =
                object : ChatterContextCollector() {
                    override suspend fun collectReplyContext(
                        messages: List<Message>,
                        kordSelfId: Snowflake?,
                    ): PreparedChatterContext = PreparedChatterContext(transcript = "")
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = "k"), traceRecorder = recorder),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = emptyCollector,
                    traceRecorder = recorder,
                )
            val msg = createMockMessage(id = 802L, content = "<@9999> hey!", mentionedUserIds = setOf(selfId), selfId = selfId)
            assertFalse(service.processMessage(msg))
        }

        // 3. Channel resolution error after ambient DECISION succeeded -> preserves DECISION attempt
        run {
            val decisionEngine =
                MockEngine {
                    respond(
                        content = """{"answers":{"should_participate":{"type":"noul","noul":0.9,"confidence":0.9}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "k"),
                            httpClient = createHttpClient(decisionEngine),
                            traceRecorder = recorder,
                        ),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    traceRecorder = recorder,
                )
            val msg = createMockMessage(id = 803L, content = "Does anyone know Kotlin?", channelThrows = true)
            assertFalse(service.processMessage(msg))
        }

        // 4. Discord delivery failure -> UNEXPECTED_ERROR with CHATTER_GENERATE attempt recorded
        run {
            val genEngine =
                MockEngine {
                    respond(
                        content = """{"id":"int-deliv","status":"completed","finish_reason":"STOP","output_text":"Reply text"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val httpClient = createHttpClient(genEngine)
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "k"),
                            httpClient = httpClient,
                            traceRecorder = recorder,
                        ),
                    chatterGenerator =
                        ChatterGenerator(
                            config = ChatterConfig(apiKey = "k", searchGroundingEnabled = false),
                            httpClient = httpClient,
                            traceRecorder = recorder,
                        ),
                    historyFetcher = { _, _ -> emptyList() },
                    messageSender = { _, _ -> throw RuntimeException("Discord 500") },
                    traceRecorder = recorder,
                )
            val msg = createMockMessage(id = 804L, content = "<@9999> hi", mentionedUserIds = setOf(selfId), selfId = selfId)
            assertFalse(service.processMessage(msg))
        }

        recorder.flushAndClose()
        val traces = readTraceLines(tempDir)
        assertEquals(4, traces.size)

        assertEquals(AiTraceOutcome.FAILED, traces[0].outcome)
        assertEquals(AiTraceFailureCategory.NOT_CONFIGURED, traces[0].failureCategory)
        assertEquals(0, traces[0].attemptCount)
        assertTrue(traces[0].attempts.isEmpty())

        assertEquals(AiTraceOutcome.FAILED, traces[1].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[1].failureCategory)
        assertEquals(0, traces[1].attemptCount)
        assertTrue(traces[1].attempts.isEmpty())

        assertEquals(AiTraceOutcome.FAILED, traces[2].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[2].failureCategory)
        assertEquals(1, traces[2].attemptCount)
        assertEquals(AiAttemptStage.DECISION, traces[2].attempts.single().stage)

        assertEquals(AiTraceOutcome.FAILED, traces[3].outcome)
        assertEquals(AiTraceFailureCategory.UNEXPECTED_ERROR, traces[3].failureCategory)
        assertEquals(1, traces[3].attemptCount)
        assertEquals(AiAttemptStage.CHATTER_GENERATE, traces[3].attempts.single().stage)
    }

    @Test
    fun `ChatterService classifies collectDecisionContext and collectReplyContext exceptions as CONTEXT_ERROR`(
        @TempDir tempDir: Path,
    ) = runTest {
        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val selfId = Snowflake(9999L)

        // 1. collectDecisionContext throws
        run {
            val failingDecisionCollector =
                object : ChatterContextCollector() {
                    override suspend fun collectDecisionContext(
                        messages: List<Message>,
                        kordSelfId: Snowflake?,
                    ): ChatterDecisionContext = throw IllegalStateException("Decision context boom")
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = "k"), traceRecorder = recorder),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = failingDecisionCollector,
                    traceRecorder = recorder,
                )
            val msg = createMockMessage(id = 851L, content = "Does anyone know Kotlin?")
            assertFalse(service.processMessage(msg))
        }

        // 2. collectReplyContext throws
        run {
            val failingReplyCollector =
                object : ChatterContextCollector() {
                    override suspend fun collectReplyContext(
                        messages: List<Message>,
                        kordSelfId: Snowflake?,
                    ): PreparedChatterContext = throw IllegalStateException("Reply context boom")
                }
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(),
                    decisionClient = GeminiDecisionClient(config = ChatterDecisionConfig(apiKey = "k"), traceRecorder = recorder),
                    chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "k"), traceRecorder = recorder),
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = failingReplyCollector,
                    traceRecorder = recorder,
                )
            val msg = createMockMessage(id = 852L, content = "<@9999> hi", mentionedUserIds = setOf(selfId), selfId = selfId)
            assertFalse(service.processMessage(msg))
        }

        recorder.flushAndClose()
        val traces = readTraceLines(tempDir)
        assertEquals(2, traces.size)

        assertEquals(AiTraceOutcome.FAILED, traces[0].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[0].failureCategory)
        assertTrue(traces[0].errorSummary?.contains("Decision context boom") == true)

        assertEquals(AiTraceOutcome.FAILED, traces[1].outcome)
        assertEquals(AiTraceFailureCategory.CONTEXT_ERROR, traces[1].failureCategory)
        assertTrue(traces[1].errorSummary?.contains("Reply context boom") == true)
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
    fun `ChatterService direct-mention long-video AGENTIC_VIDEO success emits MENTION trace with single collapsed attempt`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-chatter-agentic-key"
        var pollCount = 0
        val completedJson =
            """{"id": "chat-int-1", "status": "completed", "output_text": "這支長影片重點在後半段的實測 👍"}"""

        val mockEngine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/interactions") && request.method.value == "POST" -> {
                        respond(
                            content = """{"id": "chat-int-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    request.url.encodedPath.endsWith("/interactions/chat-int-1") && request.method.value == "GET" -> {
                        pollCount++
                        val body =
                            if (pollCount == 1) {
                                """{"id": "chat-int-1", "status": "in_progress"}"""
                            } else {
                                completedJson
                            }
                        respond(
                            content = body,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        respond("Not found", status = HttpStatusCode.NotFound)
                    }
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val httpClient = createHttpClient(mockEngine)
        val longVideoPost =
            dev.socialpeek.model.PeekPost(
                platform = dev.socialpeek.model.Platform.YOUTUBE,
                id = "longChatter720",
                originalUrl = "https://www.youtube.com/watch?v=longChatter720",
                cleanUrl = "https://www.youtube.com/watch?v=longChatter720",
                author = dev.socialpeek.model.Author(username = "Creator"),
                title = "Long Architecture Talk",
                content = "Talk summary",
                rawData = mapOf("durationSeconds" to "720"),
            )
        val filter = ChatterNoiseFilter()
        val collector =
            ChatterContextCollector(
                noiseFilter = filter,
                peekResolver = { longVideoPost },
            )
        val sentMessages = mutableListOf<String>()
        val service =
            ChatterService(
                filter = filter,
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = apiKey),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = apiKey, searchGroundingEnabled = true),
                        httpClient = httpClient,
                        pollingDelayMs = 1L,
                        maxPollingAttempts = 5,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, content ->
                    sentMessages.add(content)
                    mockk(relaxed = true)
                },
                contextCollector = collector,
                traceRecorder = recorder,
            )

        val selfId = Snowflake(9999L)
        val mentionMsg =
            createMockMessage(
                id = 901L,
                channelId = 902L,
                guildId = 903L,
                authorId = 904L,
                username = "Alice",
                content = "<@9999> 幫我看這支片 https://www.youtube.com/watch?v=longChatter720",
                mentionedUserIds = setOf(selfId),
                selfId = selfId,
            )

        val handled = service.processMessage(mentionMsg)
        recorder.flushAndClose()

        assertTrue(handled)
        assertEquals(listOf("這支長影片重點在後半段的實測"), sentMessages)

        val traces = readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertFalse(trace.degraded)
        assertNull(trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)
        assertEquals("這支長影片重點在後半段的實測", trace.finalOutput)

        val attempt = trace.attempts.single()
        assertEquals(1, attempt.attemptIndex)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, attempt.stage)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions?key=******", attempt.endpoint)
        assertEquals(2, attempt.pollingAttempts)
        assertEquals(200, attempt.httpStatus)
        assertEquals(completedJson, attempt.responseRaw)
        assertEquals(AiAttemptOutcome.SUCCESS, attempt.parsedOutcome)
        assertEquals(true, attempt.request?.hasVideo)
        assertEquals(720L, attempt.request?.videoDurationSeconds)

        val jsonlFile = tempDir.resolve("ai-2026-11-14.jsonl")
        assertFalse(Files.readString(jsonlFile).contains(apiKey))
    }

    @Test
    fun `ChatterService direct-mention agentic timeout fails without retry and records FAILED AGENTIC_VIDEO trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-chatter-degrade-key"
        var pollCount = 0
        val pendingJson = """{"id": "chat-int-to", "status": "in_progress"}"""

        val mockEngine =
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/interactions") && request.method.value == "POST" -> {
                        respond(
                            content = pendingJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    path.contains("/interactions/chat-int-to") && request.method.value == "GET" -> {
                        pollCount++
                        respond(
                            content = pendingJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        error("Unexpected fallback HTTP call to $path")
                    }
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val httpClient = createHttpClient(mockEngine)
        val longVideoPost =
            dev.socialpeek.model.PeekPost(
                platform = dev.socialpeek.model.Platform.YOUTUBE,
                id = "longTimeout900",
                originalUrl = "https://www.youtube.com/watch?v=longTimeout900",
                cleanUrl = "https://www.youtube.com/watch?v=longTimeout900",
                author = dev.socialpeek.model.Author(username = "Creator"),
                title = "Long Stream Replay",
                content = "Stream outline",
                comments =
                    listOf(
                        dev.socialpeek.model.Comment(author = "viewer", content = "12:00 精華必看"),
                    ),
                rawData = mapOf("durationSeconds" to "900"),
            )
        val filter = ChatterNoiseFilter()
        val service =
            ChatterService(
                filter = filter,
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = apiKey),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = apiKey, searchGroundingEnabled = true),
                        httpClient = httpClient,
                        pollingDelayMs = 1L,
                        maxPollingAttempts = 2,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, _ -> mockk(relaxed = true) },
                contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { longVideoPost }),
                traceRecorder = recorder,
            )

        val selfId = Snowflake(9999L)
        val mentionMsg =
            createMockMessage(
                id = 911L,
                channelId = 912L,
                guildId = 913L,
                authorId = 914L,
                username = "Alice",
                content = "<@9999> 幫忙看 https://www.youtube.com/watch?v=longTimeout900",
                mentionedUserIds = setOf(selfId),
                selfId = selfId,
            )

        val handled = service.processMessage(mentionMsg)
        recorder.flushAndClose()

        assertFalse(handled)
        assertEquals(2, pollCount)

        val trace = readTraceLines(tempDir).single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.TIMEOUT, trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)

        val agenticAttempt = trace.attempts.single()
        assertEquals(1, agenticAttempt.attemptIndex)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, agenticAttempt.stage)
        assertEquals(2, agenticAttempt.pollingAttempts)
        assertEquals(AiAttemptOutcome.ERROR, agenticAttempt.parsedOutcome)
        assertEquals(900L, agenticAttempt.request?.videoDurationSeconds)
    }

    @Test
    fun `standalone ChatterGenerator failed agentic interaction records single FAILED AGENTIC_VIDEO attempt without retry`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-standalone-agentic-key"
        val failedInteractionJson = """{"id": "chat-fail-1", "status": "failed", "error": {"message": "Video unavailable"}}"""

        val mockEngine =
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/interactions") && request.method.value == "POST" -> {
                        respond(
                            content = """{"id": "chat-fail-1", "status": "in_progress"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    path.contains("/interactions/chat-fail-1") && request.method.value == "GET" -> {
                        respond(
                            content = failedInteractionJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        error("Unexpected fallback HTTP call to $path")
                    }
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = apiKey, searchGroundingEnabled = true),
                httpClient = createHttpClient(mockEngine),
                pollingDelayMs = 1L,
                maxPollingAttempts = 3,
                traceRecorder = recorder,
            )

        val reply =
            generator.generateReply(
                ChatterInput(
                    transcript = "[Alice]: 看看這支長影片\n標題：Long Video",
                    videoUri = "https://www.youtube.com/watch?v=longFail950",
                    videoDurationSeconds = 950L,
                    isDirectMention = true,
                ),
            )
        recorder.flushAndClose()

        assertNull(reply)
        val traces = readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTracePipeline.CHATTER, trace.pipeline)
        assertEquals(AiTraceTrigger.MENTION, trace.trigger)
        assertEquals(AiTraceOutcome.FAILED, trace.outcome)
        assertFalse(trace.degraded)
        assertEquals(AiTraceFailureCategory.UNEXPECTED_ERROR, trace.failureCategory)
        assertEquals(1, trace.attemptCount)
        assertEquals(listOf("AGENTIC_VIDEO"), trace.fallbackPath)
        assertEquals(AiAttemptStage.AGENTIC_VIDEO, trace.attempts[0].stage)
        assertEquals(1, trace.attempts[0].pollingAttempts)
        assertEquals(failedInteractionJson, trace.attempts[0].responseRaw)
        assertEquals(950L, trace.attempts[0].request?.videoDurationSeconds)
    }

    @Test
    fun `ChatterService end-to-end with general web URL skips SocialPeek, enables url_context, and records full AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val apiKey = "secret-e2e-web-key"
        val webUrl = "https://kotlinlang.org/docs/coroutines-overview.html"
        var peekCalled = false

        val mockEngine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/decisions") -> {
                        respond(
                            content = """{"answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    request.url.encodedPath.endsWith("/interactions") -> {
                        respond(
                            content =
                                """
                                {
                                  "id": "int-e2e-web",
                                  "status": "completed",
                                  "steps": [
                                    {
                                      "type": "url_context_result",
                                      "result": [{"retrieved_url": "$webUrl", "status": "success"}]
                                    },
                                    {
                                      "type": "google_search_call",
                                      "arguments": {"queries": ["kotlinx coroutines 1.9"]}
                                    },
                                    {
                                      "type": "model_output",
                                      "content": [
                                        {
                                          "type": "text",
                                          "text": "官方文件寫得很清楚 新手照著看很快就能上手",
                                          "annotations": [
                                            {"type": "url_citation", "url": "$webUrl", "title": "Coroutines Overview"},
                                            {"type": "url_citation", "url": "https://github.com/Kotlin/kotlinx.coroutines", "title": "kotlinx.coroutines"}
                                          ]
                                        }
                                      ]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        respond("Not found", status = HttpStatusCode.NotFound)
                    }
                }
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = fixedClock)
        val httpClient = createHttpClient(mockEngine)
        val filter = ChatterNoiseFilter()
        val sentMessages = mutableListOf<String>()
        val service =
            ChatterService(
                filter = filter,
                decisionClient =
                    GeminiDecisionClient(
                        config = ChatterDecisionConfig(apiKey = apiKey),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                chatterGenerator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = apiKey, searchGroundingEnabled = true, urlContextEnabled = true),
                        httpClient = httpClient,
                        traceRecorder = recorder,
                    ),
                historyFetcher = { _, _ -> emptyList() },
                messageSender = { _, content ->
                    sentMessages.add(content)
                    mockk(relaxed = true)
                },
                contextCollector =
                    ChatterContextCollector(
                        noiseFilter = filter,
                        peekResolver = {
                            peekCalled = true
                            null
                        },
                    ),
                traceRecorder = recorder,
            )

        val msg = createMockMessage(id = 990L, channelId = 991L, content = "推薦看一下 $webUrl")
        assertTrue(service.processMessage(msg))
        recorder.flushAndClose()

        assertFalse(peekCalled)
        assertEquals(listOf("官方文件寫得很清楚 新手照著看很快就能上手"), sentMessages)

        val trace = readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals(2, trace.attemptCount)
        assertTrue(trace.citations.isEmpty())

        val genAttempt = trace.attempts[1]
        assertEquals(AiAttemptStage.CHATTER_GENERATE, genAttempt.stage)
        assertEquals(1, genAttempt.request?.webUrlCount)
        assertEquals(listOf("url_context", "google_search"), genAttempt.request?.enabledTools)
        assertEquals(1, genAttempt.urlContextResults.size)
        assertEquals(webUrl, genAttempt.urlContextResults.single().retrievedUrl)
        assertEquals(listOf("kotlinx coroutines 1.9"), genAttempt.googleSearchQueries)
        assertEquals(2, genAttempt.rawCitations.size)
        assertEquals(
            listOf(GroundingCitation("kotlinx.coroutines", "https://github.com/Kotlin/kotlinx.coroutines")),
            genAttempt.filteredCitations,
        )
    }
}
