package luna.core.ai

/**
 * Utility to detect and strip Unicode emojis and unverified Discord custom emoji tokens from text.
 */
object EmojiSanitizer {
    private val CUSTOM_EMOJI_TOKEN_REGEX = Regex("""<(a?):([A-Za-z0-9_~-]+):(\d+)>""")

    /**
     * Determines whether the given text contains any Unicode emoji character or unverified Discord custom emoji token.
     */
    @JvmOverloads
    fun containsEmoji(
        text: String,
        allowedCustomEmojiIds: Set<String> = emptySet(),
    ): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (isEmojiCodePoint(codePoint)) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return CUSTOM_EMOJI_TOKEN_REGEX.findAll(text).any { match ->
            match.groupValues[3] !in allowedCustomEmojiIds
        }
    }

    /**
     * Removes all Unicode emoji characters and any Discord custom emoji tokens (`<:name:id>` / `<a:name:id>`)
     * whose IDs are not present in [allowedCustomEmojiIds].
     */
    @JvmOverloads
    fun stripEmojis(
        text: String,
        allowedCustomEmojiIds: Set<String> = emptySet(),
    ): String {
        val customFiltered =
            CUSTOM_EMOJI_TOKEN_REGEX.replace(text) { match ->
                if (match.groupValues[3] in allowedCustomEmojiIds) {
                    match.value
                } else {
                    ""
                }
            }
        val sb = StringBuilder(customFiltered.length)
        var i = 0
        while (i < customFiltered.length) {
            val codePoint = customFiltered.codePointAt(i)
            if (!isEmojiCodePoint(codePoint)) {
                sb.appendCodePoint(codePoint)
            }
            i += Character.charCount(codePoint)
        }
        return sb.toString()
    }

    /**
     * Checks if a Unicode code point falls into any emoji, pictograph, variation selector, or emoji joiner range.
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
            in 0x1F170..0x1F2FF, // Enclosed Alphanumeric/Ideographic Supplement & Regional Indicators
            in 0x23E9..0x23FA, // Media control & clock symbols
            in 0x2B05..0x2B07, // Directional arrow emojis
            in 0xE0020..0xE007F, // Tag characters for subdivision flags
            0x1F004, // Mahjong Red Dragon
            0x1F0CF, // Joker
            0x200D, // Zero Width Joiner
            0x20E3, // Combining Enclosing Keycap
            0x231A, // Watch
            0x231B, // Hourglass
            0x25AA, // Black Small Square
            0x25AB, // White Small Square
            0x25B6, // Play Button
            0x25C0, // Reverse Button
            in 0x25FB..0x25FE, // Medium Squares
            0x2B1B, // Black Large Square
            0x2B1C, // White Large Square
            0x2B50, // Star
            0x2B55, // Hollow Red Circle
            -> true

            else -> false
        }
}
