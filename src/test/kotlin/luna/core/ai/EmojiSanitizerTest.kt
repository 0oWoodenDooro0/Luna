package luna.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiSanitizerTest {
    @Test
    fun `detects emojis across various Unicode ranges`() {
        assertTrue(EmojiSanitizer.containsEmoji("測試 🚀 飛向宇宙"))
        assertTrue(EmojiSanitizer.containsEmoji("按讚 👍"))
        assertTrue(EmojiSanitizer.containsEmoji("笑臉 😀"))
        assertTrue(EmojiSanitizer.containsEmoji("靈感 💡"))
        assertTrue(EmojiSanitizer.containsEmoji("警告 ⚠️"))
    }

    @Test
    fun `returns false for plain Chinese, English, numbers, and standard punctuation`() {
        assertFalse(EmojiSanitizer.containsEmoji("你是一個專業的內容摘要助理。"))
        assertFalse(EmojiSanitizer.containsEmoji("TL;DR: 這是摘要。"))
        assertFalse(EmojiSanitizer.containsEmoji("重點摘要:\n• 第 1 點\n• 第 2 點"))
        assertFalse(EmojiSanitizer.containsEmoji("社群討論風向: 大家都在討論。"))
        assertFalse(EmojiSanitizer.containsEmoji("1234567890 !@#$%^&*()-_=+[]{}|;:',.<>?/`~"))
    }

    @Test
    fun `stripEmojis removes all emojis while preserving valid text`() {
        val input = "TL;DR: 🚀 台灣科技發展快速 👍！\n重點摘要:\n• 💡 量子運算突破 100 萬量子位元"
        val expected = "TL;DR:  台灣科技發展快速 ！\n重點摘要:\n•  量子運算突破 100 萬量子位元"
        val result = EmojiSanitizer.stripEmojis(input)

        assertFalse(EmojiSanitizer.containsEmoji(result))
        assertEquals(expected, result)
    }

    @Test
    fun `stripEmojis strips all custom emoji tokens by default for Summary Pipeline`() {
        val input = "大家在討論 <:blobcat:111222333444> 跟 <a:party:555666777888> 🔥"
        val result = EmojiSanitizer.stripEmojis(input)

        assertTrue(EmojiSanitizer.containsEmoji(input))
        assertFalse(EmojiSanitizer.containsEmoji(result))
        assertEquals("大家在討論  跟  ", result)
    }

    @Test
    fun `stripEmojis preserves verified guild custom emojis while stripping Unicode and hallucinated custom emojis`() {
        val allowedIds = setOf("111222333444", "555666777888")
        val input =
            "這波太強 <:blobcat:111222333444> <a:party:555666777888> <:fake:999999> <a:other:111111> 😂👍🏻⭐⏰1️⃣🇹🇼"

        val result = EmojiSanitizer.stripEmojis(input, allowedCustomEmojiIds = allowedIds)

        assertTrue(EmojiSanitizer.containsEmoji(input, allowedCustomEmojiIds = allowedIds))
        assertFalse(EmojiSanitizer.containsEmoji(result, allowedCustomEmojiIds = allowedIds))
        assertEquals("這波太強 <:blobcat:111222333444> <a:party:555666777888>   1", result.trimEnd())
    }

    @Test
    fun `stripEmojis strips ZWJ, variation selectors, and tag flags while preserving Discord user, channel, and timestamp tags`() {
        val input = "找 <@123456> 去 <#654321> 看 <t:1700000000:R> 🤦‍♀️ 🏴󠁧󠁢󠁥󠁮󠁧󠁿"
        val result = EmojiSanitizer.stripEmojis(input, allowedCustomEmojiIds = setOf("999")).trim()

        assertFalse(EmojiSanitizer.containsEmoji(result, allowedCustomEmojiIds = setOf("999")))
        assertEquals("找 <@123456> 去 <#654321> 看 <t:1700000000:R>", result)
    }
}
