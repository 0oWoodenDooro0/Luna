package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.entity.MessageReference
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    @Test
    fun `ChatterService preserves DIRECT_REPLY trigger when same-author follow-up coalesces in burst window`(
        @TempDir tempDir: Path,
    ) = runTest {
        val mockEngine =
            MockEngine { request ->
                assertFalse(request.url.encodedPath.contains("/decisions"), "Coalesced DIRECT_REPLY must not call /v1beta/decisions")
                respond(
                    content =
                        """
                        {
                          "id": "int-direct-reply-burst",
                          "status": "completed",
                          "finish_reason": "STOP",
                          "usage": {"input_tokens": 50, "output_tokens": 8, "total_tokens": 58},
                          "steps": [{"type": "model_output", "content": [{"type": "text", "text": "對呀！"}]}]
                        }
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }

        val recorder = JsonlAiTraceRecorder(traceDir = tempDir, clock = ChatterAiTraceTestSupport.fixedClock)
        val httpClient = ChatterAiTraceTestSupport.createHttpClient(mockEngine)
        val filter = ChatterNoiseFilter()
        val service =
            ChatterService(
                filter = filter,
                decisionClient = GeminiDecisionClient(config = ChatterConfig(apiKey = "secret-key"), httpClient = httpClient, traceRecorder = recorder),
                chatterGenerator = ChatterGenerator(config = ChatterConfig(apiKey = "secret-key"), httpClient = httpClient, traceRecorder = recorder),
                channelPort = FakeChatterChannelPort(),
                burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 200L, mentionDebounceMs = 200L, maxDebounceMs = 1_000L),
                traceRecorder = recorder,
            )

        val selfId = Snowflake(9999L)
        val lunaChatter =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 520L,
                channelId = 603L,
                guildId = 701L,
                authorId = 9999L,
                username = "Luna",
                content = "今天吃什麼",
                isBot = true,
                selfId = selfId,
            )
        val pingOffReply =
            ChatterAiTraceTestSupport
                .createMockMessage(
                    id = 521L,
                    channelId = 603L,
                    guildId = 701L,
                    authorId = 801L,
                    username = "Alice",
                    content = "我吃拉麵",
                    type = MessageType.Reply,
                    mentionedUserIds = emptySet(),
                    selfId = selfId,
                ).apply {
                    every { referencedMessage } returns lunaChatter
                }
        val followUp =
            ChatterAiTraceTestSupport.createMockMessage(
                id = 522L,
                channelId = 603L,
                guildId = 701L,
                authorId = 801L,
                username = "Alice",
                content = "加蛋超讚",
                type = MessageType.Default,
                mentionedUserIds = emptySet(),
                selfId = selfId,
            )

        coroutineScope {
            val firstJob = async { service.processMessage(pingOffReply) }
            delay(20L)
            val secondJob = async { service.processMessage(followUp) }

            assertFalse(firstJob.await(), "First message should yield to coalesced follow-up")
            assertTrue(secondJob.await(), "Coalesced same-author follow-up should win and deliver")
        }
        recorder.flushAndClose()

        val traces = ChatterAiTraceTestSupport.readTraceLines(tempDir)
        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals("522", trace.discord.messageId)
        assertEquals(AiTraceTrigger.DIRECT_REPLY, trace.trigger)
        assertTrue(trace.discord.isDirectMention, "Coalesced follow-up to DIRECT_REPLY should keep discord.isDirectMention = true")
    }

    @Test
    fun `ChatterContextCollector retains uncached empty or emoji-only ping-off reply to Luna chatter and excludes summary reply`() =
        runTest {
            val selfId = Snowflake(9999L)
            val channelId = Snowflake(604L)
            val lunaChatter =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 888L,
                    channelId = 604L,
                    guildId = 701L,
                    authorId = 9999L,
                    username = "Luna",
                    content = "How was your day?",
                    isBot = true,
                    selfId = selfId,
                )
            val lunaSummary =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 889L,
                    channelId = 604L,
                    guildId = 701L,
                    authorId = 9999L,
                    username = "Luna",
                    content = "TL;DR: Daily summary",
                    isBot = true,
                    selfId = selfId,
                )
            val channelBehavior =
                mockk<MessageChannelBehavior> {
                    coEvery { getMessageOrNull(Snowflake(888L)) } returns lunaChatter
                    coEvery { getMessageOrNull(Snowflake(889L)) } returns lunaSummary
                }
            val chatterRef =
                mockk<MessageReference> {
                    every { data.id } returns OptionalSnowflake.Value(Snowflake(888L))
                    every { data.channelId } returns OptionalSnowflake.Value(channelId)
                }
            val summaryRef =
                mockk<MessageReference> {
                    every { data.id } returns OptionalSnowflake.Value(Snowflake(889L))
                    every { data.channelId } returns OptionalSnowflake.Value(channelId)
                }
            val uncachedReplyToChatter =
                ChatterAiTraceTestSupport
                    .createMockMessage(
                        id = 922L,
                        channelId = 604L,
                        guildId = 701L,
                        authorId = 801L,
                        username = "Alice",
                        content = "",
                        type = MessageType.Reply,
                        selfId = selfId,
                    ).apply {
                        every { referencedMessage } returns null
                        every { messageReference } returns chatterRef
                        every { channel } returns channelBehavior
                    }
            val uncachedReplyToSummary =
                ChatterAiTraceTestSupport
                    .createMockMessage(
                        id = 923L,
                        channelId = 604L,
                        guildId = 701L,
                        authorId = 802L,
                        username = "Bob",
                        content = "",
                        type = MessageType.Reply,
                        selfId = selfId,
                    ).apply {
                        every { referencedMessage } returns null
                        every { messageReference } returns summaryRef
                        every { channel } returns channelBehavior
                    }

            val collector = ChatterContextCollector(noiseFilter = ChatterNoiseFilter())
            val context = collector.collectReplyContext(listOf(uncachedReplyToChatter, uncachedReplyToSummary), selfId)

            assertTrue(
                context.transcript.contains("Alice"),
                "Uncached empty/emoji-only ping-off reply to Luna chatter should be retained in context",
            )
            assertFalse(
                context.transcript.contains("Bob"),
                "Uncached empty/emoji-only ping-off reply to Luna summary should remain excluded from context",
            )
        }
}
