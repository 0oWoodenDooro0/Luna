package dev.socialpeek.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface Media {
    val url: String
    val previewUrl: String?

    @Serializable
    data class Image(
        override val url: String,
        override val previewUrl: String? = null,
        val width: Int? = null,
        val height: Int? = null
    ) : Media

    @Serializable
    data class Video(
        override val url: String,
        override val previewUrl: String? = null,
        val durationSeconds: Double? = null,
        val width: Int? = null,
        val height: Int? = null,
        val bitrate: Long? = null
    ) : Media
}
