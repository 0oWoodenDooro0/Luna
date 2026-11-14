package luna.core.ai

import dev.socialpeek.SocialPeekClient
import dev.socialpeek.model.Platform
import io.ktor.http.Url
import org.slf4j.LoggerFactory

/**
 * Classification categories for HTTP(S) URLs extracted from Discord conversation context.
 */
enum class ContextUrlCategory {
    SOCIAL,
    YOUTUBE,
    DISCORD_CDN,
    WEB,
}

/**
 * Ordered, deduplicated URLs extracted from conversation context and grouped by downstream pipeline handling.
 *
 * @property socialUrls SocialPeek-supported social media post URLs (excluding YouTube).
 * @property youtubeUrls YouTube video/channel/music URLs handled via YouTube metadata + Gemini Video Understanding.
 * @property discordCdnUrls Discord CDN media attachment URLs handled via multimodal image attachments.
 * @property webUrls General public web URLs eligible for Gemini `url_context`, capped at [ContextUrlClassifier.MAX_WEB_URLS].
 * @property allOriginalUrls All distinct valid URLs extracted from context in chronological order (uncapped), useful for citation filtering.
 */
data class ClassifiedContextUrls(
    val socialUrls: List<String> = emptyList(),
    val youtubeUrls: List<String> = emptyList(),
    val discordCdnUrls: List<String> = emptyList(),
    val webUrls: List<String> = emptyList(),
    val allOriginalUrls: List<String> = emptyList(),
)

/**
 * Extracts, normalizes, deduplicates, and classifies `http(s)://` URLs from Discord message transcripts
 * into SocialPeek social links, YouTube links, Discord CDN media links, and general public web URLs.
 */
object ContextUrlClassifier {
    /** Maximum number of general web URLs accepted per Gemini `url_context` request. */
    const val MAX_WEB_URLS = 20

    /** Regex matching raw `http(s)://` URL tokens in message text before trailing-punctuation trimming. */
    val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    private val logger = LoggerFactory.getLogger(ContextUrlClassifier::class.java)
    private val socialPeekClient: SocialPeekClient by lazy { SocialPeekFactory.buildClient() }

    private val TRAILING_PUNCTUATION_CHARS =
        setOf(
            '.',
            ',',
            ';',
            ':',
            '!',
            '?',
            '\'',
            '"',
            '`',
            '}',
            '>',
            '。',
            '，',
            '、',
            '；',
            '：',
            '！',
            '？',
            '」',
            '』',
            '）',
            '】',
            '》',
        )

    private val DISCORD_CDN_HOSTS =
        setOf(
            "cdn.discordapp.com",
            "media.discordapp.net",
        )

    private val SCHEME_PREFIX_REGEX = Regex("""^https?://""", RegexOption.IGNORE_CASE)

    /**
     * Extracts and classifies all valid HTTP(S) URLs from a single [text] block in order of appearance.
     */
    fun classify(
        text: String,
        capturedAttachmentUrls: Collection<String> = emptySet(),
    ): ClassifiedContextUrls =
        if (text.isBlank()) {
            ClassifiedContextUrls()
        } else {
            classify(listOf(text), capturedAttachmentUrls)
        }

    /**
     * Extracts and classifies all valid HTTP(S) URLs across chronological [messages],
     * deduplicating via [normalizeUrl] and capping [ClassifiedContextUrls.webUrls] at [MAX_WEB_URLS].
     */
    fun classify(
        messages: Iterable<String>,
        capturedAttachmentUrls: Collection<String> = emptySet(),
    ): ClassifiedContextUrls {
        val seenNormalized = HashSet<String>()
        val normalizedCapturedAttachments =
            capturedAttachmentUrls.mapNotNullTo(HashSet()) { normalizeUrl(it) }
        val allOriginalUrls = mutableListOf<String>()
        val socialUrls = mutableListOf<String>()
        val youtubeUrls = mutableListOf<String>()
        val discordCdnUrls = mutableListOf<String>()
        val webUrls = mutableListOf<String>()
        var droppedWebUrlCount = 0

        for (message in messages) {
            if (message.isBlank()) continue
            for (url in extractUrls(message)) {
                val normalized = normalizeUrl(url) ?: continue
                if (!seenNormalized.add(normalized)) continue

                allOriginalUrls.add(url)
                when (classifyNormalizedUrl(url, normalized, normalizedCapturedAttachments)) {
                    ContextUrlCategory.SOCIAL -> {
                        socialUrls.add(url)
                    }

                    ContextUrlCategory.YOUTUBE -> {
                        youtubeUrls.add(url)
                    }

                    ContextUrlCategory.DISCORD_CDN -> {
                        discordCdnUrls.add(url)
                    }

                    ContextUrlCategory.WEB -> {
                        if (webUrls.size < MAX_WEB_URLS) {
                            webUrls.add(url)
                        } else {
                            droppedWebUrlCount++
                        }
                    }

                    null -> {
                        Unit
                    }
                }
            }
        }

        if (droppedWebUrlCount > 0) {
            logger.debug(
                "Capped context webUrls at MAX_WEB_URLS={} (omitted {} additional web URL(s) from url_context)",
                MAX_WEB_URLS,
                droppedWebUrlCount,
            )
        }

        return ClassifiedContextUrls(
            socialUrls = socialUrls,
            youtubeUrls = youtubeUrls,
            discordCdnUrls = discordCdnUrls,
            webUrls = webUrls,
            allOriginalUrls = allOriginalUrls,
        )
    }

    /**
     * Extracts valid `http(s)://` URLs from [text] in order of appearance, stripping trailing punctuation
     * while preserving balanced parentheses/brackets.
     */
    fun extractUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return URL_REGEX
            .findAll(text)
            .mapNotNull { match ->
                val cleaned = trimTrailingPunctuation(match.value)
                if (parseValidHttpUrl(cleaned) != null) {
                    cleaned
                } else {
                    logger.debug("Ignored malformed HTTP(S) URL token during extraction: '{}'", cleaned)
                    null
                }
            }.toList()
    }

    /**
     * Classifies a single [url] into a [ContextUrlCategory], or returns `null` if malformed/non-HTTP(S).
     */
    fun classifyUrl(
        url: String,
        capturedAttachmentUrls: Collection<String> = emptySet(),
    ): ContextUrlCategory? {
        val cleaned = trimTrailingPunctuation(url.trim())
        val normalized = normalizeUrl(cleaned) ?: return null
        val normalizedCapturedAttachments =
            capturedAttachmentUrls.mapNotNullTo(HashSet()) { normalizeUrl(it) }
        return classifyNormalizedUrl(cleaned, normalized, normalizedCapturedAttachments)
    }

    private fun classifyNormalizedUrl(
        cleanedUrl: String,
        normalizedUrl: String,
        normalizedCapturedAttachments: Set<String>,
    ): ContextUrlCategory? {
        val parsed = parseValidHttpUrl(cleanedUrl) ?: return null
        val host = parsed.host.lowercase()

        if (host in DISCORD_CDN_HOSTS || normalizedUrl in normalizedCapturedAttachments) {
            return ContextUrlCategory.DISCORD_CDN
        }

        if (isYouTubeHost(host)) {
            return ContextUrlCategory.YOUTUBE
        }

        val resolver = socialPeekClient.findResolver(cleanedUrl)
        if (resolver != null) {
            return when (resolver.platform) {
                Platform.YOUTUBE -> ContextUrlCategory.YOUTUBE
                Platform.GENERIC -> ContextUrlCategory.WEB
                else -> ContextUrlCategory.SOCIAL
            }
        }

        return ContextUrlCategory.WEB
    }

    fun isYouTubeUrl(url: String): Boolean {
        val cleaned = trimTrailingPunctuation(url.trim())
        val parsed = parseValidHttpUrl(cleaned) ?: return false
        return isYouTubeHost(parsed.host.lowercase())
    }

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host.endsWith(".youtu.be")

    /**
     * Produces a canonical normalized representation of an HTTP(S) [url] for deduplication and
     * citation filtering (lowercased scheme/host, default port removed, trailing slash trimmed,
     * fragment stripped), or returns `null` if [url] is malformed or not HTTP(S).
     */
    fun normalizeUrl(url: String): String? {
        val cleaned = trimTrailingPunctuation(url.trim())
        val parsed = parseValidHttpUrl(cleaned) ?: return null
        val scheme = parsed.protocol.name.lowercase()
        val host = parsed.host.lowercase()
        val port = parsed.specifiedPort
        val portPart =
            if (port == 0 || port == parsed.protocol.defaultPort) {
                ""
            } else {
                ":$port"
            }
        val path = parsed.encodedPath.trimEnd('/')
        val query = parsed.encodedQuery
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        return "$scheme://$host$portPart$path$queryPart"
    }

    /**
     * Canonicalizes [rawUrl] for cross-scheme citation comparison (`http` -> `https`),
     * falling back to trimmed lowercase for non-HTTP strings.
     */
    fun normalizeUrlForComparison(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val normalized = normalizeUrl(trimmed) ?: return trimmed.lowercase().trimEnd('/')
        return if (normalized.startsWith("http://")) {
            "https://" + normalized.removePrefix("http://")
        } else {
            normalized
        }
    }

    private fun trimTrailingPunctuation(raw: String): String {
        var current = raw
        while (current.isNotEmpty()) {
            val last = current.last()
            when {
                last in TRAILING_PUNCTUATION_CHARS -> {
                    current = current.dropLast(1)
                }

                last == ')' && current.count { it == ')' } > current.count { it == '(' } -> {
                    current = current.dropLast(1)
                }

                last == ']' && current.count { it == ']' } > current.count { it == '[' } -> {
                    current = current.dropLast(1)
                }

                else -> {
                    break
                }
            }
        }
        return current
    }

    private fun parseValidHttpUrl(candidate: String): Url? {
        if (candidate.isBlank() || candidate.any { it.isWhitespace() }) return null
        if (!SCHEME_PREFIX_REGEX.containsMatchIn(candidate)) return null

        val afterScheme = candidate.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank()) return null

        val hostPort = if ('@' in authority) authority.substringAfterLast('@') else authority
        if (hostPort.isBlank()) return null

        val host =
            if (hostPort.startsWith("[")) {
                val closeIdx = hostPort.indexOf(']')
                if (closeIdx <= 1) return null
                val portPart = hostPort.substring(closeIdx + 1)
                if (portPart.isNotEmpty()) {
                    if (!portPart.startsWith(":")) return null
                    val portNum = portPart.substring(1).toIntOrNull() ?: return null
                    if (portNum !in 1..65535) return null
                }
                hostPort.substring(0, closeIdx + 1)
            } else {
                val colonCount = hostPort.count { it == ':' }
                if (colonCount > 1) return null
                if (colonCount == 1) {
                    val portStr = hostPort.substringAfter(':')
                    val portNum = portStr.toIntOrNull() ?: return null
                    if (portNum !in 1..65535) return null
                }
                hostPort.substringBefore(':')
            }

        if (host.isBlank() || host.startsWith('.') || host.endsWith('.') || ".." in host) {
            return null
        }

        val parsed = runCatching { Url(candidate) }.getOrNull() ?: return null
        val scheme = parsed.protocol.name.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (parsed.host.isBlank()) return null
        return parsed
    }
}
