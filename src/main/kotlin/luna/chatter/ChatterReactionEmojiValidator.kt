package luna.chatter

import dev.kord.core.entity.ReactionEmoji
import luna.core.ai.EmojiSanitizer

/**
 * Validates and normalizes LLM reaction-selection output into a single valid Unicode emoji
 * or verified Discord guild custom emoji (`<:name:id>` / `<a:name:id>`).
 */
object ChatterReactionEmojiValidator {
    const val NO_REACTION_SENTINEL: String = "NONE"
    private const val VARIATION_SELECTOR_16 = 0xFE0F
    private const val VARIATION_SELECTOR_15 = 0xFE0E
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
    private const val TAG_BASE_BLACK_FLAG = 0x1F3F4
    private const val TAG_CANCEL = 0xE007F

    private val CUSTOM_EMOJI_REGEX = Regex("""^<(a?):([A-Za-z0-9_~-]+):(\d+)>$""")

    /**
     * Returns `true` when the reaction selection model explicitly declined to react using [NO_REACTION_SENTINEL].
     */
    fun isExplicitNoReaction(raw: String?): Boolean = raw?.trim()?.equals(NO_REACTION_SENTINEL, ignoreCase = true) == true

    /**
     * Validates [raw] and returns either the trimmed single Unicode emoji or the canonical verified
     * guild custom emoji mention (`<:name:id>` / `<a:name:id>`), or `null` if invalid/unverified.
     */
    fun validateAndNormalize(
        raw: String?,
        guildEmojis: List<GuildCustomEmoji> = emptyList(),
    ): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val customMatch = CUSTOM_EMOJI_REGEX.matchEntire(trimmed)
        if (customMatch != null) {
            val name = customMatch.groupValues[2]
            val id = customMatch.groupValues[3]
            val matched =
                guildEmojis.firstOrNull { emoji ->
                    emoji.id.toString() == id && emoji.name == name
                } ?: return null
            return matched.mention
        }

        return if (isSingleUnicodeEmoji(trimmed)) trimmed else null
    }

    /**
     * Converts a raw or normalized reaction emoji string into a Kord [ReactionEmoji] when valid and verified.
     */
    fun toReactionEmoji(
        raw: String?,
        guildEmojis: List<GuildCustomEmoji> = emptyList(),
    ): ReactionEmoji? {
        val normalized = validateAndNormalize(raw, guildEmojis) ?: return null
        val customMatch = CUSTOM_EMOJI_REGEX.matchEntire(normalized)
        if (customMatch != null) {
            val name = customMatch.groupValues[2]
            val id = customMatch.groupValues[3]
            return guildEmojis
                .firstOrNull { it.id.toString() == id && it.name == name }
                ?.toReactionEmoji()
        }
        return ReactionEmoji.Unicode(normalized)
    }

    internal fun isSingleUnicodeEmoji(text: String): Boolean {
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        val codePoints = text.codePoints().toArray()
        if (codePoints.isEmpty()) return false

        // 1. Flag sequence: exactly two regional indicator symbols
        if (codePoints.size == 2 && codePoints.all(::isRegionalIndicator)) {
            return true
        }
        if (codePoints.any(::isRegionalIndicator)) {
            return false
        }

        // 2. Keycap sequence: [0-9#*] + optional FE0F + 20E3
        if (isKeycapSequence(codePoints)) {
            return true
        }

        // 3. Tag flag sequence (e.g., England/Scotland/Wales): 1F3F4 + tag chars (E0061..E007A) + E007F
        if (isTagFlagSequence(codePoints)) {
            return true
        }

        // 4. Standard / skin-tone / ZWJ emoji sequence: component (ZWJ component)*
        return isSingleEmojiComponentSequence(codePoints)
    }

    private fun isKeycapSequence(codePoints: IntArray): Boolean {
        val base = codePoints[0]
        if (base !in '0'.code..'9'.code && base != '#'.code && base != '*'.code) {
            return false
        }
        return when (codePoints.size) {
            2 -> codePoints[1] == COMBINING_ENCLOSING_KEYCAP
            3 -> codePoints[1] == VARIATION_SELECTOR_16 && codePoints[2] == COMBINING_ENCLOSING_KEYCAP
            else -> false
        }
    }

    private fun isTagFlagSequence(codePoints: IntArray): Boolean {
        if (codePoints.size < 4) return false
        if (codePoints[0] != TAG_BASE_BLACK_FLAG) return false
        if (codePoints.last() != TAG_CANCEL) return false
        for (i in 1 until codePoints.size - 1) {
            if (codePoints[i] !in 0xE0020..0xE007E) return false
        }
        return true
    }

    private fun isSingleEmojiComponentSequence(codePoints: IntArray): Boolean {
        var idx = 0
        var componentCount = 0

        while (idx < codePoints.size) {
            val base = codePoints[idx]
            val requiresVs16 = isEmojiOnlyWithVs16(base)
            if (!isBaseEmojiCodePoint(base) && !requiresVs16) {
                return false
            }
            idx++
            componentCount++

            var hasVs16 = false
            var hasModifier = false
            while (idx < codePoints.size) {
                val cp = codePoints[idx]
                when {
                    (cp == VARIATION_SELECTOR_16 || cp == VARIATION_SELECTOR_15) && !hasVs16 -> {
                        hasVs16 = cp == VARIATION_SELECTOR_16
                        idx++
                    }

                    isSkinToneModifier(cp) && !hasModifier -> {
                        hasModifier = true
                        idx++
                    }

                    else -> {
                        break
                    }
                }
            }

            if (requiresVs16 && !hasVs16) {
                return false
            }

            if (idx < codePoints.size) {
                if (codePoints[idx] != ZERO_WIDTH_JOINER) {
                    return false
                }
                idx++
                // Trailing ZWJ with no following component is invalid
                if (idx >= codePoints.size) {
                    return false
                }
            }
        }

        return componentCount >= 1
    }

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun isSkinToneModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    private fun isEmojiOnlyWithVs16(cp: Int): Boolean = cp == 0x00A9 || cp == 0x00AE || cp in 0x2194..0x2199 || cp in 0x21A9..0x21AA

    private fun isBaseEmojiCodePoint(cp: Int): Boolean =
        EmojiSanitizer.isBaseEmojiCodePoint(cp) ||
            when (cp) {
                0x203C,
                0x2049,
                0x2122,
                0x2139,
                0x2328,
                0x23CF,
                0x24C2,
                0x2934,
                0x2935,
                0x3030,
                0x303D,
                0x3297,
                0x3299,
                -> true

                else -> false
            }
}
