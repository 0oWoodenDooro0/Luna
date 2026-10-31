package dev.socialpeek.resolver.generic

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Author
import dev.socialpeek.model.Media
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import dev.socialpeek.network.KtorSocialPeekHttpClient
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer
import io.ktor.http.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GenericWebResolver : PlatformResolver {
    override val platform: Platform = Platform.GENERIC

    companion object {
        val WEB_HEADERS =
            mapOf(
                HttpHeaders.UserAgent to KtorSocialPeekHttpClient.BOT_USER_AGENT,
            )
    }

    override fun canResolve(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return try {
            val parsed = Url(url)
            (parsed.protocol == URLProtocol.HTTP || parsed.protocol == URLProtocol.HTTPS) && parsed.host.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun resolve(
        url: String,
        client: SocialPeekHttpClient,
    ): PeekPost {
        val html =
            try {
                client.get(url, WEB_HEADERS)
            } catch (e: PostNotFoundException) {
                throw e
            } catch (e: Exception) {
                throw PostNotFoundException(url, "Failed to fetch web page: ${e.message}")
            }

        val doc = Jsoup.parse(html, url)

        val titleTag =
            doc
                .selectFirst("title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val ogTitle =
            doc
                .selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val twitterTitle =
            doc
                .selectFirst("meta[name=twitter:title]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val title = titleTag ?: ogTitle ?: twitterTitle

        val ogDescription =
            doc
                .selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc
                    .selectFirst("meta[name=description]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: doc
                    .selectFirst("meta[name=twitter:description]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

        val bodyText = extractBodyText(doc)
        val content = bodyText.ifBlank { ogDescription ?: "" }

        val authorName =
            doc
                .selectFirst("meta[name=author]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc
                    .selectFirst("meta[property=article:author]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: doc
                    .selectFirst("meta[name=twitter:creator]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: runCatching { Url(url).host }.getOrNull()
                ?: "web"

        val author = Author(username = authorName, displayName = authorName)

        val mediaList = mutableListOf<Media>()
        val ogImage =
            doc
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc
                    .selectFirst("meta[name=twitter:image]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
        if (!ogImage.isNullOrBlank()) {
            mediaList.add(Media.Image(url = ogImage, previewUrl = ogImage))
        }

        val rawMap = mutableMapOf<String, String>()
        if (ogDescription != null) {
            rawMap["description"] = ogDescription
            rawMap["og:description"] = ogDescription
        }
        if (ogTitle != null) {
            rawMap["og:title"] = ogTitle
        }
        if (title != null) {
            rawMap["title"] = title
        }

        val cleanUrl = UrlSanitizer.clean(url, Platform.GENERIC)
        val id = runCatching { Url(url).encodedPath.trim('/').ifEmpty { Url(url).host } }.getOrDefault(url)

        return PeekPost(
            platform = Platform.GENERIC,
            id = id,
            originalUrl = url,
            cleanUrl = cleanUrl,
            author = author,
            title = title,
            content = content,
            media = mediaList,
            rawData = rawMap,
        )
    }

    private fun extractBodyText(doc: Document): String {
        val article = doc.selectFirst("article")
        if (article != null) {
            val text = extractCleanText(article)
            if (text.isNotBlank()) return text
        }

        val main = doc.selectFirst("main")
        if (main != null) {
            val text = extractCleanText(main)
            if (text.isNotBlank()) return text
        }

        val paragraphs =
            doc
                .select("p")
                .map { cleanWhitespace(it.text()) }
                .filter { it.isNotBlank() }
        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("\n\n")
        }

        return ""
    }

    private fun extractCleanText(element: Element): String {
        val clone = element.clone()
        clone.select("script, style, noscript, nav, footer, header").remove()
        val pTags =
            clone
                .select("p")
                .map { cleanWhitespace(it.text()) }
                .filter { it.isNotBlank() }
        return if (pTags.isNotEmpty()) {
            pTags.joinToString("\n\n")
        } else {
            cleanWhitespace(clone.text())
        }
    }

    private fun cleanWhitespace(text: String): String =
        text
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
