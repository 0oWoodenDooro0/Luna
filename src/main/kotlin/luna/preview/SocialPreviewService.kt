package luna.preview

import dev.kord.common.Color
import dev.kord.common.entity.AllowedMentionType
import dev.kord.common.entity.MessageFlag
import dev.kord.common.entity.MessageFlags
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.WebhookBehavior
import dev.kord.core.behavior.channel.CategorizableChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.channel.createWebhook
import dev.kord.core.behavior.channel.threads.ThreadChannelBehavior
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.execute
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.TopGuildChannel
import dev.kord.core.entity.channel.thread.ThreadChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.allowedMentions
import dev.kord.rest.builder.message.create.WebhookMessageCreateBuilder
import dev.kord.rest.builder.message.embed
import dev.socialpeek.SocialPeek
import dev.socialpeek.model.Media
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import kotlinx.coroutines.flow.firstOrNull
import luna.core.JsonLogger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object SocialPreviewService {
    private val logger = LoggerFactory.getLogger("SocialPreviewService")

    // Regex to match URLs in messages
    private val URL_REGEX = Regex("""https?://[^\s<>"']+""")

    // Regex to extract subreddit from URL if not in rawData
    private val SUBREDDIT_REGEX = Regex("""/r/([a-zA-Z0-9_]+)""", RegexOption.IGNORE_CASE)

    // Unified limits for post description across all platforms
    private const val MAX_LINES = 5
    private const val MAX_CHARS = 250

    // Webhook cache: channelId -> (webhookId, token)
    private val webhookCache = ConcurrentHashMap<Snowflake, Pair<Snowflake, String>>()

    fun register(
        kord: Kord,
        settingsStorage: PreviewSettingsStorage,
    ) {
        logger.info("SocialPreviewService registered successfully!")

        // Listen to messages for auto preview
        kord.on<MessageCreateEvent> {
            val author = message.author ?: return@on
            if (author.isBot) return@on

            val guildId =
                message.data.guildId.value
                    ?.toString()
            if (guildId != null && !settingsStorage.isGuildEnabled(guildId)) {
                return@on
            }

            val content = message.content
            logger.debug("Received message: author={}, content={}", author.username, content)

            if (content.isBlank()) {
                logger.warn(
                    "Received message with empty content from {}. If you sent text, please ensure " +
                        "'Message Content Intent' is enabled in Discord Developer Portal!",
                    author.username,
                )
                return@on
            }

            val urls =
                URL_REGEX
                    .findAll(content)
                    .map { it.value }
                    .distinct()
                    .toList()
            if (urls.isEmpty()) return@on

            logger.info("Detected URLs in message {}: {}", message.id, urls)

            for (url in urls) {
                val canResolve = SocialPeek.canResolve(url)
                logger.info("Checking URL [{}]: canResolve={}", url, canResolve)

                if (canResolve) {
                    processUrl(url, message, guildId, settingsStorage)
                    // Currently handle the first supported link per message to avoid spamming
                    break
                }
            }
        }
    }

    private suspend fun processUrl(
        url: String,
        originalMessage: Message,
        guildId: String?,
        settingsStorage: PreviewSettingsStorage,
    ) {
        try {
            logger.info("Resolving URL via SocialPeek: {}", url)
            val post = SocialPeek.peekOrNull(url)
            if (post == null) {
                logger.warn("SocialPeek returned null for URL: {}", url)
                return
            }

            // Check if this specific platform is disabled in the guild
            if (guildId != null && !settingsStorage.isPlatformEnabled(guildId, post.platform)) {
                logger.info("Preview for platform {} is disabled in guild {}", post.platform, guildId)
                return
            }

            logger.info(
                "Successfully resolved post: platform={}, title={}, author={}",
                post.platform,
                post.title,
                post.author.displayName ?: post.author.username,
            )

            // Extract and normalize media image URLs
            val mediaImageUrls = getNormalizedImageUrls(post)
            val targetUrl = post.cleanUrl.ifBlank { post.originalUrl }
            val hasCleanUrlNotice =
                (guildId == null || settingsStorage.isCleanUrlEnabled(guildId)) &&
                    post.cleanUrl.isNotBlank() &&
                    post.cleanUrl != post.originalUrl

            val isWebhookEnabled = guildId != null && settingsStorage.isWebhookReplaceEnabled(guildId)
            var webhookSucceeded = false

            // Attempt seamless Webhook replacement if enabled and no attachments
            if (isWebhookEnabled && originalMessage.attachments.isEmpty()) {
                var cleanContent = originalMessage.content.replace(url, targetUrl)
                if (post.originalUrl.isNotBlank() && post.originalUrl != targetUrl) {
                    cleanContent = cleanContent.replace(post.originalUrl, targetUrl)
                }

                webhookSucceeded =
                    runCatching {
                        executeWebhookReplacement(
                            kord = originalMessage.kord,
                            message = originalMessage,
                            post = post,
                            targetUrl = targetUrl,
                            content = cleanContent,
                            mediaImageUrls = mediaImageUrls,
                        )
                    }.onFailure { e ->
                        logger.warn("Webhook replacement failed, falling back to standard embed: {}", e.message)
                    }.getOrDefault(false)
            }

            // Fallback: If webhook replacement was not performed or failed
            if (!webhookSucceeded) {
                // Suppress original message's embeds so Discord's native preview doesn't conflict
                val suppressResult =
                    runCatching {
                        originalMessage.edit {
                            flags = (originalMessage.flags ?: MessageFlags()) + MessageFlag.SuppressEmbeds
                        }
                    }
                if (suppressResult.isFailure) {
                    logger.warn(
                        "Failed to suppress embeds on message {}: {}",
                        originalMessage.id,
                        suppressResult.exceptionOrNull()?.message,
                    )
                }

                // Send clean standalone embed message in the same channel
                originalMessage.channel.createMessage {
                    if (hasCleanUrlNotice) {
                        content = "🧹 **乾淨連結**：<${post.cleanUrl}>"
                    }
                    populatePreviewEmbeds(post, targetUrl, mediaImageUrls)
                }
            }

            JsonLogger.log(
                layer = "PREVIEW",
                component = "SocialPreviewService",
                operation = "processUrl",
                data =
                    mapOf(
                        "platform" to post.platform.name,
                        "url" to url,
                        "cleanUrl" to post.cleanUrl,
                        "replacedByWebhook" to webhookSucceeded,
                        "messageId" to originalMessage.id.toString(),
                        "imageCount" to mediaImageUrls.size,
                    ),
            )
        } catch (e: Exception) {
            logger.error("Error processing preview for URL $url", e)
            JsonLogger.error(
                layer = "PREVIEW",
                component = "SocialPreviewService",
                operation = "processUrl",
                data = mapOf("url" to url),
                errorMessage = e.message,
            )
        }
    }

    /**
     * Executes webhook message replacement: sends the message masquerading as the author
     * with tracking parameters removed and rich embeds attached, then deletes the original message.
     */
    private suspend fun executeWebhookReplacement(
        kord: Kord,
        message: Message,
        post: PeekPost,
        targetUrl: String,
        content: String,
        mediaImageUrls: List<String>,
    ): Boolean {
        val channel = message.channel.asChannelOrNull() ?: return false
        val (webhookChannel, threadId) =
            when (channel) {
                is ThreadChannel -> channel.parent to channel.id
                is CategorizableChannelBehavior -> channel to null
                else -> return false
            }

        val rawGuildId = message.data.guildId.value
        val topGuildChannel =
            when (channel) {
                is ThreadChannel -> channel.parent.asChannelOrNull() as? TopGuildChannel
                is TopGuildChannel -> channel
                else -> null
            }

        if (topGuildChannel != null) {
            val perms = topGuildChannel.getEffectivePermissions(kord.selfId)
            val canManageMessages =
                perms.contains(Permission.ManageMessages) || perms.contains(Permission.Administrator)
            val canManageWebhooks =
                perms.contains(Permission.ManageWebhooks) || perms.contains(Permission.Administrator)
            if (!canManageMessages || !canManageWebhooks) {
                logger.debug(
                    "Missing ManageMessages or ManageWebhooks permissions in channel {}, skipping webhook replacement",
                    channel.id,
                )
                return false
            }
        }

        val webhookPair = getOrCreateWebhook(webhookChannel, kord) ?: return false

        val member = if (rawGuildId != null) message.getAuthorAsMemberOrNull() else null
        val author = message.author

        val displayName = member?.effectiveName ?: author?.globalName ?: author?.username ?: "User"
        val avatarUrl =
            member?.memberAvatar?.cdnUrl?.toUrl()
                ?: author?.avatar?.cdnUrl?.toUrl()
                ?: author?.defaultAvatar?.cdnUrl?.toUrl()

        // If user replied to another message, include a quote jump link
        val ref = message.messageReference
        val replyPrefix =
            if (ref != null && rawGuildId != null) {
                val refMsgId = ref.data.id.value
                if (refMsgId != null) {
                    "> 💬 回覆: https://discord.com/channels/$rawGuildId/${message.channelId}/$refMsgId\n"
                } else {
                    ""
                }
            } else {
                ""
            }

        val finalContent = (replyPrefix + content).trim()

        val webhookExecuteBlock: WebhookMessageCreateBuilder.() -> Unit = {
            this.username = displayName
            this.avatarUrl = avatarUrl
            this.content = finalContent
            allowedMentions {
                add(AllowedMentionType.UserMentions)
                add(AllowedMentionType.RoleMentions)
            }
            populatePreviewEmbeds(post, targetUrl, mediaImageUrls)
        }

        // Try executing, retry once if cached webhook was deleted/invalidated
        var currentPair = webhookPair
        val executeResult =
            runCatching {
                currentPair.first.execute(currentPair.second, threadId, webhookExecuteBlock)
            }

        if (executeResult.isFailure) {
            webhookCache.remove(webhookChannel.id)
            val retryPair = getOrCreateWebhook(webhookChannel, kord) ?: return false
            currentPair = retryPair
            currentPair.first.execute(currentPair.second, threadId, webhookExecuteBlock)
        }

        // Delete the original message now that the replacement has been posted
        runCatching {
            message.delete("Replaced by Luna Preview Webhook with clean URL")
        }.onFailure { e ->
            logger.warn("Failed to delete original message {}: {}", message.id, e.message)
        }

        return true
    }

    private suspend fun getOrCreateWebhook(
        channel: CategorizableChannelBehavior,
        kord: Kord,
    ): Pair<WebhookBehavior, String>? {
        val cached = webhookCache[channel.id]
        if (cached != null) {
            val (id, token) = cached
            val webhook = runCatching { kord.getWebhookWithTokenOrNull(id, token) }.getOrNull()
            if (webhook != null) {
                return Pair(webhook, token)
            }
            webhookCache.remove(channel.id)
        }

        // Find existing webhook created by Luna or named "Luna Preview"
        val existing =
            runCatching {
                channel.webhooks.firstOrNull { it.creatorId == kord.selfId && it.token != null }
                    ?: channel.webhooks.firstOrNull { it.name == "Luna Preview" && it.token != null }
            }.getOrNull()

        if (existing != null && existing.token != null) {
            webhookCache[channel.id] = existing.id to existing.token!!
            return Pair(existing, existing.token!!)
        }

        // Create new webhook
        val created =
            runCatching {
                channel.createWebhook(name = "Luna Preview")
            }.getOrNull()

        if (created != null && created.token != null) {
            webhookCache[channel.id] = created.id to created.token!!
            return Pair(created, created.token!!)
        }

        return null
    }

    /**
     * Shared preview embed builder used for both Webhook execution and normal fallback message execution.
     */
    private fun MessageBuilder.populatePreviewEmbeds(
        post: PeekPost,
        targetUrl: String,
        mediaImageUrls: List<String>,
    ) {
        embed {
            val platformName = post.platform.displayName
            val platformColor = getPlatformColor(post.platform)

            color = platformColor

            if (post.platform == Platform.REDDIT) {
                // Reddit: author field shows Subreddit link, author is placed at the bottom
                val subreddit =
                    post.community
                        ?: post.rawData["subreddit"]
                        ?: SUBREDDIT_REGEX.find(targetUrl)?.groupValues?.getOrNull(1)
                        ?: SUBREDDIT_REGEX.find(post.originalUrl)?.groupValues?.getOrNull(1)

                val redditIcon =
                    post.communityIcon?.takeIf { it.isNotBlank() }
                        ?: post.rawData["community_icon"]?.takeIf { it.isNotBlank() }
                        ?: "https://www.redditstatic.com/shreddit/assets/favicon/192x192.png"

                if (!subreddit.isNullOrBlank()) {
                    this.author {
                        name = "r/$subreddit"
                        this.url = "https://www.reddit.com/r/$subreddit"
                        icon = redditIcon
                    }
                } else {
                    this.author {
                        name = "Reddit"
                        this.url = targetUrl
                        icon = redditIcon
                    }
                }
            } else {
                val authorName = post.author.displayName?.takeIf { it.isNotBlank() } ?: post.author.username
                this.author {
                    name =
                        if (authorName != post.author.username) {
                            "$authorName (@${post.author.username})"
                        } else {
                            authorName
                        }
                    icon = post.author.avatarUrl
                    this.url = post.author.profileUrl
                }
            }

            // Always ensure a clickable title leading to the post URL across all platforms
            val postTitle = post.title?.takeIf { it.isNotBlank() }
            val resolvedTitle =
                if (postTitle != null) {
                    postTitle
                } else {
                    val authorName = post.author.displayName?.takeIf { it.isNotBlank() } ?: post.author.username
                    if (authorName.isNotBlank() && authorName != "Unknown") {
                        "$authorName on ${post.platform.displayName}"
                    } else {
                        "${post.platform.displayName} Post"
                    }
                }

            title = if (resolvedTitle.length > 250) resolvedTitle.take(247) + "..." else resolvedTitle
            this.url = targetUrl

            if (post.content.isNotBlank()) {
                description = truncateContent(post.content)
            }

            // First image for the main embed
            if (mediaImageUrls.isNotEmpty()) {
                image = mediaImageUrls.first()
            }

            // Metrics and image count in footer (without platform emoji/icon)
            val metricsText = formatMetrics(post)
            val imageCountText =
                when {
                    mediaImageUrls.size > 10 -> "🖼️ 10/${mediaImageUrls.size} 張圖片"
                    mediaImageUrls.size > 1 -> "🖼️ ${mediaImageUrls.size} 張圖片"
                    else -> ""
                }
            val footerDetails =
                listOf(imageCountText, metricsText).filter { it.isNotBlank() }.joinToString(" • ")

            footer {
                if (post.platform == Platform.REDDIT) {
                    val authorName = post.author.username.removePrefix("u/")
                    val authorInfo = "u/$authorName"
                    text =
                        when {
                            footerDetails.isNotEmpty() -> "$platformName • Posted by $authorInfo • $footerDetails"
                            else -> "$platformName • Posted by $authorInfo"
                        }
                } else {
                    text =
                        if (footerDetails.isNotEmpty()) {
                            "$platformName • $footerDetails"
                        } else {
                            platformName
                        }
                }
            }

            post.createdAtEpochSeconds?.let { epoch ->
                timestamp = kotlin.time.Instant.fromEpochSeconds(epoch)
            }
        }

        // If post has multiple images, add secondary embeds with the same url to form a Discord Image Gallery (up to Discord's maximum 10 embeds)
        if (mediaImageUrls.size > 1) {
            val maxEmbeds = minOf(mediaImageUrls.size, 10)
            for (i in 1 until maxEmbeds) {
                embed {
                    this.url = targetUrl
                    image = mediaImageUrls[i]
                }
            }
        }
    }

    /**
     * Unified truncation: limits by max lines and max characters.
     */
    private fun truncateContent(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return ""

        val lines = trimmed.lines()
        val lineTruncated =
            if (lines.size > MAX_LINES) {
                lines.take(MAX_LINES).joinToString("\n") + "..."
            } else {
                trimmed
            }

        return if (lineTruncated.length > MAX_CHARS) {
            lineTruncated.take(MAX_CHARS).trimEnd() + "..."
        } else {
            lineTruncated
        }
    }

    /**
     * Extracts full-resolution image URLs and strips cropping parameters where applicable.
     * Preserves original media order and handles both images and video preview thumbnails.
     */
    private fun getNormalizedImageUrls(post: PeekPost): List<String> {
        val urls =
            post.media
                .mapNotNull { media ->
                    when (media) {
                        is Media.Image -> media.url
                        is Media.Video -> media.previewUrl ?: media.url
                    }
                }.filter { it.isNotBlank() }
                .distinct()

        return urls.map { normalizeImageUrl(it) }
    }

    /**
     * Normalizes image URLs to prevent unwanted thumbnail cropping:
     * - Bilibili: removes `@...webp` suffix which crops/resizes images
     * - Twitter: replaces `:small` or `name=small` with `name=large`
     */
    private fun normalizeImageUrl(url: String): String {
        var cleanUrl = url
        if (cleanUrl.contains("hdslb.com") && cleanUrl.contains("@")) {
            cleanUrl = cleanUrl.substringBeforeLast("@")
        }
        if (cleanUrl.contains("pbs.twimg.com")) {
            cleanUrl =
                cleanUrl
                    .replace("name=small", "name=large")
                    .replace("name=medium", "name=large")
                    .replace(":small", ":large")
                    .replace(":medium", ":large")
        }
        return cleanUrl
    }

    private fun formatMetrics(post: PeekPost): String {
        val metrics = post.metrics ?: return ""
        val parts = mutableListOf<String>()

        metrics.likes?.let { parts.add("❤️ ${formatCount(it)}") }
        metrics.comments?.let { parts.add("💬 ${formatCount(it)}") }
        metrics.reposts?.let { parts.add("🔁 ${formatCount(it)}") }
        metrics.bookmarks?.let { parts.add("⭐ ${formatCount(it)}") }

        return parts.joinToString(" ")
    }

    private fun formatCount(count: Long): String =
        when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }

    private fun getPlatformColor(platform: Platform): Color =
        when (platform) {
            Platform.X -> Color(0x1DA1F2)
            Platform.THREADS -> Color(0x000000)
            Platform.INSTAGRAM -> Color(0xE1306C)
            Platform.REDDIT -> Color(0xFF4500)
            Platform.BILIBILI -> Color(0x00AEEC)
            Platform.YOUTUBE -> Color(0xFF0000)
            Platform.GENERIC -> Color(0x5865F2)
        }
}
