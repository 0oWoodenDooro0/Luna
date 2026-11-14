package luna.core.ai

import dev.socialpeek.SocialPeek
import dev.socialpeek.model.Platform
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.resolver.bilibili.BilibiliResolver
import dev.socialpeek.resolver.facebook.FacebookResolver
import dev.socialpeek.resolver.instagram.InstagramResolver
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.resolver.threads.ThreadsResolver
import dev.socialpeek.resolver.x.XResolver
import dev.socialpeek.resolver.youtube.YouTubeResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialPeekFactoryTest {
    @Test
    fun `buildClient excludes GenericWebResolver and SocialPeek defaultClient when youtubeApiKey is null or blank`() {
        val nullKeyClient = SocialPeekFactory.buildClient(null)
        val blankKeyClient = SocialPeekFactory.buildClient("   ")

        assertNotSame(SocialPeek.defaultClient, nullKeyClient)
        assertNotSame(SocialPeek.defaultClient, blankKeyClient)

        val expectedResolvers =
            listOf(
                FacebookResolver::class,
                BilibiliResolver::class,
                XResolver::class,
                InstagramResolver::class,
                ThreadsResolver::class,
                YouTubeResolver::class,
                RedditResolver::class,
            )
        assertEquals(expectedResolvers, nullKeyClient.resolvers.map { it::class })
        assertEquals(expectedResolvers, blankKeyClient.resolvers.map { it::class })

        assertTrue(nullKeyClient.canResolveSocial("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(nullKeyClient.canResolveSocial("https://x.com/Kotlin/status/1800000000000000000"))
        assertFalse(nullKeyClient.canResolve("https://example.com/article"))
        assertFalse(blankKeyClient.canResolve("https://github.com/0oWoodenDooro0/Luna"))
    }

    @Test
    fun `buildClient wires social resolvers and configured YOUTUBE_API_KEY without GenericWebResolver`() =
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
                ),
                client.resolvers.map { it::class },
            )
            assertFalse(client.canResolve("https://example.com/article"))

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
    fun `buildPeekResolver returns null for general web URLs and malformed inputs`() =
        runTest {
            val defaultResolver = SocialPeekFactory.buildPeekResolver(null)
            val keyedResolver = SocialPeekFactory.buildPeekResolver("yt-key")

            assertNull(defaultResolver("not-a-url"))
            assertNull(keyedResolver("not-a-url"))
            assertNull(defaultResolver("https://example.com/article"))
            assertNull(keyedResolver("https://github.com/0oWoodenDooro0/Luna"))
        }
}
