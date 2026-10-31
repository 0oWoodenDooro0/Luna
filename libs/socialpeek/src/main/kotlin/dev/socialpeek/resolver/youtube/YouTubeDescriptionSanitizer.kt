package dev.socialpeek.resolver.youtube

import dev.socialpeek.util.UrlSanitizer
import io.ktor.http.*

object YouTubeDescriptionSanitizer {
    const val MAX_DESCRIPTION_LENGTH = 1000

    private val URL_REGEX =
        Regex("""https?://[^\s<>"{}|\\^`]+""", RegexOption.IGNORE_CASE)

    private val PROMOTIONAL_OR_SOCIAL_HOSTS =
        setOf(
            "twitter.com",
            "x.com",
            "instagram.com",
            "facebook.com",
            "fb.com",
            "fb.watch",
            "fb.me",
            "threads.net",
            "tiktok.com",
            "discord.gg",
            "discord.com",
            "t.me",
            "telegram.me",
            "linkedin.com",
            "twitch.tv",
            "patreon.com",
            "buymeacoffee.com",
            "ko-fi.com",
            "subscribestar.com",
            "gumroad.com",
            "amzn.to",
            "amzn.com",
            "bit.ly",
            "tinyurl.com",
            "t.co",
            "cutt.ly",
            "is.gd",
            "buff.ly",
            "ow.ly",
            "linktr.ee",
            "beacons.ai",
        )

    private val PROMOTIONAL_PATH_OR_QUERY_REGEX =
        Regex(
            """(?:[?&](?:tag|aff|affiliate|affiliate_id|ref|referral|click_id|discount)=|/affiliate/|/ref/|/sponsor/)""",
            RegexOption.IGNORE_CASE,
        )

    private val YOUTUBE_PROMOTIONAL_PATH_REGEX =
        Regex("""^/(?:@[a-zA-Z0-9_.-]+|channel/|c/|user/|sub_confirmation)""", RegexOption.IGNORE_CASE)

    private val DANGLING_PROMO_PREFIX_REGEX =
        Regex(
            """^(?:(?:follow|connect|join|support|check)\s+(?:me\s+|us\s+|our\s+|my\s+)?(?:on\s+|to\s+|at\s+)?(?:twitter|x|instagram|discord|patreon|sponsor|channel)?|buy\s+my\s+book(?:\s*\(affiliate\))?|socials?|links?|twitter|x|instagram|discord|patreon|merch|sponsor|sponsors|affiliate|affiliates|shortlink\s+promo)[:\s-]*$""",
            RegexOption.IGNORE_CASE,
        )

    private val TIMESTAMP_REGEX =
        Regex("""(?:\b|\()(?:\d{1,2}:)?\d{1,2}:\d{2}(?:\b|\))""")

    private val CHAPTER_HEADER_REGEX =
        Regex("""^(?:timestamps?|chapters?|timeline|tracklist)[:\s]*$""", RegexOption.IGNORE_CASE)

    fun sanitize(description: String?): String? {
        if (description.isNullOrBlank()) return null

        val normalized = description.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.lines()
        val processedLines = mutableListOf<String>()

        for (line in lines) {
            var currentLine = line
            val urls = URL_REGEX.findAll(line).map { it.value }.toList()
            for (url in urls) {
                if (isPromotionalOrAffiliateUrl(url)) {
                    currentLine = currentLine.replace(url, "")
                } else {
                    val cleaned = sanitizeUrl(url)
                    currentLine = currentLine.replace(url, cleaned)
                }
            }
            val trimmedLine = currentLine.trim()
            if (trimmedLine.isEmpty()) {
                processedLines.add("")
                continue
            }
            if (!isChapterOrTimestampLine(trimmedLine) && DANGLING_PROMO_PREFIX_REGEX.matches(trimmedLine)) {
                continue
            }
            processedLines.add(trimmedLine)
        }

        // Collapse consecutive blank lines
        val collapsedLines = mutableListOf<String>()
        var previousWasBlank = false
        for (line in processedLines) {
            val isBlank = line.isBlank()
            if (isBlank) {
                if (!previousWasBlank && collapsedLines.isNotEmpty()) {
                    collapsedLines.add("")
                }
                previousWasBlank = true
            } else {
                collapsedLines.add(line)
                previousWasBlank = false
            }
        }
        while (collapsedLines.isNotEmpty() && collapsedLines.last().isBlank()) {
            collapsedLines.removeAt(collapsedLines.lastIndex)
        }

        if (collapsedLines.isEmpty()) {
            return null
        }

        val fullText = collapsedLines.joinToString("\n")
        if (fullText.length <= MAX_DESCRIPTION_LENGTH) {
            return fullText
        }

        return truncatePreservingTimestamps(collapsedLines)
    }

    private fun isPromotionalOrAffiliateUrl(url: String): Boolean {
        val lower = url.lowercase()
        val host =
            runCatching {
                Url(if (url.contains("://")) url else "https://$url").host.lowercase()
            }.getOrNull()

        if (host != null) {
            if (PROMOTIONAL_OR_SOCIAL_HOSTS.any { host == it || host.endsWith(".$it") }) {
                return true
            }
            if ((host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be") &&
                (YOUTUBE_PROMOTIONAL_PATH_REGEX.containsMatchIn(url) || lower.contains("sub_confirmation="))
            ) {
                return true
            }
        }

        if (PROMOTIONAL_PATH_OR_QUERY_REGEX.containsMatchIn(url)) {
            return true
        }

        return false
    }

    private fun sanitizeUrl(url: String): String =
        if (UrlSanitizer.hasTrackingParams(url)) {
            UrlSanitizer.clean(url)
        } else {
            url
        }

    private fun isChapterOrTimestampLine(line: String): Boolean = TIMESTAMP_REGEX.containsMatchIn(line) || CHAPTER_HEADER_REGEX.matches(line.trim())

    private fun truncatePreservingTimestamps(lines: List<String>): String {
        val timestampIndices = lines.indices.filter { isChapterOrTimestampLine(lines[it]) }.toSet()
        val timestampLines = lines.filterIndexed { index, _ -> index in timestampIndices }
        val timestampLength = if (timestampLines.isEmpty()) 0 else timestampLines.joinToString("\n").length

        if (timestampLines.isNotEmpty() && timestampLength >= MAX_DESCRIPTION_LENGTH) {
            val result = mutableListOf<String>()
            var currentLen = 0
            for (tLine in timestampLines) {
                val nextLen = if (result.isEmpty()) tLine.length else currentLen + 1 + tLine.length
                if (nextLen <= MAX_DESCRIPTION_LENGTH) {
                    result.add(tLine)
                    currentLen = nextLen
                } else {
                    break
                }
            }
            return result.joinToString("\n")
        }

        var remainingTimestampLength = timestampLength
        val includedLines = mutableListOf<String>()
        var currentLen = 0

        for ((index, line) in lines.withIndex()) {
            val isTimestamp = index in timestampIndices
            if (isTimestamp) {
                val nextLen = if (includedLines.isEmpty()) line.length else currentLen + 1 + line.length
                includedLines.add(line)
                currentLen = nextLen
                remainingTimestampLength -= (line.length + if (remainingTimestampLength > line.length) 1 else 0)
                if (remainingTimestampLength < 0) remainingTimestampLength = 0
            } else {
                val extraForSeparator = if (remainingTimestampLength > 0) 1 else 0
                val nextLen = if (includedLines.isEmpty()) line.length else currentLen + 1 + line.length
                if (nextLen + extraForSeparator + remainingTimestampLength <= MAX_DESCRIPTION_LENGTH) {
                    includedLines.add(line)
                    currentLen = nextLen
                } else {
                    if (remainingTimestampLength == 0) {
                        val spaceLeft = MAX_DESCRIPTION_LENGTH - (if (includedLines.isEmpty()) 0 else currentLen + 1)
                        if (spaceLeft > 20) {
                            val partial = line.take(spaceLeft).trimEnd()
                            if (partial.isNotBlank()) {
                                includedLines.add(partial)
                            }
                        }
                    }
                }
            }
        }

        return includedLines.joinToString("\n").trim()
    }
}
