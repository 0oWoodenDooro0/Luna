package dev.socialpeek.resolver.bilibili

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.BilibiliTarget
import dev.socialpeek.util.BilibiliUrlPolicy
import dev.socialpeek.util.UrlSanitizer
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class BilibiliResolver : PlatformResolver {
    override val platform: Platform = Platform.BILIBILI

    private val apiClient = BilibiliApiClient()
    private val listResolver = BilibiliListResolver(apiClient)

    companion object {
        private val logger = LoggerFactory.getLogger(BilibiliResolver::class.java)
    }

    override fun canResolve(url: String): Boolean = BilibiliUrlPolicy.canResolve(url)

    override suspend fun resolve(
        url: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        var currentUrl = url
        // Expand b23.tv short link if it doesn't already have a video/opus ID in the path
        if (BilibiliUrlPolicy.isExpandableShortLink(url)) {
            currentUrl =
                try {
                    client.resolveFinalUrl(url, BilibiliApiClient.BILIBILI_HEADERS)
                } catch (e: Exception) {
                    logger.warn("Failed to expand b23.tv short link {}: {}", url, e.message)
                    url
                }
        }

        currentUrl = BilibiliUrlPolicy.canonicalizeB23DirectVideoUrl(currentUrl)

        return when (val target = BilibiliUrlPolicy.matchTarget(currentUrl)) {
            is BilibiliTarget.Video -> {
                resolveVideo(
                    bvid = target.bvid,
                    aid = target.aid,
                    originalUrl = url,
                    currentUrl = currentUrl,
                    client = client,
                )
            }

            is BilibiliTarget.Opus -> {
                resolveOpus(
                    opusId = target.opusId,
                    originalUrl = url,
                    currentUrl = currentUrl,
                    client = client,
                )
            }

            is BilibiliTarget.ListTarget -> {
                listResolver.resolve(
                    target = target,
                    originalUrl = url,
                    currentUrl = currentUrl,
                    client = client,
                )
            }

            BilibiliTarget.ShortLink, null -> {
                if (BilibiliUrlPolicy.isShortLink(currentUrl)) {
                    logger.warn("b23.tv short link did not resolve to a supported Bilibili URL: original={}, resolved={}", url, currentUrl)
                }
                throw ParsingException(currentUrl, "Unsupported Bilibili URL format: $currentUrl")
            }
        }
    }

    private suspend fun resolveVideo(
        bvid: String?,
        aid: String?,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val queryParam = if (bvid != null) "bvid=$bvid" else "aid=$aid"
        val apiUrl = "https://api.bilibili.com/x/web-interface/view?$queryParam"
        val data = apiClient.getJsonData(apiUrl, originalUrl, "video API", client)

        val realBvid = data["bvid"]?.jsonPrimitive?.contentOrNull ?: bvid ?: aid ?: "unknown"
        val title = data["title"]?.jsonPrimitive?.contentOrNull
        val desc = data["desc"]?.jsonPrimitive?.contentOrNull ?: ""
        val pic = data["pic"]?.jsonPrimitive?.contentOrNull?.let(BilibiliUrlPolicy::upgradeToHttps)
        val pubdate = data["pubdate"]?.jsonPrimitive?.longOrNull
        val duration = data["duration"]?.jsonPrimitive?.doubleOrNull

        val owner = data["owner"]?.jsonObject
        val mid =
            owner
                ?.get("mid")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: "unknown"
        val author = apiClient.extractOwnerAuthor(owner = owner, mid = mid, fallbackName = "UP主")

        val stat = data["stat"]?.jsonObject
        val views = stat?.get("view")?.jsonPrimitive?.longOrNull
        val comments = stat?.get("reply")?.jsonPrimitive?.longOrNull
        val likes = stat?.get("like")?.jsonPrimitive?.longOrNull
        val reposts = stat?.get("share")?.jsonPrimitive?.longOrNull
        val bookmarks = stat?.get("favorite")?.jsonPrimitive?.longOrNull

        val videoCanonicalUrl = "https://www.bilibili.com/video/$realBvid/"
        val mediaList = mutableListOf<Media>()
        if (!pic.isNullOrBlank()) {
            mediaList.add(
                Media.Video(
                    url = videoCanonicalUrl,
                    previewUrl = pic,
                    durationSeconds = duration,
                ),
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
            metrics =
                Metrics(
                    likes = likes,
                    comments = comments,
                    views = views,
                    reposts = reposts,
                    bookmarks = bookmarks,
                ),
            createdAtEpochSeconds = pubdate,
        )
    }

    private suspend fun resolveOpus(
        opusId: String,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val apiUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=$opusId"
        val root = apiClient.getJsonRoot(apiUrl, originalUrl, "dynamic API", client)
        val item =
            root["data"]?.jsonObject?.get("item")?.jsonObject
                ?: throw PostNotFoundException(originalUrl, "Dynamic item not found")

        val modules = item["modules"]?.jsonObject
        val authorModule = modules?.get("module_author")?.jsonObject
        val dynamicModule = modules?.get("module_dynamic")?.jsonObject
        val statModule = modules?.get("module_stat")?.jsonObject

        val mid = authorModule?.get("mid")?.jsonPrimitive?.contentOrNull ?: "unknown"
        val authorName = authorModule?.get("name")?.jsonPrimitive?.contentOrNull ?: "UP主"
        val face = authorModule?.get("face")?.jsonPrimitive?.contentOrNull
        val pubTs = authorModule?.get("pub_ts")?.jsonPrimitive?.longOrNull

        val author = apiClient.buildAuthor(mid = mid, displayName = authorName, rawAvatarUrl = face)

        val descText =
            dynamicModule
                ?.get("desc")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull ?: ""
        val major = dynamicModule?.get("major")?.jsonObject
        val opus = major?.get("opus")?.jsonObject
        val opusTitle = opus?.get("title")?.jsonPrimitive?.contentOrNull

        val mediaList = mutableListOf<Media>()
        val picsArray =
            opus?.get("pics")?.jsonArray
                ?: major
                    ?.get("draw")
                    ?.jsonObject
                    ?.get("items")
                    ?.jsonArray

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
                        height = height,
                    ),
                )
            }
        }

        val likes =
            statModule
                ?.get("like")
                ?.jsonObject
                ?.get("count")
                ?.jsonPrimitive
                ?.longOrNull
        val comments =
            statModule
                ?.get("comment")
                ?.jsonObject
                ?.get("count")
                ?.jsonPrimitive
                ?.longOrNull
        val reposts =
            statModule
                ?.get("forward")
                ?.jsonObject
                ?.get("count")
                ?.jsonPrimitive
                ?.longOrNull

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
            metrics =
                Metrics(
                    likes = likes,
                    comments = comments,
                    reposts = reposts,
                ),
            createdAtEpochSeconds = pubTs,
        )
    }
}
