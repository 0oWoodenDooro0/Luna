package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import kotlinx.coroutines.test.runTest
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceOutcome
import luna.core.ai.AiTraceTrigger
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterDirectReplyServiceTest {
    @Test
    fun `ChatterService records DIRECT_REPLY trigger, bypasses decision and cooldown, and sets pingRepliedUser`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine { request ->
                assertFalse(request.url.encodedPath.contains("/decisions"), "DIRECT_REPLY must not call /v1beta/decisions")
                respond(
                    content =
                        """
                        {
                          "id": "int-direct-reply-1",
                          "status": "completed",
                          "finish_reason": "STOP",
                          "usage": {
                            "input_tokens": 60,
                            "output_tokens": 10,
                            "total_tokens": 70
                          },
                          "steps": [
                            {
                              "type": "model_output",
                              "content": [{"type": "text", "text": "真的耶！"}]
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
        val filter =
            ChatterNoiseFilter(
                cooldownCache = BoundedChannelCooldownCache(defaultCooldownDurationMs = 60_000L),
            )
        val decisionClient =
            GeminiDecisionClient(
                config = ChatterConfig(apiKey = "secret-gemini-key"),
                httpClient = httpClient,
                traceRecorder = recorder,
            )
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-gemini-key"),
                httpClient = httpClient,
                traceRecorder = recorder,
            )
        val selfId = Snowflake(9999L)
        val channelPort =
            FakeChatterChannelPort().apply {
                afterMessages =
                    listOf(
                        ChatterAiTraceTestSupport.createMockMessage(
                            id = 502L,
                            channelId = 601L,
                            guildId = 701L,
                            authorId = 802L,
                            username = "Bob",
                            content = "有人在嗎",
                            selfId = selfId,
                        ),
                        ChatterAiTraceTestSupport.createMockMessage(
                            id = 503L,
                            channelId = 601L,
                            guildId = 701L,
                            authorId = 803L,
                            username = "Carol",
                            content = "我也剛上線",
                            selfId = selfId,
                        ),
                        ChatterAiTraceTestSupport.createMockMessage(
                            id = 504L,
                            channelId = 601L,
                            guildId = 701L,
                            authorId = 804L,
                            username = "Dave",
                            content = "準備開團",
                            selfId = selfId,
                        ),
                    )
            }
        val service =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = generator,
                channelPort = channelPort,
                traceRecorder = recorder,
            )

        val lunaChatter =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 500L,
                channelId = 601L,
                guildId = 701L,
                authorId = 9999L,
                username = "Luna",
                content = "今天天氣不錯",
                isBot = true,
                selfId = selfId,
            )
        val pingOffReply =
            ChatterAiTraceTestSupport
                .createMockMessage(
                    id = 501L,
                    channelId = 601L,
                    guildId = 701L,
                    authorId = 801L,
                    username = "Alice",
                    content = "對啊超舒服",
                    type = MessageType.Reply,
                    mentionedUserIds = emptySet(),
                    selfId = selfId,
                ).apply {
                    every { referencedMessage } returns lunaChatter
                }

        filter.cooldownCache.record(Snowflake(601L))

        val handled = service.processMessage(pingOffReply)
        recorder.flushAndClose()

        assertTrue(handled, "Ping-off reply to Luna chatter should be handled")
        assertEquals(listOf("真的耶！"), channelPort.allDeliveredTexts)
        assertTrue(channelPort.sentReplies.single().pingRepliedUser, "Ping-off reply should still set pingRepliedUser = true")

        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTraceTrigger.DIRECT_REPLY, trace.trigger)
        assertTrue(trace.discord.isDirectMention, "DIRECT_REPLY should set discord.isDirectMention = true")
        assertTrue(trace.attempts.none { it.stage == AiAttemptStage.DECISION }, "DIRECT_REPLY should bypass Participation Decision")
    }

    @Test
    fun `ChatterService routes ping-off reply to Luna summary through AMBIENT Participation Decision`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine { request ->
                assertTrue(request.url.encodedPath.contains("/decisions"), "Summary reply must still go through /v1beta/decisions")
                respond(
                    content =
                        """
                        {
                          "answers": {
                            "should_participate": {
                              "type": "noul",
                              "noul": 0.12,
                              "confidence": 0.90
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
        val filter = ChatterNoiseFilter()
        val decisionClient =
            GeminiDecisionClient(
                config = ChatterConfig(apiKey = "secret-gemini-key"),
                httpClient = httpClient,
                traceRecorder = recorder,
            )
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "secret-gemini-key"),
                httpClient = httpClient,
                traceRecorder = recorder,
            )
        val channelPort = FakeChatterChannelPort()
        val service =
            ChatterService(
                filter = filter,
                decisionClient = decisionClient,
                chatterGenerator = generator,
                channelPort = channelPort,
                traceRecorder = recorder,
            )

        val selfId = Snowflake(9999L)
        val lunaSummary =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 510L,
                channelId = 602L,
                guildId = 701L,
                authorId = 9999L,
                username = "Luna",
                content = "TL;DR: Everyone talked about dinner",
                isBot = true,
                selfId = selfId,
            )
        val replyToSummary =
            ChatterAiTraceTestSupport
                .createMockMessage(
                    id = 511L,
                    channelId = 602L,
                    guildId = 701L,
                    authorId = 801L,
                    username = "Alice",
                    content = "感謝摘要",
                    type = MessageType.Reply,
                    mentionedUserIds = emptySet(),
                    selfId = selfId,
                ).apply {
                    every { referencedMessage } returns lunaSummary
                }

        val handled = service.processMessage(replyToSummary)
        recorder.flushAndClose()

        assertFalse(handled, "Skipped summary reply should return false")
        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(AiTraceTrigger.AMBIENT, trace.trigger)
        assertEquals(AiTraceOutcome.SKIPPED, trace.outcome)
        assertFalse(trace.discord.isDirectMention, "Reply to Luna summary should keep discord.isDirectMention = false")
        assertTrue(trace.attempts.any { it.stage == AiAttemptStage.DECISION }, "Reply to Luna summary should run Participation Decision")
    }
}
