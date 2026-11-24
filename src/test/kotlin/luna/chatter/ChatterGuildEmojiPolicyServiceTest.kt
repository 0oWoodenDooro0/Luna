package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.GuildEmoji
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
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterGuildEmojiPolicyServiceTest {
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

    private fun fixedPersonaProvider(): PersonaStateProvider =
        object : PersonaStateProvider {
            override fun currentState(baseThreshold: Double): PersonaState =
                PersonaState(
                    driftEnabled = false,
                    timezone = "Asia/Taipei",
                    localTime = ZonedDateTime.of(2026, 11, 19, 14, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                    formattedWeekday = "星期四",
                    formattedTime = "14:00",
                    circadianPeriod = null,
                    mood = null,
                    baseThreshold = baseThreshold,
                    effectiveThreshold = baseThreshold,
                )
        }

    @Test
    fun `ChatterService fetches bounded guild custom emojis, propagates to prompt, and sanitizes delivered reply`() =
        runTest {
            val capturedInteractionBodies = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    if (url.contains("/v1beta/interactions")) {
                        capturedInteractionBodies += body
                        respond(
                            content =
                                """{"id":"int-reply-1","status":"completed","finish_reason":"STOP","output_text":"這波穩了 😂 <:emoji_1:1001> <a:emoji_2:1002> <:fake:999999> <a:other:111111>"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.90,"confidence":0.90}}}""",
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
                    config = ChatterConfig(apiKey = "secret-key"),
                    httpClient = createClient(mockEngine),
                )

            val unavailableEmoji =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(999L)
                    every { name } returns "broken_emoji"
                    every { isAvailable } returns false
                    every { isAnimated } returns false
                }
            val guildEmojiMocks =
                listOf(unavailableEmoji) +
                    (1..55).map { idx ->
                        mockk<GuildEmoji>(relaxed = true).apply {
                            every { id } returns Snowflake(1000L + idx)
                            every { name } returns "emoji_$idx"
                            every { isAvailable } returns true
                            every { isAnimated } returns (idx % 2 == 0)
                        }
                    }
            val message = ChatterAiTraceTestSupport.createMockMessage(id = 201L, channelId = 301L, content = "大家覺得這波穩嗎")
            val channelPort =
                FakeChatterChannelPort().apply {
                    guildEmojis = guildEmojiMocks
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
                    personaStateProvider = fixedPersonaProvider(),
                )

            val handled = service.processMessage(message)

            assertTrue(handled, "Guild emoji policy message should be handled")
            assertEquals(listOf("這波穩了  <:emoji_1:1001> <a:emoji_2:1002>"), channelPort.allDeliveredTexts)

            val promptText =
                json
                    .parseToJsonElement(capturedInteractionBodies.single())
                    .jsonObject["input"]
                    ?.jsonArray
                    ?.last()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertContains(promptText, "<:emoji_1:1001>")
            assertContains(promptText, "<a:emoji_2:1002>")
            assertContains(promptText, "<a:emoji_10:1010>")
            assertFalse(promptText.contains("broken_emoji"), "Unavailable guild emojis must be excluded from reply prompt")
            assertFalse(promptText.contains("emoji_11"), "Guild emojis in reply prompt must be bounded to 10")
        }

    @Test
    fun `ChatterService degrades safely with observable debug and warn logs on DM empty guild or failed lookup`() =
        runTest {
            val serviceLogger = LoggerFactory.getLogger(ChatterService::class.java) as Logger
            val previousLevel = serviceLogger.level
            serviceLogger.level = Level.DEBUG
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            serviceLogger.addAppender(appender)

            try {
                val capturedInteractionBodies = mutableListOf<String>()
                val mockEngine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        val body = request.body.toByteArray().decodeToString()
                        if (url.contains("/v1beta/interactions")) {
                            capturedInteractionBodies += body
                            respond(
                                content =
                                    """{"id":"int-dm-1","status":"completed","finish_reason":"STOP","output_text":"收到 😂 <:blobcat:111222> <a:party:555666>"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond(
                                content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.90}}}""",
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
                        config = ChatterConfig(apiKey = "secret-key"),
                        httpClient = createClient(mockEngine),
                    )

                // 1. DM / empty guild fallback (listGuildCustomEmojis returns empty list)
                val dmMessage = ChatterAiTraceTestSupport.createMockMessage(id = 202L, channelId = 302L, guildId = null, content = "私訊問一下")
                val dmChannelPort = FakeChatterChannelPort()
                val dmService =
                    ChatterService(
                        filter = filter,
                        decisionClient = decisionClient,
                        chatterGenerator = chatterGenerator,
                        channelPort = dmChannelPort,
                        contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                        burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                        randomProvider = { _, _ -> 0L },
                        personaStateProvider = fixedPersonaProvider(),
                    )
                assertTrue(dmService.processMessage(dmMessage), "DM message should be handled")
                assertEquals(listOf("收到"), dmChannelPort.allDeliveredTexts)
                assertFalse(
                    capturedInteractionBodies[0].contains("伺服器表情（"),
                    "DM prompt must not include guild custom emojis section",
                )

                // 2. Direct mention when listGuildCustomEmojis throws exception -> WARN log + safe fallback
                val botSelfId = Snowflake(9999L)
                val mentionMessage =
                    ChatterAiTraceTestSupport.createMockMessage(
                        id = 203L,
                        channelId = 303L,
                        content = "<@9999> 嗨嗨",
                        mentionedUserIds = setOf(botSelfId),
                    )
                val failChannelPort =
                    FakeChatterChannelPort().apply {
                        guildEmojisError = IllegalStateException("Guild emoji cache miss")
                    }
                val failService =
                    ChatterService(
                        filter = filter,
                        decisionClient = decisionClient,
                        chatterGenerator = chatterGenerator,
                        channelPort = failChannelPort,
                        contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                        burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                        randomProvider = { _, _ -> 0L },
                        personaStateProvider = fixedPersonaProvider(),
                    )
                assertTrue(failService.processMessage(mentionMessage), "Failed guild emoji lookup must still deliver reply")
                assertEquals(listOf("收到"), failChannelPort.allDeliveredTexts)
                assertFalse(
                    capturedInteractionBodies[1].contains("伺服器表情（"),
                    "Failed guild emoji lookup must omit guild custom emojis section",
                )

                val debugLogs = appender.list.filter { it.level == Level.DEBUG }.map { it.formattedMessage }
                val warnLogs = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    debugLogs.any { it.contains("guild custom emojis", ignoreCase = true) && it.contains("202") },
                    "Expected observable DEBUG log for empty/DM guild emojis",
                )
                assertTrue(
                    warnLogs.any {
                        it.contains("guild emojis", ignoreCase = true) &&
                            it.contains("203") &&
                            it.contains("Guild emoji cache miss")
                    },
                    "Expected observable WARN log when guildEmojisProvider throws",
                )
            } finally {
                serviceLogger.detachAppender(appender)
                serviceLogger.level = previousLevel
            }
        }
}
