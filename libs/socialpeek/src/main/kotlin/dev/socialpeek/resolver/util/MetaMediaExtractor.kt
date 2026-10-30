package dev.socialpeek.resolver.util

import dev.socialpeek.model.Author
import dev.socialpeek.model.Media
import dev.socialpeek.model.Metrics
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

data class MetaPostDetails(
    val media: List<Media> = emptyList(),
    val author: Author? = null,
    val content: String? = null,
    val createdAtEpochSeconds: Long? = null,
    val metrics: Metrics? = null,
)

object MetaMediaExtractor {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extracts rich post details (media, author, content, timestamp, metrics) from Meta (Threads/Instagram) SSR scripts.
     */
    fun extractPostDetails(
        html: String,
        targetPostId: String? = null,
        fallbackUsername: String? = null,
    ): MetaPostDetails? {
        try {
            val doc = Jsoup.parse(html)
            val scripts = doc.getElementsByTag("script")

            val candidateJsonElements = mutableListOf<JsonElement>()

            for (script in scripts) {
                val content = script.data().takeIf { it.isNotBlank() } ?: script.html()
                if (content.isBlank()) continue

                val hasMediaIndicator =
                    content.contains("\"video_versions\"") ||
                        content.contains("\"carousel_media\"") ||
                        content.contains("\"quoted_attachment_post\"") ||
                        content.contains("\"giphy_media_info\"") ||
                        content.contains("\"image_versions2\"") ||
                        content.contains("\"RelayPrefetchedStreamCache\"") ||
                        (targetPostId != null && content.contains(targetPostId))

                if (!hasMediaIndicator) continue

                val element =
                    runCatching { json.parseToJsonElement(content) }.getOrNull()
                        ?: runCatching {
                            val firstBrace = content.indexOf('{')
                            if (firstBrace != -1) {
                                val slice = extractBalancedBracket(content, firstBrace, '{', '}')
                                if (slice != null) json.parseToJsonElement(slice) else null
                            } else {
                                null
                            }
                        }.getOrNull()

                if (element != null) {
                    candidateJsonElements.add(element)
                }
            }

            var targetPostNode: JsonObject? = null

            // Pass 1: find post matching targetPostId
            if (targetPostId != null) {
                for (elem in candidateJsonElements) {
                    val found = findPostById(elem, targetPostId)
                    if (found != null) {
                        targetPostNode = found
                        break
                    }
                }
            }

            // Pass 2: constrained fallback to target query media node or post container
            if (targetPostNode == null) {
                for (elem in candidateJsonElements) {
                    val found = findFallbackPostObject(elem)
                    if (found != null) {
                        targetPostNode = found
                        break
                    }
                }
            }

            if (targetPostNode == null) return null

            // Extract author
            val userObj = targetPostNode["user"] as? JsonObject
            val author =
                if (userObj != null) {
                    val username =
                        (userObj["username"] as? JsonPrimitive)?.contentOrNull
                            ?: fallbackUsername
                            ?: "unknown"
                    val displayName =
                        (userObj["full_name"] as? JsonPrimitive)
                            ?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: username
                    val avatarUrl =
                        (userObj["profile_pic_url"] as? JsonPrimitive)
                            ?.contentOrNull
                            ?.replace("\\/", "/")
                    val isVerified = (userObj["is_verified"] as? JsonPrimitive)?.booleanOrNull ?: false
                    Author(
                        username = username,
                        displayName = displayName,
                        avatarUrl = avatarUrl,
                        isVerified = isVerified,
                        profileUrl = null,
                    )
                } else {
                    null
                }

            // Extract content
            val captionObj = targetPostNode["caption"] as? JsonObject
            val contentText = (captionObj?.get("text") as? JsonPrimitive)?.contentOrNull

            // Extract timestamp
            val createdAt = (targetPostNode["taken_at"] as? JsonPrimitive)?.longOrNull

            // Extract metrics
            val likeCount = (targetPostNode["like_count"] as? JsonPrimitive)?.longOrNull
            val textPostAppInfo = targetPostNode["text_post_app_info"] as? JsonObject
            val directReplyCount = (textPostAppInfo?.get("direct_reply_count") as? JsonPrimitive)?.longOrNull
            val repostCount = (textPostAppInfo?.get("repost_count") as? JsonPrimitive)?.longOrNull

            val metrics =
                if (likeCount != null || directReplyCount != null || repostCount != null) {
                    Metrics(
                        likes = likeCount,
                        comments = directReplyCount,
                        reposts = repostCount,
                    )
                } else {
                    null
                }

            // Extract media
            val mediaList = extractMediaFromPostNode(targetPostNode)

            return MetaPostDetails(
                media = mediaList,
                author = author,
                content = contentText,
                createdAtEpochSeconds = createdAt,
                metrics = metrics,
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Extracts all carousel media (images and videos) from Meta (Instagram/Threads) SSR HTML scripts.
     */
    fun extractCarouselMedia(html: String): List<Media> {
        try {
            val doc = Jsoup.parse(html)
            val scripts = doc.getElementsByTag("script")
            for (script in scripts) {
                val content = script.data().takeIf { it.isNotBlank() } ?: script.html()
                if (!content.contains("\"carousel_media\"") && !content.contains("\"edge_sidecar_to_children\"")) continue

                // 1. Try finding "carousel_media" via balanced JSON array
                if (content.contains("\"carousel_media\"")) {
                    val array = extractBalancedJsonArray(content, "carousel_media")
                    if (array != null && array.isNotEmpty()) {
                        val items = parseCarouselItems(array)
                        if (items.isNotEmpty()) return items
                    }
                }

                // 2. Try finding "edge_sidecar_to_children" (Instagram web GraphQL)
                if (content.contains("\"edge_sidecar_to_children\"")) {
                    val array = extractSidecarItems(content)
                    if (array.isNotEmpty()) return array
                }

                // 3. Fallback: Parse whole script as JSON
                try {
                    val element = json.parseToJsonElement(content)
                    val carouselArray = findKeyInJson(element, "carousel_media") as? JsonArray
                    if (carouselArray != null && carouselArray.isNotEmpty()) {
                        val items = parseCarouselItems(carouselArray)
                        if (items.isNotEmpty()) return items
                    }
                } catch (_: Exception) {
                    // Ignore parse errors
                }
            }
        } catch (_: Exception) {
            // Ignore any extraction error
        }
        return emptyList()
    }

    /**
     * Extracts author information from Meta (Instagram/Threads) SSR HTML scripts.
     */
    fun extractAuthor(
        html: String,
        fallbackUsername: String? = null,
    ): Author? {
        try {
            val doc = Jsoup.parse(html)
            val scripts = doc.getElementsByTag("script")
            for (script in scripts) {
                val content = script.data().takeIf { it.isNotBlank() } ?: script.html()
                if (!content.contains("\"profile_pic_url\"")) continue
                try {
                    val element = json.parseToJsonElement(content)
                    val userObj = findUserObject(element, fallbackUsername)
                    if (userObj != null) {
                        val username =
                            (userObj["username"] as? JsonPrimitive)?.contentOrNull
                                ?: fallbackUsername
                                ?: "unknown"
                        val displayName =
                            (userObj["full_name"] as? JsonPrimitive)
                                ?.contentOrNull
                                ?.takeIf { it.isNotBlank() } ?: username
                        val avatarUrl =
                            (userObj["profile_pic_url"] as? JsonPrimitive)
                                ?.contentOrNull
                                ?.replace("\\/", "/")
                        val isVerified = (userObj["is_verified"] as? JsonPrimitive)?.booleanOrNull ?: false

                        return Author(
                            username = username,
                            displayName = displayName,
                            avatarUrl = avatarUrl,
                            isVerified = isVerified,
                            profileUrl = null,
                        )
                    }
                } catch (_: Exception) {
                    // Ignore
                }
            }
        } catch (_: Exception) {
            // Ignore
        }
        return null
    }

    private fun isPostObject(obj: JsonObject): Boolean =
        obj.containsKey("user") ||
            obj.containsKey("caption") ||
            obj.containsKey("text_post_app_info") ||
            obj.containsKey("media_type") ||
            obj.containsKey("carousel_media")

    private fun findJsonObject(
        element: JsonElement,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject? {
        if (element is JsonObject) {
            if (predicate(element)) return element
            for (v in element.values) {
                val found = findJsonObject(v, predicate)
                if (found != null) return found
            }
        } else if (element is JsonArray) {
            for (item in element) {
                val found = findJsonObject(item, predicate)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findPostById(
        element: JsonElement,
        targetId: String,
    ): JsonObject? {
        return findJsonObject(element) { obj ->
            if (!isPostObject(obj)) return@findJsonObject false
            val code = (obj["code"] as? JsonPrimitive)?.contentOrNull
            val shortcode = (obj["shortcode"] as? JsonPrimitive)?.contentOrNull
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            val pk = (obj["pk"] as? JsonPrimitive)?.contentOrNull
            code == targetId || shortcode == targetId || id == targetId || pk == targetId
        }
    }

    private fun findFallbackPostObject(element: JsonElement): JsonObject? {
        // Constrained fallback: prefer target query media node, or container with carousel/video/giphy
        val targetQueryPost =
            findJsonObject(element) { obj ->
                val media = obj["media"] as? JsonObject
                media != null && isPostObject(media)
            }?.get("media") as? JsonObject

        if (targetQueryPost != null) return targetQueryPost

        return findJsonObject(element) { obj ->
            isPostObject(obj) && (
                obj.containsKey("carousel_media") ||
                    obj.containsKey("video_versions") ||
                    obj.containsKey("giphy_media_info")
            )
        }
    }

    private fun extractMediaFromPostNode(postNode: JsonObject): List<Media> {
        // 1. carousel_media
        val carouselMedia = postNode["carousel_media"] as? JsonArray
        if (carouselMedia != null && carouselMedia.isNotEmpty()) {
            val items =
                carouselMedia.mapNotNull { item ->
                    (item as? JsonObject)?.let { extractSingleMedia(it) }
                }
            if (items.isNotEmpty()) return items
        }

        // 2. direct media on the post node
        val directMedia = extractSingleMedia(postNode)
        if (directMedia != null) {
            return listOf(directMedia)
        }

        // 3. quoted / shared post media
        val textPostAppInfo = postNode["text_post_app_info"] as? JsonObject
        val shareInfo = textPostAppInfo?.get("share_info") as? JsonObject
        if (shareInfo != null) {
            val quotedKeys = listOf("quoted_attachment_post", "quoted_post", "reposted_post")
            for (key in quotedKeys) {
                val quoted = shareInfo[key] as? JsonObject ?: continue
                val quotedMedia = extractMediaFromPostNode(quoted)
                if (quotedMedia.isNotEmpty()) return quotedMedia
            }
        }

        return emptyList()
    }

    private data class ImageCandidate(
        val url: String,
        val width: Int?,
        val height: Int?,
    )

    private fun extractBestCandidate(imageVersions2: JsonElement?): ImageCandidate? {
        val candidates = (imageVersions2 as? JsonObject)?.get("candidates") as? JsonArray ?: return null
        val list =
            candidates.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/") ?: return@mapNotNull null
                val w = (obj["width"] as? JsonPrimitive)?.intOrNull
                val h = (obj["height"] as? JsonPrimitive)?.intOrNull
                ImageCandidate(url, w, h)
            }
        if (list.isEmpty()) return null
        return list.maxByOrNull { it.width ?: 0 } ?: list.first()
    }

    private fun extractSingleMedia(node: JsonObject): Media? {
        val originalWidth = (node["original_width"] as? JsonPrimitive)?.intOrNull
        val originalHeight = (node["original_height"] as? JsonPrimitive)?.intOrNull

        val imageCandidate = extractBestCandidate(node["image_versions2"])
        val posterUrl =
            imageCandidate?.url
                ?: (node["display_uri"] as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
        val width = imageCandidate?.width ?: originalWidth
        val height = imageCandidate?.height ?: originalHeight

        // 1. Check video_versions
        val videoVersions = node["video_versions"] as? JsonArray
        if (videoVersions != null && videoVersions.isNotEmpty()) {
            val validVideos =
                videoVersions
                    .mapNotNull { it as? JsonObject }
                    .filter { (it["url"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }

            if (validVideos.isNotEmpty()) {
                val bestVideo =
                    validVideos.maxByOrNull { (it["width"] as? JsonPrimitive)?.intOrNull ?: 0 }
                        ?: validVideos.first()
                val videoUrl = (bestVideo["url"] as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                if (!videoUrl.isNullOrBlank()) {
                    val vWidth = (bestVideo["width"] as? JsonPrimitive)?.intOrNull ?: width
                    val vHeight = (bestVideo["height"] as? JsonPrimitive)?.intOrNull ?: height
                    val duration = (node["video_duration"] as? JsonPrimitive)?.doubleOrNull
                    return Media.Video(
                        url = videoUrl,
                        previewUrl = posterUrl,
                        durationSeconds = duration,
                        width = vWidth,
                        height = vHeight,
                    )
                }
            }
        }

        // 2. Check giphy_media_info
        val giphy = node["giphy_media_info"] as? JsonObject
        if (giphy != null) {
            val images = giphy["images"] as? JsonObject
            val imgObj =
                (images?.get("original") as? JsonObject)
                    ?: (images?.get("fixed_height") as? JsonObject)
                    ?: (images?.values?.firstOrNull() as? JsonObject)

            if (imgObj != null) {
                val mp4 = (imgObj["mp4"] as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                // Prefer still frame for preview if available
                val stillObj =
                    (images?.get("original_still") as? JsonObject)
                        ?: (images?.get("fixed_height_still") as? JsonObject)
                        ?: (images?.get("downsized_still") as? JsonObject)
                val stillUrl = (stillObj?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                val preview = stillUrl ?: (imgObj["url"] as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")

                val gWidth = (imgObj["width"] as? JsonPrimitive)?.intOrNull ?: width
                val gHeight = (imgObj["height"] as? JsonPrimitive)?.intOrNull ?: height

                if (!mp4.isNullOrBlank()) {
                    return Media.Video(
                        url = mp4,
                        previewUrl = preview,
                        width = gWidth,
                        height = gHeight,
                    )
                } else if (!preview.isNullOrBlank()) {
                    return Media.Image(
                        url = preview,
                        previewUrl = preview,
                        width = gWidth,
                        height = gHeight,
                    )
                }
            }
        }

        // 3. Check image_versions2
        if (imageCandidate != null && !imageCandidate.url.isNullOrBlank()) {
            return Media.Image(
                url = imageCandidate.url,
                previewUrl = imageCandidate.url,
                width = width,
                height = height,
            )
        }

        // 4. Check display_uri
        val displayUri = (node["display_uri"] as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
        if (!displayUri.isNullOrBlank()) {
            return Media.Image(
                url = displayUri,
                previewUrl = displayUri,
                width = width,
                height = height,
            )
        }

        return null
    }

    private fun extractBalancedJsonArray(
        text: String,
        key: String,
    ): JsonArray? {
        val keyIndex = text.indexOf("\"$key\"")
        if (keyIndex == -1) return null
        val colonIndex = text.indexOf(':', keyIndex)
        if (colonIndex == -1) return null
        val startIndex = text.indexOf('[', colonIndex)
        if (startIndex == -1 || startIndex > colonIndex + 10) return null
        val slice = extractBalancedBracket(text, startIndex, '[', ']') ?: return null
        return runCatching { json.parseToJsonElement(slice) as? JsonArray }.getOrNull()
    }

    private fun extractSidecarItems(content: String): List<Media> {
        val keyIndex = content.indexOf("\"edge_sidecar_to_children\"")
        if (keyIndex == -1) return emptyList()
        val colonIndex = content.indexOf(':', keyIndex)
        if (colonIndex == -1) return emptyList()
        val startIndex = content.indexOf('{', colonIndex)
        if (startIndex == -1 || startIndex > colonIndex + 10) return emptyList()
        val slice = extractBalancedBracket(content, startIndex, '{', '}') ?: return emptyList()
        val obj = runCatching { json.parseToJsonElement(slice) as? JsonObject }.getOrNull() ?: return emptyList()
        val edges = (obj["edges"] as? JsonArray) ?: return emptyList()

        val list = mutableListOf<Media>()
        for (edge in edges) {
            val node = (edge as? JsonObject)?.get("node") as? JsonObject ?: continue
            val isVideo = (node["is_video"] as? JsonPrimitive)?.booleanOrNull ?: false
            val displayResources = node["display_resources"] as? JsonArray
            val lastResource = displayResources?.lastOrNull() as? JsonObject
            val displayUrl =
                (node["display_url"] as? JsonPrimitive)?.contentOrNull
                    ?: (lastResource?.get("src") as? JsonPrimitive)?.contentOrNull
            val dimensions = node["dimensions"] as? JsonObject
            val width = (dimensions?.get("width") as? JsonPrimitive)?.intOrNull
            val height = (dimensions?.get("height") as? JsonPrimitive)?.intOrNull

            if (isVideo) {
                val videoUrl = (node["video_url"] as? JsonPrimitive)?.contentOrNull
                if (!videoUrl.isNullOrBlank()) {
                    list.add(Media.Video(url = videoUrl, previewUrl = displayUrl, width = width, height = height))
                    continue
                }
            }
            if (!displayUrl.isNullOrBlank()) {
                list.add(Media.Image(url = displayUrl, previewUrl = displayUrl, width = width, height = height))
            }
        }
        return list
    }

    private fun extractBalancedBracket(
        text: String,
        startIndex: Int,
        openChar: Char,
        closeChar: Char,
    ): String? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in startIndex until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == openChar) {
                    depth++
                } else if (c == closeChar) {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIndex, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun findKeyInJson(
        element: JsonElement,
        targetKey: String,
    ): JsonElement? {
        if (element is JsonObject) {
            if (element.containsKey(targetKey)) {
                return element[targetKey]
            }
            for (v in element.values) {
                val found = findKeyInJson(v, targetKey)
                if (found != null) return found
            }
        } else if (element is JsonArray) {
            for (item in element) {
                val found = findKeyInJson(item, targetKey)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findUserObject(
        element: JsonElement,
        targetUsername: String?,
    ): JsonObject? {
        if (element is JsonObject) {
            if (element.containsKey("profile_pic_url") && element.containsKey("username")) {
                val u = (element["username"] as? JsonPrimitive)?.contentOrNull
                if (targetUsername == null || u.equals(targetUsername, ignoreCase = true)) {
                    return element
                }
            }
            if (element.containsKey("user") && element["user"] is JsonObject) {
                val user = element["user"] as JsonObject
                if (user.containsKey("profile_pic_url")) {
                    val u = (user["username"] as? JsonPrimitive)?.contentOrNull
                    if (targetUsername == null || u.equals(targetUsername, ignoreCase = true)) {
                        return user
                    }
                }
            }
            for (v in element.values) {
                val found = findUserObject(v, targetUsername)
                if (found != null) return found
            }
        } else if (element is JsonArray) {
            for (item in element) {
                val found = findUserObject(item, targetUsername)
                if (found != null) return found
            }
        }
        return null
    }

    private fun parseCarouselItems(carouselArray: JsonArray): List<Media> {
        val list = mutableListOf<Media>()
        for (item in carouselArray) {
            val obj = item as? JsonObject ?: continue
            val videoVersions = obj["video_versions"] as? JsonArray
            val imageVersions = (obj["image_versions2"] as? JsonObject)?.get("candidates") as? JsonArray
            val displayUri = (obj["display_uri"] as? JsonPrimitive)?.contentOrNull

            val bestCandidate = imageVersions?.firstOrNull() as? JsonObject
            val posterUrl =
                (bestCandidate?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                    ?: displayUri?.replace("\\/", "/")

            if (videoVersions != null && videoVersions.isNotEmpty()) {
                val bestVideo = videoVersions.firstOrNull() as? JsonObject
                val videoUrl = (bestVideo?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                if (!videoUrl.isNullOrBlank()) {
                    list.add(
                        Media.Video(
                            url = videoUrl,
                            previewUrl = posterUrl,
                            width = (bestVideo["width"] as? JsonPrimitive)?.intOrNull,
                            height = (bestVideo["height"] as? JsonPrimitive)?.intOrNull,
                        ),
                    )
                    continue
                }
            }

            val imageUrl =
                (bestCandidate?.get("url") as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                    ?: displayUri?.replace("\\/", "/")

            if (!imageUrl.isNullOrBlank()) {
                list.add(
                    Media.Image(
                        url = imageUrl,
                        previewUrl = imageUrl,
                        width = (bestCandidate?.get("width") as? JsonPrimitive)?.intOrNull,
                        height = (bestCandidate?.get("height") as? JsonPrimitive)?.intOrNull,
                    ),
                )
            }
        }
        return list
    }
}
