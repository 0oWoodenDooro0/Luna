package luna.chatter.emoji

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
import io.mockk.mockk
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
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatterEmojiMeaningServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun openDatabase(name: String = "chatter-emoji-service.db"): Database =
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

    private fun extractInteractionSystemInstruction(body: String): String =
        json
            .parseToJsonElement(body)
            .jsonObject["system_instruction"]
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    @Test
    fun `ChatterService injects cached meanings into decision and reply prompts while keeping external Nitro emojis non-sendable`() =
        runTest {
            val db = openDatabase("reply-path.db")
            val repo =
                ExposedCustomEmojiMeaningRepository(
                    database = db,
                    clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC),
                )
            repo.upsertResolvedMeaning("1001", "guild_happy", isAnimated = false, meaning = "開心慶祝")
            repo.upsertResolvedMeaning("9001", "nitro_hype", isAnimated = true, meaning = "超興奮")

            val capturedDecisionBodies = mutableListOf<String>()
            val capturedInteractionBodies = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    if (url.contains("/v1beta/interactions")) {
                        capturedInteractionBodies += body
                        respond(
                            content =
                                """{"id":"int-reply-1","status":"completed","finish_reason":"STOP","output_text":"太讚了吧 <:guild_happy:1001> <a:nitro_hype:9001>"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        capturedDecisionBodies += body
                        respond(
                            content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.92,"confidence":0.92}}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })
            val filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
            val decisionClient =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                    httpClient = createClient(mockEngine),
                )
            val chatterGenerator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "secret-key"),
                    httpClient = createClient(mockEngine),
                )

            val guildEmojiCached =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(1001L)
                    every { name } returns "guild_happy"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val guildEmojiUncached =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(1002L)
                    every { name } returns "guild_uncached"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val mockGuild =
                mockk<Guild>(relaxed = true).apply {
                    every { id } returns Snowflake(777L)
                    every { emojis } returns flowOf(guildEmojiCached, guildEmojiUncached)
                }

            val message =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 201L,
                    channelId = 301L,
                    content = "終於過關 <:guild_happy:1001> <:guild_happy:1001> <a:nitro_hype:9001> <:uncached_ext:5001>",
                )
            coEvery { message.getGuildOrNull() } returns mockGuild

            val sentMessages = mutableListOf<String>()
            val service =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    messageSender = { _, content ->
                        sentMessages += content
                        mockk(relaxed = true)
                    },
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    afterMessagesFetcher = { _, _ -> emptyList() },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCatalog = DefaultChatterEmojiCatalog(meaningRepository = repo),
                )

            val handled = service.handleMessage(message)

            assertTrue(handled, "Expected reply path to handle message")
            assertEquals(
                listOf("太讚了吧 <:guild_happy:1001>"),
                sentMessages,
                "External Nitro emoji must be stripped by Guild Custom Emoji Policy while guild emoji stays",
            )

            val decisionBody = capturedDecisionBodies.single()
            assertContains(decisionBody, "<:guild_happy:1001>(開心慶祝) <:guild_happy:1001>(開心慶祝)")
            assertContains(decisionBody, "<a:nitro_hype:9001>(超興奮)")
            assertContains(decisionBody, "<:uncached_ext:5001>")
            assertFalse(decisionBody.contains("<:uncached_ext:5001>()"), "Uncached emoji must not get empty parentheses")

            val replyPrompt = extractInteractionPromptText(capturedInteractionBodies.single())
            assertContains(replyPrompt, "<:guild_happy:1001>(開心慶祝) <:guild_happy:1001>(開心慶祝)")
            assertContains(replyPrompt, "<a:nitro_hype:9001>(超興奮)")
            assertContains(replyPrompt, "<:uncached_ext:5001>")
            assertFalse(replyPrompt.contains("<:uncached_ext:5001>()"), "Uncached emoji must not get empty parentheses")
            assertContains(replyPrompt, "<:guild_happy:1001>：開心慶祝")
            assertContains(replyPrompt, "<:guild_uncached:1002>")
            assertFalse(replyPrompt.contains("<:guild_uncached:1002>："), "Uncached guild emoji must stay bare in pool")
            assertFalse(replyPrompt.contains("<a:nitro_hype:9001>："), "External Nitro emoji must never enter guild pool")
        }

    @Test
    fun `ChatterService injects cached meanings into Silent Reaction Path transcript and guild selection pool`() =
        runTest {
            val db = openDatabase("reaction-path.db")
            val repo =
                ExposedCustomEmojiMeaningRepository(
                    database = db,
                    clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC),
                )
            repo.upsertResolvedMeaning("1001", "guild_happy", isAnimated = false, meaning = "開心慶祝")
            repo.upsertResolvedMeaning("9001", "nitro_hype", isAnimated = true, meaning = "超興奮")

            val capturedInteractionBodies = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    if (url.contains("/v1beta/interactions")) {
                        capturedInteractionBodies += body
                        respond(
                            content =
                                """{"id":"int-react-1","status":"completed","finish_reason":"STOP","output_text":"<:guild_happy:1001>"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.55,"confidence":0.85}}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })
            val filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
            val decisionClient =
                GeminiDecisionClient(
                    config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                    httpClient = createClient(mockEngine),
                )
            val chatterGenerator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45),
                    httpClient = createClient(mockEngine),
                )

            val guildEmojiCached =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(1001L)
                    every { name } returns "guild_happy"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val guildEmojiUncached =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(1002L)
                    every { name } returns "guild_uncached"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val mockGuild =
                mockk<Guild>(relaxed = true).apply {
                    every { id } returns Snowflake(777L)
                    every { emojis } returns flowOf(guildEmojiCached, guildEmojiUncached)
                }

            val message =
                ChatterAiTraceTestSupport.createMockMessage(
                    id = 202L,
                    channelId = 302L,
                    content = "大家一起嗨 <:guild_happy:1001> <a:nitro_hype:9001>",
                )
            coEvery { message.getGuildOrNull() } returns mockGuild

            val deliveredReactions = mutableListOf<ReactionEmoji>()
            val service =
                ChatterService(
                    filter = filter,
                    decisionClient = decisionClient,
                    chatterGenerator = chatterGenerator,
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    afterMessagesFetcher = { _, _ -> emptyList() },
                    personaStateProvider = fixedPersonaProvider(),
                    reactionSender = { _, emoji -> deliveredReactions += emoji },
                    emojiCatalog = DefaultChatterEmojiCatalog(meaningRepository = repo),
                )

            val handled = service.handleMessage(message)

            assertTrue(handled, "Expected Silent Reaction Path to handle message")
            assertEquals(1, deliveredReactions.size, "Expected 1 reaction delivered")
            val customReaction = assertIs<ReactionEmoji.Custom>(deliveredReactions.single())
            assertEquals(Snowflake(1001L), customReaction.id)

            val reactionBody = capturedInteractionBodies.single()
            val reactionUserPrompt = extractInteractionPromptText(reactionBody)
            assertContains(reactionUserPrompt, "<:guild_happy:1001>(開心慶祝)")
            assertContains(reactionUserPrompt, "<a:nitro_hype:9001>(超興奮)")

            val reactionSystemInstruction = extractInteractionSystemInstruction(reactionBody)
            assertContains(reactionSystemInstruction, "<:guild_happy:1001>：開心慶祝")
            assertContains(reactionSystemInstruction, "<:guild_uncached:1002>")
            assertFalse(
                reactionSystemInstruction.contains("<:guild_uncached:1002>："),
                "Uncached guild emoji must remain bare without meaning separator in reaction pool",
            )
            assertFalse(reactionSystemInstruction.contains("9001"), "External Nitro emoji must not appear in reaction pool")
        }

    @Test
    fun `ChatterPlugin initializes DefaultChatterEmojiCatalog backed by PluginContext database`() =
        runTest {
            val db = openDatabase("plugin-wiring.db")
            val mockKord = mockk<Kord>(relaxed = true)
            val eventsFlow = MutableSharedFlow<Event>()
            every { mockKord.coroutineContext } returns EmptyCoroutineContext
            every { mockKord.events } returns eventsFlow

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = db,
                    configMap =
                        mapOf(
                            "GEMINI_API_KEY" to "test-key",
                            "CHATTER_EMOJI_VISION_MODEL" to "gemini-2.5-flash",
                        ),
                )

            val plugin = ChatterPlugin()
            plugin.initialize(context)

            assertNotNull(plugin.emojiCatalog, "ChatterPlugin must initialize emojiCatalog")
            assertIs<DefaultChatterEmojiCatalog>(plugin.emojiCatalog)
            assertEquals("gemini-2.5-flash", plugin.chatterGenerator?.config?.cleanEmojiVisionModel)
            assertIs<DefaultChatterEmojiCatalog>(plugin.service?.emojiCatalog)

            plugin.shutdown()
        }
}
