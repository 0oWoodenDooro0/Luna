package dev.socialpeek.resolver.bilibili

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.jsonResponse
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BilibiliResolverTest {

    private val resolver = BilibiliResolver()

    @Test
    fun `canResolve should match bilibili video, opus, and b23-tv URLs`() {
        assertTrue(resolver.canResolve("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertTrue(resolver.canResolve("https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333.337"))
        assertTrue(resolver.canResolve("https://www.bilibili.com/opus/123456789012345678"))
        assertTrue(resolver.canResolve("https://t.bilibili.com/123456789012345678"))
        assertTrue(resolver.canResolve("https://b23.tv/BV1xx411c7mD"))
        assertTrue(resolver.canResolve("https://b23.tv/7zYk63w?share_source=copy_web"))
        assertFalse(resolver.canResolve("https://bilibili.com/bangumi/play/ep123"))
        assertFalse(resolver.canResolve("https://youtube.com/watch?v=123"))
    }

    @Test
    fun `resolve should parse bilibili video correctly`() = runTest {
        val videoMockJson = """
        {
            "code": 0,
            "message": "0",
            "data": {
                "bvid": "BV1xx411c7mD",
                "aid": 170001,
                "title": "【初音ミク】測試影片標題",
                "desc": "這是影片詳細說明文案",
                "pic": "https://i0.hdslb.com/bfs/archive/cover.jpg",
                "pubdate": 1600000000,
                "duration": 210,
                "owner": {
                    "mid": 9999,
                    "name": "B站知名UP主",
                    "face": "https://i0.hdslb.com/bfs/face/avatar.jpg"
                },
                "stat": {
                    "view": 123456,
                    "reply": 789,
                    "like": 10000,
                    "share": 500,
                    "favorite": 3000
                }
            }
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            assertTrue(request.url.encodedPath.contains("x/web-interface/view"))
            assertEquals("BV1xx411c7mD", request.url.parameters["bvid"])
            assertEquals("https://www.bilibili.com/", request.headers["Referer"])
            jsonResponse(videoMockJson)
        }

        val post = resolver.resolve("https://www.bilibili.com/video/BV1xx411c7mD", client)

        assertEquals(Platform.BILIBILI, post.platform)
        assertEquals("BV1xx411c7mD", post.id)
        assertEquals("【初音ミク】測試影片標題", post.title)
        assertEquals("這是影片詳細說明文案", post.content)
        assertEquals("9999", post.author.id)
        assertEquals("B站知名UP主", post.author.displayName)
        assertEquals("https://space.bilibili.com/9999", post.author.profileUrl)
        assertEquals("https://i0.hdslb.com/bfs/face/avatar.jpg", post.author.avatarUrl)

        assertEquals(1, post.media.size)
        val media = post.media.first() as Media.Video
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", media.url)
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", media.previewUrl)
        assertEquals(210.0, media.durationSeconds)

        assertEquals(10000, post.metrics?.likes)
        assertEquals(789, post.metrics?.comments)
        assertEquals(123456, post.metrics?.views)
        assertEquals(500, post.metrics?.reposts)
        assertEquals(3000, post.metrics?.bookmarks)
        assertEquals(1600000000L, post.createdAtEpochSeconds)
    }

    @Test
    fun `resolve should parse opus dynamic post with multiple pictures`() = runTest {
        val opusMockJson = """
        {
            "code": 0,
            "data": {
                "item": {
                    "id_str": "999888777",
                    "type": "DYNAMIC_TYPE_DRAW",
                    "modules": {
                        "module_author": {
                            "mid": 123456,
                            "name": "Illustrator",
                            "face": "https://i0.hdslb.com/avatar.jpg",
                            "pub_ts": 1700000000
                        },
                        "module_dynamic": {
                            "desc": { "text": "Check out my new illustrations!" },
                            "major": {
                                "opus": {
                                    "pics": [
                                        { "url": "https://i0.hdslb.com/art1.jpg", "width": 1920, "height": 1080 },
                                        { "url": "https://i0.hdslb.com/art2.jpg", "width": 1920, "height": 1080 },
                                        { "url": "https://i0.hdslb.com/art3.jpg", "width": 1920, "height": 1080 }
                                    ]
                                }
                            }
                        },
                        "module_stat": {
                            "like": { "count": 555 },
                            "comment": { "count": 66 }
                        }
                    }
                }
            }
        }
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(opusMockJson)
        }

        val post = resolver.resolve("https://www.bilibili.com/opus/999888777", client)

        assertEquals("999888777", post.id)
        assertEquals("Illustrator", post.author.displayName)
        assertEquals("Check out my new illustrations!", post.content)
        assertEquals(3, post.media.size)
        assertEquals("https://i0.hdslb.com/art1.jpg", (post.media[0] as Media.Image).url)
        assertEquals("https://i0.hdslb.com/art2.jpg", (post.media[1] as Media.Image).url)
        assertEquals("https://i0.hdslb.com/art3.jpg", (post.media[2] as Media.Image).url)
    }

    @Test
    fun `resolve should follow b23-tv short link redirect with query string`() = runTest {
        val videoMockJson = """
        {
            "code": 0,
            "data": {
                "bvid": "BV1xx411c7mD",
                "title": "Short link video",
                "desc": "Redirect worked",
                "owner": { "mid": 123, "name": "Author" },
                "stat": { "like": 10 }
            }
        }
        """.trimIndent()

        val client = createMockHttpClient { request ->
            if (request.url.host == "b23.tv") {
                respond(
                    content = "",
                    status = HttpStatusCode.MovedPermanently,
                    headers = headersOf(HttpHeaders.Location, "https://www.bilibili.com/video/BV1xx411c7mD/")
                )
            } else {
                jsonResponse(videoMockJson)
            }
        }

        val post = resolver.resolve("https://b23.tv/7zYk63w?share_source=copy_web", client)
        assertEquals("BV1xx411c7mD", post.id)
        assertEquals("Short link video", post.title)
    }

    @Test
    fun `resolve should throw PostNotFoundException when video is not found or code is non-zero`() = runTest {
        val errorMockJson = """
        {
            "code": -404,
            "message": "啥都没有"
        }
        """.trimIndent()

        val client = createMockHttpClient {
            jsonResponse(errorMockJson)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://www.bilibili.com/video/BV1000000000", client)
        }
    }
}
