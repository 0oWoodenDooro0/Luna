package luna.chatter.memory

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import luna.core.LunaHost
import luna.core.ai.GeminiEmbeddingClient
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatterMemoryToolHandlerTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun unitVector768(): List<Float> =
        List(GeminiEmbeddingClient.DEFAULT_OUTPUT_DIMENSIONALITY) { idx ->
            if (idx == 0) 1.0f else 0.0f
        }

    private fun mockEmbeddingResponseJson(): String {
        val values = unitVector768().joinToString(",")
        return """{"embedding":{"values":[$values]}}"""
    }

    private fun createStore(clock: Clock = Clock.fixed(Instant.parse("2026-01-01T01:00:00Z"), ZoneOffset.UTC)): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("tool-handler-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    private fun createMockEmbeddingClient(
        callCounter: AtomicInteger = AtomicInteger(0),
        delayMs: Long = 0L,
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = mockEmbeddingResponseJson(),
    ): GeminiEmbeddingClient {
        val engine =
            MockEngine { _ ->
                callCounter.incrementAndGet()
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                respond(
                    content = responseBody,
                    status = statusCode,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return GeminiEmbeddingClient(
            httpClient = HttpClient(engine),
            apiKey = "test-api-key",
        )
    }

    @Test
    fun `scope both without target_user_id defaults to triggering author and returns qualifying memories`() =
        runBlocking {
            val mutableClock =
                object : Clock() {
                    var now: Instant = Instant.parse("2026-01-01T00:00:00Z")

                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId) = this

                    override fun instant(): Instant = now
                }
            val sharedStore = createStore(mutableClock)
            sharedStore.insertMemory(
                guildId = "1001",
                scope = MemoryScope.USER,
                targetUserId = "999001",
                channelId = "2002",
                content = "Alice likes pour-over Ethiopian coffee",
            )
            sharedStore.insertMemory(
                guildId = "1001",
                scope = MemoryScope.USER,
                targetUserId = "999002",
                channelId = "2002",
                content = "Bob likes Ethiopian coffee too",
            )
            sharedStore.insertMemory(
                guildId = "1001",
                scope = MemoryScope.CHANNEL,
                targetUserId = null,
                channelId = "2001",
                content = "This channel hosts weekly coffee tastings",
            )
            mutableClock.now = Instant.parse("2026-01-01T01:00:00Z")

            val embedCalls = AtomicInteger(0)
            val embeddingClient = createMockEmbeddingClient(callCounter = embedCalls)
            val handler =
                ChatterMemoryToolHandler(
                    embeddingClient = embeddingClient,
                    memoryStore = sharedStore,
                    config = ChatterMemoryConfig(),
                )

            val resultJson =
                handler.handleToolCall(
                    arguments =
                        buildJsonObject {
                            put("query", "Ethiopian coffee")
                            put("scope", "both")
                            put("limit", 5)
                        },
                    guildId = "1001",
                    channelId = "2001",
                    defaultAuthorId = "999001",
                )

            assertEquals(1, embedCalls.get())
            val obj = json.parseToJsonElement(resultJson).jsonObject
            assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
            val memories = obj["memories"]?.jsonArray.orEmpty()
            assertEquals(2, memories.size)
            val contents = memories.map { it.jsonObject["content"]?.jsonPrimitive?.content }
            assertTrue(contents.contains("Alice likes pour-over Ethiopian coffee"), "Expected Alice's user memory in results: $contents")
            assertTrue(contents.contains("This channel hosts weekly coffee tastings"), "Expected channel memory in results: $contents")
            assertTrue(contents.none { it?.contains("Bob") == true }, "Did not expect Bob's memory in results: $contents")
        }

    @Test
    fun `rejects invalid non-Snowflake target_user_id without calling Embedding API`() =
        runBlocking {
            val embedCalls = AtomicInteger(0)
            val embeddingClient = createMockEmbeddingClient(callCounter = embedCalls)
            val store = createStore()
            val handler =
                ChatterMemoryToolHandler(
                    embeddingClient = embeddingClient,
                    memoryStore = store,
                    config = ChatterMemoryConfig(),
                )

            val resultJson =
                handler.handleToolCall(
                    arguments =
                        buildJsonObject {
                            put("query", "coffee preference")
                            put("scope", "user")
                            put("target_user_id", "not-a-snowflake")
                        },
                    guildId = "1001",
                    channelId = "2001",
                    defaultAuthorId = "999001",
                )

            assertEquals(0, embedCalls.get())
            val obj = json.parseToJsonElement(resultJson).jsonObject
            assertEquals("invalid_argument", obj["status"]?.jsonPrimitive?.content)
            assertTrue(obj["message"]?.jsonPrimitive?.content?.contains("target_user_id") == true, "Expected target_user_id error message in $obj")
        }

    @Test
    fun `empty search result includes recent 10 minute context note without padding`() =
        runBlocking {
            val embedCalls = AtomicInteger(0)
            val embeddingClient = createMockEmbeddingClient(callCounter = embedCalls)
            val store = createStore()
            val handler =
                ChatterMemoryToolHandler(
                    embeddingClient = embeddingClient,
                    memoryStore = store,
                    config = ChatterMemoryConfig(),
                )

            val resultJson =
                handler.handleToolCall(
                    arguments =
                        buildJsonObject {
                            put("query", "nonexistent topic")
                            put("limit", 99)
                        },
                    guildId = "1001",
                    channelId = "2001",
                    defaultAuthorId = "999001",
                )

            val obj = json.parseToJsonElement(resultJson).jsonObject
            assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
            assertEquals(0, obj["count"]?.jsonPrimitive?.content?.toInt())
            assertTrue(obj["memories"]?.jsonArray?.isEmpty() == true, "Expected empty memories array in $obj")
            assertTrue(obj["note"]?.jsonPrimitive?.content?.contains("近期 10 分鐘對話已在上下文中") == true, "Expected recent context note in $obj")
        }

    @Test
    fun `slow or failing Embedding API triggers fail-open unavailable response`() =
        runBlocking {
            val store = createStore()
            val slowEmbeddingClient = createMockEmbeddingClient(delayMs = 500L)
            val timeoutHandler =
                ChatterMemoryToolHandler(
                    embeddingClient = slowEmbeddingClient,
                    memoryStore = store,
                    config = ChatterMemoryConfig(toolTimeoutMs = 50L),
                )

            val timeoutJson =
                timeoutHandler.handleToolCall(
                    arguments = buildJsonObject { put("query", "coffee") },
                    guildId = "1001",
                    channelId = "2001",
                    defaultAuthorId = "999001",
                )
            val timeoutObj = json.parseToJsonElement(timeoutJson).jsonObject
            assertEquals("unavailable", timeoutObj["status"]?.jsonPrimitive?.content)

            val errorEmbeddingClient =
                createMockEmbeddingClient(
                    statusCode = HttpStatusCode.InternalServerError,
                    responseBody = """{"error":"boom"}""",
                )
            val errorHandler =
                ChatterMemoryToolHandler(
                    embeddingClient = errorEmbeddingClient,
                    memoryStore = store,
                    config = ChatterMemoryConfig(toolTimeoutMs = 3000L),
                )

            val errorJson =
                errorHandler.handleToolCall(
                    arguments = buildJsonObject { put("query", "coffee") },
                    guildId = "1001",
                    channelId = "2001",
                    defaultAuthorId = "999001",
                )
            val errorObj = json.parseToJsonElement(errorJson).jsonObject
            assertEquals("unavailable", errorObj["status"]?.jsonPrimitive?.content)
        }

    @Test
    fun `clamps limit to 1 to 8 and rejects blank query or invalid scope`() =
        runBlocking {
            val embedCalls = AtomicInteger(0)
            val embeddingClient = createMockEmbeddingClient(callCounter = embedCalls)
            val store = createStore()
            val handler =
                ChatterMemoryToolHandler(
                    embeddingClient = embeddingClient,
                    memoryStore = store,
                    config = ChatterMemoryConfig(),
                )

            val blankQueryObj =
                json
                    .parseToJsonElement(
                        handler.handleToolCall(
                            arguments = buildJsonObject { put("query", "   ") },
                            guildId = "1001",
                            channelId = "2001",
                            defaultAuthorId = "999001",
                        ),
                    ).jsonObject
            assertEquals("invalid_argument", blankQueryObj["status"]?.jsonPrimitive?.content)

            val badScopeObj =
                json
                    .parseToJsonElement(
                        handler.handleToolCall(
                            arguments =
                                buildJsonObject {
                                    put("query", "coffee")
                                    put("scope", "global")
                                },
                            guildId = "1001",
                            channelId = "2001",
                            defaultAuthorId = "999001",
                        ),
                    ).jsonObject
            assertEquals("invalid_argument", badScopeObj["status"]?.jsonPrimitive?.content)
            assertEquals(0, embedCalls.get())
        }

    @Test
    fun `workingMemoryStartMs eliminates distillation recency black hole while still preventing duplication`() =
        runBlocking {
            val mutableClock =
                object : Clock() {
                    var now: Instant = Instant.parse("2026-01-01T10:05:00Z")

                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId) = this

                    override fun instant(): Instant = now
                }
            val store = createStore(mutableClock)
            // Memory distilled at 10:05 from messages originally sent at 10:00
            store.insertMemory(
                guildId = "1001",
                scope = MemoryScope.USER,
                targetUserId = "999001",
                channelId = "2001",
                content = "Alice prefers pour-over coffee",
            )
            val embeddingClient = createMockEmbeddingClient()
            val handler =
                ChatterMemoryToolHandler(
                    embeddingClient = embeddingClient,
                    memoryStore = store,
                    config = ChatterMemoryConfig(recencyExcludeMinutes = 10L),
                )

            // At 10:11 (only 6m after distillation), conversation resumed after >10m gap so Working Memory starts at 10:11
            mutableClock.now = Instant.parse("2026-01-01T10:11:00Z")
            val resumedResult =
                json
                    .parseToJsonElement(
                        handler.handleToolCall(
                            arguments = buildJsonObject { put("query", "pour-over coffee") },
                            guildId = "1001",
                            channelId = "2001",
                            defaultAuthorId = "999001",
                            workingMemoryStartMs = Instant.parse("2026-01-01T10:11:00Z").toEpochMilli(),
                        ),
                    ).jsonObject
            assertEquals(1, resumedResult["count"]?.jsonPrimitive?.content?.toInt())

            // When Working Memory still covers 10:00..10:08, memory distilled at 10:05 is excluded to avoid duplication
            mutableClock.now = Instant.parse("2026-01-01T10:08:00Z")
            val overlapResult =
                json
                    .parseToJsonElement(
                        handler.handleToolCall(
                            arguments = buildJsonObject { put("query", "pour-over coffee") },
                            guildId = "1001",
                            channelId = "2001",
                            defaultAuthorId = "999001",
                            workingMemoryStartMs = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli(),
                        ),
                    ).jsonObject
            assertEquals(0, overlapResult["count"]?.jsonPrimitive?.content?.toInt())
        }
}
