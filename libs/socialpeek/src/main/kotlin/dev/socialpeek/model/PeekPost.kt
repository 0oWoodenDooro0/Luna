package dev.socialpeek.model

import kotlinx.serialization.Serializable

@Serializable
data class PeekPost(
    val platform: Platform,
    val id: String,
    val originalUrl: String,
    val cleanUrl: String = originalUrl,
    val author: Author,
    val content: String,
    val title: String? = null,
    val media: List<Media> = emptyList(),
    val metrics: Metrics? = null,
    val createdAtEpochSeconds: Long? = null,
    val community: String? = null,
    val communityIcon: String? = null,
    val rawData: Map<String, String> = emptyMap()
) {
    val board: String? get() = community
    val subreddit: String? get() = community
}
