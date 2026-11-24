package luna.chatter.memory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import luna.core.LunaHost
import luna.core.ai.GeminiEmbeddingClient
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatterMemoryToolUnderstandingTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun mockEmbeddingResponseJson(): String {
        val values = List(GeminiEmbeddingClient.DEFAULT_OUTPUT_DIMENSIONALITY) { idx -> if (idx == 0) 1.0f else 0.0f }.joinToString(",")
        return """{"embedding":{"values":[$values]}}"""
    }

    private fun createStore(clock: Clock = Clock.fixed(Instant.parse("2026-01-01T01:00:00Z"), ZoneOffset.UTC)): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("tool-understanding-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    private fun createMockEmbeddingClient(callCounter: AtomicInteger = AtomicInteger(0)): GeminiEmbeddingClient {
        val engine =
            MockEngine { _ ->
                callCounter.incrementAndGet()
                respond(
                    content = mockEmbeddingResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return GeminiEmbeddingClient(
            httpClient = HttpClient(engine),
            apiKey = "test-api-key",
        )
    }

    @Test
    fun `search_long_term_memory omits keywords and attaches direct SQL user_understandings when target_user_id is queried`() =
        runBlocking {
            val mutableClock =
                object : Clock() {
                    var now: Instant = Instant.parse("2026-01-01T00:00:00Z")

                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId) = this

                    override fun instant(): Instant = now
                }
            val store = createStore(mutableClock)
            store.insertMemory(
                guildId = "1001",
                scope = MemoryScope.USER,
                targetUserId = "999002",
                content = "Bob enjoys pour-over Ethiopian coffee",
                keywords = "coffee pour-over ethiopian",
            )
            val insertedUnderstanding =
                store.insertUnderstanding(
                    guildId = "1001",
                    scope = MemoryScope.USER,
                    targetUserId = "999002",
                    subject = "說話風格",
                    content = "語氣冷靜但常偷偷吐槽",
                )
            mutableClock.now = Instant.parse("2026-01-02T00:00:00Z")

            val embedCalls = AtomicInteger(0)
            val handler =
                ChatterMemoryToolHandler(
                    embeddingClient = createMockEmbeddingClient(callCounter = embedCalls),
                    memoryStore = store,
                    config = ChatterMemoryConfig(),
                    clock = mutableClock,
                )

            val resultJson =
                handler.handleToolCall(
                    arguments =
                        buildJsonObject {
                            put("query", "Ethiopian coffee")
                            put("scope", "user")
                            put("target_user_id", "999002")
                        },
                    guildId = "1001",
                    channelId = "2001",
                    defaultAuthorId = "999001",
                )

            assertEquals(1, embedCalls.get())
            val obj = json.parseToJsonElement(resultJson).jsonObject
            assertEquals("ok", obj["status"]?.jsonPrimitive?.content)

            val memories = obj["memories"]?.jsonArray.orEmpty().map { it.jsonObject }
            assertEquals(1, memories.size)
            assertTrue(!memories.first().containsKey("keywords"), "Tool response must omit keywords: ${memories.first()}")

            val understandings = obj["user_understandings"]?.jsonArray.orEmpty().map { it.jsonObject }
            assertEquals(1, understandings.size)
            assertEquals("說話風格", understandings.first()["subject"]?.jsonPrimitive?.content)
            assertEquals("語氣冷靜但常偷偷吐槽", understandings.first()["content"]?.jsonPrimitive?.content)

            val refreshed = store.listUserUnderstandings("1001", "999002").single { it.id == insertedUnderstanding.id }
            assertEquals(mutableClock.instant().toEpochMilli(), refreshed.lastAccessedAt)
        }

    @Test
    fun `search_long_term_memory omits user_understandings when target_user_id is omitted in scope both`() =
        runBlocking {
            val store = createStore()
            store.insertMemory(
                guildId = "1001",
                scope = MemoryScope.USER,
                targetUserId = "999001",
                content = "Alice likes pour-over Ethiopian coffee",
            )
            store.insertUnderstanding(
                guildId = "1001",
                scope = MemoryScope.USER,
                targetUserId = "999001",
                subject = "互動習慣",
                content = "喜歡深夜聊咖啡",
            )

            val handler =
                ChatterMemoryToolHandler(
                    embeddingClient = createMockEmbeddingClient(),
                    memoryStore = store,
                    config = ChatterMemoryConfig(recencyExcludeMinutes = 0L),
                )

            val resultJson =
                handler.handleToolCall(
                    arguments =
                        buildJsonObject {
                            put("query", "Ethiopian coffee")
                            put("scope", "both")
                        },
                    guildId = "1001",
                    channelId = "2001",
                    defaultAuthorId = "999001",
                )

            val obj = json.parseToJsonElement(resultJson).jsonObject
            assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
            assertTrue(!obj.containsKey("user_understandings"), "Did not expect user_understandings when target_user_id is omitted: $obj")
        }

    @Test
    fun `search_long_term_memory fails open with warning log when Social Understanding lookup throws`() =
        runBlocking {
            val logger = LoggerFactory.getLogger(ChatterMemoryToolHandler::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                val baseStore = createStore()
                baseStore.insertMemory(
                    guildId = "1001",
                    scope = MemoryScope.USER,
                    targetUserId = "999002",
                    content = "Bob enjoys pour-over Ethiopian coffee",
                )
                val failingUnderstandingStore =
                    object : ChatterMemoryStore by baseStore {
                        override fun listUserUnderstandings(
                            guildId: String,
                            userId: String,
                            limit: Int,
                        ): List<SocialUnderstandingRecord> = throw IllegalStateException("sqlite understanding lookup error")
                    }

                val handler =
                    ChatterMemoryToolHandler(
                        embeddingClient = createMockEmbeddingClient(),
                        memoryStore = failingUnderstandingStore,
                        config = ChatterMemoryConfig(recencyExcludeMinutes = 0L),
                    )

                val resultJson =
                    handler.handleToolCall(
                        arguments =
                            buildJsonObject {
                                put("query", "Ethiopian coffee")
                                put("scope", "user")
                                put("target_user_id", "999002")
                            },
                        guildId = "1001",
                        channelId = "2001",
                        defaultAuthorId = "999001",
                    )

                val obj = json.parseToJsonElement(resultJson).jsonObject
                assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
                assertEquals(1, obj["memories"]?.jsonArray?.size)
                assertTrue(!obj.containsKey("user_understandings"), "Expected no user_understandings when lookup fails open: $obj")

                val warnMessages = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnMessages.any { it.contains("Social Understanding") || it.contains("understanding") },
                    "Expected WARN log for fail-open understanding lookup, got: $warnMessages",
                )
            } finally {
                logger.detachAppender(appender)
            }
        }
}
