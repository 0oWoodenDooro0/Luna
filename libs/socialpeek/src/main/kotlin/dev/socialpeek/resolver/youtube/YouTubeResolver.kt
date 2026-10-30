package dev.socialpeek.resolver.youtube

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer
import kotlinx.serialization.json.*

class YouTubeResolver : PlatformResolver {

    override val platform: Platform = Platform.YOUTUBE

    private val youtubeUrlPattern = Regex(
        """(?:https?://)?(?:(?:www\.|m\.)?youtube\.com/(?:watch\?v=|shorts/|embed/|live/)|youtu\.be/)([a-zA-Z0-9_-]{11})""",
        RegexOption.IGNORE_CASE
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun canResolve(url: String): Boolean {
        return youtubeUrlPattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        val match = youtubeUrlPattern.find(url)
            ?: throw ParsingException(url, "Could not extract YouTube video ID from URL")
        val videoId = match.groupValues[1]

        val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
        val oembedUrl = "https://www.youtube.com/oembed?url=$canonicalUrl&format=json"

        val responseText = try {
            client.get(oembedUrl)
        } catch (e: PostNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw PostNotFoundException(url, e.message)
        }

        val rootObj = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            throw ParsingException(url, "Failed to parse YouTube oEmbed JSON response", e)
        }

        val title = rootObj["title"]?.jsonPrimitive?.contentOrNull ?: "YouTube Video"
        val authorName = rootObj["author_name"]?.jsonPrimitive?.contentOrNull ?: "YouTube Creator"
        val authorUrl = rootObj["author_url"]?.jsonPrimitive?.contentOrNull
        
        // Extract handle / username from authorUrl: e.g. "https://www.youtube.com/@channel" -> "channel"
        val handleMatch = Regex("""/@([a-zA-Z0-9_.-]+)""").find(authorUrl ?: "")
        val username = handleMatch?.groupValues?.get(1) ?: authorName

        val author = Author(
            username = username,
            displayName = authorName,
            profileUrl = authorUrl
        )

        val maxResThumbnail = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

        val videoMedia = Media.Video(
            url = canonicalUrl,
            previewUrl = maxResThumbnail
        )

        return PeekPost(
            platform = Platform.YOUTUBE,
            id = videoId,
            originalUrl = url,
            cleanUrl = UrlSanitizer.clean(url, Platform.YOUTUBE),
            author = author,
            title = title,
            content = title,
            media = listOf(videoMedia)
        )
    }
}
