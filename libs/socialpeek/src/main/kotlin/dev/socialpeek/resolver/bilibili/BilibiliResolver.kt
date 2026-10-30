package dev.socialpeek.resolver.bilibili

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer
import io.ktor.http.*
import kotlinx.serialization.json.*

class BilibiliResolver : PlatformResolver {

    override val platform: Platform = Platform.BILIBILI

    private val bvPattern = Regex("""(BV[a-zA-Z0-9]{10})""", RegexOption.IGNORE_CASE)
    private val avPattern = Regex("""(?:bilibili\.com/video/|b23\.tv/)av([0-9]+)""", RegexOption.IGNORE_CASE)
    private val opusPattern = Regex("""(?:bilibili\.com/opus/|t\.bilibili\.com/)([0-9]+)""", RegexOption.IGNORE_CASE)
    private val b23Pattern = Regex("""https?://b23\.tv/[a-zA-Z0-9]+""", RegexOption.IGNORE_CASE)

    companion object {
        val BILIBILI_HEADERS: Map<String, String> = mapOf(
            HttpHeaders.UserAgent to "SocialPeek/1.0 (https://github.com/0oWoodenDooro0/SocialPeek)",
            "Referer" to "https://www.bilibili.com/"
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun canResolve(url: String): Boolean {
        return bvPattern.containsMatchIn(url) ||
                avPattern.containsMatchIn(url) ||
                opusPattern.containsMatchIn(url) ||
                b23Pattern.containsMatchIn(url)
    }

    override suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost {
        var currentUrl = url
        // Expand b23.tv short link if it doesn't already have a video/opus ID in the path
        if (b23Pattern.containsMatchIn(url) && !bvPattern.containsMatchIn(url) && !avPattern.containsMatchIn(url) && !opusPattern.containsMatchIn(url)) {
            currentUrl = try {
                client.resolveFinalUrl(url)
            } catch (e: Exception) {
                url
            }
        }

        val bvMatch = bvPattern.find(currentUrl)
        val avMatch = avPattern.find(currentUrl)
        val opusMatch = opusPattern.find(currentUrl)

        return when {
            bvMatch != null -> resolveVideo(bvid = bvMatch.groupValues[1], aid = null, originalUrl = url, currentUrl = currentUrl, client = client)
            avMatch != null -> resolveVideo(bvid = null, aid = avMatch.groupValues[1], originalUrl = url, currentUrl = currentUrl, client = client)
            opusMatch != null -> resolveOpus(opusId = opusMatch.groupValues[1], originalUrl = url, currentUrl = currentUrl, client = client)
            else -> throw ParsingException(currentUrl, "Unsupported Bilibili URL format: $currentUrl")
        }
    }

    private suspend fun resolveVideo(
        bvid: String?,
        aid: String?,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient
    ): PeekPost {
        val queryParam = if (bvid != null) "bvid=$bvid" else "aid=$aid"
        val apiUrl = "https://api.bilibili.com/x/web-interface/view?$queryParam"

        val responseText = try {
            client.get(apiUrl, BILIBILI_HEADERS)
        } catch (e: Exception) {
            throw PostNotFoundException(originalUrl, e.message)
        }

        val root = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            throw ParsingException(originalUrl, "Failed to parse Bilibili video API JSON", e)
        }

        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Bilibili error code $code"
            throw PostNotFoundException(originalUrl, message)
        }

        val data = root["data"]?.jsonObject
            ?: throw ParsingException(originalUrl, "Missing 'data' field in Bilibili video response")

        val realBvid = data["bvid"]?.jsonPrimitive?.contentOrNull ?: bvid ?: aid ?: "unknown"
        val title = data["title"]?.jsonPrimitive?.contentOrNull
        val desc = data["desc"]?.jsonPrimitive?.contentOrNull ?: ""
        val pic = data["pic"]?.jsonPrimitive?.contentOrNull
        val pubdate = data["pubdate"]?.jsonPrimitive?.longOrNull
        val duration = data["duration"]?.jsonPrimitive?.doubleOrNull

        val owner = data["owner"]?.jsonObject
        val mid = owner?.get("mid")?.jsonPrimitive?.contentOrNull ?: "unknown"
        val ownerName = owner?.get("name")?.jsonPrimitive?.contentOrNull ?: "UP主"
        val face = owner?.get("face")?.jsonPrimitive?.contentOrNull

        val author = Author(
            id = mid,
            username = mid,
            displayName = ownerName,
            avatarUrl = face,
            profileUrl = "https://space.bilibili.com/$mid"
        )

        val stat = data["stat"]?.jsonObject
        val views = stat?.get("view")?.jsonPrimitive?.longOrNull
        val comments = stat?.get("reply")?.jsonPrimitive?.longOrNull
        val likes = stat?.get("like")?.jsonPrimitive?.longOrNull
        val reposts = stat?.get("share")?.jsonPrimitive?.longOrNull
        val bookmarks = stat?.get("favorite")?.jsonPrimitive?.longOrNull

        val videoCanonicalUrl = "https://www.bilibili.com/video/$realBvid"
        val mediaList = mutableListOf<Media>()
        if (!pic.isNullOrBlank()) {
            mediaList.add(
                Media.Video(
                    url = videoCanonicalUrl,
                    previewUrl = pic,
                    durationSeconds = duration
                )
            )
        }

        val cleanUrl = UrlSanitizer.clean(currentUrl, Platform.BILIBILI)

        return PeekPost(
            platform = Platform.BILIBILI,
            id = realBvid,
            originalUrl = originalUrl,
            cleanUrl = cleanUrl,
            author = author,
            title = title,
            content = desc,
            media = mediaList,
            metrics = Metrics(
                likes = likes,
                comments = comments,
                views = views,
                reposts = reposts,
                bookmarks = bookmarks
            ),
            createdAtEpochSeconds = pubdate
        )
    }

    private suspend fun resolveOpus(
        opusId: String,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient
    ): PeekPost {
        val apiUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=$opusId"

        val responseText = try {
            client.get(apiUrl, BILIBILI_HEADERS)
        } catch (e: Exception) {
            throw PostNotFoundException(originalUrl, e.message)
        }

        val root = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            throw ParsingException(originalUrl, "Failed to parse Bilibili dynamic API JSON", e)
        }

        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Bilibili error code $code"
            throw PostNotFoundException(originalUrl, message)
        }

        val item = root["data"]?.jsonObject?.get("item")?.jsonObject
            ?: throw PostNotFoundException(originalUrl, "Dynamic item not found")

        val modules = item["modules"]?.jsonObject
        val authorModule = modules?.get("module_author")?.jsonObject
        val dynamicModule = modules?.get("module_dynamic")?.jsonObject
        val statModule = modules?.get("module_stat")?.jsonObject

        val mid = authorModule?.get("mid")?.jsonPrimitive?.contentOrNull ?: "unknown"
        val authorName = authorModule?.get("name")?.jsonPrimitive?.contentOrNull ?: "UP主"
        val face = authorModule?.get("face")?.jsonPrimitive?.contentOrNull
        val pubTs = authorModule?.get("pub_ts")?.jsonPrimitive?.longOrNull

        val author = Author(
            id = mid,
            username = mid,
            displayName = authorName,
            avatarUrl = face,
            profileUrl = "https://space.bilibili.com/$mid"
        )

        val descText = dynamicModule?.get("desc")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        val major = dynamicModule?.get("major")?.jsonObject
        val opus = major?.get("opus")?.jsonObject
        val opusTitle = opus?.get("title")?.jsonPrimitive?.contentOrNull

        val mediaList = mutableListOf<Media>()
        val picsArray = opus?.get("pics")?.jsonArray 
            ?: major?.get("draw")?.jsonObject?.get("items")?.jsonArray

        picsArray?.forEach { picElem ->
            val picObj = picElem.jsonObject
            val picUrl = picObj["url"]?.jsonPrimitive?.contentOrNull ?: picObj["src"]?.jsonPrimitive?.contentOrNull
            if (!picUrl.isNullOrBlank()) {
                val width = picObj["width"]?.jsonPrimitive?.intOrNull
                val height = picObj["height"]?.jsonPrimitive?.intOrNull
                mediaList.add(
                    Media.Image(
                        url = picUrl,
                        previewUrl = picUrl,
                        width = width,
                        height = height
                    )
                )
            }
        }

        val likes = statModule?.get("like")?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull
        val comments = statModule?.get("comment")?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull
        val reposts = statModule?.get("forward")?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull

        val cleanUrl = UrlSanitizer.clean(currentUrl, Platform.BILIBILI)

        return PeekPost(
            platform = Platform.BILIBILI,
            id = opusId,
            originalUrl = originalUrl,
            cleanUrl = cleanUrl,
            author = author,
            title = opusTitle,
            content = descText,
            media = mediaList,
            metrics = Metrics(
                likes = likes,
                comments = comments,
                reposts = reposts
            ),
            createdAtEpochSeconds = pubTs
        )
    }
}
