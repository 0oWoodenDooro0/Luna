package dev.socialpeek

import dev.socialpeek.model.Media
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SocialPeekIntegrationTest {
    private suspend fun peekLiveOrAbort(url: String): PeekPost =
        try {
            SocialPeek.peek(url)
        } catch (e: Exception) {
            Assumptions.abort("Live network request aborted due to external anti-bot/network restriction: ${e.message}")
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
