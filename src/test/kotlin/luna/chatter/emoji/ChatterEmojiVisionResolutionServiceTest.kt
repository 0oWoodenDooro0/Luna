package luna.chatter.emoji

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Guild
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.event.Event
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.BoundedChannelBurstCoordinator
import luna.chatter.BoundedChannelCooldownCache
import luna.chatter.ChatterAiTraceTestSupport
import luna.chatter.ChatterConfig
import luna.chatter.ChatterContextCollector
import luna.chatter.ChatterDecisionConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterNoiseFilter
import luna.chatter.ChatterPlugin
import luna.chatter.ChatterService
import luna.chatter.GeminiDecisionClient
import luna.chatter.PersonaState
import luna.chatter.PersonaStateProvider
import luna.core.ai.GeminiInteractionClient
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterEmojiVisionResolutionServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun openDatabase(name: String = "chatter-emoji-vision.db"): Database =
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve(name).toFile().absolutePath}",
            driver = "org.sqlite.JDBC",
        )

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

    private fun extractInteractionPromptText(body: String): String =
        json
            .parseToJsonElement(body)
            .jsonObject["input"]
            ?.jsonArray
            ?.last()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    private fun extractInteractionModel(body: String): String =
        json
            .parseToJsonElement(body)
            .jsonObject["model"]
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    private fun extractInteractionImageInputs(body: String): List<Pair<String, String>> =
        json
            .parseToJsonElement(body)
            .jsonObject["input"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val obj = element.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "image") {
                    val uri = obj["uri"]?.jsonPrimitive?.content.orEmpty()
                    val mime = obj["mime_type"]?.jsonPrimitive?.content.orEmpty()
                    uri to mime
                } else {
                    null
                }
            }.orEmpty()

    private fun guildEmoji(
        id: Long,
        name: String,
        isAnimated: Boolean = false,
        isAvailable: Boolean = true,
    ): GuildEmoji =
        mockk<GuildEmoji>(relaxed = true).also { emoji ->
            every { emoji.id } returns Snowflake(id)
            every { emoji.name } returns name
            every { emoji.isAnimated } returns isAnimated
            every { emoji.isAvailable } returns isAvailable
        }

    private class MutableClock(
        private var currentInstant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = currentInstant

        fun advanceBySeconds(seconds: Long) {
            currentInstant = currentInstant.plusSeconds(seconds)
        }
    }

    @Test
    fun `ChatterService resolves uncached guild and external emojis on first turn when CHATTER_EMOJI_VISION_MODEL is set`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("first-turn.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)

            val capturedBodies = CopyOnWriteArrayList<Pair<String, String>>()
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    capturedBodies += url to body
                    when {
                        url.contains("/v1beta/decisions") -> {
                            respond(
                                content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        url.contains("/v1beta/interactions") && extractInteractionModel(body) == "gemini-2.5-flash" -> {
                            val imageUri = extractInteractionImageInputs(body).firstOrNull()?.first.orEmpty()
                            val meaning =
                                when {
                                    imageUri.contains("/1001.png") -> "開心比讚"
                                    imageUri.contains("/9001.png") -> "震驚流汗"
                                    else -> "未知表情"
                                }
                            respond(
                                content = """{"id":"vis-1","status":"completed","finish_reason":"STOP","output_text":"$meaning"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        url.contains("/v1beta/interactions") -> {
                            respond(
                                content = """{"id":"int-reply","status":"completed","finish_reason":"STOP","output_text":"太讚了吧 <:a1:1001> <:nitro1:9001>"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            error("Unexpected URL: $url")
                        }
                    }
                }

            val httpClient = createClient(mockEngine)
            val visionResolver =
                CustomEmojiVisionResolver(
                    meaningRepository = repo,
                    visionModel = "models/gemini-2.5-flash",
                    interactionClient = GeminiInteractionClient(httpClient = httpClient),
                    apiKey = "secret-key",
                    clock = clock,
                )
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = repo, visionResolver = visionResolver, clock = clock)

            val sentMessages = CopyOnWriteArrayList<String>()
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })),
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                            httpClient = httpClient,
                        ),
                    chatterGenerator =
                        ChatterGenerator(
                            config = ChatterConfig(apiKey = "secret-key", model = "gemini-3.5-flash-lite", emojiVisionModel = "gemini-2.5-flash"),
                            httpClient = httpClient,
                        ),
                    cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L }),
                    historyFetcher = { _, _ -> emptyList() },
                    messageSender = { _, content ->
                        sentMessages += content
                        mockk(relaxed = true)
                    },
                    contextCollector = ChatterContextCollector(ChatterNoiseFilter(), peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 10L),
                    randomProvider = { _, _ -> 0L },
                    afterMessagesFetcher = { _, _ -> emptyList() },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCatalog = catalog,
                )

            val mockGuild =
                mockk<Guild>(relaxed = true).apply {
                    every { id } returns Snowflake(4001L)
                    every { emojis } returns flowOf(guildEmoji(1001L, "a1"))
                }
            val message =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 2001L,
                    channelId = 3001L,
                    content = "這波 <:a1:1001> 但有點 <:nitro1:9001>",
                )
            coEvery { message.getGuildOrNull() } returns mockGuild

            val result = service.handleMessage(message)
            assertTrue(result, "Expected ChatterService to participate and reply")

            val visionBodies =
                capturedBodies
                    .filter { (url, body) -> url.contains("/v1beta/interactions") && extractInteractionModel(body) == "gemini-2.5-flash" }
            assertEquals(2, visionBodies.size, "Expected 2 vision requests (guild + external Nitro emoji)")
            assertTrue(
                visionBodies.any { (_, body) ->
                    extractInteractionImageInputs(body).any {
                        it.first == "https://cdn.discordapp.com/emojis/1001.png" &&
                            it.second == "image/png"
                    }
                },
                "Expected CDN Direct Ingestion PNG URL for guild emoji 1001",
            )
            assertTrue(
                visionBodies.any { (_, body) ->
                    extractInteractionImageInputs(body).any {
                        it.first == "https://cdn.discordapp.com/emojis/9001.png" &&
                            it.second == "image/png"
                    }
                },
                "Expected CDN Direct Ingestion PNG URL for external Nitro emoji 9001",
            )

            val decisionBody = capturedBodies.first { (url, _) -> url.contains("/v1beta/decisions") }.second
            assertContains(decisionBody, "<:a1:1001>(開心比讚)")
            assertContains(decisionBody, "<:nitro1:9001>(震驚流汗)")

            val replyPrompt =
                capturedBodies
                    .filter { (url, body) -> url.contains("/v1beta/interactions") && extractInteractionModel(body) != "gemini-2.5-flash" }
                    .map { (_, body) -> extractInteractionPromptText(body) }
                    .single()
            assertContains(replyPrompt, "<:a1:1001>(開心比讚)")
            assertContains(replyPrompt, "<:nitro1:9001>(震驚流汗)")
            assertContains(replyPrompt, "<:a1:1001>：開心比讚")
            assertFalse(replyPrompt.contains("<:nitro1:9001>："), "External Nitro emoji must not enter guild selection pool")

            // External Nitro emoji 9001 must still be stripped from delivered reply
            assertEquals(1, sentMessages.size)
            assertContains(sentMessages.single(), "<:a1:1001>")
            assertFalse(sentMessages.single().contains("<:nitro1:9001>"), "External Nitro emoji must be stripped from outgoing reply")

            // Persisted in SQLite
            assertEquals("開心比讚", repo.findById("1001")?.meaning)
            assertEquals("震驚流汗", repo.findById("9001")?.meaning)
        }

    @Test
    fun `strict opt-in sends zero vision requests when CHATTER_EMOJI_VISION_MODEL is unset or blank while still injecting cached meanings`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("opt-in-disabled.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)
            repo.upsertResolvedMeaning(emojiId = "1001", name = "cached", isAnimated = false, meaning = "已快取意思")

            val capturedBodies = CopyOnWriteArrayList<Pair<String, String>>()
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    capturedBodies += url to body
                    when {
                        url.contains("/v1beta/decisions") -> {
                            respond(
                                content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            respond(
                                content = """{"id":"int-reply","status":"completed","finish_reason":"STOP","output_text":"收到 <:cached:1001>"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }

            val httpClient = createClient(mockEngine)
            val visionResolver =
                CustomEmojiVisionResolver(
                    meaningRepository = repo,
                    visionModel = "   ",
                    interactionClient = GeminiInteractionClient(httpClient = httpClient),
                    apiKey = "secret-key",
                    clock = clock,
                )
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = repo, visionResolver = visionResolver, clock = clock)

            val sentMessages = CopyOnWriteArrayList<String>()
            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })),
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                            httpClient = httpClient,
                        ),
                    chatterGenerator =
                        ChatterGenerator(
                            config = ChatterConfig(apiKey = "secret-key", model = "gemini-3.5-flash-lite", emojiVisionModel = "   "),
                            httpClient = httpClient,
                        ),
                    cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L }),
                    historyFetcher = { _, _ -> emptyList() },
                    messageSender = { _, content ->
                        sentMessages += content
                        mockk(relaxed = true)
                    },
                    contextCollector = ChatterContextCollector(ChatterNoiseFilter(), peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 10L),
                    randomProvider = { _, _ -> 0L },
                    afterMessagesFetcher = { _, _ -> emptyList() },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCatalog = catalog,
                )

            val mockGuild =
                mockk<Guild>(relaxed = true).apply {
                    every { id } returns Snowflake(4002L)
                    every { emojis } returns flowOf(guildEmoji(1001L, "cached"), guildEmoji(1002L, "uncached"))
                }
            val message =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 2002L,
                    channelId = 3002L,
                    content = "看看 <:cached:1001> 跟 <:uncached:1002>",
                )
            coEvery { message.getGuildOrNull() } returns mockGuild

            assertTrue(service.handleMessage(message), "Expected ChatterService to handle message when vision model is unset")

            val interactionRequests = capturedBodies.filter { (url, _) -> url.contains("/v1beta/interactions") }
            assertEquals(1, interactionRequests.size, "Expected only the reply interaction request and zero vision requests")
            val replyPrompt = extractInteractionPromptText(interactionRequests.single().second)
            assertContains(replyPrompt, "<:cached:1001>(已快取意思)")
            assertContains(replyPrompt, "<:uncached:1002>")
            assertFalse(replyPrompt.contains("<:uncached:1002>("), "Uncached emoji must remain bare when vision is disabled")
        }

    @Test
    fun `ChatterPlugin wires CustomEmojiVisionResolver only when CHATTER_EMOJI_VISION_MODEL is configured`() =
        runTest {
            val db = openDatabase("plugin-vision.db")
            val mockKord = mockk<Kord>(relaxed = true)
            val eventsFlow = MutableSharedFlow<Event>()
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns eventsFlow

            val pluginWithVision = ChatterPlugin()
            pluginWithVision.initialize(
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                    configMap =
                        mapOf(
                            "GEMINI_API_KEY" to "test-key",
                            "CHATTER_EMOJI_VISION_MODEL" to "gemini-2.5-flash",
                        ),
                ),
            )
            assertNotNull(pluginWithVision.emojiVisionResolver)
            assertTrue(
                pluginWithVision.emojiVisionResolver!!.isEnabled,
                "Expected emojiVisionResolver to be enabled when CHATTER_EMOJI_VISION_MODEL is configured",
            )
            assertIs<DefaultChatterEmojiCatalog>(pluginWithVision.emojiCatalog)
            pluginWithVision.shutdown()

            val pluginWithoutVision = ChatterPlugin()
            pluginWithoutVision.initialize(
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                    configMap = mapOf("GEMINI_API_KEY" to "test-key"),
                ),
            )
            assertNull(pluginWithoutVision.emojiVisionResolver)
            pluginWithoutVision.shutdown()
        }

    @Test
    fun `Silent Reaction Path resolves uncached emojis on first turn and injects meanings into transcript and guild pool`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-19T10:00:00Z"))
            val db = openDatabase("silent-reaction.db")
            val repo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)

            val capturedReactionPrompts = CopyOnWriteArrayList<Pair<String, String>>()
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    when {
                        url.contains("/v1beta/decisions") -> {
                            val decisionJson =
                                """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.55,"confidence":0.85}}}"""
                            respond(
                                content = decisionJson,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        url.contains("/v1beta/interactions") && extractInteractionModel(body) == "gemini-2.5-flash" -> {
                            val uri = extractInteractionImageInputs(body).single().first
                            val meaning =
                                when {
                                    uri.contains("/1001.png") -> "比心鼓勵"
                                    uri.contains("/9001.png") -> "大笑噴茶"
                                    else -> "其他"
                                }
                            respond(
                                content = """{"id":"vis-react","status":"completed","finish_reason":"STOP","output_text":"$meaning"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            val systemInstruction =
                                json
                                    .parseToJsonElement(body)
                                    .jsonObject["system_instruction"]
                                    ?.jsonPrimitive
                                    ?.content
                                    .orEmpty()
                            capturedReactionPrompts += systemInstruction to extractInteractionPromptText(body)
                            respond(
                                content = """{"id":"int-react","status":"completed","finish_reason":"STOP","output_text":"<:cheer:1001>"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }

            val httpClient = createClient(mockEngine)
            val visionResolver =
                CustomEmojiVisionResolver(
                    meaningRepository = repo,
                    visionModel = "gemini-2.5-flash",
                    interactionClient = GeminiInteractionClient(httpClient = httpClient),
                    apiKey = "secret-key",
                    clock = clock,
                )
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = repo, visionResolver = visionResolver, clock = clock)

            val service =
                ChatterService(
                    filter = ChatterNoiseFilter(cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })),
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                            httpClient = httpClient,
                        ),
                    chatterGenerator =
                        ChatterGenerator(
                            config =
                                ChatterConfig(
                                    apiKey = "secret-key",
                                    reactionThreshold = 0.45,
                                    emojiVisionModel = "gemini-2.5-flash",
                                ),
                            httpClient = httpClient,
                        ),
                    cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L }),
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = ChatterContextCollector(ChatterNoiseFilter(), peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L, mentionDebounceMs = 1L, maxDebounceMs = 10L),
                    randomProvider = { _, _ -> 0L },
                    afterMessagesFetcher = { _, _ -> emptyList() },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCatalog = catalog,
                )

            val mockGuild =
                mockk<Guild>(relaxed = true).apply {
                    every { id } returns Snowflake(4010L)
                    every { emojis } returns flowOf(guildEmoji(1001L, "cheer"))
                }
            val message =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 2010L,
                    channelId = 3010L,
                    content = "太好笑 <:nitro_lol:9001>",
                )
            coEvery { message.getGuildOrNull() } returns mockGuild
            coEvery { message.addReaction(any<ReactionEmoji>()) } just runs

            assertTrue(service.handleMessage(message), "Expected Silent Reaction Path to handle message")
            assertEquals(1, capturedReactionPrompts.size)
            val (reactionSystem, reactionPrompt) = capturedReactionPrompts.single()
            assertContains(reactionPrompt, "<:nitro_lol:9001>(大笑噴茶)")
            assertContains(reactionSystem, "<:cheer:1001>：比心鼓勵")
            assertFalse(reactionSystem.contains("9001"), "External Nitro emoji must not enter reaction selection pool")
        }
}
