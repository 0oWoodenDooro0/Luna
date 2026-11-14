package luna.core.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GeminiModelsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    @Test
    fun `serializes file_data and googleSearch tool cleanly in GeminiGenerateContentRequest`() {
        val request =
            GeminiGenerateContentRequest(
                contents =
                    listOf(
                        GeminiContent(
                            role = "user",
                            parts =
                                listOf(
                                    GeminiPart(
                                        fileData =
                                            GeminiFileData(
                                                fileUri = "https://cdn.discordapp.com/img.png",
                                                mimeType = "image/png",
                                            ),
                                    ),
                                    GeminiPart(text = "Describe this image"),
                                ),
                        ),
                    ),
                tools = listOf(GeminiTools.GOOGLE_SEARCH_TOOL),
                generationConfig = GeminiGenerationConfig(temperature = 0.2, maxOutputTokens = 512),
            )

        val encoded = json.encodeToString(request)
        assertContains(encoded, "\"file_uri\":\"https://cdn.discordapp.com/img.png\"")
        assertContains(encoded, "\"mime_type\":\"image/png\"")
        assertContains(encoded, "\"googleSearch\":{}")
        assertContains(encoded, "\"maxOutputTokens\":512")
    }

    @Test
    fun `GeminiInteractionResponse extracts model_output steps before falling back to outputText`() {
        val responseWithSteps =
            GeminiInteractionResponse(
                id = "int-1",
                status = "completed",
                outputText = "Fallback output",
                steps =
                    listOf(
                        GeminiInteractionStep(type = "thought", content = listOf(GeminiInteractionStepContent(text = "thinking"))),
                        GeminiInteractionStep(type = "model_output", content = listOf(GeminiInteractionStepContent(text = "Step output"))),
                    ),
            )
        assertEquals("Step output", responseWithSteps.extractOutputText())

        val responseWithoutSteps =
            GeminiInteractionResponse(
                id = "int-2",
                status = "completed",
                outputText = "  Direct output  ",
            )
        assertEquals("Direct output", responseWithoutSteps.extractOutputText())
    }

    @Test
    fun `parses usageMetadata and finishReason from camelCase Gemini response`() {
        val rawJson =
            """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [{"text": "Hello"}]
                  },
                  "finishReason": "STOP"
                }
              ],
              "promptFeedback": {
                "blockReason": "SAFETY"
              },
              "usageMetadata": {
                "promptTokenCount": 120,
                "candidatesTokenCount": 45,
                "totalTokenCount": 165
              }
            }
            """.trimIndent()

        val parsed = json.decodeFromString<GeminiGenerateContentResponse>(rawJson)
        assertEquals("STOP", parsed.candidates?.firstOrNull()?.finishReason)
        assertEquals("SAFETY", parsed.promptFeedback?.blockReason)
        assertEquals(GeminiUsageMetadata(promptTokenCount = 120, candidatesTokenCount = 45, totalTokenCount = 165), parsed.usageMetadata)
    }

    @Test
    fun `parses usage_metadata and finish_reason from snake_case Gemini response`() {
        val rawJson =
            """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [{"text": "Hello"}]
                  },
                  "finish_reason": "MAX_TOKENS"
                }
              ],
              "prompt_feedback": {
                "block_reason": "OTHER"
              },
              "usage_metadata": {
                "prompt_token_count": 300,
                "candidates_token_count": 200,
                "total_token_count": 500
              }
            }
            """.trimIndent()

        val parsed = json.decodeFromString<GeminiGenerateContentResponse>(rawJson)
        assertEquals("MAX_TOKENS", parsed.candidates?.firstOrNull()?.finishReason)
        assertEquals("OTHER", parsed.promptFeedback?.blockReason)
        assertEquals(GeminiUsageMetadata(promptTokenCount = 300, candidatesTokenCount = 200, totalTokenCount = 500), parsed.usageMetadata)
    }

    @Test
    fun `serializes full GeminiInteractionRequest with tools generation_config previous_interaction_id and omits mime_type for YouTube`() {
        val request =
            GeminiInteractionRequest(
                model = "gemini-3.5-flash-lite",
                systemInstruction = "Summarize accurately.",
                input =
                    listOf(
                        GeminiInteractionInput.video(
                            uri = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                            processing = VideoProcessingMode.STATIC,
                            mimeType = "video/mp4",
                        ),
                        GeminiInteractionInput.image(
                            uri = "https://cdn.discordapp.com/attachments/1/2/img.png",
                            mimeType = "image/png",
                        ),
                        GeminiInteractionInput.text("What is in this thread?"),
                    ),
                tools =
                    listOf(
                        GeminiInteractionTools.URL_CONTEXT,
                        GeminiInteractionTools.GOOGLE_SEARCH,
                        GeminiInteractionTools.FETCH_EARLIER_MESSAGES,
                    ),
                generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = 1024),
                background = false,
                previousInteractionId = "int-prev-123",
            )

        val encoded = json.encodeToString(request)
        val obj = Json.parseToJsonElement(encoded).let { it as kotlinx.serialization.json.JsonObject }

        assertEquals("gemini-3.5-flash-lite", obj["model"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("Summarize accurately.", obj["system_instruction"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("int-prev-123", obj["previous_interaction_id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("false", obj["background"]?.toString())

        val genConfig = obj["generation_config"] as kotlinx.serialization.json.JsonObject
        assertEquals("1024", genConfig["max_output_tokens"]?.toString())
        assertEquals(false, genConfig.containsKey("temperature"))

        val inputs = obj["input"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, inputs.size)
        val videoObj = inputs[0] as kotlinx.serialization.json.JsonObject
        assertEquals("video", (videoObj["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("static", (videoObj["processing"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(false, videoObj.containsKey("mime_type"))

        val imageObj = inputs[1] as kotlinx.serialization.json.JsonObject
        assertEquals("image", (imageObj["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("image/png", (imageObj["mime_type"] as kotlinx.serialization.json.JsonPrimitive).content)

        val tools = obj["tools"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, tools.size)
        assertEquals("url_context", ((tools[0] as kotlinx.serialization.json.JsonObject)["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("google_search", ((tools[1] as kotlinx.serialization.json.JsonObject)["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        val fnTool = tools[2] as kotlinx.serialization.json.JsonObject
        assertEquals("function", (fnTool["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("fetch_earlier_messages", (fnTool["name"] as kotlinx.serialization.json.JsonPrimitive).content)

        val continuation =
            GeminiInteractionRequest(
                model = "gemini-3.5-flash-lite",
                input = listOf(GeminiInteractionInput.functionResult("fetch_earlier_messages", "call_99", "Alice: earlier msg")),
                previousInteractionId = "int-prev-123",
                background = false,
            )
        val encodedCont = json.encodeToString(continuation)
        assertContains(encodedCont, "\"type\":\"function_result\"")
        assertContains(encodedCont, "\"call_id\":\"call_99\"")
        assertContains(encodedCont, "\"name\":\"fetch_earlier_messages\"")
    }

    @Test
    fun `parses Interactions steps with JsonArray and JsonObject tool results function_call usage and url_citation filtering`() {
        val rawJson =
            """
            {
              "id": "int-full-1",
              "status": "completed",
              "usage": {
                "input_tokens": 500,
                "output_tokens": 120,
                "total_tokens": 700,
                "thoughts_tokens": 50,
                "tool_use_input_tokens": 30
              },
              "steps": [
                {"type": "thought", "content": [{"type": "text", "text": "analyzing"}]},
                {"type": "url_context_call", "id": "uc-1", "arguments": {"urls": ["https://example.com/post/"]}},
                {
                  "type": "url_context_result",
                  "call_id": "uc-1",
                  "result": [
                    {"retrieved_url": "https://example.com/post/", "status": "success"},
                    {"retrieved_url": "https://blocked.example.org/bad", "status": "unsafe"}
                  ]
                },
                {"type": "google_search_call", "id": "gs-1", "arguments": {"queries": ["kotlin 2.1", "kotlin 2.1", "luna bot"]}},
                {
                  "type": "google_search_result",
                  "call_id": "gs-1",
                  "result": {"organic_results": [{"url": "https://kotlinlang.org/docs"}]}
                },
                {"type": "function_call", "id": "fn-1", "name": "fetch_earlier_messages", "arguments": {"reason": "need context"}},
                {
                  "type": "model_output",
                  "content": [
                    {
                      "type": "text",
                      "text": "Summary body",
                      "annotations": [
                        {"type": "url_citation", "url": "http://Example.COM/original", "title": "Original Input"},
                        {"type": "url_citation", "url": "https://example.com/post", "title": "Retrieved URL"},
                        {"type": "url_citation", "url": "https://kotlinlang.org/docs/", "title": "Kotlin Docs"},
                        {"type": "url_citation", "url": "http://kotlinlang.org/docs", "title": "Kotlin Docs Dup"},
                        {"type": "url_citation", "url": "https://blog.jetbrains.com/kotlin", "title": ""},
                        {"type": "url_citation", "url": "https://developer.android.com/kotlin", "title": "Android Kotlin"},
                        {"type": "url_citation", "url": "https://github.com/JetBrains/kotlin", "title": "GitHub Kotlin"},
                        {"type": "other_annotation", "url": "https://ignored.example.com", "title": "Ignored"}
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val parsed = json.decodeFromString<GeminiInteractionResponse>(rawJson)
        assertEquals("Summary body", parsed.extractOutputText())
        assertEquals(
            GeminiUsageMetadata(
                promptTokenCount = 500,
                candidatesTokenCount = 120,
                totalTokenCount = 700,
                thoughtsTokenCount = 50,
                toolUseInputTokens = 30,
            ),
            parsed.usage,
        )

        val urlResults = parsed.extractUrlContextResults()
        assertEquals(
            listOf(
                UrlContextResultEntry(retrievedUrl = "https://example.com/post/", status = "success"),
                UrlContextResultEntry(retrievedUrl = "https://blocked.example.org/bad", status = "unsafe"),
            ),
            urlResults,
        )

        assertEquals(listOf("kotlin 2.1", "luna bot"), parsed.extractGoogleSearchQueries())

        val fnCalls = parsed.extractFunctionCalls()
        assertEquals(1, fnCalls.size)
        assertEquals("fn-1", fnCalls[0].id)
        assertEquals("fetch_earlier_messages", fnCalls[0].name)
        assertEquals("need context", fnCalls[0].argumentsAsStrings()["reason"])

        val filtered = parsed.extractFilteredGroundingCitations(excludedUrls = listOf("https://example.com/original/"))
        assertEquals(
            listOf(
                GroundingCitation(title = "Kotlin Docs", url = "https://kotlinlang.org/docs/"),
                GroundingCitation(title = "https://blog.jetbrains.com/kotlin", url = "https://blog.jetbrains.com/kotlin"),
                GroundingCitation(title = "Android Kotlin", url = "https://developer.android.com/kotlin"),
            ),
            filtered,
        )
    }

    @Test
    fun `parses url_context_result as JsonObject and google_search_result as JsonArray resiliently`() {
        val rawJson =
            """
            {
              "id": "int-alt-shapes",
              "status": "completed",
              "steps": [
                {
                  "type": "url_context_result",
                  "call_id": "uc-obj",
                  "result": {"retrieved_url": "https://single.example.com/page", "url_retrieval_status": "URL_RETRIEVAL_STATUS_ERROR"}
                },
                {
                  "type": "google_search_result",
                  "call_id": "gs-arr",
                  "result": [{"url": "https://search.example.com", "title": "Search Result"}]
                },
                {
                  "type": "model_output",
                  "content": [{"type": "text", "text": "Fallback still works"}]
                }
              ]
            }
            """.trimIndent()

        val parsed = json.decodeFromString<GeminiInteractionResponse>(rawJson)
        assertEquals("Fallback still works", parsed.extractOutputText())
        assertEquals(
            listOf(
                UrlContextResultEntry(
                    retrievedUrl = "https://single.example.com/page",
                    status = "URL_RETRIEVAL_STATUS_ERROR",
                ),
            ),
            parsed.extractUrlContextResults(),
        )
    }
}
