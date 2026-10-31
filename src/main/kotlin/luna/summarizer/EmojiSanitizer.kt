package luna.summarizer

/**
 * Utility to detect and strip emoji characters from text.
 */
object EmojiSanitizer {
    /**
     * Determines whether the given text contains any emoji character.
     */
    fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (isEmojiCodePoint(codePoint)) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * Removes all emoji characters from the given text.
     */
    fun stripEmojis(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (!isEmojiCodePoint(codePoint)) {
                sb.appendCodePoint(codePoint)
            }
            i += Character.charCount(codePoint)
        }
        return sb.toString()
    }

    /**
     * Checks if a Unicode code point falls into any emoji or pictograph range.
     */
    fun isEmojiCodePoint(cp: Int): Boolean =
        when (cp) {
            in 0x1F600..0x1F64F, // Emoticons
            in 0x1F300..0x1F5FF, // Misc Symbols and Pictographs
            in 0x1F680..0x1F6FF, // Transport and Map
            in 0x1F900..0x1F9FF, // Supplemental Symbols and Pictographs
            in 0x1FA00..0x1FAFF, // Symbols and Pictographs Extended-A
            in 0x2600..0x26FF, // Misc Symbols
            in 0x2700..0x27BF, // Dingbats
            in 0xFE00..0xFE0F, // Variation Selectors
            in 0x1F1E6..0x1F1FF, // Regional indicator symbols (Flags)
            -> true

            else -> false
        }
}
