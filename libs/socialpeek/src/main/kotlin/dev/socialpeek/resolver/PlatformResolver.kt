package dev.socialpeek.resolver

import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import dev.socialpeek.network.SocialPeekHttpClient

interface PlatformResolver {
    val platform: Platform

    /**
     * Returns true if this resolver can handle the provided URL.
     */
    fun canResolve(url: String): Boolean

    /**
     * Resolves the given URL into a unified structured [PeekPost].
     */
    suspend fun resolve(url: String, client: SocialPeekHttpClient): PeekPost
}
