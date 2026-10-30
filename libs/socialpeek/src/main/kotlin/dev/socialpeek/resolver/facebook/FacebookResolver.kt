package dev.socialpeek.resolver.facebook

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class FacebookResolver : PlatformResolver {

    override val platform: Platform = Platform.FACEBOOK

    companion object {
        val FACEBOOK_HEADERS = mapOf(
            HttpHeaders.UserAgent to "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            HttpHeaders.AcceptLanguage to "en-US,en;q=0.9"
        )

        private val FB_DOMAINS = setOf(
            "facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com",
            "touch.facebook.com", "fb.com", "www.fb.com", "fb.watch", "fb.me"
        )

        private val POST_PATH_REGEX = Regex(
            """/(?:[^/]+/)?posts/([a-zA-Z0-9_.-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val REEL_PATH_REGEX = Regex(
            """/reel/([0-9a-zA-Z_-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val VIDEO_PATH_REGEX = Regex(
            """/(?:[^/]+/)?videos/(?:[^/]+/)?([0-9]+)""",
            RegexOption.IGNORE_CASE
        )
        private val PHOTO_PATH_REGEX = Regex(
            """/(?:[^/]+/)?photos/(?:[^/]+/)?([0-9]+)""",
            RegexOption.IGNORE_CASE
        )
        private val GROUP_POST_REGEX = Regex(
            """/groups/([^/]+)/(?:posts|permalink|multi_permalinks)/([a-zA-Z0-9_.-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val SHARE_PATH_REGEX = Regex(
            """/share/(?:[pvr]/)?([a-zA-Z0-9_-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val FB_WATCH_REGEX = Regex(
            """^https?://(?:www\.)?fb\.watch/([a-zA-Z0-9_-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val FB_SHORT_REGEX = Regex(
            """^https?://(?:www\.)?fb\.(?:com|me)/([a-zA-Z0-9_.-]+)""",
            RegexOption.IGNORE_CASE
        )

        private val METRIC_VIEWS_REGEX = Regex(
            """([\d,.]+[KMBkmb萬億]?)\s*(?:views|觀看|次觀看)""",
            RegexOption.IGNORE_CASE
        )
        private val METRIC_REACTIONS_REGEX = Regex(
            """([\d,.]+[KMBkmb萬億]?)\s*(?:reactions|likes|個心情|個讚|讚)""",
            RegexOption.IGNORE_CASE
        )
        private val METRIC_COMMENTS_REGEX = Regex(
            """([\d,.]+[KMBkmb萬億]?)\s*(?:comments|則留言|留言)""",
            RegexOption.IGNORE_CASE
        )
        private val METRIC_SHARES_REGEX = Regex(
            """([\d,.]+[KMBkmb萬億]?)\s*(?:shares|次分享|分享)""",
            RegexOption.IGNORE_CASE
        )

        private val RESERVED_SEGMENTS = setOf(
            "posts", "reel", "videos", "watch", "photos", "photo", "groups", "share",
            "permalink.php", "story.php", "photo.php", "video.php", "profile.php",
            "pages", "people", "events", "marketplace", "gaming", "login", "help"
        )
    }

    private data class OEmbedAuthor(
        val displayName: String?,
        val username: String?,
        val profileUrl: String?
    )

    override fun canResolve(url: String): Boolean {
        val parsed = runCatching { Url(if (url.contains("://")) url else "https://$url") }.getOrNull()
            ?: return false
        val host = parsed.host.lowercase()
        val isFbHost = FB_DOMAINS.contains(host) || FB_DOMAINS.any { host.endsWith(".$it") }
        if (!isFbHost) return false

        val path = parsed.encodedPath
        val query = parsed.parameters

        return when {
            FB_WATCH_REGEX.containsMatchIn(url) -> true
            FB_SHORT_REGEX.containsMatchIn(url) -> true
            POST_PATH_REGEX.containsMatchIn(path) -> true
            REEL_PATH_REGEX.containsMatchIn(path) -> true
            VIDEO_PATH_REGEX.containsMatchIn(path) -> true
            PHOTO_PATH_REGEX.containsMatchIn(path) -> true
            GROUP_POST_REGEX.containsMatchIn(path) -> true
            SHARE_PATH_REGEX.containsMatchIn(path) -> true
            path.startsWith("/watch") && (query.contains("v") || path.length > 6) -> true
            path.startsWith("/photo") && query.contains("fbid") -> true
            (path.startsWith("/permalink.php") || path.startsWith("/story.php")) && query.contains("story_fbid") -> true
            path.startsWith("/video.php") && query.contains("v") -> true
            else -> false
        }
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        var currentUrl = url

        // 1. Follow redirects for share or short links
        if (isShareOrShortUrl(url)) {
            currentUrl = try {
                client.resolveFinalUrl(url, FACEBOOK_HEADERS)
            } catch (_: Exception) {
                url
            }
        }

        val cleanTargetUrl = UrlSanitizer.clean(currentUrl, Platform.FACEBOOK)
        val extractedId = extractPostId(currentUrl) ?: extractPostId(cleanTargetUrl)

        val html = try {
            client.get(cleanTargetUrl, FACEBOOK_HEADERS)
        } catch (e: PostNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw PostNotFoundException(url, e.message)
        }

        val doc = Jsoup.parse(html)
        val oembedUrl = doc.selectFirst("link[rel=alternate][type='application/json+oembed']")?.attr("href")
        val oembedAuthor = if (!oembedUrl.isNullOrBlank()) {
            fetchOEmbedAuthor(client, oembedUrl)
        } else {
            null
        }

        return parseHtml(doc, currentUrl, cleanTargetUrl, extractedId, oembedAuthor)
    }

    private suspend fun fetchOEmbedAuthor(client: SocialPeekHttpClient, oembedUrl: String): OEmbedAuthor? {
        val oembedTarget = extractOembedAuthorFromUrl(oembedUrl)
        val jsonText = try {
            client.get(oembedUrl, FACEBOOK_HEADERS)
        } catch (_: Exception) {
            return oembedTarget
        }

        val json = try {
            Json.parseToJsonElement(jsonText).jsonObject
        } catch (_: Exception) {
            return oembedTarget
        }

        // Try to get author name and url from html field
        val html = json["html"]?.jsonPrimitive?.contentOrNull
        if (!html.isNullOrBlank()) {
            val oembedDoc = Jsoup.parse(html)
            val blockquote = oembedDoc.selectFirst("blockquote")
            val authorLink = blockquote?.select("a")?.last()
            if (authorLink != null) {
                val authorUrl = authorLink.attr("href").takeIf { it.isNotBlank() }?.trimEnd('/')
                val displayName = authorLink.text().takeIf { it.isNotBlank() }
                val username = (authorUrl?.let { extractUsernameFromUrl(it) }) ?: oembedTarget?.username
                if (displayName != null || username != null) {
                    return OEmbedAuthor(
                        displayName = displayName ?: username,
                        username = username,
                        profileUrl = authorUrl ?: username?.let { "https://www.facebook.com/$it" }
                    )
                }
            }
        }

        val authorName = json["author_name"]?.jsonPrimitive?.contentOrNull
        val authorUrl = json["author_url"]?.jsonPrimitive?.contentOrNull
        if (!authorName.isNullOrBlank() || !authorUrl.isNullOrBlank()) {
            val username = (authorUrl?.let { extractUsernameFromUrl(it) }) ?: oembedTarget?.username
            return OEmbedAuthor(
                displayName = authorName ?: username,
                username = username,
                profileUrl = authorUrl ?: username?.let { "https://www.facebook.com/$it" }
            )
        }

        return oembedTarget
    }

    private fun extractOembedAuthorFromUrl(oembedUrl: String): OEmbedAuthor? {
        val parsed = runCatching { Url(oembedUrl) }.getOrNull() ?: return null
        val targetUrl = parsed.parameters["url"] ?: return null
        val decodedTarget = runCatching { java.net.URLDecoder.decode(targetUrl, "UTF-8") }.getOrDefault(targetUrl)
        val username = extractUsernameFromUrl(decodedTarget) ?: return null
        return OEmbedAuthor(
            displayName = username,
            username = username,
            profileUrl = "https://www.facebook.com/$username"
        )
    }

    private fun isShareOrShortUrl(url: String): Boolean {
        return FB_WATCH_REGEX.containsMatchIn(url) ||
                FB_SHORT_REGEX.containsMatchIn(url) ||
                url.contains("/share/")
    }

    internal fun extractPostId(url: String): String? {
        val parsed = runCatching { Url(if (url.contains("://")) url else "https://$url") }.getOrNull()
            ?: return null

        // 1. Check query parameters first
        parsed.parameters["story_fbid"]?.takeIf { it.isNotBlank() }?.let { return it }
        parsed.parameters["fbid"]?.takeIf { it.isNotBlank() }?.let { return it }
        parsed.parameters["v"]?.takeIf { it.isNotBlank() }?.let { return it }

        val path = parsed.encodedPath

        // 2. Check path patterns
        GROUP_POST_REGEX.find(path)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.let { return it }
        POST_PATH_REGEX.find(path)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        REEL_PATH_REGEX.find(path)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        VIDEO_PATH_REGEX.find(path)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        PHOTO_PATH_REGEX.find(path)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        SHARE_PATH_REGEX.find(path)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        FB_WATCH_REGEX.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        FB_SHORT_REGEX.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private fun extractCommunity(url: String, ogTitle: String?): String? {
        val groupMatch = GROUP_POST_REGEX.find(url)
        if (groupMatch != null) {
            return groupMatch.groupValues[1]
        }
        if (ogTitle != null && ogTitle.contains(" in ") && ogTitle.contains("| Facebook")) {
            val beforePipe = ogTitle.substringBefore("| Facebook").trim()
            val parts = beforePipe.split(" in ")
            if (parts.size >= 2) {
                return parts[1].trim()
            }
        }
        return null
    }

    private fun parseHtml(
        doc: Document,
        originalUrl: String,
        cleanUrl: String,
        fallbackId: String?,
        oembedAuthor: OEmbedAuthor? = null
    ): PeekPost {
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("meta[name=twitter:title]")?.attr("content")
        val ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val ogType = doc.selectFirst("meta[property=og:type]")?.attr("content")
        val ogUrl = doc.selectFirst("meta[property=og:url]")?.attr("content")
        val canonicalHref = doc.selectFirst("link[rel=canonical]")?.attr("href")

        val ogImages = doc.select("meta[property=og:image], meta[name=twitter:image]")
            .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() } }
            .distinct()
        val ogVideo = doc.selectFirst("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")
            ?.attr("content")

        val pageTitle = doc.title()

        // Detect not found or private
        val isEmptyOg = ogTitle.isNullOrBlank() && ogDescription.isNullOrBlank() && ogImages.isEmpty()
        val isGenericTitle = (ogTitle.equals("Facebook", ignoreCase = true) || pageTitle.equals("Facebook", ignoreCase = true)) &&
                ogDescription.isNullOrBlank() && ogImages.isEmpty()
        val isLoginBlocked = pageTitle.contains("Log in", ignoreCase = true) ||
                pageTitle.contains("Log In", ignoreCase = true) ||
                pageTitle.contains("登入")

        if (isEmptyOg || isGenericTitle || (isLoginBlocked && isEmptyOg)) {
            throw PostNotFoundException(originalUrl, "Facebook post not found, private, or removed")
        }

        // Post ID
        val postId = (ogUrl?.let { extractPostId(it) })
            ?: fallbackId
            ?: (canonicalHref?.let { extractPostId(it) })
            ?: "unknown"

        val finalCleanUrl = when {
            !ogUrl.isNullOrBlank() && !ogUrl.contains("/share/") -> UrlSanitizer.clean(ogUrl, Platform.FACEBOOK)
            !canonicalHref.isNullOrBlank() && !canonicalHref.contains("/share/") -> UrlSanitizer.clean(canonicalHref, Platform.FACEBOOK)
            else -> cleanUrl
        }

        // Author & Title extraction
        val authorFromMeta = doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it != "Facebook" }
        val community = extractCommunity(finalCleanUrl, ogTitle)
        val (parsedDisplayName, title, extractedAuthor) = parseTitleAndAuthor(ogTitle, authorFromMeta, originalUrl)

        val displayName = oembedAuthor?.displayName
            ?: (if (parsedDisplayName != "Facebook User") parsedDisplayName else (oembedAuthor?.username ?: "Facebook User"))

        val authorUsername = oembedAuthor?.username
            ?: extractUsernameFromUrl(finalCleanUrl)
            ?: extractUsernameFromUrl(ogUrl ?: "")
            ?: extractedAuthor
            ?: displayName.takeIf { it != "Facebook User" }?.lowercase()?.replace(Regex("""[^a-zA-Z0-9_.-]"""), "")
            ?: ""

        val profileUrl = oembedAuthor?.profileUrl?.trimEnd('/')
            ?: authorUsername.takeIf { it.isNotBlank() }?.let { "https://www.facebook.com/$it" }

        // Content
        val content = ogDescription ?: title ?: ""

        // Metrics
        val metricsText = "$ogTitle ${ogDescription.orEmpty()}"
        val metrics = parseMetrics(metricsText)

        // Media
        val mediaList = mutableListOf<Media>()
        val isVideoPost = !ogVideo.isNullOrBlank() ||
                ogType.equals("video.other", ignoreCase = true) ||
                finalCleanUrl.contains("/reel/") ||
                finalCleanUrl.contains("/watch") ||
                finalCleanUrl.contains("/videos/")

        val previewImage = ogImages.firstOrNull { !isPlaceholder(it) }

        if (isVideoPost) {
            val videoUrl = ogVideo ?: finalCleanUrl
            val videoWidth = doc.selectFirst("meta[property=og:video:width]")?.attr("content")?.toIntOrNull()
            val videoHeight = doc.selectFirst("meta[property=og:video:height]")?.attr("content")?.toIntOrNull()
            mediaList.add(
                Media.Video(
                    url = videoUrl,
                    previewUrl = previewImage,
                    width = videoWidth,
                    height = videoHeight
                )
            )
        } else {
            val validImages = ogImages.filter { !isPlaceholder(it) }
            val imgWidth = doc.selectFirst("meta[property=og:image:width]")?.attr("content")?.toIntOrNull()
            val imgHeight = doc.selectFirst("meta[property=og:image:height]")?.attr("content")?.toIntOrNull()

            validImages.forEachIndexed { index, imgUrl ->
                mediaList.add(
                    Media.Image(
                        url = imgUrl,
                        previewUrl = imgUrl,
                        width = if (index == 0) imgWidth else null,
                        height = if (index == 0) imgHeight else null
                    )
                )
            }
        }

        val author = Author(
            username = authorUsername,
            displayName = displayName,
            avatarUrl = previewImage?.takeIf { !isVideoPost && (it.contains("/profile_pic") || it.contains("crawler/media/?media_id=")) },
            profileUrl = profileUrl
        )

        return PeekPost(
            platform = Platform.FACEBOOK,
            id = postId,
            originalUrl = originalUrl,
            cleanUrl = finalCleanUrl,
            author = author,
            content = content,
            title = title,
            media = mediaList,
            metrics = metrics,
            community = community
        )
    }

    private fun parseTitleAndAuthor(
        ogTitle: String?,
        authorMeta: String?,
        url: String
    ): Triple<String, String?, String?> {
        if (ogTitle.isNullOrBlank()) {
            val name = authorMeta ?: "Facebook User"
            return Triple(name, null, null)
        }

        var cleaned = ogTitle.trim()
        if (cleaned.endsWith("| Facebook", ignoreCase = true)) {
            cleaned = cleaned.substringBeforeLast("| Facebook").trim()
        }

        // Pattern 1: Video views/reactions prefix: "2.8M views · 1.2K reactions | Video Title"
        if (cleaned.contains(" | ")) {
            val parts = cleaned.split(" | ")
            val titlePart = parts.last().trim()
            val cleanTitle = titlePart.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: titlePart
            val name = authorMeta ?: "Facebook User"
            return Triple(name, cleanTitle.takeIf { it.isNotBlank() }, null)
        }

        // Pattern 2: "Author Name in GroupName"
        if (cleaned.contains(" in ")) {
            val author = cleaned.substringBefore(" in ").trim()
            return Triple(author, null, null)
        }

        // Pattern 3: "Author Name - Post Content Snippet"
        if (cleaned.contains(" - ")) {
            val author = cleaned.substringBefore(" - ").trim()
            val snippet = cleaned.substringAfter(" - ").trim()
            return Triple(author, snippet.takeIf { it.isNotBlank() }, null)
        }

        // Pattern 4: Plain Name
        val name = authorMeta ?: cleaned
        return Triple(name, null, null)
    }

    private fun extractUsernameFromUrl(url: String): String? {
        val parsed = runCatching { Url(if (url.contains("://")) url else "https://$url") }.getOrNull()
            ?: return null
        val segments = parsed.encodedPath.split("/").filter { it.isNotBlank() }
        if (segments.isNotEmpty()) {
            val first = segments[0]
            if (!RESERVED_SEGMENTS.contains(first.lowercase()) && !first.startsWith("pfbid")) {
                return first
            }
        }
        return null
    }

    private fun isPlaceholder(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".ico") ||
                lower.contains("static.xx.fbcdn.net/rsrc.php") && lower.endsWith(".gif") ||
                lower.contains("blank.gif") ||
                lower.contains("spacer.gif")
    }

    private fun parseMetrics(text: String): Metrics? {
        val views = METRIC_VIEWS_REGEX.find(text)?.groupValues?.get(1)?.let { parseMetricNumber(it) }
        val reactions = METRIC_REACTIONS_REGEX.find(text)?.groupValues?.get(1)?.let { parseMetricNumber(it) }
        val comments = METRIC_COMMENTS_REGEX.find(text)?.groupValues?.get(1)?.let { parseMetricNumber(it) }
        val shares = METRIC_SHARES_REGEX.find(text)?.groupValues?.get(1)?.let { parseMetricNumber(it) }

        if (views == null && reactions == null && comments == null && shares == null) {
            return null
        }

        return Metrics(
            views = views,
            likes = reactions,
            comments = comments,
            reposts = shares
        )
    }

    private fun parseMetricNumber(raw: String): Long? {
        val cleaned = raw.replace(",", "").trim()
        val lower = cleaned.lowercase()

        val multiplier = when {
            lower.endsWith("b") -> 1_000_000_000.0
            lower.endsWith("m") -> 1_000_000.0
            lower.endsWith("k") -> 1_000.0
            lower.endsWith("億") -> 100_000_000.0
            lower.endsWith("萬") -> 10_000.0
            else -> 1.0
        }

        val numStr = cleaned.filter { it.isDigit() || it == '.' }
        val value = numStr.toDoubleOrNull() ?: return null
        return (value * multiplier).toLong()
    }
}
