package luna.core.ai

import dev.socialpeek.SocialPeek
import dev.socialpeek.SocialPeekClient
import dev.socialpeek.model.PeekPost
import dev.socialpeek.resolver.bilibili.BilibiliResolver
import dev.socialpeek.resolver.facebook.FacebookResolver
import dev.socialpeek.resolver.generic.GenericWebResolver
import dev.socialpeek.resolver.instagram.InstagramResolver
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.resolver.threads.ThreadsResolver
import dev.socialpeek.resolver.x.XResolver
import dev.socialpeek.resolver.youtube.YouTubeResolver

/**
 * Shared factory wiring [SocialPeekClient] and URL resolver callbacks with social resolvers,
 * [GenericWebResolver], and optional `YOUTUBE_API_KEY`.
 */
object SocialPeekFactory {
    fun buildClient(youtubeApiKey: String? = null): SocialPeekClient =
        if (!youtubeApiKey.isNullOrBlank()) {
            SocialPeek
                .builder()
                .includeDefaultResolvers(false)
                .addResolver(FacebookResolver())
                .addResolver(BilibiliResolver())
                .addResolver(XResolver())
                .addResolver(InstagramResolver())
                .addResolver(ThreadsResolver())
                .addResolver(YouTubeResolver(apiKey = youtubeApiKey))
                .addResolver(RedditResolver())
                .addResolver(GenericWebResolver())
                .build()
        } else {
            SocialPeek.defaultClient
        }

    fun buildPeekResolver(youtubeApiKey: String? = null): suspend (String) -> PeekPost? {
        val client = buildClient(youtubeApiKey)
        return { url -> client.peekOrNull(url) }
    }
}
