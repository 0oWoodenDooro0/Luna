package dev.socialpeek.util

import io.ktor.http.*

internal sealed interface BilibiliTarget {
    data class Video(
        val bvid: String?,
        val aid: String?,
    ) : BilibiliTarget

    data class Opus(
        val opusId: String,
    ) : BilibiliTarget

    sealed interface ListTarget : BilibiliTarget {
        val mid: String
        val sid: String
    }

    data class CollectionDetail(
        override val mid: String,
        override val sid: String,
    ) : ListTarget

    data class SeriesDetail(
        override val mid: String,
        override val sid: String,
    ) : ListTarget

    data class Lists(
        override val mid: String,
        override val sid: String,
        val explicitType: String?,
    ) : ListTarget

    data object ShortLink : BilibiliTarget
}

internal object BilibiliUrlPolicy {
    private val VIDEO_HOSTS = setOf("www.bilibili.com", "bilibili.com", "m.bilibili.com")

    private val BV_PATTERN = Regex("""(BV[a-zA-Z0-9]{10})""", RegexOption.IGNORE_CASE)
    private val AV_PATTERN = Regex("""(?:bilibili\.com/video/|b23\.tv/)av([0-9]+)""", RegexOption.IGNORE_CASE)
    private val OPUS_URL_PATTERN = Regex("""(?:bilibili\.com/opus/|t\.bilibili\.com/)([0-9]+)""", RegexOption.IGNORE_CASE)
    private val B23_SHORT_URL_PATTERN = Regex("""https?://b23\.tv/[a-zA-Z0-9_-]+""", RegexOption.IGNORE_CASE)
    private val B23_VIDEO_PATH_PATTERN = Regex("""^/(BV[a-zA-Z0-9]{10}|av[0-9]+)/?$""", RegexOption.IGNORE_CASE)

    private val VIDEO_PATH_REGEX =
        Regex("""^/video/(?:BV[a-zA-Z0-9]+|av[0-9]+)/?$""", RegexOption.IGNORE_CASE)
    private val OPUS_PATH_REGEX = Regex("""^/opus/[0-9]+/?$""")
    private val DYNAMIC_PATH_REGEX = Regex("""^/[0-9]+/?$""")
    private val COLLECTION_DETAIL_PATH_REGEX =
        Regex("""^/([0-9]+)/channel/collectiondetail/?$""", RegexOption.IGNORE_CASE)
    private val SERIES_DETAIL_PATH_REGEX =
        Regex("""^/([0-9]+)/channel/seriesdetail/?$""", RegexOption.IGNORE_CASE)
    private val LISTS_PATH_REGEX =
        Regex("""^/([0-9]+)/lists/([0-9]+)/?$""", RegexOption.IGNORE_CASE)

    private enum class RouteType {
        VIDEO {
            override fun isAllowedParam(name: String): Boolean {
                val lower = name.lowercase()
                return lower == "t" || lower == "p"
            }

            override fun shouldStripOnClean(
                name: String,
                values: List<String>?,
            ): Boolean {
                val lower = name.lowercase()
                return when (lower) {
                    "t" -> false
                    "p" -> values?.all { it == "1" } == true
                    else -> true
                }
            }
        },
        DYNAMIC {
            override fun isAllowedParam(name: String): Boolean = false
        },
        COLLECTION_OR_SERIES {
            override fun isAllowedParam(name: String): Boolean {
                val lower = name.lowercase()
                return lower == "sid" || lower == "type"
            }
        },
        ;

        abstract fun isAllowedParam(name: String): Boolean

        open fun shouldStripOnClean(
            name: String,
            values: List<String>?,
        ): Boolean = !isAllowedParam(name)
    }

    /**
     * Canonicalizes trailing slash and enforces route-scoped query whitelisting when [builder]
     * points to a supported Bilibili content route. Returns true if handled, false otherwise.
     */
    fun sanitizeIfSupportedRoute(builder: URLBuilder): Boolean {
        val route = detectRouteType(builder.host, builder.encodedPath) ?: return false
        if (!builder.encodedPath.endsWith("/")) {
            builder.encodedPath = "${builder.encodedPath}/"
        }
        val toRemove =
            builder.parameters.names().toList().filter { param ->
                route.shouldStripOnClean(param, builder.parameters.getAll(param))
            }
        for (param in toRemove) {
            builder.parameters.remove(param)
        }
        return true
    }

    /**
     * Evaluates route-scoped tracking status when [parsed] points to a supported Bilibili
     * content route. Returns null if the route is unsupported so caller falls back to blacklist.
     */
    fun hasTrackingParamsIfSupportedRoute(parsed: Url): Boolean? {
        val route = detectRouteType(parsed.host, parsed.encodedPath) ?: return null
        return parsed.parameters.names().any { !route.isAllowedParam(it) }
    }

    fun canResolve(url: String): Boolean = matchTarget(url) != null

    fun isShortLink(url: String): Boolean = B23_SHORT_URL_PATTERN.containsMatchIn(url)

    fun isExpandableShortLink(url: String): Boolean =
        B23_SHORT_URL_PATTERN.containsMatchIn(url) &&
            !BV_PATTERN.containsMatchIn(url) &&
            !AV_PATTERN.containsMatchIn(url) &&
            !OPUS_URL_PATTERN.containsMatchIn(url)

    fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) {
            "https://${url.substring(7)}"
        } else {
            url
        }

    fun canonicalizeB23DirectVideoUrl(url: String): String {
        val parsed = parseHttpUrlOrNull(url) ?: return url
        if (!parsed.host.equals("b23.tv", ignoreCase = true)) return url
        val match = B23_VIDEO_PATH_PATTERN.find(parsed.encodedPath) ?: return url
        val videoId = match.groupValues[1]
        return URLBuilder(parsed)
            .apply {
                protocol = URLProtocol.HTTPS
                host = "www.bilibili.com"
                encodedPath = "/video/$videoId/"
            }.buildString()
    }

    fun matchTarget(url: String): BilibiliTarget? {
        val bvMatch = BV_PATTERN.find(url)
        if (bvMatch != null) {
            return BilibiliTarget.Video(bvid = bvMatch.groupValues[1], aid = null)
        }

        val avMatch = AV_PATTERN.find(url)
        if (avMatch != null) {
            return BilibiliTarget.Video(bvid = null, aid = avMatch.groupValues[1])
        }

        val opusMatch = OPUS_URL_PATTERN.find(url)
        if (opusMatch != null) {
            return BilibiliTarget.Opus(opusId = opusMatch.groupValues[1])
        }

        val spaceTarget = matchSpaceTarget(url)
        if (spaceTarget != null) {
            return spaceTarget
        }

        if (B23_SHORT_URL_PATTERN.containsMatchIn(url)) {
            return BilibiliTarget.ShortLink
        }

        return null
    }

    private fun detectRouteType(
        host: String,
        encodedPath: String,
    ): RouteType? {
        val h = host.lowercase()
        return when {
            h in VIDEO_HOSTS -> {
                when {
                    VIDEO_PATH_REGEX.matches(encodedPath) -> RouteType.VIDEO
                    OPUS_PATH_REGEX.matches(encodedPath) -> RouteType.DYNAMIC
                    else -> null
                }
            }

            h == "t.bilibili.com" -> {
                if (DYNAMIC_PATH_REGEX.matches(encodedPath)) RouteType.DYNAMIC else null
            }

            h == "space.bilibili.com" -> {
                if (COLLECTION_DETAIL_PATH_REGEX.matches(encodedPath) ||
                    SERIES_DETAIL_PATH_REGEX.matches(encodedPath) ||
                    LISTS_PATH_REGEX.matches(encodedPath)
                ) {
                    RouteType.COLLECTION_OR_SERIES
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }
    }

    private fun matchSpaceTarget(url: String): BilibiliTarget? {
        val parsed = parseHttpUrlOrNull(url) ?: return null
        if (!parsed.host.equals("space.bilibili.com", ignoreCase = true)) return null

        COLLECTION_DETAIL_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { match ->
            val sid = extractNumericParam(parsed, "sid") ?: return null
            return BilibiliTarget.CollectionDetail(mid = match.groupValues[1], sid = sid)
        }
        SERIES_DETAIL_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { match ->
            val sid = extractNumericParam(parsed, "sid") ?: return null
            return BilibiliTarget.SeriesDetail(mid = match.groupValues[1], sid = sid)
        }
        LISTS_PATH_REGEX.matchEntire(parsed.encodedPath)?.let { match ->
            val explicitType =
                parsed.parameters
                    .entries()
                    .firstOrNull { it.key.equals("type", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
            return BilibiliTarget.Lists(mid = match.groupValues[1], sid = match.groupValues[2], explicitType = explicitType)
        }
        return null
    }

    private fun extractNumericParam(
        parsed: Url,
        paramName: String,
    ): String? =
        parsed.parameters
            .entries()
            .firstOrNull { it.key.equals(paramName, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun parseHttpUrlOrNull(url: String): Url? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return try {
            Url(url)
        } catch (_: Exception) {
            null
        }
    }
}
