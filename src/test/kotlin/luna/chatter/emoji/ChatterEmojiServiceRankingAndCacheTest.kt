package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.ApplicationEmoji
import dev.kord.core.entity.Guild
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.GuildCustomEmoji
import luna.core.ai.GeminiInteractionClient
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatterEmojiServiceRankingAndCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun openDatabase(name: String): Database =
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve(name).toFile().absolutePath}",
            driver = "org.sqlite.JDBC",
        )

    private fun createInteractionClient(engine: MockEngine): GeminiInteractionClient =
        GeminiInteractionClient(
            httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json(json)
                    }
                },
        )

    private class MutableClock(
        initialEpochMs: Long,
    ) : Clock() {
        private val nowMs = AtomicLong(initialEpochMs)

        fun advanceMs(deltaMs: Long) {
            nowMs.addAndGet(deltaMs)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(nowMs.get())
    }

    @Test
    fun `enforces partitioned reply and reaction prompt quotas with thirty day rolling usage ranking and backfill`() =
        runTest {
            val db = openDatabase("ranking-quotas.db")
            val dayMs = 86_400_000L
            val clock = MutableClock(1_700_000_000_000L)
            val guildId = Snowflake(500L)

            val appEmojis =
                (1..35).map { idx ->
                    GuildCustomEmoji(id = Snowflake(10_000L + idx), name = "app_%02d".format(idx), isAnimated = false)
                }
            val guildEmojis =
                (1..25).map { idx ->
                    GuildCustomEmoji(id = Snowflake(20_000L + idx), name = "guild_%02d".format(idx), isAnimated = false)
                }

            val service: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = db,
                    applicationEmojiFetcher = { appEmojis },
                    clock = clock,
                )

            // Seed cache so known Application Emoji IDs are recognized by recordMessageUsage/recordReactionUsage
            service.beginTurn(Snowflake(1L), guildId, guildEmojisProvider = { guildEmojis }).prepareReplyEmojis("init")

            // Record old usage on day 0 for app_35 and guild_25 (will expire after 30 days)
            service.recordMessageUsage(guildId, "<:app_35:10035> <:app_35:10035> <:guild_25:20025> <:guild_25:20025>")

            // Advance 31 days -> old day-0 usage expires; record fresh usage for app_34 and guild_24
            clock.advanceMs(31 * dayMs)
            service.recordMessageUsage(guildId, "<:app_34:10034> <:guild_24:20024>")
            service.recordReactionUsage(guildId, ReactionEmoji.Custom(Snowflake(10_034L), "app_34", isAnimated = false))
            service.recordReactionUsage(guildId, ReactionEmoji.Custom(Snowflake(20_024L), "guild_24", isAnimated = false))

            val turn = service.beginTurn(Snowflake(2L), guildId, guildEmojisProvider = { guildEmojis })

            // Reply quotas: 15 app + 10 guild, top-ranked first then deterministic name backfill
            val replyPrepared = turn.prepareReplyEmojis("hello")
            assertEquals(15, replyPrepared.applicationEmojis.size)
            assertEquals(10, replyPrepared.guildEmojis.size)
            assertEquals(Snowflake(10_034L), replyPrepared.applicationEmojis.first().id)
            assertEquals(Snowflake(10_001L), replyPrepared.applicationEmojis[1].id)
            assertEquals(Snowflake(20_024L), replyPrepared.guildEmojis.first().id)
            assertEquals(Snowflake(20_001L), replyPrepared.guildEmojis[1].id)
            assertTrue(
                replyPrepared.applicationEmojis.none { it.id == Snowflake(10_035L) },
                "Expired day-0 app_35 should not appear in top 15",
            )
            assertTrue(
                replyPrepared.guildEmojis.none { it.id == Snowflake(20_025L) },
                "Expired day-0 guild_25 should not appear in top 10",
            )

            // Reaction quotas: 30 app + 20 guild
            val reactionPrepared = turn.prepareReactionEmojis()
            assertEquals(30, reactionPrepared.applicationEmojis.size)
            assertEquals(20, reactionPrepared.guildEmojis.size)
            assertEquals(Snowflake(10_034L), reactionPrepared.applicationEmojis.first().id)
            assertEquals(Snowflake(20_024L), reactionPrepared.guildEmojis.first().id)
        }

    @Test
    fun `application emoji cache honors ten minute TTL serves stale on failure and pre scans newly discovered emojis`() =
        runTest {
            val db = openDatabase("app-cache-ttl.db")
            val clock = MutableClock(1_700_000_000_000L)
            val fetchCount = AtomicInteger(0)
            var nextFetchResult: Result<List<GuildCustomEmoji>> =
                Result.success(listOf(GuildCustomEmoji(Snowflake(30_001L), "app_one", isAnimated = false)))

            val visionUris = CopyOnWriteArrayList<String>()
            val engine =
                MockEngine { request ->
                    val body = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val uri =
                        body["input"]!!
                            .jsonArray
                            .first { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }
                            .jsonObject["uri"]!!
                            .jsonPrimitive.content
                    visionUris += uri
                    respond(
                        content = """{"id":"i1","status":"completed","finish_reason":"STOP","output_text":"可愛貓咪"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = db,
                    visionModel = "gemini-2.5-flash-lite",
                    interactionClient = createInteractionClient(engine),
                    apiKey = "test-key",
                    applicationEmojiFetcher = {
                        fetchCount.incrementAndGet()
                        nextFetchResult.getOrThrow()
                    },
                    clock = clock,
                    applicationEmojiTtlMillis = 600_000L,
                )

            // First fetch populates cache
            val t1 = service.beginTurn(Snowflake(1L), Snowflake(99L), guildEmojisProvider = { emptyList() })
            assertEquals(listOf(Snowflake(30_001L)), t1.prepareReplyEmojis("hi").applicationEmojis.map { it.id })
            assertEquals(1, fetchCount.get())

            // Within 10-minute TTL: uses cached snapshot, zero fetches
            clock.advanceMs(5 * 60 * 1000L)
            val t2 = service.beginTurn(Snowflake(2L), Snowflake(99L), guildEmojisProvider = { emptyList() })
            assertEquals(listOf(Snowflake(30_001L)), t2.prepareReplyEmojis("hi").applicationEmojis.map { it.id })
            assertEquals(1, fetchCount.get())

            // Past TTL with transient error: serves stale snapshot without caching error
            clock.advanceMs(6 * 60 * 1000L)
            nextFetchResult = Result.failure(IllegalStateException("Discord 503"))
            val t3 = service.beginTurn(Snowflake(3L), Snowflake(99L), guildEmojisProvider = { emptyList() })
            assertEquals(listOf(Snowflake(30_001L)), t3.prepareReplyEmojis("hi").applicationEmojis.map { it.id })
            assertEquals(2, fetchCount.get())

            // Next call retries immediately (error was not cached) and discovers new emoji 30_002
            nextFetchResult =
                Result.success(
                    listOf(
                        GuildCustomEmoji(Snowflake(30_001L), "app_one", isAnimated = false),
                        GuildCustomEmoji(Snowflake(30_002L), "app_two", isAnimated = false),
                    ),
                )
            val t4 = service.beginTurn(Snowflake(4L), Snowflake(99L), guildEmojisProvider = { emptyList() }, preScanScope = this)
            val prepared = t4.prepareReplyEmojis("hi")
            assertEquals(listOf(Snowflake(30_001L), Snowflake(30_002L)), prepared.applicationEmojis.map { it.id })
            assertEquals("可愛貓咪", prepared.applicationEmojis.last().meaning)
            assertEquals(3, fetchCount.get())
            assertTrue(visionUris.any { it.endsWith("/30002.png") }, "Expected vision resolution for newly discovered 30002")
        }

    @Test
    fun `preScanStartup and preScanGuild populate cached meanings and serialize concurrent SQLite access`() =
        runTest {
            val db = openDatabase("prescan-concurrency.db")
            val engine =
                MockEngine {
                    respond(
                        content = """{"id":"i1","status":"completed","finish_reason":"STOP","output_text":"測試表情"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val guildEmojiMock =
                mockk<GuildEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(40_001L)
                    every { name } returns "startup_guild"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val guildMock =
                mockk<Guild>(relaxed = true).apply {
                    every { id } returns Snowflake(77L)
                    every { emojis } returns flowOf(guildEmojiMock)
                }
            val appEmojiMock =
                mockk<ApplicationEmoji>(relaxed = true).apply {
                    every { id } returns Snowflake(40_002L)
                    every { name } returns "startup_app"
                    every { isAvailable } returns true
                    every { isAnimated } returns false
                }
            val kordMock =
                mockk<Kord>(relaxed = true).apply {
                    every { guilds } returns flowOf(guildMock)
                    every { emojis } returns flowOf(appEmojiMock)
                }

            val service: ChatterEmojiService =
                DefaultChatterEmojiService(
                    database = db,
                    visionModel = "gemini-2.5-flash-lite",
                    interactionClient = createInteractionClient(engine),
                    apiKey = "test-key",
                )

            service.preScanStartup(kordMock)
            service.preScanGuild(
                Snowflake(77L),
                listOf(GuildCustomEmoji(Snowflake(40_003L), "updated_guild", isAnimated = false)),
            )

            // Stress concurrent SQLite reads and writes across both tables through the single Mutex
            coroutineScope {
                val jobs =
                    (1..12).map { idx ->
                        async {
                            service.recordMessageUsage(Snowflake(77L), "<:startup_guild:40001> <:startup_app:40002>")
                            val turn =
                                service.beginTurn(
                                    channelId = Snowflake(100L + idx),
                                    guildId = Snowflake(77L),
                                    guildEmojisProvider = {
                                        listOf(GuildCustomEmoji(Snowflake(40_001L), "startup_guild", isAnimated = false))
                                    },
                                )
                            turn.annotateTranscript("User: <:startup_guild:40001> <:startup_app:40002> <:updated_guild:40003>")
                        }
                    }
                val results = jobs.awaitAll()
                for (annotated in results) {
                    assertEquals(
                        "User: <:startup_guild:40001>(測試表情) <:startup_app:40002>(測試表情) <:updated_guild:40003>(測試表情)",
                        annotated,
                    )
                }
            }
        }
}
