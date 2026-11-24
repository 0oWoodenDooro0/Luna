package luna.chatter

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.memory.ChatterMemoryConfig
import luna.chatter.memory.MemoryScope
import luna.chatter.memory.SqliteChatterMemoryStore
import luna.chatter.memory.UnderstandingPreInjector
import luna.core.LunaHost
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatterUnderstandingPreInjectionIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun createStore(): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("gen-preinject-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db)
    }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    @Test
    fun `ChatterGenerator pre-injects Social Understanding into reply prompt without embedding calls and respects enabled flag`() =
        runTest {
            val store = createStore()
            store.insertUnderstanding("1001", MemoryScope.USER, "101", "說話風格", "講話很簡短常回哈哈")
            store.insertUnderstanding("1001", MemoryScope.GUILD, null, "群組氣氛", "半夜常聊拉麵")

            val recordedBodies = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    recordedBodies.add(request.body.toByteArray().decodeToString())
                    respond(
                        content =
                            """
                            {
                              "id": "int-1",
                              "status": "completed",
                              "steps": [
                                {
                                  "type": "model_output",
                                  "content": [{"type": "text", "text": "哈哈真的假的"}]
                                }
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val httpClient = createTestClient(mockEngine)
            val preInjector = UnderstandingPreInjector(memoryStore = store)
            val input =
                ChatterInput(
                    transcript = "Alice (uid:101): 晚上想吃拉麵",
                    guildId = "1001",
                    channelId = "2001",
                    authorId = "101",
                )

            val enabledGenerator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "test-key"),
                    httpClient = httpClient,
                    memoryConfig = ChatterMemoryConfig(enabled = true),
                    understandingPreInjector = preInjector,
                )
            val reply = enabledGenerator.generateReply(input)
            assertEquals("哈哈真的假的", reply)

            val enabledPayload = json.parseToJsonElement(recordedBodies.single()).jsonObject
            val enabledPrompt =
                enabledPayload["input"]!!
                    .jsonArray
                    .first()
                    .jsonObject["text"]!!
                    .jsonPrimitive
                    .content
            assertTrue(enabledPrompt.contains("講話很簡短常回哈哈"), "Expected user understanding in prompt: $enabledPrompt")
            assertTrue(enabledPrompt.contains("半夜常聊拉麵"), "Expected guild understanding in prompt: $enabledPrompt")

            val disabledGenerator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "test-key"),
                    httpClient = httpClient,
                    memoryConfig = ChatterMemoryConfig(enabled = false),
                    understandingPreInjector = preInjector,
                )
            disabledGenerator.generateReply(input)
            val disabledPayload = json.parseToJsonElement(recordedBodies.last()).jsonObject
            val disabledPrompt =
                disabledPayload["input"]!!
                    .jsonArray
                    .first()
                    .jsonObject["text"]!!
                    .jsonPrimitive
                    .content
            assertFalse(disabledPrompt.contains("講話很簡短常回哈哈"), "Expected no user understanding when memory disabled")
            assertFalse(disabledPrompt.contains("半夜常聊拉麵"), "Expected no guild understanding when memory disabled")
        }

    @Test
    fun `ChatterPromptBuilder places understandingContext in reply user prompt only when non-blank`() {
        val input = ChatterInput(transcript = "Alice (uid:101): 哈囉")
        val withContext =
            ChatterPromptBuilder.buildUserPrompt(
                input = input,
                understandingContext = "[當前發言者 (uid:101)]\n- 風格：親切",
            )
        val withoutContext = ChatterPromptBuilder.buildUserPrompt(input = input, understandingContext = "   ")

        assertNotNull(withContext)
        assertTrue(withContext.contains("[當前發言者 (uid:101)]"), "Expected understanding header in prompt")
        assertTrue(withContext.contains("- 風格：親切"), "Expected understanding entry in prompt")
        assertFalse(withoutContext.contains("[當前發言者"), "Expected blank understandingContext to be omitted")
    }
}
