package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.Kord
import dev.kord.core.entity.GuildEmoji
import dev.kord.core.entity.ReactionEmoji
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.Routing
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.emoji.BoundedChannelEmojiCadenceTracker
import luna.chatter.emoji.DefaultChatterEmojiCatalog
import luna.chatter.emoji.ExposedCustomEmojiMeaningRepository
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceOutcome
import luna.core.ai.JsonlAiTraceRecorder
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterEmojiCadenceAndReactionPromptTest {
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

    private fun fixedPersonaProvider(): PersonaStateProvider =
        object : PersonaStateProvider {
            override fun currentState(baseThreshold: Double): PersonaState =
                PersonaState(
                    driftEnabled = false,
                    timezone = "Asia/Taipei",
                    localTime = ZonedDateTime.of(2026, 11, 20, 13, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                    formattedWeekday = "星期五",
                    formattedTime = "13:00",
                    circadianPeriod = null,
                    mood = null,
                    baseThreshold = baseThreshold,
                    effectiveThreshold = baseThreshold,
                )
        }

    private fun openDatabase(name: String): Database =
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve(name).toFile().absolutePath}",
            driver = "org.sqlite.JDBC",
        )

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
    fun `ChatterPromptBuilder enforces pure text default and reaction prompt avoids eyes anchor while prioritizing fitting guild emojis`() {
        val emojis =
            (1..20).map { idx ->
                GuildCustomEmoji(
                    id = Snowflake(2000L + idx),
                    name = "guild_$idx",
                    isAnimated = false,
                    meaning = "語意$idx",
                )
            }

        val systemInst = ChatterPromptBuilder.buildSystemInstruction()
        assertContains(systemInst, "中性閒聊維持純文字回覆")
        assertContains(systemInst, "Luna 專屬表情")
        assertContains(systemInst, "單獨換行放表情")

        val replyPrompt = ChatterPromptBuilder.buildUserPrompt(ChatterInput(transcript = "[Alice]: 嗨", guildEmojis = emojis))
        assertContains(replyPrompt, "中性閒聊維持純文字")
        assertContains(replyPrompt, "<:guild_10:2010>：語意10")
        assertFalse(replyPrompt.contains("guild_11"), "Reply prompt must cap guild emoji pool at 10")

        val reactionWithoutHistory = ChatterPromptBuilder.buildReactionSystemInstruction(guildEmojis = emojis)
        assertContains(reactionWithoutHistory, "Reaction Emoji")
        assertContains(reactionWithoutHistory, "Luna 專屬表情")
        assertContains(reactionWithoutHistory, "伺服器表情")
        assertContains(reactionWithoutHistory, "NONE")
        assertFalse(reactionWithoutHistory.contains("👀"), "Reaction prompt must not anchor on eyes emoji")
        assertFalse(reactionWithoutHistory.contains("👍、😂、👀、❤️、🔥"), "Reaction prompt must not embed hardcoded example list")
        assertFalse(reactionWithoutHistory.contains("近期在這個頻道已使用過的 Reaction"), "Empty recentReactions must omit history section")

        val reactionWithHistory =
            ChatterPromptBuilder.buildReactionInteractionRequest(
                ChatterInput(
                    transcript = "[Alice]: 笑死",
                    guildEmojis = emojis,
                    recentReactions = listOf("🔥", "<:guild_1:2001>"),
                ),
            )
        val reactionSys = assertNotNull(reactionWithHistory.systemInstruction)
        assertContains(reactionSys, "近期在這個頻道已使用過的 Reaction")
        assertContains(reactionSys, "🔥 <:guild_1:2001>")

        val reactionUser = ChatterPromptBuilder.buildReactionUserPrompt(ChatterInput(transcript = "[Alice]: 笑死"))
        assertContains(reactionUser, "Discord Reaction Emoji")
        assertContains(reactionUser, "NONE")
    }

    @Test
    fun `ChatterReactionEmojiValidator and selectReactionEmoji treat NONE as intentional silent skip without warn`() =
        runTest {
            val guildEmojis = listOf(GuildCustomEmoji(Snowflake(1001L), "cat_ok"))
            assertTrue(ChatterReactionEmojiValidator.isExplicitNoReaction("NONE"), "Exact NONE must be recognized")
            assertTrue(ChatterReactionEmojiValidator.isExplicitNoReaction("  none \n"), "Case-insensitive trimmed none must be recognized")
            assertFalse(ChatterReactionEmojiValidator.isExplicitNoReaction("🔥"), "Emoji must not match NONE sentinel")
            assertFalse(ChatterReactionEmojiValidator.isExplicitNoReaction("NONE please"), "Extra words must not match NONE sentinel")
            assertNull(ChatterReactionEmojiValidator.validateAndNormalize("NONE", guildEmojis))

            val generatorLogger = LoggerFactory.getLogger(ChatterGenerator::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            generatorLogger.addAppender(appender)

            try {
                val traceDir = tempDir.resolve("none-trace")
                val recorder = JsonlAiTraceRecorder(traceDir = traceDir, clock = ChatterAiTraceTestSupport.fixedClock)
                val mockEngine =
                    MockEngine {
                        respond(
                            content = """{"id":"int-none","status":"completed","finish_reason":"STOP","output_text":"  NONE "}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val generator =
                    ChatterGenerator(
                        config = ChatterConfig(apiKey = "secret-key"),
                        httpClient = createClient(mockEngine),
                        traceRecorder = recorder,
                    )

                val selected = generator.selectReactionEmoji(ChatterInput(transcript = "[Alice]: 嗯嗯", guildEmojis = guildEmojis))
                recorder.flushAndClose()

                assertNull(selected)
                val trace = ChatterAiTraceTestSupport.readTraceLines(traceDir).single()
                assertEquals(AiTraceOutcome.SKIPPED, trace.outcome)
                assertNull(trace.failureCategory)
                assertTrue(trace.errorSummary.orEmpty().contains("NONE"), "Expected NONE reason in AI Trace summary")

                val warnLogs = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(warnLogs.isEmpty(), "Intentional NONE skip must not emit WARN logs, got: $warnLogs")
            } finally {
                generatorLogger.detachAppender(appender)
            }
        }

    @Test
    fun `ChatterService reply emoji cooldown omits guild pool for two turns while preserving transcript meaning annotation`() =
        runTest {
            val db = openDatabase("reply-cadence.db")
            val meaningRepo =
                ExposedCustomEmojiMeaningRepository(
                    database = db,
                    clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC),
                )
            meaningRepo.upsertResolvedMeaning("1001", "guild_happy", isAnimated = false, meaning = "開心慶祝")

            val now = AtomicLong(100_000L)
            val replyBodies = mutableListOf<String>()
            var llmOutput = "太神了吧 <:guild_happy:1001>"

            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    if (url.contains("/v1beta/interactions")) {
                        replyBodies += body
                        respond(
                            content = """{"id":"int-reply","status":"completed","finish_reason":"STOP","output_text":"$llmOutput"}""",
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

            val cooldownCache = BoundedChannelCooldownCache(defaultCooldownDurationMs = 5_000L, timeSource = { now.get() })
            val filter = ChatterNoiseFilter(cooldownCache = cooldownCache, cooldownDurationMs = 5_000L)
            val cadenceTracker = BoundedChannelEmojiCadenceTracker(replyCooldownTurns = 2, timeSource = { now.get() })
            val guildEmoji =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(1001L)
                    every { name } returns "guild_happy"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val channelPort =
                FakeChatterChannelPort().apply {
                    guildEmojis = listOf(guildEmoji)
                }
            val service =
                ChatterService(
                    filter = filter,
                    decisionClient = GeminiDecisionClient(ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70), createClient(mockEngine)),
                    chatterGenerator = ChatterGenerator(ChatterConfig(apiKey = "secret-key"), createClient(mockEngine)),
                    channelPort = channelPort,
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCatalog = DefaultChatterEmojiCatalog(meaningRepository = meaningRepo),
                    emojiCadenceTracker = cadenceTracker,
                )

            fun guildMsg(
                id: Long,
                content: String,
            ) = ChatterAiTraceTestSupport.createMockMessage(id = id, channelId = 301L, content = content).apply {
                every { data.guildId } returns OptionalSnowflake.Value(Snowflake(777L))
            }

            // Turn 1: guild pool injected and custom emoji allowed
            assertTrue(service.processMessage(guildMsg(1L, "終於過關 <:guild_happy:1001>")), "Turn 1 must succeed")
            assertEquals("太神了吧 <:guild_happy:1001>", channelPort.allDeliveredTexts[0])
            assertContains(extractInteractionPromptText(replyBodies[0]), "<:guild_happy:1001>：開心慶祝")

            // Turn 2 (cooldown turn 1): pool omitted, imitated emoji stripped, transcript meaning still annotated
            now.addAndGet(10_000L)
            llmOutput = "笑死 <:guild_happy:1001>"
            assertTrue(service.processMessage(guildMsg(2L, "再來一局 <:guild_happy:1001>")), "Turn 2 must succeed")
            assertEquals("笑死", channelPort.allDeliveredTexts[1])
            val prompt2 = extractInteractionPromptText(replyBodies[1])
            assertFalse(prompt2.contains("伺服器表情（"), "Turn 2 must omit guild emoji pool during cooldown")
            assertFalse(prompt2.contains("Luna 專屬表情（"), "Turn 2 must omit application emoji pool during cooldown")
            assertContains(prompt2, "<:guild_happy:1001>(開心慶祝)")

            // Turn 3 (cooldown turn 2): pool still omitted, pure text reply decrements remaining cooldown to 0
            now.addAndGet(10_000L)
            llmOutput = "穩了啦"
            assertTrue(service.processMessage(guildMsg(3L, "衝啊")), "Turn 3 must succeed")
            assertEquals("穩了啦", channelPort.allDeliveredTexts[2])
            assertFalse(extractInteractionPromptText(replyBodies[2]).contains("伺服器表情（"), "Turn 3 must still omit guild emoji pool")

            // Turn 4: cooldown cleared -> guild pool re-enabled
            now.addAndGet(10_000L)
            llmOutput = "讚啦 <:guild_happy:1001>"
            assertTrue(service.processMessage(guildMsg(4L, "真的過了")), "Turn 4 must succeed")
            assertEquals("讚啦 <:guild_happy:1001>", channelPort.allDeliveredTexts[3])
            assertContains(extractInteractionPromptText(replyBodies[3]), "<:guild_happy:1001>：開心慶祝")
        }

    @Test
    fun `ChatterService silent reaction enforces cooldown, injects recent reactions, and skips NONE without starting cooldown`() =
        runTest {
            val now = AtomicLong(200_000L)
            val traceDir = tempDir.resolve("reaction-cadence-traces")
            val recorder = JsonlAiTraceRecorder(traceDir = traceDir, clock = ChatterAiTraceTestSupport.fixedClock)

            val reactionBodies = mutableListOf<String>()
            var nextReactionOutput = "🔥"

            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    if (url.contains("/v1beta/interactions")) {
                        reactionBodies += body
                        respond(
                            content = """{"id":"int-react","status":"completed","finish_reason":"STOP","output_text":"$nextReactionOutput"}""",
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

            val cooldownCache = BoundedChannelCooldownCache(timeSource = { now.get() })
            val filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
            val cadenceTracker = BoundedChannelEmojiCadenceTracker(reactionCooldownMs = 30_000L, timeSource = { now.get() })
            val channelPort = FakeChatterChannelPort()

            val service =
                ChatterService(
                    filter = filter,
                    decisionClient =
                        GeminiDecisionClient(
                            config = ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70),
                            httpClient = createClient(mockEngine),
                            traceRecorder = recorder,
                        ),
                    chatterGenerator =
                        ChatterGenerator(
                            config = ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45),
                            httpClient = createClient(mockEngine),
                            traceRecorder = recorder,
                        ),
                    channelPort = channelPort,
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    traceRecorder = recorder,
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCadenceTracker = cadenceTracker,
                )

            // Turn 1: delivers 🔥 reaction without starting text reply cooldown
            assertTrue(
                service.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 11L, channelId = 401L, content = "買新顯卡了")),
                "Turn 1 reaction should succeed",
            )
            assertEquals(listOf<ReactionEmoji>(ReactionEmoji.Unicode("🔥")), channelPort.addedReactions.map { it.emoji })
            assertFalse(cooldownCache.isOnCooldown(Snowflake(401L)), "Silent reaction must not start text cooldownCache")
            assertFalse(extractInteractionSystemInstruction(reactionBodies[0]).contains("近期在這個頻道已使用過的 Reaction"), "Turn 1 has no prior reaction history")

            // Turn 2 within 30s reaction cooldown: skips REACTION_SELECT HTTP call and emits SKIPPED trace
            now.addAndGet(10_000L)
            assertFalse(
                service.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 12L, channelId = 401L, content = "好猛")),
                "Turn 2 should skip on reaction cooldown",
            )
            assertEquals(1, reactionBodies.size, "Reaction cooldown must avoid calling REACTION_SELECT")

            // Turn 3 after 30s: passes 🔥 in recentReactions, model declines with NONE -> skips cleanly without starting cooldown
            now.addAndGet(25_000L)
            nextReactionOutput = "NONE"
            assertFalse(
                service.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 13L, channelId = 401L, content = "喔喔")),
                "Turn 3 NONE should return false",
            )
            assertEquals(2, reactionBodies.size)
            assertContains(extractInteractionSystemInstruction(reactionBodies[1]), "近期在這個頻道已使用過的 Reaction")
            assertContains(extractInteractionSystemInstruction(reactionBodies[1]), "🔥")
            assertEquals(1, channelPort.addedReactions.size, "NONE must not add any Discord reaction")
            assertFalse(cadenceTracker.isReactionOnCooldown(Snowflake(401L)), "Declined NONE reaction must not trigger reaction cooldown")

            // Turn 4 immediately after NONE: not on cooldown, only 🔥 remains in recentReactions
            nextReactionOutput = "👍"
            assertTrue(
                service.processMessage(ChatterAiTraceTestSupport.createMockMessage(id = 14L, channelId = 401L, content = "準備測試")),
                "Turn 4 after NONE should not be blocked by cooldown",
            )
            assertEquals(
                listOf<ReactionEmoji>(ReactionEmoji.Unicode("🔥"), ReactionEmoji.Unicode("👍")),
                channelPort.addedReactions.map { it.emoji },
            )
            assertEquals(listOf("🔥", "👍"), cadenceTracker.recentReactions(Snowflake(401L)))

            recorder.flushAndClose()
            val traces = ChatterAiTraceTestSupport.readTraceLines(traceDir)
            assertEquals(4, traces.size)
            assertEquals(AiTraceOutcome.SUCCESS, traces[0].outcome)
            assertEquals(AiTraceOutcome.SKIPPED, traces[1].outcome)
            assertTrue(traces[1].attempts.none { it.stage == AiAttemptStage.REACTION_SELECT }, "Cooldown skip must not record REACTION_SELECT attempt")
            assertEquals(AiTraceOutcome.SKIPPED, traces[2].outcome)
            assertNull(traces[2].failureCategory)
            assertEquals(AiTraceOutcome.SUCCESS, traces[3].outcome)
        }

    @Test
    fun `failed reply or reaction delivery does not start cooldown or pollute recent reactions`() =
        runTest {
            val now = AtomicLong(300_000L)
            val mockEngine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body = request.body.toByteArray().decodeToString()
                    when {
                        url.contains("/v1beta/interactions") && body.contains("Reaction Emoji") -> {
                            respond(
                                content = """{"id":"int-react-ok","status":"completed","finish_reason":"STOP","output_text":"🔥"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        url.contains("/v1beta/interactions") -> {
                            respond(
                                content = """{"id":"int-reply-ok","status":"completed","finish_reason":"STOP","output_text":"衝啊 <:ok:1001>"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            respond(
                                content = """{"model":"gemini-decision-1","answers":{"should_participate":{"type":"noul","noul":0.95,"confidence":0.95}}}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }

            val cooldownCache = BoundedChannelCooldownCache(timeSource = { now.get() })
            val filter = ChatterNoiseFilter(cooldownCache = cooldownCache)
            val cadenceTracker = BoundedChannelEmojiCadenceTracker(timeSource = { now.get() })

            val okGuildEmoji =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(1001L)
                    every { name } returns "ok"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val replyFailPort =
                FakeChatterChannelPort().apply {
                    guildEmojis = listOf(okGuildEmoji)
                    sendError = RuntimeException("Discord send failed")
                    reactionError = RuntimeException("Missing Add Reactions permission")
                }
            val failingService =
                ChatterService(
                    filter = filter,
                    decisionClient = GeminiDecisionClient(ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70), createClient(mockEngine)),
                    chatterGenerator = ChatterGenerator(ChatterConfig(apiKey = "secret-key"), createClient(mockEngine)),
                    channelPort = replyFailPort,
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCadenceTracker = cadenceTracker,
                )

            val replyMsg = ChatterAiTraceTestSupport.createMockMessage(id = 21L, channelId = 501L, content = "失敗測試")
            assertFalse(failingService.processMessage(replyMsg), "Failed sendMessage must return false")
            assertTrue(cadenceTracker.shouldAllowReplyCustomEmojis(Snowflake(501L)), "Failed reply delivery must not trigger reply emoji cooldown")

            // Reaction path failure via addReaction exception
            val reactionFailingEngine =
                MockEngine { request ->
                    if (request.url.toString().contains("/v1beta/interactions")) {
                        respond(
                            content = """{"id":"int-react-ok","status":"completed","finish_reason":"STOP","output_text":"🔥"}""",
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
            val reactionFailPort =
                FakeChatterChannelPort().apply {
                    reactionError = RuntimeException("Missing Add Reactions permission")
                }
            val reactionFailingService =
                ChatterService(
                    filter = filter,
                    decisionClient = GeminiDecisionClient(ChatterDecisionConfig(apiKey = "secret-key", threshold = 0.70), createClient(reactionFailingEngine)),
                    chatterGenerator = ChatterGenerator(ChatterConfig(apiKey = "secret-key", reactionThreshold = 0.45), createClient(reactionFailingEngine)),
                    channelPort = reactionFailPort,
                    contextCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null }),
                    burstCoordinator = BoundedChannelBurstCoordinator(debounceMs = 1L),
                    randomProvider = { _, _ -> 0L },
                    personaStateProvider = fixedPersonaProvider(),
                    emojiCadenceTracker = cadenceTracker,
                )

            val reactMsg = ChatterAiTraceTestSupport.createMockMessage(id = 22L, channelId = 502L, content = "失敗表態測試")
            assertFalse(reactionFailingService.processMessage(reactMsg), "Failed addReaction must return false")
            assertFalse(cadenceTracker.isReactionOnCooldown(Snowflake(502L)), "Failed reaction delivery must not trigger reaction cooldown")
            assertEquals(emptyList(), cadenceTracker.recentReactions(Snowflake(502L)))
        }

    @Test
    fun `ChatterPlugin wires shared BoundedChannelEmojiCadenceTracker into ChatterService`() =
        runTest {
            val context =
                DefaultPluginContext(
                    kord = mockk<Kord>(relaxed = true),
                    routing = mockk<Routing>(relaxed = true),
                    database = mockk<Database>(relaxed = true),
                    env =
                        luna.config.EnvReader.of(
                            "GEMINI_API_KEY" to "plugin-key",
                            "CHATTER_ENABLED" to "false",
                        ),
                )
            val plugin = ChatterPlugin()
            plugin.initialize(context)
            try {
                val tracker = assertNotNull(plugin.emojiCadenceTracker)
                assertTrue(
                    tracker === plugin.service?.emojiCadenceTracker,
                    "ChatterPlugin must wire the same BoundedChannelEmojiCadenceTracker instance into ChatterService",
                )
            } finally {
                plugin.shutdown()
            }
        }
}
