package luna.summarizer

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
}
