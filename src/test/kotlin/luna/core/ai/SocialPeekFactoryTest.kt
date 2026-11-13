package luna.core.ai

import dev.socialpeek.SocialPeek
import dev.socialpeek.model.Platform
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.bilibili.BilibiliResolver
import dev.socialpeek.resolver.facebook.FacebookResolver
import dev.socialpeek.resolver.generic.GenericWebResolver
import dev.socialpeek.resolver.instagram.InstagramResolver
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.resolver.threads.ThreadsResolver
import dev.socialpeek.resolver.x.XResolver
import dev.socialpeek.resolver.youtube.YouTubeResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SocialPeekFactoryTest {
    @Test
    fun `buildClient returns default SocialPeek client when youtubeApiKey is null or blank`() {
        val nullKeyClient = SocialPeekFactory.buildClient(null)
        val blankKeyClient = SocialPeekFactory.buildClient("   ")

        assertSame(SocialPeek.defaultClient, nullKeyClient)
        assertSame(SocialPeek.defaultClient, blankKeyClient)
        assertTrue(nullKeyClient.canResolveSocial("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(nullKeyClient.canResolve("https://example.com/article"))
    }

    @Test
    fun `buildClient wires all social resolvers, GenericWebResolver, and configured YOUTUBE_API_KEY`() =
        runTest {
            val client = SocialPeekFactory.buildClient("yt-secret-key")
            assertNotSame(SocialPeek.defaultClient, client)

            assertEquals(
                listOf(
                    FacebookResolver::class,
                    BilibiliResolver::class,
                    XResolver::class,
                    InstagramResolver::class,
                    ThreadsResolver::class,
                    YouTubeResolver::class,
                    RedditResolver::class,
                    GenericWebResolver::class,
                ),
                client.resolvers.map { it::class },
            )

            val youtubeResolver = client.resolvers.filterIsInstance<YouTubeResolver>().single()
            val requestedUrls = mutableListOf<String>()
            val mockHttpClient =
                object : SocialPeekHttpClient {
                    override suspend fun get(
                        url: String,
                        headers: Map<String, String>,
                    ): String {
                        requestedUrls.add(url)
                        return """
                            {
                              "items": [
                                {
                                  "snippet": {
                                    "title": "Test Video",
                                    "description": "Video description",
                                    "channelTitle": "Test Channel"
                                  },
                                  "contentDetails": {
                                    "duration": "PT2M30S"
                                  }
                                }
                              ]
                            }
                            """.trimIndent()
                    }

                    override suspend fun resolveFinalUrl(
                        url: String,
                        headers: Map<String, String>,
                    ): String = url
                }

            val post = youtubeResolver.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", mockHttpClient)
            assertEquals(Platform.YOUTUBE, post.platform)
            assertEquals("Test Video", post.title)
            assertTrue(requestedUrls.any { it.contains("googleapis.com/youtube/v3/videos") && it.contains("key=yt-secret-key") })
        }

    @Test
    fun `buildPeekResolver returns fail-open resolver lambda for both null and configured youtubeApiKey`() =
        runTest {
            val defaultResolver = SocialPeekFactory.buildPeekResolver(null)
            val keyedResolver = SocialPeekFactory.buildPeekResolver("yt-key")

            assertNull(defaultResolver("not-a-url"))
            assertNull(keyedResolver("not-a-url"))
        }
}
