package dev.socialpeek.resolver.x

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.jsonResponse
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XResolverTest {

    private val resolver = XResolver()

    @Test
    fun `canResolve should match twitter and x URLs`() {
        assertTrue(resolver.canResolve("https://twitter.com/jack/status/20"))
        assertTrue(resolver.canResolve("https://x.com/elonmusk/status/1715438407489569064"))
        assertTrue(resolver.canResolve("https://x.com/i/status/1715438407489569064"))
        assertTrue(resolver.canResolve("https://twitter.com/i/web/status/1715438407489569064"))
        assertTrue(resolver.canResolve("http://mobile.twitter.com/user/status/123456?s=20"))
        assertFalse(resolver.canResolve("https://youtube.com/watch?v=123"))
        assertFalse(resolver.canResolve("https://x.com/home"))
    }

    @Test
    fun `resolve should parse single image tweet correctly and populate cleanUrl`() = runTest {
        val mockJson = """
        {
            "id_str": "1715438407489569064",
            "text": "Just setting up my SocialPeek",
            "user": {
                "id_str": "44196397",
                "name": "Elon Musk",
                "screen_name": "elonmusk",
                "profile_image_url_https": "https://pbs.twimg.com/profile_images/normal.jpg",
                "is_blue_verified": true
            },
            "photos": [
                {
                    "url": "https://pbs.twimg.com/media/F86EXAMPLE.jpg",
                    "width": 1200,
                    "height": 800
                }
            ],
            "favorite_count": 42000,
            "conversation_count": 1337
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            assertEquals("cdn.syndication.twimg.com", request.url.host)
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://x.com/elonmusk/status/1715438407489569064?s=20&t=abcdef", client)

        assertEquals(Platform.X, post.platform)
        assertEquals("1715438407489569064", post.id)
        assertEquals("https://x.com/elonmusk/status/1715438407489569064?s=20&t=abcdef", post.originalUrl)
        assertEquals("https://x.com/elonmusk/status/1715438407489569064", post.cleanUrl)
        assertEquals("Elon Musk", post.author.displayName)
        assertEquals("elonmusk", post.author.username)
        assertTrue(post.author.isVerified)
        assertEquals("Just setting up my SocialPeek", post.content)
        assertEquals(42000, post.metrics?.likes)
        assertEquals(1337, post.metrics?.comments)
        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://pbs.twimg.com/media/F86EXAMPLE.jpg", image.url)
        assertEquals(1200, image.width)
        assertEquals(800, image.height)
    }

    @Test
    fun `resolve should parse 4 photos from multi-photo tweet`() = runTest {
        val mockJson = """
        {
            "id_str": "1715438407489569064",
            "text": "4 photo tweet",
            "user": {
                "name": "Photographer",
                "screen_name": "photo_guy"
            },
            "photos": [
                { "url": "https://pbs.twimg.com/media/pic1.jpg", "width": 1080, "height": 1080 },
                { "url": "https://pbs.twimg.com/media/pic2.jpg", "width": 1080, "height": 1080 },
                { "url": "https://pbs.twimg.com/media/pic3.jpg", "width": 1080, "height": 1080 },
                { "url": "https://pbs.twimg.com/media/pic4.jpg", "width": 1080, "height": 1080 }
            ]
        }
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://x.com/photo_guy/status/1715438407489569064", client)

        assertEquals(4, post.media.size)
        assertEquals("https://pbs.twimg.com/media/pic1.jpg", (post.media[0] as Media.Image).url)
        assertEquals("https://pbs.twimg.com/media/pic2.jpg", (post.media[1] as Media.Image).url)
        assertEquals("https://pbs.twimg.com/media/pic3.jpg", (post.media[2] as Media.Image).url)
        assertEquals("https://pbs.twimg.com/media/pic4.jpg", (post.media[3] as Media.Image).url)
    }

    @Test
    fun `resolve should parse video tweet and select highest bitrate mp4`() = runTest {
        val mockJson = """
        {
            "id_str": "1234567890",
            "text": "Check out this video clip",
            "user": {
                "id_str": "123",
                "name": "Creator",
                "screen_name": "creator",
                "profile_image_url_https": "https://pbs.twimg.com/avatar.jpg"
            },
            "video": {
                "poster": "https://pbs.twimg.com/video_thumb.jpg",
                "variants": [
                    { "type": "application/x-mpegURL", "src": "https://video.twimg.com/playlist.m3u8" },
                    { "type": "video/mp4", "src": "https://video.twimg.com/low.mp4", "bitrate": 256000 },
                    { "type": "video/mp4", "src": "https://video.twimg.com/high.mp4", "bitrate": 2176000 }
                ]
            }
        }
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://twitter.com/creator/status/1234567890", client)

        assertEquals(1, post.media.size)
        val video = post.media.first() as Media.Video
        assertEquals("https://video.twimg.com/high.mp4", video.url)
        assertEquals("https://pbs.twimg.com/video_thumb.jpg", video.previewUrl)
        assertEquals(2176000L, video.bitrate)
    }

    @Test
    fun `resolve should throw PostNotFoundException on 404 response`() = runTest {
        val client = createMockHttpClient {
            jsonResponse("{}", status = HttpStatusCode.NotFound)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://x.com/ghost/status/404404404", client)
        }
    }
}
