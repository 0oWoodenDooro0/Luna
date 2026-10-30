package dev.socialpeek

import dev.socialpeek.model.PeekPost
import dev.socialpeek.network.KtorSocialPeekHttpClient
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.PlatformResolver
import dev.socialpeek.util.UrlSanitizer

object SocialPeek {

    /**
     * Default global instance with all standard resolvers.
     */
    val defaultClient: SocialPeekClient by lazy {
        SocialPeekClient()
    }

    /**
     * Parses a social media [url] using the default client.
     */
    suspend fun peek(url: String): PeekPost = defaultClient.peek(url)

    /**
     * Safely parses a social media [url] or returns null on failure.
     */
    suspend fun peekOrNull(url: String): PeekPost? = defaultClient.peekOrNull(url)

    /**
     * Checks if the default client can resolve the given [url].
     */
    fun canResolve(url: String): Boolean = defaultClient.canResolve(url)

    /**
     * Convenience method to clean a URL without making a network request.
     */
    fun cleanUrl(url: String): String = UrlSanitizer.clean(url)

    /**
     * Checks if the given URL contains known tracking/marketing parameters.
     */
    fun hasTrackingParams(url: String): Boolean = UrlSanitizer.hasTrackingParams(url)

    /**
     * Builder for custom [SocialPeekClient] instances.
     */
    class Builder {
        private var httpClient: SocialPeekHttpClient? = null
        private val customResolvers = mutableListOf<PlatformResolver>()
        private var includeDefaults: Boolean = true

        fun httpClient(client: SocialPeekHttpClient) = apply {
            this.httpClient = client
        }

        fun addResolver(resolver: PlatformResolver) = apply {
            this.customResolvers.add(resolver)
        }

        fun includeDefaultResolvers(include: Boolean) = apply {
            this.includeDefaults = include
        }

        fun build(): SocialPeekClient {
            val list = mutableListOf<PlatformResolver>()
            list.addAll(customResolvers)
            if (includeDefaults) {
                list.addAll(SocialPeekClient.defaultResolvers())
            }
            return SocialPeekClient(
                resolvers = list,
                httpClient = httpClient ?: KtorSocialPeekHttpClient()
            )
        }
    }

    fun builder(): Builder = Builder()
}
