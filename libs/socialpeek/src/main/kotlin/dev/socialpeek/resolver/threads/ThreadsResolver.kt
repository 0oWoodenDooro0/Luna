package dev.socialpeek.resolver.threads

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.resolver.util.MetaMediaExtractor
import dev.socialpeek.util.UrlSanitizer
import io.ktor.http.*
import org.jsoup.Jsoup

class ThreadsResolver : PlatformResolver {
    override val platform: Platform = Platform.THREADS

    private val threadsUrlPattern =
        Regex(
            """https?://(?:www\.)?threads\.(?:net|com)/(?:@([a-zA-Z0-9_.-]+)/post/|t/)([a-zA-Z0-9_-]+)""",
            RegexOption.IGNORE_CASE,
        )

    private val threadsSharePattern =
        Regex(
            """https?://(?:www\.)?threads\.(?:net|com)/share/([a-zA-Z0-9_-]+)""",
            RegexOption.IGNORE_CASE,
        )

    companion object {
        val THREADS_HEADERS =
            mapOf(
                HttpHeaders.UserAgent to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            )
    }

    override fun canResolve(url: String): Boolean = threadsUrlPattern.containsMatchIn(url) || threadsSharePattern.containsMatchIn(url)

    override suspend fun resolve(
        url: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        var currentUrl = url
        if (threadsSharePattern.containsMatchIn(url)) {
            currentUrl =
                try {
                    client.resolveFinalUrl(url, THREADS_HEADERS)
                } catch (e: Exception) {
                    url
                }
        }

        val match =
            threadsUrlPattern.find(currentUrl)
                ?: throw ParsingException(currentUrl, "Could not extract Threads post ID from URL: $currentUrl")

        val urlUsername = match.groupValues[1].takeIf { it.isNotBlank() }
        val postId = match.groupValues[2]

        val targetUrl =
            if (urlUsername != null) {
                "https://www.threads.net/@$urlUsername/post/$postId"
            } else {
                "https://www.threads.net/t/$postId"
            }

        val html =
            try {
                client.get(targetUrl, THREADS_HEADERS)
            } catch (e: PostNotFoundException) {
                throw e
            } catch (e: Exception) {
                throw PostNotFoundException(url, e.message)
            }

        val doc = Jsoup.parse(html)

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val ogDescription = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val ogImages =
            doc
                .select("meta[property=og:image]")
                .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() } }
                .distinct()
        val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
        val twitterCard = doc.selectFirst("meta[name=twitter:card]")?.attr("content")
        val ogImageWidth = doc.selectFirst("meta[property=og:image:width]")?.attr("content")?.toIntOrNull()
        val ogImageHeight = doc.selectFirst("meta[property=og:image:height]")?.attr("content")?.toIntOrNull()

        if (ogTitle.isNullOrBlank() && ogDescription.isNullOrBlank() && ogImages.isEmpty()) {
            throw PostNotFoundException(url, "Threads post not found or empty response")
        }

        val (username, initialDisplayName) = extractUser(ogTitle, urlUsername)
        val postDetails = MetaMediaExtractor.extractPostDetails(html, postId, username)
        val scriptAuthor = postDetails?.author ?: MetaMediaExtractor.extractAuthor(html, username)

        var avatarUrl = scriptAuthor?.avatarUrl
        if (avatarUrl == null) {
            avatarUrl = ogImages.firstOrNull { isProfilePic(it) }
        }

        val displayName = scriptAuthor?.displayName ?: initialDisplayName ?: username
        val isVerified = scriptAuthor?.isVerified ?: false
        val content = ogDescription ?: postDetails?.content ?: ""
        val mediaList = mutableListOf<Media>()

        // 1. Try extracting rich media (single video, quote post video/images, carousel, giphy) from Meta SSR script
        if (postDetails != null && postDetails.media.isNotEmpty()) {
            mediaList.addAll(postDetails.media)
        } else {
            val carouselMedia = MetaMediaExtractor.extractCarouselMedia(html)
            if (carouselMedia.isNotEmpty()) {
                mediaList.addAll(carouselMedia)
            } else {
                // 2. Fallback to OpenGraph tags
                if (!ogVideo.isNullOrBlank()) {
                    val preview =
                        ogImages.firstOrNull {
                            !isPlatformShareCard(it, ogImageWidth, ogImageHeight, twitterCard) &&
                                !isProfilePic(it)
                        }
                    mediaList.add(
                        Media.Video(
                            url = ogVideo,
                            previewUrl = preview,
                        ),
                    )
                } else if (!twitterCard.equals("summary", ignoreCase = true)) {
                    val validImages =
                        ogImages.filter {
                            !isProfilePic(it) && !isPlatformShareCard(it, ogImageWidth, ogImageHeight, twitterCard)
                        }
                    validImages.forEachIndexed { index, imgUrl ->
                        mediaList.add(
                            Media.Image(
                                url = imgUrl,
                                previewUrl = imgUrl,
                                width = if (index == 0) ogImageWidth else null,
                                height = if (index == 0) ogImageHeight else null,
                            ),
                        )
                    }
                }
            }
        }

        val author =
            Author(
                username = username,
                displayName = displayName,
                avatarUrl = avatarUrl,
                isVerified = isVerified,
                profileUrl = "https://www.threads.net/@$username",
            )

        val cleanUrl = UrlSanitizer.clean(targetUrl, Platform.THREADS)

        return PeekPost(
            platform = Platform.THREADS,
            id = postId,
            originalUrl = url,
            cleanUrl = cleanUrl,
            author = author,
            content = content,
            media = mediaList,
            metrics = postDetails?.metrics,
            createdAtEpochSeconds = postDetails?.createdAtEpochSeconds,
        )
    }

    private fun isProfilePic(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/t51.82787-19/") ||
            lower.contains("/t51.2885-19/") ||
            lower.contains("profile_pic") ||
            lower.contains("eyj2zw5jb2rlx3rhzyi6inpvbglszv9wawm")
    }

    private fun isPlatformShareCard(
        url: String,
        width: Int?,
        height: Int?,
        twitterCard: String?,
    ): Boolean {
        val lower = url.lowercase()
        if (lower.contains("share.threads.net") || lower.contains("/t39.92108-6/")) {
            return true
        }
        if (width == 1200 && height == 628 && (lower.contains("t39.") || (lower.contains("fbcdn.net") && !lower.contains("t51.")))) {
            return true
        }
        return false
    }

    private fun extractUser(
        ogTitle: String?,
        fallbackUsername: String?,
    ): Pair<String, String?> {
        if (ogTitle.isNullOrBlank()) {
            val user = fallbackUsername ?: "unknown"
            return Pair(user, user)
        }

        // Patterns:
        // "Mark Zuckerberg (@zuck) on Threads"
        // "Name (@username) on Threads"
        val regexWithDisplay = Regex("""^(.*?)\s+\(@([a-zA-Z0-9_.-]+)\)\s+on\s+Threads""", RegexOption.IGNORE_CASE)
        val matchWithDisplay = regexWithDisplay.find(ogTitle)
        if (matchWithDisplay != null) {
            val displayName = matchWithDisplay.groupValues[1].trim()
            val username = matchWithDisplay.groupValues[2].trim()
            return Pair(username, displayName)
        }

        // "@username on Threads"
        val regexUserOnly = Regex("""^@([a-zA-Z0-9_.-]+)\s+on\s+Threads""", RegexOption.IGNORE_CASE)
        val matchUserOnly = regexUserOnly.find(ogTitle)
        if (matchUserOnly != null) {
            val username = matchUserOnly.groupValues[1].trim()
            return Pair(username, null)
        }

        // Fallback: search for (@username) anywhere in ogTitle
        val handleMatch = Regex("""\(@([a-zA-Z0-9_.-]+)\)""").find(ogTitle)
        if (handleMatch != null) {
            val username = handleMatch.groupValues[1].trim()
            val prefix = ogTitle.substringBefore(handleMatch.value).trim()
            return Pair(username, prefix.takeIf { it.isNotBlank() })
        }

        val user = fallbackUsername ?: "unknown"
        return Pair(user, ogTitle)
    }
}
