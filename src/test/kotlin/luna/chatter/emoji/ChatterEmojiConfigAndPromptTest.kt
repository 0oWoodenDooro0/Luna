package luna.chatter.emoji

import dev.kord.common.entity.Snowflake
import luna.chatter.ChatterConfig
import luna.chatter.ChatterInput
import luna.chatter.ChatterPromptBuilder
import luna.chatter.GuildCustomEmoji
import luna.config.EnvReader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatterEmojiConfigAndPromptTest {
    @Test
    fun `ChatterConfig resolves CHATTER_EMOJI_VISION_MODEL strictly opt-in with no fallback to chat models`() {
        assertNull(ChatterConfig().emojiVisionModel)
        assertNull(ChatterConfig().cleanEmojiVisionModel)

        val noFallbackEnv =
            ChatterConfig.from(
                EnvReader.of(
                    "GEMINI_API_KEY" to "test-key",
                    "CHATTER_GEMINI_MODEL" to "gemini-chat-model",
                    "GEMINI_MODEL" to "gemini-default-model",
                ),
            )
        assertNull(noFallbackEnv.emojiVisionModel)
        assertNull(noFallbackEnv.cleanEmojiVisionModel)

        val blankEnv =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_EMOJI_VISION_MODEL" to "   ",
                    "CHATTER_GEMINI_MODEL" to "gemini-chat-model",
                    "GEMINI_MODEL" to "gemini-default-model",
                ),
            )
        assertNull(blankEnv.emojiVisionModel)

        val configuredEnv =
            ChatterConfig.from(
                EnvReader.of(
                    "CHATTER_EMOJI_VISION_MODEL" to " models/gemini-2.5-flash ",
                ),
            )
        assertEquals("gemini-2.5-flash", configuredEnv.emojiVisionModel)
        assertEquals("gemini-2.5-flash", configuredEnv.cleanEmojiVisionModel)

        val legacyIgnored =
            ChatterConfig.from(
                EnvReader.of(
                    "chatter.emojiVisionModel" to "models/gemini-2.5-flash-lite",
                ),
            )
        assertNull(legacyIgnored.emojiVisionModel)
    }

    @Test
    fun `ChatterPromptBuilder formats cached guild emoji meanings as colon-separated entries and leaves uncached bare`() {
        val emojis =
            listOf(
                GuildCustomEmoji(
                    id = Snowflake(1001L),
                    name = "blob_clap",
                    isAnimated = false,
                    meaning = "開心拍手",
                ),
                GuildCustomEmoji(
                    id = Snowflake(1002L),
                    name = "party_parrot",
                    isAnimated = true,
                    meaning = null,
                ),
                GuildCustomEmoji(
                    id = Snowflake(1003L),
                    name = "blank_meaning",
                    isAnimated = false,
                    meaning = "  ",
                ),
            )

        val userPrompt = ChatterPromptBuilder.buildUserPrompt(ChatterInput(transcript = "[Alice]: hi", guildEmojis = emojis))
        assertContains(userPrompt, "<:blob_clap:1001>：開心拍手")
        assertContains(userPrompt, "<a:party_parrot:1002>")
        assertFalse(
            userPrompt.contains("<a:party_parrot:1002>："),
            "Emoji with null meaning must not render a trailing separator in user prompt",
        )
        assertContains(userPrompt, "<:blank_meaning:1003>")
        assertFalse(
            userPrompt.contains("<:blank_meaning:1003>："),
            "Emoji with blank meaning must not render a trailing separator in user prompt",
        )

        val reactionSys = ChatterPromptBuilder.buildReactionSystemInstruction(guildEmojis = emojis)
        assertContains(reactionSys, "<:blob_clap:1001>：開心拍手")
        assertContains(reactionSys, "<a:party_parrot:1002>")
        assertFalse(
            reactionSys.contains("<a:party_parrot:1002>："),
            "Emoji with null meaning must not render a trailing separator in reaction system instruction",
        )
    }
}
