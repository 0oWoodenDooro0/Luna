package luna.chatter.emoji

/**
 * Parsed Discord custom emoji token (`<:name:id>` or `<a:name:id>`).
 */
data class CustomEmojiToken(
    val id: String,
    val name: String,
    val isAnimated: Boolean,
    val mention: String,
)

/**
 * Extracts Discord custom emoji tokens and annotates dialogue transcripts inline with cached meanings.
 */
object ChatterEmojiTranscriptAnnotator {
    private val CUSTOM_EMOJI_TOKEN_REGEX = Regex("""<(a?):([A-Za-z0-9_~-]+):(\d+)>""")

    fun extractEmojiTokens(text: String): List<CustomEmojiToken> {
        if (text.isEmpty()) return emptyList()
        return CUSTOM_EMOJI_TOKEN_REGEX
            .findAll(text)
            .map { match ->
                CustomEmojiToken(
                    id = match.groupValues[3],
                    name = match.groupValues[2],
                    isAnimated = match.groupValues[1] == "a",
                    mention = match.value,
                )
            }.toList()
    }

    /**
     * Annotates every custom emoji token in [transcript] that has a non-blank entry in [meaningsById]
     * inline as `<:name:id>(meaning)` or `<a:name:id>(meaning)`. Uncached tokens remain unmodified.
     */
    fun annotate(
        transcript: String,
        meaningsById: Map<String, String>,
    ): String {
        if (transcript.isEmpty() || meaningsById.isEmpty()) return transcript
        return CUSTOM_EMOJI_TOKEN_REGEX.replace(transcript) { match ->
            val emojiId = match.groupValues[3]
            val meaning = meaningsById[emojiId]?.trim()?.takeIf { it.isNotEmpty() }
            if (meaning != null) {
                "${match.value}($meaning)"
            } else {
                match.value
            }
        }
    }
}
