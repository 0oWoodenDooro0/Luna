package dev.socialpeek.resolver.youtube

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.jsonResponse
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `resolve should parse youtube video successfully and populate cleanUrl`() =
        runTest {
            val oembedJson =
                """
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

            val client =
                createMockHttpClient { request ->
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
    fun `resolve should throw PostNotFoundException on 404 or bad oembed`() =
        runTest {
            val client =
                createMockHttpClient {
                    jsonResponse("Not Found", status = HttpStatusCode.NotFound)
                }

            assertFailsWith<PostNotFoundException> {
                resolver.resolve("https://www.youtube.com/watch?v=00000000000", client)
            }
        }

    @Test
    fun `resolve should query YouTube Data API when apiKey is provided, parsing duration and comments`() =
        runTest {
            val resolverWithKey = YouTubeResolver(apiKey = "TEST_API_KEY")

            val videosJson =
                """
                {
                  "items": [
                    {
                      "snippet": {
                        "title": "Rick Astley - Never Gonna Give You Up",
                        "channelTitle": "RickAstleyVEVO",
                        "channelId": "UCuAXFkgsw1L7xaCfnd5JJOw"
                      },
                      "contentDetails": {
                        "duration": "PT3M33S"
                      }
                    }
                  ]
                }
                """.trimIndent()

            val commentsJson =
                """
                {
                  "items": [
                    {
                      "snippet": {
                        "topLevelComment": {
                          "snippet": {
                            "authorDisplayName": "Viewer1",
                            "textDisplay": "This song will never get old!",
                            "likeCount": 12500
                          }
                        }
                      }
                    },
                    {
                      "snippet": {
                        "topLevelComment": {
                          "snippet": {
                            "authorDisplayName": "Viewer2",
                            "textDisplay": "A true masterpiece.",
                            "likeCount": 850
                          }
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("/videos") -> {
                            assertTrue(request.url.parameters["key"] == "TEST_API_KEY")
                            assertTrue(request.url.parameters["id"] == "dQw4w9WgXcQ")
                            jsonResponse(videosJson)
                        }

                        request.url.encodedPath.contains("/commentThreads") -> {
                            assertTrue(request.url.parameters["key"] == "TEST_API_KEY")
                            assertTrue(request.url.parameters["videoId"] == "dQw4w9WgXcQ")
                            jsonResponse(commentsJson)
                        }

                        else -> {
                            jsonResponse("Not found", status = HttpStatusCode.NotFound)
                        }
                    }
                }

            val post = resolverWithKey.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", client)

            assertEquals("Rick Astley - Never Gonna Give You Up", post.title)
            assertEquals("RickAstleyVEVO", post.author.displayName)
            assertEquals("213", post.rawData["durationSeconds"])
            assertEquals(2, post.comments.size)
            assertEquals("Viewer1", post.comments[0].author)
            assertEquals("This song will never get old!", post.comments[0].content)
            assertEquals(12500L, post.comments[0].score)
            assertEquals("Viewer2", post.comments[1].author)
            assertEquals("A true masterpiece.", post.comments[1].content)
            assertEquals(850L, post.comments[1].score)
        }

    @Test
    fun `resolve should fallback to oEmbed when Data API returns 403 quota exceeded`() =
        runTest {
            val resolverWithKey = YouTubeResolver(apiKey = "EXHAUSTED_KEY")
            val oembedJson =
                """
                {
                    "title": "Fallback Oembed Title",
                    "author_name": "Fallback Creator",
                    "author_url": "https://www.youtube.com/@FallbackCreator"
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    if (request.url.encodedPath.contains("/videos")) {
                        jsonResponse("Quota Exceeded", status = HttpStatusCode.Forbidden)
                    } else if (request.url.host.contains("youtube.com") && request.url.encodedPath.contains("/oembed")) {
                        jsonResponse(oembedJson)
                    } else {
                        jsonResponse("Not Found", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolverWithKey.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", client)

            assertEquals("Fallback Oembed Title", post.title)
            assertEquals("Fallback Creator", post.author.displayName)
            assertTrue(post.comments.isEmpty())
            assertTrue(post.rawData.isEmpty())
        }

    @Test
    fun `resolve should return video info with empty comments when commentThreads fails`() =
        runTest {
            val resolverWithKey = YouTubeResolver(apiKey = "TEST_API_KEY")

            val videosJson =
                """
                {
                  "items": [
                    {
                      "snippet": {
                        "title": "Video With Comments Disabled",
                        "channelTitle": "Creator",
                        "channelId": "UC123"
                      },
                      "contentDetails": {
                        "duration": "PT1M0S"
                      }
                    }
                  ]
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("/videos") -> jsonResponse(videosJson)
                        request.url.encodedPath.contains("/commentThreads") -> jsonResponse("Comments Disabled", status = HttpStatusCode.Forbidden)
                        else -> jsonResponse("Not found", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolverWithKey.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", client)

            assertEquals("Video With Comments Disabled", post.title)
            assertEquals("60", post.rawData["durationSeconds"])
            assertTrue(post.comments.isEmpty())
        }

    @Test
    fun `resolve should extract video description from Data API and populate content and rawData description`() =
        runTest {
            val resolverWithKey = YouTubeResolver(apiKey = "TEST_API_KEY")

            val videosJson =
                """
                {
                  "items": [
                    {
                      "snippet": {
                        "title": "Rick Astley - Never Gonna Give You Up",
                        "description": "Official music video for Rick Astley - Never Gonna Give You Up.",
                        "channelTitle": "RickAstleyVEVO",
                        "channelId": "UCuAXFkgsw1L7xaCfnd5JJOw"
                      },
                      "contentDetails": {
                        "duration": "PT3M33S"
                      }
                    }
                  ]
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("/videos") -> jsonResponse(videosJson)
                        request.url.encodedPath.contains("/commentThreads") -> jsonResponse("""{"items":[]}""")
                        else -> jsonResponse("Not found", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolverWithKey.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", client)

            assertEquals("Rick Astley - Never Gonna Give You Up", post.title)
            assertEquals("Official music video for Rick Astley - Never Gonna Give You Up.", post.content)
            assertEquals("Official music video for Rick Astley - Never Gonna Give You Up.", post.rawData["description"])
        }

    @Test
    fun `resolve should sanitize promotional and affiliate URLs and tracking links while retaining chapters and outlines`() =
        runTest {
            val resolverWithKey = YouTubeResolver(apiKey = "TEST_API_KEY")

            val rawDescription =
                """
                In this video, we explore Kotlin Coroutines and StateFlow in depth.

                Chapters:
                00:00 - Introduction
                02:15 - Coroutine Scope & Job
                05:30 - Flow vs StateFlow
                08:45 - Live Demo
                12:00 - Summary

                Reference documentation: https://kotlinlang.org/docs/coroutines-overview.html?utm_source=youtube&utm_medium=video

                Follow me on Twitter: https://twitter.com/kotlincreator
                Follow my Instagram: https://instagram.com/kotlincreator
                Join our Discord: https://discord.gg/kotlindev
                Support on Patreon: https://patreon.com/kotlincreator
                Buy my book (affiliate): https://amzn.to/3example
                Check sponsor: https://brand.com/deal?affiliate_id=999&utm_campaign=winter
                Shortlink promo: https://bit.ly/secretdeal
                """.trimIndent()

            val videosJson =
                buildJsonObject {
                    put(
                        "items",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "snippet",
                                        buildJsonObject {
                                            put("title", "Kotlin Coroutines Deep Dive")
                                            put("description", rawDescription)
                                            put("channelTitle", "Kotlin Devs")
                                            put("channelId", "UC123")
                                        },
                                    )
                                    put(
                                        "contentDetails",
                                        buildJsonObject {
                                            put("duration", "PT12M0S")
                                        },
                                    )
                                },
                            )
                        },
                    )
                }.toString()

            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("/videos") -> jsonResponse(videosJson)
                        request.url.encodedPath.contains("/commentThreads") -> jsonResponse("""{"items":[]}""")
                        else -> jsonResponse("Not found", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolverWithKey.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", client)

            assertEquals("Kotlin Coroutines Deep Dive", post.title)
            val content = post.content
            assertTrue(content.contains("In this video, we explore Kotlin Coroutines and StateFlow in depth."))
            assertTrue(content.contains("Chapters:"))
            assertTrue(content.contains("00:00 - Introduction"))
            assertTrue(content.contains("02:15 - Coroutine Scope & Job"))
            assertTrue(content.contains("05:30 - Flow vs StateFlow"))
            assertTrue(content.contains("08:45 - Live Demo"))
            assertTrue(content.contains("12:00 - Summary"))
            assertTrue(content.contains("https://kotlinlang.org/docs/coroutines-overview.html"))
            assertFalse(content.contains("utm_source"))
            assertFalse(content.contains("https://twitter.com"))
            assertFalse(content.contains("https://instagram.com"))
            assertFalse(content.contains("https://discord.gg"))
            assertFalse(content.contains("https://patreon.com"))
            assertFalse(content.contains("https://amzn.to"))
            assertFalse(content.contains("https://bit.ly"))
            assertFalse(content.contains("https://brand.com"))
            assertEquals(content, post.rawData["description"])
        }

    @Test
    fun `resolve should cap description length to 1000 characters while retaining chapter markers and timestamps`() =
        runTest {
            val resolverWithKey = YouTubeResolver(apiKey = "TEST_API_KEY")

            val longIntro = "A".repeat(1200)
            val rawDescription =
                """
                Overview of the system:
                $longIntro

                Timestamps:
                00:00 - Introduction
                05:30 - Architecture Deep Dive
                10:45 - Live Benchmarking
                15:00 - Conclusion
                """.trimIndent()

            val videosJson =
                buildJsonObject {
                    put(
                        "items",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put(
                                        "snippet",
                                        buildJsonObject {
                                            put("title", "Massive Video with End Timestamps")
                                            put("description", rawDescription)
                                            put("channelTitle", "Tech Channel")
                                            put("channelId", "UC999")
                                        },
                                    )
                                    put(
                                        "contentDetails",
                                        buildJsonObject {
                                            put("duration", "PT15M0S")
                                        },
                                    )
                                },
                            )
                        },
                    )
                }.toString()

            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("/videos") -> jsonResponse(videosJson)
                        request.url.encodedPath.contains("/commentThreads") -> jsonResponse("""{"items":[]}""")
                        else -> jsonResponse("Not found", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolverWithKey.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", client)

            assertTrue(post.content.length <= 1000, "Content length ${post.content.length} must be <= 1000")
            assertTrue(post.content.contains("00:00 - Introduction"))
            assertTrue(post.content.contains("05:30 - Architecture Deep Dive"))
            assertTrue(post.content.contains("10:45 - Live Benchmarking"))
            assertTrue(post.content.contains("15:00 - Conclusion"))
            assertEquals(post.content, post.rawData["description"])
        }

    @Test
    fun `resolve should cleanly fall back to title when description is missing or blank`() =
        runTest {
            val resolverWithKey = YouTubeResolver(apiKey = "TEST_API_KEY")

            val videosJson =
                """
                {
                  "items": [
                    {
                      "snippet": {
                        "title": "Blank Description Video",
                        "description": "   \n\n  ",
                        "channelTitle": "Creator",
                        "channelId": "UC123"
                      },
                      "contentDetails": {
                        "duration": "PT1M0S"
                      }
                    }
                  ]
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("/videos") -> jsonResponse(videosJson)
                        request.url.encodedPath.contains("/commentThreads") -> jsonResponse("""{"items":[]}""")
                        else -> jsonResponse("Not found", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolverWithKey.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", client)

            assertEquals("Blank Description Video", post.title)
            assertEquals("Blank Description Video", post.content)
            assertNull(post.rawData["description"])
        }

    @Test
    fun `resolve should cleanly fall back to title when description is sanitized to empty`() =
        runTest {
            val resolverWithKey = YouTubeResolver(apiKey = "TEST_API_KEY")

            val videosJson =
                """
                {
                  "items": [
                    {
                      "snippet": {
                        "title": "Only Promo Links Video",
                        "description": "Follow me on Twitter: https://twitter.com/creator\nJoin Discord: https://discord.gg/abc\nhttps://amzn.to/promo",
                        "channelTitle": "Creator",
                        "channelId": "UC123"
                      },
                      "contentDetails": {
                        "duration": "PT1M0S"
                      }
                    }
                  ]
                }
                """.trimIndent()

            val client =
                createMockHttpClient { request ->
                    when {
                        request.url.encodedPath.contains("/videos") -> jsonResponse(videosJson)
                        request.url.encodedPath.contains("/commentThreads") -> jsonResponse("""{"items":[]}""")
                        else -> jsonResponse("Not found", status = HttpStatusCode.NotFound)
                    }
                }

            val post = resolverWithKey.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ", client)

            assertEquals("Only Promo Links Video", post.title)
            assertEquals("Only Promo Links Video", post.content)
            assertNull(post.rawData["description"])
        }
}
