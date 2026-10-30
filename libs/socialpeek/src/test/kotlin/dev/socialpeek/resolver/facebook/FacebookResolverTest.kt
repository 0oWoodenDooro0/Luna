package dev.socialpeek.resolver.facebook

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FacebookResolverTest {

    private val resolver = FacebookResolver()

    @Test
    fun `canResolve should match valid Facebook post, video, reel, and photo URLs`() {
        // Posts
        assertTrue(resolver.canResolve("https://www.facebook.com/zuck/posts/pfbid02517vL7rZ9kFq71k51aP6k4R7Y6oBw8m"))
        assertTrue(resolver.canResolve("https://www.facebook.com/Meta/posts/10159496668744991"))
        assertTrue(resolver.canResolve("https://facebook.com/posts/pfbid12345"))
        assertTrue(resolver.canResolve("https://m.facebook.com/zuck/posts/123456789"))

        // Reels & Videos
        assertTrue(resolver.canResolve("https://www.facebook.com/reel/10153231379946729"))
        assertTrue(resolver.canResolve("https://www.facebook.com/watch/?v=10153231379946729"))
        assertTrue(resolver.canResolve("https://www.facebook.com/facebook/videos/10153231379946729/"))
        assertTrue(resolver.canResolve("https://fb.watch/m4abc123/"))

        // Photos
        assertTrue(resolver.canResolve("https://www.facebook.com/photo.php?fbid=10153231379946729"))
        assertTrue(resolver.canResolve("https://www.facebook.com/photo/?fbid=10153231379946729"))
        assertTrue(resolver.canResolve("https://www.facebook.com/zuck/photos/10153231379946729"))

        // Groups
        assertTrue(resolver.canResolve("https://www.facebook.com/groups/kotlin.lang/posts/123456789/"))
        assertTrue(resolver.canResolve("https://www.facebook.com/groups/123456789/permalink/987654321/"))

        // Permalinks & Stories
        assertTrue(resolver.canResolve("https://www.facebook.com/permalink.php?story_fbid=12345&id=67890"))
        assertTrue(resolver.canResolve("https://www.facebook.com/story.php?story_fbid=12345&id=67890"))

        // Shares
        assertTrue(resolver.canResolve("https://www.facebook.com/share/p/123456/"))
        assertTrue(resolver.canResolve("https://www.facebook.com/share/v/123456/"))
        assertTrue(resolver.canResolve("https://www.facebook.com/share/123456/"))
        assertTrue(resolver.canResolve("https://fb.com/share/123456/"))
    }

    @Test
    fun `canResolve should reject non-Facebook URLs`() {
        assertFalse(resolver.canResolve("https://www.instagram.com/p/CuP48CiS5sx"))
        assertFalse(resolver.canResolve("https://x.com/zuck/status/123456"))
        assertFalse(resolver.canResolve("https://threads.net/@zuck/post/123456"))
        assertFalse(resolver.canResolve("https://www.youtube.com/watch?v=12345"))
    }

    @Test
    fun `extractPostId should extract correct IDs from diverse Facebook URL formats`() {
        assertEquals("pfbid02517vL7rZ9k", resolver.extractPostId("https://www.facebook.com/zuck/posts/pfbid02517vL7rZ9k"))
        assertEquals("10159496668744991", resolver.extractPostId("https://www.facebook.com/Meta/posts/10159496668744991"))
        assertEquals("10153231379946729", resolver.extractPostId("https://www.facebook.com/watch/?v=10153231379946729"))
        assertEquals("10153231379946729", resolver.extractPostId("https://www.facebook.com/reel/10153231379946729"))
        assertEquals("10153231379946729", resolver.extractPostId("https://www.facebook.com/photo.php?fbid=10153231379946729"))
        assertEquals("99887766", resolver.extractPostId("https://www.facebook.com/permalink.php?story_fbid=99887766&id=1234"))
        assertEquals("55443322", resolver.extractPostId("https://www.facebook.com/groups/devgroup/posts/55443322/"))
        assertEquals("abcxyz123", resolver.extractPostId("https://fb.watch/abcxyz123/"))
        assertEquals("share123", resolver.extractPostId("https://www.facebook.com/share/p/share123/"))
    }

    @Test
    fun `resolve should extract post info from standard Facebook post page`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Mark Zuckerberg - Building open source AI models for everyone | Facebook" />
            <meta property="og:description" content="Today we are releasing Llama 3 open source. Looking forward to what you build with it." />
            <meta property="og:image" content="https://scontent.xx.fbcdn.net/v/t39.30808-6/llama_launch.jpg" />
            <meta property="og:image:width" content="1200" />
            <meta property="og:image:height" content="630" />
            <meta property="og:url" content="https://www.facebook.com/zuck/posts/pfbid02517vL7rZ9kFq71k51aP6k4R7Y6oBw8m" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.facebook.com/zuck/posts/pfbid02517vL7rZ9kFq71k51aP6k4R7Y6oBw8m", client)

        assertEquals(Platform.FACEBOOK, post.platform)
        assertEquals("pfbid02517vL7rZ9kFq71k51aP6k4R7Y6oBw8m", post.id)
        assertEquals("zuck", post.author.username)
        assertEquals("Mark Zuckerberg", post.author.displayName)
        assertEquals("https://www.facebook.com/zuck", post.author.profileUrl)
        assertEquals("Today we are releasing Llama 3 open source. Looking forward to what you build with it.", post.content)
        assertEquals("Building open source AI models for everyone", post.title)
        assertEquals(1, post.media.size)
        val image = post.media.first() as Media.Image
        assertEquals("https://scontent.xx.fbcdn.net/v/t39.30808-6/llama_launch.jpg", image.url)
        assertEquals(1200, image.width)
        assertEquals(630, image.height)
    }

    @Test
    fun `resolve should parse video and reel with metrics`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:type" content="video.other" />
            <meta property="og:title" content="2.8M views · 1.2K reactions | How to share with just friends | Facebook" />
            <meta property="og:description" content="A quick guide on setting privacy for your Facebook posts." />
            <meta property="og:image" content="https://scontent.xx.fbcdn.net/video_thumb.jpg" />
            <meta property="og:video" content="https://video.xx.fbcdn.net/how_to_share.mp4" />
            <meta property="og:video:width" content="1920" />
            <meta property="og:video:height" content="1080" />
            <meta property="og:url" content="https://www.facebook.com/facebook/videos/10153231379946729/" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.facebook.com/reel/10153231379946729", client)

        assertEquals(Platform.FACEBOOK, post.platform)
        assertEquals("10153231379946729", post.id)
        assertEquals("facebook", post.author.username)
        assertEquals("How to share with just friends", post.title)
        assertEquals("A quick guide on setting privacy for your Facebook posts.", post.content)
        assertNotNull(post.metrics)
        assertEquals(2_800_000L, post.metrics.views)
        assertEquals(1_200L, post.metrics.likes)

        assertEquals(1, post.media.size)
        val video = post.media.first() as Media.Video
        assertEquals("https://video.xx.fbcdn.net/how_to_share.mp4", video.url)
        assertEquals("https://scontent.xx.fbcdn.net/video_thumb.jpg", video.previewUrl)
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
    }

    @Test
    fun `resolve should extract group name as community for Facebook group post`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Jane Doe in Kotlin Developers | Facebook" />
            <meta property="og:description" content="Check out the new Kotlin 2.1 features!" />
            <meta property="og:image" content="https://scontent.xx.fbcdn.net/photo.jpg" />
            <meta property="og:url" content="https://www.facebook.com/groups/kotlin.lang/posts/123456789/" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        val post = resolver.resolve("https://www.facebook.com/groups/kotlin.lang/posts/123456789/", client)

        assertEquals("123456789", post.id)
        assertEquals("Jane Doe", post.author.displayName)
        assertEquals("kotlin.lang", post.community)
        assertEquals("Check out the new Kotlin 2.1 features!", post.content)
    }

    @Test
    fun `resolve should follow redirect for Facebook share link`() = runTest {
        val finalPostHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:title" content="Meta | Facebook" />
            <meta property="og:description" content="Introducing Quest 3S" />
            <meta property="og:image" content="https://scontent.xx.fbcdn.net/quest.jpg" />
            <meta property="og:url" content="https://www.facebook.com/Meta/posts/998877" />
        </head>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            when (it.url.encodedPath) {
                "/share/p/share123/" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://www.facebook.com/Meta/posts/998877")
                )
                else -> htmlResponse(finalPostHtml)
            }
        }

        val post = resolver.resolve("https://www.facebook.com/share/p/share123/", client)
        assertEquals("998877", post.id)
        assertEquals("Meta", post.author.displayName)
        assertEquals("Introducing Quest 3S", post.content)
    }

    @Test
    fun `resolve should follow redirect for Facebook share video link and strip share_url`() = runTest {
        val reelHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta property="og:type" content="video.other" />
            <meta property="og:title" content="119K views · 633 reactions | 當前版本T1上路布蕾爾 | Facebook" />
            <meta property="og:description" content="當前版本T1上路布蕾爾｜不需要任何操作！玩就上分！" />
            <meta property="og:image" content="https://scontent.xx.fbcdn.net/thumb.jpg" />
            <meta property="og:video" content="https://video.xx.fbcdn.net/reel.mp4" />
            <meta property="og:url" content="https://www.facebook.com/reel/832727859062012/" />
            <link rel="alternate" type="application/json+oembed" href="https://graph.facebook.com/v26.0/oembed_video?url=https%3A%2F%2Fwww.facebook.com%2FlolSEA2016%2Fvideos%2F832727859062012%2F" />
        </head>
        </html>
        """.trimIndent()

        val oembedJson = """
        {
            "html": "<blockquote cite=\"https://www.facebook.com/lolSEA2016/videos/832727859062012/\"><a href=\"https://www.facebook.com/lolSEA2016/videos/832727859062012/\">布蕾爾</a>Posted by <a href=\"https://www.facebook.com/lolSEA2016\">League of Legends • 英雄联盟• Lolsea</a> on Thursday</blockquote>"
        }
        """.trimIndent()

        val client = createMockHttpClient {
            when (it.url.encodedPath) {
                "/share/v/18L6FcWfXE/" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "https://www.facebook.com/reel/832727859062012/?share_url=https%3A%2F%2Fwww.facebook.com%2Fshare%2Fv%2F18L6FcWfXE%2F%3Fmibextid%3DwwXIfr&rdid=N4KhDE7UhnTStU5V"
                    )
                )
                "/v26.0/oembed_video" -> respond(
                    content = oembedJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> htmlResponse(reelHtml)
            }
        }

        val post = resolver.resolve("https://www.facebook.com/share/v/18L6FcWfXE/?mibextid=wwXIfr", client)
        assertEquals("832727859062012", post.id)
        assertEquals("https://www.facebook.com/reel/832727859062012/", post.cleanUrl)
        assertFalse(post.cleanUrl.contains("share_url"))
        assertFalse(post.cleanUrl.contains("mibextid"))
        assertEquals("League of Legends • 英雄联盟• Lolsea", post.author.displayName)
        assertEquals("lolSEA2016", post.author.username)
        assertEquals("https://www.facebook.com/lolSEA2016", post.author.profileUrl)
    }

    @Test
    fun `resolve should throw PostNotFoundException when post is unavailable or private`() = runTest {
        val pageHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Facebook – log in or sign up</title>
        </head>
        <body>
            <div id="login_form">Log In</div>
        </body>
        </html>
        """.trimIndent()

        val client = createMockHttpClient {
            htmlResponse(pageHtml)
        }

        assertFailsWith<PostNotFoundException> {
            resolver.resolve("https://www.facebook.com/private/posts/123456", client)
        }
    }
}
