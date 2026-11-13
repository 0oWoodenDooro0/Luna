package luna.core.ai

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import org.slf4j.LoggerFactory

/**
 * Shared extractor, validator, and chronological capacity selector for [ChatImageAttachment]s
 * across Luna plugins.
 */
object ChatImageAttachmentExtractor {
    const val DEFAULT_MAX_IMAGE_BYTES = 10 * 1024 * 1024

    val SUPPORTED_IMAGE_MIME_TYPES: Set<String> =
        setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
        )

    private val logger = LoggerFactory.getLogger(ChatImageAttachmentExtractor::class.java)

    /**
     * Validates and normalizes an attachment's MIME type against the supported image whitelist.
     * Rejects spoofed or unknown MIME types (such as `application/octet-stream` or `null`)
     * regardless of filename extension.
     */
    fun resolveImageMimeType(
        contentType: String?,
        filename: String? = null,
    ): String? {
        val normalizedContentType =
            contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()

        if (normalizedContentType != null && normalizedContentType in SUPPORTED_IMAGE_MIME_TYPES) {
            return normalizedContentType
        }

        if (!contentType.isNullOrBlank()) {
            logger.debug("Rejected unsupported attachment MIME type '{}' (filename='{}')", contentType, filename)
        }
        return null
    }

    /**
     * Extracts valid [ChatImageAttachment]s from a Discord [message], enforcing the MIME whitelist
     * and the per-image byte ceiling [maxImageBytes].
     */
    fun extractFromMessage(
        message: Message,
        maxImageBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
    ): List<ChatImageAttachment> {
        if (maxImageBytes <= 0) {
            return emptyList()
        }

        val attachments = runCatching { message.attachments }.getOrElse { emptySet() }
        if (attachments.isEmpty()) {
            return emptyList()
        }

        return attachments.mapNotNull { attachment ->
            val size = runCatching { attachment.size }.getOrNull()
            if (size != null && size > maxImageBytes) {
                logger.debug(
                    "Discarded oversized image attachment on message {} (size={} bytes > max={} bytes)",
                    runCatching { message.id }.getOrNull(),
                    size,
                    maxImageBytes,
                )
                return@mapNotNull null
            }

            val contentType = runCatching { attachment.contentType }.getOrNull()
            val filename = runCatching { attachment.filename }.getOrNull()
            val resolvedMimeType = resolveImageMimeType(contentType, filename)
            if (resolvedMimeType != null) {
                val url = runCatching { attachment.url }.getOrNull() ?: return@mapNotNull null
                ChatImageAttachment(
                    url = url,
                    mimeType = resolvedMimeType,
                    filename = filename,
                )
            } else {
                null
            }
        }
    }

    /**
     * Selects up to [maxImages] valid image attachments across chronological [messages],
     * preserving chronological message order.
     *
     * When [prioritizedMessageId] is provided, up to [maxImages] attachments from that message
     * are retained first (preserving attachment order), and any remaining capacity is filled
     * from the newest remaining messages. When [prioritizedMessageId] is null, the newest
     * attachments across [messages] are retained.
     */
    fun selectChronologicalImages(
        messages: List<Message>,
        maxImages: Int,
        maxImageBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
        prioritizedMessageId: Snowflake? = null,
    ): Map<Snowflake, List<ChatImageAttachment>> {
        if (maxImages <= 0 || maxImageBytes <= 0 || messages.isEmpty()) {
            return emptyMap()
        }

        val retainedTargetImages =
            if (prioritizedMessageId != null) {
                val targetMessage = messages.firstOrNull { it.id == prioritizedMessageId }
                targetMessage?.let { extractFromMessage(it, maxImageBytes).take(maxImages) }.orEmpty()
            } else {
                emptyList()
            }

        var capacityLeft = (maxImages - retainedTargetImages.size).coerceAtLeast(0)
        val otherMessages =
            if (prioritizedMessageId != null) {
                messages.filterNot { it.id == prioritizedMessageId }
            } else {
                messages
            }

        val retainedOtherByMessage = mutableMapOf<Snowflake, List<ChatImageAttachment>>()
        for (msg in otherMessages.asReversed()) {
            if (capacityLeft <= 0) {
                retainedOtherByMessage[msg.id] = emptyList()
                continue
            }
            val images = extractFromMessage(msg, maxImageBytes)
            if (images.isEmpty()) {
                retainedOtherByMessage[msg.id] = emptyList()
                continue
            }
            if (images.size <= capacityLeft) {
                retainedOtherByMessage[msg.id] = images
                capacityLeft -= images.size
            } else {
                retainedOtherByMessage[msg.id] = images.takeLast(capacityLeft)
                capacityLeft = 0
            }
        }

        val result = LinkedHashMap<Snowflake, List<ChatImageAttachment>>(messages.size)
        for (msg in messages) {
            result[msg.id] =
                if (prioritizedMessageId != null && msg.id == prioritizedMessageId) {
                    retainedTargetImages
                } else {
                    retainedOtherByMessage[msg.id].orEmpty()
                }
        }
        return result
    }

    /**
     * Convenience helper returning a flat chronological list of up to [maxImages] newest valid
     * [ChatImageAttachment]s extracted from [messages].
     */
    fun collectNewestImages(
        messages: List<Message>,
        maxImages: Int,
        maxImageBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
        prioritizedMessageId: Snowflake? = null,
    ): List<ChatImageAttachment> =
        selectChronologicalImages(
            messages = messages,
            maxImages = maxImages,
            maxImageBytes = maxImageBytes,
            prioritizedMessageId = prioritizedMessageId,
        ).values.flatten()
}
