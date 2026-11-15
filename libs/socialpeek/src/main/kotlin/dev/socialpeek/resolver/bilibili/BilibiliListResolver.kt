package dev.socialpeek.resolver.bilibili

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.model.*
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.util.BilibiliTarget
import dev.socialpeek.util.BilibiliUrlPolicy
import dev.socialpeek.util.UrlSanitizer
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

internal class BilibiliListResolver(
    private val apiClient: BilibiliApiClient,
) {
    private data class ListRequestContext(
        val sid: String,
        val fallbackMid: String,
        val originalUrl: String,
        val currentUrl: String,
        val client: SocialPeekHttpClient,
    )

    private data class ListHeader(
        val resolvedId: String,
        val mid: String,
        val title: String?,
        val createdAtEpochSeconds: Long?,
    )

    private data class ListPreview(
        val coverUrl: String?,
        val firstBvid: String?,
        val fallbackAuthorName: String?,
        val content: String,
        val metrics: Metrics?,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(BilibiliListResolver::class.java)

        private const val DEFAULT_COVER_MARKER = "viedeo_material_default.png"
        private const val MAX_DESCRIPTION_LENGTH = 90
        private const val DESCRIPTION_ELLIPSIS_KEEP_LENGTH = 87
        private const val MAX_SUMMARY_LINES = 5
        private const val MAX_SUMMARY_LENGTH = 250
        private const val SUMMARY_ELLIPSIS_KEEP_LENGTH = 247
        private const val MAX_FEATURED_ITEMS = 3
        private const val SERIES_ARCHIVES_PAGE_SIZE = 10
        private const val MILLION = 1_000_000
        private const val THOUSAND = 1_000
        private val LINE_BREAK_WHITESPACE_REGEX = Regex("""\s*\r?\n+\s*""")
    }

    suspend fun resolve(
        target: BilibiliTarget.ListTarget,
        originalUrl: String,
        currentUrl: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val context = ListRequestContext(target.sid, target.mid, originalUrl, currentUrl, client)
        return when (target) {
            is BilibiliTarget.CollectionDetail -> {
                resolveCollection(context).first
            }

            is BilibiliTarget.SeriesDetail -> {
                resolveSeries(context)
            }

            is BilibiliTarget.Lists -> {
                when (target.explicitType) {
                    "season" -> resolveCollection(context).first
                    "series" -> resolveSeries(context)
                    null -> resolveListsWithFallback(context)
                    else -> throw ParsingException(currentUrl, "Unsupported Bilibili lists type '${target.explicitType}' in URL: $currentUrl")
                }
            }
        }
    }

    private suspend fun resolveListsWithFallback(context: ListRequestContext): PeekPost {
        val seasonResult =
            try {
                resolveCollection(context)
            } catch (e: Exception) {
                logger.warn("Bilibili /lists/{} season lookup failed ({}), falling back to series", context.sid, e.message)
                null
            }

        val emptySeasonPost =
            seasonResult?.let { (seasonPost, hasMedias) ->
                if (hasMedias) return seasonPost
                logger.warn("Bilibili /lists/{} season lookup returned empty media list, falling back to series", context.sid)
                seasonPost
            }

        return try {
            resolveSeries(context)
        } catch (e: Exception) {
            if (emptySeasonPost != null) {
                logger.warn(
                    "Bilibili /lists/{} series fallback also failed ({}), keeping empty season metadata",
                    context.sid,
                    e.message,
                )
                emptySeasonPost
            } else {
                throw e
            }
        }
    }

    private suspend fun resolveCollection(context: ListRequestContext): Pair<PeekPost, Boolean> {
        val apiUrl = "https://api.bilibili.com/x/space/fav/season/list?season_id=${context.sid}"
        val data = apiClient.getJsonData(apiUrl, context.originalUrl, "collection API", context.client)
        val info =
            data["info"]?.jsonObject
                ?: throw ParsingException(context.originalUrl, "Missing 'info' field in Bilibili collection response")

        val resolvedId = info["id"]?.jsonPrimitive?.contentOrNull ?: context.sid
        val title = info["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawCover = info["cover"]?.jsonPrimitive?.contentOrNull
        val intro = info["intro"]?.jsonPrimitive?.contentOrNull ?: ""
        val medias = data["medias"]?.jsonArray ?: JsonArray(emptyList())
        val totalCount = info["media_count"]?.jsonPrimitive?.longOrNull ?: medias.size.toLong()

        val upper = info["upper"]?.jsonObject
        val mid = upper?.get("mid")?.jsonPrimitive?.contentOrNull ?: context.fallbackMid
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

        val post =
            assembleListPost(
                context = context,
                header = ListHeader(resolvedId, mid, title, firstPubtime),
                preview =
                    ListPreview(
                        coverUrl = resolveListCover(rawCover, firstItemCover),
                        firstBvid = firstBvid,
                        fallbackAuthorName = upperName,
                        content = formatListContent(totalCount, playCount, intro, extractItemTitles(medias)),
                        metrics =
                            if (playCount != null || collectCount != null) {
                                Metrics(views = playCount, bookmarks = collectCount)
                            } else {
                                null
                            },
                    ),
            )
        return post to medias.isNotEmpty()
    }

    private suspend fun resolveSeries(context: ListRequestContext): PeekPost {
        val metaUrl = "https://api.bilibili.com/x/series/series?series_id=${context.sid}"
        val metaData = apiClient.getJsonData(metaUrl, context.originalUrl, "series metadata", context.client)
        val meta =
            metaData["meta"]?.jsonObject
                ?: throw ParsingException(context.originalUrl, "Missing 'data.meta' field in Bilibili series response")

        val resolvedId = meta["series_id"]?.jsonPrimitive?.contentOrNull ?: context.sid
        val mid = meta["mid"]?.jsonPrimitive?.contentOrNull ?: context.fallbackMid
        val title = meta["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val description = meta["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val metaTotal = meta["total"]?.jsonPrimitive?.longOrNull
        val updatedAt =
            meta["last_update_ts"]?.jsonPrimitive?.longOrNull
                ?: meta["mtime"]?.jsonPrimitive?.longOrNull
                ?: meta["ctime"]?.jsonPrimitive?.longOrNull

        val archivesUrl =
            "https://api.bilibili.com/x/series/archives?mid=$mid&series_id=${context.sid}" +
                "&only_normal=true&sort=desc&pn=1&ps=$SERIES_ARCHIVES_PAGE_SIZE"
        val archivesData = apiClient.getJsonData(archivesUrl, context.originalUrl, "series archives", context.client)
        val pageTotal =
            archivesData["page"]
                ?.jsonObject
                ?.get("total")
                ?.jsonPrimitive
                ?.longOrNull
        val archives = archivesData["archives"]?.jsonArray ?: JsonArray(emptyList())
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

        return assembleListPost(
            context = context,
            header = ListHeader(resolvedId, mid, title, updatedAt ?: firstPubdate),
            preview =
                ListPreview(
                    coverUrl = resolveListCover(rawCover = null, firstItemCover = firstItemPic),
                    firstBvid = firstBvid,
                    fallbackAuthorName = null,
                    content = formatListContent(totalCount, playCount = null, description, extractItemTitles(archives)),
                    metrics = null,
                ),
        )
    }

    private suspend fun assembleListPost(
        context: ListRequestContext,
        header: ListHeader,
        preview: ListPreview,
    ): PeekPost {
        val mediaList =
            if (!preview.coverUrl.isNullOrBlank()) {
                listOf(Media.Image(url = preview.coverUrl, previewUrl = preview.coverUrl))
            } else {
                emptyList()
            }

        val author =
            enrichCreator(
                firstBvid = preview.firstBvid,
                mid = header.mid,
                fallbackName = preview.fallbackAuthorName,
                client = context.client,
            )

        return PeekPost(
            platform = Platform.BILIBILI,
            id = header.resolvedId,
            originalUrl = context.originalUrl,
            cleanUrl = UrlSanitizer.clean(context.currentUrl, Platform.BILIBILI),
            author = author,
            title = header.title,
            content = preview.content,
            media = mediaList,
            metrics = preview.metrics,
            createdAtEpochSeconds = header.createdAtEpochSeconds,
        )
    }

    private suspend fun enrichCreator(
        firstBvid: String?,
        mid: String,
        fallbackName: String?,
        client: SocialPeekHttpClient,
    ): Author {
        val owner =
            if (!firstBvid.isNullOrBlank()) {
                val viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=$firstBvid"
                apiClient
                    .getJsonDataOrNull(viewUrl, "Creator enrichment (bvid=$firstBvid, mid=$mid)", client)
                    ?.get("owner")
                    ?.jsonObject
            } else {
                null
            }
        return apiClient.extractOwnerAuthor(owner = owner, mid = mid, fallbackName = fallbackName ?: "UID $mid")
    }

    private fun extractItemTitles(items: JsonArray): List<String> =
        items.mapNotNull { elem ->
            elem.jsonObject["title"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }

    private fun resolveListCover(
        rawCover: String?,
        firstItemCover: String?,
    ): String? {
        val isMissingOrPlaceholder =
            rawCover.isNullOrBlank() || rawCover.contains(DEFAULT_COVER_MARKER, ignoreCase = true)
        val chosen =
            if (isMissingOrPlaceholder) {
                firstItemCover?.takeIf {
                    it.isNotBlank() && !it.contains(DEFAULT_COVER_MARKER, ignoreCase = true)
                }
            } else {
                rawCover
            }
        return chosen?.let(BilibiliUrlPolicy::upgradeToHttps)
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
                .replace(LINE_BREAK_WHITESPACE_REGEX, " ")
        if (normalizedDesc.isNotBlank()) {
            val truncatedDesc =
                if (normalizedDesc.length > MAX_DESCRIPTION_LENGTH) {
                    normalizedDesc.take(DESCRIPTION_ELLIPSIS_KEEP_LENGTH).trimEnd() + "..."
                } else {
                    normalizedDesc
                }
            lines.add(truncatedDesc)
        }

        val maxItems = minOf(MAX_FEATURED_ITEMS, MAX_SUMMARY_LINES - lines.size)
        for (itemTitle in itemTitles.take(maxItems)) {
            lines.add("• $itemTitle")
        }

        val joined = lines.joinToString("\n")
        return if (joined.length > MAX_SUMMARY_LENGTH) {
            joined.take(SUMMARY_ELLIPSIS_KEEP_LENGTH).trimEnd() + "..."
        } else {
            joined
        }
    }

    private fun formatCount(count: Long): String =
        when {
            count >= MILLION -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
            count >= THOUSAND -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
}
