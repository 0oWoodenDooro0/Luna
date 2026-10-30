package dev.socialpeek.resolver.youtube

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

class YouTubeResolverTest {

    private val resolver = YouTubeResolver()

    @Test
    fun `canResolve should match youtube video, shorts, live, and youtu-be URLs`() {
        assertTrue(resolver.canResolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(resolver.canResolve("https://youtu.be/dQw4w9WgXcQ?si=123"))
        assertTrue(resolver.canResolve("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(resolver.canResolve("https://www.youtube.com/live/dQw4w9WgXcQ"))
        assertTrue(resolver.canResolve("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(resolver.canResolve("https://www.youtube.com/channel/UC123"))
        assertFalse(resolver.canResolve("https://x.com/jack/status/20"))
    }

    @Test
    fun `resolve should parse youtube video successfully and populate cleanUrl`() = runTest {
        val oembedJson = """
        {
            "title": "Rick Astley - Never Gonna Give You Up (Official Music Video)",
            "author_name": "Rick Astley",
            "author_url": "https://www.youtube.com/@RickAstleyYT",
            "type": "video",
            "height": 113,
            "width": 200,
            "thumbnail_height": 360,
            "thumbnail_width": 480,
            "thumbnail_url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            assertTrue(request.url.host.contains("youtube.com"))
            jsonResponse(oembedJson)
        }

        val post = resolver.resolve("https://youtu.be/dQw4w9WgXcQ?si=tracking123&t=30", client)

        assertEquals(Platform.YOUTUBE, post.platform)
        assertEquals("dQw4w9WgXcQ", post.id)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=tracking123&t=30", post.originalUrl)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=30", post.cleanUrl)
        assertEquals("Rick Astley - Never Gonna Give You Up (Official Music Video)", post.title)
        assertEquals("Rick Astley", post.author.displayName)
        assertEquals("RickAstleyYT", post.author.username)
        assertEquals("https://www.youtube.com/@RickAstleyYT", post.author.profileUrl)
        assertEquals(1, post.media.size)
        val media = post.media.first() as Media.Video
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", media.url)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", media.previewUrl)
    }

    @Test
    fun `resolve should throw PostNotFoundException on 404 or bad oembed`() = runTest {
        val client = createMockHttpClient {
            jsonResponse("Not Found", status = HttpStatusCode.NotFound)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://www.youtube.com/watch?v=00000000000", client)
        }
    }
}
