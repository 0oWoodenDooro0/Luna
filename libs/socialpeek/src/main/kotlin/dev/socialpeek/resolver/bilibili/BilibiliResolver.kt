package dev.socialpeek.resolver.bilibili

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class BilibiliResolver : PlatformResolver {
    override val platform: Platform = Platform.BILIBILI

    private val bvPattern = Regex("""(BV[a-zA-Z0-9]{10})""", RegexOption.IGNORE_CASE)
    private val avPattern = Regex("""(?:bilibili\.com/video/|b23\.tv/)av([0-9]+)""", RegexOption.IGNORE_CASE)
    private val opusPattern = Regex("""(?:bilibili\.com/opus/|t\.bilibili\.com/)([0-9]+)""", RegexOption.IGNORE_CASE)
    private val b23Pattern = Regex("""https?://b23\.tv/[a-zA-Z0-9_-]+""", RegexOption.IGNORE_CASE)
    private val b23VideoPathPattern = Regex("""^/(BV[a-zA-Z0-9]{10}|av[0-9]+)/?$""", RegexOption.IGNORE_CASE)
    private val collectionDetailPattern =
        Regex("""https?://space\.bilibili\.com/([0-9]+)/channel/collectiondetail/?(?:\?|#|$)""", RegexOption.IGNORE_CASE)
    private val seriesDetailPattern =
        Regex("""https?://space\.bilibili\.com/([0-9]+)/channel/seriesdetail/?(?:\?|#|$)""", RegexOption.IGNORE_CASE)
    private val listsPattern =
        Regex("""https?://space\.bilibili\.com/([0-9]+)/lists/([0-9]+)/?(?:\?|#|$)""", RegexOption.IGNORE_CASE)

    companion object {
        private val logger = LoggerFactory.getLogger(BilibiliResolver::class.java)

        val BILIBILI_HEADERS: Map<String, String> =
            mapOf(
                HttpHeaders.UserAgent to "SocialPeek/1.0 (https://github.com/0oWoodenDooro0/SocialPeek)",
                "Referer" to "https://www.bilibili.com/",
            )
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override fun canResolve(url: String): Boolean =
        bvPattern.containsMatchIn(url) ||
            avPattern.containsMatchIn(url) ||
            opusPattern.containsMatchIn(url) ||
            b23Pattern.containsMatchIn(url) ||
            collectionDetailPattern.containsMatchIn(url) ||
            seriesDetailPattern.containsMatchIn(url) ||
            listsPattern.containsMatchIn(url)

    override suspend fun resolve(
        url: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        var currentUrl = url
        // Expand b23.tv short link if it doesn't already have a video/opus ID in the path
        if (b23Pattern.containsMatchIn(url) && !bvPattern.containsMatchIn(url) && !avPattern.containsMatchIn(url) &&
            !opusPattern.containsMatchIn(url)
        ) {
            currentUrl =
                try {
                    client.resolveFinalUrl(url, BILIBILI_HEADERS)
                } catch (e: Exception) {
                    logger.warn("Failed to expand b23.tv short link {}: {}", url, e.message)
                    url
                }
        }

        currentUrl = canonicalizeB23DirectVideoUrl(currentUrl)

        val bvMatch = bvPattern.find(currentUrl)
        val avMatch = avPattern.find(currentUrl)
        val opusMatch = opusPattern.find(currentUrl)
        val collectionMatch = collectionDetailPattern.find(currentUrl)
        val seriesMatch = seriesDetailPattern.find(currentUrl)
        val listsMatch = listsPattern.find(currentUrl)

        return when {
            bvMatch != null -> {
                resolveVideo(
                    bvid = bvMatch.groupValues[1],
                    aid = null,
                    originalUrl = url,
                    currentUrl = currentUrl,
                    client = client,
                )
            }

            avMatch != null -> {
                resolveVideo(
                    bvid = null,
                    aid = avMatch.groupValues[1],
                    originalUrl = url,
                    currentUrl = currentUrl,
                    client = client,
                )
            }

            opusMatch != null -> {
                resolveOpus(opusId = opusMatch.groupValues[1], originalUrl = url, currentUrl = currentUrl, client = client)
            }

            collectionMatch != null -> {
                val mid = collectionMatch.groupValues[1]
                val sid =
                    extractQueryParam(currentUrl, "sid")?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
                        ?: throw ParsingException(currentUrl, "Missing or invalid 'sid' parameter in Bilibili collection URL")
                resolveCollection(sid = sid, fallbackMid = mid, originalUrl = url, currentUrl = currentUrl, client = client).first
            }

            seriesMatch != null -> {
                val mid = seriesMatch.groupValues[1]
                val sid =
                    extractQueryParam(currentUrl, "sid")?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
                        ?: throw ParsingException(currentUrl, "Missing or invalid 'sid' parameter in Bilibili series URL")
                resolveSeries(sid = sid, fallbackMid = mid, originalUrl = url, currentUrl = currentUrl, client = client)
            }

            listsMatch != null -> {
                val mid = listsMatch.groupValues[1]
                val sid = listsMatch.groupValues[2]
                val typeParam = extractQueryParam(currentUrl, "type")?.lowercase()?.takeIf { it.isNotBlank() }
                when (typeParam) {
                    "season" -> resolveCollection(sid = sid, fallbackMid = mid, originalUrl = url, currentUrl = currentUrl, client = client).first
                    "series" -> resolveSeries(sid = sid, fallbackMid = mid, originalUrl = url, currentUrl = currentUrl, client = client)
                    null -> resolveListsWithFallback(sid = sid, fallbackMid = mid, originalUrl = url, currentUrl = currentUrl, client = client)
                    else -> throw ParsingException(currentUrl, "Unsupported Bilibili lists type '$typeParam' in URL: $currentUrl")
                }
            }

            else -> {
                if (b23Pattern.containsMatchIn(currentUrl)) {
                    logger.warn("b23.tv short link did not resolve to a supported Bilibili URL: original={}, resolved={}", url, currentUrl)
                }
                throw ParsingException(currentUrl, "Unsupported Bilibili URL format: $currentUrl")
            }
        }
    }

    private fun canonicalizeB23DirectVideoUrl(url: String): String {
        val parsed =
            try {
                Url(url)
            } catch (_: Exception) {
                return url
            }
        if (!parsed.host.equals("b23.tv", ignoreCase = true)) return url
        val match = b23VideoPathPattern.find(parsed.encodedPath) ?: return url
        val videoId = match.groupValues[1]
        return URLBuilder(parsed)
            .apply {
                protocol = URLProtocol.HTTPS
                host = "www.bilibili.com"
                encodedPath = "/video/$videoId/"
            }.buildString()
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

        val responseText =
            try {
                client.get(apiUrl, BILIBILI_HEADERS)
            } catch (e: Exception) {
                throw PostNotFoundException(originalUrl, e.message)
            }

        val root =
            try {
                json.parseToJsonElement(responseText).jsonObject
            } catch (e: Exception) {
                throw ParsingException(originalUrl, "Failed to parse Bilibili video API JSON", e)
            }

        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Bilibili error code $code"
            throw PostNotFoundException(originalUrl, message)
        }

        val data =
            root["data"]?.jsonObject
                ?: throw ParsingException(originalUrl, "Missing 'data' field in Bilibili video response")

        val realBvid = data["bvid"]?.jsonPrimitive?.contentOrNull ?: bvid ?: aid ?: "unknown"
        val title = data["title"]?.jsonPrimitive?.contentOrNull
        val desc = data["desc"]?.jsonPrimitive?.contentOrNull ?: ""
        val pic = data["pic"]?.jsonPrimitive?.contentOrNull?.let(::upgradeToHttps)
        val pubdate = data["pubdate"]?.jsonPrimitive?.longOrNull
        val duration = data["duration"]?.jsonPrimitive?.doubleOrNull

        val owner = data["owner"]?.jsonObject
        val mid = owner?.get("mid")?.jsonPrimitive?.contentOrNull ?: "unknown"
        val ownerName = owner?.get("name")?.jsonPrimitive?.contentOrNull ?: "UP主"
        val face =
            owner
                ?.get("face")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let(::upgradeToHttps)

        val author =
            Author(
                id = mid,
                username = mid,
                displayName = ownerName,
                avatarUrl = face,
                profileUrl = "https://space.bilibili.com/$mid/",
            )

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

        val responseText =
            try {
                client.get(apiUrl, BILIBILI_HEADERS)
            } catch (e: Exception) {
                throw PostNotFoundException(originalUrl, e.message)
            }

        val root =
            try {
                json.parseToJsonElement(responseText).jsonObject
            } catch (e: Exception) {
                throw ParsingException(originalUrl, "Failed to parse Bilibili dynamic API JSON", e)
            }

        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Bilibili error code $code"
            throw PostNotFoundException(originalUrl, message)
        }

        val item =
            root["data"]?.jsonObject?.get("item")?.jsonObject
                ?: throw PostNotFoundException(originalUrl, "Dynamic item not found")

        val modules = item["modules"]?.jsonObject
        val authorModule = modules?.get("module_author")?.jsonObject
        val dynamicModule = modules?.get("module_dynamic")?.jsonObject
        val statModule = modules?.get("module_stat")?.jsonObject

        val mid = authorModule?.get("mid")?.jsonPrimitive?.contentOrNull ?: "unknown"
        val authorName = authorModule?.get("name")?.jsonPrimitive?.contentOrNull ?: "UP主"
        val face =
            authorModule
                ?.get("face")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let(::upgradeToHttps)
        val pubTs = authorModule?.get("pub_ts")?.jsonPrimitive?.longOrNull

        val author =
            Author(
                id = mid,
                username = mid,
                displayName = authorName,
                avatarUrl = face,
                profileUrl = "https://space.bilibili.com/$mid/",
            )

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

    private suspend fun resolveListsWithFallback(
        sid: String,
        fallbackMid: String,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val seasonResult =
            try {
                resolveCollection(
                    sid = sid,
                    fallbackMid = fallbackMid,
                    originalUrl = originalUrl,
                    currentUrl = currentUrl,
                    client = client,
                )
            } catch (e: Exception) {
                logger.warn("Bilibili /lists/{} season lookup failed ({}), falling back to series", sid, e.message)
                return resolveSeries(
                    sid = sid,
                    fallbackMid = fallbackMid,
                    originalUrl = originalUrl,
                    currentUrl = currentUrl,
                    client = client,
                )
            }

        val (seasonPost, hasMedias) = seasonResult
        if (hasMedias) {
            return seasonPost
        }

        logger.warn("Bilibili /lists/{} season lookup returned empty media list, falling back to series", sid)
        return try {
            resolveSeries(
                sid = sid,
                fallbackMid = fallbackMid,
                originalUrl = originalUrl,
                currentUrl = currentUrl,
                client = client,
            )
        } catch (e: Exception) {
            logger.warn("Bilibili /lists/{} series fallback also failed ({}), keeping empty season metadata", sid, e.message)
            seasonPost
        }
    }

    private suspend fun resolveCollection(
        sid: String,
        fallbackMid: String,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient,
    ): Pair<PeekPost, Boolean> {
        val apiUrl = "https://api.bilibili.com/x/space/fav/season/list?season_id=$sid"
        val responseText =
            try {
                client.get(apiUrl, BILIBILI_HEADERS)
            } catch (e: Exception) {
                throw PostNotFoundException(originalUrl, e.message)
            }

        val root =
            try {
                json.parseToJsonElement(responseText).jsonObject
            } catch (e: Exception) {
                throw ParsingException(originalUrl, "Failed to parse Bilibili collection API JSON", e)
            }

        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Bilibili error code $code"
            throw PostNotFoundException(originalUrl, message)
        }

        val data =
            root["data"]?.jsonObject
                ?: throw ParsingException(originalUrl, "Missing 'data' field in Bilibili collection response")
        val info =
            data["info"]?.jsonObject
                ?: throw ParsingException(originalUrl, "Missing 'info' field in Bilibili collection response")

        val resolvedId = info["id"]?.jsonPrimitive?.contentOrNull ?: sid
        val title = info["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawCover = info["cover"]?.jsonPrimitive?.contentOrNull
        val intro = info["intro"]?.jsonPrimitive?.contentOrNull ?: ""
        val medias = data["medias"]?.jsonArray ?: JsonArray(emptyList())
        val totalCount = info["media_count"]?.jsonPrimitive?.longOrNull ?: medias.size.toLong()

        val upper = info["upper"]?.jsonObject
        val mid = upper?.get("mid")?.jsonPrimitive?.contentOrNull ?: fallbackMid
        val upperName =
            upper
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }

        val cntInfo = info["cnt_info"]?.jsonObject
        val playCount = cntInfo?.get("play")?.jsonPrimitive?.longOrNull
        val collectCount = cntInfo?.get("collect")?.jsonPrimitive?.longOrNull

        val firstMedia = medias.firstOrNull()?.jsonObject
        val firstBvid =
            firstMedia
                ?.get("bvid")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        val firstItemCover = firstMedia?.get("cover")?.jsonPrimitive?.contentOrNull
        val firstPubtime = firstMedia?.get("pubtime")?.jsonPrimitive?.longOrNull

        val coverUrl = resolveListCover(rawCover, firstItemCover)
        val mediaList =
            if (!coverUrl.isNullOrBlank()) {
                listOf(Media.Image(url = coverUrl, previewUrl = coverUrl))
            } else {
                emptyList()
            }

        val itemTitles =
            medias.mapNotNull { elem ->
                elem.jsonObject["title"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }

        val author = enrichCreator(firstBvid = firstBvid, mid = mid, fallbackName = upperName, client = client)
        val content =
            formatListContent(
                totalCount = totalCount,
                playCount = playCount,
                description = intro,
                itemTitles = itemTitles,
            )

        val cleanUrl = UrlSanitizer.clean(currentUrl, Platform.BILIBILI)

        val post =
            PeekPost(
                platform = Platform.BILIBILI,
                id = resolvedId,
                originalUrl = originalUrl,
                cleanUrl = cleanUrl,
                author = author,
                title = title,
                content = content,
                media = mediaList,
                metrics =
                    if (playCount != null || collectCount != null) {
                        Metrics(views = playCount, bookmarks = collectCount)
                    } else {
                        null
                    },
                createdAtEpochSeconds = firstPubtime,
            )
        return post to medias.isNotEmpty()
    }

    private suspend fun resolveSeries(
        sid: String,
        fallbackMid: String,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val metaUrl = "https://api.bilibili.com/x/series/series?series_id=$sid"
        val metaText =
            try {
                client.get(metaUrl, BILIBILI_HEADERS)
            } catch (e: Exception) {
                throw PostNotFoundException(originalUrl, e.message)
            }

        val metaRoot =
            try {
                json.parseToJsonElement(metaText).jsonObject
            } catch (e: Exception) {
                throw ParsingException(originalUrl, "Failed to parse Bilibili series metadata JSON", e)
            }

        val metaCode = metaRoot["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (metaCode != 0) {
            val message = metaRoot["message"]?.jsonPrimitive?.contentOrNull ?: "Bilibili error code $metaCode"
            throw PostNotFoundException(originalUrl, message)
        }

        val meta =
            metaRoot["data"]?.jsonObject?.get("meta")?.jsonObject
                ?: throw ParsingException(originalUrl, "Missing 'data.meta' field in Bilibili series response")

        val resolvedId = meta["series_id"]?.jsonPrimitive?.contentOrNull ?: sid
        val mid = meta["mid"]?.jsonPrimitive?.contentOrNull ?: fallbackMid
        val title = meta["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val description = meta["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val metaTotal = meta["total"]?.jsonPrimitive?.longOrNull
        val updatedAt =
            meta["last_update_ts"]?.jsonPrimitive?.longOrNull
                ?: meta["mtime"]?.jsonPrimitive?.longOrNull
                ?: meta["ctime"]?.jsonPrimitive?.longOrNull

        val archivesUrl =
            "https://api.bilibili.com/x/series/archives?mid=$mid&series_id=$sid&only_normal=true&sort=desc&pn=1&ps=10"
        val archivesText =
            try {
                client.get(archivesUrl, BILIBILI_HEADERS)
            } catch (e: Exception) {
                throw PostNotFoundException(originalUrl, e.message)
            }

        val archivesRoot =
            try {
                json.parseToJsonElement(archivesText).jsonObject
            } catch (e: Exception) {
                throw ParsingException(originalUrl, "Failed to parse Bilibili series archives JSON", e)
            }

        val archivesCode = archivesRoot["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (archivesCode != 0) {
            val message = archivesRoot["message"]?.jsonPrimitive?.contentOrNull ?: "Bilibili error code $archivesCode"
            throw PostNotFoundException(originalUrl, message)
        }

        val archivesData = archivesRoot["data"]?.jsonObject
        val pageTotal =
            archivesData
                ?.get("page")
                ?.jsonObject
                ?.get("total")
                ?.jsonPrimitive
                ?.longOrNull
        val archives = archivesData?.get("archives")?.jsonArray ?: JsonArray(emptyList())
        val totalCount = pageTotal ?: metaTotal ?: archives.size.toLong()

        val firstArchive = archives.firstOrNull()?.jsonObject
        val firstBvid =
            firstArchive
                ?.get("bvid")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        val firstItemPic = firstArchive?.get("pic")?.jsonPrimitive?.contentOrNull
        val firstPubdate =
            firstArchive?.get("pubdate")?.jsonPrimitive?.longOrNull
                ?: firstArchive?.get("ctime")?.jsonPrimitive?.longOrNull

        val coverUrl = resolveListCover(rawCover = null, firstItemCover = firstItemPic)
        val mediaList =
            if (!coverUrl.isNullOrBlank()) {
                listOf(Media.Image(url = coverUrl, previewUrl = coverUrl))
            } else {
                emptyList()
            }

        val itemTitles =
            archives.mapNotNull { elem ->
                elem.jsonObject["title"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }

        val author = enrichCreator(firstBvid = firstBvid, mid = mid, fallbackName = null, client = client)
        val content =
            formatListContent(
                totalCount = totalCount,
                playCount = null,
                description = description,
                itemTitles = itemTitles,
            )

        val cleanUrl = UrlSanitizer.clean(currentUrl, Platform.BILIBILI)

        return PeekPost(
            platform = Platform.BILIBILI,
            id = resolvedId,
            originalUrl = originalUrl,
            cleanUrl = cleanUrl,
            author = author,
            title = title,
            content = content,
            media = mediaList,
            metrics = null,
            createdAtEpochSeconds = updatedAt ?: firstPubdate,
        )
    }

    private suspend fun enrichCreator(
        firstBvid: String?,
        mid: String,
        fallbackName: String?,
        client: SocialPeekHttpClient,
    ): Author {
        val defaultName = fallbackName ?: "UID $mid"
        var resolvedName = defaultName
        var resolvedAvatar: String? = null

        if (!firstBvid.isNullOrBlank()) {
            val viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=$firstBvid"
            try {
                val viewText = client.get(viewUrl, BILIBILI_HEADERS)
                val viewRoot = json.parseToJsonElement(viewText).jsonObject
                val code = viewRoot["code"]?.jsonPrimitive?.intOrNull ?: -1
                if (code == 0) {
                    val owner = viewRoot["data"]?.jsonObject?.get("owner")?.jsonObject
                    val ownerName =
                        owner
                            ?.get("name")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                    val ownerFace =
                        owner
                            ?.get("face")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                    if (ownerName != null) {
                        resolvedName = ownerName
                    }
                    if (ownerFace != null) {
                        resolvedAvatar = upgradeToHttps(ownerFace)
                    }
                } else {
                    logger.warn("Creator enrichment returned non-zero code={} for bvid={}, mid={}", code, firstBvid, mid)
                }
            } catch (e: Exception) {
                logger.warn("Creator enrichment failed for bvid={}, mid={}: {}", firstBvid, mid, e.message)
            }
        }

        return Author(
            id = mid,
            username = mid,
            displayName = resolvedName,
            avatarUrl = resolvedAvatar,
            profileUrl = "https://space.bilibili.com/$mid/",
        )
    }

    private fun resolveListCover(
        rawCover: String?,
        firstItemCover: String?,
    ): String? {
        val isMissingOrPlaceholder =
            rawCover.isNullOrBlank() || rawCover.contains("viedeo_material_default.png", ignoreCase = true)
        val chosen =
            if (isMissingOrPlaceholder) {
                firstItemCover?.takeIf {
                    it.isNotBlank() && !it.contains("viedeo_material_default.png", ignoreCase = true)
                }
            } else {
                rawCover
            }
        return chosen?.let(::upgradeToHttps)
    }

    private fun formatListContent(
        totalCount: Long,
        playCount: Long?,
        description: String,
        itemTitles: List<String>,
    ): String {
        val statsLine =
            if (playCount != null) {
                "📚 共 $totalCount 部作品 • ▶️ ${formatCount(playCount)} 播放"
            } else {
                "📚 共 $totalCount 部作品"
            }

        val lines = mutableListOf(statsLine)
        val normalizedDesc =
            description
                .trim()
                .replace(Regex("""\s*\r?\n+\s*"""), " ")
        if (normalizedDesc.isNotBlank()) {
            val truncatedDesc =
                if (normalizedDesc.length > 90) {
                    normalizedDesc.take(87).trimEnd() + "..."
                } else {
                    normalizedDesc
                }
            lines.add(truncatedDesc)
        }

        val maxItems = minOf(3, 5 - lines.size)
        for (itemTitle in itemTitles.take(maxItems)) {
            lines.add("• $itemTitle")
        }

        return lines.joinToString("\n")
    }

    private fun formatCount(count: Long): String =
        when {
            count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }

    private fun extractQueryParam(
        url: String,
        paramName: String,
    ): String? {
        val parsed =
            try {
                Url(url)
            } catch (_: Exception) {
                return null
            }
        return parsed.parameters
            .entries()
            .firstOrNull { it.key.equals(paramName, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }

    private fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) {
            "https://${url.substring(7)}"
        } else {
            url
        }
}
