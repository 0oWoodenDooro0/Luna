package dev.socialpeek.resolver.instagram

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstagramResolverTest {

    private val resolver = InstagramResolver()

    @Test
    fun `canResolve should match instagram post, reel, and tv URLs`() {
        assertTrue(resolver.canResolve("https://www.instagram.com/p/Cx12345abc/"))
        assertTrue(resolver.canResolve("https://instagram.com/reel/Cx12345abc"))
        assertTrue(resolver.canResolve("https://instagram.com/share/p/Cx12345abc/"))
        assertTrue(resolver.canResolve("https://instagram.com/share/reel/Cx12345abc/"))
        assertTrue(resolver.canResolve("https://instagr.am/p/Cx12345abc/"))
        assertTrue(resolver.canResolve("https://www.instagram.com/tv/Cx12345abc/"))
        assertFalse(resolver.canResolve("https://www.instagram.com/stories/user/123/"))
        assertFalse(resolver.canResolve("https://twitter.com/jack/status/20"))
    }

    @Test
    fun `resolve should parse instagram post via Bot OpenGraph SSR`() = runTest {
        val botHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="John Doe on Instagram: &quot;Beautiful sunset in Tokyo!&quot;" />
            <meta property="og:description" content="1,200 likes, 45 comments - johndoe on March 15, 2026: &quot;Beautiful sunset in Tokyo!&quot;" />
            <meta property="og:image" content="https://instagram.fxxx.fbcdn.net/sunset.jpg" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath == "/p/Cx12345abc/") {
                htmlResponse(botHtml)
            } else {
                htmlResponse("Not found", HttpStatusCode.NotFound)
            }
        }

        val post = resolver.resolve("https://www.instagram.com/p/Cx12345abc/", client)

        assertEquals(Platform.INSTAGRAM, post.platform)
        assertEquals("Cx12345abc", post.id)
        assertEquals("johndoe", post.author.username)
        assertEquals("John Doe", post.author.displayName)
        assertEquals("Beautiful sunset in Tokyo!", post.content)
        assertEquals(1200L, post.metrics?.likes)
        assertEquals(45L, post.metrics?.comments)
        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://instagram.fxxx.fbcdn.net/sunset.jpg", image.url)
    }

    @Test
    fun `resolve should parse multi-image carousel post from Instagram`() = runTest {
        val scriptJson = """
        {
            "entry_data": {
                "PostPage": [{
                    "graphql": {
                        "shortcode_media": {
                            "carousel_media": [
                                {
                                    "__typename": "XIGPolarisImageMedia",
                                    "image_versions2": {
                                        "candidates": [
                                            { "url": "https://instagram.fxxx.fbcdn.net/slide1.jpg", "width": 1080, "height": 1080 }
                                        ]
                                    }
                                },
                                {
                                    "__typename": "XIGPolarisImageMedia",
                                    "image_versions2": {
                                        "candidates": [
                                            { "url": "https://instagram.fxxx.fbcdn.net/slide2.jpg", "width": 1080, "height": 1080 }
                                        ]
                                    }
                                },
                                {
                                    "__typename": "XIGPolarisImageMedia",
                                    "image_versions2": {
                                        "candidates": [
                                            { "url": "https://instagram.fxxx.fbcdn.net/slide3.jpg", "width": 1080, "height": 1080 }
                                        ]
                                    }
                                }
                            ]
                        }
                    }
                }]
            }
        }
        """.trimIndent()

        val botHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Kim Kardashian on Instagram: &quot;🧁&quot;" />
            <meta property="og:description" content="1,620,641 likes, 9,323 comments - kimkardashian on August 20, 2024: &quot;🧁&quot;" />
            <meta property="og:image" content="https://instagram.fxxx.fbcdn.net/slide1.jpg" />
            <script type="application/json">$scriptJson</script>
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath == "/p/C-6WRvdS75Q/") {
                htmlResponse(botHtml)
            } else {
                htmlResponse("Not found", HttpStatusCode.NotFound)
            }
        }

        val post = resolver.resolve("https://www.instagram.com/p/C-6WRvdS75Q/", client)

        assertEquals(Platform.INSTAGRAM, post.platform)
        assertEquals("C-6WRvdS75Q", post.id)
        assertEquals("kimkardashian", post.author.username)
        assertEquals("Kim Kardashian", post.author.displayName)
        assertEquals("🧁", post.content)
        assertEquals(3, post.media.size)
        assertEquals("https://instagram.fxxx.fbcdn.net/slide1.jpg", (post.media[0] as Media.Image).url)
        assertEquals("https://instagram.fxxx.fbcdn.net/slide2.jpg", (post.media[1] as Media.Image).url)
        assertEquals("https://instagram.fxxx.fbcdn.net/slide3.jpg", (post.media[2] as Media.Image).url)
    }

    @Test
    fun `resolve should fallback to embed page when direct bot request fails`() = runTest {
        val embedHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Photo by John Doe on Instagram" />
            <meta property="og:description" content="Beautiful sunset in Tokyo! #travel #sunset" />
            <meta property="og:image" content="https://instagram.fxxx.fbcdn.net/sunset.jpg" />
        </head>
        <body>
            <div class="Caption">
                <a class="CaptionUsername" href="/johndoe/">johndoe</a>
                <span class="CaptionComments">Beautiful sunset in Tokyo! #travel #sunset</span>
            </div>
            <div class="Avatar">
                <img src="https://instagram.fxxx.fbcdn.net/avatar.jpg" alt="johndoe's profile picture" />
            </div>
            <img class="EmbeddedMediaImage" src="https://instagram.fxxx.fbcdn.net/sunset.jpg" />
        </body>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("embed/captioned")) {
                htmlResponse(embedHtml)
            } else {
                htmlResponse("Not found", HttpStatusCode.NotFound)
            }
        }

        val post = resolver.resolve("https://www.instagram.com/share/p/Cx12345abc/", client)

        assertEquals(Platform.INSTAGRAM, post.platform)
        assertEquals("Cx12345abc", post.id)
        assertEquals("johndoe", post.author.username)
        assertEquals("https://www.instagram.com/johndoe/", post.author.profileUrl)
        assertEquals("https://instagram.fxxx.fbcdn.net/avatar.jpg", post.author.avatarUrl)
        assertTrue(post.content.contains("Beautiful sunset in Tokyo!"))
        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://instagram.fxxx.fbcdn.net/sunset.jpg", image.url)
    }

    @Test
    fun `resolve should parse instagram reel video post from embed page`() = runTest {
        val embedHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Reel by cool_creator on Instagram" />
            <meta property="og:description" content="Check out my new reel!" />
            <meta property="og:image" content="https://instagram.fxxx.fbcdn.net/poster.jpg" />
            <meta property="og:video" content="https://instagram.fxxx.fbcdn.net/video.mp4" />
        </head>
        <body>
            <div class="Caption">
                <a class="CaptionUsername" href="/cool_creator/">cool_creator</a>
                <span class="CaptionComments">Check out my new reel!</span>
            </div>
            <video src="https://instagram.fxxx.fbcdn.net/video.mp4" poster="https://instagram.fxxx.fbcdn.net/poster.jpg"></video>
        </body>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("embed/captioned")) {
                htmlResponse(embedHtml)
            } else {
                htmlResponse("Not found", HttpStatusCode.NotFound)
            }
        }

        val post = resolver.resolve("https://www.instagram.com/reel/CxReel123/", client)

        assertEquals("CxReel123", post.id)
        assertEquals("cool_creator", post.author.username)
        assertEquals(1, post.media.size)
        val video = post.media.first() as Media.Video
        assertEquals("https://instagram.fxxx.fbcdn.net/video.mp4", video.url)
        assertEquals("https://instagram.fxxx.fbcdn.net/poster.jpg", video.previewUrl)
    }

    @Test
    fun `resolve should throw PostNotFoundException when embed page returns 404`() = runTest {
        val client = createMockHttpClient {
            htmlResponse("Not found", HttpStatusCode.NotFound)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://www.instagram.com/p/NotExist123/", client)
        }
    }
}
