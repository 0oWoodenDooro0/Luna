package dev.socialpeek.resolver.x

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer
import kotlinx.serialization.json.*

class XResolver : PlatformResolver {

    override val platform: Platform = Platform.X

    private val tweetUrlPattern = Regex(
        """https?://(?:www\.|mobile\.)?(?:twitter\.com|x\.com)/(?:[A-Za-z0-9_]+/|i/web/)?status/([0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun canResolve(url: String): Boolean {
        return tweetUrlPattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        val match = tweetUrlPattern.find(url) 
            ?: throw ParsingException(url, "Could not extract tweet ID from URL")
        val tweetId = match.groupValues[1]

        val syndicationUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=x"

        val responseText = try {
            client.get(syndicationUrl)
        } catch (e: Exception) {
            throw PostNotFoundException(url, e.message)
        }

        val rootElement = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            throw ParsingException(url, "Invalid JSON received from X syndication endpoint", e)
        }

        if (rootElement.isEmpty() || rootElement["error"] != null) {
            throw PostNotFoundException(url, rootElement["error"]?.jsonPrimitive?.contentOrNull)
        }

        val id = rootElement["id_str"]?.jsonPrimitive?.contentOrNull ?: tweetId
        val text = rootElement["text"]?.jsonPrimitive?.contentOrNull ?: ""

        val userObj = rootElement["user"]?.jsonObject
        val author = Author(
            id = userObj?.get("id_str")?.jsonPrimitive?.contentOrNull,
            username = userObj?.get("screen_name")?.jsonPrimitive?.contentOrNull ?: "unknown",
            displayName = userObj?.get("name")?.jsonPrimitive?.contentOrNull,
            avatarUrl = userObj?.get("profile_image_url_https")?.jsonPrimitive?.contentOrNull?.replace("_normal.", "_400x400."),
            profileUrl = userObj?.get("screen_name")?.jsonPrimitive?.contentOrNull?.let { "https://x.com/$it" },
            isVerified = userObj?.get("is_blue_verified")?.jsonPrimitive?.booleanOrNull 
                ?: userObj?.get("verified")?.jsonPrimitive?.booleanOrNull 
                ?: false
        )

        val mediaList = mutableListOf<Media>()

        // 1. media_extended (vxTwitter / fxTwitter rich multi-media format)
        val mediaExtended = rootElement["media_extended"]?.jsonArray
        if (mediaExtended != null && mediaExtended.isNotEmpty()) {
            mediaExtended.forEach { elem ->
                val obj = elem.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                val mediaUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                val size = obj["size"]?.jsonObject
                val width = size?.get("w")?.jsonPrimitive?.intOrNull
                val height = size?.get("h")?.jsonPrimitive?.intOrNull
                if (!mediaUrl.isNullOrBlank()) {
                    if (type == "image") {
                        mediaList.add(
                            Media.Image(
                                url = mediaUrl,
                                previewUrl = mediaUrl,
                                width = width,
                                height = height
                            )
                        )
                    } else if (type == "video" || type == "gif") {
                        val thumb = obj["thumbnail_url"]?.jsonPrimitive?.contentOrNull
                        mediaList.add(
                            Media.Video(
                                url = mediaUrl,
                                previewUrl = thumb,
                                width = width,
                                height = height
                            )
                        )
                    }
                }
            }
        }

        // 2. Photos (if media_extended wasn't present or yielded no images)
        if (mediaList.isEmpty()) {
            rootElement["photos"]?.jsonArray?.forEach { photoElem ->
                val photoUrl = if (photoElem is JsonObject) {
                    photoElem["url"]?.jsonPrimitive?.contentOrNull
                } else {
                    photoElem.jsonPrimitive.contentOrNull
                }
                val width = (photoElem as? JsonObject)?.get("width")?.jsonPrimitive?.intOrNull
                val height = (photoElem as? JsonObject)?.get("height")?.jsonPrimitive?.intOrNull
                if (!photoUrl.isNullOrBlank()) {
                    mediaList.add(
                        Media.Image(
                            url = photoUrl,
                            previewUrl = photoUrl,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        }

        // 3. Videos (if not already captured by media_extended)
        if (mediaList.none { it is Media.Video }) {
            val videoObj = rootElement["video"]?.jsonObject
            if (videoObj != null) {
                val posterUrl = videoObj["poster"]?.jsonPrimitive?.contentOrNull
                val variants = videoObj["variants"]?.jsonArray ?: JsonArray(emptyList())

                val bestVariant = variants.mapNotNull { it.jsonObject }
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "video/mp4" }
                    .maxByOrNull { it["bitrate"]?.jsonPrimitive?.longOrNull ?: 0L }

                val videoSrc = bestVariant?.get("src")?.jsonPrimitive?.contentOrNull
                    ?: variants.firstOrNull()?.jsonObject?.get("src")?.jsonPrimitive?.contentOrNull

                if (!videoSrc.isNullOrBlank()) {
                    mediaList.add(
                        Media.Video(
                            url = videoSrc,
                            previewUrl = posterUrl,
                            bitrate = bestVariant?.get("bitrate")?.jsonPrimitive?.longOrNull
                        )
                    )
                }
            }
        }

        val likes = rootElement["favorite_count"]?.jsonPrimitive?.longOrNull
        val comments = rootElement["conversation_count"]?.jsonPrimitive?.longOrNull

        val metrics = if (likes != null || comments != null) {
            Metrics(likes = likes, comments = comments)
        } else null

        return PeekPost(
            platform = Platform.X,
            id = id,
            originalUrl = url,
            cleanUrl = UrlSanitizer.clean(url, Platform.X),
            author = author,
            content = text,
            media = mediaList,
            metrics = metrics
        )
    }
}
