package luna.core.ai

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTraceRecorderTest {
    private class MutableClock(
        private var currentInstant: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = currentInstant

        fun setInstant(instant: Instant) {
            currentInstant = instant
        }
    }

    @Test
    fun `serializes AiTraceRecord as deterministic single-line JSONL with all required fields`() {
        val requestPayload =
            buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", "Summarize concisely.") })
                    }
                }
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                add(
                                    buildJsonObject {
                                        putJsonObject("file_data") {
                                            put("file_uri", "https://cdn.discordapp.com/attachments/1/2/photo.png")
                                            put("mime_type", "image/png")
                                        }
                                    },
                                )
                                add(buildJsonObject { put("text", "Hello world") })
                            }
                        },
                    )
                }
            }

        val attempt =
            AiAttemptRecord(
                attemptIndex = 1,
                stage = AiAttemptStage.SUMMARY_GENERATE,
                model = "gemini-3.5-flash-lite",
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
                latencyMs = 412L,
                httpStatus = 200,
                request =
                    AiAttemptRequest(
                        payload = requestPayload,
                        hasImages = true,
                        imageCount = 1,
                        hasVideo = false,
                        videoDurationSeconds = null,
                        transcriptLength = 11,
                    ),
                responseRaw = """{"candidates":[{"finishReason":"STOP"}]}""",
                finishReason = "STOP",
                blockReason = null,
                usageMetadata = GeminiUsageMetadata(promptTokenCount = 100, candidatesTokenCount = 25, totalTokenCount = 125),
                pollingAttempts = null,
                parsedOutcome = AiAttemptOutcome.SUCCESS,
                errorMessage = null,
            )

        val record =
            AiTraceRecord(
                traceId = "trace-001",
                timestamp = "2026-11-14T15:30:00Z",
                pipeline = AiTracePipeline.SUMMARY,
                trigger = AiTraceTrigger.REACTION,
                discord =
                    AiTraceDiscordContext(
                        guildId = "guild-1",
                        channelId = "chan-1",
                        messageId = "msg-1",
                        authorId = "user-1",
                        reactingUserId = "user-2",
                        isDirectMention = false,
                    ),
                outcome = AiTraceOutcome.SUCCESS,
                degraded = false,
                failureCategory = null,
                fallbackPath = listOf("SUMMARY_GENERATE"),
                totalLatencyMs = 450L,
                attemptCount = 1,
                finalOutput = "Summary output",
                citations = listOf(GroundingCitation(title = "Example", url = "https://example.com")),
                errorSummary = null,
                attempts = listOf(attempt),
            )

        val line = AiTraceJson.encodeToString(AiTraceRecord.serializer(), record)
        assertFalse(line.contains("\n"), "JSONL record must be single-line without newlines")
        assertFalse(line.contains("\r"), "JSONL record must not contain carriage returns")

        val parsed = Json.parseToJsonElement(line).jsonObject
        assertEquals("trace-001", parsed["traceId"]?.jsonPrimitive?.content)
        assertEquals("2026-11-14T15:30:00Z", parsed["timestamp"]?.jsonPrimitive?.content)
        assertEquals("SUMMARY", parsed["pipeline"]?.jsonPrimitive?.content)
        assertEquals("REACTION", parsed["trigger"]?.jsonPrimitive?.content)
        assertEquals("SUCCESS", parsed["outcome"]?.jsonPrimitive?.content)
        assertEquals(false, parsed["degraded"]?.jsonPrimitive?.content?.toBooleanStrict())
        assertTrue(parsed.containsKey("failureCategory"), "explicitNulls must preserve failureCategory key")
        assertEquals(1, parsed["attemptCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals(450L, parsed["totalLatencyMs"]?.jsonPrimitive?.content?.toLong())
        assertEquals("Summary output", parsed["finalOutput"]?.jsonPrimitive?.content)

        val attemptObj = parsed["attempts"]?.jsonArray?.first()?.jsonObject
        assertEquals(
            1,
            attemptObj
                ?.get("attemptIndex")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
        assertEquals("SUMMARY_GENERATE", attemptObj?.get("stage")?.jsonPrimitive?.content)
        assertEquals("STOP", attemptObj?.get("finishReason")?.jsonPrimitive?.content)
        assertEquals(
            125,
            attemptObj
                ?.get("usageMetadata")
                ?.jsonObject
                ?.get("totalTokenCount")
                ?.jsonPrimitive
                ?.content
                ?.toInt(),
        )
    }

    @Test
    fun `AiTraceRedactor scrubs query keys headers explicit config secrets and inline binary payloads`() {
        val redactor =
            AiTraceRedactor.fromConfig(
                mapOf(
                    "GEMINI_API_KEY" to "AIzaSySecretGemini123",
                    "YOUTUBE_API_KEY" to "YTzaSecretYouTube456",
                    "DISCORD_TOKEN" to "discord.bot.token.secret",
                    "DISCORD_CLIENT_SECRET" to "discord_oauth_secret_val",
                    "CUSTOM_SERVICE_KEY" to "custom_key_789",
                    "NON_SECRET_PORT" to "8080",
                ),
            )

        val rawAttempt =
            AiAttemptRecord(
                attemptIndex = 1,
                stage = AiAttemptStage.DECISION,
                model = "gemini-decision-1",
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent?key=AIzaSySecretGemini123&alt=json",
                latencyMs = 100L,
                httpStatus = 401,
                request =
                    AiAttemptRequest(
                        payload =
                            buildJsonObject {
                                put("x-goog-api-key", "AIzaSySecretGemini123")
                                put("apiKey", "inline_secret_value")
                                put("url", "https://example.com/api?foo=bar&key=queryKey999")
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/png")
                                    put("data", "iVBORw0KGgoAAAANSUhEUgAA")
                                }
                            },
                    ),
                responseRaw =
                    "Error calling https://api.example.com?key=urlKey111 with header x-goog-api-key: headerKey222 " +
                        "and youtube YTzaSecretYouTube456 token discord.bot.token.secret " +
                        "client discord_oauth_secret_val custom custom_key_789",
                parsedOutcome = AiAttemptOutcome.ERROR,
                errorMessage = "Rejected x-goog-api-key=AIzaSySecretGemini123 on port 8080",
            )

        val trace =
            AiTraceRecord(
                traceId = "trace-redact",
                timestamp = "2026-11-14T15:30:00Z",
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.AMBIENT,
                discord =
                    AiTraceDiscordContext(
                        guildId = "g1",
                        channelId = "c1",
                        messageId = "m1",
                        authorId = "a1",
                    ),
                outcome = AiTraceOutcome.FAILED,
                failureCategory = AiTraceFailureCategory.HTTP_ERROR,
                attempts = listOf(rawAttempt),
                errorSummary = "Failed with AIzaSySecretGemini123",
            )

        val redacted = redactor.redactTrace(trace)
        val serialized = AiTraceJson.encodeToString(AiTraceRecord.serializer(), redacted)

        assertFalse(serialized.contains("AIzaSySecretGemini123"))
        assertFalse(serialized.contains("YTzaSecretYouTube456"))
        assertFalse(serialized.contains("discord.bot.token.secret"))
        assertFalse(serialized.contains("discord_oauth_secret_val"))
        assertFalse(serialized.contains("custom_key_789"))
        assertFalse(serialized.contains("inline_secret_value"))
        assertFalse(serialized.contains("queryKey999"))
        assertFalse(serialized.contains("urlKey111"))
        assertFalse(serialized.contains("headerKey222"))
        assertFalse(serialized.contains("iVBORw0KGgoAAAANSUhEUgAA"))

        val redactedAttempt = redacted.attempts.first()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent?key=******&alt=json",
            redactedAttempt.endpoint,
        )
        val payloadObj = redactedAttempt.request?.payload as JsonObject
        assertEquals("******", payloadObj["x-goog-api-key"]?.jsonPrimitive?.content)
        assertEquals("******", payloadObj["apiKey"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/api?foo=bar&key=******", payloadObj["url"]?.jsonPrimitive?.content)
        assertTrue(serialized.contains("8080"), "Non-secret config values must not be scrubbed")
    }

    @Test
    fun `NoOpAiTraceRecorder does not write files or enable sessions`(
        @TempDir tempDir: Path,
    ) = runTest {
        assertFalse(NoOpAiTraceRecorder.isEnabled())
        val session =
            NoOpAiTraceRecorder.startSession(
                pipeline = AiTracePipeline.SUMMARY,
                trigger = AiTraceTrigger.REACTION,
                discord = AiTraceDiscordContext(guildId = "g1", channelId = "c1", messageId = "m1", authorId = "a1"),
            )
        assertFalse(session.isEnabled)
        session.recordAttempt(
            stage = AiAttemptStage.SUMMARY_GENERATE,
            parsedOutcome = AiAttemptOutcome.SUCCESS,
        )
        session.complete("output")
        session.finish()
        NoOpAiTraceRecorder.flushAndClose()
        assertEquals(0L, Files.list(tempDir).use { it.count() })
    }

    @Test
    fun `AiTraceSession propagates via CoroutineContext records attempts idempotently and emits lightweight JsonLogger correlation`(
        @TempDir tempDir: Path,
    ) = runTest {
        val logDir = tempDir.resolve("traces")
        val fixedClock = MutableClock(Instant.parse("2026-11-14T10:15:30Z"))
        val recorder =
            JsonlAiTraceRecorder(
                traceDir = logDir,
                clock = fixedClock,
                redactor = AiTraceRedactor(listOf("runtime-secret-key-999")),
            )

        // Lazy directory creation: dir must not exist until a trace is written
        assertFalse(Files.exists(logDir), "Trace directory must not be created until a trace is written")

        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        jsonLogger.addAppender(listAppender)

        try {
            assertNull(currentAiTraceSession())

            val session =
                recorder.startSession(
                    pipeline = AiTracePipeline.SUMMARY,
                    trigger = AiTraceTrigger.REACTION,
                    discord =
                        AiTraceDiscordContext(
                            guildId = "guild-1",
                            channelId = "chan-1",
                            messageId = "msg-1",
                            authorId = "author-1",
                            reactingUserId = "reactor-1",
                            isDirectMention = false,
                        ),
                )

            withAiTraceSession(session) {
                val active = currentAiTraceSession()
                assertNotNull(active)
                assertEquals(session.traceId, active.traceId)

                active.registerSecret("dynamic-api-secret-xyz")
                active.recordAttempt(
                    stage = AiAttemptStage.SUMMARY_GENERATE,
                    variant = "GOOGLE_SEARCH",
                    model = "gemini-3.5-flash-lite",
                    endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent?key=dynamic-api-secret-xyz",
                    latencyMs = 150L,
                    httpStatus = 500,
                    request =
                        AiAttemptRequest(
                            payload = buildJsonObject { put("prompt", "SUPER_SECRET_RAW_PROMPT_MARKER") },
                            transcriptLength = 42,
                        ),
                    responseRaw = "upstream error with dynamic-api-secret-xyz",
                    parsedOutcome = AiAttemptOutcome.ERROR,
                    errorMessage = "HTTP 500",
                )
                active.markDegraded("Stripped search grounding after 500")
                active.recordAttempt(
                    stage = AiAttemptStage.SUMMARY_GENERATE,
                    variant = "BASELINE",
                    model = "gemini-3.5-flash-lite",
                    endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent?key=runtime-secret-key-999",
                    latencyMs = 200L,
                    httpStatus = 200,
                    responseRaw = """{"candidates":[{"finishReason":"STOP"}]}""",
                    finishReason = "STOP",
                    usageMetadata = GeminiUsageMetadata(10, 5, 15),
                    parsedOutcome = AiAttemptOutcome.SUCCESS,
                )
                active.completeDegraded(
                    finalOutput = "Degraded summary text",
                    citations = emptyList(),
                    failureCategory = AiTraceFailureCategory.HTTP_ERROR,
                    errorSummary = "Stripped search grounding after 500",
                )
            }

            // Calling finish() twice must be idempotent
            session.finish()
            session.finish()

            recorder.flushAndClose()

            val expectedFile = logDir.resolve("ai-2026-11-14.jsonl")
            assertTrue(Files.exists(expectedFile), "Expected daily JSONL trace file to exist")
            val lines = Files.readAllLines(expectedFile)
            assertEquals(1, lines.size, "Idempotent finish() must write exactly one JSONL line")

            val serialized = lines.first()
            assertFalse(serialized.contains("dynamic-api-secret-xyz"))
            assertFalse(serialized.contains("runtime-secret-key-999"))

            val parsed = Json.parseToJsonElement(serialized).jsonObject
            assertEquals(session.traceId, parsed["traceId"]?.jsonPrimitive?.content)
            assertEquals("DEGRADED_SUCCESS", parsed["outcome"]?.jsonPrimitive?.content)
            assertEquals(true, parsed["degraded"]?.jsonPrimitive?.content?.toBooleanStrict())
            assertEquals("HTTP_ERROR", parsed["failureCategory"]?.jsonPrimitive?.content)
            assertEquals(2, parsed["attemptCount"]?.jsonPrimitive?.content?.toInt())
            assertEquals(
                listOf("SUMMARY_GENERATE:GOOGLE_SEARCH", "SUMMARY_GENERATE:BASELINE"),
                parsed["fallbackPath"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            val attempts = parsed["attempts"]?.jsonArray
            assertEquals(2, attempts?.size)
            assertEquals(
                1,
                attempts
                    ?.get(0)
                    ?.jsonObject
                    ?.get("attemptIndex")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )
            assertEquals(
                2,
                attempts
                    ?.get(1)
                    ?.jsonObject
                    ?.get("attemptIndex")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )

            val correlationLogs =
                listAppender.list.filter { event ->
                    event.formattedMessage.contains("traceRecorded") &&
                        (event.argumentArray?.any { it.toString().contains(session.traceId) } == true)
                }
            assertEquals(1, correlationLogs.size, "Exactly one lightweight JsonLogger correlation log must be emitted")
            val logText =
                correlationLogs.first().formattedMessage +
                    " " +
                    correlationLogs.first().argumentArray?.joinToString(" ")
            assertFalse(logText.contains("SUPER_SECRET_RAW_PROMPT_MARKER"), "Correlation log must not duplicate raw prompts")
            assertTrue(logText.contains("DEGRADED_SUCCESS"))
        } finally {
            jsonLogger.detachAppender(listAppender)
        }
    }
}
