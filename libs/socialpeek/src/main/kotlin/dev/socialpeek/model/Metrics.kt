package dev.socialpeek.model

import kotlinx.serialization.Serializable

@Serializable
data class Metrics(
    val likes: Long? = null,
    val reposts: Long? = null,
    val comments: Long? = null,
    val views: Long? = null,
    val bookmarks: Long? = null
)
