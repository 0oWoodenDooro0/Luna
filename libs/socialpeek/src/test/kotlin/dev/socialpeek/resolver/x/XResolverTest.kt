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
import kotlin.test.assertNotNull
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
    fun `resolve should parse single image tweet correctly and populate cleanUrl`() =
        runTest {
            val mockJson =
                """
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

            val client =
                createMockHttpClient { request ->
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
    fun `resolve should parse 4 photos from multi-photo tweet`() =
        runTest {
            val mockJson =
                """
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

            val client =
                createMockHttpClient {
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
    fun `resolve should parse video tweet and select highest bitrate mp4`() =
        runTest {
            val mockJson =
                """
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

            val client =
                createMockHttpClient {
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
    fun `resolve should throw PostNotFoundException on 404 response`() =
        runTest {
            val client =
                createMockHttpClient {
                    jsonResponse("{}", status = HttpStatusCode.NotFound)
                }

            assertFailsWith<PostNotFoundException> {
                resolver.resolve("https://x.com/ghost/status/404404404", client)
            }
        }

    @Test
    fun `resolve should parse quoted post with outer commentary and strip trailing quote url`() =
        runTest {
            val mockJson =
                """
                {
                    "id_str": "10001",
                    "text": "This is brilliant commentary https://t.co/quoteLink123",
                    "user": {
                        "id_str": "111",
                        "name": "Commentator",
                        "screen_name": "commentator",
                        "profile_image_url_https": "https://pbs.twimg.com/avatar_comm.jpg"
                    },
                    "quoted_tweet": {
                        "id_str": "20002",
                        "text": "Original deep insight",
                        "user": {
                            "id_str": "222",
                            "name": "Original Author",
                            "screen_name": "original_author",
                            "profile_image_url_https": "https://pbs.twimg.com/avatar_orig.jpg"
                        },
                        "photos": [
                            {
                                "url": "https://pbs.twimg.com/media/quoted_pic.jpg",
                                "width": 800,
                                "height": 600
                            }
                        ],
                        "favorite_count": 500,
                        "conversation_count": 42
                    }
                }
                """.trimIndent()

            val client =
                createMockHttpClient {
                    jsonResponse(mockJson)
                }

            val post = resolver.resolve("https://x.com/commentator/status/10001", client)

            assertEquals("10001", post.id)
            assertEquals("This is brilliant commentary\n\n[引用 @original_author: Original deep insight]", post.content)
            val quoted = post.quotedPost
            assertNotNull(quoted)
            assertEquals("20002", quoted.id)
            assertEquals("original_author", quoted.author.username)
            assertEquals("Original Author", quoted.author.displayName)
            assertEquals("Original deep insight", quoted.content)
            assertEquals(500L, quoted.metrics?.likes)
            assertEquals(42L, quoted.metrics?.comments)
            assertEquals(1, quoted.media.size)
            assertEquals("https://pbs.twimg.com/media/quoted_pic.jpg", (quoted.media.first() as Media.Image).url)

            // Media fallback: outer tweet had no media, so it should inherit from quoted post
            assertEquals(1, post.media.size)
            assertEquals("https://pbs.twimg.com/media/quoted_pic.jpg", (post.media.first() as Media.Image).url)
        }

    @Test
    fun `resolve should parse quoted post without outer commentary`() =
        runTest {
            val mockJson =
                """
                {
                    "id_str": "10002",
                    "text": "https://t.co/quoteLink456",
                    "user": {
                        "id_str": "333",
                        "name": "Retweeter",
                        "screen_name": "retweeter"
                    },
                    "quoted_tweet": {
                        "id_str": "30003",
                        "text": "Just the quoted news",
                        "user": {
                            "name": "News Outlet",
                            "screen_name": "news_outlet"
                        }
                    }
                }
                """.trimIndent()

            val client =
                createMockHttpClient {
                    jsonResponse(mockJson)
                }

            val post = resolver.resolve("https://x.com/retweeter/status/10002", client)

            assertEquals("[引用 @news_outlet: Just the quoted news]", post.content)
            val quoted = post.quotedPost
            assertNotNull(quoted)
            assertEquals("30003", quoted.id)
            assertEquals("news_outlet", quoted.author.username)
            assertEquals("Just the quoted news", quoted.content)
        }

    @Test
    fun `resolve should preserve outer media when outer tweet has its own media`() =
        runTest {
            val mockJson =
                """
                {
                    "id_str": "10003",
                    "text": "Look at my photo quoting this https://t.co/quoteLink789",
                    "user": {
                        "name": "Photographer",
                        "screen_name": "photographer"
                    },
                    "photos": [
                        {
                            "url": "https://pbs.twimg.com/media/outer_pic.jpg",
                            "width": 1000,
                            "height": 1000
                        }
                    ],
                    "quoted_tweet": {
                        "id_str": "40004",
                        "text": "Quoted video clip",
                        "user": {
                            "name": "Videographer",
                            "screen_name": "videographer"
                        },
                        "video": {
                            "poster": "https://pbs.twimg.com/video_thumb.jpg",
                            "variants": [
                                { "type": "video/mp4", "src": "https://video.twimg.com/quoted_vid.mp4", "bitrate": 1500000 }
                            ]
                        }
                    }
                }
                """.trimIndent()

            val client =
                createMockHttpClient {
                    jsonResponse(mockJson)
                }

            val post = resolver.resolve("https://x.com/photographer/status/10003", client)

            assertEquals("Look at my photo quoting this\n\n[引用 @videographer: Quoted video clip]", post.content)
            assertEquals(1, post.media.size)
            assertEquals("https://pbs.twimg.com/media/outer_pic.jpg", (post.media.first() as Media.Image).url)
            val quoted = post.quotedPost
            assertNotNull(quoted)
            assertEquals(1, quoted.media.size)
            val quotedMedia = quoted.media.first() as Media.Video
            assertEquals("https://video.twimg.com/quoted_vid.mp4", quotedMedia.url)
            assertEquals("https://pbs.twimg.com/video_thumb.jpg", quotedMedia.previewUrl)
        }

    @Test
    fun `resolve should parse cross-tweet media embed with empty outer text`() =
        runTest {
            val outerTweetJson =
                """
                {
                    "id_str": "2112817190735085873",
                    "text": "https://t.co/lUT27eowKZ",
                    "user": {
                        "name": "Joshi",
                        "screen_name": "joshi_no0"
                    },
                    "mediaDetails": [
                        {
                            "expanded_url": "https://x.com/BlackSandStocks/status/2087832788661854276/video/1"
                        }
                    ]
                }
                """.trimIndent()

            val sourceTweetJson =
                """
                {
                    "id_str": "2087832788661854276",
                    "text": "카메라 뺏은분 신고 가능한가요????",
                    "user": {
                        "name": "BlackSandStocks",
                        "screen_name": "BlackSandStocks"
                    }
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when (request.url.parameters["id"]) {
                        "2112817190735085873" -> jsonResponse(outerTweetJson)
                        "2087832788661854276" -> jsonResponse(sourceTweetJson)
                        else -> jsonResponse("{}", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolver.resolve("https://x.com/joshi_no0/status/2112817190735085873", client)

            assertEquals("[引用 @BlackSandStocks: 카메라 뺏은분 신고 가능한가요????]", post.content)
            val quoted = post.quotedPost
            assertNotNull(quoted)
            assertEquals("2087832788661854276", quoted.id)
            assertEquals("BlackSandStocks", quoted.author.username)
            assertEquals("카메라 뺏은분 신고 가능한가요????", quoted.content)
        }

    @Test
    fun `resolve should parse cross-tweet media embed with outer commentary`() =
        runTest {
            val outerTweetJson =
                """
                {
                    "id_str": "2112817190735085873",
                    "text": "Check this out https://t.co/lUT27eowKZ",
                    "user": {
                        "name": "Joshi",
                        "screen_name": "joshi_no0"
                    },
                    "entities": {
                        "media": [
                            {
                                "expanded_url": "https://x.com/BlackSandStocks/status/2087832788661854276/video/1"
                            }
                        ]
                    }
                }
                """.trimIndent()

            val sourceTweetJson =
                """
                {
                    "id_str": "2087832788661854276",
                    "text": "카메라 뺏은분 신고 가능한가요????",
                    "user": {
                        "name": "BlackSandStocks",
                        "screen_name": "BlackSandStocks"
                    }
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when (request.url.parameters["id"]) {
                        "2112817190735085873" -> jsonResponse(outerTweetJson)
                        "2087832788661854276" -> jsonResponse(sourceTweetJson)
                        else -> jsonResponse("{}", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolver.resolve("https://x.com/joshi_no0/status/2112817190735085873", client)

            assertEquals("Check this out\n\n[引用 @BlackSandStocks: 카메라 뺏은분 신고 가능한가요????]", post.content)
            val quoted = post.quotedPost
            assertNotNull(quoted)
            assertEquals("2087832788661854276", quoted.id)
            assertEquals("BlackSandStocks", quoted.author.username)
            assertEquals("카메라 뺏은분 신고 가능한가요????", quoted.content)
        }

    @Test
    fun `resolve should gracefully fall back to outer content when cross-tweet source returns 404`() =
        runTest {
            val outerTweetJson =
                """
                {
                    "id_str": "2112817190735085873",
                    "text": "Outer commentary https://t.co/lUT27eowKZ",
                    "user": {
                        "name": "Joshi",
                        "screen_name": "joshi_no0"
                    },
                    "mediaDetails": [
                        {
                            "expanded_url": "https://x.com/BlackSandStocks/status/2087832788661854276/video/1"
                        }
                    ]
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when (request.url.parameters["id"]) {
                        "2112817190735085873" -> jsonResponse(outerTweetJson)
                        else -> jsonResponse("{}", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolver.resolve("https://x.com/joshi_no0/status/2112817190735085873", client)

            assertEquals("Outer commentary https://t.co/lUT27eowKZ", post.content)
            assertEquals(null, post.quotedPost)
        }

    @Test
    fun `resolve should not cascade cross-tweet media embed beyond single hop`() =
        runTest {
            val outerTweetJson =
                """
                {
                    "id_str": "1001",
                    "text": "https://t.co/link1",
                    "user": { "name": "Outer", "screen_name": "outer" },
                    "mediaDetails": [
                        { "expanded_url": "https://x.com/hop1/status/2002/video/1" }
                    ]
                }
                """.trimIndent()

            val hop1TweetJson =
                """
                {
                    "id_str": "2002",
                    "text": "Hop 1 text https://t.co/link2",
                    "user": { "name": "Hop1", "screen_name": "hop1" },
                    "mediaDetails": [
                        { "expanded_url": "https://x.com/hop2/status/3003/video/1" }
                    ]
                }
                """.trimIndent()

            var hop2Requested = false
            val client =
                createMockHttpClient { request ->
                    when (request.url.parameters["id"]) {
                        "1001" -> {
                            jsonResponse(outerTweetJson)
                        }

                        "2002" -> {
                            jsonResponse(hop1TweetJson)
                        }

                        "3003" -> {
                            hop2Requested = true
                            jsonResponse("{}", status = HttpStatusCode.NotFound)
                        }

                        else -> {
                            jsonResponse("{}", status = HttpStatusCode.NotFound)
                        }
                    }
                }

            val post = resolver.resolve("https://x.com/outer/status/1001", client)

            assertEquals("[引用 @hop1: Hop 1 text https://t.co/link2]", post.content)
            assertNotNull(post.quotedPost)
            assertEquals("2002", post.quotedPost.id)
            assertFalse(hop2Requested, "Source tweet media embed should not trigger a second hop")
            assertEquals(null, post.quotedPost.quotedPost)
        }
}
