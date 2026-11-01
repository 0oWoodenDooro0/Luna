package luna.summarizer

import dev.kord.core.entity.Message

/**
 * Encapsulates extraction, format validation, and byte-budget filtering of Discord image attachments.
 */
open class ChatImageResolver(
    private val maxImageBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
) {
    open fun extractImageAttachments(message: Message): List<ChatImageAttachment> {
        val attachments = runCatching { message.attachments }.getOrElse { emptySet() }
        if (attachments.isEmpty()) return emptyList()

        return attachments.mapNotNull { attachment ->
            val size = runCatching { attachment.size }.getOrNull()
            if (size != null && size > maxImageBytes) {
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

    companion object {
        const val DEFAULT_MAX_IMAGE_BYTES = 10 * 1024 * 1024

        val SUPPORTED_IMAGE_MIME_TYPES =
            setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
            )

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

            return null
        }
    }
}
