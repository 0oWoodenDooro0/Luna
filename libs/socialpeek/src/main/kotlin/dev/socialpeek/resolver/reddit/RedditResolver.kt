package dev.socialpeek.resolver.reddit

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.KtorSocialPeekHttpClient
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class RedditResolver : PlatformResolver {

    override val platform: Platform = Platform.REDDIT

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val redditCommentsPattern = Regex(
        """https?://(?:(?:www\.|old\.|new\.)?reddit\.com/(?:r/([a-zA-Z0-9_]+)/)?comments/|redd\.it/)([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    private val redditSharePattern = Regex(
        """https?://(?:(?:www\.|old\.|new\.)?reddit\.com/(?:r/[a-zA-Z0-9_]+/)?s/|redd\.it/s/)([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    companion object {
        val REDDIT_HEADERS = mapOf(
            HttpHeaders.UserAgent to KtorSocialPeekHttpClient.BOT_USER_AGENT
        )

        private val JsonElement?.asJsonObject: JsonObject? get() = this as? JsonObject
        private val JsonElement?.asJsonArray: JsonArray? get() = this as? JsonArray
        private val JsonElement?.asJsonPrimitive: JsonPrimitive? get() = this as? JsonPrimitive
    }

    override fun canResolve(url: String): Boolean {
        return redditCommentsPattern.containsMatchIn(url) || redditSharePattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        var currentUrl = url

        // Handle short share links if matched
        if (redditSharePattern.containsMatchIn(url) && !redditCommentsPattern.containsMatchIn(url)) {
            currentUrl = try {
                client.resolveFinalUrl(url, REDDIT_HEADERS)
            } catch (e: Exception) {
                url
            }
        }

        val (urlSubreddit, postId) = extractSubredditAndPostId(currentUrl)
            ?: throw ParsingException(currentUrl, "Could not extract Reddit post ID from URL: $currentUrl")

        val canonicalPathUrl = if (urlSubreddit != null) {
            "https://www.reddit.com/r/$urlSubreddit/comments/$postId/"
        } else {
            "https://www.reddit.com/comments/$postId/"
        }

        // 1. Primary: Try direct JSON API via api.reddit.com or www.reddit.com
        val jsonUrls = listOfNotNull(
            if (urlSubreddit != null) "https://api.reddit.com/r/$urlSubreddit/comments/$postId" else null,
            "https://api.reddit.com/comments/$postId",
            "https://www.reddit.com/comments/$postId.json"
        )
        for (jsonUrl in jsonUrls) {
            try {
                val responseText = client.get(jsonUrl, REDDIT_HEADERS)
                return parseFromJson(responseText, postId, originalUrl = url, currentUrl = currentUrl, client = client)
            } catch (_: Exception) {
                // Try next endpoint
            }
        }

        // 2. Fallback: oEmbed + Bot HTML scraping
        return resolveFallback(postId, originalUrl = url, currentUrl = currentUrl, canonicalPathUrl = canonicalPathUrl, urlSubreddit = urlSubreddit, client = client)
    }

    private fun extractSubredditAndPostId(url: String): Pair<String?, String>? {
        val commentsMatch = redditCommentsPattern.find(url)
        if (commentsMatch != null) {
            val sub = commentsMatch.groupValues[1].takeIf { it.isNotBlank() }
            val id = commentsMatch.groupValues[2]
            return sub to id
        }
        val reddItMatch = Regex("""https?://redd\.it/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE).find(url)
        if (reddItMatch != null) {
            return null to reddItMatch.groupValues[1]
        }
        val shareMatch = redditSharePattern.find(url)
        if (shareMatch != null) {
            return null to shareMatch.groupValues[1]
        }
        return null
    }

    private suspend fun parseFromJson(
        jsonText: String,
        fallbackPostId: String,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient
    ): PeekPost {
        val root = try {
            json.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw ParsingException(originalUrl, "Failed to parse Reddit JSON response", e)
        }

        val jsonArray = root as? JsonArray
            ?: throw PostNotFoundException(originalUrl, "Unexpected Reddit API format")

        if (jsonArray.isEmpty()) {
            throw PostNotFoundException(originalUrl, "Empty response from Reddit API")
        }

        val listing = jsonArray[0].asJsonObject
        val children = listing?.get("data")?.asJsonObject?.get("children")?.asJsonArray
        if (children.isNullOrEmpty()) {
            throw PostNotFoundException(originalUrl, "Post not found in Reddit listing")
        }

        val postData = children[0].asJsonObject?.get("data")?.asJsonObject
            ?: throw PostNotFoundException(originalUrl, "Post data missing")

        val id = postData["id"]?.asJsonPrimitive?.contentOrNull ?: fallbackPostId
        val authorName = postData["author"]?.asJsonPrimitive?.contentOrNull ?: "unknown"
        val authorFullname = postData["author_fullname"]?.asJsonPrimitive?.contentOrNull
        val title = postData["title"]?.asJsonPrimitive?.contentOrNull ?: "Reddit Post"
        val selftext = postData["selftext"]?.asJsonPrimitive?.contentOrNull ?: ""
        val subreddit = postData["subreddit"]?.asJsonPrimitive?.contentOrNull
        val ups = postData["ups"]?.asJsonPrimitive?.longOrNull
        val numComments = postData["num_comments"]?.asJsonPrimitive?.longOrNull
        val createdUtc = postData["created_utc"]?.asJsonPrimitive?.doubleOrNull?.toLong()
        val permalink = postData["permalink"]?.asJsonPrimitive?.contentOrNull

        val communityIcon = subreddit?.let { fetchSubredditIcon(it, client) }

        val author = Author(
            id = authorFullname,
            username = authorName,
            displayName = "u/$authorName",
            profileUrl = "https://www.reddit.com/user/$authorName"
        )

        val mediaList = mutableListOf<Media>()

        // Check for video (Reddit video or rich:video)
        val isVideo = postData["is_video"]?.asJsonPrimitive?.booleanOrNull ?: false
        val mediaObj = postData["media"]?.asJsonObject
        val secureMediaObj = postData["secure_media"]?.asJsonObject
        val redditVideo = mediaObj?.get("reddit_video")?.asJsonObject
        val secureMediaVideo = secureMediaObj?.get("reddit_video")?.asJsonObject
        val videoObj = redditVideo ?: secureMediaVideo

        if (isVideo && videoObj != null) {
            val fallbackUrl = videoObj["fallback_url"]?.asJsonPrimitive?.contentOrNull
            val hlsUrl = videoObj["hls_url"]?.asJsonPrimitive?.contentOrNull
            val duration = videoObj["duration"]?.asJsonPrimitive?.doubleOrNull
            val width = videoObj["width"]?.asJsonPrimitive?.intOrNull
            val height = videoObj["height"]?.asJsonPrimitive?.intOrNull

            val previewImages = postData["preview"]?.asJsonObject?.get("images")?.asJsonArray
            val previewSource = previewImages?.firstOrNull()?.asJsonObject?.get("source")?.asJsonObject
            val previewUrl = previewSource?.get("url")?.asJsonPrimitive?.contentOrNull?.replace("&amp;", "&")

            val videoUrl = fallbackUrl ?: hlsUrl
            if (!videoUrl.isNullOrBlank()) {
                mediaList.add(
                    Media.Video(
                        url = videoUrl,
                        previewUrl = previewUrl,
                        durationSeconds = duration,
                        width = width,
                        height = height
                    )
                )
            }
        } else {
            // Check for gallery / multi-image
            val isGallery = postData["is_gallery"]?.asJsonPrimitive?.booleanOrNull ?: false
            val galleryData = postData["gallery_data"]?.asJsonObject?.get("items")?.asJsonArray
            val mediaMetadata = postData["media_metadata"]?.asJsonObject

            if (mediaMetadata != null && (isGallery || galleryData != null || mediaMetadata.isNotEmpty())) {
                val mediaIds = galleryData?.mapNotNull { it.asJsonObject?.get("media_id")?.asJsonPrimitive?.contentOrNull }
                    ?: mediaMetadata.keys.toList()

                mediaIds.forEach { mediaId ->
                    val meta = mediaMetadata[mediaId]?.asJsonObject
                    val s = meta?.get("s")?.asJsonObject
                    val imgUrl = s?.get("u")?.asJsonPrimitive?.contentOrNull?.replace("&amp;", "&")
                        ?: s?.get("gif")?.asJsonPrimitive?.contentOrNull?.replace("&amp;", "&")
                    val width = s?.get("x")?.asJsonPrimitive?.intOrNull
                    val height = s?.get("y")?.asJsonPrimitive?.intOrNull

                    if (!imgUrl.isNullOrBlank() && !isPlatformPreviewOrAsset(imgUrl)) {
                        mediaList.add(
                            Media.Image(
                                url = imgUrl,
                                previewUrl = imgUrl,
                                width = width,
                                height = height
                            )
                        )
                    }
                }
            }

            // If no gallery images found, check single image
            if (mediaList.isEmpty()) {
                val postUrl = postData["url"]?.asJsonPrimitive?.contentOrNull
                val previewImages = postData["preview"]?.asJsonObject?.get("images")?.asJsonArray
                val firstPreview = previewImages?.firstOrNull()?.asJsonObject?.get("source")?.asJsonObject

                if (postUrl != null && !isPlatformPreviewOrAsset(postUrl) && (
                        postUrl.contains("i.redd.it") ||
                        postUrl.contains("preview.redd.it") ||
                        postUrl.endsWith(".jpg") || postUrl.endsWith(".jpeg") ||
                        postUrl.endsWith(".png") || postUrl.endsWith(".webp")
                    )) {
                    val width = firstPreview?.get("width")?.asJsonPrimitive?.intOrNull
                    val height = firstPreview?.get("height")?.asJsonPrimitive?.intOrNull
                    mediaList.add(
                        Media.Image(
                            url = postUrl,
                            previewUrl = firstPreview?.get("url")?.asJsonPrimitive?.contentOrNull?.replace("&amp;", "&") ?: postUrl,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        }

        val rawMap = mutableMapOf<String, String>()
        if (subreddit != null) {
            rawMap["community"] = subreddit
        }
        if (communityIcon != null) {
            rawMap["community_icon"] = communityIcon
        }

        val targetUrl = if (currentUrl != originalUrl) currentUrl else (if (!permalink.isNullOrBlank()) "https://www.reddit.com$permalink" else originalUrl)
        val cleanUrl = UrlSanitizer.clean(targetUrl, Platform.REDDIT)

        return PeekPost(
            platform = Platform.REDDIT,
            id = id,
            originalUrl = originalUrl,
            cleanUrl = cleanUrl,
            author = author,
            title = title,
            content = selftext,
            media = mediaList,
            metrics = if (ups != null || numComments != null) Metrics(likes = ups, comments = numComments) else null,
            createdAtEpochSeconds = createdUtc,
            community = subreddit,
            communityIcon = communityIcon,
            rawData = rawMap
        )
    }

    private suspend fun resolveFallback(
        postId: String,
        originalUrl: String,
        currentUrl: String,
        canonicalPathUrl: String,
        urlSubreddit: String?,
        client: SocialPeekHttpClient
    ): PeekPost {
        // 1. Try oEmbed
        var oembedTitle: String? = null
        var authorName: String? = null
        var foundSubreddit: String? = initialSubreddit(urlSubreddit)
        val oembedUrl = "https://www.reddit.com/oembed?url=$canonicalPathUrl"
        try {
            val oembedText = client.get(oembedUrl, REDDIT_HEADERS)
            val oembedJson = json.parseToJsonElement(oembedText).asJsonObject
            oembedTitle = oembedJson?.get("title")?.asJsonPrimitive?.contentOrNull
            authorName = oembedJson?.get("author_name")?.asJsonPrimitive?.contentOrNull

            val html = oembedJson?.get("html")?.asJsonPrimitive?.contentOrNull
            if (html != null && foundSubreddit == null) {
                val subMatch = Regex("""/r/([a-zA-Z0-9_]+)/""").find(html)
                if (subMatch != null) {
                    foundSubreddit = subMatch.groupValues[1]
                }
            }
        } catch (_: Exception) {
            // Ignore oEmbed failure
        }

        // 2. Try HTML scraping
        var scrapedTitle: String? = null
        var scrapedContent: String = ""
        var scrapedVotes: Long? = null
        var scrapedComments: Long? = null
        val scrapedImages = mutableListOf<String>()
        var scrapedVideo: String? = null
        var scrapedCommunityIcon: String? = null

        try {
            val html = client.get(canonicalPathUrl, REDDIT_HEADERS)
            val doc = Jsoup.parse(html)

            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            if (!ogTitle.isNullOrBlank()) {
                val subMatch = Regex("""From the ([a-zA-Z0-9_]+) community on Reddit""", RegexOption.IGNORE_CASE).find(ogTitle)
                if (subMatch != null && foundSubreddit == null) {
                    foundSubreddit = subMatch.groupValues[1]
                }
                scrapedTitle = ogTitle.replace(Regex("""^From the \w+ community on Reddit:\s*"""), "")
            } else {
                scrapedTitle = doc.selectFirst("title")?.text()
            }

            // Subreddit extraction fallback
            if (foundSubreddit == null) {
                val subEl = doc.selectFirst("meta[name=twitter:creator]") ?: doc.selectFirst("a[href^=/r/]")
                val subText = subEl?.attr("content") ?: subEl?.attr("href") ?: ""
                val subMatch = Regex("""/r/([a-zA-Z0-9_]+)""").find(subText)
                if (subMatch != null) {
                    foundSubreddit = subMatch.groupValues[1]
                }
            }

            val metaDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            if (!metaDesc.isNullOrBlank()) {
                val descRegex = Regex("""^(?:([0-9,]+)\s*votes?,\s*)?(?:([0-9,]+)\s*comments?\.\s*)?(.*)$""", RegexOption.DOT_MATCHES_ALL)
                val descMatch = descRegex.find(metaDesc)
                if (descMatch != null) {
                    val votesStr = descMatch.groupValues[1].replace(",", "").trim()
                    val commentsStr = descMatch.groupValues[2].replace(",", "").trim()
                    val body = descMatch.groupValues[3].trim()

                    if (votesStr.isNotBlank()) scrapedVotes = votesStr.toLongOrNull()
                    if (commentsStr.isNotBlank()) scrapedComments = commentsStr.toLongOrNull()
                    scrapedContent = body
                } else {
                    scrapedContent = metaDesc
                }
            }

            val ogImages = doc.select("meta[property=og:image]")
                .mapNotNull { it.attr("content").takeIf { c -> c.isNotBlank() && !isPlatformPreviewOrAsset(c) } }
            scrapedImages.addAll(ogImages)

            scrapedVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:video:url]")?.attr("content")

            val communityIconEl = doc.selectFirst("img[src*=\"communityIcon\"], img.shreddit-subreddit-icon__icon")
            scrapedCommunityIcon = communityIconEl?.attr("src")?.replace("&amp;", "&")
        } catch (_: Exception) {
            // Ignore HTML scrape failure if oEmbed succeeded
        }

        val finalTitle = oembedTitle ?: scrapedTitle
        val finalAuthorName = authorName ?: "reddit_user"

        if (scrapedTitle == null && oembedTitle == null) {
            throw PostNotFoundException(canonicalPathUrl, "Reddit post not found or could not be resolved: $canonicalPathUrl")
        }

        val author = Author(
            username = finalAuthorName,
            displayName = "u/$finalAuthorName",
            profileUrl = "https://www.reddit.com/user/$finalAuthorName"
        )

        val mediaList = mutableListOf<Media>()
        if (!scrapedVideo.isNullOrBlank()) {
            mediaList.add(
                Media.Video(
                    url = scrapedVideo,
                    previewUrl = scrapedImages.firstOrNull()
                )
            )
        } else {
            scrapedImages.distinct().forEach { imgUrl ->
                mediaList.add(
                    Media.Image(
                        url = imgUrl,
                        previewUrl = imgUrl
                    )
                )
            }
        }

        val communityIcon = foundSubreddit?.let { fetchSubredditIcon(it, client) } ?: scrapedCommunityIcon

        val rawMap = mutableMapOf<String, String>()
        if (foundSubreddit != null) {
            rawMap["community"] = foundSubreddit
        }
        if (communityIcon != null) {
            rawMap["community_icon"] = communityIcon
        }

        val cleanUrl = UrlSanitizer.clean(currentUrl, Platform.REDDIT)

        return PeekPost(
            platform = Platform.REDDIT,
            id = postId,
            originalUrl = originalUrl,
            cleanUrl = cleanUrl,
            author = author,
            title = finalTitle ?: "Reddit Post",
            content = scrapedContent,
            media = mediaList,
            metrics = if (scrapedVotes != null || scrapedComments != null) {
                Metrics(likes = scrapedVotes, comments = scrapedComments)
            } else null,
            community = foundSubreddit,
            communityIcon = communityIcon,
            rawData = rawMap
        )
    }

    private fun initialSubreddit(subreddit: String?): String? = subreddit

    private suspend fun fetchSubredditIcon(subreddit: String, client: SocialPeekHttpClient): String? {
        return try {
            val aboutUrl = "https://api.reddit.com/r/$subreddit/about"
            val responseText = client.get(aboutUrl, REDDIT_HEADERS)
            val root = json.parseToJsonElement(responseText).asJsonObject
            val dataObj = root?.get("data")?.asJsonObject
            val icon = dataObj?.get("community_icon")?.asJsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: dataObj?.get("icon_img")?.asJsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            icon?.replace("&amp;", "&")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isPlatformPreviewOrAsset(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("share.redd.it/preview/post/") ||
                lower.contains("redditstatic.com") ||
                lower.contains("redditinc.com") ||
                lower.contains("/shreddit/assets/") ||
                lower.contains("styles/communityicon") ||
                lower.contains("styles/bannerbackgroundimage") ||
                lower.contains("styles/profileicon")
    }
}
