package dev.socialpeek.resolver.bilibili

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
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

class BilibiliCollectionAndSeriesResolverTest {
    private val resolver = BilibiliResolver()

    @Test
    fun `resolve should parse bilibili collection with placeholder cover fallback, https upgrade, creator enrichment, and 5-line summary`() =
        runTest {
            val seasonMockJson =
                """
                {
                    "code": 0,
                    "data": {
                        "info": {
                            "id": 678,
                            "title": "測試合集標題",
                            "cover": "http://i0.hdslb.com/bfs/archive/viedeo_material_default.png",
                            "intro": "這是一個合集簡介",
                            "media_count": 12,
                            "upper": { "mid": 12345, "name": "舊作者名稱" },
                            "cnt_info": { "play": 13200000, "collect": 450, "danmaku": 800 }
                        },
                        "medias": [
                            {
                                "bvid": "BV1aa1111111",
                                "title": "第一集標題",
                                "cover": "http://i0.hdslb.com/bfs/archive/first_cover.jpg",
                                "duration": 180,
                                "pubtime": 1700000100,
                                "upper": { "mid": 12345, "name": "舊作者名稱" }
                            },
                            { "bvid": "BV1bb2222222", "title": "第二集標題", "cover": "http://i0.hdslb.com/second.jpg" },
                            { "bvid": "BV1cc3333333", "title": "第三集標題", "cover": "http://i0.hdslb.com/third.jpg" },
                            { "bvid": "BV1dd4444444", "title": "第四集標題", "cover": "http://i0.hdslb.com/fourth.jpg" }
                        ]
                    }
                }
                """.trimIndent()

            val viewMockJson =
                """
                {
                    "code": 0,
                    "data": {
                        "bvid": "BV1aa1111111",
                        "owner": {
                            "mid": 12345,
                            "name": "精選UP主",
                            "face": "http://i0.hdslb.com/bfs/face/enriched.jpg"
                        }
                    }
                }
                """.trimIndent()

            var viewLookupCount = 0
            val client =
                createMockHttpClient { request ->
                    assertEquals(
                        "SocialPeek/1.0 (https://github.com/0oWoodenDooro0/SocialPeek)",
                        request.headers[HttpHeaders.UserAgent],
                    )
                    when {
                        request.url.encodedPath.contains("x/space/fav/season/list") -> {
                            assertEquals("678", request.url.parameters["season_id"])
                            jsonResponse(seasonMockJson)
                        }

                        request.url.encodedPath.contains("x/web-interface/view") -> {
                            viewLookupCount++
                            assertEquals("BV1aa1111111", request.url.parameters["bvid"])
                            jsonResponse(viewMockJson)
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }

            val post =
                resolver.resolve(
                    "https://space.bilibili.com/12345/channel/collectiondetail?sid=678&spm_id_from=333",
                    client,
                )

            assertEquals(1, viewLookupCount)
            assertEquals("678", post.id)
            assertEquals("https://space.bilibili.com/12345/channel/collectiondetail/?sid=678", post.cleanUrl)
            assertEquals("測試合集標題", post.title)
            assertEquals("精選UP主", post.author.displayName)
            assertEquals("https://i0.hdslb.com/bfs/face/enriched.jpg", post.author.avatarUrl)
            assertEquals("https://space.bilibili.com/12345/", post.author.profileUrl)

            assertEquals(1, post.media.size)
            val cover = post.media.single() as Media.Image
            assertEquals("https://i0.hdslb.com/bfs/archive/first_cover.jpg", cover.url)

            val lines = post.content.lines()
            assertTrue(lines.size <= 5)
            assertTrue(post.content.length <= 250)
            assertEquals("📚 共 12 部作品 • ▶️ 13.2M 播放", lines[0])
            assertEquals("這是一個合集簡介", lines[1])
            assertEquals("• 第一集標題", lines[2])
            assertEquals("• 第二集標題", lines[3])
            assertEquals("• 第三集標題", lines[4])
            assertFalse(post.content.contains("第四集標題"))
            assertEquals(13200000L, post.metrics?.views)
            assertEquals(450L, post.metrics?.bookmarks)
            assertEquals(1700000100L, post.createdAtEpochSeconds)
        }

    @Test
    fun `resolve should degrade gracefully when creator enrichment fails on collection`() =
        runTest {
            val seasonMockJson =
                """
                {
                    "code": 0,
                    "data": {
                        "info": {
                            "id": 678,
                            "title": "無頭像合集",
                            "cover": "https://i0.hdslb.com/bfs/archive/real_cover.jpg",
                            "intro": "",
                            "media_count": 1,
                            "upper": { "mid": 12345, "name": "合集作者" },
                            "cnt_info": { "play": 500 }
                        },
                        "medias": [
                            { "bvid": "BV1aa1111111", "title": "單集影片" }
                        ]
                    }
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    if (request.url.encodedPath.contains("x/space/fav/season/list")) {
                        jsonResponse(seasonMockJson)
                    } else {
                        respondError(HttpStatusCode.InternalServerError)
                    }
                }

            val post = resolver.resolve("https://space.bilibili.com/12345/lists/678?type=season", client)
            assertEquals("合集作者", post.author.displayName)
            assertEquals(null, post.author.avatarUrl)
            assertEquals("https://space.bilibili.com/12345/lists/678/?type=season", post.cleanUrl)
            assertEquals("📚 共 1 部作品 • ▶️ 500 播放\n• 單集影片", post.content)
        }

    @Test
    fun `resolve should parse bilibili seriesdetail using series metadata and archives APIs`() =
        runTest {
            val seriesMetaJson =
                """
                {
                    "code": 0,
                    "data": {
                        "meta": {
                            "series_id": 888,
                            "mid": 54321,
                            "name": "Kotlin 教學系列",
                            "description": "從零開始學 Kotlin",
                            "total": 4,
                            "last_update_ts": 1700000500
                        }
                    }
                }
                """.trimIndent()

            val seriesArchivesJson =
                """
                {
                    "code": 0,
                    "data": {
                        "page": { "total": 4 },
                        "archives": [
                            { "bvid": "BV1sr1111111", "title": "EP1 環境建置", "pic": "http://i0.hdslb.com/ep1.jpg" },
                            { "bvid": "BV1sr2222222", "title": "EP2 語法基礎", "pic": "http://i0.hdslb.com/ep2.jpg" },
                            { "bvid": "BV1sr3333333", "title": "EP3 協程實戰", "pic": "http://i0.hdslb.com/ep3.jpg" },
                            { "bvid": "BV1sr4444444", "title": "EP4 測試框架", "pic": "http://i0.hdslb.com/ep4.jpg" }
                        ]
                    }
                }
                """.trimIndent()

            val viewJson =
                """
                {
                    "code": 0,
                    "data": {
                        "bvid": "BV1sr1111111",
                        "owner": {
                            "mid": 54321,
                            "name": "系列講師",
                            "face": "http://i0.hdslb.com/teacher.jpg"
                        }
                    }
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("x/series/series") -> {
                            assertEquals("888", request.url.parameters["series_id"])
                            jsonResponse(seriesMetaJson)
                        }

                        request.url.encodedPath.contains("x/series/archives") -> {
                            assertEquals("54321", request.url.parameters["mid"])
                            assertEquals("888", request.url.parameters["series_id"])
                            assertEquals("true", request.url.parameters["only_normal"])
                            assertEquals("desc", request.url.parameters["sort"])
                            assertEquals("1", request.url.parameters["pn"])
                            assertEquals("10", request.url.parameters["ps"])
                            jsonResponse(seriesArchivesJson)
                        }

                        request.url.encodedPath.contains("x/web-interface/view") -> {
                            assertEquals("BV1sr1111111", request.url.parameters["bvid"])
                            jsonResponse(viewJson)
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }

            val post = resolver.resolve("https://space.bilibili.com/54321/channel/seriesdetail?sid=888&buvid=xyz", client)

            assertEquals("888", post.id)
            assertEquals("https://space.bilibili.com/54321/channel/seriesdetail/?sid=888", post.cleanUrl)
            assertEquals("Kotlin 教學系列", post.title)
            assertEquals("系列講師", post.author.displayName)
            assertEquals("https://i0.hdslb.com/teacher.jpg", post.author.avatarUrl)
            assertEquals("https://space.bilibili.com/54321/", post.author.profileUrl)
            assertEquals(1, post.media.size)
            assertEquals("https://i0.hdslb.com/ep1.jpg", (post.media.single() as Media.Image).url)
            assertEquals(
                "📚 共 4 部作品\n從零開始學 Kotlin\n• EP1 環境建置\n• EP2 語法基礎\n• EP3 協程實戰",
                post.content,
            )
            assertEquals(1700000500L, post.createdAtEpochSeconds)
        }

    @Test
    fun `resolve should try season first then fall back to series on type-less lists URL`() =
        runTest {
            val emptyOrErrorSeasonJson = """{ "code": -404, "message": "not a season" }"""
            val seriesMetaJson =
                """
                {
                    "code": 0,
                    "data": {
                        "meta": {
                            "series_id": 777,
                            "mid": 54321,
                            "name": "Fallback 系列",
                            "description": "",
                            "total": 1
                        }
                    }
                }
                """.trimIndent()
            val seriesArchivesJson =
                """
                {
                    "code": 0,
                    "data": {
                        "page": { "total": 1 },
                        "archives": [
                            { "bvid": "BV1fb1111111", "title": "Fallback 影片", "pic": "https://i0.hdslb.com/fb.jpg" }
                        ]
                    }
                }
                """.trimIndent()

            var seasonCalled = false
            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("x/space/fav/season/list") -> {
                            seasonCalled = true
                            jsonResponse(emptyOrErrorSeasonJson)
                        }

                        request.url.encodedPath.contains("x/series/series") -> {
                            jsonResponse(seriesMetaJson)
                        }

                        request.url.encodedPath.contains("x/series/archives") -> {
                            jsonResponse(seriesArchivesJson)
                        }

                        request.url.encodedPath.contains("x/web-interface/view") -> {
                            respondError(HttpStatusCode.NotFound)
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }

            val post = resolver.resolve("https://space.bilibili.com/54321/lists/777", client)
            assertTrue(seasonCalled)
            assertEquals("777", post.id)
            assertEquals("Fallback 系列", post.title)
            assertEquals("UID 54321", post.author.displayName)
            assertEquals(null, post.author.avatarUrl)
            assertEquals("https://space.bilibili.com/54321/lists/777/", post.cleanUrl)
            assertEquals("📚 共 1 部作品\n• Fallback 影片", post.content)
        }

    @Test
    fun `resolve should not fall back to series when type=season is explicit and should reject missing sid`() =
        runTest {
            var seriesCalled = false
            val client =
                createMockHttpClient { request ->
                    if (request.url.encodedPath.contains("x/series/")) {
                        seriesCalled = true
                    }
                    jsonResponse("""{ "code": -404, "message": "not found" }""")
                }

            assertFailsWith<PostNotFoundException> {
                resolver.resolve("https://space.bilibili.com/12345/lists/999?type=season", client)
            }
            assertFalse(seriesCalled)

            assertFalse(resolver.canResolve("https://space.bilibili.com/12345/channel/collectiondetail"))
            assertFailsWith<ParsingException> {
                resolver.resolve("https://space.bilibili.com/12345/channel/collectiondetail", client)
            }
        }

    @Test
    fun `resolve should clamp total summary content to 250 chars when episode titles are very long`() =
        runTest {
            val longTitle = "超長影片標題測試內容".repeat(12)
            val seasonMockJson =
                """
                {
                    "code": 0,
                    "data": {
                        "info": {
                            "id": 678,
                            "title": "長標題合集",
                            "cover": "https://i0.hdslb.com/cover.jpg",
                            "intro": "${"簡介內容".repeat(30)}",
                            "media_count": 3,
                            "upper": { "mid": 12345, "name": "UP主" }
                        },
                        "medias": [
                            { "bvid": "BV1aa1111111", "title": "$longTitle" },
                            { "bvid": "BV1bb2222222", "title": "$longTitle" },
                            { "bvid": "BV1cc3333333", "title": "$longTitle" }
                        ]
                    }
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    if (request.url.encodedPath.contains("x/space/fav/season/list")) {
                        jsonResponse(seasonMockJson)
                    } else {
                        respondError(HttpStatusCode.NotFound)
                    }
                }

            val post = resolver.resolve("https://space.bilibili.com/12345/channel/collectiondetail?sid=678", client)
            assertTrue(post.content.length <= 250)
            assertTrue(post.content.endsWith("..."))
        }
}
