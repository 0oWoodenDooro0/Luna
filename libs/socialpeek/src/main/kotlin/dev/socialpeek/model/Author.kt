package dev.socialpeek.model

import kotlinx.serialization.Serializable

@Serializable
data class Author(
    val id: String? = null,
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val profileUrl: String? = null,
    val isVerified: Boolean = false
)
