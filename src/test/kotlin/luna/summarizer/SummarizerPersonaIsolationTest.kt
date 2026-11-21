package luna.summarizer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummarizerPersonaIsolationTest {
    @Test
    fun `Summary Pipeline prompts remain completely isolated from Chatter Persona`() {
        val summaryPrompts =
            listOf(
                GeminiPromptBuilder.SYSTEM_INSTRUCTION,
                GeminiPromptBuilder.VIDEO_SYSTEM_INSTRUCTION,
                GeminiPromptBuilder.CHAT_IMAGE_SYSTEM_INSTRUCTION,
            )
        for (prompt in summaryPrompts) {
            assertFalse(prompt.contains("Luna"), "Summary Pipeline prompt must not reference Chatter Persona Luna")
            assertFalse(prompt.contains("吐槽"), "Summary Pipeline prompt must not contain Chatter Persona traits")
            assertFalse(prompt.contains("炸毛"), "Summary Pipeline prompt must not contain Chatter Persona traits")
            assertTrue(prompt.contains("摘要助理"), "Summary Pipeline prompt must remain a structured summarization assistant")
        }
    }
}
