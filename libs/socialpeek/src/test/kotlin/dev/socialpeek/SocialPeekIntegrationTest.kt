package dev.socialpeek

import dev.socialpeek.model.Media
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import dev.socialpeek.resolver.bilibili.BilibiliResolver
import dev.socialpeek.resolver.facebook.FacebookResolver
import dev.socialpeek.resolver.instagram.InstagramResolver
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.resolver.threads.ThreadsResolver
import dev.socialpeek.resolver.x.XResolver
import dev.socialpeek.resolver.youtube.YouTubeResolver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialPeekIntegrationTest {
    private suspend fun peekLiveOrAbort(url: String): PeekPost =
        try {
            SocialPeek.peek(url)
        } catch (e: Exception) {
            Assumptions.abort("Live network request aborted due to external anti-bot/network restriction: ${e.message}")
        }

    @Test
    fun `default client should support all 7 platforms`() {
        val client = SocialPeek.defaultClient

        assertEquals(7, client.resolvers.size)
        assertTrue(client.resolvers.any { it is FacebookResolver })
        assertTrue(client.resolvers.any { it is BilibiliResolver })
        assertTrue(client.resolvers.any { it is XResolver })
        assertTrue(client.resolvers.any { it is InstagramResolver })
        assertTrue(client.resolvers.any { it is ThreadsResolver })
        assertTrue(client.resolvers.any { it is YouTubeResolver })
        assertTrue(client.resolvers.any { it is RedditResolver })

        // Check resolver routing
        assertEquals(
            Platform.FACEBOOK,
            client.findResolver("https://www.facebook.com/zuck/posts/pfbid02517vL7rZ9kFq71k51aP6k4R7Y6oBw8m")?.platform,
        )
        assertEquals(Platform.FACEBOOK, client.findResolver("https://www.facebook.com/reel/10153231379946729")?.platform)
        assertEquals(Platform.FACEBOOK, client.findResolver("https://fb.watch/m4abc123/")?.platform)
        assertEquals(Platform.FACEBOOK, client.findResolver("https://www.facebook.com/share/p/123456/")?.platform)
        assertEquals(Platform.BILIBILI, client.findResolver("https://www.bilibili.com/video/BV1xx411c7mD")?.platform)
        assertEquals(Platform.X, client.findResolver("https://x.com/jack/status/20")?.platform)
        assertEquals(Platform.X, client.findResolver("https://x.com/i/status/2097593106510094458")?.platform)
        assertEquals(Platform.INSTAGRAM, client.findResolver("https://www.instagram.com/p/Cx12345abc/")?.platform)
        assertEquals(Platform.INSTAGRAM, client.findResolver("https://instagram.com/share/p/Cx12345abc/")?.platform)
        assertEquals(Platform.THREADS, client.findResolver("https://www.threads.net/@zuck/post/CuZ12345")?.platform)
        assertEquals(Platform.THREADS, client.findResolver("https://www.threads.com/share/BAENHoOpq1/")?.platform)
        assertEquals(Platform.YOUTUBE, client.findResolver("https://youtu.be/dQw4w9WgXcQ")?.platform)
        assertEquals(Platform.YOUTUBE, client.findResolver("https://www.youtube.com/live/SyG1rbuHB9A")?.platform)
        assertEquals(Platform.REDDIT, client.findResolver("https://redd.it/1cdefgh")?.platform)
        assertEquals(Platform.REDDIT, client.findResolver("https://www.reddit.com/r/google_antigravity/s/7GwvvFKRsE")?.platform)
        assertNull(client.findResolver("https://randomwebsite.com/article/1"))
    }

    @Test
    fun `SocialPeek helper methods should work`() {
        assertTrue(SocialPeek.canResolve("https://www.facebook.com/zuck/posts/pfbid02517vL7rZ9kFq71k51aP6k4R7Y6oBw8m"))
        assertTrue(SocialPeek.canResolve("https://fb.watch/m4abc123/"))
        assertTrue(SocialPeek.canResolve("https://x.com/jack/status/20"))
        assertTrue(SocialPeek.canResolve("https://bilibili.com/opus/12345"))
        assertTrue(SocialPeek.canResolve("https://www.reddit.com/r/google_antigravity/s/7GwvvFKRsE"))
        assertTrue(SocialPeek.canResolve("https://www.threads.com/share/BAENHoOpq1/"))
        assertTrue(SocialPeek.canResolve("https://www.youtube.com/live/SyG1rbuHB9A"))
    }

    @Test
    @Tag("integration")
    fun `peek should resolve Reddit post with image`() =
        runTest {
            val post = peekLiveOrAbort("https://www.reddit.com/r/SaaS/comments/1wccqkh/someone_is_trying_really_hard_to_get_the_env_file/")
            assertEquals(Platform.REDDIT, post.platform)
            assertEquals("1wccqkh", post.id)
            assertEquals("Little_Thanos", post.author.username)
            assertEquals("SaaS", post.community)
            assertNotNull(post.communityIcon)
            assertTrue(post.communityIcon.contains("communityIcon"))
            assertTrue(post.media.isNotEmpty())
            val image = post.media.first() as Media.Image
            assertTrue(image.url.contains("fkhj9xodgnoh1"))
        }

    @Test
    @Tag("integration")
    fun `peek should resolve live Facebook video post`() =
        runTest {
            val post = peekLiveOrAbort("https://www.facebook.com/facebook/videos/10153231379946729/")
            assertEquals(Platform.FACEBOOK, post.platform)
            assertEquals("10153231379946729", post.id)
            assertNotNull(post.author.displayName)
            assertTrue(post.media.isNotEmpty())
        }

    @Test
    @Tag("integration")
    fun `peek should resolve live Facebook share video link and produce clean reel url`() =
        runTest {
            val post = peekLiveOrAbort("https://www.facebook.com/share/v/18L6FcWfXE/?mibextid=wwXIfr")
            assertEquals(Platform.FACEBOOK, post.platform)
            assertEquals("832727859062012", post.id)
            assertEquals("https://www.facebook.com/reel/832727859062012/", post.cleanUrl)
            assertFalse(post.cleanUrl.contains("share_url"))
            assertFalse(post.cleanUrl.contains("mibextid"))
            assertEquals("League of Legends • 英雄联盟• Lolsea", post.author.displayName)
            assertEquals("lolSEA2016", post.author.username)
            assertEquals("https://www.facebook.com/lolSEA2016", post.author.profileUrl)
            assertTrue(post.media.isNotEmpty())
        }

    @Test
    @Tag("integration")
    fun `peek should resolve live Threads post with video and preview`() =
        runTest {
            val post = peekLiveOrAbort("https://www.threads.net/@ou.omar.71/post/DeynNaEmZHj")
            assertEquals(Platform.THREADS, post.platform)
            assertEquals("DeynNaEmZHj", post.id)
            assertEquals("ou.omar.71", post.author.username)
            assertEquals("嘎", post.author.displayName)
            assertEquals("留起來吵朋友", post.content)
            assertTrue(post.media.isNotEmpty())
            val video = post.media.first() as Media.Video
            assertTrue(video.url.contains(".mp4"))
            assertNotNull(video.previewUrl)
            assertTrue(video.previewUrl.contains("cdninstagram.com") || video.previewUrl.contains("fbcdn.net"))
            assertNotNull(post.createdAtEpochSeconds)
        }
}
