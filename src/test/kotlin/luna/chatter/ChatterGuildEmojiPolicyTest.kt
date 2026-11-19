package luna.chatter

import dev.kord.common.entity.Snowflake
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
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionInput
import luna.core.ai.GeminiInteractionRequest
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.InteractionTraceContext
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterGuildEmojiPolicyTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    @Test
    fun `ChatterPromptBuilder system instruction prohibits Unicode emojis while permitting provided guild custom emojis without inventing IDs`() {
        val systemInstruction = ChatterPromptBuilder.buildSystemInstruction()

        assertContains(systemInstruction, "Unicode")
        assertContains(systemInstruction, "<:name:id>")
        assertContains(systemInstruction, "<a:name:id>")
        assertContains(systemInstruction, "編造")
    }

    @Test
    fun `ChatterPromptBuilder buildUserPrompt injects bounded canonical guild custom emojis and omits section when empty`() {
        val sampleEmojis =
            (1..60).map { idx ->
                GuildCustomEmoji(
                    id = Snowflake(1000L + idx),
                    name = "emoji_$idx",
                    isAnimated = idx % 2 == 0,
                )
            }

        val withEmojisPrompt =
            ChatterPromptBuilder.buildUserPrompt(
                ChatterInput(
                    transcript = "[Alice]: 早安",
                    guildEmojis = sampleEmojis,
                ),
            )
        assertContains(withEmojisPrompt, "<:emoji_1:1001>")
        assertContains(withEmojisPrompt, "<a:emoji_2:1002>")
        assertContains(withEmojisPrompt, "<a:emoji_50:1050>")
        assertFalse(withEmojisPrompt.contains("emoji_51"), "Prompt guild emojis must be capped at MAX_PROMPT_GUILD_EMOJIS")
        assertContains(withEmojisPrompt, "Unicode")

        val withoutEmojisPrompt =
            ChatterPromptBuilder.buildUserPrompt(
                ChatterInput(
                    transcript = "[Alice]: 早安",
                    guildEmojis = emptyList(),
                ),
            )
        assertFalse(withoutEmojisPrompt.contains("可用的伺服器自訂表情"))
        assertContains(withoutEmojisPrompt, "嚴禁使用任何 Unicode emoji")
    }

    @Test
    fun `ChatterPromptBuilder sanitizeResponse preserves verified guild custom emojis and strips Unicode and hallucinated IDs`() {
        val guildEmojis =
            listOf(
                GuildCustomEmoji(id = Snowflake(111222333444L), name = "blobcat", isAnimated = false),
                GuildCustomEmoji(id = Snowflake(555666777888L), name = "party", isAnimated = true),
            )

        val raw = "```text\n身為一個AI助手，太神了吧 😂👍🏻 <:blobcat:111222333444> <a:party:555666777888> <:fake:999999> <a:other:111111> ✨\n```"
        val sanitizedWithGuild = ChatterPromptBuilder.sanitizeResponse(raw, guildEmojis = guildEmojis)
        assertEquals("太神了吧  <:blobcat:111222333444> <a:party:555666777888>", sanitizedWithGuild)

        val sanitizedWithoutGuild = ChatterPromptBuilder.sanitizeResponse(raw, guildEmojis = emptyList())
        assertEquals("太神了吧", sanitizedWithoutGuild)
    }

    @Test
    fun `GeminiInteractionClient strips custom emojis by default and preserves allowedCustomEmojiIds when specified`() =
        runTest {
            val mockEngine =
                MockEngine {
                    respond(
                        content =
                            """{"id":"int-1","status":"completed","finish_reason":"STOP",""" +
                                """"output_text":"好耶 <:blobcat:111222> <a:party:555666> <:fake:999999> 🔥"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client = GeminiInteractionClient(httpClient = createTestClient(mockEngine))
            val req =
                GeminiInteractionRequest(
                    model = "gemini-3.5-flash-lite",
                    input = listOf(GeminiInteractionInput.text("hello")),
                )

            // Default (Summary Pipeline): strips all custom and Unicode emojis
            val defaultResult =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request = req,
                )
            val defaultSuccess = assertIs<InteractionExecutionResult.Success>(defaultResult)
            assertEquals("好耶", defaultSuccess.text)

            // Chatter Pipeline with verified IDs: preserves verified IDs, strips Unicode & hallucinated IDs
            val allowedResult =
                client.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request = req,
                    traceContext = InteractionTraceContext(allowedCustomEmojiIds = setOf("111222", "555666")),
                )
            val allowedSuccess = assertIs<InteractionExecutionResult.Success>(allowedResult)
            assertEquals("好耶 <:blobcat:111222> <a:party:555666>", allowedSuccess.text)
        }

    @Test
    fun `ChatterGenerator generateReply injects guild emojis into prompt, preserves verified custom emojis, and strips Unicode or hallucinated IDs`() =
        runTest {
            val capturedBodies = mutableListOf<String>()
            var responseText = "太扯了吧 😂 <:blobcat:111222333444> <a:party:555666777888> <:fake:999999>"

            val mockEngine =
                MockEngine { request ->
                    capturedBodies += request.body.toByteArray().decodeToString()
                    respond(
                        content =
                            """{"id":"int-gen-1","status":"completed","finish_reason":"STOP","output_text":"$responseText"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config = ChatterConfig(apiKey = "valid-key"),
                    httpClient = createTestClient(mockEngine),
                )

            val guildEmojis =
                listOf(
                    GuildCustomEmoji(id = Snowflake(111222333444L), name = "blobcat", isAnimated = false),
                    GuildCustomEmoji(id = Snowflake(555666777888L), name = "party", isAnimated = true),
                )

            // 1. With guild emojis: verified static & animated preserved, Unicode & fake stripped
            val replyWithGuild =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 抽到 SSR 了",
                        guildEmojis = guildEmojis,
                    ),
                )
            assertEquals("太扯了吧  <:blobcat:111222333444> <a:party:555666777888>", replyWithGuild)

            val promptText1 =
                json
                    .parseToJsonElement(capturedBodies[0])
                    .jsonObject["input"]
                    ?.jsonArray
                    ?.last()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertContains(promptText1, "<:blobcat:111222333444>")
            assertContains(promptText1, "<a:party:555666777888>")

            // 2. Empty guild fallback (e.g. DM): strips all custom and Unicode emojis, no custom list in prompt
            val replyWithoutGuild =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 抽到 SSR 了",
                        guildEmojis = emptyList(),
                    ),
                )
            assertEquals("太扯了吧", replyWithoutGuild)

            val promptText2 =
                json
                    .parseToJsonElement(capturedBodies[1])
                    .jsonObject["input"]
                    ?.jsonArray
                    ?.last()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertFalse(promptText2.contains("可用的伺服器自訂表情"))

            // 3. Output consisting solely of Unicode + hallucinated custom emojis degrades to null
            responseText = "😂😂 <:fake:999999>"
            val blankAfterSanitize =
                generator.generateReply(
                    ChatterInput(
                        transcript = "[Alice]: 抽到 SSR 了",
                        guildEmojis = guildEmojis,
                    ),
                )
            assertNull(blankAfterSanitize)
        }
}
