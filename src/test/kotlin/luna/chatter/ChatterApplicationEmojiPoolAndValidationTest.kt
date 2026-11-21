package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.ReactionEmoji
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.emoji.ApplicationEmojiCache
import luna.chatter.emoji.BoundedChannelEmojiCadenceTracker
import luna.chatter.emoji.ChatterEmojiCatalog
import luna.chatter.emoji.CustomEmojiVisionResolver
import luna.chatter.emoji.DefaultChatterEmojiCatalog
import luna.chatter.emoji.EmojiVisionRequest
import luna.chatter.emoji.EmojiVisionTurnBudget
import luna.chatter.emoji.ExposedCustomEmojiMeaningRepository
import luna.chatter.emoji.ExposedEmojiUsageRankingRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterApplicationEmojiPoolAndValidationTest {
    @TempDir
    lateinit var tempDir: Path

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

    private fun openDatabase(name: String): Database =
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve(name).toFile().absolutePath}",
            driver = "org.sqlite.JDBC",
        )

    private fun fixedClock(isoInstant: String = "2026-11-21T10:00:00Z"): Clock = Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC)

    private fun fixedPersonaProvider(): PersonaStateProvider =
        object : PersonaStateProvider {
            override fun currentState(baseThreshold: Double): PersonaState =
                PersonaState(
                    driftEnabled = false,
                    timezone = "Asia/Taipei",
                    localTime = ZonedDateTime.of(2026, 11, 21, 14, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                    formattedWeekday = "星期六",
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
    fun `ChatterPromptBuilder renders partitioned Application and Guild emoji pools with 15+10 reply and 30+20 reaction caps`() {
        val appEmojis =
            (1..35).map { idx ->
                GuildCustomEmoji(
                    id = Snowflake(10_000L + idx),
                    name = "luna_app_$idx",
                    isAnimated = idx % 2 == 0,
                    meaning = "專屬語意$idx",
                )
            }
        val guildEmojis =
            (1..25).map { idx ->
                GuildCustomEmoji(
                    id = Snowflake(20_000L + idx),
                    name = "guild_e_$idx",
                    isAnimated = idx % 2 == 0,
                    meaning = "群組語意$idx",
                )
            }

        val replyPrompt =
            ChatterPromptBuilder.buildUserPrompt(
                ChatterInput(
                    transcript = "[Alice]: 笑死",
                    guildEmojis = guildEmojis,
                    applicationEmojis = appEmojis,
                ),
            )
        val appHeaderIndex = replyPrompt.indexOf("Luna 專屬表情（優先選用")
        val guildHeaderIndex = replyPrompt.indexOf("伺服器表情（僅在專屬表情不合適時備選")
        assertTrue(appHeaderIndex >= 0, "Reply prompt must render Luna 專屬表情 section")
        assertTrue(guildHeaderIndex > appHeaderIndex, "Reply prompt must render 伺服器表情 section after Luna 專屬表情")
        assertContains(replyPrompt, "<:luna_app_15:10015>：專屬語意15")
        assertFalse(replyPrompt.contains("luna_app_16"), "Reply prompt must cap Application Emojis at 15")
        assertContains(replyPrompt, "<a:guild_e_10:20010>：群組語意10")
        assertFalse(replyPrompt.contains("guild_e_11"), "Reply prompt must cap Guild Emojis at 10")

        val reactionSys =
            ChatterPromptBuilder.buildReactionSystemInstruction(
                guildEmojis = guildEmojis,
                applicationEmojis = appEmojis,
            )
        val reactAppHeaderIdx = reactionSys.indexOf("Luna 專屬表情（優先選用")
        val reactGuildHeaderIdx = reactionSys.indexOf("伺服器表情（僅在專屬表情不合適時備選")
        assertTrue(reactAppHeaderIdx >= 0, "Reaction system instruction must render Luna 專屬表情 section")
        assertTrue(reactGuildHeaderIdx > reactAppHeaderIdx, "Reaction system instruction must render 伺服器表情 after Luna 專屬表情")
        assertContains(reactionSys, "<a:luna_app_30:10030>：專屬語意30")
        assertFalse(reactionSys.contains("luna_app_31"), "Reaction prompt must cap Application Emojis at 30")
        assertContains(reactionSys, "<a:guild_e_20:20020>：群組語意20")
        assertFalse(reactionSys.contains("guild_e_21"), "Reaction prompt must cap Guild Emojis at 20")
    }

    @Test
    fun `sanitizeResponse and ChatterReactionEmojiValidator allow Application union Guild emojis and reject hallucinated or Unicode in replies`() {
        val appEmojis =
            listOf(
                GuildCustomEmoji(Snowflake(100_001L), "luna_smug", isAnimated = false),
                GuildCustomEmoji(Snowflake(100_002L), "luna_wave", isAnimated = true),
            )
        val guildEmojis =
            listOf(
                GuildCustomEmoji(Snowflake(200_001L), "guild_clap", isAnimated = false),
            )

        // Reply sanitizer allows app ∪ guild, strips Unicode and hallucinated custom IDs
        val rawReply = "太會了吧 😂 <:luna_smug:100001> <a:luna_wave:100002> <:guild_clap:200001> <:fake:999999>"
        val sanitized =
            ChatterPromptBuilder.sanitizeResponse(
                rawText = rawReply,
                guildEmojis = guildEmojis,
                applicationEmojis = appEmojis,
            )
        assertEquals("太會了吧  <:luna_smug:100001> <a:luna_wave:100002> <:guild_clap:200001>", sanitized)

        // Reaction validator accepts app emojis, guild emojis, and single Unicode; rejects wrong name / hallucinated ID
        assertEquals(
            "<:luna_smug:100001>",
            ChatterReactionEmojiValidator.validateAndNormalize("<:luna_smug:100001>", guildEmojis, appEmojis),
        )
        assertEquals(
            "<a:luna_wave:100002>",
            ChatterReactionEmojiValidator.validateAndNormalize("<:luna_wave:100002>", guildEmojis, appEmojis),
        )
        assertEquals(
            "<:guild_clap:200001>",
            ChatterReactionEmojiValidator.validateAndNormalize("<:guild_clap:200001>", guildEmojis, appEmojis),
        )
        assertEquals("🔥", ChatterReactionEmojiValidator.validateAndNormalize("🔥", guildEmojis, appEmojis))
        assertNull(ChatterReactionEmojiValidator.validateAndNormalize("<:wrong_name:100001>", guildEmojis, appEmojis))
        assertNull(ChatterReactionEmojiValidator.validateAndNormalize("<:hallucinated:999999>", guildEmojis, appEmojis))

        assertEquals(
            ReactionEmoji.Custom(Snowflake(100_002L), "luna_wave", isAnimated = true),
            ChatterReactionEmojiValidator.toReactionEmoji("<a:luna_wave:100002>", guildEmojis, appEmojis),
        )
        assertNull(ChatterReactionEmojiValidator.toReactionEmoji("<:hallucinated:999999>", guildEmojis, appEmojis))
    }

    @Test
    fun `catalog ranks Application Emojis under __app__ scope across guilds and DMs with deterministic name-then-id backfill`() =
        runTest {
            val db = openDatabase("app-emoji-ranking.db")
            val clock = fixedClock()
            val meaningRepo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)
            val usageRepo = ExposedEmojiUsageRankingRepository(database = db, clock = clock)
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = meaningRepo, usageRepository = usageRepo, clock = clock)

            val appEmojis =
                listOf(
                    GuildCustomEmoji(Snowflake(9001L), "z_hot_app"),
                    GuildCustomEmoji(Snowflake(9002L), "m_mid_app"),
                    GuildCustomEmoji(Snowflake(8002L), "a_tied_app"),
                    GuildCustomEmoji(Snowflake(8001L), "a_tied_app"),
                ) +
                    (1..20).map { idx ->
                        GuildCustomEmoji(
                            id = Snowflake(2000L + idx),
                            name = "unused_${idx.toString().padStart(2, '0')}",
                        )
                    }
            val knownAppIds = appEmojis.map { it.id.toString() }.toSet()

            // Observed DM message (guildId = null) + guild message + reaction + cross-guild reaction
            catalog.recordMessageUsage(
                guildId = null,
                content = "<:z_hot_app:9001> <:unknown_ext:77777>",
                knownApplicationEmojiIds = knownAppIds,
            )
            catalog.recordMessageUsage(
                guildId = Snowflake(100L),
                content = "<:z_hot_app:9001> <:m_mid_app:9002>",
                knownApplicationEmojiIds = knownAppIds,
            )
            catalog.recordReactionUsage(
                guildId = null,
                emoji = ReactionEmoji.Custom(Snowflake(9001L), "z_hot_app", false),
                knownApplicationEmojiIds = knownAppIds,
            )
            catalog.recordReactionUsage(
                guildId = Snowflake(200L),
                emoji = ReactionEmoji.Custom(Snowflake(8002L), "a_tied_app", false),
                knownApplicationEmojiIds = knownAppIds,
            )
            catalog.recordReactionUsage(
                guildId = Snowflake(200L),
                emoji = ReactionEmoji.Custom(Snowflake(8001L), "a_tied_app", false),
                knownApplicationEmojiIds = knownAppIds,
            )

            val prepared =
                catalog.preparePromptEmojis(
                    transcript = "[Alice]: 嗨",
                    guildId = Snowflake(100L),
                    availableGuildEmojis = emptyList(),
                    limit = 10,
                    availableApplicationEmojis = appEmojis,
                    applicationEmojiLimit = 15,
                )

            assertEquals(15, prepared.applicationEmojis.size, "Application emoji pool must be capped at requested limit 15")
            assertEquals(
                listOf("9001", "8001", "8002", "9002"),
                prepared.applicationEmojis.take(4).map { it.id.toString() },
                "Expected z_hot_app(3), tied a_tied_app(1) ordered by ID 8001 before 8002, then m_mid_app(1)",
            )
            assertEquals(
                (1..11).map { "unused_${it.toString().padStart(2, '0')}" },
                prepared.applicationEmojis.drop(4).map { it.name },
                "Remaining slots must backfill deterministically by name",
            )
            assertEquals(
                mapOf("9001" to 3L, "9002" to 1L, "8001" to 1L, "8002" to 1L),
                usageRepo.findRollingUsageCounts(ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE),
                "Usage counts under __app__ scope must include only known Application Emoji IDs across guilds and DMs",
            )
        }

    @Test
    fun `preparePromptEmojis resolves transcript, application emojis, and guild emojis in a single pass prioritizing app before guild`() =
        runTest {
            val db = openDatabase("single-pass-vision.db")
            val clock = fixedClock()
            val meaningRepo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)
            val recordedBatches = mutableListOf<List<String>>()
            val fakeResolver =
                mockk<CustomEmojiVisionResolver>().apply {
                    every { roundTimeoutMs } returns 5_000L
                    coEvery { resolveMissing(any(), any()) } answers {
                        val targets = firstArg<List<EmojiVisionRequest>>()
                        recordedBatches += targets.map { it.id }
                        targets.forEach { req ->
                            meaningRepo.upsertResolvedMeaning(req.id, req.name, req.isAnimated, "意思_${req.name}")
                        }
                    }
                }
            val catalog =
                DefaultChatterEmojiCatalog(
                    meaningRepository = meaningRepo,
                    visionResolver = fakeResolver,
                    clock = clock,
                )

            val budget = EmojiVisionTurnBudget()
            val prepared =
                catalog.preparePromptEmojis(
                    transcript = "[Alice]: 看看 <:tx_emoji:3001>",
                    guildId = Snowflake(500L),
                    availableGuildEmojis = listOf(GuildCustomEmoji(Snowflake(5001L), "guild_one")),
                    limit = 10,
                    roundBudget = budget,
                    availableApplicationEmojis = listOf(GuildCustomEmoji(Snowflake(4001L), "app_one")),
                    applicationEmojiLimit = 15,
                )

            assertEquals(1, recordedBatches.size, "Expected single-pass combined vision resolution round")
            assertEquals(
                listOf("3001", "4001", "5001"),
                recordedBatches.single(),
                "Combined vision batch must prioritize transcript -> application emojis -> guild emojis",
            )
            assertTrue(
                prepared.transcript.contains("<:tx_emoji:3001>(意思_tx_emoji)"),
                "Transcript must be annotated with resolved meaning",
            )
            assertEquals("意思_app_one", prepared.applicationEmojis.single().meaning)
            assertEquals("意思_guild_one", prepared.guildEmojis.single().meaning)
        }

    @Test
    fun `ChatterService wires Application Emojis for DM fallback, cadence cooldown omission, silent reaction, and __app__ usage recording`() =
        runTest {
            val db = openDatabase("service-app-emoji-e2e.db")
            val clock = fixedClock()
            val meaningRepo = ExposedCustomEmojiMeaningRepository(database = db, clock = clock)
            val usageRepo = ExposedEmojiUsageRankingRepository(database = db, clock = clock)
            meaningRepo.upsertResolvedMeaning("7001", "luna_smug", isAnimated = false, meaning = "得意壞笑")
            meaningRepo.upsertResolvedMeaning("8001", "guild_ok", isAnimated = false, meaning = "群組讚")
            val catalog = DefaultChatterEmojiCatalog(meaningRepository = meaningRepo, usageRepository = usageRepo, clock = clock)

            val now = AtomicLong(100_000L)
            var decisionScore = 0.95
            var nextModelOutput = "哼哼 <:luna_smug:7001> 😂 <:fake:999>"
            val capturedBodies = mutableListOf<String>()

            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    if (url.contains("/v1beta/interactions")) {
                        capturedBodies += body
                        respond(
                            content = """{"id":"int-app","status":"completed","finish_reason":"STOP","output_text":"$nextModelOutput"}""",
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

            val cooldownCache = BoundedChannelCooldownCache(defaultCooldownDurationMs = 5_000L, timeSource = { now.get() })
            val filter = ChatterNoiseFilter(cooldownCache = cooldownCache, cooldownDurationMs = 5_000L)
            val cadenceTracker = BoundedChannelEmojiCadenceTracker(replyCooldownTurns = 1, reactionCooldownMs = 5_000L, timeSource = { now.get() })
            val appEmoji = GuildCustomEmoji(Snowflake(7001L), "luna_smug", isAnimated = false)
            val guildEmojiMock =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(8001L)
                    every { name } returns "guild_ok"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val channelPort =
                FakeChatterChannelPort().apply {
                    applicationEmojis = listOf(appEmoji)
                    guildEmojis = emptyList()
                }
            val appCache = ApplicationEmojiCache(clock = clock)
            val service =
                ChatterService(
                    filter = filter,
                    decisionClient = GeminiDecisionClient(ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70), createClient(mockEngine)),
                    chatterGenerator = ChatterGenerator(ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45), createClient(mockEngine)),
                    channelPort = channelPort,
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCatalog = catalog,
                    emojiCadenceTracker = cadenceTracker,
                    applicationEmojiCache = appCache,
                )

            // 1. DM / no-guild fallback: Application Emojis still injected, guild section omitted, delivered reply preserves app emoji & records __app__ usage
            val dmMsg = ChatterAiTraceTestSupport.createMockMessage(id = 1L, channelId = 501L, guildId = null, content = "私訊測試")
            assertTrue(service.processMessage(dmMsg), "DM reply with Application Emoji should succeed")
            assertEquals("哼哼 <:luna_smug:7001>", channelPort.allDeliveredTexts[0])
            val dmPrompt = extractInteractionPromptText(capturedBodies[0])
            assertContains(dmPrompt, "Luna 專屬表情（優先選用")
            assertContains(dmPrompt, "<:luna_smug:7001>：得意壞笑")
            assertFalse(dmPrompt.contains("伺服器表情（"), "DM prompt must omit guild emoji section when no guild emojis exist")
            assertEquals(1L, usageRepo.findRollingUsageCounts(ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE)["7001"])

            // 2. Reply emoji cadence cooldown in same channel: BOTH app and guild pools omitted while transcript meaning stays annotated
            now.addAndGet(10_000L)
            channelPort.guildEmojis = listOf(guildEmojiMock)
            nextModelOutput = "冷靜一下 <:luna_smug:7001> <:guild_ok:8001>"
            val cooldownMsg =
                ChatterAiTraceTestSupport.createMockMessage(id = 2L, channelId = 501L, content = "再來 <:luna_smug:7001>").apply {
                    every { data.guildId } returns OptionalSnowflake.Value(Snowflake(900L))
                }
            assertTrue(service.processMessage(cooldownMsg), "Cooldown turn should still deliver plain-text reply")
            assertEquals("冷靜一下", channelPort.allDeliveredTexts[1])
            val cooldownPrompt = extractInteractionPromptText(capturedBodies[1])
            assertFalse(cooldownPrompt.contains("Luna 專屬表情（"), "Cooldown turn must omit Application Emoji pool")
            assertFalse(cooldownPrompt.contains("伺服器表情（"), "Cooldown turn must omit Guild Emoji pool")
            assertContains(cooldownPrompt, "<:luna_smug:7001>(得意壞笑)")
            // Incoming member message on turn 2 also recorded +1 for 7001 under __app__ (total 2)
            assertEquals(2L, usageRepo.findRollingUsageCounts(ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE)["7001"])

            // 3. Silent reaction path accepts Application Emoji and records __app__ usage on delivery
            now.addAndGet(10_000L)
            decisionScore = 0.55
            nextModelOutput = "<:luna_smug:7001>"
            val reactMsg =
                ChatterAiTraceTestSupport.createMockMessage(id = 3L, channelId = 502L, content = "好強喔").apply {
                    every { data.guildId } returns OptionalSnowflake.Value(Snowflake(900L))
                }
            assertTrue(service.processMessage(reactMsg), "Silent reaction with Application Emoji should succeed")
            assertEquals(
                listOf<ReactionEmoji>(ReactionEmoji.Custom(Snowflake(7001L), "luna_smug", isAnimated = false)),
                channelPort.addedReactions.map { it.emoji },
            )
            val reactSys = extractInteractionSystemInstruction(capturedBodies[2])
            assertContains(reactSys, "Luna 專屬表情（優先選用")
            assertContains(reactSys, "<:luna_smug:7001>：得意壞笑")
            assertContains(reactSys, "伺服器表情（僅在專屬表情不合適時備選")
            assertContains(reactSys, "<:guild_ok:8001>：群組讚")
            assertEquals(3L, usageRepo.findRollingUsageCounts(ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE)["7001"])

            // 4. Observed DM reaction (guildId = null) with known Application Emoji records __app__ usage
            val kordMock = mockk<Kord>(relaxed = true).apply { every { selfId } returns Snowflake(999_999L) }
            val dmReactionEvent =
                mockk<ReactionAddEvent>(relaxed = true).apply {
                    every { guildId } returns null
                    every { userId } returns Snowflake(12345L)
                    every { kord } returns kordMock
                    every { emoji } returns ReactionEmoji.Custom(Snowflake(7001L), "luna_smug", false)
                }
            service.onReactionAdd(dmReactionEvent)
            assertEquals(4L, usageRepo.findRollingUsageCounts(ChatterEmojiCatalog.APP_EMOJI_USAGE_SCOPE)["7001"])
        }
}
