package luna.chatter

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.ReactionEmoji
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
import io.ktor.server.routing.Routing
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.emoji.DefaultChatterEmojiService
import luna.chatter.emoji.ReactionResolution
import luna.core.ai.AiAttemptOutcome
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceFailureCategory
import luna.core.ai.AiTraceOutcome
import luna.core.ai.ChatImageAttachment
import luna.core.ai.JsonlAiTraceRecorder
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterReactionSelectionTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    @Test
    fun `ChatterConfig resolves CHATTER_REACTION_THRESHOLD defaults and fails fast on out-of-range or invalid values`() {
        val defaults = ChatterConfig()
        assertEquals(0.45, defaults.reactionThreshold)

        assertEquals(0.45, ChatterConfig.from(luna.config.EnvReader.of()).reactionThreshold)
        assertEquals(0.55, ChatterConfig.from(luna.config.EnvReader.of("CHATTER_REACTION_THRESHOLD" to "0.55")).reactionThreshold)
        assertEquals(0.0, ChatterConfig.from(luna.config.EnvReader.of("CHATTER_REACTION_THRESHOLD" to "0.0")).reactionThreshold)
        assertEquals(1.0, ChatterConfig.from(luna.config.EnvReader.of("CHATTER_REACTION_THRESHOLD" to "1.0")).reactionThreshold)

        for (bad in listOf("-0.25", "1.75", "not-a-number", "NaN")) {
            kotlin.test.assertFailsWith<luna.config.ConfigValidationException> {
                ChatterConfig.from(luna.config.EnvReader.of("CHATTER_REACTION_THRESHOLD" to bad))
            }
        }

        for (badDirect in listOf(-0.1, 1.4, Double.NaN, Double.POSITIVE_INFINITY)) {
            kotlin.test.assertFailsWith<luna.config.ConfigValidationException> {
                ChatterConfig(reactionThreshold = badDirect)
            }
        }
    }

    @Test
    fun `ChatterEmojiTurn resolveReaction accepts single Unicode and verified guild custom emojis and rejects invalid outputs`() =
        runTest {
            val guildEmojis =
                listOf(
                    GuildCustomEmoji(id = Snowflake(111222333444L), name = "blobcat", isAnimated = false),
                    GuildCustomEmoji(id = Snowflake(555666777888L), name = "party", isAnimated = true),
                )
            val turn =
                DefaultChatterEmojiService()
                    .beginTurn(
                        channelId = Snowflake(1L),
                        guildId = Snowflake(2L),
                        guildEmojisProvider = { guildEmojis },
                    )
            turn.prepareReactionEmojis()

            // Valid single Unicode emojis (basic, VS16, skin tone, ZWJ, flag, keycap, star)
            for (emoji in listOf("👍", "❤️", "👍🏻", "🤦‍♀️", "🇹🇼", "1️⃣", "⭐")) {
                val res = turn.resolveReaction("  $emoji\n") as ReactionResolution.Valid
                assertEquals(emoji, res.normalizedToken)
                assertEquals(ReactionEmoji.Unicode(emoji), res.reactionEmoji)
            }

            // Valid verified guild custom emojis (canonicalizes animated flag)
            val blobcat = turn.resolveReaction("<:blobcat:111222333444>") as ReactionResolution.Valid
            assertEquals("<:blobcat:111222333444>", blobcat.normalizedToken)
            val party = turn.resolveReaction("<:party:555666777888>") as ReactionResolution.Valid
            assertEquals("<a:party:555666777888>", party.normalizedToken)
            assertEquals(ReactionEmoji.Custom(Snowflake(555666777888L), "party", isAnimated = true), party.reactionEmoji)

            // Explicit NONE -> Declined
            assertEquals(ReactionResolution.Declined, turn.resolveReaction("none"))

            // Negative boundary rejections -> Invalid
            val invalidCases =
                listOf(
                    "",
                    "   \n ",
                    "👍👍",
                    "😂😭",
                    "👍 nice",
                    "haha",
                    "1",
                    "🇹",
                    "\uFE0F",
                    "\u200D",
                    "<:fake:999999999999>",
                    "<:wrongname:111222333444>",
                    "<:blobcat:111222333444> <:party:555666777888>",
                    "<:blobcat:111222333444> lol",
                )
            for (bad in invalidCases) {
                assertTrue(turn.resolveReaction(bad) is ReactionResolution.Invalid, "Expected invalid reaction for '$bad'")
            }
        }

    @Test
    fun `ChatterPromptBuilder buildReactionInteractionRequest sets maxOutputTokens 128, multimodal attachments, guild emojis, and persona drift`() {
        val personaState =
            PersonaState(
                driftEnabled = true,
                timezone = "Asia/Taipei",
                localTime = ZonedDateTime.of(2026, 11, 19, 21, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                formattedWeekday = "星期四",
                formattedTime = "21:00",
                circadianPeriod = CircadianPeriod.EVENING,
                mood = PersonaMood.MELLOW,
                baseThreshold = 0.7,
                effectiveThreshold = 0.72,
            )
        val guildEmojis =
            listOf(
                GuildCustomEmoji(id = Snowflake(12345L), name = "luna_sip", isAnimated = false),
                GuildCustomEmoji(id = Snowflake(67890L), name = "cat_nod", isAnimated = true),
            )
        val input =
            ChatterInput(
                transcript = "[Latest Message]\n[Alice]: [貼圖: smug_cat]",
                imageAttachments =
                    listOf(
                        ChatImageAttachment(
                            url = "https://cdn.discordapp.com/stickers/123.png",
                            mimeType = "image/png",
                            filename = "smug_cat.png",
                        ),
                    ),
                personaState = personaState,
                guildEmojis = guildEmojis,
            )

        val request = ChatterPromptBuilder.buildReactionInteractionRequest(input, model = "gemini-3.5-flash-lite")

        assertEquals(128, request.generationConfig?.maxOutputTokens)
        assertFalse(request.background)
        assertNull(request.tools)
        assertEquals(2, request.input.size)
        assertEquals("image", request.input[0].type)
        assertEquals("https://cdn.discordapp.com/stickers/123.png", request.input[0].uri)
        assertEquals("text", request.input[1].type)

        val sys = assertNotNull(request.systemInstruction)
        assertTrue(sys.contains("<:luna_sip:12345>"))
        assertTrue(sys.contains("<a:cat_nod:67890>"))
        assertTrue(sys.contains(CircadianPeriod.EVENING.promptCue))
        assertTrue(sys.contains(PersonaMood.MELLOW.promptCue))
    }

    @Test
    fun `selectReactionEmoji invokes interactions with maxOutputTokens 128, multimodal attachments, and records REACTION_SELECT AI Trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val mockEngine =
            MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedBody = request.body.toByteArray().decodeToString()
                respond(
                    content = """{"id":"int-react-1","status":"completed","finish_reason":"STOP","output_text":"😂"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-react-key"),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        val emoji =
            generator.selectReactionEmoji(
                ChatterInput(
                    transcript = "[Latest Message]\n[Alice]: 這個 GIF 太好笑了 [GIF: funny cat]",
                    imageAttachments =
                        listOf(
                            ChatImageAttachment(
                                url = "https://media.tenor.com/abc/cat.gif",
                                mimeType = "image/gif",
                                filename = "cat.gif",
                            ),
                        ),
                ),
            )
        recorder.flushAndClose()

        assertEquals("😂", emoji)
        assertTrue(capturedUrl.orEmpty().contains("/v1beta/interactions?key=secret-react-key"))

        val reqObj = json.parseToJsonElement(checkNotNull(capturedBody)).jsonObject
        assertEquals(
            128,
            reqObj["generation_config"]
                ?.jsonObject
                ?.get("max_output_tokens")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
        assertEquals(false, reqObj["background"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertFalse(reqObj.containsKey("tools"))
        val inputBlocks = reqObj["input"]?.jsonArray
        assertNotNull(inputBlocks)
        assertEquals("image", inputBlocks[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("https://media.tenor.com/abc/cat.gif", inputBlocks[0].jsonObject["uri"]?.jsonPrimitive?.content)

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals("😂", trace.finalOutput)
        assertEquals(listOf("REACTION_SELECT"), trace.fallbackPath)
        val attempt = trace.attempts.single()
        assertEquals(AiAttemptStage.REACTION_SELECT, attempt.stage)
        assertEquals(AiAttemptOutcome.SUCCESS, attempt.parsedOutcome)
        assertTrue(attempt.request?.hasImages == true)
    }

    @Test
    fun `selectReactionEmoji returns verified guild custom emoji when selected from input guildEmojis`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine {
                respond(
                    content = """{"id":"int-react-custom","status":"completed","finish_reason":"STOP","output_text":"<a:party:555666777888>"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-react-key"),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        val emoji =
            generator.selectReactionEmoji(
                ChatterInput(
                    transcript = "[Latest Message]\n[Alice]: 下班了！！",
                    guildEmojis = listOf(GuildCustomEmoji(Snowflake(555666777888L), "party", isAnimated = true)),
                ),
            )
        recorder.flushAndClose()

        assertEquals("<a:party:555666777888>", emoji)
        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        assertEquals(AiTraceOutcome.SUCCESS, trace.outcome)
        assertEquals("<a:party:555666777888>", trace.finalOutput)
    }

    @Test
    fun `selectReactionEmoji enforces strict no-fallback rule on HTTP error, timeout, and blank output with warn log and SKIPPED trace`(
        @TempDir tempDir: Path,
    ) = runTest {
        val slf4jLogger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        slf4jLogger.addAppender(appender)

        try {
            var scenario = 0
            var requestCount = 0
            val mockEngine =
                MockEngine {
                    requestCount++
                    when (scenario) {
                        0 -> {
                            respond(
                                content = """{"error":{"code":500,"message":"Internal error"}}""",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        1 -> {
                            throw HttpRequestTimeoutException("https://generativelanguage.googleapis.com", 10_000L)
                        }

                        else -> {
                            respond(
                                content = """{"id":"int-empty","status":"completed","finish_reason":"STOP","output_text":"   "}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }

            val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "secret-react-key"),
                    httpClient = createTestClient(mockEngine),
                    traceRecorder = recorder,
                )

            val input =
                ChatterInput(
                    transcript = "[Alice]: 看看這個",
                    guildEmojis = listOf(GuildCustomEmoji(Snowflake(111222333444L), "blobcat")),
                )

            for (caseIdx in 0..2) {
                scenario = caseIdx
                assertNull(generator.selectReactionEmoji(input), "Case $caseIdx must return null without fallback")
            }
            recorder.flushAndClose()

            assertEquals(3, requestCount)

            val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
            assertEquals(3, traces.size)
            assertTrue(traces.all { it.outcome == AiTraceOutcome.SKIPPED }, "Expected all failed reaction traces to be SKIPPED")
            assertTrue(traces.all { it.finalOutput == null }, "Expected null finalOutput for all failed reaction traces")
            assertTrue(traces.all { it.attempts.single().stage == AiAttemptStage.REACTION_SELECT }, "Expected REACTION_SELECT attempt stage")

            assertEquals(AiTraceFailureCategory.HTTP_ERROR, traces[0].failureCategory)
            assertEquals(AiTraceFailureCategory.TIMEOUT, traces[1].failureCategory)
            assertEquals(AiTraceFailureCategory.EMPTY_OUTPUT, traces[2].failureCategory)

            val warnMessages = appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }
            assertEquals(3, warnMessages.size, "Expected observable WARN log for every failed/blank reaction selection")
        } finally {
            slf4jLogger.detachAppender(appender)
        }
    }

    @Test
    fun `selectReactionEmoji records INCOMPLETE finishReason and Interactions token usage when interaction status is incomplete`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine {
                respond(
                    content =
                        """
                        {
                          "id": "int-incomplete-emoji",
                          "status": "incomplete",
                          "usage": {
                            "total_tokens": 2237,
                            "total_input_tokens": 2225,
                            "total_output_tokens": 12,
                            "total_thought_tokens": 0,
                            "total_tool_use_tokens": 0
                          },
                          "steps": [
                            {"signature": "sig-1", "type": "thought"},
                            {"type": "model_output", "content": [{"type": "text", "text": "<:dora_peter:14336"}]}
                          ]
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-react-key"),
                httpClient = createTestClient(mockEngine),
                traceRecorder = recorder,
            )

        val guildEmojis = listOf(GuildCustomEmoji(Snowflake(1433693710293667860L), "dora_peter"))
        val raw =
            generator.selectReactionEmoji(
                ChatterInput(
                    transcript = "[Alice]: 看看這個",
                    guildEmojis = guildEmojis,
                ),
            )
        recorder.flushAndClose()

        assertEquals("<:dora_peter:14336", raw)
        val turn = DefaultChatterEmojiService().beginTurn(Snowflake(1L), Snowflake(2L), guildEmojisProvider = { guildEmojis })
        turn.prepareReactionEmojis()
        assertTrue(turn.resolveReaction(raw) is ReactionResolution.Invalid, "Truncated custom emoji must fail closed without falling back")

        val trace = ChatterAiTraceTestSupport.readTraceLines(tempDir).single()
        val attempt = trace.attempts.single()
        assertEquals("INCOMPLETE", attempt.finishReason)
        assertEquals(2225, attempt.usageMetadata?.promptTokenCount)
        assertEquals(12, attempt.usageMetadata?.candidatesTokenCount)
        assertEquals(2237, attempt.usageMetadata?.totalTokenCount)
        assertEquals(0, attempt.usageMetadata?.thoughtsTokenCount)
    }
}
