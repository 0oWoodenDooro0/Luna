package dev.socialpeek.resolver.youtube

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer
import kotlinx.serialization.json.*
import java.time.Duration

class YouTubeResolver(
    private val apiKey: String? = System.getenv("YOUTUBE_API_KEY"),
) : PlatformResolver {
    override val platform: Platform = Platform.YOUTUBE

    private val youtubeUrlPattern =
        Regex(
            """(?:https?://)?(?:(?:www\.|m\.)?youtube\.com/(?:watch\?v=|shorts/|embed/|live/)|youtu\.be/)([a-zA-Z0-9_-]{11})""",
            RegexOption.IGNORE_CASE,
        )

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override fun canResolve(url: String): Boolean = youtubeUrlPattern.containsMatchIn(url)

    override suspend fun resolve(
        url: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val match =
            youtubeUrlPattern.find(url)
                ?: throw ParsingException(url, "Could not extract YouTube video ID from URL")
        val videoId = match.groupValues[1]

        val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
        val cleanUrl = UrlSanitizer.clean(url, Platform.YOUTUBE)
        val maxResThumbnail = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        val videoMedia =
            Media.Video(
                url = canonicalUrl,
                previewUrl = maxResThumbnail,
            )

        val key = apiKey
        if (!key.isNullOrBlank()) {
            val dataApiResult = runCatching { resolveViaDataApi(videoId, key, client) }.getOrNull()
            if (dataApiResult != null) {
                return dataApiResult.copy(
                    originalUrl = url,
                    cleanUrl = cleanUrl,
                    media = listOf(videoMedia),
                )
            }
        }

        return resolveViaOembed(videoId, url, cleanUrl, canonicalUrl, videoMedia, client)
    }

    private suspend fun resolveViaDataApi(
        videoId: String,
        key: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val videoUrl = "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=$videoId&key=$key"
        val videoResponseText = client.get(videoUrl)
        val rootObj = json.parseToJsonElement(videoResponseText).jsonObject
        val items = rootObj["items"]?.jsonArray ?: JsonArray(emptyList())
        if (items.isEmpty()) {
            throw PostNotFoundException("https://www.youtube.com/watch?v=$videoId", "Video not found via YouTube Data API")
        }

        val firstItem = items[0].jsonObject
        val snippet = firstItem["snippet"]?.jsonObject ?: JsonObject(emptyMap())
        val contentDetails = firstItem["contentDetails"]?.jsonObject ?: JsonObject(emptyMap())

        val title = snippet["title"]?.jsonPrimitive?.contentOrNull ?: "YouTube Video"
        val rawDescription = snippet["description"]?.jsonPrimitive?.contentOrNull
        val sanitizedDescription = YouTubeDescriptionSanitizer.sanitize(rawDescription)
        val content = sanitizedDescription ?: title
        val channelTitle = snippet["channelTitle"]?.jsonPrimitive?.contentOrNull ?: "YouTube Creator"
        val channelId = snippet["channelId"]?.jsonPrimitive?.contentOrNull
        val authorUrl = channelId?.let { "https://www.youtube.com/channel/$it" }

        val author =
            Author(
                username = channelTitle,
                displayName = channelTitle,
                profileUrl = authorUrl,
            )

        val durationIso = contentDetails["duration"]?.jsonPrimitive?.contentOrNull
        val durationSeconds =
            durationIso?.let {
                runCatching { Duration.parse(it).seconds.toString() }.getOrNull()
            }

        val rawData =
            buildMap {
                durationSeconds?.let { put("durationSeconds", it) }
                durationIso?.let { put("durationIso", it) }
                sanitizedDescription?.let { put("description", it) }
            }

        val comments = runCatching { fetchComments(videoId, key, client) }.getOrDefault(emptyList())

        return PeekPost(
            platform = Platform.YOUTUBE,
            id = videoId,
            originalUrl = "https://www.youtube.com/watch?v=$videoId",
            cleanUrl = "https://www.youtube.com/watch?v=$videoId",
            author = author,
            title = title,
            content = content,
            comments = comments,
            rawData = rawData,
        )
    }

    private suspend fun fetchComments(
        videoId: String,
        key: String,
        client: SocialPeekHttpClient,
    ): List<Comment> {
        val commentsUrl =
            "https://www.googleapis.com/youtube/v3/commentThreads?part=snippet&videoId=$videoId&order=relevance&maxResults=25&textFormat=plainText&key=$key"
        val responseText = client.get(commentsUrl)
        val rootObj = json.parseToJsonElement(responseText).jsonObject
        val items = rootObj["items"]?.jsonArray ?: return emptyList()

        return items.mapNotNull { item ->
            val topLevel =
                item.jsonObject["snippet"]
                    ?.jsonObject
                    ?.get("topLevelComment")
                    ?.jsonObject
                    ?.get("snippet")
                    ?.jsonObject
                    ?: return@mapNotNull null

            val author = topLevel["authorDisplayName"]?.jsonPrimitive?.contentOrNull ?: "Viewer"
            val text = topLevel["textDisplay"]?.jsonPrimitive?.contentOrNull ?: ""
            val likes = topLevel["likeCount"]?.jsonPrimitive?.longOrNull ?: 0L

            Comment(
                author = author,
                content = text,
                score = likes,
            )
        }
    }

    private suspend fun resolveViaOembed(
        videoId: String,
        originalUrl: String,
        cleanUrl: String,
        canonicalUrl: String,
        videoMedia: Media.Video,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val oembedUrl = "https://www.youtube.com/oembed?url=$canonicalUrl&format=json"

        val responseText =
            try {
                client.get(oembedUrl)
            } catch (e: PostNotFoundException) {
                throw e
            } catch (e: Exception) {
                throw PostNotFoundException(originalUrl, e.message)
            }

        val rootObj =
            try {
                json.parseToJsonElement(responseText).jsonObject
            } catch (e: Exception) {
                throw ParsingException(originalUrl, "Failed to parse YouTube oEmbed JSON response", e)
            }

        val title = rootObj["title"]?.jsonPrimitive?.contentOrNull ?: "YouTube Video"
        val authorName = rootObj["author_name"]?.jsonPrimitive?.contentOrNull ?: "YouTube Creator"
        val authorUrl = rootObj["author_url"]?.jsonPrimitive?.contentOrNull

        val handleMatch = Regex("""/@([a-zA-Z0-9_.-]+)""").find(authorUrl ?: "")
        val username = handleMatch?.groupValues?.get(1) ?: authorName

        val author =
            Author(
                username = username,
                displayName = authorName,
                profileUrl = authorUrl,
            )

        return PeekPost(
            platform = Platform.YOUTUBE,
            id = videoId,
            originalUrl = originalUrl,
            cleanUrl = cleanUrl,
            author = author,
            title = title,
            content = title,
            media = listOf(videoMedia),
        )
    }
}
