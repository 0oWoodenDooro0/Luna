package dev.socialpeek.resolver.threads

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ThreadsResolverTest {
    private val resolver = ThreadsResolver()

    @Test
    fun `canResolve should match valid Threads post URLs`() {
        assertTrue(resolver.canResolve("https://www.threads.net/@zuck/post/CuP48CiS5sx"))
        assertTrue(resolver.canResolve("https://threads.net/@zuck/post/CuP48CiS5sx"))
        assertTrue(resolver.canResolve("https://www.threads.net/t/CuP48CiS5sx"))
        assertTrue(resolver.canResolve("https://threads.net/t/CuP48CiS5sx"))
        assertTrue(resolver.canResolve("https://threads.com/@user/post/12345"))
        assertTrue(resolver.canResolve("https://www.threads.net/share/BAENHoOpq1/"))
        assertTrue(resolver.canResolve("https://threads.com/share/BAENHoOpq1/"))
    }

    @Test
    fun `canResolve should reject non-threads URLs`() {
        assertFalse(resolver.canResolve("https://www.instagram.com/p/CuP48CiS5sx"))
        assertFalse(resolver.canResolve("https://twitter.com/zuck/status/123456"))
        assertFalse(resolver.canResolve("https://www.threads.net/@zuck"))
    }

    @Test
    fun `resolve should extract post info from threads page`() =
        runTest {
            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
                    <meta property="og:description" content="Welcome to Threads! Glad you are all here." />
                    <meta property="og:image" content="https://scontent.cdninstagram.com/photo.jpg" />
                    <meta property="og:image:width" content="1080" />
                    <meta property="og:image:height" content="1080" />
                    <meta property="og:url" content="https://www.threads.net/@zuck/post/CuP48CiS5sx" />
                </head>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    htmlResponse(pageHtml)
                }

            val post = resolver.resolve("https://www.threads.net/@zuck/post/CuP48CiS5sx", client)

            assertEquals(Platform.THREADS, post.platform)
            assertEquals("CuP48CiS5sx", post.id)
            assertEquals("zuck", post.author.username)
            assertEquals("Mark Zuckerberg", post.author.displayName)
            assertEquals("https://www.threads.net/@zuck", post.author.profileUrl)
            assertEquals("Welcome to Threads! Glad you are all here.", post.content)
            assertEquals(1, post.media.size)
            val image = post.media.first() as Media.Image
            assertEquals("https://scontent.cdninstagram.com/photo.jpg", image.url)
            assertEquals(1080, image.width)
            assertEquals(1080, image.height)
        }

    @Test
    fun `resolve should parse text-only post without media and map avatar to author`() =
        runTest {
            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
                    <meta property="og:description" content="Let's do this. Welcome to Threads. 🔥" />
                    <meta property="og:image" content="https://instagram.fbcdn.net/v/t51.82787-19/avatar.jpg?efg=profile_pic" />
                    <meta name="twitter:card" content="summary" />
                    <meta property="og:url" content="https://www.threads.net/@zuck/post/CuP48CiS5sx" />
                </head>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    htmlResponse(pageHtml)
                }

            val post = resolver.resolve("https://www.threads.net/@zuck/post/CuP48CiS5sx", client)

            assertEquals("CuP48CiS5sx", post.id)
            assertEquals("zuck", post.author.username)
            assertEquals("Mark Zuckerberg", post.author.displayName)
            assertEquals("Let's do this. Welcome to Threads. 🔥", post.content)
            assertEquals("https://instagram.fbcdn.net/v/t51.82787-19/avatar.jpg?efg=profile_pic", post.author.avatarUrl)
            assertTrue(post.media.isEmpty())
        }

    @Test
    fun `resolve should filter out 1200x628 platform share card banner for text post`() =
        runTest {
            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
                    <meta property="og:description" content="Let's do this. Welcome to Threads. 🔥" />
                    <meta property="og:image" content="https://scontent.fbcdn.net/v/t39.92108-6/802988515_preview.jpg" />
                    <meta property="og:image:width" content="1200" />
                    <meta property="og:image:height" content="628" />
                    <meta name="twitter:card" content="summary_large_image" />
                    <meta property="og:url" content="https://www.threads.net/@zuck/post/CuP48CiS5sx" />
                </head>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    htmlResponse(pageHtml)
                }

            val post = resolver.resolve("https://www.threads.net/@zuck/post/CuP48CiS5sx", client)

            assertEquals("CuP48CiS5sx", post.id)
            assertEquals("Let's do this. Welcome to Threads. 🔥", post.content)
            assertTrue(post.media.isEmpty())
        }

    @Test
    fun `resolve should extract all images from multi-image carousel post with null video_versions`() =
        runTest {
            val scriptJson =
                """
                {
                    "require": [
                        ["RelayPrefetchedStreamCache", "next", [], [{
                            "data": {
                                "user": {
                                    "username": "zuck",
                                    "full_name": "Mark Zuckerberg",
                                    "profile_pic_url": "https://instagram.fbcdn.net/avatar.jpg"
                                },
                                "carousel_media": [
                                    {
                                        "__typename": "XIGPolarisImageMedia",
                                        "video_versions": null,
                                        "image_versions2": {
                                            "candidates": [
                                                { "url": "https://instagram.fbcdn.net/photo1.jpg", "width": 1440, "height": 1440 }
                                            ]
                                        }
                                    },
                                    {
                                        "__typename": "XIGPolarisImageMedia",
                                        "video_versions": null,
                                        "image_versions2": {
                                            "candidates": [
                                                { "url": "https://instagram.fbcdn.net/photo2.jpg", "width": 1440, "height": 1440 }
                                            ]
                                        }
                                    },
                                    {
                                        "__typename": "XIGPolarisImageMedia",
                                        "video_versions": null,
                                        "image_versions2": {
                                            "candidates": [
                                                { "url": "https://instagram.fbcdn.net/photo3.jpg", "width": 1440, "height": 1440 }
                                            ]
                                        }
                                    }
                                ]
                            }
                        }]]
                    ]
                }
                """.trimIndent()

            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="Mark Zuckerberg (@zuck) on Threads" />
                    <meta property="og:description" content="Look at these 3 AI creations!" />
                    <meta property="og:image" content="https://instagram.fbcdn.net/photo1.jpg" />
                    <script type="application/json">$scriptJson</script>
                </head>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    htmlResponse(pageHtml)
                }

            val post = resolver.resolve("https://www.threads.net/@zuck/post/C9xxwZZyx5B", client)

            assertEquals("C9xxwZZyx5B", post.id)
            assertEquals("zuck", post.author.username)
            assertEquals("Mark Zuckerberg", post.author.displayName)
            assertEquals("https://instagram.fbcdn.net/avatar.jpg", post.author.avatarUrl)
            assertEquals(3, post.media.size)
            assertEquals("https://instagram.fbcdn.net/photo1.jpg", (post.media[0] as Media.Image).url)
            assertEquals("https://instagram.fbcdn.net/photo2.jpg", (post.media[1] as Media.Image).url)
            assertEquals("https://instagram.fbcdn.net/photo3.jpg", (post.media[2] as Media.Image).url)
        }

    @Test
    fun `resolve should extract video and preview image from quoted attachment post`() =
        runTest {
            val scriptJson =
                """
                {
                    "require": [
                        ["RelayPrefetchedStreamCache", "next", [], [{
                            "__bbox": {
                                "result": {
                                    "data": {
                                        "media": {
                                            "code": "DeynNaEmZHj",
                                            "user": {
                                                "username": "ou.omar.71",
                                                "full_name": "嘎",
                                                "profile_pic_url": "https://instagram.fbcdn.net/avatar.jpg",
                                                "is_verified": false
                                            },
                                            "caption": { "text": "留起來吵朋友" },
                                            "like_count": 497,
                                            "taken_at": 1792659670,
                                            "text_post_app_info": {
                                                "direct_reply_count": 6,
                                                "repost_count": 57,
                                                "share_info": {
                                                    "quoted_attachment_post": {
                                                        "code": "Dexy6YlCk-N",
                                                        "original_width": 768,
                                                        "original_height": 576,
                                                        "video_versions": [
                                                            { "type": 101, "url": "https://cdn.threads.net/video.mp4" }
                                                        ],
                                                        "image_versions2": {
                                                            "candidates": [
                                                                { "url": "https://cdn.threads.net/cover.jpg", "width": 640, "height": 480 }
                                                            ]
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }]]
                    ]
                }
                """.trimIndent()

            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="嘎 (@ou.omar.71) on Threads" />
                    <meta property="og:description" content="留起來吵朋友" />
                    <meta property="og:image" content="https://instagram.fbcdn.net/avatar.jpg?efg=profile_pic" />
                    <script type="application/json">$scriptJson</script>
                </head>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    htmlResponse(pageHtml)
                }

            val post = resolver.resolve("https://www.threads.net/@ou.omar.71/post/DeynNaEmZHj", client)

            assertEquals("DeynNaEmZHj", post.id)
            assertEquals("ou.omar.71", post.author.username)
            assertEquals("嘎", post.author.displayName)
            assertEquals("留起來吵朋友", post.content)
            assertEquals(1, post.media.size)
            assertTrue(post.media.first() is Media.Video)
            val video = post.media.first() as Media.Video
            assertEquals("https://cdn.threads.net/video.mp4", video.url)
            assertEquals("https://cdn.threads.net/cover.jpg", video.previewUrl)
            assertEquals(640, video.width)
            assertEquals(480, video.height)
            assertEquals(497L, post.metrics?.likes)
            assertEquals(6L, post.metrics?.comments)
            assertEquals(57L, post.metrics?.reposts)
            assertEquals(1792659670L, post.createdAtEpochSeconds)
        }

    @Test
    fun `resolve should extract direct video post with poster preview and dimensions`() =
        runTest {
            val scriptJson =
                """
                {
                    "require": [
                        ["RelayPrefetchedStreamCache", "next", [], [{
                            "__bbox": {
                                "result": {
                                    "data": {
                                        "media": {
                                            "code": "DirectVideo123",
                                            "user": {
                                                "username": "videocreator",
                                                "full_name": "Video Creator",
                                                "profile_pic_url": "https://instagram.fbcdn.net/avatar.jpg"
                                            },
                                            "video_versions": [
                                                { "type": 101, "url": "https://cdn.threads.net/direct_video.mp4", "width": 1080, "height": 1920 }
                                            ],
                                            "image_versions2": {
                                                "candidates": [
                                                    { "url": "https://cdn.threads.net/direct_preview.jpg", "width": 1080, "height": 1920 }
                                                ]
                                            }
                                        }
                                    }
                                }
                            }
                        }]]
                    ]
                }
                """.trimIndent()

            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="Video Creator (@videocreator) on Threads" />
                    <meta property="og:description" content="Check out my new video" />
                    <script type="application/json">$scriptJson</script>
                </head>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    htmlResponse(pageHtml)
                }

            val post = resolver.resolve("https://www.threads.net/@videocreator/post/DirectVideo123", client)
            assertEquals(1, post.media.size)
            assertTrue(post.media.first() is Media.Video)
            val video = post.media.first() as Media.Video
            assertEquals("https://cdn.threads.net/direct_video.mp4", video.url)
            assertEquals("https://cdn.threads.net/direct_preview.jpg", video.previewUrl)
            assertEquals(1080, video.width)
            assertEquals(1920, video.height)
        }

    @Test
    fun `resolve should extract GIPHY gif animation with still preview image`() =
        runTest {
            val scriptJson =
                """
                {
                    "require": [
                        ["RelayPrefetchedStreamCache", "next", [], [{
                            "__bbox": {
                                "result": {
                                    "data": {
                                        "media": {
                                            "code": "GiphyPost123",
                                            "user": {
                                                "username": "giflover",
                                                "full_name": "GIF Lover",
                                                "profile_pic_url": "https://instagram.fbcdn.net/avatar.jpg"
                                            },
                                            "giphy_media_info": {
                                                "images": {
                                                    "original": {
                                                        "mp4": "https://media.giphy.com/media/xyz/giphy.mp4",
                                                        "url": "https://media.giphy.com/media/xyz/giphy.gif",
                                                        "width": 480,
                                                        "height": 480
                                                    },
                                                    "original_still": {
                                                        "url": "https://media.giphy.com/media/xyz/giphy_s.jpg",
                                                        "width": 480,
                                                        "height": 480
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }]]
                    ]
                }
                """.trimIndent()

            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="GIF Lover (@giflover) on Threads" />
                    <meta property="og:description" content="Mood today" />
                    <script type="application/json">$scriptJson</script>
                </head>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    htmlResponse(pageHtml)
                }

            val post = resolver.resolve("https://www.threads.net/@giflover/post/GiphyPost123", client)
            assertEquals(1, post.media.size)
            assertTrue(post.media.first() is Media.Video)
            val gifMedia = post.media.first() as Media.Video
            assertEquals("https://media.giphy.com/media/xyz/giphy.mp4", gifMedia.url)
            assertEquals("https://media.giphy.com/media/xyz/giphy_s.jpg", gifMedia.previewUrl)
            assertEquals(480, gifMedia.width)
            assertEquals(480, gifMedia.height)
        }

    @Test
    fun `resolve should handle threads share link redirect`() =
        runTest {
            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="Snow Island (@snowisland.jp) on Threads" />
                    <meta property="og:description" content="Share link content" />
                    <meta property="og:image" content="https://scontent.cdninstagram.com/share_photo.jpg" />
                </head>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    when (it.url.encodedPath) {
                        "/share/BAENHoOpq1/" -> {
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, "https://www.threads.net/@snowisland.jp/post/CuP48CiS5sx"),
                            )
                        }

                        else -> {
                            htmlResponse(pageHtml)
                        }
                    }
                }

            val post = resolver.resolve("https://www.threads.net/share/BAENHoOpq1/", client)
            assertEquals("CuP48CiS5sx", post.id)
            assertEquals("snowisland.jp", post.author.username)
        }

    @Test
    fun `resolve should throw PostNotFoundException when post does not exist`() =
        runTest {
            val pageHtml =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Page Not Found &bull; Threads</title>
                </head>
                <body>
                    <div>Sorry, this page isn't available.</div>
                </body>
                </html>
                """.trimIndent()

            val client =
                createMockHttpClient {
                    htmlResponse(pageHtml)
                }

            assertFailsWith<PostNotFoundException> {
                resolver.resolve("https://www.threads.net/@zuck/post/invalidId", client)
            }
        }
}
