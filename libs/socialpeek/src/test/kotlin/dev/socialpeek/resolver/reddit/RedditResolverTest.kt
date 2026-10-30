package dev.socialpeek.resolver.reddit

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import dev.socialpeek.test.jsonResponse
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedditResolverTest {

    private val resolver = RedditResolver()

    @Test
    fun `canResolve should match valid Reddit post and comment URLs`() {
        assertTrue(resolver.canResolve("https://www.reddit.com/r/kotlindev/comments/12345/socialpeek_released/"))
        assertTrue(resolver.canResolve("https://reddit.com/comments/12345/"))
        assertTrue(resolver.canResolve("https://old.reddit.com/r/androiddev/comments/abcde/"))
        assertTrue(resolver.canResolve("https://redd.it/12345"))
        assertTrue(resolver.canResolve("https://reddit.com/r/technology/s/9876543210"))
        assertTrue(resolver.canResolve("https://redd.it/s/9876543210"))

        assertFalse(resolver.canResolve("https://reddit.com/r/kotlindev/"))
        assertFalse(resolver.canResolve("https://reddit.com/user/someone/"))
        assertFalse(resolver.canResolve("https://twitter.com/jack/status/20"))
    }

    @Test
    fun `resolve should parse text post correctly`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "t3_12345",
                                "title": "SocialPeek Released!",
                                "selftext": "A brand new social media metadata parser for Kotlin.",
                                "author": "kotlin_dev",
                                "subreddit": "kotlindev",
                                "ups": 350,
                                "num_comments": 42,
                                "permalink": "/r/kotlindev/comments/12345/socialpeek_released/",
                                "created_utc": 1715000000.0
                            }
                        }
                    ]
                }
            }
        ]
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://www.reddit.com/r/kotlindev/comments/12345/socialpeek_released/", client)

        assertEquals(Platform.REDDIT, post.platform)
        assertEquals("t3_12345", post.id)
        assertEquals("SocialPeek Released!", post.title)
        assertEquals("A brand new social media metadata parser for Kotlin.", post.content)
        assertEquals("kotlin_dev", post.author.username)
        assertEquals("u/kotlin_dev", post.author.displayName)
        assertEquals("https://www.reddit.com/user/kotlin_dev", post.author.profileUrl)
        assertEquals("kotlindev", post.community)
        assertEquals("kotlindev", post.subreddit)
        assertEquals("kotlindev", post.board)
        assertEquals(350, post.metrics?.likes)
        assertEquals(42, post.metrics?.comments)
        assertEquals(1715000000L, post.createdAtEpochSeconds)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun `resolve should fallback to oEmbed and Bot OpenGraph and ignore platform share preview card`() = runTest {
        val oEmbedJson = """
        {
            "title": "Account Disabled",
            "author_name": "xethorn",
            "html": "<blockquote class=\"reddit-embed-bq\"><a href=\"https://www.reddit.com/r/google_antigravity/comments/1wbqdaj/\">Post</a> in <a href=\"https://www.reddit.com/r/google_antigravity/\">google_antigravity</a></blockquote>"
        }
        """.trimIndent()

        val botHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="description" content="197 votes, 138 comments. My account has been disabled." />
            <meta property="og:image" content="https://share.redd.it/preview/post/1wbqdaj" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("oembed")) {
                jsonResponse(oEmbedJson)
            } else if (request.url.encodedPath.contains("comments/1wbqdaj")) {
                htmlResponse(botHtml)
            } else {
                respond("Blocked", HttpStatusCode.Forbidden)
            }
        }

        val post = resolver.resolve("https://www.reddit.com/r/google_antigravity/comments/1wbqdaj/account_disabled/", client)

        assertEquals(Platform.REDDIT, post.platform)
        assertEquals("1wbqdaj", post.id)
        assertEquals("Account Disabled", post.title)
        assertEquals("xethorn", post.author.username)
        assertEquals("google_antigravity", post.community)
        assertEquals("google_antigravity", post.subreddit)
        assertEquals("google_antigravity", post.board)
        assertEquals("My account has been disabled.", post.content)
        assertEquals(197L, post.metrics?.likes)
        assertEquals(138L, post.metrics?.comments)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun `resolve fallback should include real media image when present in og image`() = runTest {
        val oEmbedJson = """
        {
            "title": "Look at my art",
            "author_name": "artist_user",
            "html": "<blockquote class=\"reddit-embed-bq\"><a href=\"https://www.reddit.com/r/art/comments/art123/\">Post</a> in <a href=\"https://www.reddit.com/r/art/\">art</a></blockquote>"
        }
        """.trimIndent()

        val botHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="description" content="500 votes, 20 comments. Oil painting on canvas." />
            <meta property="og:image" content="https://preview.redd.it/my_art_photo.jpg" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("oembed")) {
                jsonResponse(oEmbedJson)
            } else if (request.url.encodedPath.contains("comments/art123")) {
                htmlResponse(botHtml)
            } else {
                respond("Blocked", HttpStatusCode.Forbidden)
            }
        }

        val post = resolver.resolve("https://www.reddit.com/r/art/comments/art123/look_at_my_art/", client)

        assertEquals(Platform.REDDIT, post.platform)
        assertEquals("art123", post.id)
        assertEquals("Look at my art", post.title)
        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://preview.redd.it/my_art_photo.jpg", image.url)
    }

    @Test
    fun `resolve should follow redirect for share link and extract community`() = runTest {
        val oEmbedJson = """
        {
            "title": "Account Disabled",
            "author_name": "xethorn",
            "provider_name": "Reddit"
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("/s/")) {
                respond(
                    content = "",
                    status = HttpStatusCode.MovedPermanently,
                    headers = headersOf(HttpHeaders.Location, "https://www.reddit.com/r/google_antigravity/comments/1wbqdaj/account_disabled/")
                )
            } else if (request.url.encodedPath.contains("oembed")) {
                jsonResponse(oEmbedJson)
            } else {
                respond("Blocked", HttpStatusCode.Forbidden)
            }
        }

        val post = resolver.resolve("https://www.reddit.com/r/google_antigravity/s/7GwvvFKRsE", client)
        assertEquals("1wbqdaj", post.id)
        assertEquals("Account Disabled", post.title)
        assertEquals("xethorn", post.author.username)
        assertEquals("google_antigravity", post.community)
        assertEquals("google_antigravity", post.subreddit)
    }

    @Test
    fun `resolve should parse single image post`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "img123",
                                "title": "Cute Cat",
                                "selftext": "",
                                "author": "cat_lover",
                                "subreddit": "aww",
                                "post_hint": "image",
                                "url": "https://i.redd.it/cat_picture.jpg",
                                "preview": {
                                    "images": [
                                        {
                                            "source": {
                                                "url": "https://preview.redd.it/cat_picture.jpg?auto=webp&amp;s=abc",
                                                "width": 1080,
                                                "height": 720
                                            }
                                        }
                                    ]
                                },
                                "ups": 1500,
                                "num_comments": 50
                            }
                        }
                    ]
                }
            }
        ]
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://reddit.com/r/aww/comments/img123/cute_cat/", client)

        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://i.redd.it/cat_picture.jpg", image.url)
        assertEquals(1080, image.width)
        assertEquals(720, image.height)
    }

    @Test
    fun `resolve should parse multi-image gallery post from JSON`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "gallery123",
                                "title": "My 3 vacation photos",
                                "selftext": "Enjoying the summer!",
                                "author": "traveler",
                                "subreddit": "travel",
                                "is_gallery": true,
                                "gallery_data": {
                                    "items": [
                                        { "media_id": "img_1" },
                                        { "media_id": "img_2" },
                                        { "media_id": "img_3" }
                                    ]
                                },
                                "media_metadata": {
                                    "img_1": {
                                        "s": { "u": "https://preview.redd.it/photo1.jpg?width=1080&amp;crop=smart&amp;auto=webp", "x": 1080, "y": 720 }
                                    },
                                    "img_2": {
                                        "s": { "u": "https://preview.redd.it/photo2.jpg?width=1080&amp;crop=smart&amp;auto=webp", "x": 1080, "y": 720 }
                                    },
                                    "img_3": {
                                        "s": { "u": "https://preview.redd.it/photo3.jpg?width=1080&amp;crop=smart&amp;auto=webp", "x": 1080, "y": 720 }
                                    }
                                }
                            }
                        }
                    ]
                }
            }
        ]
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(mockJson)
        }

        val post = resolver.resolve("https://www.reddit.com/r/travel/comments/gallery123/my_3_vacation_photos/", client)

        assertEquals("gallery123", post.id)
        assertEquals(3, post.media.size)
        assertEquals("https://preview.redd.it/photo1.jpg?width=1080&crop=smart&auto=webp", (post.media[0] as Media.Image).url)
        assertEquals("https://preview.redd.it/photo2.jpg?width=1080&crop=smart&auto=webp", (post.media[1] as Media.Image).url)
        assertEquals("https://preview.redd.it/photo3.jpg?width=1080&crop=smart&auto=webp", (post.media[2] as Media.Image).url)
    }

    @Test
    fun `resolve should extract communityIcon from subreddit about`() = runTest {
        val mockJson = """
        [
            {
                "data": {
                    "children": [
                        {
                            "data": {
                                "id": "t3_12345",
                                "title": "SocialPeek Released!",
                                "selftext": "A brand new social media metadata parser for Kotlin.",
                                "author": "kotlin_dev",
                                "subreddit": "kotlindev"
                            }
                        }
                    ]
                }
            }
        ]
        """.trimIndent()

        val mockAboutJson = """
        {
            "data": {
                "community_icon": "https://styles.redditmedia.com/t5_123/styles/communityIcon_abc.png?width=256&amp;s=xyz"
            }
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.encodedPath.contains("/about")) {
                jsonResponse(mockAboutJson)
            } else {
                jsonResponse(mockJson)
            }
        }

        val post = resolver.resolve("https://www.reddit.com/r/kotlindev/comments/12345/socialpeek_released/", client)

        assertEquals("kotlindev", post.community)
        assertEquals("https://styles.redditmedia.com/t5_123/styles/communityIcon_abc.png?width=256&s=xyz", post.communityIcon)
        assertEquals("https://styles.redditmedia.com/t5_123/styles/communityIcon_abc.png?width=256&s=xyz", post.rawData["community_icon"])
    }

    @Test
    fun `resolve should throw PostNotFoundException when post does not exist`() = runTest {
        val client = createMockHttpClient {
            respond("Not Found", HttpStatusCode.NotFound)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://reddit.com/comments/unknown", client)
        }
    }
}
