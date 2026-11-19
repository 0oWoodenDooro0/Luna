package luna.core.ai

import dev.kord.common.entity.MessageStickerType
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Message
import dev.kord.core.entity.StickerItem
import io.ktor.http.Url
import org.slf4j.LoggerFactory

/**
 * Collaborator responsible for extracting Discord sticker and Tenor/Giphy GIF embed multimodal
 * blocks, validating CDN hosts/schemes/image formats, and formatting sticker/GIF transcript
 * annotations while degrading vector `LOTTIE` and unsupported/malformed metadata safely.
 */
object DiscordMediaEmbedExtractor {
    const val STICKER_CDN_BASE_URL = "https://cdn.discordapp.com/stickers"
    const val STICKER_PLACEHOLDER = "[貼圖]"
    const val GIF_PLACEHOLDER = "[GIF]"

    private val logger = LoggerFactory.getLogger(DiscordMediaEmbedExtractor::class.java)
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val TENOR_OR_GIPHY_PROVIDERS = setOf("tenor", "giphy")
    private val EXTENSION_TO_MIME_TYPE =
        mapOf(
            "gif" to "image/gif",
            "png" to "image/png",
            "webp" to "image/webp",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
        )

    fun formatStickerAnnotation(sticker: StickerItem): String {
        val cleanName =
            runCatching { sticker.name }
                .getOrNull()
                ?.replace(WHITESPACE_REGEX, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        return if (cleanName != null) {
            "[貼圖: $cleanName]"
        } else {
            STICKER_PLACEHOLDER
        }
    }

    fun formatStickerAnnotations(message: Message): String {
        val stickers =
            runCatching { message.stickers }
                .onFailure { e ->
                    logger.debug(
                        "Failed to read stickers on message {}: {}",
                        runCatching { message.id }.getOrNull(),
                        e.message ?: e::class.simpleName,
                    )
                }.getOrNull()
                .orEmpty()
        if (stickers.isEmpty()) return ""
        return stickers.joinToString(" ") { formatStickerAnnotation(it) }
    }

    fun extractStickerImages(message: Message): List<ChatImageAttachment> {
        val messageId = runCatching { message.id }.getOrNull()
        val stickers =
            runCatching { message.stickers }
                .onFailure { e ->
                    logger.warn(
                        "Failed to read stickers on message {} ({}); degrading without sticker images",
                        messageId,
                        e.message ?: e::class.simpleName,
                    )
                }.getOrNull()
                .orEmpty()

        if (stickers.isEmpty()) return emptyList()

        return stickers.mapNotNull { sticker ->
            val stickerId =
                runCatching { sticker.id }
                    .onFailure { e ->
                        logger.warn(
                            "Malformed sticker metadata (missing id) on message {}: {}",
                            messageId,
                            e.message ?: e::class.simpleName,
                        )
                    }.getOrNull() ?: return@mapNotNull null

            val formatType =
                runCatching { sticker.formatType }
                    .onFailure { e ->
                        logger.warn(
                            "Malformed sticker formatType for sticker {} on message {}: {}",
                            stickerId,
                            messageId,
                            e.message ?: e::class.simpleName,
                        )
                    }.getOrNull() ?: return@mapNotNull null

            val cleanName =
                runCatching { sticker.name }
                    .getOrNull()
                    ?.replace(WHITESPACE_REGEX, " ")
                    ?.replace("/", "_")
                    ?.replace("\\", "_")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            when (formatType) {
                MessageStickerType.PNG,
                MessageStickerType.APNG,
                -> {
                    val filename =
                        when {
                            cleanName == null -> "sticker-${stickerId.value}.png"
                            cleanName.endsWith(".png", ignoreCase = true) -> cleanName
                            else -> "$cleanName.png"
                        }
                    ChatImageAttachment(
                        url = "$STICKER_CDN_BASE_URL/${stickerId.value}.png",
                        mimeType = "image/png",
                        filename = filename,
                    )
                }

                MessageStickerType.GIF -> {
                    val filename =
                        when {
                            cleanName == null -> "sticker-${stickerId.value}.gif"
                            cleanName.endsWith(".gif", ignoreCase = true) -> cleanName
                            else -> "$cleanName.gif"
                        }
                    ChatImageAttachment(
                        url = "$STICKER_CDN_BASE_URL/${stickerId.value}.gif",
                        mimeType = "image/gif",
                        filename = filename,
                    )
                }

                MessageStickerType.LOTTIE -> {
                    logger.debug(
                        "Degrading vector LOTTIE sticker '{}' (id={}) on message {} to text placeholder",
                        cleanName ?: "",
                        stickerId,
                        messageId,
                    )
                    null
                }

                is MessageStickerType.Unknown -> {
                    logger.warn(
                        "Rejected unsupported Discord sticker formatType={} (name='{}', id={}) on message {}",
                        formatType.value,
                        cleanName ?: "",
                        stickerId,
                        messageId,
                    )
                    null
                }
            }
        }
    }

    fun isTenorOrGiphyEmbed(embed: Embed): Boolean {
        val provider = runCatching { embed.provider }.getOrNull()
        val providerName = runCatching { provider?.name }.getOrNull()?.trim()?.lowercase()
        if (providerName != null && providerName in TENOR_OR_GIPHY_PROVIDERS) {
            return true
        }

        val providerUrl = runCatching { provider?.url }.getOrNull()
        if (hasTenorOrGiphyHost(providerUrl)) {
            return true
        }

        val embedUrl = runCatching { embed.url }.getOrNull()
        if (hasTenorOrGiphyHost(embedUrl)) {
            return true
        }

        val image = runCatching { embed.image }.getOrNull()
        val thumbnail = runCatching { embed.thumbnail }.getOrNull()
        val video = runCatching { embed.video }.getOrNull()

        return hasTenorOrGiphyOrProxiedHost(runCatching { image?.url }.getOrNull()) ||
            hasTenorOrGiphyOrProxiedHost(runCatching { image?.proxyUrl }.getOrNull()) ||
            hasTenorOrGiphyOrProxiedHost(runCatching { thumbnail?.url }.getOrNull()) ||
            hasTenorOrGiphyOrProxiedHost(runCatching { thumbnail?.proxyUrl }.getOrNull()) ||
            hasTenorOrGiphyHost(runCatching { video?.url }.getOrNull())
    }

    fun extractGifEmbedImages(message: Message): List<ChatImageAttachment> = extractGifEmbedEntries(message).map { it.attachment }

    fun extractCapturedGifEmbedPageUrls(message: Message): List<String> = extractGifEmbedEntries(message).mapNotNull { it.embedPageUrl }

    private data class ExtractedGifEmbed(
        val attachment: ChatImageAttachment,
        val embedPageUrl: String?,
    )

    private fun extractGifEmbedEntries(message: Message): List<ExtractedGifEmbed> {
        val messageId = runCatching { message.id }.getOrNull()
        val embeds =
            runCatching { message.embeds }
                .onFailure { e ->
                    logger.warn(
                        "Failed to read embeds on message {} ({}); degrading without GIF embed images",
                        messageId,
                        e.message ?: e::class.simpleName,
                    )
                }.getOrNull()
                .orEmpty()

        if (embeds.isEmpty()) return emptyList()

        return embeds.mapNotNull { embed ->
            extractSingleGifEmbed(embed, messageId)
        }
    }

    private fun extractSingleGifEmbed(
        embed: Embed,
        messageId: Any?,
    ): ExtractedGifEmbed? {
        if (!isTenorOrGiphyEmbed(embed)) {
            return null
        }

        // Probe embed fields defensively so a single corrupt field logs and degrades safely
        val providerResult = runCatching { embed.provider }
        providerResult.onFailure { e ->
            logger.warn(
                "Malformed provider metadata on Tenor/Giphy embed on message {}: {}",
                messageId,
                e.message ?: e::class.simpleName,
            )
        }
        val imageResult = runCatching { embed.image }
        imageResult.onFailure { e ->
            logger.warn(
                "Malformed image metadata on Tenor/Giphy embed on message {}: {}",
                messageId,
                e.message ?: e::class.simpleName,
            )
        }
        val thumbnailResult = runCatching { embed.thumbnail }
        thumbnailResult.onFailure { e ->
            logger.warn(
                "Malformed thumbnail metadata on Tenor/Giphy embed on message {}: {}",
                messageId,
                e.message ?: e::class.simpleName,
            )
        }

        val image = imageResult.getOrNull()
        val thumbnail = thumbnailResult.getOrNull()
        val embedUrl = runCatching { embed.url }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val previewCandidates =
            listOfNotNull(
                runCatching { image?.url }.getOrNull(),
                runCatching { image?.proxyUrl }.getOrNull(),
                runCatching { thumbnail?.url }.getOrNull(),
                runCatching { thumbnail?.proxyUrl }.getOrNull(),
            ).mapNotNull { it.trim().takeIf { trimmed -> trimmed.isNotEmpty() } }

        for (candidate in previewCandidates) {
            val validated = validatePreviewImageUrl(candidate, messageId)
            if (validated != null) {
                return ExtractedGifEmbed(attachment = validated, embedPageUrl = embedUrl)
            }
        }

        if (previewCandidates.isEmpty() && embedUrl != null) {
            val validatedDirect = validateDirectGifEmbedUrl(embedUrl, messageId)
            if (validatedDirect != null) {
                return ExtractedGifEmbed(attachment = validatedDirect, embedPageUrl = embedUrl)
            }
        }

        logger.debug(
            "No valid preview image extracted for Tenor/Giphy embed (url='{}') on message {}; degrading to text marker",
            embedUrl ?: "",
            messageId,
        )
        return null
    }

    private fun validatePreviewImageUrl(
        rawUrl: String,
        messageId: Any?,
    ): ChatImageAttachment? {
        val parsed = parseHttpsUrl(rawUrl)
        if (parsed == null) {
            logger.warn(
                "Rejected non-HTTPS or malformed GIF embed preview URL '{}' on message {}",
                rawUrl,
                messageId,
            )
            return null
        }

        if (!isWhitelistedPreviewHost(parsed)) {
            logger.warn(
                "Rejected non-whitelisted GIF embed preview host '{}' (url='{}') on message {}",
                parsed.host,
                rawUrl,
                messageId,
            )
            return null
        }

        val mimeType = resolveImageMimeTypeFromPath(parsed.encodedPath)
        if (mimeType == null) {
            logger.warn(
                "Rejected unsupported GIF embed preview format '{}' (path='{}') on message {}",
                rawUrl,
                parsed.encodedPath,
                messageId,
            )
            return null
        }

        val filename = parsed.encodedPath.substringAfterLast('/').takeIf { it.isNotBlank() }
        return ChatImageAttachment(
            url = rawUrl,
            mimeType = mimeType,
            filename = filename,
        )
    }

    private fun validateDirectGifEmbedUrl(
        rawUrl: String,
        messageId: Any?,
    ): ChatImageAttachment? {
        val parsed = parseHttpsUrl(rawUrl) ?: return null
        if (!isTenorOrGiphyHost(parsed.host.lowercase())) return null
        val mimeType = resolveImageMimeTypeFromPath(parsed.encodedPath)
        if (mimeType == null) {
            logger.debug(
                "Tenor/Giphy embed URL '{}' on message {} has no raster image preview extension",
                rawUrl,
                messageId,
            )
            return null
        }
        val filename = parsed.encodedPath.substringAfterLast('/').takeIf { it.isNotBlank() }
        return ChatImageAttachment(
            url = rawUrl,
            mimeType = mimeType,
            filename = filename,
        )
    }

    private fun resolveImageMimeTypeFromPath(encodedPath: String): String? {
        val segment = encodedPath.substringAfterLast('/')
        val ext = segment.substringAfterLast('.', "").lowercase()
        return EXTENSION_TO_MIME_TYPE[ext]
    }

    private fun parseHttpsUrl(rawUrl: String): Url? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        val parsed = runCatching { Url(trimmed) }.getOrNull() ?: return null
        if (!parsed.protocol.name.equals("https", ignoreCase = true)) return null
        if (parsed.host.isBlank()) return null
        return parsed
    }

    private fun hasTenorOrGiphyHost(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val parsed = runCatching { Url(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = parsed.protocol.name.lowercase()
        if (scheme != "https" && scheme != "http") return false
        return isTenorOrGiphyHost(parsed.host.lowercase())
    }

    private fun hasTenorOrGiphyOrProxiedHost(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val parsed = runCatching { Url(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = parsed.protocol.name.lowercase()
        if (scheme != "https" && scheme != "http") return false
        return isWhitelistedPreviewHost(parsed)
    }

    private fun isWhitelistedPreviewHost(parsed: Url): Boolean {
        val host = parsed.host.lowercase()
        if (isTenorOrGiphyHost(host)) {
            return true
        }
        if (isDiscordProxyHost(host)) {
            val lowerPath = parsed.encodedPath.lowercase()
            return lowerPath.contains("tenor.com") || lowerPath.contains("giphy.com")
        }
        return false
    }

    private fun isTenorOrGiphyHost(host: String): Boolean =
        host == "tenor.com" ||
            host.endsWith(".tenor.com") ||
            host == "giphy.com" ||
            host.endsWith(".giphy.com")

    private fun isDiscordProxyHost(host: String): Boolean =
        host == "media.discordapp.net" ||
            host == "cdn.discordapp.com" ||
            (host.startsWith("images-ext-") && host.endsWith(".discordapp.net"))
}
