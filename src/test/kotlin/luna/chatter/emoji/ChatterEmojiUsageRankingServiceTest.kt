package luna.chatter.emoji

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Guild
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.Message
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.User
import dev.kord.core.event.message.ReactionAddEvent
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
import luna.chatter.GuildCustomEmoji
import luna.chatter.PersonaState
import luna.chatter.PersonaStateProvider
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterEmojiUsageRankingServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun openDatabase(name: String = "emoji-ranking-service.db"): Database =
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve(name).toFile().absolutePath}",
            driver = "org.sqlite.JDBC",
        )

    private fun fixedClock(isoInstant: String): Clock = Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC)

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

    private fun extractInteractionUserPrompt(body: String): String =
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

    @Test
    fun `catalog ranks top-50 guild emojis by 30-day message and reaction usage with deterministic name-then-id backfill`() =
        runTest {
            val db = openDatabase()
            val clock = fixedClock("2026-11-19T10:00:00Z")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)
            val usageRepo = ExposedEmojiUsageRankingRepository(database = db, clock = clock)
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = meaningRepo, usageRepository = usageRepo)
            val guildId = Snowflake(100L)

            // Create 60 available guild emojis: z_top (id 9001), m_second (id 9002), a_tied_1 (id 8001), a_tied_2 (id 8002), plus 56 unused
            val available =
                listOf(
                    GuildCustomEmoji(Snowflake(9001L), "z_top", isAnimated = false),
                    GuildCustomEmoji(Snowflake(9002L), "m_second", isAnimated = false),
                    GuildCustomEmoji(Snowflake(8002L), "a_tied", isAnimated = false),
                    GuildCustomEmoji(Snowflake(8001L), "a_tied", isAnimated = false),
                ) +
                    (1..56).map { idx ->
                        GuildCustomEmoji(
                            id = Snowflake(1000L + idx),
                            name = "unused_${idx.toString().padStart(2, '0')}",
                            isAnimated = false,
                        )
                    }

            // Record message usage (including an external Nitro emoji 77777 and an unavailable guild emoji 66666)
            catalog.recordMessageUsage(
                guildId = guildId,
                content = "<:z_top:9001> <:z_top:9001> <:m_second:9002> <:ext_nitro:77777> <:unavail:66666>",
            )
            // Record custom reaction usage + Unicode reaction (should be ignored)
            catalog.recordReactionUsage(guildId, ReactionEmoji.Custom(Snowflake(9001L), "z_top", false))
            catalog.recordReactionUsage(guildId, ReactionEmoji.Custom(Snowflake(8002L), "a_tied", false))
            catalog.recordReactionUsage(guildId, ReactionEmoji.Custom(Snowflake(8001L), "a_tied", false))
            catalog.recordReactionUsage(guildId, ReactionEmoji.Unicode("🔥"))

            val pool = catalog.prepareGuildEmojiPool(guildId = guildId, availableGuildEmojis = available)

            assertEquals(50, pool.size, "Guild emoji pool must be capped at 50")
            assertEquals(
                listOf("9001", "8001", "8002", "9002"),
                pool.take(4).map { it.id.toString() },
                "Expected z_top(3), then tied a_tied(1) ordered by id 8001 before 8002, then m_second(1) by name",
            )
            assertEquals(
                (1..46).map { "unused_${it.toString().padStart(2, '0')}" },
                pool.drop(4).map { it.name },
                "Expected remaining 46 slots backfilled deterministically by name",
            )
            assertFalse(
                pool.any { it.id.toString() == "77777" || it.id.toString() == "66666" },
                "External Nitro and unavailable emojis must not appear in guild selection pool",
            )
        }

    @Test
    fun `ChatterService tracks guild message and reaction emoji usage and ranks top-50 pool for reply and silent reaction prompts`() =
        runTest {
            val db = openDatabase("service-ranking.db")
            val clock = fixedClock("2026-11-19T10:00:00Z")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)
            val usageRepo = ExposedEmojiUsageRankingRepository(database = db, clock = clock)
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = meaningRepo, usageRepository = usageRepo)

            var decisionScore = 0.90
            val capturedInteractionBodies = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    if (url.contains("/v1beta/interactions")) {
                        capturedInteractionBodies += body
                        val text =
                            if (decisionScore >= 0.70) {
                                "哈哈 <:z_hot_55:5055>"
                            } else {
                                "<:z_hot_55:5055>"
                            }
                        respond(
                            content = """{"id":"int-1","status":"completed","finish_reason":"STOP","output_text":"$text"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content =
                                """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":$decisionScore,"confidence":$decisionScore}}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })
            val filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
            val guildId = Snowflake(500L)
            val selfId = Snowflake(999999L)
            val channelId = Snowflake(200L)

            val guildEmojiMocks =
                (1..55).map { idx ->
                    val name =
                        if (idx == 55) "z_hot_55" else "a_emoji_${idx.toString().padStart(2, '0')}"
                    mockk<GuildEmoji>(relaxed = true).apply {
                        every { id } returns Snowflake(5000L + idx)
                        every { this@apply.name } returns name
                        every { isAvailable } returns true
                        every { isAnimated } returns false
                    }
                }
            val guildMock =
                mockk<Guild>(relaxed = true).apply {
                    every { id } returns guildId
                    every { emojis } returns flowOf(*guildEmojiMocks.toTypedArray())
                }
            val kordMock =
                mockk<Kord>(relaxed = true).apply {
                    every { this@apply.selfId } returns selfId
                }
            val authorMock =
                mockk<User>(relaxed = true).apply {
                    every { id } returns Snowflake(101L)
                    every { isBot } returns false
                    every { username } returns "Alice"
                }

            fun buildGuildMessage(
                msgId: Long,
                content: String,
            ): Message =
                ChatterAiTraceTestSupport
                    .createMockMessage(
                        id = msgId,
                        channelId = channelId.value.toLong(),
                        content = content,
                    ).apply {
                        every { data.guildId } returns OptionalSnowflake.Value(guildId)
                        coEvery { getGuildOrNull() } returns guildMock
                    }

            val deliveredReplies = mutableListOf<String>()
            val deliveredReactions = mutableListOf<ReactionEmoji>()

            val service =
                ChatterService(
                    filter = filter,
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                            httpClient = createClient(mockEngine),
                        ),
                    chatterGenerator =
                        ChatterGenerator(
                            config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.40, emojiVisionModel = null),
                            httpClient = createClient(mockEngine),
                        ),
                    cooldownCache = cooldownCache,
                    historyFetcher = { _, _ -> emptyList() },
                    afterMessagesFetcher = { _, _ -> emptyList() },
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    messageSender = { _, content ->
                        deliveredReplies += content
                        mockk(relaxed = true)
                    },
                    reactionSender = { _, emoji ->
                        deliveredReactions += emoji
                    },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCatalog = catalog,
                )

            // 1. Reaction-add event boosts z_hot_55 (index 55, which would be dropped by legacy .take(50))
            val reactionEvent =
                mockk<ReactionAddEvent>(relaxed = true).apply {
                    every { this@apply.guildId } returns guildId
                    every { userId } returns Snowflake(101L)
                    every { kord } returns kordMock
                    every { emoji } returns ReactionEmoji.Custom(Snowflake(5055L), "z_hot_55", false)
                }
            service.onReactionAdd(reactionEvent)

            // 2. Reply turn: incoming message also uses z_hot_55; prompt must include z_hot_55 and drop a_emoji_54
            val msg1 = buildGuildMessage(1L, "太扯了 <:z_hot_55:5055>")
            assertTrue(service.processMessage(msg1), "Expected reply turn to succeed")
            val replyPrompt = extractInteractionUserPrompt(capturedInteractionBodies.last())
            assertContains(replyPrompt, "<:z_hot_55:5055>")
            assertFalse(replyPrompt.contains("<:a_emoji_54:5054>"), "Expected 54th emoji to be outside top-50 pool")
            assertEquals(listOf("哈哈 <:z_hot_55:5055>"), deliveredReplies)

            // 3. Silent reaction turn: top-50 pool must also rank z_hot_55 ahead of unused emojis
            cooldownCache.clear()
            decisionScore = 0.55
            val msg2 = buildGuildMessage(2L, "大家早安")
            assertTrue(service.processMessage(msg2), "Expected silent reaction turn to succeed")
            val reactionBody = capturedInteractionBodies.last()
            assertContains(reactionBody, "<:z_hot_55:5055>")
            assertFalse(reactionBody.contains("<:a_emoji_54:5054>"), "Expected 54th emoji to be outside reaction top-50 pool")
            assertEquals(1, deliveredReactions.size)
        }

    @Test
    fun `ChatterService degrades gracefully with warning log when usage repository throws during recording or ranking`() =
        runTest {
            val db = openDatabase("degraded-ranking.db")
            val meaningRepo = ExposedCustomEmojiMeaningRepository(database = db)
            val failingUsageRepo =
                object : EmojiUsageRankingRepository {
                    override fun recordUsage(
                        guildId: String,
                        emojiIds: Collection<String>,
                    ): Unit = throw IllegalStateException("simulated sqlite write lock")

                    override fun findRollingUsageCounts(
                        guildId: String,
                        windowDays: Int,
                    ): Map<String, Long> = throw IllegalStateException("simulated sqlite read failure")

                    override fun pruneExpired(windowDays: Int): Int = 0
                }
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = meaningRepo, usageRepository = failingUsageRepo)

            val logger = LoggerFactory.getLogger(DefaultChatterEmojiCatalog::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            try {
                val mockEngine =
                    MockEngine { request ->
                        if (request.url.toString().contains("/v1beta/interactions")) {
                            respond(
                                content = """{"id":"int-degraded","status":"completed","finish_reason":"STOP","output_text":"沒問題 <:ok_emoji:3001>"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond(
                                content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }

                val cooldownCache = BoundedChannelCooldownCache(timeSource = { 100_000L })
                val filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
                val guildId = Snowflake(500L)
                val guildEmoji =
                    mockk<GuildEmoji>(relaxed = true).apply {
                        every { id } returns Snowflake(3001L)
                        every { name } returns "ok_emoji"
                        every { isAvailable } returns true
                        every { isAnimated } returns false
                    }
                val guildMock =
                    mockk<Guild>(relaxed = true).apply {
                        every { id } returns guildId
                        every { emojis } returns flowOf(guildEmoji)
                    }
                val msg =
                    ChatterAiTraceTestSupport
                        .createMockMessage(
                            id = 10L,
                            channelId = 200L,
                            content = "測試 <:ok_emoji:3001>",
                        ).apply {
                            every { data.guildId } returns OptionalSnowflake.Value(guildId)
                            coEvery { getGuildOrNull() } returns guildMock
                        }

                val sentReplies = mutableListOf<String>()
                val service =
                    ChatterService(
                        filter = filter,
                        decisionClient =
                            GeminiDecisionClient(
                                config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                                httpClient = createClient(mockEngine),
                            ),
                        chatterGenerator =
                            ChatterGenerator(
                                config = ChatterConfig(apiKey = "secret-key", emojiVisionModel = null),
                                httpClient = createClient(mockEngine),
                            ),
                        cooldownCache = cooldownCache,
                        historyFetcher = { _, _ -> emptyList() },
                        afterMessagesFetcher = { _, _ -> emptyList() },
                        contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                        burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                        randomProvider = { _, _ -> 0L },
                        messageSender = { _, content ->
                            sentReplies += content
                            mockk(relaxed = true)
                        },
                        personaStateProvider = fixedPersonaProvider(),
                        emojiCatalog = catalog,
                    )

                assertTrue(service.processMessage(msg), "Reply pipeline must succeed even when usage storage throws")
                assertEquals(listOf("沒問題 <:ok_emoji:3001>"), sentReplies)

                val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnings.any { it.contains("simulated sqlite write lock") },
                    "Expected warning log for usage recording failure, got: $warnings",
                )
                assertTrue(
                    warnings.any { it.contains("simulated sqlite read failure") },
                    "Expected warning log for usage ranking query failure, got: $warnings",
                )
            } finally {
                logger.detachAppender(appender)
            }
        }

    @Test
    fun `ChatterPlugin initializes usage ranking repository on emoji catalog`() =
        runTest {
            val db = openDatabase("plugin-ranking.db")
            val plugin = ChatterPlugin()
            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = db,
                    env =
                        luna.config.EnvReader.of(
                            mapOf(
                                "GEMINI_API_KEY" to "test-key",
                                "CHATTER_ENABLED" to "false",
                            ),
                        ),
                )

            plugin.initialize(context)
            try {
                val catalog = plugin.emojiCatalog as? DefaultChatterEmojiCatalog
                assertTrue(catalog != null, "Expected ChatterPlugin to initialize DefaultChatterEmojiCatalog")

                val guildId = Snowflake(777L)
                catalog.recordMessageUsage(guildId, "<:ranked:9901>")
                val pool =
                    catalog.prepareGuildEmojiPool(
                        guildId = guildId,
                        availableGuildEmojis =
                            listOf(
                                GuildCustomEmoji(Snowflake(1001L), "a_unused", false),
                                GuildCustomEmoji(Snowflake(9901L), "ranked", false),
                            ),
                        limit = 1,
                    )
                assertEquals(listOf("9901"), pool.map { it.id.toString() })
            } finally {
                plugin.shutdown()
            }
        }
}
