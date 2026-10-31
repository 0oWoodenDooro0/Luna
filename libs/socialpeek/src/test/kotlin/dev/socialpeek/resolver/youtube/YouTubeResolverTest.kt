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
}
