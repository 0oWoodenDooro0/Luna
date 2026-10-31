package dev.socialpeek.model

import kotlinx.serialization.Serializable

@Serializable
data class Comment(
    val id: String? = null,
    val author: String,
    val content: String,
    val score: Long? = null,
    val createdAtEpochSeconds: Long? = null,
)
