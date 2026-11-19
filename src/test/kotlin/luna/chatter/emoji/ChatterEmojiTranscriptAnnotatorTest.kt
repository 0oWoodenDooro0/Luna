package luna.chatter.emoji

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatterEmojiTranscriptAnnotatorTest {
    @Test
    fun `annotate injects meanings inline for cached static, animated, external, and duplicate tokens`() {
        val transcript =
            "[Alice]: 今天超開心 <:blob_clap:1001> <a:party_parrot:1002> <:blob_clap:1001>\n" +
                "[Bob]: 這太扯了吧 <:nitro_shock:9999> <:uncached_emoji:5555>"

        val annotated =
            ChatterEmojiTranscriptAnnotator.annotate(
                transcript = transcript,
                meaningsById =
                    mapOf(
                        "1001" to "開心拍手",
                        "1002" to "興奮搖擺",
                        "9999" to "震驚傻眼",
                        "5555" to "   ",
                    ),
            )

        assertEquals(
            "[Alice]: 今天超開心 <:blob_clap:1001>(開心拍手) <a:party_parrot:1002>(興奮搖擺) <:blob_clap:1001>(開心拍手)\n" +
                "[Bob]: 這太扯了吧 <:nitro_shock:9999>(震驚傻眼) <:uncached_emoji:5555>",
            annotated,
        )
    }

    @Test
    fun `annotate leaves transcript untouched when meaningsById is empty or transcript has no custom emojis`() {
        val plain = "[Alice]: 單純文字訊息 👍"
        assertEquals(plain, ChatterEmojiTranscriptAnnotator.annotate(plain, mapOf("1001" to "拍手")))

        val withUncached = "[Alice]: <:a1:1001> <a:b2:1002>"
        assertEquals(withUncached, ChatterEmojiTranscriptAnnotator.annotate(withUncached, emptyMap()))
    }

    @Test
    fun `extractEmojiTokens parses static and animated custom emoji tokens in order`() {
        val tokens =
            ChatterEmojiTranscriptAnnotator.extractEmojiTokens(
                "hi <:one:111> and <a:two_anim:222> and <:one:111>",
            )

        assertEquals(
            listOf(
                CustomEmojiToken(id = "111", name = "one", isAnimated = false, mention = "<:one:111>"),
                CustomEmojiToken(id = "222", name = "two_anim", isAnimated = true, mention = "<a:two_anim:222>"),
                CustomEmojiToken(id = "111", name = "one", isAnimated = false, mention = "<:one:111>"),
            ),
            tokens,
        )
    }
}
