package dev.socialpeek.util

import dev.socialpeek.model.Platform
import io.ktor.http.*

object UrlSanitizer {

    private val GLOBAL_TRACKING_PARAMS = setOf(
        // Google / UTM
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "gclid", "gclsrc",
        // Meta / Facebook
        "fbclid", "fbid",
        // Ads & Referral
        "ref", "ref_src", "ref_url", "source", "campaign", "affiliate_id", "click_id", "_openstat"
    )

    private val PLATFORM_BLACKLIST = mapOf(
        Platform.FACEBOOK to setOf(
            "mibextid", "share_url", "fs", "rdid", "sfnsn", "paipv", "eav", "feed_story_type",
            "__tn__", "__cft__", "acontext", "epa", "entry_point", "extid", "notif_t", "notif_id",
            "substory_index", "checkpoint_src"
        ),
        Platform.X to setOf("s", "t", "cn", "ref_src", "ref_url"),
        Platform.INSTAGRAM to setOf("igsh"),
        Platform.THREADS to setOf("xmt", "s"),
        Platform.BILIBILI to setOf(
            "spm_id_from", "vd_source", "from_source", "from", "is_story_h5", "mid",
            "share_source", "share_medium", "share_plat", "share_session_id", "share_tag", "unique_k"
        ),
        Platform.YOUTUBE to setOf(
            "si", "feature", "app", "pp", "embeds_referring_euri", "embeds_referring_origin"
        ),
        Platform.REDDIT to setOf(
            "utm_source", "utm_medium", "utm_name", "utm_term", "utm_content", "share_id", "rdt"
        )
    )

    private val PLATFORM_WHITELIST = mapOf(
        Platform.FACEBOOK to setOf("fbid", "story_fbid", "v", "id", "comment_id", "set"),
        Platform.INSTAGRAM to setOf("img_index"),
        Platform.BILIBILI to setOf("p", "t"),
        Platform.YOUTUBE to setOf("v", "t", "list", "index"),
        Platform.REDDIT to setOf("context")
    )

    private val REDDIT_POST_PATH_REGEX = Regex(
        """^/r/([a-zA-Z0-9_]+)/comments/([a-zA-Z0-9]+)(?:/[^/]+)?/?$""",
        RegexOption.IGNORE_CASE
    )
    private val REDDIT_SHORT_PATH_REGEX = Regex(
        """^/comments/([a-zA-Z0-9]+)(?:/[^/]+)?/?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 清洗 URL，去除所有通用與特定平台的 tracking/行銷參數。
     * 保留必要的功能性參數 (如時間戳 t、分頁 p、播放清單 list)。
     *
     * @param url 待清洗的原始 URL
     * @param platform 可選的平台提示；若為 null 則會自動依網域名稱判斷
     * @return 清洗後乾淨的標準化 URL
     */
    fun clean(url: String, platform: Platform? = null): String {
        if (url.isBlank()) return url

        val trimmed = url.trim()

        // Ensure URL has a scheme for proper parsing
        val hasScheme = trimmed.contains("://")
        val inputWithScheme = if (!hasScheme) {
            "https://$trimmed"
        } else {
            trimmed
        }

        val parsed = try {
            Url(inputWithScheme)
        } catch (_: Exception) {
            // If Ktor fails to parse, return original url safely
            return trimmed
        }

        val detectedPlatform = platform ?: detectPlatformFromHost(parsed.host)
        val builder = URLBuilder(parsed)

        // Normalize protocol for known platforms or URLs without scheme
        if (detectedPlatform != null && detectedPlatform != Platform.GENERIC) {
            builder.protocol = URLProtocol.HTTPS
        } else if (!hasScheme) {
            builder.protocol = URLProtocol.HTTPS
        }

        // Normalize apex host for standard platforms
        when (builder.host.lowercase()) {
            "facebook.com" -> builder.host = "www.facebook.com"
            "fb.com" -> builder.host = "www.facebook.com"
            "m.facebook.com" -> builder.host = "www.facebook.com"
            "instagram.com" -> builder.host = "www.instagram.com"
            "threads.net" -> builder.host = "www.threads.net"
            "bilibili.com" -> builder.host = "www.bilibili.com"
            "reddit.com" -> builder.host = "www.reddit.com"
            "youtube.com" -> builder.host = "www.youtube.com"
        }

        // Default port cleanup
        if (builder.port == builder.protocol.defaultPort) {
            builder.port = 0
        }

        // Platform-specific path canonicalization
        if (detectedPlatform == Platform.REDDIT) {
            val postMatch = REDDIT_POST_PATH_REGEX.find(builder.encodedPath)
            if (postMatch != null) {
                val sub = postMatch.groupValues[1]
                val id = postMatch.groupValues[2]
                builder.encodedPath = "/r/$sub/comments/$id/"
            } else {
                val shortMatch = REDDIT_SHORT_PATH_REGEX.find(builder.encodedPath)
                if (shortMatch != null) {
                    val id = shortMatch.groupValues[1]
                    builder.encodedPath = "/comments/$id/"
                }
            }
        } else if (detectedPlatform == Platform.INSTAGRAM) {
            // Instagram canonical post URLs end with /
            if (builder.encodedPath.matches(Regex("""^/(?:p|reel|tv)/[^/]+$"""))) {
                builder.encodedPath = "${builder.encodedPath}/"
            }
        } else if (detectedPlatform == Platform.FACEBOOK) {
            // Facebook canonical reel/post URLs end with /
            if (builder.encodedPath.matches(Regex("""^/(?:[^/]+/)?(?:reel|posts)/[^/]+$"""))) {
                builder.encodedPath = "${builder.encodedPath}/"
            }
        }

        // Filter tracking parameters
        val currentParamNames = builder.parameters.names().toList()
        val toRemove = currentParamNames.filter { isTrackingParam(it, detectedPlatform) }
        for (param in toRemove) {
            builder.parameters.remove(param)
        }

        if (builder.parameters.isEmpty()) {
            builder.trailingQuery = false
        }

        val cleaned = builder.buildString()
        return cleaned.removeSuffix("?")
    }

    /**
     * 檢查給定的 URL 是否包含任何已知的追蹤/行銷參數。
     */
    fun hasTrackingParams(url: String, platform: Platform? = null): Boolean {
        if (!url.contains("?")) return false

        val hasScheme = url.contains("://")
        val inputWithScheme = if (!hasScheme) "https://$url" else url

        val parsed = try {
            Url(inputWithScheme)
        } catch (_: Exception) {
            return false
        }

        val detectedPlatform = platform ?: detectPlatformFromHost(parsed.host)
        return parsed.parameters.names().any { isTrackingParam(it, detectedPlatform) }
    }

    /**
     * 判斷給定參數名稱是否為指定平台的追蹤參數。
     */
    private fun isTrackingParam(name: String, platform: Platform?): Boolean {
        val lower = name.lowercase()
        // 1. Whitelist protection
        if (platform != null && PLATFORM_WHITELIST[platform]?.contains(lower) == true) {
            return false
        }
        // 2. UTM wildcard matching
        if (lower.startsWith("utm_")) {
            return true
        }
        // 3. Global tracking blacklist
        if (GLOBAL_TRACKING_PARAMS.contains(lower)) {
            return true
        }
        // 4. Platform-specific blacklist
        if (platform != null && PLATFORM_BLACKLIST[platform]?.contains(lower) == true) {
            return true
        }
        return false
    }

    /**
     * 根據 host 推斷對應的社群平台。
     */
    private fun detectPlatformFromHost(host: String): Platform? {
        val h = host.lowercase()
        return when {
            h == "facebook.com" || h.endsWith(".facebook.com") || h == "fb.com" || h.endsWith(".fb.com") || h == "fb.watch" || h.endsWith(".fb.watch") || h == "fb.me" || h.endsWith(".fb.me") -> Platform.FACEBOOK
            h == "bilibili.com" || h.endsWith(".bilibili.com") || h == "b23.tv" || h.endsWith(".b23.tv") -> Platform.BILIBILI
            h == "x.com" || h.endsWith(".x.com") || h == "twitter.com" || h.endsWith(".twitter.com") || h == "t.co" || h.endsWith(".t.co") -> Platform.X
            h == "instagram.com" || h.endsWith(".instagram.com") || h == "instagr.am" || h.endsWith(".instagr.am") -> Platform.INSTAGRAM
            h == "threads.net" || h.endsWith(".threads.net") || h == "threads.com" || h.endsWith(".threads.com") -> Platform.THREADS
            h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtu.be" || h.endsWith(".youtu.be") -> Platform.YOUTUBE
            h == "reddit.com" || h.endsWith(".reddit.com") || h == "redd.it" || h.endsWith(".redd.it") -> Platform.REDDIT
            else -> null
        }
    }
}
