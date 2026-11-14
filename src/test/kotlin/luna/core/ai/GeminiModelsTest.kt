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
}
